import Darwin
import Foundation
import NetworkExtension
import Tun2SocksKit

/// The VPN itself. The app writes a TunnelPlan (the Xray config and how to carry the
/// packets) into the shared container and starts this extension; it also starts on its
/// own when iOS reconnects the VPN, and then reads the same plan.
///
/// Two ways from the interface into Xray-core:
///  - hev-socks5-tunnel reads the packets and hands every connection to Xray's SOCKS
///    port (the Android setup; small and proven on iOS), the default;
///  - Xray-core reads the interface itself through its TUN inbound (one Go runtime less
///    work in between, but its own TCP/IP stack).
/// Either way the extension's own sockets bypass the tunnel, so Xray reaches the server
/// directly. Memory stays within the ~50 MB iOS gives an extension: only the geo lists the
/// config names (trimmed by the app), the core's Go heap capped by libXray (30 MB, GC
/// every second), small hev buffers. TunnelGuard watches the server while the app sleeps.
final class PacketTunnelProvider: NEPacketTunnelProvider {

    private var engine: TunnelEngine = .hev
    private var metricsPort = 0
    private var socksPort = 0
    private var socksUser = ""
    private var socksPass = ""
    private var stopping = false
    private let queue = DispatchQueue(label: "com.ars3nb.volta.tunnel")
    private var guardian: TunnelGuard?
    private var trafficTimer: DispatchSourceTimer?
    /// Counters already written to the per-day traffic file.
    private var countedUp: Int64 = 0
    private var countedDown: Int64 = 0

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        stopping = false
        AppGroup.writeLastError(nil)
        guard let plan = AppGroup.readPlan(), let root = JSON.object(plan.xrayConfig) else {
            return fail(L10n.tr("tunnel.error.noPlan"), completionHandler)
        }
        engine = plan.engine

        // A fresh local port and password for the SOCKS hop on every start, so no other
        // app on the phone can find and use it.
        let ports = XrayBridge.freePorts(2)
        socksPort = ports.first ?? Int.random(in: 20_000...50_000)
        metricsPort = ports.count > 1 ? ports[1] : socksPort + 1
        socksUser = Self.randomToken()
        socksPass = Self.randomToken()

