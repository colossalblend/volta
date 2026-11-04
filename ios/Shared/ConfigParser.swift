import Foundation

/// Turns what a provider hands out into servers: share links (vless, vmess, trojan, ss,
/// socks, hysteria2, wireguard), plain or base64 subscription bodies with their `#`
/// metadata, provider JSON split into groups, single Xray outbounds and whole Xray
/// configs, and WireGuard `.conf` files. A port of the Android ConfigParser.
///
/// Parameters without a field of their own go to `Server.extras`, so new Xray knobs keep
/// working without a change here.
enum ConfigParser {

    /// Schemes a link may start with; everything else in a body is ignored.
    private static let supportedSchemes =
        "vless|vmess|trojan|ss|socks5|socks|hysteria2|hy2|hysteria|wireguard|wg|openflux|volta|vpn"

    private static let schemeStart = Rx("(?i)(?:\(supportedSchemes))://")

    /// A link ends at any of these: line breaks and HTML delimiters.
    private static let linkTerminators: Set<unichar> = [10, 13, 60, 62, 34, 39, 92]

    /// Recognised, but this build cannot dial them: reported, never silently dropped.
    private static let unsupportedSchemes: [(String, String)] = [
        ("tuic", "TUIC"), ("ssr", "ShadowsocksR"), ("snell", "Snell"),
        ("juicity", "Juicity"), ("anytls", "AnyTLS"), ("hysteria", "Hysteria"),
        ("openflux", "OpenFlux"), ("vpn", "AmneziaVPN")
    ]

    // MARK: - Subscription bodies

    static func parseSubscriptionBody(_ body: String, subscriptionId: Int64? = nil) -> ImportResult {
        var title = ""
        var expireAt: Double = 0
        var trafficUsed: Int64 = 0
        var trafficTotal: Int64 = 0
        var announce = ""
        var supportUrl = ""
        var webPageUrl = ""
        var updateInterval = 12

        let normalized = body.removingPrefix("\u{FEFF}").trimmed
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")

        // A WireGuard .conf: its # lines are comments, not metadata.
        if WireGuardConf.looksLikeConf(normalized) {
            if let server = WireGuardConf.parse(normalized, subscriptionId: subscriptionId) {
                return ImportResult(servers: [server])
            }
            return ImportResult(servers: [], skipped: ["AmneziaWG"])
        }

        var metaLines: [String] = []
        var payloadLines: [String] = []
        for line in normalized.components(separatedBy: "\n") {
            let t = line.trimmed.removingPrefix("\u{FEFF}")
            if t.isEmpty { continue }
            if t.hasPrefix("#") || t.hasPrefix("//") { metaLines.append(t) } else { payloadLines.append(t) }
        }

        func applyMeta(_ t: String) {
            let value = t.substringAfter(":").trimmed
            if t.hasPrefixIgnoringCase("#profile-title:") || t.hasPrefixIgnoringCase("//profile-title:") {
                title = decodeProfileTitle(value.trimmingChars("\""))
            } else if t.hasPrefixIgnoringCase("#profile-update-interval:") {
                updateInterval = Int(value) ?? 12
            } else if t.hasPrefixIgnoringCase("#support-url:") {
                supportUrl = value
            } else if t.hasPrefixIgnoringCase("#announce:") {
                announce = decodeProfileTitle(value)
            } else if t.hasPrefixIgnoringCase("#profile-web-page-url:") {
                webPageUrl = value
            } else if t.hasPrefixIgnoringCase("#subscription-userinfo:") {
                if let info = parseUserInfo(value) {
                    trafficUsed = info.used
                    trafficTotal = info.total
                    expireAt = info.expire
                }
            }
        }
        metaLines.forEach(applyMeta)

        var content = payloadLines.joined(separator: "\n").trimmed.removingPrefix("\u{FEFF}")

        // Many panels send the whole list as one base64 block.
        if looksLikeBase64Payload(content) {
            if let decoded = Codec.decodeBase64(content) {
                content = decoded.trimmed
                for line in content.components(separatedBy: "\n") {
                    let t = line.trimmed
                    if t.hasPrefix("#") || t.hasPrefix("//") { applyMeta(t) }
                }
            }
        } else if !content.contains("://") && !content.hasPrefix("{") && !content.isBlank {
            if let decoded = Codec.decodeBase64(content),
               decoded.contains("://") || decoded.trimmed.hasPrefix("{") {
                content = decoded.trimmed
            }
        }

        func withMeta(_ r: ImportResult, defaultTitle: String = "") -> ImportResult {
            var out = r
            out.title = title.ifBlank(r.title.ifBlank(defaultTitle))
            out.expireAt = expireAt
            out.trafficUsed = trafficUsed
            out.trafficTotal = trafficTotal
            out.announce = announce.ifBlank(r.announce)
            out.supportUrl = supportUrl.ifBlank(r.supportUrl)
            out.webPageUrl = webPageUrl
            out.updateIntervalHours = updateInterval
            return out
        }

        let start = content.trimmed
        // A provider's JSON split into groups: {"wifi": [...], "lte": [...]}.
        if start.hasPrefix("{"), let grouped = parseGroupedJson(content, subscriptionId: subscriptionId) {
            return withMeta(grouped)
        }
        // A whole Xray config, or an array of them (v2rayNG style).
        if start.hasPrefix("{") {
            return withMeta(parseXrayJson(content, subscriptionId: subscriptionId), defaultTitle: "JSON config")
        }
        if start.hasPrefix("["), let parsed = parseXrayJsonArray(content, subscriptionId: subscriptionId) {
            return withMeta(parsed)
        }

        let links = extractShareLinks(content)
        var servers: [Server] = []
        var skipped: [String] = []
        for link in links {
            if let s = parseShareLink(link, subscriptionId: subscriptionId) {
                servers.append(s)
            } else if let name = unsupportedName(of: link), !skipped.contains(name) {
                skipped.append(name)
            }
        }
        for name in unsupportedProtocols(content) where !skipped.contains(name) {
            skipped.append(name)
        }
        var result = ImportResult(servers: servers)
        result.skipped = skipped
        return withMeta(result)
    }

