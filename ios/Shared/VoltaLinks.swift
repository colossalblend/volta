import Foundation

/// Every `volta://` link, parsed by hand (URL parsers mangle subscription addresses that
/// carry their own query). The same forms as on Android:
///
///     volta://import/https://sub.example/abc               plain, link visible
///     volta://import/https://sub.example/abc?visible_subs=false
///     volta://import/https://sub.example/abc&server-type=lte:1,2;home:3#My VPN
///     volta://import?url=<encoded>&name=My%20VPN&visible_subs=false
///     volta://crypt/<payload>                              encrypted, link hidden
///     volta://connect[?mode=home|lte|auto|manual]   volta://disconnect   volta://toggle
enum VoltaLinks {

    enum Action: Equatable {
        case addSubscription(url: String, name: String, visible: Bool?, serverTypes: String)
        case addEncrypted(payload: String, name: String, serverTypes: String)
        case importContent(String)
        case connect(ConnectMode?)
        case disconnect
        case toggle
        case invalid(String)
    }

    private static let scheme = "volta://"
    private static let importHosts: Set<String> = ["import", "add", "sub", "subscribe", "subscription", "install-config"]
    private static let cryptHosts: Set<String> = ["crypt", "crypt1", "encrypted"]
    private static let connectHosts: Set<String> = ["connect", "on", "start", "enable", "up"]
    private static let disconnectHosts: Set<String> = ["disconnect", "off", "stop", "disable", "down"]
    private static let toggleHosts: Set<String> = ["toggle", "switch"]
    private static let visibleKeys: Set<String> = ["visible_subs", "visible-subs", "visiblesubs", "visible"]
    private static let typeKeys: Set<String> = ["server-type", "server_type", "servertype", "server-types", "server_types"]
    private static var ownPathKeys: Set<String> { visibleKeys.union(typeKeys) }
    private static let urlKeys = ["url", "link", "sub", "config", "data"]

    static func parse(_ raw: String) -> Action {
        let t = raw.trimmed
        guard t.hasPrefixIgnoringCase(scheme) else { return .importContent(t) }
        let rest = String(t.dropFirst(scheme.count))
        let hostEnd = rest.firstIndex(where: { $0 == "/" || $0 == "?" || $0 == "#" }) ?? rest.endIndex
        let host = rest[..<hostEnd].lowercased()
        let after = String(rest[hostEnd...])

        if importHosts.contains(host) {
            if after.hasPrefix("/") && after.count > 1 { return pathImport(String(after.dropFirst())) }
            return queryImport(query(after))
        }
        if cryptHosts.contains(host) { return crypt(after) }
        if connectHosts.contains(host) { return .connect(parseMode(value(query(after), "mode", "group", "type"))) }
        if disconnectHosts.contains(host) { return .disconnect }
        if toggleHosts.contains(host) { return .toggle }
        return queryImport(query(after))
    }

    /// Turns an opened `volta://crypt` payload into a subscription. The link stays hidden
    /// unless the encrypted part itself says `visible_subs=true`.
    static func openEncrypted(payload: String, name: String, serverTypes: String,
                              decrypt: (String) throws -> String = SubCrypto.decrypt) -> Action {
        let plain: String
        do {
            plain = try decrypt(payload).trimmed
        } catch {
            return .invalid((error as? LocalizedError)?.errorDescription ?? L10n.tr("link.crypt.failed"))
        }
        var url = ""
        var innerName = ""
        var visible: Bool? = nil
        var types = ""
        if plain.hasPrefixIgnoringCase(scheme) {
            switch parse(plain) {
            case let .addSubscription(u, n, v, st):
                url = u; innerName = n; visible = v; types = st
            case .addEncrypted:
                return .invalid(L10n.tr("link.crypt.twice"))
            default:
                return .invalid(L10n.tr("link.crypt.noSubscription"))
            }
        } else if plain.hasPrefix("{") {
            guard let o = JSON.object(plain) else { return .invalid(L10n.tr("link.crypt.noSubscription")) }
            func s(_ keys: [String]) -> String { keys.lazy.map { o.str($0) }.first { !$0.isEmpty } ?? "" }
            url = s(["url", "link", "sub"])
            innerName = s(["name", "title"])
            let v = s(Array(visibleKeys))
            visible = v.isBlank ? nil : parseBool(v)
            types = ServerMarkup.normalize(s(Array(typeKeys)))
        } else {
            url = withScheme(plain)
        }
        guard isHttp(url) else { return .invalid(L10n.tr("link.crypt.noAddress")) }
        return .addSubscription(
            url: url,
            name: innerName.ifBlank(name),
            visible: visible == true,
            serverTypes: types.ifBlank(serverTypes)
        )
    }

