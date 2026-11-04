import Foundation

/// Builds the Xray-core config for one server: a port of the Android ConfigBuilder.
///
/// The tunnel side differs by engine:
///  - `.hev`: a SOCKS inbound on 127.0.0.1 (random port and password per session) that
///    hev-socks5-tunnel feeds from the VPN interface, as on Android;
///  - `.xray`: Xray-core's own TUN inbound on the extension's interface (the extension
///    puts the descriptor into `env` before starting).
///
/// No geoip/geosite files: the Network Extension has about 50 MB of memory, and the full
/// files alone would take most of it. Local networks are spelled out as CIDRs instead,
/// and "Russian sites directly" goes by domain suffix.
enum ConfigBuilder {

    static let proxyTag = "proxy"
    static let probeURL = "https://www.gstatic.com/generate_204"

    struct Options {
        var engine: TunnelEngine = .hev
        var socksPort: Int = 10808
        var socksUser: String = ""
        var socksPass: String = ""
        /// The core answers DNS itself, through the tunnel.
        var localDns: Bool = true
        /// Russian domains and the usual whitelist go around the VPN.
        var russiaDirect: Bool = false
        /// Xray's metrics server (traffic counters) on this local port; 0 = off.
        var metricsPort: Int = 0
        var mtu: Int = 1500
        var logLevel: String = "warning"
    }

    /// The core cannot dial this server; the message is for the user.
    struct Unsupported: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // MARK: - Whole config

    static func build(server: Server, options: Options) throws -> String {
        var root: JSONObject = [:]
        root["log"] = ["loglevel": options.logLevel, "access": "none"]
        root["inbounds"] = [inbound(options)]

        var outbounds: [Any] = [try outbound(for: server, tag: proxyTag)]
        outbounds += chainedOutbounds(server, primaryTag: proxyTag)
        outbounds.append(directOutbound())
        outbounds.append(blockOutbound())
        if options.localDns { outbounds.append(dnsOutbound()) }
        root["outbounds"] = outbounds

        root["routing"] = routing(options)
        if options.localDns { root["dns"] = dns(options) }

        if options.metricsPort > 0 {
            root["stats"] = JSONObject()
            root["policy"] = [
                "system": ["statsOutboundUplink": true, "statsOutboundDownlink": true]
            ]
            root["metrics"] = ["tag": "metrics", "listen": "127.0.0.1:\(options.metricsPort)"]
        }
        return JSON.string(root)
    }

    /// The smallest config the core's ping takes: the server's outbound and a direct one.
    static func buildProbe(server: Server) throws -> String {
        var outbounds: [Any] = [try outbound(for: server, tag: proxyTag)]
        outbounds += chainedOutbounds(server, primaryTag: proxyTag)
        outbounds.append(directOutbound())
        return JSON.string(["log": ["loglevel": "none"], "outbounds": outbounds] as JSONObject)
    }

    static func canDial(_ server: Server) -> Bool { dialProblem(server) == nil }

    /// Why the server cannot be dialled, or nil when it can.
    static func dialProblem(_ server: Server) -> String? {
        do {
            _ = try outbound(for: server, tag: proxyTag)
            return nil
        } catch {
            return (error as? LocalizedError)?.errorDescription ?? error.localizedDescription
        }
    }

    // MARK: - Inbounds