    /// Links in any text: one per line, several on one line, or inside HTML.
    ///
    /// A link runs until the next scheme, a line break or an HTML delimiter; stopping at
    /// the first space would cut the common unencoded "#Название сервера".
    static func extractShareLinks(_ content: String) -> [String] {
        let ns = content as NSString
        let starts = schemeStart.allMatches(content).map { $0.range.location }
        if starts.isEmpty { return [] }
        var found: [String] = []
        var seen = Set<String>()
        for (index, start) in starts.enumerated() {
            let hardEnd = index + 1 < starts.count ? starts[index + 1] : ns.length
            var end = start
            while end < hardEnd && !linkTerminators.contains(ns.character(at: end)) { end += 1 }
            var raw = ns.substring(with: NSRange(location: start, length: end - start))
            // Spaces are only legal in the name, after '#'.
            if !raw.contains("#") { raw = raw.substringBefore(" ") }
            let link = raw.trimmed.trimmingTrailing(",;)]}.")
            if link.count > 8 && !seen.contains(link) {
                seen.insert(link)
                found.append(link)
            }
        }
        return found
    }

    /// Names of protocols in the text that this build cannot dial.
    static func unsupportedProtocols(_ content: String) -> [String] {
        var out: [String] = []
        for (scheme, name) in unsupportedSchemes where scheme != "hysteria" && scheme != "openflux" && scheme != "vpn" {
            if Rx("(?i)(?<![a-z0-9])\(scheme)://").matches(content), !out.contains(name) { out.append(name) }
        }
        return out
    }

    private static func unsupportedName(of link: String) -> String? {
        let scheme = link.substringBefore("://").lowercased()
        if scheme == "volta" { return "OpenFlux" }
        return unsupportedSchemes.first { $0.0 == scheme }?.1
    }

    /// A body that is one base64 block, judged by how its start decodes.
    private static func looksLikeBase64Payload(_ content: String) -> Bool {
        if content.isBlank || content.contains("://") || content.trimmed.hasPrefix("{") { return false }
        let cleaned = content.components(separatedBy: .whitespacesAndNewlines).joined()
        if cleaned.count < 16 { return false }
        if !Rx("^[A-Za-z0-9+/=_-]+$").matches(cleaned) { return false }
        let head = cleaned.take(12)
        let starts = ["dmxlc3", "dm1lc3", "c3M6Ly", "dHJvam", "aHlzdG", "c29ja3", "d2lyZW", "b3BlbmZsdX", "dnBuOi8v"]
        return starts.contains { head.hasPrefix($0) } || cleaned.count > 64
    }

    // MARK: - Share links

    static func parseShareLink(_ link: String, subscriptionId: Int64? = nil) -> Server? {
        let trimmed = link.trimmed
        let scheme = trimmed.substringBefore("://").lowercased()
        guard trimmed.contains("://") else {
            return trimmed.hasPrefix("{") ? parseSingleOutboundJson(trimmed, subscriptionId: subscriptionId) : nil
        }
        switch scheme {
        case "vless": return parseVless(trimmed, subscriptionId: subscriptionId)
        case "vmess": return parseVmess(trimmed, subscriptionId: subscriptionId)
        case "trojan": return parseTrojan(trimmed, subscriptionId: subscriptionId)
        case "ss": return parseShadowsocks(trimmed, subscriptionId: subscriptionId)
        case "socks", "socks5": return parseSocks(trimmed, subscriptionId: subscriptionId)
        case "hysteria2", "hy2": return parseHysteria2(trimmed, subscriptionId: subscriptionId)
        case "wireguard", "wg": return parseWireGuard(trimmed, subscriptionId: subscriptionId)
        default: return nil
        }
    }