    // MARK: - Forms

    private static func pathImport(_ tailRaw: String) -> Action {
        var tail = tailRaw
        // The name rides in the fragment: a subscription address never needs one.
        var name = ""
        if let hash = tail.range(of: "#", options: .backwards) {
            name = decode(String(tail[hash.upperBound...])).trimmed
            tail = String(tail[..<hash.lowerBound])
        }
        if tail.hasPrefixIgnoringCase("http%3A") || tail.hasPrefixIgnoringCase("https%3A") { tail = decode(tail) }

        // The provider's query apart from ours. Volta's keys may follow '?' or, when the
        // address has no query of its own, a bare '&' ("…/sub&server-type=lte:1").
        var base = tail
        var queryPart = ""
        if let q = tail.firstIndex(of: "?") {
            base = String(tail[..<q])
            queryPart = String(tail[tail.index(after: q)...])
        } else {
            let lower = tail.lowercased()
            let amps = ownPathKeys.compactMap { k -> String.Index? in lower.range(of: "&\(k)=")?.lowerBound }
            if let amp = amps.min() {
                let offset = lower.distance(from: lower.startIndex, to: amp)
                let idx = tail.index(tail.startIndex, offsetBy: offset)
                base = String(tail[..<idx])
                queryPart = String(tail[tail.index(after: idx)...])
            }
        }
        var own: [(String, String)] = []
        var theirs: [String] = []
        for p in queryPart.components(separatedBy: "&") where !p.isEmpty {
            let key = p.substringBefore("=").lowercased()
            if ownPathKeys.contains(key) { own.append((key, decode(p.substringAfter("=", missing: "")))) } else { theirs.append(p) }
        }
        let url = withScheme(base + (theirs.isEmpty ? "" : "?" + theirs.joined(separator: "&")))
        guard isHttp(url) else { return .importContent(decode(tailRaw)) }
        let visible = own.last(where: { visibleKeys.contains($0.0) }).map { parseBool($0.1) }
        let types = own.filter { typeKeys.contains($0.0) }.map { $0.1 }.joined(separator: ";")
        return .addSubscription(url: url, name: name, visible: visible, serverTypes: ServerMarkup.normalize(types))
    }

    private static func queryImport(_ params: [(String, String)]) -> Action {
        let found = urlKeys.lazy.map { VoltaLinks.value(params, [$0]) }.first { !$0.isBlank }
        guard let address = found?.trimmed else {
            return .invalid(L10n.tr("link.noAddress"))
        }
        guard isHttp(address) else { return .importContent(address) }
        let v = VoltaLinks.value(params, Array(visibleKeys))
        let types = params.filter { typeKeys.contains($0.0) }.map { $0.1 }.joined(separator: ";")
        return .addSubscription(
            url: address,
            name: VoltaLinks.value(params, "name", "title").trimmed,
            visible: v.isBlank ? nil : parseBool(v),
            serverTypes: ServerMarkup.normalize(types)
        )
    }

    private static func crypt(_ after: String) -> Action {
        let payload: String
        let params: [(String, String)]
        if after.hasPrefix("/") && after.count > 1 {
            let body = String(after.dropFirst())
            payload = body.substringBefore("?").substringBefore("#")
            params = query(String(body.dropFirst(payload.count)))
        } else {
            params = query(after)
            payload = value(params, "data", "payload", "d")
        }
        if payload.isBlank { return .invalid(L10n.tr("link.crypt.empty")) }
        let types = params.filter { typeKeys.contains($0.0) }.map { $0.1 }.joined(separator: ";")
        return .addEncrypted(payload: payload.trimmed, name: value(params, "name", "title").trimmed,
                             serverTypes: ServerMarkup.normalize(types))
    }

    // MARK: - Helpers

    static func parseMode(_ raw: String?) -> ConnectMode? {
        guard let raw = raw else { return nil }
        let v = raw.trimmed.lowercased().replacingOccurrences(of: " ", with: "").replacingOccurrences(of: "-", with: "_")
        if v.isEmpty { return nil }
        if let exact = ConnectMode.allCases.first(where: { $0.rawValue.lowercased() == v }) { return exact }
        switch v {
        case "home", "wifi", "h", "auto_home": return .autoHome
        case "lte", "l", "mobile", "auto_lte": return .autoLte
        case "auto", "a", "all", "home_lte", "homelte", "home+lte", "home_and_lte": return .autoHomeLte
        case "manual", "own", "custom", "selected": return .manual
        default: return nil
        }
    }

