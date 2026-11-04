import Foundation

/// The decisions of the background check, free of iOS so they can be tested: the same
/// numbers as Android's GuardPolicy. On iOS the check runs inside the VPN extension (the
/// app may be asleep): every two minutes, and at once when Wi-Fi and mobile data swap.
enum GuardPolicy {

    static let slowAbsMs = 900
    static let slowRatio = 2.5
    static let slowMinMs = 350
    /// A slow-triggered comparison runs at most this often.
    static let slowRecheckEvery: TimeInterval = 6 * 60
    /// This much downloaded since the last check proves the server carries data.
    static let aliveBytes: Int64 = 48 * 1024
    static let baseInterval: TimeInterval = 2 * 60
    /// The full comparison with «Улучшать выбор сервера».
    static let fullEvery: TimeInterval = 15 * 60

    static func hasLte(_ servers: [Server]) -> Bool { servers.contains { $0.role == .bypass } }

    /// The mode that really runs: Home when the chosen one needs LTE servers that aren't there.
    static func effectiveMode(_ mode: ConnectMode, hasLte: Bool) -> ConnectMode {
        if hasLte { return mode }
        return mode == .autoLte || mode == .autoHomeLte ? .autoHome : mode
    }

    static func isSlow(_ latencyMs: Int, usualMs: Int, absMs: Int = slowAbsMs) -> Bool {
        if latencyMs <= 0 { return false }
        if latencyMs >= absMs { return true }
        return usualMs > 0 && latencyMs >= slowMinMs && Double(latencyMs) >= Double(usualMs) * slowRatio
    }

    static func trafficProvesAlive(_ downloadedSinceLastCheck: Int64) -> Bool {
        downloadedSinceLastCheck >= aliveBytes
    }

    /// Stops optional switches from flapping: after [max] within [window] the guard holds
    /// still for [cooldown]. A dead server is still replaced; that is never optional.
    struct SwitchLimiter {
        var max = 3
        var window: TimeInterval = 10 * 60
        var cooldown: TimeInterval = 10 * 60
        private(set) var times: [TimeInterval] = []
        private(set) var quietUntil: TimeInterval = 0

        init(max: Int = 3, window: TimeInterval = 600, cooldown: TimeInterval = 600) {
            self.max = max
            self.window = window
            self.cooldown = cooldown
        }

        mutating func allow(_ now: TimeInterval) -> Bool {
            if now < quietUntil { return false }
            times.removeAll { now - $0 > window }
            return times.count < max
        }

        mutating func record(_ now: TimeInterval) {
            times.append(now)
            times.removeAll { now - $0 > window }
            if times.count >= max {
                quietUntil = now + cooldown
                times.removeAll()
            }
        }
    }

    // MARK: - One check

    /// What the core's observatory says about one outbound.
    struct Observation: Equatable {
        var alive: Bool
        var delayMs: Int
    }

    struct Input {
        var plan: GuardPlan
        var liveId: Int64
        /// The group the network wants now (Home+LTE with followNetwork), nil = no opinion.
        var wantedGroup: ServerRole?
        /// `proxy` and every `alt-N`.
        var observations: [String: Observation]
        var downloadedSinceLastCheck: Int64
        /// Sites answer outside the tunnel (asked only when it matters).
        var internetWorks: Bool
        var fullDue: Bool
        var optionalAllowed: Bool
    }

    enum Action: Equatable {
        case stay
        /// Move to this server; `optional` ones count against the limiter.
        case switchTo(id: Int64, reason: Reason, optional: Bool)
    }

    enum Reason: String, Codable {
        case network, dead, slow, faster
    }

    /// The Android guard's order: the right group for the network first, then a dead
    /// server (only when no data went through it and the internet itself works), then a
    /// slow one or a clearly faster one.
    static func decide(_ input: Input) -> Action {
        let plan = input.plan
        if plan.mode == .manual { return .stay }
        let live = plan.home.first { $0.id == input.liveId } ?? plan.lte.first { $0.id == input.liveId }
        let liveGroup: ServerRole = plan.lte.contains { $0.id == input.liveId } ? .bypass : .normal

        if plan.mode == .autoHomeLte && plan.followNetwork && plan.hasLte && !plan.home.isEmpty,
           let wanted = input.wantedGroup, wanted != liveGroup,
           let first = plan.candidates(wanted).first {
            return .switchTo(id: first.id, reason: .network, optional: false)
        }

        let group = plan.candidates(liveGroup)
        func alternate(_ tag: String) -> Int64? { live?.alternates[tag] }
        // Alive alternates by delay, best first.
        let alive = input.observations
            .filter { $0.key.hasPrefix(ConfigBuilder.alternatePrefix) && $0.value.alive }
            .compactMap { tag, o -> (Int64, Int)? in alternate(tag).map { ($0, o.delayMs) } }
            .sorted { $0.1 < $1.1 }

        let me = input.observations[ConfigBuilder.proxyTag]
        if let me = me, me.alive {
            let usual = live?.usualMs ?? -1
            let slow = isSlow(me.delayMs, usualMs: usual)
            if (slow || (input.fullDue && plan.autoRecheck)) && input.optionalAllowed, let best = alive.first,
               Balancer.isClearlyFaster(best.1, than: me.delayMs) {
                return .switchTo(id: best.0, reason: slow ? .slow : .faster, optional: true)
            }
            return .stay
        }
        // Not answering (or not measured yet: then wait).
        guard me != nil else { return .stay }
        if trafficProvesAlive(input.downloadedSinceLastCheck) { return .stay }
        if !input.internetWorks { return .stay }
        if let best = alive.first { return .switchTo(id: best.0, reason: .dead, optional: false) }
        // Nothing measured alive: the next in the group's order.
        if let next = group.first(where: { $0.id != input.liveId }) {
            return .switchTo(id: next.id, reason: .dead, optional: false)
        }
        return .stay
    }
}

/// What the extension last did with a live tunnel, for the app to show.
struct GuardState: Codable, Equatable {
    var serverId: Int64
    var serverName: String
    var group: ServerRole
    var switchedAt: Double
    var reason: GuardPolicy.Reason?
    var checkedAt: Double = 0
    var regime: NetworkRegime = .unknown
}