    static func parseVless(_ link: String, subscriptionId: Int64? = nil) -> Server? {
        guard let parts = splitShareLink(link, scheme: "vless") else { return nil }
        let p = parseQuery(parts.query)
        let (address, port) = splitHostPort(parts.hostPort, defaultPort: 443)
        if address.isBlank || parts.userInfo.isBlank { return nil }
        return Server(
            subscriptionId: subscriptionId,
            remark: parts.remark.ifBlank("VLESS"),
            proto: .vless,
            address: address,
            port: port,
            uuid: parts.userInfo,
            flow: p["flow"] ?? "",
            encryption: (p["encryption"] ?? "").ifBlank("none"),
            network: normalizeNetwork(p["type"] ?? p["network"]),
            security: normalizeSecurity(p["security"]),
            sni: p["sni"] ?? "",
            fp: p["fp"] ?? "",
            pbk: p["pbk"] ?? "",
            sid: p["sid"] ?? "",
            spx: p["spx"] ?? "",
            alpn: p["alpn"] ?? "",
            path: p["path"] ?? "",
            host: p["host"] ?? "",
            serviceName: p["serviceName"] ?? "",
            headerType: p["headerType"] ?? "",
            rawLink: link,
            extras: clean(p)
        )
    }

    private static func parseVmess(_ link: String, subscriptionId: Int64?) -> Server? {
        // Newer panels write vmess the VLESS way: vmess://uuid@host:port?...#name
        if link.contains("@"), let s = parseVmessUriStyle(link, subscriptionId: subscriptionId) { return s }
        guard let json = Codec.decodeBase64(link.substringAfter("://")),
              let obj = JSON.object(json) else { return nil }
        let address = obj.str("add").ifBlank(obj.str("address"))
        if address.isBlank { return nil }
        var extras: [String: String] = ["alterId": String(obj.int("aid", 0))]
        for (key, name) in [("type", "headerType"), ("mode", "mode"), ("allowInsecure", "allowInsecure")] {
            let v = obj.str(key)
            if !v.isBlank { extras[name] = v }
        }
        let net = obj.str("net")
        return Server(
            subscriptionId: subscriptionId,
            remark: obj.str("ps").ifBlank(obj.str("remarks").ifBlank("VMess")),
            proto: .vmess,
            address: address,
            port: obj.int("port", 443),
            uuid: obj.str("id"),
            encryption: obj.str("scy").ifBlank("auto"),
            network: normalizeNetwork(net),
            security: normalizeSecurity(obj.str("tls")),
            sni: obj.str("sni").ifBlank(obj.str("host")),
            fp: obj.str("fp"),
            alpn: obj.str("alpn"),
            path: obj.str("path"),
            host: obj.str("host"),
            serviceName: net == "grpc" ? obj.str("path") : "",
            headerType: obj.str("type"),
            rawLink: link,
            extras: extras
        )
    }

    private static func parseVmessUriStyle(_ link: String, subscriptionId: Int64?) -> Server? {
        guard let parts = splitShareLink(link, scheme: "vmess") else { return nil }
        let p = parseQuery(parts.query)
        let (address, port) = splitHostPort(parts.hostPort, defaultPort: 443)
        if address.isBlank || parts.userInfo.isBlank { return nil }
        return Server(
            subscriptionId: subscriptionId,
            remark: parts.remark.ifBlank("VMess"),
            proto: .vmess,
            address: address,
            port: port,
            uuid: parts.userInfo,
            encryption: (p["encryption"] ?? "").ifBlank("auto"),
            network: normalizeNetwork(p["type"] ?? p["network"]),
            security: normalizeSecurity(p["security"]),
            sni: p["sni"] ?? "",
            fp: p["fp"] ?? "",
            alpn: p["alpn"] ?? "",
            path: p["path"] ?? "",
            host: p["host"] ?? "",
            serviceName: p["serviceName"] ?? "",
            headerType: p["headerType"] ?? "",
            rawLink: link,
            extras: clean(p)
        )
    }

    private static func parseTrojan(_ link: String, subscriptionId: Int64?) -> Server? {
        guard let parts = splitShareLink(link, scheme: "trojan") else { return nil }
        let p = parseQuery(parts.query)
        let (address, port) = splitHostPort(parts.hostPort, defaultPort: 443)
        if address.isBlank || parts.userInfo.isBlank { return nil }
        // Trojan is TLS unless the link says reality.
        var security = normalizeSecurity(p["security"])
        if security == "none" { security = "tls" }
        return Server(
            subscriptionId: subscriptionId,
            remark: parts.remark.ifBlank("Trojan"),
            proto: .trojan,
            address: address,
            port: port,
            uuid: parts.userInfo,
            flow: p["flow"] ?? "",
            network: normalizeNetwork(p["type"] ?? p["network"]),
            security: security,
            sni: (p["sni"] ?? "").ifBlank(p["peer"] ?? ""),
            fp: p["fp"] ?? "",
            pbk: p["pbk"] ?? "",
            sid: p["sid"] ?? "",
            alpn: p["alpn"] ?? "",
            path: p["path"] ?? "",
            host: p["host"] ?? "",
            serviceName: p["serviceName"] ?? "",
            headerType: p["headerType"] ?? "",
            rawLink: link,
            extras: clean(p)
        )
    }