    private static func sniffing() -> JSONObject {
        // routeOnly keeps the original destination for the outbound (what REALITY and
        // SNI-pinned nodes need); only the routing table sees the host name.
        ["enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true]
    }

    private static func inbound(_ o: Options) -> JSONObject {
        switch o.engine {
        case .xray:
            return [
                "tag": "tun",
                "port": 0,
                "protocol": "tun",
                "settings": ["name": "utun", "mtu": o.mtu] as JSONObject,
                "sniffing": sniffing()
            ]
        case .hev:
            var settings: JSONObject = ["udp": true]
            if o.socksUser.isBlank {
                settings["auth"] = "noauth"
            } else {
                settings["auth"] = "password"
                settings["accounts"] = [["user": o.socksUser, "pass": o.socksPass]]
            }
            return [
                "tag": "socks",
                "port": o.socksPort,
                "listen": "127.0.0.1",
                "protocol": "socks",
                "settings": settings,
                "sniffing": sniffing()
            ]
        }
    }

    // MARK: - Outbounds

    private static func directOutbound() -> JSONObject {
        ["tag": "direct", "protocol": "freedom", "settings": ["domainStrategy": "UseIP"]]
    }

    private static func blockOutbound() -> JSONObject {
        ["tag": "block", "protocol": "blackhole", "settings": ["response": ["type": "http"]]]
    }

    private static func dnsOutbound() -> JSONObject {
        ["tag": "dns-out", "protocol": "dns", "settings": ["nonIPQuery": "skip"]]
    }

    /// One proxy outbound. A provider's own JSON outbound wins over the field-by-field
    /// build, so exotic configs survive untouched.
    static func outbound(for server: Server, tag: String) throws -> JSONObject {
        if var raw = try rawOutbound(server) {
            raw["tag"] = tag
            return raw
        }
        var ob: JSONObject = ["tag": tag]
        switch server.proto {
        case .vless:
            ob["protocol"] = "vless"
            var user: JSONObject = ["encryption": server.encryption.ifBlank("none")]
            if let flow = flowFor(server) { user["flow"] = flow }
            ob["settings"] = try vnext(server, user: user)
        case .vmess:
            ob["protocol"] = "vmess"
            let alterId = Int(server.extra("alterId", "aid")) ?? 0
            ob["settings"] = try vnext(server, user: ["security": server.encryption.ifBlank("auto"), "alterId": alterId])
        case .trojan:
            ob["protocol"] = "trojan"
            var entry: JSONObject = ["address": server.address, "port": server.port, "password": server.uuid]
            if let flow = flowFor(server) { entry["flow"] = flow }
            ob["settings"] = ["servers": [entry]]
        case .shadowsocks:
            if server.encryption.isBlank || server.encryption == "none" && server.uuid.isBlank {
                throw Unsupported(message: L10n.tr("server.error.incomplete"))
            }
            ob["protocol"] = "shadowsocks"
            ob["settings"] = ["servers": [[
                "address": server.address, "port": server.port,
                "method": server.encryption, "password": server.uuid
            ] as JSONObject]]
        case .socks, .http:
            ob["protocol"] = server.proto == .socks ? "socks" : "http"
            let user = server.extra("user", "username").ifBlank(server.host)
            var entry: JSONObject = ["address": server.address, "port": server.port]
            if !user.isBlank {
                var account: JSONObject = ["user": user, "pass": server.uuid]
                if server.proto == .socks { account["level"] = 0 }
                entry["users"] = [account]
            }
            ob["settings"] = ["servers": [entry]]
        case .wireguard:
            ob["protocol"] = "wireguard"
            ob["settings"] = try wireGuardSettings(server)
            // WireGuard carries its own transport.
            return ob
        case .hysteria2:
            ob["protocol"] = "hysteria"
            ob["settings"] = ["version": 2, "address": server.address, "port": server.port] as JSONObject
        case .amneziawg, .hysteria, .openflux:
            throw Unsupported(message: L10n.tr("server.error.notInThisBuild", server.proto.label))
        case .rawJson, .unknown:
            throw Unsupported(message: L10n.tr("server.error.unknownType", server.remark))
        }
        ob["streamSettings"] = try streamSettings(server)
        if let mux = muxSettings(server) { ob["mux"] = mux }
        return ob
    }

    /// XTLS flow exists only over TLS/REALITY on raw TCP. On ws/grpc/xhttp the core takes
    /// it and then fails to dial, which looks like a dead server, so it is dropped.
    private static func flowFor(_ server: Server) -> String? {
        let flow = server.flow.trimmed
        if flow.isEmpty { return nil }
        let security = server.security.lowercased()
        let network = server.network.lowercased()
        let tlsLike = security == "tls" || security == "reality"
        let raw = network.isEmpty || network == "tcp" || network == "raw"
        return tlsLike && raw ? flow : nil
    }

    private static func vnext(_ server: Server, user extra: JSONObject) throws -> JSONObject {
        if server.uuid.isBlank { throw Unsupported(message: L10n.tr("server.error.noKey", server.remark)) }
        var user: JSONObject = ["id": server.uuid, "level": 0]
        for (k, v) in extra { user[k] = v }
        return ["vnext": [["address": server.address, "port": server.port, "users": [user]] as JSONObject]]
    }

    private static func wireGuardSettings(_ server: Server) throws -> JSONObject {
        let secret = server.extra("privateKey", "privatekey", "secretKey").ifBlank(server.uuid)
        let publicKey = server.extra("publicKey", "publickey", "peerPublicKey").ifBlank(server.pbk)
        if secret.isBlank || publicKey.isBlank {
            throw Unsupported(message: L10n.tr("server.error.noKeys", server.remark))
        }
        let local = server.extra("address", "localAddress", "ip").ifBlank("172.16.0.2/32")
        let allowed = server.extra("allowedIPs", "allowedips").ifBlank("0.0.0.0/0,::/0")
        let psk = server.extra("preSharedKey", "presharedkey")
        let reserved = server.extra("reserved")
        let mtu = Int(server.extra("mtu")) ?? 1420
        var peer: JSONObject = [
            "publicKey": publicKey,
            "endpoint": "\(server.address):\(server.port)",
            "allowedIPs": splitList(allowed)
        ]
        if !psk.isBlank { peer["preSharedKey"] = psk }
        if !reserved.isBlank { peer["reserved"] = splitList(reserved).compactMap { Int($0) } }
        return [
            "secretKey": secret,
            "address": splitList(local),
            "mtu": mtu,
            // Userspace stack: an app has no kernel TUN of its own to give it.
            "noKernelTun": true,
            "peers": [peer]
        ]
    }

    /// Outbound protocols this core can dial.
    private static let dialable: Set<String> = [
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria"
    ]

    /// The provider's own outbound, when the server came from JSON. Its protocol is still
    /// checked: one undialable entry would make the whole config fail to load.
    private static func rawOutbound(_ server: Server) throws -> JSONObject? {
        if server.rawJson.isBlank || !server.rawJson.contains("\"protocol\"") { return nil }
        guard let raw = JSON.object(server.rawJson) else { return nil }
        let candidate: JSONObject?
        if let list = raw.arr("outbounds") {
            candidate = list.compactMap { $0 as? JSONObject }.first(where: isProxyOutbound)
        } else {
            candidate = isProxyOutbound(raw) ? raw : nil
        }
        guard let outbound = candidate else { return nil }
        let proto = outbound.str("protocol").lowercased()
        if !dialable.contains(proto) {
            throw Unsupported(message: L10n.tr("server.error.typeNotSupported", server.remark))
        }
        return dropRetired(outbound)
    }

    /// Fields this core refuses outright; without them the certificate is simply checked.
    private static func dropRetired(_ outbound: JSONObject) -> JSONObject {
        var ob = outbound
        if var stream = ob.obj("streamSettings"), var tls = stream.obj("tlsSettings"), tls["allowInsecure"] != nil {
            tls.removeValue(forKey: "allowInsecure")
            stream["tlsSettings"] = tls
            ob["streamSettings"] = stream
        }
        return ob
    }

    /// Outbounds the imported one dials through (`sockopt.dialerProxy`, `proxySettings.tag`).
    private static func chainedOutbounds(_ server: Server, primaryTag: String) -> [JSONObject] {
        guard !server.rawJson.isBlank, let raw = JSON.object(server.rawJson),
              let all = raw.arr("outbounds")?.compactMap({ $0 as? JSONObject }) else { return [] }
        var byTag: [String: JSONObject] = [:]
        for ob in all { byTag[ob.str("tag")] = ob }
        var wanted: [String] = []
        func collect(_ from: JSONObject, _ depth: Int) {
            if depth > 4 { return }
            let next = [
                from.obj("proxySettings")?.str("tag") ?? "",
                from.obj("streamSettings")?.obj("sockopt")?.str("dialerProxy") ?? ""
            ]
            for tag in next where !tag.isBlank && !wanted.contains(tag) {
                wanted.append(tag)
                if let ob = byTag[tag] { collect(ob, depth + 1) }
            }
        }
        if let primary = try? rawOutbound(server) { collect(primary, 0) }
        return wanted.compactMap { byTag[$0] }.map(dropRetired).filter { $0.str("tag") != primaryTag }
    }

    private static func isProxyOutbound(_ ob: JSONObject) -> Bool {
        let proto = ob.str("protocol")
        let tag = ob.str("tag")
        return !proto.isBlank &&
            !["freedom", "blackhole", "dns", "loopback"].contains(proto) &&
            !["direct", "block", "dns-out", "api"].contains(tag)
    }

    // MARK: - streamSettings

    private static func streamSettings(_ server: Server) throws -> JSONObject {
        var stream: JSONObject = [:]
        let network = server.network.ifBlank("tcp").lowercased()
        let security = server.security.ifBlank("none").lowercased()

        // The core dropped the standalone h2 and QUIC transports.
        let effective: String
        switch network {
        case "raw": effective = "tcp"
        case "h2", "http", "splithttp": effective = "xhttp"
        case "mkcp": effective = "kcp"
        case "quic": throw Unsupported(message: L10n.tr("server.error.oldTransport", server.remark))
        default: effective = network
        }
        // An unknown security value would make the core refuse the whole config.
        guard ["none", "tls", "reality"].contains(security) else {
            throw Unsupported(message: L10n.tr("server.error.encryption", server.remark))
        }
        stream["network"] = effective
        stream["security"] = security

        if security == "reality" {
            if server.pbk.isBlank { throw Unsupported(message: L10n.tr("server.error.noRealityKey", server.remark)) }
            var reality: JSONObject = [
                "serverName": server.sni.ifBlank(server.host.ifBlank(server.address)),
                "fingerprint": server.fp.ifBlank("chrome"),
                "publicKey": server.pbk,
                "shortId": server.sid,
                "show": false
            ]
            if !server.spx.isBlank { reality["spiderX"] = server.spx }
            let pqv = server.extra("pqv", "mldsa65Verify")
            if !pqv.isBlank { reality["mldsa65Verify"] = pqv }
            stream["realitySettings"] = reality
        } else if security == "tls" {
            var tls: JSONObject = ["serverName": tlsServerName(server)]
            if !server.fp.isBlank { tls["fingerprint"] = server.fp }
            if !server.alpn.isBlank { tls["alpn"] = splitList(server.alpn) }
            // allowInsecure is gone from the core; a pinned certificate replaces it.
            let pins = pinsOf(server)
            if !pins.isEmpty { tls["pinnedPeerCertSha256"] = pins.joined(separator: ",") }
            let vcn = server.extra("vcn", "verifyPeerCertByName")
            if !vcn.isBlank { tls["verifyPeerCertByName"] = vcn }
            stream["tlsSettings"] = tls
        }

        switch effective {
        case "ws":
            var ws: JSONObject = ["path": server.path.ifBlank("/")]
            if !server.host.isBlank {
                ws["host"] = server.host
                ws["headers"] = ["Host": server.host]
            }
            stream["wsSettings"] = ws
        case "httpupgrade":
            var hu: JSONObject = ["path": server.path.ifBlank("/")]
            if !server.host.isBlank { hu["host"] = server.host }
            stream["httpupgradeSettings"] = hu
        case "xhttp":
            var xh: JSONObject = ["path": server.path.ifBlank("/")]
            if !server.host.isBlank { xh["host"] = server.host }
            let legacyH2 = ["h2", "http"].contains(server.network.lowercased())
            xh["mode"] = server.extra("mode").ifBlank(legacyH2 ? "stream-one" : "auto")
            if let extra = JSON.object(server.extra("extra")) { xh["extra"] = extra }
            stream["xhttpSettings"] = xh
        case "grpc":
            var grpc: JSONObject = [
                "serviceName": server.serviceName.ifBlank(server.extra("serviceName", "path")),
                "multiMode": server.extra("mode").lowercased() == "multi"
            ]
            let authority = server.extra("authority")
            if !authority.isBlank { grpc["authority"] = authority }
            stream["grpcSettings"] = grpc
        case "kcp":
            var kcp: JSONObject = [
                "mtu": Int(server.extra("mtu")) ?? 1350,
                "tti": Int(server.extra("tti")) ?? 50,
                "uplinkCapacity": 12,
                "downlinkCapacity": 100,
                "congestion": server.extra("congestion") == "true",
                "header": ["type": server.headerType.ifBlank("none")]
            ]
            let seed = server.extra("seed", "serviceName")
            if !seed.isBlank { kcp["seed"] = seed }
            stream["kcpSettings"] = kcp
        case "hysteria":
            stream["hysteriaSettings"] = try hysteriaSettings(server)
        case "tcp":
            if server.headerType.lowercased() == "http" {
                var headers: JSONObject = [:]
                if !server.host.isBlank { headers["Host"] = splitList(server.host) }
                stream["tcpSettings"] = [
                    "header": [
                        "type": "http",
                        "request": [
                            "version": "1.1",
                            "method": "GET",
                            "path": [server.path.ifBlank("/")],
                            "headers": headers
                        ] as JSONObject
                    ] as JSONObject
                ]
            }
        default:
            break
        }

        // Salamander obfuscation is a final-mask layer in this core.
        let obfs = server.extra("obfs")
        let obfsPassword = server.extra("obfs-password", "obfsPassword", "obfsParam")
        if obfs.lowercased() == "salamander" && !obfsPassword.isBlank {
            stream["finalmask"] = [
                "udp": [["type": "salamander", "settings": ["password": obfsPassword]] as JSONObject]
            ]
        }
        return stream
    }

    private static func hysteriaSettings(_ server: Server) throws -> JSONObject {
        let auth = server.uuid.ifBlank(server.extra("auth", "password"))
        if auth.isBlank { throw Unsupported(message: L10n.tr("server.error.noPassword", server.remark)) }
        var h: JSONObject = ["version": 2, "auth": auth]
        let up = server.extra("upmbps", "up", "upMbps")
        let down = server.extra("downmbps", "down", "downMbps")
        if !up.isBlank && !down.isBlank {
            h["congestion"] = "brutal"
            h["brutalUp"] = "\(String(up.filter { $0.isNumber }).ifBlank("50"))mbps"
            h["brutalDown"] = "\(String(down.filter { $0.isNumber }).ifBlank("50"))mbps"
        }
        if let idle = Int(server.extra("udpIdleTimeout")) { h["udpIdleTimeout"] = idle }
        let hop = server.extra("mport", "ports", "hopPorts")
        if !hop.isBlank {
            h["udphop"] = ["port": hop, "interval": Int(server.extra("hopInterval")) ?? 30] as JSONObject
        }
        return h
    }

    /// Mux never helps XTLS-flow nodes and hurts UDP-heavy ones; only when asked for.
    private static func muxSettings(_ server: Server) -> JSONObject? {
        if flowFor(server) != nil { return nil }
        guard let concurrency = Int(server.extra("mux", "concurrency")), concurrency > 0 else { return nil }
        return ["enabled": true, "concurrency": concurrency, "xudpConcurrency": 8, "xudpProxyUDP443": "reject"]
    }

    /// Certificate pins from the link (pcs); malformed ones would sink the config.
    static func pinsOf(_ server: Server) -> [String] {
        server.extra("pcs", "pinnedPeerCertSha256")
            .components(separatedBy: ",")
            .map { $0.trimmed.replacingOccurrences(of: ":", with: "").lowercased() }
            .filter { $0.count == 64 && $0.allSatisfy { $0.isHexDigit } }
    }

    static func tlsServerName(_ server: Server) -> String {
        server.sni.ifBlank(server.host.ifBlank(server.address))
    }

    // MARK: - Routing and DNS

    /// Addresses that never leave the device. Spelled out: there is no geoip.dat here.
    static let privateRanges = [
        "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
        "::1/128", "fc00::/7", "fe80::/10", "ff00::/8"
    ]

    /// Domains that stay reachable under the mobile whitelist (Android's list).
    static let russianWhitelist = [
        "gosuslugi.ru", "mos.ru", "kremlin.ru", "government.ru", "cbr.ru", "nalog.gov.ru", "pochta.ru",
        "vk.com", "vk.ru", "ok.ru", "mail.ru", "dzen.ru", "rutube.ru", "yandex.ru", "ya.ru", "yandex.net",
        "ozon.ru", "wildberries.ru", "avito.ru", "megamarket.ru", "tinkoff.ru", "tbank.ru", "vtb.ru",
        "alfabank.ru", "sberbank.ru", "mts.ru", "megafon.ru", "beeline.ru", "tele2.ru", "rzd.ru",
        "aeroflot.ru", "hh.ru", "2gis.ru", "kinopoisk.ru", "ivi.ru", "okko.tv", "max.ru"
    ]

    static let russianSuffixes = ["domain:ru", "domain:su", "domain:xn--p1ai"]

    private static func routing(_ o: Options) -> JSONObject {
        var rules: [JSONObject] = []
        if o.localDns {
            rules.append(["type": "field", "port": "53", "network": "udp", "outboundTag": "dns-out"])
        }
        rules.append(["type": "field", "ip": privateRanges, "outboundTag": "direct"])
        if o.russiaDirect {
            rules.append([
                "type": "field",
                "domain": russianSuffixes + russianWhitelist.map { "domain:\($0)" },
                "outboundTag": "direct"
            ])
        }
        rules.append(["type": "field", "network": "tcp,udp", "outboundTag": proxyTag])
        // AsIs: the destination is already an address (the system resolved it through the
        // tunnel), and sniffing gives the host name for the domain rules.
        return ["domainStrategy": "AsIs", "rules": rules]
    }

    private static func dns(_ o: Options) -> JSONObject {
        var servers: [Any] = ["https://1.1.1.1/dns-query"]
        if o.russiaDirect {
            servers.append(["address": "localhost", "domains": russianSuffixes] as JSONObject)
        }
        servers.append("localhost")
        // IPv4 answers only: most servers have no IPv6 exit, and apps then wait for it.
        return ["servers": servers, "queryStrategy": "UseIPv4", "disableCache": false]
    }

    // MARK: - Helpers

    static func splitList(_ value: String) -> [String] {
        value.components(separatedBy: CharacterSet(charactersIn: ",; ")).map { $0.trimmed }.filter { !$0.isEmpty }
    }
}
