import Foundation
import Network

/// The background check, inside the extension because the app may be asleep: Android's
/// ConnectionGuard and guardCheck as far as iOS allows.
///
/// Every two minutes, and three seconds after Wi-Fi and mobile data swap (NWPathMonitor):
///  - Home+LTE: the sites say which group fits this network; the wrong group moves over;
///  - the core's observatory says whether the server in use answers and how fast, and how
///    the rest of its group does (they ride along as `alt-N` outbounds);
///  - a dead server is replaced only when no data went through it and sites answer outside
///    the tunnel; a slow one only by a clearly faster one, within the flapping limit.
/// The extension's own sockets bypass the tunnel, so the site check sees the real network.
final class TunnelGuard {

    enum Trigger { case timer, network, wake }

    var readCounters: () -> (Int64, Int64) = { (0, 0) }
    var readObservatory: () -> [String: GuardPolicy.Observation] = { [:] }
    /// Restarts the core with this server; true when it came up.
    var switchTo: (GuardCandidate) -> Bool = { _ in false }

    private var plan: GuardPlan
    private var liveId: Int64
    private let queue: DispatchQueue
    private var timer: DispatchSourceTimer?
    private let monitor = NWPathMonitor()
    private var lastInterface: NWInterface.InterfaceType?
    private var limiter = GuardPolicy.SwitchLimiter()
    private var lastDown: Int64 = 0
    private var lastFull = Date().timeIntervalSince1970
    private var checking = false
    private var stopped = false
    private var regime: NetworkRegime = .unknown

    init(plan: GuardPlan, liveId: Int64, queue: DispatchQueue) {
        self.plan = plan
        self.liveId = liveId
        self.queue = queue
    }

    func start() {
        lastDown = readCounters().1
        let t = DispatchSource.makeTimerSource(queue: queue)
        t.schedule(deadline: .now() + GuardPolicy.baseInterval, repeating: GuardPolicy.baseInterval, leeway: .seconds(10))
        t.setEventHandler { [weak self] in self?.check(.timer) }
        t.resume()
        timer = t
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self = self else { return }
            let type = path.availableInterfaces.first { $0.type == .wifi || $0.type == .cellular || $0.type == .wiredEthernet }?.type
            defer { self.lastInterface = type }
            guard path.status == .satisfied, let now = type, let before = self.lastInterface, now != before else { return }
            self.checkSoon(reason: .network)
        }
        monitor.start(queue: queue)
    }

    func stop() {
        stopped = true
        timer?.cancel()
        timer = nil
        monitor.cancel()
    }

    func checkSoon(reason: Trigger) {
        // The sockets of the old network are dead; give the new one a moment to settle.
        queue.asyncAfter(deadline: .now() + 3) { [weak self] in self?.check(reason) }
    }

    private func check(_ trigger: Trigger) {
        guard !stopped, !checking, plan.mode != .manual else { return }
        checking = true
        Task {
            await run(trigger)
            queue.async { self.checking = false }
        }
    }

    private func run(_ trigger: Trigger) async {
        var wanted: ServerRole?
        if plan.mode == .autoHomeLte && plan.followNetwork && plan.hasLte && !plan.home.isEmpty {
            let r = await NetworkProbe.run()
            regime = r
            // No site answers: no internet right now, and that is not the server's fault.
            if r == .blocked { return writeState(nil) }
            wanted = r == .open ? .normal : (r == .whitelistOnly ? .bypass : nil)
        }
        let (observations, down) = await onQueue { (self.readObservatory(), self.readCounters().1) }
        let downloaded = max(0, down - lastDown)
        lastDown = down
        let now = Date().timeIntervalSince1970
        let fullDue = now - lastFull >= GuardPolicy.fullEvery
        if fullDue { lastFull = now }
        let proxyDown = observations[ConfigBuilder.proxyTag].map { !$0.alive } ?? false
        var internet = true
        if proxyDown && !GuardPolicy.trafficProvesAlive(downloaded) {
            if regime != .open && regime != .whitelistOnly {
                let probed = await NetworkProbe.run()
                internet = probed != .blocked
            }
        }
        let input = GuardPolicy.Input(plan: plan, liveId: liveId, wantedGroup: wanted, observations: observations,
                                      downloadedSinceLastCheck: downloaded, internetWorks: internet, fullDue: fullDue,
                                      optionalAllowed: limiter.allow(now))
        guard case let .switchTo(id, reason, optional) = GuardPolicy.decide(input),
              let candidate = (plan.home + plan.lte).first(where: { $0.id == id }) else { return writeState(nil) }
        let ok = await onQueue { self.switchTo(candidate) }
        guard ok else { return writeState(nil) }
        if optional { limiter.record(now) }
        liveId = candidate.id
        plan.group = plan.lte.contains { $0.id == candidate.id } ? .bypass : .normal
        lastDown = await onQueue { self.readCounters().1 }
        writeState(reason, switchedTo: candidate)
    }

    private func writeState(_ reason: GuardPolicy.Reason?, switchedTo candidate: GuardCandidate? = nil) {
        var state = AppGroup.readGuardState() ?? GuardState(serverId: liveId, serverName: "", group: plan.group,
                                                             switchedAt: 0, reason: nil)
        if let c = candidate {
            state.serverId = c.id
            state.serverName = c.name
            state.group = plan.group
            state.switchedAt = Date().timeIntervalSince1970
            state.reason = reason
        }
        state.checkedAt = Date().timeIntervalSince1970
        state.regime = regime
        AppGroup.writeGuardState(state)
    }

    private func onQueue<T>(_ work: @escaping () -> T) async -> T {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume(returning: work()) }
        }
    }
}