    private static func parseShadowsocks(_ link: String, subscriptionId: Int64?) -> Server? {
        let without = link.substringAfter("://")
        let remark: String
        let main: String
        if let hash = without.range(of: "#", options: .backwards) {
            remark = percentDecode(String(without[hash.upperBound...]))
            main = String(without[..<hash.lowerBound])
        } else {
            remark = "Shadowsocks"
            main = without
        }
        let query = main.substringAfter("?", missing: "")
        let beforeQuery = main.substringBefore("?")
        var userInfo: String
        var hostPort: String
        if let at = beforeQuery.range(of: "@", options: .backwards) {
            userInfo = String(beforeQuery[..<at.lowerBound])
            hostPort = String(beforeQuery[at.upperBound...])
        } else {
            // SIP002 also allows userinfo@host:port to be base64 as a whole.
            guard let decoded = Codec.decodeBase64(beforeQuery),
                  let at = decoded.range(of: "@", options: .backwards) else { return nil }
            userInfo = String(decoded[..<at.lowerBound])
            hostPort = String(decoded[at.upperBound...])
        }
        // SIP002 percent-encodes the userinfo; 2022 keys are base64 themselves, so only a
        // decode that yields "method:password" counts.
        let rawUser = percentDecode(userInfo)
        var decodedUser = rawUser
        if let d = Codec.decodeBase64(rawUser), d.contains(":") { decodedUser = d }
        let method = decodedUser.substringBefore(":")
        let password = decodedUser.substringAfter(":")
        let (address, port) = splitHostPort(hostPort, defaultPort: 443)
        if address.isBlank || method.isBlank || !decodedUser.contains(":") { return nil }
        let p = parseQuery(query)
        return Server(
            subscriptionId: subscriptionId,
            remark: remark.ifBlank("Shadowsocks"),
            proto: .shadowsocks,
            address: address,
            port: port,
            uuid: password,
            encryption: method,
            network: normalizeNetwork(p["type"] ?? p["network"]),
            security: normalizeSecurity(p["security"]),
            sni: p["sni"] ?? "",
            path: p["path"] ?? "",
            host: p["host"] ?? "",
            serviceName: p["serviceName"] ?? "",
            rawLink: link,
            extras: clean(p)
        )
    }

    private static func parseSocks(_ link: String, subscriptionId: Int64?) -> Server? {
        guard let parts = splitShareLink(link, scheme: link.substringBefore("://")) else { return nil }
        var user = ""
        var pass = ""
        var hostPort = parts.hostPort
        if !parts.userInfo.isBlank {
            var decoded = parts.userInfo
            if let d = Codec.decodeBase64(parts.userInfo), d.contains(":") { decoded = d }
            user = decoded.substringBefore(":")
            pass = decoded.substringAfter(":", missing: "")
        } else if !hostPort.isBlank && !hostPort.contains(":") {
            guard let decoded = Codec.decodeBase64(hostPort) else { return nil }
            if let at = decoded.range(of: "@", options: .backwards) {
                let cred = String(decoded[..<at.lowerBound])
                user = cred.substringBefore(":")
                pass = cred.substringAfter(":", missing: "")
                hostPort = String(decoded[at.upperBound...])
            } else {
                hostPort = decoded
            }
        }
        let (address, port) = splitHostPort(hostPort, defaultPort: 1080)
        if address.isBlank { return nil }
        return Server(
            subscriptionId: subscriptionId,
            remark: parts.remark.ifBlank("SOCKS5"),
            proto: .socks,
            address: address,
            port: port,
            uuid: pass,
            host: user,
            rawLink: link,
            extras: user.isBlank ? [:] : ["user": user]
        )
    }

    private static func parseHysteria2(_ link: String, subscriptionId: Int64?) -> Server? {
        guard let parts = splitShareLink(link, scheme: link.substringBefore("://")) else { return nil }
        let p = parseQuery(parts.query)
        let (hostPort, hopPorts) = cutPortRange(parts.hostPort)
        let (address, port) = splitHostPort(hostPort, defaultPort: 443)
        if address.isBlank { return nil }
        let auth = parts.userInfo.ifBlank(p["auth"] ?? "")
        var extras = clean(p)
        if !auth.isBlank { extras["auth"] = auth }
        if !hopPorts.isBlank && (extras["mport"] ?? "").isBlank { extras["mport"] = hopPorts }
        return Server(
            subscriptionId: subscriptionId,
            remark: parts.remark.ifBlank("Hysteria2"),
            proto: .hysteria2,
            address: address,
            port: port,
            uuid: auth,
            network: "hysteria",
            security: "tls",
            sni: (p["sni"] ?? "").ifBlank(p["peer"] ?? ""),
            alpn: (p["alpn"] ?? "").ifBlank("h3"),
            rawLink: link,
            extras: extras
        )
    }

