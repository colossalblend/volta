import Foundation
import NetworkExtension
import SwiftUI
import UIKit

enum VpnState: Equatable {
    case disconnected, connecting, connected, disconnecting, error
}

/// What a connect is doing before the tunnel is up, for the line under the disc.
enum ConnectPhase: Equatable {
    case idle, probing, measuring, starting

    var caption: String {
        switch self {
        case .idle: return ""
        case .probing: return L("phase.probing")
        case .measuring: return L("phase.measuring")
        case .starting: return L("phase.starting")
        }
    }
}

enum AppTab: Int, CaseIterable {
    case home, servers, routing, subscriptions, settings
}

/// "Why this server": the finalists of the last auto pick.
struct PickSummary: Equatable {
    struct Row: Equatable {
        var name: String
        var ms: Int
        var chosen: Bool
    }
    var rows: [Row]
    var measured: Int
    var alive: Int
    var at: Date
}

struct Traffic: Equatable {
    var uplink: Int64 = 0
    var downlink: Int64 = 0
    var upSpeed: Int64 = 0
    var downSpeed: Int64 = 0
}

struct SpeedPoint: Equatable {
    var down: Int64
    var up: Int64
}

struct UserError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

/// The app's state and everything the screens can ask for. A port of the Android view
/// model and repository (routing in AppModel+Routing, the rest of the parity work in
/// AppModel+Parity), minus what iOS has no room for (the helper programs, per-app VPN).
@MainActor
final class AppModel: ObservableObject {

    @Published var subscriptions: [Subscription] = []
    @Published var servers: [Server] = []
    @Published var settings: AppSettings {
        didSet { if settings != oldValue { Store.save(settings: settings) } }
    }
    @Published var vpnState: VpnState = .disconnected
    @Published var connectedSince: Date?
    @Published var phase: ConnectPhase = .idle
    @Published var lastError: String?
    @Published var busy = false
    @Published var pingingAll = false
    @Published var pinging: Set<Int64> = []
    @Published var runningScope: BalancerScope?
    @Published var toast: String?
    @Published var tab: AppTab = .home
    @Published var importOpen = false
    @Published var importDraft = ""
    @Published var traffic = Traffic()
    @Published var speedHistory: [SpeedPoint] = []
    @Published var pick: PickSummary?
    @Published var regime: NetworkRegime = .unknown
    @Published var ipInfo: IpChecker.Info?
    @Published var coreVersion = ""
    @Published private(set) var connecting = false
    @Published var profiles: [RoutingProfile] = []
    /// Routing changed while connected: «Применить» reconnects.
    @Published var routingPending = false
    /// What the guard in the extension last did (the live server may have changed).
    @Published var guardState: GuardState?
    @Published var update: UpdateStatus = .idle
    @Published var trafficSummary = TrafficSummary()
    @Published var internet: InternetCheck.Status = .ok

    let vpn = VPNController()
    private var connectTask: Task<Void, Never>?
    private var statsTask: Task<Void, Never>?
    private var toastTask: Task<Void, Never>?
    private var ipTask: Task<Void, Never>?
    private var lastStats: (TunnelMessage.Stats, Date)?
    private var started = false
    private var userStopped = false
    private var active = true

    init() {
        settings = Store.loadSettings()
        subscriptions = Store.loadSubscriptions()
        servers = Store.loadServers()
        Countries.displayLanguage = L10n.language
        seedProfiles()
        if Demo.isOn { Demo.apply(to: self) }
    }

    // MARK: - Derived

    var selectedServer: Server? {
        guard let id = settings.selectedServerId else { return nil }
        return servers.first { $0.id == id }
    }

    var hasServers: Bool { !servers.isEmpty }
    var isConnected: Bool { vpnState == .connected }
    var working: Bool { connecting || phase != .idle }

    func subscription(_ id: Int64?) -> Subscription? {
        guard let id = id else { return nil }
        return subscriptions.first { $0.id == id }
    }

    func count(_ scope: BalancerScope) -> Int { Balancer.pool(servers, scope).count }

    // MARK: - Launch and lifecycle

