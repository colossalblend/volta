import Foundation

/// Builds the Xray-core config for one server: a port of the Android ConfigBuilder,
/// routing profiles, provider rules, AdGuard and the torrent filter included.
///
/// The tunnel side differs by engine:
///  - `.hev`: a SOCKS inbound on 127.0.0.1 (random port and password per session) that
///    hev-socks5-tunnel feeds from the VPN interface, as on Android;
///  - `.xray`: Xray-core's own TUN inbound on the extension's interface (the extension
///    puts the descriptor into `env` before starting).
///
/// Geo lists keep their Android names (`geoip:private`, `geosite:category-ru`…). The
/// Network Extension has about 50 MB, so the app writes trimmed copies of the lists a config
/// names before each connect (see GeoData) and points the core at them with `env`.
enum ConfigBuilder {

    static let proxyTag = "proxy"
    /// Other servers of the group, watched by the core's observatory for the guard.
    static let alternatePrefix = "alt-"
    static let probeURL = "https://www.gstatic.com/generate_204"

    /// The DNS server iOS is given while the core answers lookups itself: a free address in
    /// the tunnel's own 198.18.0.0/24 (Android uses 10.10.14.2 the same way). Nothing
    /// listens there; the routing hands every lookup sent to it to the core's resolver. A
    /// public resolver here would let apps upgrade to encrypted DNS past AdGuard.
    static let tunnelDns = "198.18.0.2"

    /// Where lookups the core does not answer itself (MX, SRV, TXT…) are passed on.
    static let passThroughDns = "1.1.1.1"

    struct Options {
        var engine: TunnelEngine = .hev
        var socksPort: Int = 10808
        var socksUser: String = ""
        var socksPass: String = ""
        /// The core answers DNS itself, through the tunnel.
        var localDns: Bool = true
        /// Xray's metrics server (traffic counters, observatory) on this local port; 0 = off.
        var metricsPort: Int = 0
        var mtu: Int = 1500
        var logLevel: String = "warning"
        /// The routing profile this server runs with (RoutingResolver), nil = none.
        var profile: RoutingProfile? = nil
        /// The subscription's own rule JSON (only for its own profile).
        var providerRoutingJson: String = ""
        /// A JSON subscription's server carries its own routing: use it as shipped.
        var preferServerRouting: Bool = false
        /// The subscription's own geo files (`ext:` names), nil = the app's lists.
        var geo: GeoNames? = nil
        /// AdGuard DNS for every lookup in the tunnel plus the core's ad list.
        var adBlock: AdBlockMode = .off
        /// Drops BitTorrent whatever the profile says.
        var blockTorrents: Bool = false
        /// Other servers of the group: added as `alt-N` outbounds that only the observatory
        /// dials, so the guard in the extension knows which one to move to.
        var alternates: [Server] = []
        var probeIntervalSeconds: Int = 120
        /// Folder with the trimmed geo lists (`xray.location.asset`).
        var assetDir: String? = nil

        /// Whether the core's DNS module runs (AdGuard needs it).
        var dnsInCore: Bool { localDns || adBlock != .off }
    }

    /// The core cannot dial this server; the message is for the user.
    struct Unsupported: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // MARK: - Whole config

    static func build(server: Server, options: Options) throws -> String {
        JSON.string(try buildObject(server: server, options: options))
    }

    static func buildObject(server: Server, options: Options) throws -> JSONObject {
        var root: JSONObject = [:]
        root["log"] = ["loglevel": options.logLevel, "access": "none"]
        root["inbounds"] = [inbound(options)]

        var outbounds: [Any] = [try outbound(for: server, tag: proxyTag)]
        var chainedTags = Set<String>()
        for extra in chainedOutbounds(server, primaryTag: proxyTag) where chainedTags.insert(extra.str("tag")).inserted {
            outbounds.append(extra)
        }
        let alternates = usableAlternates(server, options.alternates)
        for (n, alt) in alternates.enumerated() {
            if let ob = try? outbound(for: alt, tag: alternatePrefix + String(n)) { outbounds.append(ob) }
        }
        let alternateCount = alternates.count
        outbounds.append(directOutbound())
        outbounds.append(blockOutbound())
        if options.dnsInCore { outbounds.append(dnsOutbound()) }
        root["outbounds"] = outbounds

        root["routing"] = routing(server: server, options: options)
        if options.dnsInCore { root["dns"] = dns(options) }

        if options.metricsPort > 0 {
            root["stats"] = JSONObject()
            root["policy"] = [
                "system": ["statsOutboundUplink": true, "statsOutboundDownlink": true]
            ]
            root["metrics"] = ["tag": "metrics", "listen": "127.0.0.1:\(options.metricsPort)"]
        }
        if alternateCount > 0 || options.metricsPort > 0 {
            var subjects = [proxyTag]
            if alternateCount > 0 { subjects.append(alternatePrefix) }
            root["observatory"] = [
                "subjectSelector": subjects,
                "probeURL": probeURL,
                "probeInterval": "\(options.probeIntervalSeconds)s",
                "enableConcurrency": true
            ] as JSONObject
        }
        if let dir = options.assetDir {
            var env = root.obj("env") ?? [:]
            env["xray.location.asset"] = dir
            root["env"] = env
        }
        return root
    }