    /// Port hopping in the address ("host:20000-30000", "host:443,8443"): the address with
    /// its first port, and the whole port list.
    static func cutPortRange(_ hostPort: String) -> (String, String) {
        let hp = hostPort.trimmed.trimmingChars("/")
        guard let colon = hp.range(of: ":", options: .backwards), colon.lowerBound != hp.startIndex,
              !hp.hasSuffix("]") else { return (hp, "") }
        let spec = String(hp[colon.upperBound...])
        if !spec.contains("-") && !spec.contains(",") { return (hp, "") }
        let first = spec.substringBefore(",").substringBefore("-")
        guard Int(first) != nil else { return (hp, "") }
        return ("\(hp[..<colon.lowerBound]):\(first)", spec)
    }

    private static func parseWireGuard(_ link: String, subscriptionId: Int64?) -> Server? {
        guard let parts = splitShareLink(link, scheme: link.substringBefore("://")) else { return nil }
        let p = parseQuery(parts.query)
        let (address, port) = splitHostPort(parts.hostPort, defaultPort: 51820)
        let privateKey = parts.userInfo.ifBlank(p["privatekey"] ?? p["privateKey"] ?? "")
        if address.isBlank || privateKey.isBlank { return nil }
        // AmneziaWG fields: Xray's WireGuard cannot speak them.
        if WireGuardConf.amneziaKeys.contains(where: { k in p.keys.contains { $0.lowercased() == k } }) { return nil }
        var extras = clean(p)
        extras["privateKey"] = privateKey
        return Server(
            subscriptionId: subscriptionId,
            remark: parts.remark.ifBlank("WireGuard"),
            proto: .wireguard,
            address: address,
            port: port,
            uuid: privateKey,
            network: "udp",
            pbk: p["publickey"] ?? p["publicKey"] ?? "",
            rawLink: link,
            extras: extras
        )
    }

    // MARK: - JSON

    static func parseXrayJson(_ json: String, subscriptionId: Int64?) -> ImportResult {
        guard let root = JSON.object(json) else { return ImportResult(servers: []) }
        var servers: [Server] = []
        for item in root.arr("outbounds") ?? [] {
            // One malformed outbound must not cost every other server.
            if let s = parseOutbound(item, subscriptionId: subscriptionId) { servers.append(s) }
        }
        if servers.isEmpty, root["protocol"] != nil, root["outbounds"] == nil,
           let s = parseOutbound(root, subscriptionId: subscriptionId) {
            servers.append(s)
        }
        // A v2rayNG-style config names the server in "remarks"; the outbound tag is just
        // "proxy". Without this every server would be called "proxy".
        let remarks = root.str("remarks").trimmed
        let named: [Server]
        if remarks.isBlank {
            named = servers
        } else {
            named = servers.map { s in
                var copy = s
                let generic = s.remark.isBlank || s.remark.lowercased().hasPrefix("proxy") || s.remark.contains("://")
                if generic { copy.remark = servers.count == 1 ? remarks : "\(remarks) · \(s.remark)" }
                return copy
            }
        }
        return ImportResult(servers: named, title: remarks.ifBlank("Xray JSON"))
    }

    /// A provider's subscription split into groups:
    /// `{"title": "…", "wifi": [links or configs], "lte": [...], "balancers": [...]}`;
    /// the lists may also sit under "groups" or "servers". nil when it is not that shape.
    static func parseGroupedJson(_ json: String, subscriptionId: Int64?) -> ImportResult? {
        guard let root = JSON.object(json), root["outbounds"] == nil else { return nil }
        let holder = ["groups", "servers"].lazy.compactMap { root.obj($0) }.first ?? root
        var groups: [(ServerRole, Any)] = []
        // In the order the provider wrote them (JSONSerialization keeps no order).
        func position(_ key: String) -> Int {
            guard let r = json.range(of: "\"\(key)\"") else { return Int.max }
            return json.distance(from: json.startIndex, to: r.lowerBound)
        }
        for key in holder.keys.sorted(by: { position($0) < position($1) }) {
            guard let role = ServerMarkup.groupForKey(key), let value = holder[key] else { continue }
            if value is [Any] || value is String { groups.append((role, value)) }
        }
        if groups.isEmpty { return nil }
        var servers: [Server] = []
        for (role, value) in groups {
            let items: [Any] = (value as? [Any]) ?? [value]
            for item in items {
                for var s in groupItem(item, subscriptionId: subscriptionId) {
                    s.role = role
                    s.roleFromProvider = true
                    servers.append(s)
                }
            }
        }
        // A stray "mobile": "yes" in some other JSON is not a group list.
        if servers.isEmpty && !groups.contains(where: { $0.1 is [Any] }) { return nil }
        var result = ImportResult(servers: servers)
        result.title = root.str("title").ifBlank(root.str("profile-title").ifBlank(root.str("name")))
        result.announce = root.str("announce")
        result.supportUrl = root.str("support-url").ifBlank(root.str("supportUrl"))
        if servers.isEmpty { result.skipped = unsupportedProtocols(json) }
        return result
    }