    func onLaunch() async {
        guard !started else { return }
        started = true
        if Demo.isOn {
            coreVersion = await Task.detached(priority: .utility) { XrayBridge.version }.value
            return
        }
        vpn.onStatusChange = { [weak self] status in self?.apply(status) }
        startInternetWatch()
        loadTraffic()
        await vpn.load()
        coreVersion = await Task.detached(priority: .utility) { XrayBridge.version }.value
        if settings.refreshOnLaunch && !subscriptions.isEmpty {
            await refreshAll(quiet: true)
        }
        if !isConnected && settings.probeOnLaunch && Balancer.hasLte(servers) {
            regime = await NetworkProbe.run()
        }
        refreshIp()
        await checkUpdatesOnLaunch()
    }

    func setActive(_ value: Bool) {
        active = value
        if Demo.isOn { return }
        if value {
            loadTraffic()
            apply(vpn.status)
            if isConnected { startStats() }
        } else {
            stopStats()
        }
    }

    private func apply(_ status: NEVPNStatus) {
        let old = vpnState
        switch status {
        case .connected:
            vpnState = .connected
            lastError = nil
            connectedSince = vpn.connectedDate ?? Date()
            if old != .connected {
                Haptics.success()
                refreshIp()
            }
            if active { startStats() }
        case .connecting, .reasserting:
            if vpnState != .connecting { vpnState = .connecting }
        case .disconnecting:
            vpnState = .disconnecting
        case .disconnected, .invalid:
            let wasUp = old == .connecting || old == .connected || old == .disconnecting
            connectedSince = nil
            stopStats()
            traffic = Traffic()
            speedHistory = []
            if vpnState != .error { vpnState = .disconnected }
            if wasUp && !userStopped {
                // The tunnel ended without being asked to: say why.
                Task { [weak self] in
                    guard let self = self, let message = await self.vpn.lastError() else { return }
                    if self.vpnState == .disconnected && !self.working {
                        self.lastError = message
                        self.vpnState = .error
                        Haptics.error()
                    }
                }
            }
            userStopped = false
            if wasUp { refreshIp() }
        @unknown default:
            break
        }
    }

    private func refreshIp() {
        ipTask?.cancel()
        ipTask = Task { [weak self] in
            // Give the new route a moment to settle.
            try? await Task.sleep(nanoseconds: 800_000_000)
            let info = await IpChecker.fetch()
            guard !Task.isCancelled else { return }
            self?.ipInfo = info
        }
    }

    // MARK: - Connecting

    func toggle() {
        if connectTask != nil {
            connectTask?.cancel()
            return
        }
        switch vpnState {
        case .connected, .connecting:
            disconnect()
        case .disconnecting:
            break
        case .disconnected, .error:
            connect()
        }
    }

    func connect(mode: ConnectMode? = nil) {
        guard connectTask == nil else { return }
        // Connecting with no network under the VPN can only spin: say so instead.
        if internet.blocksConnect {
            lastError = L(internet == .airplane ? "network_connect_airplane" : "network_connect_none")
            vpnState = .error
            return
        }
        lastError = nil
        if vpnState == .error { vpnState = .disconnected }
        AppGroup.writeLastError(nil)
        connecting = true
        connectTask = Task { [weak self] in
            guard let self = self else { return }
            do {
                let server = try await self.chooseServer(for: mode ?? self.settings.mode)
                try Task.checkCancellation()
                self.phase = .starting
                try self.writeTunnelPlan(for: server, mode: mode ?? self.settings.mode)
                self.routingPending = false
                try await self.vpn.start(keepConnected: self.settings.keepConnected)
                self.settings.selectedServerId = server.id
            } catch is CancellationError {
                // A second tap on the disc.
            } catch {
                self.lastError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
                self.vpnState = .error
                Haptics.error()
            }
            self.phase = .idle
            self.connecting = false
            self.connectTask = nil
        }
    }

    func disconnect() {
        userStopped = true
        Task { await vpn.stop() }
    }

