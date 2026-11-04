import Foundation
import Network
import UIKit

/// «Проверка обновлений» on iOS: find out and point at the release; installing is up to
/// whatever signed this copy (iOS lets no app replace itself).
enum UpdateStatus: Equatable {
    case idle
    case checking
    case upToDate(Date)
    case available(Updates.Manifest)
    case failed
}

/// The parity work beyond routing: updates, backups, traffic per day, the internet
/// check, what the extension's guard did, and the language.
extension AppModel {

    // MARK: - Updates

    var buildNumber: Int { Int((Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String) ?? "") ?? 0 }

    var versionName: String { (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0" }

    func checkUpdatesOnLaunch() async {
        guard settings.updateAutoCheck, !Demo.isOn else { return }
        if Date().timeIntervalSince1970 - settings.updateLastCheck < Updates.launchEvery, update != .idle { return }
        await checkUpdates()
    }

    func checkUpdates() async {
        if update == .checking { return }
        update = .checking
        let session = URLSession(configuration: .ephemeral)
        func fetch(_ url: URL) async throws -> Updates.Manifest? {
            var request = URLRequest(url: url, timeoutInterval: 15)
            request.setValue(SubscriptionFetcher.userAgent, forHTTPHeaderField: "User-Agent")
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
            // Nothing published yet: nothing to update to, and nothing wrong.
            if [404, 410].contains(http.statusCode) { return nil }
            guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
            return Updates.parse(data)
        }
        do {
            let stable = try await fetch(Updates.latestURL)
            // A missing or broken test build must not hide a good release.
            var beta: Updates.Manifest?
            if settings.updateBeta { beta = (try? await fetch(Updates.betaURL)) ?? nil }
            if let found = Updates.newest(stable: stable, beta: beta, current: buildNumber) {
                update = .available(found)
            } else {
                update = .upToDate(Date())
            }
        } catch {
            update = .failed
        }
        settings.updateLastCheck = Date().timeIntervalSince1970
    }

    func setUpdateBeta(_ on: Bool) {
        settings.updateBeta = on
        update = .idle
        Task { await checkUpdates() }
    }

    func setUpdateAutoCheck(_ on: Bool) {
        settings.updateAutoCheck = on
        if on { Task { await checkUpdates() } }
    }

    // MARK: - Backup

    /// The backup as a file in the temporary folder, for the share sheet.
    func exportBackup() throws -> URL {
        let nameById = Dictionary(uniqueKeysWithValues: appProfiles.map { ($0.id, $0.name) })
        var routingBySub: [Int64: String] = [:]
        for s in subscriptions { if let n = nameById[s.routingProfileId] { routingBySub[s.id] = n } }
        let text = try BackupCodec.encode(BackupCodec.Snapshot(
            subscriptions: subscriptions,
            subscriptionRouting: routingBySub,
            servers: servers.filter { $0.subscriptionId == nil },
            routing: appProfiles.filter { BackupCodec.worthKeeping($0) },
            activeRouting: appProfiles.first { $0.isActive }?.name,
            settings: settings.backupValues(),
            appVersion: versionName,
            createdAt: Int64(Date().timeIntervalSince1970 * 1000)
        ))
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(BackupCodec.fileName())
        try Data(text.utf8).write(to: url, options: [.atomic])
        return url
    }

    func readBackup(_ url: URL) throws -> BackupCodec.Contents {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        if data.count > BackupCodec.maxBytes { throw BackupCodec.BackupError(key: "settings_backup_error_too_big") }
        return try BackupCodec.decode(String(decoding: data, as: UTF8.self))
    }

    /// The question the restore dialog asks, in plain words (Android's describe).
    func describe(_ c: BackupCodec.Contents) -> String {
        var out = ""
        if c.createdAt > 0 {
            let f = DateFormatter()
            f.locale = Locale(identifier: L10n.language)
            f.setLocalizedDateFormatFromTemplate("d MMMM yyyy")
            out += L("settings_backup_saved_on", f.string(from: Date(timeIntervalSince1970: Double(c.createdAt) / 1000))) + "\n\n"
        }
        out += L("settings_backup_contains") + "\n"
        if !c.subscriptions.isEmpty { out += "• " + L10n.plural("settings_backup_count_subscriptions", c.subscriptions.count) + "\n" }
        if !c.servers.isEmpty { out += "• " + L10n.plural("settings_backup_count_servers", c.servers.count) + "\n" }
        if !c.routing.isEmpty { out += "• " + L10n.plural("settings_backup_count_rules", c.routing.count) + "\n" }
        if !c.settings.isEmpty { out += "• " + L("settings_backup_item_settings") + "\n" }
        var notes = [L("settings_backup_no_duplicates")]
        if !c.settings.isEmpty { notes.append(L("settings_backup_settings_replaced")) }
        if !c.subscriptions.isEmpty { notes.append(L("settings_backup_servers_reload")) }
        return (out + "\n" + notes.joined(separator: " ")).trimmed
    }

    /// Only adds: what is already in the app stays as it is, so the same file twice changes nothing.
    func restore(_ c: BackupCodec.Contents) async -> String {
        busy = true
        defer { busy = false }
        // Rule sets by name: one with the same name gets the saved rules, a new one is added.
        var routingRestored = 0
        for saved in c.routing {
            if let i = profiles.firstIndex(where: { $0.sourceSubscriptionId == nil && $0.name == saved.name }) {
                profiles[i].description = saved.description.ifBlank(profiles[i].description)
                profiles[i].useProviderRules = saved.useProviderRules
                profiles[i].rules = saved.rules
                profiles[i].domainStrategy = saved.domainStrategy
                profiles[i].remoteDns = saved.remoteDns
                profiles[i].domesticDns = saved.domesticDns
                profiles[i].dnsHostsJson = saved.dnsHostsJson
                routingRestored += 1
            } else if !saved.isBuiltIn {
                var p = saved
                p.id = Server.newId()
                p.isActive = false
                profiles.append(p)
                routingRestored += 1
            }
        }
        if let active = c.activeRouting, let p = appProfiles.first(where: { $0.name == active }) { setActiveProfile(p.id) }
        saveProfiles()
        var next = settings
        let settingsRestored = next.restore(c.settings)
        settings = next

        var known = Set(servers.filter { $0.subscriptionId == nil }.map(BackupCodec.sameServerKey))
        var serversAdded = 0
        for var s in c.servers where known.insert(BackupCodec.sameServerKey(s)).inserted {
            s.id = Server.newId()
            servers.append(s)
            serversAdded += 1
        }
        persist()

        var added = 0, notLoaded = 0, already = 0
        for restored in c.subscriptions {
            if subscriptions.contains(where: { $0.url == restored.subscription.url }) {
                already += 1
                continue
            }
            var sub = restored.subscription
            sub.id = Server.newId()
            sub.routingProfileId = restored.routingName.flatMap { n in appProfiles.first { $0.name == n }?.id } ?? 0
            subscriptions.append(sub)
            added += 1
            do { try await refresh(sub.id) } catch { notLoaded += 1 }
        }
        if selectedServer == nil { settings.selectedServerId = servers.first { $0.role != .auto }?.id }
        persist()

        var parts: [String] = []
        if added > 0 { parts.append(L10n.plural("settings_backup_count_subscriptions", added)) }
        if serversAdded > 0 { parts.append(L10n.plural("settings_backup_count_servers", serversAdded)) }
        if routingRestored > 0 { parts.append(L10n.plural("settings_backup_count_rules", routingRestored)) }
        if settingsRestored > 0 { parts.append(L("settings_backup_item_settings")) }
        let head = parts.isEmpty ? L(already > 0 ? "settings_restore_all_there" : "settings_restore_nothing")
            : L("settings_restore_done", parts.joined(separator: L("settings_list_separator")))
        var notes: [String] = []
        if notLoaded > 0 { notes.append(L10n.plural("settings_restore_not_loaded", notLoaded)) }
        if c.skipped > 0 { notes.append(L10n.plural("settings_restore_skipped", c.skipped)) }
        return ([head] + notes).joined(separator: L("settings_sentence_separator"))
    }

    // MARK: - Traffic per day

    func loadTraffic() {
        trafficSummary = TrafficTally.summarize(AppGroup.readTraffic(), today: TrafficTally.day(of: Date()))
    }

    // MARK: - Internet under the tunnel

    func startInternetWatch() {
        InternetWatch.shared.start { [weak self] status in
            Task { @MainActor in self?.internet = status }
        }
    }

    // MARK: - The guard in the extension

    /// The extension may have moved the tunnel to another server: follow it.
    func applyGuardState() {
        guard let state = AppGroup.readGuardState() else { return }
        let old = guardState
        guardState = state
        guard isConnected, old?.serverId != state.serverId, state.reason != nil else { return }
        settings.selectedServerId = state.serverId
        settings.remembered[state.group == .bypass ? BalancerScope.bypass.rawValue : BalancerScope.normal.rawValue] = state.serverId
        if old != nil { show(L("guard.switched", Countries.withoutLeadingFlag(state.serverName))) }
    }

    // MARK: - Language

    /// The 15 languages Volta speaks, as on Android.
    static let languages = ["ru", "en", "uk", "be", "kk", "uz", "az", "tr", "fa", "ar", "zh-Hans", "es", "de", "fr", "pt-BR"]

    /// The language chosen in Volta, nil = as on the iPhone.
    var chosenLanguage: String? { UserDefaults.standard.string(forKey: "VoltaLanguage") }

    /// iOS reads an app's language when it starts: the choice applies after a restart. iOS
    /// Settings has the same choice on Volta's own page.
    func chooseLanguage(_ code: String?) {
        if let code = code {
            UserDefaults.standard.set([code], forKey: "AppleLanguages")
            UserDefaults.standard.set(code, forKey: "VoltaLanguage")
        } else {
            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
            UserDefaults.standard.removeObject(forKey: "VoltaLanguage")
        }
        show(L("language.restart"))
    }
}

/// NWPathMonitor for the internet check: the physical paths only (Volta's own tunnel is
/// left out by looking at the interfaces under it), re-read after a short settle.
final class InternetWatch {
    static let shared = InternetWatch()
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.ars3nb.volta.internet")
    private var started = false
    private var pending: DispatchWorkItem?

    func start(_ onChange: @escaping (InternetCheck.Status) -> Void) {
        guard !started else { return }
        started = true
        monitor.pathUpdateHandler = { [weak self] path in
            self?.pending?.cancel()
            let work = DispatchWorkItem { onChange(Self.status(path)) }
            self?.pending = work
            self?.queue.asyncAfter(deadline: .now() + 1.2, execute: work)
        }
        monitor.start(queue: queue)
    }

    static func status(_ path: NWPath) -> InternetCheck.Status {
        let physical = path.availableInterfaces.filter { $0.type != .other }
        let cellular = physical.contains { $0.type == .cellular }
        let other = physical.contains { $0.type == .wifi || $0.type == .wiredEthernet }
        return InternetCheck.decide(.init(satisfied: path.status == .satisfied, hasNonCellular: other, hasCellular: cellular))
    }
}