    private static func groupItem(_ item: Any, subscriptionId: Int64?) -> [Server] {
        if let text = item as? String { return linksIn(text, subscriptionId: subscriptionId) }
        guard let obj = item as? JSONObject else { return [] }
        let name = obj.str("name").ifBlank(obj.str("remark")).trimmed
        let link = ["link", "url", "config", "uri"].lazy.map { obj.str($0) }.first { !$0.isBlank }
        var parsed: [Server]
        if let link = link {
            parsed = linksIn(link, subscriptionId: subscriptionId)
        } else if obj["outbounds"] != nil {
            parsed = parseXrayJson(JSON.string(obj), subscriptionId: subscriptionId).servers
        } else if obj["protocol"] != nil {
            parsed = parseOutbound(obj, subscriptionId: subscriptionId).map { [$0] } ?? []
        } else {
            parsed = []
        }
        if !name.isBlank && parsed.count == 1 { parsed[0].remark = name }
        return parsed
    }

    private static func linksIn(_ text: String, subscriptionId: Int64?) -> [Server] {
        extractShareLinks(text).compactMap { parseShareLink($0, subscriptionId: subscriptionId) }
    }

    private static let nonProxyProtocols: Set<String> = ["freedom", "blackhole", "dns", "loopback"]
    private static let nonProxyTags: Set<String> = ["direct", "block", "dns-out", "api"]

    static func parseOutbound(_ element: Any, subscriptionId: Int64?) -> Server? {
        guard let ob = element as? JSONObject else { return nil }
        let tag = ob.str("tag")
        let proto = ob.str("protocol").lowercased()
        if nonProxyProtocols.contains(proto) || nonProxyTags.contains(tag) { return nil }

        let settings = ob.obj("settings")
        let vnext = (settings?.arr("vnext")?.first as? JSONObject)
        let firstServer = (settings?.arr("servers")?.first as? JSONObject)

        var address = ""
        var port = 443
        var uuid = ""
        var flow = ""
        var encryption = "none"

        switch proto {
        case "vless", "vmess":
            // The newer flat form keeps address/port/id right in settings.
            let holder = vnext ?? settings
            address = holder?.str("address") ?? ""
            port = holder?.int("port", 443) ?? 443
            let user = (vnext?.arr("users")?.first as? JSONObject) ?? settings
            uuid = user?.str("id") ?? ""
            flow = user?.str("flow") ?? ""
            let enc = (user?.str("encryption") ?? "").ifBlank(user?.str("security") ?? "")
            encryption = enc.ifBlank(proto == "vmess" ? "auto" : "none")
        case "trojan", "shadowsocks", "socks", "http":
            let holder = firstServer ?? settings
            address = holder?.str("address") ?? ""
            port = holder?.int("port", 443) ?? 443
            uuid = holder?.str("password") ?? ""
            if uuid.isBlank, let u = holder?.arr("users")?.first as? JSONObject { uuid = u.str("pass") }
            encryption = (holder?.str("method") ?? "").ifBlank("none")
        case "hysteria":
            address = settings?.str("address") ?? ""
            port = settings?.int("port", 443) ?? 443
        case "wireguard":
            uuid = settings?.str("secretKey") ?? ""
            let peer = settings?.arr("peers")?.first as? JSONObject
            let split = splitHostPort(peer?.str("endpoint") ?? "", defaultPort: 51820)
            address = split.0
            port = split.1
        default:
            address = vnext?.str("address") ?? firstServer?.str("address") ?? ""
            port = vnext?.int("port", 443) ?? firstServer?.int("port", 443) ?? 443
        }

        let stream = ob.obj("streamSettings")
        let network = normalizeNetwork(stream?.str("network"))
        let security = normalizeSecurity(stream?.str("security"))
        let tls = stream?.obj("tlsSettings")
        let reality = stream?.obj("realitySettings")
        let ws = stream?.obj("wsSettings")
        let grpc = stream?.obj("grpcSettings")
        let hysteria = stream?.obj("hysteriaSettings")

        if address.isBlank && proto != "wireguard" { return nil }

        let type: ProtocolType
        switch proto {
        case "vless": type = .vless
        case "vmess": type = .vmess
        case "trojan": type = .trojan
        case "shadowsocks": type = .shadowsocks
        case "socks": type = .socks
        case "http": type = .http
        case "wireguard": type = .wireguard
        case "hysteria": type = .hysteria2
        default: type = .rawJson
        }
        if type == .hysteria2 && uuid.isBlank { uuid = hysteria?.str("auth") ?? "" }

        let sni = (reality?.str("serverName") ?? "").ifBlank(tls?.str("serverName") ?? "")
        let fp = (reality?.str("fingerprint") ?? "").ifBlank(tls?.str("fingerprint") ?? "")
        let shortIds = reality?.arr("shortIds")?.compactMap { $0 as? String } ?? []
        let alpnList = (tls?.arr("alpn") ?? reality?.arr("alpn"))?.compactMap { $0 as? String } ?? []
        let headers = ws?.obj("headers")

        return Server(
            subscriptionId: subscriptionId,
            remark: tag.ifBlank("\(proto)://\(address):\(port)"),
            proto: type,
            address: address,
            port: port,
            uuid: uuid,
            flow: flow,
            encryption: encryption,
            network: network,
            security: security,
            sni: sni,
            fp: fp,
            pbk: reality?.str("publicKey") ?? "",
            sid: (shortIds.first ?? "").ifBlank(reality?.str("shortId") ?? ""),
            spx: reality?.str("spiderX") ?? "",
            alpn: alpnList.joined(separator: ","),
            path: ws?.str("path") ?? "",
            host: headers?.str("Host") ?? "",
            serviceName: grpc?.str("serviceName") ?? "",
            // The whole outbound is kept: exotic transports survive untouched.
            rawJson: JSON.string(ob)
        )
    }

