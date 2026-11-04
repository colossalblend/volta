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
/// directly. Memory stays within the ~50 MB iOS gives an extension: no geo files, the
/// core's Go heap capped by libXray (30 MB, GC every second), small hev buffers.
final class PacketTunnelProvider: NEPacketTunnelProvider {

    private var engine: TunnelEngine = .hev
    private var metricsPort = 0
    private var stopping = false
    private let queue = DispatchQueue(label: "com.ars3nb.volta.tunnel")

    override func startTunnel(options: [String: NSObject]?, completionHandler: @escaping (Error?) -> Void) {
        stopping = false
        AppGroup.writeLastError(nil)
        guard let plan = AppGroup.readPlan(), var root = JSON.object(plan.xrayConfig) else {
            return fail(L10n.tr("tunnel.error.noPlan"), completionHandler)
        }
        engine = plan.engine

        // A fresh local port and password for the SOCKS hop on every start, so no other
        // app on the phone can find and use it.
        let ports = XrayBridge.freePorts(2)
        let socksPort = ports.first ?? Int.random(in: 20_000...50_000)
        metricsPort = ports.count > 1 ? ports[1] : socksPort + 1
        let user = Self.randomToken()
        let pass = Self.randomToken()

        switch engine {
        case .hev:
            if var inbounds = root["inbounds"] as? [JSONObject], !inbounds.isEmpty {
                var inbound = inbounds[0]
                var settings = inbound.obj("settings") ?? [:]
                settings["auth"] = "password"
                settings["accounts"] = [["user": user, "pass": pass]]
                settings["udp"] = true
                inbound["settings"] = settings
                inbound["port"] = socksPort
                inbound["listen"] = "127.0.0.1"
                inbounds[0] = inbound
                root["inbounds"] = inbounds
            }
        case .xray:
            root["stats"] = JSONObject()
            root["policy"] = ["system": ["statsOutboundUplink": true, "statsOutboundDownlink": true]]
            root["metrics"] = ["tag": "metrics", "listen": "127.0.0.1:\(metricsPort)"]
        }

        let excluded = Self.resolve(plan.excludedAddresses, timeout: 1.5)
        setTunnelNetworkSettings(Self.networkSettings(plan, excluded: excluded)) { [weak self] error in
            guard let self = self else { return }
            if let error = error {
                return self.fail(L10n.tr("tunnel.error.settings", error.localizedDescription), completionHandler)
            }
            self.queue.async {
                self.startCore(root: root, plan: plan, socksPort: socksPort, user: user, pass: pass,
                               completionHandler: completionHandler)
            }
        }
    }

    private func startCore(root: JSONObject, plan: TunnelPlan, socksPort: Int, user: String, pass: String,
                           completionHandler: @escaping (Error?) -> Void) {
        var root = root
        guard let fd = Self.tunnelFileDescriptor() else {
            return fail(L10n.tr("tunnel.error.noInterface"), completionHandler)
        }
        if engine == .xray {
            // Xray works on a duplicate of the descriptor; the original stays the system's.
            var env = root.obj("env") ?? [:]
            env["xray.tun.fd"] = String(fd)
            root["env"] = env
        }
        XrayBridge.stop()
        let result = XrayBridge.run(config: JSON.string(root))
        guard result.ok else {
            return fail(L10n.tr("tunnel.error.core", result.error), completionHandler)
        }
        if engine == .hev {
            let config = Self.hevConfig(mtu: plan.mtu, port: socksPort, user: user, pass: pass)
            Socks5Tunnel.run(withConfig: .string(content: config)) { [weak self] code in
                guard let self = self, !self.stopping else { return }
                // The bridge stopped while the tunnel was meant to be up.
                let message = L10n.tr("tunnel.error.bridge", Int(code))
                AppGroup.writeLastError(message)
                XrayBridge.stop()
                self.cancelTunnelWithError(NSError(domain: "Volta", code: Int(code),
                                                   userInfo: [NSLocalizedDescriptionKey: message]))
            }
        }
        completionHandler(nil)
    }

    override func stopTunnel(with reason: NEProviderStopReason, completionHandler: @escaping () -> Void) {
        stopping = true
        let engine = self.engine
        queue.async {
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
            var stats = TunnelMessage.Stats(uplink: 0, downlink: 0, engine: self.engine.rawValue)
            switch self.engine {
            case .hev:
                let s = Socks5Tunnel.stats
                // hev counts from the interface's point of view: what it sent is upload.
                stats.uplink = Int64(s.up.bytes)
                stats.downlink = Int64(s.down.bytes)
            case .xray:
                if let traffic = self.xrayTraffic() {
                    stats.uplink = traffic.0
                    stats.downlink = traffic.1
                }
            }
            stats.memory = Self.residentMemory()
            completionHandler?(try? JSONEncoder().encode(stats))
        }
    }

    override func sleep(completionHandler: @escaping () -> Void) { completionHandler() }

    override func wake() {}

    // MARK: - Pieces

    private func fail(_ message: String, _ completionHandler: @escaping (Error?) -> Void) {
        AppGroup.writeLastError(message)
        completionHandler(NSError(domain: "Volta", code: 1, userInfo: [NSLocalizedDescriptionKey: message]))
    }

    /// Traffic through the proxy outbound, from Xray's metrics server.
    private func xrayTraffic() -> (Int64, Int64)? {
        guard metricsPort > 0, let url = URL(string: "http://127.0.0.1:\(metricsPort)/debug/vars") else { return nil }
        var result: (Int64, Int64)?
        let done = DispatchSemaphore(value: 0)
        var request = URLRequest(url: url)
        request.timeoutInterval = 1
        URLSession.shared.dataTask(with: request) { data, _, _ in
            defer { done.signal() }
            guard let data = data,
                  let root = try? JSONSerialization.jsonObject(with: data) as? JSONObject,
                  let proxy = (root.obj("stats")?.obj("outbound"))?.obj(ConfigBuilder.proxyTag) else { return }
            let up = (proxy["uplink"] as? NSNumber)?.int64Value ?? 0
            let down = (proxy["downlink"] as? NSNumber)?.int64Value ?? 0
            result = (up, down)
        }.resume()
        _ = done.wait(timeout: .now() + 1.5)
        return result
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