        let excluded = Self.resolve(plan.excludedAddresses, timeout: 1.5)
        setTunnelNetworkSettings(Self.networkSettings(plan, excluded: excluded)) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                return self.fail(L10n.tr("tunnel.error.settings", error.localizedDescription), completionHandler)
            }
            self.queue.async {
                self.startCore(root: root, plan: plan, completionHandler: completionHandler)
            }
        }
    }

    /// The parts of a config only the extension knows: the SOCKS hop's port and password,
    /// the metrics port, the interface descriptor.
    private func runtime(_ config: JSONObject) -> JSONObject? {
        var root = config
        switch engine {
        case .hev:
            if var inbounds = root["inbounds"] as? [JSONObject], !inbounds.isEmpty {
                var inbound = inbounds[0]
                var settings = inbound.obj("settings") ?? [:]
                settings["auth"] = "password"
                settings["accounts"] = [["user": socksUser, "pass": socksPass]]
                settings["udp"] = true
                inbound["settings"] = settings
                inbound["port"] = socksPort
                inbound["listen"] = "127.0.0.1"
                inbounds[0] = inbound
                root["inbounds"] = inbounds
            }
        case .xray:
            guard let fd = Self.tunnelFileDescriptor() else { return nil }
            // Xray works on a duplicate of the descriptor; the original stays the system's.
            var env = root.obj("env") ?? [:]
            env["xray.tun.fd"] = String(fd)
            root["env"] = env
        }
        // Traffic counters and the observatory's findings, for the app and the guard.
        root["stats"] = JSONObject()
        root["policy"] = ["system": ["statsOutboundUplink": true, "statsOutboundDownlink": true]]
        root["metrics"] = ["tag": "metrics", "listen": "127.0.0.1:\(metricsPort)"]
        return root
    }

    private func startCore(root: JSONObject, plan: TunnelPlan, completionHandler: @escaping (Error?) -> Void) {
        guard let config = runtime(root) else {
            return fail(L10n.tr("tunnel.error.noInterface"), completionHandler)
        }
        XrayBridge.stop()
        let result = XrayBridge.run(config: JSON.string(config))
        guard result.ok else {
            return fail(L10n.tr("tunnel.error.core", result.error), completionHandler)
        }
        if engine == .hev {
            let hev = Self.hevConfig(mtu: plan.mtu, port: socksPort, user: socksUser, pass: socksPass)
            Socks5Tunnel.run(withConfig: .string(content: hev)) { [weak self] code in
                guard let self = self, !self.stopping else { return }
                // The bridge stopped while the tunnel was meant to be up.
                let message = L10n.tr("tunnel.error.bridge", Int(code))
                AppGroup.writeLastError(message)
                XrayBridge.stop()
                self.cancelTunnelWithError(NSError(domain: "Volta", code: Int(code),
                                                   userInfo: [NSLocalizedDescriptionKey: message]))
            }
        }
        countedUp = 0
        countedDown = 0
        startTrafficTimer()
        if let guardPlan = plan.guardPlan {
            let g = TunnelGuard(plan: guardPlan, liveId: plan.serverId, queue: queue)
            g.readCounters = { [weak self] in self?.counters() ?? (0, 0) }
            g.readObservatory = { [weak self] in self?.observatory() ?? [:] }
            g.switchTo = { [weak self] candidate in self?.switchCore(to: candidate) ?? false }
            guardian = g
            g.start()
        }
        completionHandler(nil)
    }

    /// Moves the live tunnel to another server: the core restarts with its config, the
    /// interface and the SOCKS hop stay. Runs on [queue].
    private func switchCore(to candidate: GuardCandidate) -> Bool {
        guard !stopping, let root = JSON.object(candidate.config), let config = runtime(root) else { return false }
        recordTraffic()
        XrayBridge.stop()
        let result = XrayBridge.run(config: JSON.string(config))
        // Metrics counters start again with the new core.
        if engine == .xray { countedUp = 0; countedDown = 0 }
        if !result.ok { AppGroup.writeLastError(L10n.tr("tunnel.error.core", result.error)) }
        return result.ok
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        stopping = true
        let engine = self.engine
        guardian?.stop()
        guardian = nil
        queue.async {
            self.recordTraffic()
            self.trafficTimer?.cancel()
            self.trafficTimer = nil
            if engine == .hev { Socks5Tunnel.quit() }
            XrayBridge.stop()
            completionHandler()
        }
    }

    override func handleAppMessage(_ messageData: Data, completionHandler: ((Data?) -> Void)?) {
        let message = String(decoding: messageData, as: UTF8.self)
        guard message == TunnelMessage.stats else { completionHandler?(nil); return }
        queue.async { [weak self] in
            guard let self = self else { completionHandler?(nil); return }
            let (up, down) = self.counters()
            var stats = TunnelMessage.Stats(uplink: up, downlink: down, engine: self.engine.rawValue)
            stats.memory = Self.residentMemory()
            completionHandler?(try? JSONEncoder().encode(stats))
        }
    }

    override func sleep(completionHandler: @escaping () -> Void) { completionHandler() }

    override func wake() { guardian?.checkSoon(reason: .wake) }

    // MARK: - Counters

    /// Bytes up and down so far: hev counts from the interface's point of view (what it
    /// sent is upload); the Xray engine reads the proxy outbound's counters.
    private func counters() -> (Int64, Int64) {
        switch engine {
        case .hev:
            let s = Socks5Tunnel.stats
            return (Int64(s.up.bytes), Int64(s.down.bytes))
        case .xray:
            return xrayTraffic() ?? (0, 0)
        }
    }

    private func startTrafficTimer() {
        trafficTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 30, repeating: 30, leeway: .seconds(5))
        timer.setEventHandler { [weak self] in self?.recordTraffic() }
        timer.resume()
        trafficTimer = timer
    }

    /// Adds what went through since the last time to today's total in the shared container.
    private func recordTraffic() {
        let (up, down) = counters()
        // A counter that went down was started again (a new core): count it from zero.
        let du = up >= countedUp ? up - countedUp : up
        let dd = down >= countedDown ? down - countedDown : down
        countedUp = up
        countedDown = down
        AppGroup.addTraffic(up: du, down: dd)
    }

    private func fail(_ message: String, _ completionHandler: @escaping (Error?) -> Void) {
        AppGroup.writeLastError(message)
        completionHandler(NSError(domain: "Volta", code: 1, userInfo: [NSLocalizedDescriptionKey: message]))
    }

    private func debugVars() -> JSONObject? {
        guard metricsPort > 0, let url = URL(string: "http://127.0.0.1:\(metricsPort)/debug/vars") else { return nil }
        var result: JSONObject?
        let done = DispatchSemaphore(value: 0)
        var request = URLRequest(url: url)
        request.timeoutInterval = 1
        URLSession.shared.dataTask(with: request) { data, _, _ in
            defer { done.signal() }
            guard let data = data else { return }
            result = try? JSONSerialization.jsonObject(with: data) as? JSONObject
        }.resume()
        _ = done.wait(timeout: .now() + 1.5)
        return result
    }

    /// Traffic through the proxy outbound, from Xray's metrics server.
    private func xrayTraffic() -> (Int64, Int64)? {
        guard let proxy = debugVars()?.obj("stats")?.obj("outbound")?.obj(ConfigBuilder.proxyTag) else { return nil }
        let up = (proxy["uplink"] as? NSNumber)?.int64Value ?? 0
        let down = (proxy["downlink"] as? NSNumber)?.int64Value ?? 0
        return (up, down)
    }

    /// What the core's observatory measured: `proxy` and every `alt-N`.
    private func observatory() -> [String: GuardPolicy.Observation] {
        guard let obs = debugVars()?.obj("observatory") else { return [:] }
        var out: [String: GuardPolicy.Observation] = [:]
        for (tag, value) in obs {
            guard let o = value as? JSONObject else { continue }
            let alive = (o["alive"] as? NSNumber)?.boolValue ?? false
            let delay = (o["delay"] as? NSNumber)?.intValue ?? -1
            out[tag] = GuardPolicy.Observation(alive: alive, delayMs: alive ? delay : -1)
        }
        return out
    }

    static func networkSettings(_ plan: TunnelPlan, excluded: [String]) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: "254.1.1.1")
        settings.mtu = NSNumber(value: plan.mtu)

        let v4 = NEIPv4Settings(addresses: ["198.18.0.1"], subnetMasks: ["255.255.255.0"])
        v4.includedRoutes = [NEIPv4Route.default()]
        // The local network stays local (printers, AirPlay, the router's page).
        var routes = [
            NEIPv4Route(destinationAddress: "10.0.0.0", subnetMask: "255.0.0.0"),
            NEIPv4Route(destinationAddress: "172.16.0.0", subnetMask: "255.240.0.0"),
            NEIPv4Route(destinationAddress: "192.168.0.0", subnetMask: "255.255.0.0"),
            NEIPv4Route(destinationAddress: "169.254.0.0", subnetMask: "255.255.0.0"),
            NEIPv4Route(destinationAddress: "224.0.0.0", subnetMask: "240.0.0.0")
        ]
        var v6Excluded: [NEIPv6Route] = []
        for address in excluded {
            if address.contains(":") {
                v6Excluded.append(NEIPv6Route(destinationAddress: address, networkPrefixLength: 128))
            } else {
                routes.append(NEIPv4Route(destinationAddress: address, subnetMask: "255.255.255.255"))
            }
        }
        v4.excludedRoutes = routes
        settings.ipv4Settings = v4

        if plan.ipv6 {
            // IPv6 goes into the tunnel too, so nothing leaks around it.
            let v6 = NEIPv6Settings(addresses: ["fd6e:a81b:704f:1211::1"], networkPrefixLengths: [64])
            v6.includedRoutes = [NEIPv6Route.default()]
            v6.excludedRoutes = v6Excluded + [NEIPv6Route(destinationAddress: "fe80::", networkPrefixLength: 10)]
            settings.ipv6Settings = v6
        }

        let dns = NEDNSSettings(servers: plan.dnsServers)
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        return settings
    }

    static func hevConfig(mtu: Int, port: Int, user: String, pass: String) -> String {
        """
        tunnel:
          mtu: \(mtu)
          ipv4: 198.18.0.1
          ipv6: 'fd6e:a81b:704f:1211::1'
        socks5:
          port: \(port)
          address: 127.0.0.1
          udp: 'udp'
          username: '\(user)'
          password: '\(pass)'
        misc:
          task-stack-size: 24576
          tcp-buffer-size: 4096
          max-session-count: 800
          connect-timeout: 10000
          tcp-read-write-timeout: 300000
          udp-read-write-timeout: 60000
          log-file: stderr
          log-level: error
          limit-nofile: 65535
        """
    }

    /// The utun descriptor NetworkExtension opened for this tunnel: the socket whose
    /// interface name (UTUN_OPT_IFNAME) starts with "utun".
    static func tunnelFileDescriptor() -> Int32? {
        var buffer = [CChar](repeating: 0, count: Int(IFNAMSIZ))
        for fd: Int32 in 0...1024 {
            var length = socklen_t(buffer.count)
            // SYSPROTO_CONTROL = 2, UTUN_OPT_IFNAME = 2
            if getsockopt(fd, 2, 2, &buffer, &length) == 0 {
                let name = String(cString: buffer)
                if name.hasPrefix("utun") { return fd }
            }
        }
        return nil
    }

    /// Addresses of the servers, so traffic to them can skip the tunnel. Host names are
    /// looked up in parallel, at most [timeout] seconds in all.
    static func resolve(_ hosts: [String], timeout: TimeInterval) -> [String] {
        final class Collector {
            private let lock = NSLock()
            private var items = Set<String>()
            func add(_ values: [String]) { lock.lock(); values.forEach { items.insert($0) }; lock.unlock() }
            var all: Set<String> { lock.lock(); defer { lock.unlock() }; return items }
        }
        let found = Collector()
        var names: [String] = []
        for host in hosts.prefix(96) {
            if isIPAddress(host) { found.add([host]) } else { names.append(host) }
        }
        if !names.isEmpty {
            let group = DispatchGroup()
            for name in names {
                group.enter()
                DispatchQueue.global(qos: .userInitiated).async {
                    found.add(PacketTunnelProvider.lookup(name))
                    group.leave()
                }
            }
            _ = group.wait(timeout: .now() + timeout)
        }
        return found.all.filter { !$0.hasPrefix("127.") && $0 != "::1" && $0 != "0.0.0.0" }.sorted()
    }

    private static func isIPAddress(_ s: String) -> Bool {
        var v4 = in_addr()
        var v6 = in6_addr()
        return inet_pton(AF_INET, s, &v4) == 1 || inet_pton(AF_INET6, s, &v6) == 1
    }

    private static func lookup(_ host: String) -> [String] {
        var hints = addrinfo()
        hints.ai_socktype = SOCK_STREAM
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(host, nil, &hints, &result) == 0, let first = result else { return [] }
        defer { freeaddrinfo(first) }
        var out: [String] = []
        var cursor: UnsafeMutablePointer<addrinfo>? = first
        while let info = cursor {
            var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(info.pointee.ai_addr, info.pointee.ai_addrlen, &buffer, socklen_t(buffer.count),
                           nil, 0, NI_NUMERICHOST) == 0 {
                out.append(String(cString: buffer))
            }
            cursor = info.pointee.ai_next
        }
        return out
    }

    private static func randomToken() -> String {
        let alphabet = Array("abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789")
        return String((0..<20).map { _ in alphabet.randomElement()! })
    }

    /// The extension's footprint as iOS counts it against the limit.
    static func residentMemory() -> Int64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return kr == KERN_SUCCESS ? Int64(info.phys_footprint) : 0
    }
}