    private static func parseXrayJsonArray(_ json: String, subscriptionId: Int64?) -> ImportResult? {
        guard let arr = JSON.parse(json) as? [Any] else { return nil }
        var servers: [Server] = []
        for item in arr {
            guard let obj = item as? JSONObject else { continue }
            servers += parseXrayJson(JSON.string(obj), subscriptionId: subscriptionId).servers
        }
        return servers.isEmpty ? nil : ImportResult(servers: servers, title: "JSON config")
    }

    private static func parseSingleOutboundJson(_ json: String, subscriptionId: Int64?) -> Server? {
        guard let obj = JSON.object(json) else { return nil }
        let outbound: Any = obj.arr("outbounds")?.first ?? obj
        return parseOutbound(outbound, subscriptionId: subscriptionId)
    }

    // MARK: - Pieces

    struct LinkParts {
        var userInfo: String
        var hostPort: String
        var query: String
        var remark: String
    }

    /// `scheme://userinfo@host:port?query#remark`, split by hand: URL parsers choke on
    /// raw UUIDs and the unencoded names providers ship.
    static func splitShareLink(_ link: String, scheme: String) -> LinkParts? {
        var without = link.substringAfter("\(scheme)://", missing: "")
        if without.isBlank { without = link.substringAfter("://", missing: "") }
        if without.isBlank { return nil }
        var remark = ""
        var main = without
        if let hash = without.range(of: "#") {
            remark = percentDecode(String(without[hash.upperBound...]))
            main = String(without[..<hash.lowerBound])
        }
        var beforeQuery = main
        var query = ""
        if let q = main.range(of: "?") {
            beforeQuery = String(main[..<q.lowerBound])
            query = String(main[q.upperBound...])
        }
        var userInfo = ""
        var hostPort = beforeQuery
        if let at = beforeQuery.range(of: "@", options: .backwards) {
            userInfo = percentDecode(String(beforeQuery[..<at.lowerBound]))
            hostPort = String(beforeQuery[at.upperBound...])
        }
        return LinkParts(userInfo: userInfo, hostPort: hostPort.trimmingChars("/"), query: query, remark: remark)
    }

    static func splitHostPort(_ hostPort: String, defaultPort: Int) -> (String, Int) {
        let hp = hostPort.trimmed.trimmingChars("/")
        if hp.hasPrefix("["), let end = hp.firstIndex(of: "]") {
            let host = String(hp[hp.index(after: hp.startIndex)..<end])
            let portPart = String(hp[hp.index(after: end)...]).removingPrefix(":")
            return (host, Int(portPart) ?? defaultPort)
        }
        // A bare IPv6 literal has several colons and no port.
        if hp.filter({ $0 == ":" }).count == 1, let colon = hp.firstIndex(of: ":"), colon != hp.startIndex {
            let portText = String(hp[hp.index(after: colon)...]).substringBefore(",")
            if let port = Int(portText) { return (String(hp[..<colon]), port) }
        }
        return (hp, defaultPort)
    }

    static func parseQuery(_ query: String) -> [String: String] {
        if query.isBlank { return [:] }
        var out: [String: String] = [:]
        for part in query.components(separatedBy: "&") {
            guard let eq = part.firstIndex(of: "="), eq != part.startIndex else { continue }
            let key = percentDecode(String(part[..<eq]))
            let value = percentDecode(String(part[part.index(after: eq)...]))
            out[key] = value
        }
        return out
    }

    /// tcp and raw are the same wire format; one spelling.
    static func normalizeNetwork(_ value: String?) -> String {
        let v = (value ?? "").trimmed.lowercased()
        switch v {
        case "", "raw": return "tcp"
        case "h2": return "http"
        case "splithttp": return "xhttp"
        default: return v
        }
    }