    /// The other servers a config carries as `alt-N`, in that order: only nodes that dial
    /// on their own (a chained one would need its helpers' tags).
    static func usableAlternates(_ server: Server, _ alternates: [Server]) -> [Server] {
        alternates.filter { alt in
            alt.id != server.id && chainedOutbounds(alt, primaryTag: proxyTag).isEmpty &&
                (try? outbound(for: alt, tag: alternatePrefix)) != nil
        }
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
        ["tag": "dns-out", "protocol": "dns",
         "settings": ["nonIPQuery": "skip", "address": passThroughDns, "port": 53] as JSONObject]
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
            // On Android these run in helper programs next to the core. An iOS VPN extension
            // has about 50 MB and room for one Go runtime (Xray's), so they cannot come along.
            throw Unsupported(message: L10n.tr("server.error.helperOnly", server.proto.label))
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

    /// Addresses that never leave the device, for when no geoip.dat has the private list.
    static let privateRanges = [
        "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8", "169.254.0.0/16", "172.16.0.0/12",
        "192.168.0.0/16", "224.0.0.0/4", "255.255.255.255/32",
        "::1/128", "fc00::/7", "fe80::/10", "ff00::/8"
    ]

    /// Android's built-in filters, ahead of every profile rule so no profile can undo
    /// them. Ads: AdGuard DNS alone misses apps that find addresses elsewhere, so the same
    /// list blocks connections by the sniffed name. Torrents: BitTorrent is told by
    /// sniffing, trackers by name; a strong hint, not a guarantee.
    static func filterRules(_ adBlock: AdBlockMode, _ blockTorrents: Bool) -> [RoutingRule] {
        var out: [RoutingRule] = []
        func filter(_ name: String, domains: [String] = [], protocol proto: [String] = []) -> RoutingRule {
            RoutingRule(name: name, outbound: .block, domains: domains, protocol: proto, source: "builtin", sortOrder: Int.min)
        }
        if adBlock != .off {
            out.append(filter("Реклама и трекеры", domains: ["geosite:category-ads-all"] + AdBlockLists.domains(for: adBlock)))
        }
        if adBlock == .family {
            out.append(filter("Сайты для взрослых", domains: ["geosite:category-porn"]))
        }
        if blockTorrents {
            out.append(filter("Торренты", protocol: ["bittorrent"]))
            out.append(filter("Торрент-трекеры", domains: ["geosite:category-public-tracker"]))
        }
        return out
    }

    static func serverRouting(_ server: Server) -> JSONObject? {
        guard server.rawJson.contains("\"routing\"") else { return nil }
        return JSON.object(server.rawJson)?.obj("routing")
    }

    /// The rules a server runs with, before the filters: the server's own routing when the
    /// subscription ships it, else the profile merged with the provider's rules.
    static func effectiveRules(server: Server, options o: Options) -> (rules: [RoutingRule], strategy: String?) {
        let shipped = serverRouting(server)
        let serverRules = o.preferServerRouting ? shipped.map { RoutingEngine.parseRules(JSON.string($0)) } ?? [] : []
        var rules: [RoutingRule]
        var strategy: String?
        if !serverRules.isEmpty {
            rules = serverRules.map { var r = $0; r.source = "provider"; return r }
            strategy = shipped?.str("domainStrategy")
        } else {
            rules = RoutingEngine.mergeRules(
                profileRules: o.profile?.rules ?? [],
                useProviderRules: o.profile?.useProviderRules ?? true,
                providerRoutingJson: o.providerRoutingJson,
                serverJsonRouting: shipped.map { JSON.string($0) } ?? ""
            )
            strategy = o.profile?.domainStrategy
        }
        if let geo = o.geo { rules = rules.map { geo.rewrite($0) } }
        return (rules, strategy)
    }

    private static func routing(server: Server, options o: Options) -> JSONObject {
        let (rules, strategy) = effectiveRules(server: server, options: o)
        // After the rewrite: the filters always use the app's own lists.
        return RoutingEngine.toXrayRouting(
            filterRules(o.adBlock, o.blockTorrents) + rules,
            domainStrategy: (strategy ?? "").ifBlank("IPIfNonMatch"),
            dnsRuleEnabled: o.dnsInCore,
            dnsAddress: tunnelDns
        )
    }

    private static func dns(_ o: Options) -> JSONObject {
        var dns: JSONObject = [:]
        var hosts: JSONObject = [:]
        // Happ DnsHosts: fixed answers the provider wants for its own domains.
        if let profileHosts = o.profile.flatMap({ JSON.object($0.dnsHostsJson) }) { hosts = profileHosts }
        // Volta's own tracker list, answered by the core itself (the provider's entries win).
        for name in AdBlockLists.domains(for: o.adBlock) where hosts[name] == nil {
            hosts[name] = ["0.0.0.0", "::"]
        }
        if !hosts.isEmpty { dns["hosts"] = hosts }
        // IPv4 answers only: most servers have no IPv6 exit, and apps then wait for it.
        dns["queryStrategy"] = "UseIPv4"
        dns["disableCache"] = false
        if o.adBlock != .off {
            // AdGuard answers everything, Russian domains included; the system resolver stays last.
            dns["servers"] = [o.adBlock.dohUrl, "localhost"]
            return dns
        }
        let remote = (o.profile?.remoteDns ?? "").ifBlank("https://1.1.1.1/dns-query")
        let domestic = (o.profile?.domesticDns ?? "").ifBlank("https://dns.google/dns-query")
        dns["servers"] = [
            remote,
            ["address": domestic,
             "domains": ["geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai"],
             "expectIPs": ["geoip:ru"]] as JSONObject,
            "localhost"
        ] as [Any]
        return dns
    }

    // MARK: - Helpers

    static func splitList(_ value: String) -> [String] {
        value.components(separatedBy: CharacterSet(charactersIn: ",; ")).map { $0.trimmed }.filter { !$0.isEmpty }
    }
}