    private static func parseBool(_ v: String) -> Bool {
        !["false", "0", "no", "off", "hide", "hidden", "n"].contains(v.trimmed.lowercased())
    }

    static func isHttp(_ v: String) -> Bool { v.hasPrefixIgnoringCase("https://") || v.hasPrefixIgnoringCase("http://") }

    private static let bareHost = Rx("^[\\w.-]+\\.[a-z]{2,}(?::\\d+)?(/.*)?$", [.caseInsensitive])

    private static func withScheme(_ v: String) -> String {
        let s = v.trimmed
        if isHttp(s) || s.contains("://") { return s }
        // "sub.example.com/abc": a host with a path, no scheme.
        return bareHost.matchesEntirely(s) ? "https://\(s)" : s
    }

    private static func query(_ after: String) -> [(String, String)] {
        let q = after.substringAfter("?", missing: "").substringBefore("#")
        if q.isEmpty { return [] }
        return q.components(separatedBy: "&").filter { !$0.isEmpty }.map { p in
            (p.substringBefore("=").lowercased(), decode(p.substringAfter("=", missing: "")))
        }
    }

    private static func value(_ params: [(String, String)], _ keys: String...) -> String {
        value(params, keys)
    }

    private static func value(_ params: [(String, String)], _ keys: [String]) -> String {
        for k in keys {
            if let hit = params.last(where: { $0.0 == k }) { return hit.1 }
        }
        return ""
    }

    /// %XX as UTF-8; '+' stays '+'.
    static func decode(_ s: String) -> String { Codec.percentDecode(s) }
}

/// What the user pasted into «Добавить», decided as they type, so the screen can say it
/// back and name the button after what will happen. Cheap checks only.
enum ImportKind: Equatable {
    case empty
    case subscription(hidden: Bool)
    case encrypted
    case server(String)
    case servers(Int)
    case conf
    case json
    case control
    case amnezia
    case unknown

    var isSubscription: Bool {
        switch self {
        case .subscription, .encrypted: return true
        default: return false
        }
    }

    var canSubmit: Bool { self != .empty && self != .unknown && self != .amnezia }

    static func detect(_ raw: String) -> ImportKind {
        let t = raw.trimmed
        if t.isEmpty { return .empty }
        if t.hasPrefixIgnoringCase("volta://") {
            switch VoltaLinks.parse(t) {
            case let .addSubscription(_, _, visible, _): return .subscription(hidden: visible == false)
            case .addEncrypted: return .encrypted
            case let .importContent(value):
                let inner = detect(value)
                return inner == .empty ? .unknown : inner
            case .connect, .disconnect, .toggle: return .control
            case .invalid: return .unknown
            }
        }
        if t.hasPrefixIgnoringCase("https://") || t.hasPrefixIgnoringCase("http://") {
            // One address, not a pasted page of links.
            return t.contains(where: { $0.isWhitespace }) ? .unknown : .subscription(hidden: false)
        }
        if WireGuardConf.looksLikeConf(t) { return .conf }
        if t.hasPrefixIgnoringCase("vpn://") { return .amnezia }
        if t.hasPrefix("{") || t.hasPrefix("[") { return .json }
        let links = ConfigParser.extractShareLinks(t)
        if links.count > 1 { return .servers(links.count) }
        if links.count == 1 { return .server(protocolName(links[0])) }
        if t.count > 64 && !t.contains(where: { $0.isWhitespace }),
           let decoded = Codec.decodeBase64(t), decoded.contains("://") {
            return .servers(max(1, ConfigParser.extractShareLinks(decoded).count))
        }
        return .unknown
    }

    private static func protocolName(_ link: String) -> String {
        switch link.substringBefore("://").lowercased() {
        case "vless": return "VLESS"
        case "vmess": return "VMess"
        case "trojan": return "Trojan"
        case "ss": return "Shadowsocks"
        case "socks", "socks5": return "SOCKS5"
        case "hysteria2", "hy2": return "Hysteria2"
        case "hysteria": return "Hysteria"
        case "wireguard", "wg": return "WireGuard"
        default: return link.substringBefore("://").uppercased()
        }
    }
}