    static func normalizeSecurity(_ value: String?) -> String {
        let v = (value ?? "").trimmed.lowercased()
        switch v {
        case "", "none", "0", "false": return "none"
        case "1", "true": return "tls"
        default: return v
        }
    }

    private static func clean(_ params: [String: String]) -> [String: String] {
        params.filter { !$0.value.isBlank }
    }

    /// `upload=…; download=…; total=…; expire=…` (expire in seconds).
    static func parseUserInfo(_ info: String) -> (used: Int64, total: Int64, expire: Double)? {
        var map: [String: String] = [:]
        for part in info.components(separatedBy: ";") {
            let kv = part.trimmed.components(separatedBy: "=")
            if kv.count == 2 { map[kv[0].trimmed] = kv[1].trimmed }
        }
        if map.isEmpty { return nil }
        let upload = Int64(map["upload"] ?? "") ?? 0
        let download = Int64(map["download"] ?? "") ?? 0
        let total = Int64(map["total"] ?? "") ?? 0
        let expire = Double(map["expire"] ?? "") ?? 0
        return (upload + download, total, expire)
    }

    static func percentDecode(_ raw: String) -> String { Codec.percentDecode(raw) }

    /// Providers send the name as plain text, percent-encoded text or base64. Base64 is
    /// only guessed when the value cannot be an ordinary name: correct padding and a
    /// decode that reads as text ("Premium" must stay "Premium").
    static func decodeProfileTitle(_ raw: String) -> String {
        let value = raw.trimmed.trimmingChars("\"")
        if value.isBlank { return "" }
        if value.hasPrefixIgnoringCase("base64:") {
            return (Codec.decodeBase64(value.substringAfter(":")) ?? "").trimmed.ifBlank(value)
        }
        let decoded = percentDecode(value)
        if decoded != value { return decoded }
        if value.count >= 12 && value.count % 4 == 0 && Rx("^[A-Za-z0-9+/]+={0,2}$").matches(value),
           let candidate = Codec.decodeBase64(value)?.trimmed {
            let readable = !candidate.isBlank &&
                !candidate.unicodeScalars.contains { $0.value < 32 && $0 != "\n" && $0 != "\t" } &&
                !candidate.contains("\u{FFFD}") &&
                (candidate.contains { $0.isWhitespace } || candidate.unicodeScalars.contains { $0.value > 127 })
            if readable { return candidate }
        }
        return value
    }
}

/// WireGuard `.conf` files. Plain WireGuard becomes a server Xray dials; AmneziaWG
/// (junk packets, header obfuscation) needs a helper this build does not have.
enum WireGuardConf {
    static let amneziaKeys: Set<String> = [
        "jc", "jmin", "jmax", "s1", "s2", "s3", "s4", "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5"
    ]

    static func looksLikeConf(_ text: String) -> Bool {
        text.containsIgnoringCase("[Interface]") && text.containsIgnoringCase("[Peer]")
    }

    static func parse(_ text: String, subscriptionId: Int64?) -> Server? {
        var section = ""
        var iface: [String: String] = [:]
        var peer: [String: String] = [:]
        var name = ""
        for rawLine in text.components(separatedBy: "\n") {
            let line = rawLine.trimmed
            if line.hasPrefix("#") {
                let comment = line.removingPrefix("#").trimmed
                if name.isEmpty, comment.hasPrefixIgnoringCase("name") || comment.hasPrefixIgnoringCase("название") {
                    name = comment.substringAfter("=", missing: comment.substringAfter(":", missing: "")).trimmed
                }
                continue
            }
            if line.hasPrefix("[") { section = line.lowercased(); continue }
            guard let eq = line.firstIndex(of: "=") else { continue }
            let key = String(line[..<eq]).trimmed.lowercased()
            let value = String(line[line.index(after: eq)...]).trimmed
            if section == "[interface]" { iface[key] = value } else if section == "[peer]", peer[key] == nil { peer[key] = value }
        }
        if iface.keys.contains(where: { amneziaKeys.contains($0) }) { return nil }
        guard let privateKey = iface["privatekey"], let publicKey = peer["publickey"],
              let endpoint = peer["endpoint"] else { return nil }
        let (address, port) = ConfigParser.splitHostPort(endpoint, defaultPort: 51820)
        if address.isBlank { return nil }
        var extras: [String: String] = ["privateKey": privateKey, "publicKey": publicKey]
        if let a = iface["address"] { extras["address"] = a }
        if let m = iface["mtu"] { extras["mtu"] = m }
        if let psk = peer["presharedkey"] { extras["preSharedKey"] = psk }
        if let allowed = peer["allowedips"] { extras["allowedIPs"] = allowed }
        if let reserved = peer["reserved"] { extras["reserved"] = reserved }
        return Server(
            subscriptionId: subscriptionId,
            remark: name.ifBlank("WireGuard · \(address)"),
            proto: .wireguard,
            address: address,
            port: port,
            uuid: privateKey,
            network: "udp",
            pbk: publicKey,
            extras: extras
        )
    }
}