    /// Moves a running tunnel to [server]: a new plan, then the extension restarts.
    func reconnectTunnel(to server: Server) {
        guard isConnected || vpnState == .connecting else { return }
        Task { [weak self] in
            guard let self = self else { return }
            do {
                try self.writeTunnelPlan(for: server, mode: self.settings.mode)
                self.routingPending = false
                self.userStopped = true
                await self.vpn.stop()
                for _ in 0..<40 {
                    if self.vpn.status == .disconnected || self.vpn.status == .invalid { break }
                    try? await Task.sleep(nanoseconds: 100_000_000)
                }
                try await self.vpn.start(keepConnected: self.settings.keepConnected)
            } catch {
                self.lastError = (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
            }
        }
    }

    /// The server for a connect: the selected one, or the best of the group the mode
    /// asks for. Uses fresh measurements when there are some (last ten minutes), else
    /// measures the most promising servers first.
    private func chooseServer(for mode: ConnectMode) async throws -> Server {
        if mode == .manual {
            guard let s = selectedServer else { throw UserError(L("connect.error.noServer")) }
            if let problem = ConfigBuilder.dialProblem(s) { throw UserError(problem) }
            return s
        }
        let dialable = servers.filter { ConfigBuilder.canDial($0) }
        if dialable.isEmpty {
            throw UserError(servers.isEmpty ? L("connect.error.noServers") : L("connect.error.noneDialable"))
        }
        let scope = mode.scope ?? .all
        var networkRegime = NetworkRegime.unknown
        let pool: [Server]
        if scope == .all && Balancer.hasLte(dialable) {
            phase = .probing
            networkRegime = await NetworkProbe.run()
            try Task.checkCancellation()
            regime = networkRegime
            if networkRegime == .blocked { throw UserError(L("connect.error.offline")) }
            pool = Balancer.filterByRegime(dialable, networkRegime)
        } else {
            pool = Balancer.pool(dialable, scope)
        }
        if pool.isEmpty { throw UserError(L("connect.error.emptyGroup")) }

        let group = Balancer.groupOf(scope, networkRegime) ?? (scope == .all ? .normal : scope)
        let holder = selectedServer.flatMap { s in pool.first { $0.id == s.id } }
            ?? settings.remembered[group.rawValue].flatMap { id in pool.first { $0.id == id } }
        let samples = try await samplesFor(pool)
        let decision = Balancer.choose(pool: pool, samples: samples, holder: holder)
        guard let chosen = decision.selected else { throw UserError(L("connect.error.noAnswer")) }
        settings.remembered[group.rawValue] = chosen.id
        summarize(decision.ranked, chosen: chosen)
        return chosen
    }

    private func samplesFor(_ pool: [Server]) async throws -> [Int64: [Int]] {
        let now = Date().timeIntervalSince1970
        let fresh = pool.filter { $0.lastPingAt > 0 && now - $0.lastPingAt < 600 }
        var samples: [Int64: [Int]] = [:]
        if !fresh.isEmpty && fresh.count >= min(3, pool.count) {
            for s in fresh { samples[s.id] = [s.latencyMs] }
            return samples
        }
        phase = .measuring
        let candidates = Array(pool.sorted { $0.score < $1.score }.prefix(15))
        let results = await Pinger.measure(candidates, method: settings.pingMethod, timeout: 3) { [weak self] batch in
            self?.applyPings(batch)
        }
        try Task.checkCancellation()
        for (id, ms) in results { samples[id] = [ms] }
        return samples
    }

    private func summarize(_ ranked: [Balancer.Ranked], chosen: Server) {
        let alive = ranked.filter { $0.ok }
        guard !alive.isEmpty else { pick = nil; return }
        var rows = alive.prefix(3).map { PickSummary.Row(name: $0.server.remark, ms: $0.latencyMs, chosen: $0.server.id == chosen.id) }
        if !rows.contains(where: { $0.chosen }), let c = alive.first(where: { $0.server.id == chosen.id }) {
            rows.append(PickSummary.Row(name: c.server.remark, ms: c.latencyMs, chosen: true))
        }
        pick = PickSummary(rows: rows, measured: ranked.count, alive: alive.count, at: Date())
    }

    // MARK: - Traffic

    private func startStats() {
        guard statsTask == nil else { return }
        statsTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { return }
                if let stats = await self.vpn.stats() { self.applyStats(stats) }
                self.applyGuardState()
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    private func stopStats() {
        statsTask?.cancel()
        statsTask = nil
        lastStats = nil
    }

    private func applyStats(_ s: TunnelMessage.Stats) {
        let now = Date()
        var t = Traffic(uplink: s.uplink, downlink: s.downlink)
        if let last = lastStats {
            let prev = last.0
            let dt = max(0.2, now.timeIntervalSince(last.1))
            t.upSpeed = max(0, Int64(Double(s.uplink - prev.uplink) / dt))
            t.downSpeed = max(0, Int64(Double(s.downlink - prev.downlink) / dt))
        }
        lastStats = (s, now)
        traffic = t
        speedHistory.append(SpeedPoint(down: t.downSpeed, up: t.upSpeed))
        if speedHistory.count > 60 { speedHistory.removeFirst(speedHistory.count - 60) }
    }

    // MARK: - Servers

    func select(_ server: Server) {
        Haptics.select()
        settings.selectedServerId = server.id
        settings.mode = .manual
        if isConnected { reconnectTunnel(to: server) }
    }

    func setMode(_ mode: ConnectMode) {
        guard mode != settings.mode else { return }
        settings.mode = mode
    }

    func toggleFavorite(_ id: Int64) {
        update(id) { $0.isFavorite.toggle() }
    }

    func setRole(_ id: Int64, _ role: ServerRole) {
        update(id) {
            $0.role = role
            $0.roleManual = true
        }
    }

    func deleteServer(_ id: Int64) {
        servers.removeAll { $0.id == id && $0.subscriptionId == nil }
        if settings.selectedServerId == id { settings.selectedServerId = nil }
        persistServers()
    }

    private func update(_ id: Int64, _ change: (inout Server) -> Void) {
        guard let i = servers.firstIndex(where: { $0.id == id }) else { return }
        change(&servers[i])
        persistServers()
    }

    /// Stores measurements: the reading, its smoothed value and the failure streak.
    func applyPings(_ batch: [Int64: Int]) {
        let now = Date().timeIntervalSince1970
        for (id, ms) in batch {
            guard let i = servers.firstIndex(where: { $0.id == id }) else { continue }
            var s = servers[i]
            if ms > 0 {
                s.latencyMs = ms
                s.ewmaMs = Balancer.blendEwma(previous: s.ewmaMs, sample: ms)
                s.failStreak = 0
            } else {
                s.latencyMs = -1
                s.failStreak += 1
            }
            s.lastPingAt = now
            servers[i] = s
        }
        pinging.subtract(batch.keys)
        persistServers()
    }

    func ping(_ server: Server) {
        guard !pinging.contains(server.id) else { return }
        if isConnected && settings.pingMethod == .realDelay { show(L("ping.whileConnected")) }
        pinging.insert(server.id)
        Task { [weak self] in
            guard let self = self else { return }
            _ = await Pinger.measure([server], method: self.settings.pingMethod) { [weak self] batch in
                self?.applyPings(batch)
            }
        }
    }

    func pingAll(_ list: [Server]) {
        guard !pingingAll, !list.isEmpty else { return }
        pingingAll = true
        pinging.formUnion(list.map { $0.id })
        Task { [weak self] in
            guard let self = self else { return }
            _ = await Pinger.measure(list, method: self.settings.pingMethod) { [weak self] batch in
                self?.applyPings(batch)
            }
            self.pinging.removeAll()
            self.pingingAll = false
        }
    }

    /// "Лучший сервер в группе": measures the group, selects its fastest server and
    /// switches the disc to that group's auto mode.
    func runBalancer(_ scope: BalancerScope) {
        guard runningScope == nil else { return }
        runningScope = scope
        Task { [weak self] in
            guard let self = self else { return }
            defer { self.runningScope = nil }
            let dialable = self.servers.filter { ConfigBuilder.canDial($0) }
            var regime = NetworkRegime.unknown
            var pool = Balancer.pool(dialable, scope)
            if scope == .all && Balancer.hasLte(dialable) && !self.isConnected {
                regime = await NetworkProbe.run()
                self.regime = regime
                pool = Balancer.filterByRegime(dialable, regime)
            }
            guard !pool.isEmpty else { self.show(L("connect.error.emptyGroup")); return }
            let results = await Pinger.measure(pool, method: self.settings.pingMethod) { [weak self] batch in
                self?.applyPings(batch)
            }
            var samples: [Int64: [Int]] = [:]
            for (id, ms) in results { samples[id] = [ms] }
            let current = self.selectedServer.flatMap { s in pool.first { $0.id == s.id } }
            let decision = Balancer.choose(pool: pool, samples: samples, holder: current)
            guard let chosen = decision.selected, decision.ranked.contains(where: { $0.ok }) else {
                self.show(L("balancer.noAnswer"))
                return
            }
            self.summarize(decision.ranked, chosen: chosen)
            let group = Balancer.groupOf(scope, regime) ?? (scope == .all ? .normal : scope)
            self.settings.remembered[group.rawValue] = chosen.id
            let changed = self.settings.selectedServerId != chosen.id
            self.settings.selectedServerId = chosen.id
            switch scope {
            case .all: self.settings.mode = .autoHomeLte
            case .normal: self.settings.mode = .autoHome
            case .bypass: self.settings.mode = .autoLte
            }
            let ms = decision.ranked.first { $0.server.id == chosen.id }?.latencyMs ?? -1
            self.show(L("balancer.picked", Countries.withoutLeadingFlag(chosen.remark), ms))
            if changed && self.isConnected { self.reconnectTunnel(to: chosen) }
        }
    }

    // MARK: - Subscriptions

    @discardableResult
    func addSubscription(url raw: String, name: String, hidden: Bool = false, serverTypes: String = "",
                         reveal: Bool = false) async -> Bool {
        var url = raw.trimmed.removingPrefix("\u{FEFF}")
        if url.hasPrefix("<") && url.hasSuffix(">") && url.count > 2 { url = String(url.dropFirst().dropLast()).trimmed }
        if url.hasPrefixIgnoringCase("volta://") { return await handleVoltaLink(url, name: name) }
        guard !url.isBlank else { show(L("sub.error.noLink")); return false }
        guard !url.hasPrefixIgnoringCase("http://") else { show(L("sub.error.https")); return false }
        busy = true
        defer { busy = false }
        let types = ServerMarkup.normalize(serverTypes)

        if let i = subscriptions.firstIndex(where: { $0.url == url }) {
            let before = subscriptions[i]
            var existing = before
            if !name.isBlank {
                existing.name = name.trimmed
                existing.nameLocked = true
            }
            if hidden { existing.urlHidden = true } else if reveal { existing.urlHidden = false }
            if !types.isEmpty { existing.serverTypes = types }
            existing.isEnabled = true
            subscriptions[i] = existing
            do {
                try await refresh(existing.id)
                return true
            } catch {
                if let j = subscriptions.firstIndex(where: { $0.id == before.id }) { subscriptions[j] = before }
                show(message(error))
                return false
            }
        }

        let sub = Subscription(
            id: Server.newId(),
            name: name.trimmed.ifBlank(L("sub.defaultName")),
            nameLocked: !name.isBlank,
            url: url,
            colorHex: Subscription.colors[subscriptions.count % Subscription.colors.count],
            urlHidden: hidden,
            serverTypes: types
        )
        subscriptions.append(sub)
        do {
            try await refresh(sub.id)
            show(L("sub.added", subscriptions.first { $0.id == sub.id }?.name ?? sub.name))
            return true
        } catch {
            subscriptions.removeAll { $0.id == sub.id }
            servers.removeAll { $0.subscriptionId == sub.id }
            persist()
            show(message(error))
            return false
        }
    }

    /// Downloads one subscription and merges its servers in. Throws a message for the user.
    func refresh(_ id: Int64) async throws {
        guard let sub = subscriptions.first(where: { $0.id == id }) else { return }
        let body: String
        do {
            body = try await SubscriptionFetcher.fetch(
                url: sub.url, userAgent: sub.userAgent, hwid: settings.sendHwid && sub.hwidEnabled ? Hwid.value : nil
            )
        } catch {
            // A hidden link must not leak through an error text.
            if sub.urlHidden { throw UserError(conceal(message(error), sub.url)) }
            throw error
        }
        let parsed = ConfigParser.parseSubscriptionBody(body, subscriptionId: id)
        let fetchedBody = body
        let merged = ServerClassifier.mergeProviderUpdate(
            previous: servers.filter { $0.subscriptionId == id },
            incoming: parsed.servers,
            subscriptionId: id,
            serverTypes: sub.serverTypes
        )
        if merged.isEmpty {
            throw UserError(parsed.skipped.isEmpty ? L("sub.error.noServers")
                            : L("sub.error.onlyUnsupported", parsed.skipped.joined(separator: ", ")))
        }
        servers = servers.filter { $0.subscriptionId != id } + merged
        if selectedServer == nil {
            settings.selectedServerId = (merged.first { $0.role != .auto } ?? merged.first)?.id
        }
        if let i = subscriptions.firstIndex(where: { $0.id == id }) {
            var s = subscriptions[i]
            if !s.nameLocked {
                let title = parsed.title.trimmed
                s.name = title.ifBlank(s.name)
            }
            s.lastUpdated = Date().timeIntervalSince1970
            s.expireAt = parsed.expireAt
            s.trafficUsed = parsed.trafficUsed
            s.trafficTotal = parsed.trafficTotal
            s.announce = parsed.announce
            s.supportUrl = parsed.supportUrl
            s.webPageUrl = parsed.webPageUrl
            s.updateIntervalHours = parsed.updateIntervalHours
            subscriptions[i] = s
            await applyProviderRouting(subscriptionId: id, displayName: s.name, body: fetchedBody, parsed: parsed)
        }
        persist()
    }

    func refreshSubscription(_ id: Int64) {
        Task { [weak self] in
            guard let self = self else { return }
            self.busy = true
            defer { self.busy = false }
            do {
                try await self.refresh(id)
                self.show(L("sub.refreshed"))
            } catch is CancellationError {
            } catch {
                self.show(self.message(error))
            }
        }
    }

    func refreshAll(quiet: Bool = false) async {
        busy = true
        defer { busy = false }
        var failed = 0
        for sub in subscriptions where sub.isEnabled {
            do { try await refresh(sub.id) } catch { failed += 1 }
        }
        if !quiet { show(failed == 0 ? L("sub.refreshedAll") : L("sub.refreshFailed", failed)) }
    }

    func updateSubscription(_ id: Int64, name: String, url: String) async -> Bool {
        guard let i = subscriptions.firstIndex(where: { $0.id == id }) else { return false }
        let before = subscriptions[i]
        var s = before
        if !name.isBlank && name.trimmed != s.name {
            s.name = name.trimmed
            s.nameLocked = true
        }
        let newUrl = url.trimmed
        let urlChanged = !s.urlHidden && !newUrl.isBlank && newUrl != s.url
        if urlChanged {
            guard !newUrl.hasPrefixIgnoringCase("http://") else { show(L("sub.error.https")); return false }
            s.url = newUrl
        }
        subscriptions[i] = s
        if urlChanged {
            busy = true
            defer { busy = false }
            do {
                try await refresh(id)
            } catch {
                if let j = subscriptions.firstIndex(where: { $0.id == id }) { subscriptions[j] = before }
                show(message(error))
                return false
            }
        }
        persist()
        return true
    }

    func deleteSubscription(_ id: Int64) {
        forgetRouting(ofSubscription: id)
        subscriptions.removeAll { $0.id == id }
        servers.removeAll { $0.subscriptionId == id }
        if selectedServer == nil { settings.selectedServerId = servers.first { $0.role != .auto }?.id }
        persist()
    }

    // MARK: - Import

    /// Servers, keys and configs pasted or opened from a file; subscriptions go through
    /// addSubscription.
    @discardableResult
    func importText(_ text: String) async -> Bool {
        let t = text.trimmed
        if t.hasPrefixIgnoringCase("volta://") { return await handleVoltaLink(t, name: "") }
        if ImportKind.detect(t) == .amnezia {
            show(L("import.error.amnezia"))
            return false
        }
        if VoltaLinks.isHttp(t) && !t.contains(where: { $0.isWhitespace }) {
            return await addSubscription(url: t, name: "")
        }
        let parsed = ConfigParser.parseSubscriptionBody(t)
        if parsed.servers.isEmpty {
            show(parsed.skipped.isEmpty ? L("import.error.nothing")
                 : L("import.error.unsupported", parsed.skipped.joined(separator: ", ")))
            return false
        }
        var keys = Set(servers.filter { $0.subscriptionId == nil }.map { ServerClassifier.stableKey($0) })
        var added: [Server] = []
        for raw in parsed.servers {
            var s = ServerClassifier.classify(raw, types: .empty, position: -1)
            let key = ServerClassifier.stableKey(s)
            if keys.contains(key) { continue }
            keys.insert(key)
            s.id = Server.newId()
            s.subscriptionId = nil
            s.sortOrder = servers.count + added.count
            added.append(s)
        }
        servers += added
        if let last = added.last, selectedServer == nil { settings.selectedServerId = last.id }
        persistServers()
        show(added.isEmpty ? L("import.already") : L10n.plural("import.added", added.count))
        return true
    }

    @discardableResult
    func handleVoltaLink(_ link: String, name: String) async -> Bool {
        switch VoltaLinks.parse(link) {
        case let .addSubscription(url, n, visible, types):
            return await addSubscription(url: url, name: name.ifBlank(n), hidden: visible == false,
                                         serverTypes: types, reveal: visible == true)
        case let .addEncrypted(payload, n, types):
            switch VoltaLinks.openEncrypted(payload: payload, name: name.ifBlank(n), serverTypes: types) {
            case let .addSubscription(url, inner, visible, st):
                return await addSubscription(url: url, name: inner, hidden: visible != true, serverTypes: st)
            case let .invalid(text):
                show(text)
                return false
            default:
                return false
            }
        case let .importContent(value):
            if value.hasPrefixIgnoringCase("volta://") { return false }
            return await importText(value)
        case let .connect(mode):
            if let mode = mode { settings.mode = mode }
            if !isConnected { connect() }
            return true
        case .disconnect:
            disconnect()
            return true
        case .toggle:
            toggle()
            return true
        case let .invalid(text):
            show(text)
            return false
        }
    }

    /// A volta:// link opened from outside: switching acts at once, anything to add
    /// opens «Добавить» filled in, so it is added with one more tap.
    func open(url: URL) {
        let text = url.absoluteString
        switch VoltaLinks.parse(text) {
        case .connect, .disconnect, .toggle:
            Task { await handleVoltaLink(text, name: "") }
        default:
            importDraft = text
            importOpen = true
        }
    }

    // MARK: - Settings that act

    func setKeepConnected(_ on: Bool) {
        settings.keepConnected = on
        Task { await vpn.setKeepConnected(on) }
    }

    // MARK: - Messages

    func show(_ text: String) {
        toast = text
        toastTask?.cancel()
        toastTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 3_200_000_000)
            guard !Task.isCancelled else { return }
            self?.toast = nil
        }
    }

    func message(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
    }

    private func conceal(_ text: String, _ url: String) -> String {
        var out = text
        let host = URL(string: url)?.host ?? ""
        for part in [url, host] where !part.isBlank {
            out = out.replacingOccurrences(of: part, with: "…", options: .caseInsensitive)
        }
        out = Rx("https?://\\S+").replace(out, with: "…")
        return out.ifBlank(L("sub.error.download"))
    }

    // MARK: - Persistence

    func persist() {
        Store.save(subscriptions: subscriptions)
        Store.save(servers: servers)
    }

    private func persistServers() { Store.save(servers: servers) }
}
