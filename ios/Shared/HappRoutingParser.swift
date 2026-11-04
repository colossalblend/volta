import Foundation

/// Rules a VPN subscription ships (Happ and similar services), a port of Android's
/// HappRoutingParser. Sources: the `routing` header (happ://routing/onadd/BASE64), a
/// happ:// line in the body, Happ JSON (DirectSites / ProxySites / BlockSites…), a
/// `#routing-profile:` line, or plain Xray routing.
enum HappRoutingParser {

    // Stored with the rules, the same in every language; RoutingNames shows them translated.
    static let defaultName = "Правила подписки"
    static let ruleBlock = "Блок (из подписки)"
    static let ruleProxy = "Через VPN (из подписки)"
    static let ruleDirect = "Напрямую (из подписки)"
    static let ruleLan = "Локальная сеть"
    static let ruleRestDirect = "Остальное напрямую (из подписки)"
    static let ipSuffix = " · IP"

    struct Parsed {
        var name: String
        var rules: [RoutingRule]
        var domainStrategy: String = "AsIs"
        var remoteDns: String = ""
        var domesticDns: String = ""
        var rawJson: String = ""
        var activate: Bool = true
        var geoipUrl: String = ""
        var geositeUrl: String = ""
        var geoVersion: String = ""
        var dnsHostsJson: String = ""
    }

    static func extractFromBodyAndHeaders(body: String, routingHeader: String = "") -> Parsed? {
        if let fromHeader = parseRoutingLink(routingHeader) { return fromHeader }

        // Providers often send the whole body as base64; the metadata lines put in front
        // carry URLs, so the base64 test runs on the payload alone.
        let payload = body.components(separatedBy: "\n").map { $0.trimmed }
            .filter { !$0.isEmpty && !$0.hasPrefix("#") && !$0.hasPrefix("//") }
            .joined(separator: "\n")
        var decoded = ""
        if !payload.contains("://") && !payload.trimmed.hasPrefix("{") {
            decoded = decodeBase64Text(payload) ?? ""
        }
        let searchable = [body, decoded].filter { !$0.isBlank }.joined(separator: "\n")

        if let groups = Rx("happ://routing/(onadd|add)/([A-Za-z0-9+/=_\\-]+)", [.caseInsensitive]).firstGroups(searchable),
           groups.count >= 3, let json = decodeBase64Json(groups[2]) {
            return parseHappJson(json, activate: groups[1].lowercased() == "onadd")
        }

        let trimmed = searchable.trimmed
        if trimmed.hasPrefix("{") && ["DirectSites", "ProxySites", "BlockSites", "GlobalProxy"]
            .contains(where: { trimmed.containsIgnoringCase($0) }) {
            return parseHappJson(trimmed, activate: true)
        }
        if let json = extractRoutingProfileJson(searchable) { return parseAnyJson(json, activate: true) }
        if trimmed.hasPrefix("{") && trimmed.contains("\"routing\""),
           let root = JSON.object(trimmed), let routing = root.obj("routing") {
            return parseXrayRouting(routing, activate: true)
        }
        if trimmed.hasPrefix("{") && trimmed.contains("\"rules\""), let obj = JSON.object(trimmed), obj["rules"] != nil {
            return parseXrayRouting(obj, activate: true)
        }
        return nil
    }

    static func isHappSchema(_ o: JSONObject) -> Bool {
        ["DirectSites", "ProxySites", "BlockSites", "GlobalProxy", "DirectIp", "ProxyIp", "BlockIp"]
            .contains { o[$0] != nil }
    }

    static func extractRoutingProfileJson(_ text: String) -> String? {
        guard let marker = text.range(of: "#routing-profile:", options: .caseInsensitive),
              let brace = text[marker.upperBound...].firstIndex(of: "{") else { return nil }
        return extractJsonObject(text, from: brace)
    }

    private static func extractJsonObject(_ text: String, from start: String.Index) -> String? {
        var depth = 0
        var inString = false
        var escape = false
        var i = start
        while i < text.endIndex {
            let c = text[i]
            if inString {
                if escape { escape = false } else if c == "\\" { escape = true } else if c == "\"" { inString = false }
            } else if c == "\"" {
                inString = true
            } else if c == "{" {
                depth += 1
            } else if c == "}" {
                depth -= 1
                if depth == 0 { return String(text[start...i]) }
            }
            i = text.index(after: i)
        }
        return nil
    }

    static func parseRoutingLink(_ link: String) -> Parsed? {
        let raw = link.trimmed
        if raw.isBlank { return nil }
        let b64: String
        if raw.containsIgnoringCase("happ://routing/") {
            b64 = raw.substringAfterLast("/").trimmed
        } else if raw.hasPrefixIgnoringCase("base64:") {
            b64 = raw.substringAfter(":")
        } else {
            b64 = raw
        }
        if b64.count < 8 { return nil }
        guard let json = decodeBase64Json(b64) else { return nil }
        let activate = raw.containsIgnoringCase("onadd") || !raw.contains("/add/")
        return parseHappJson(json, activate: activate) ?? parseAnyJson(json, activate: activate)
    }

    static func parseHappJson(_ json: String, activate: Bool = true) -> Parsed? {
        guard let o = JSON.object(json) else { return nil }
        let isHapp = isHappSchema(o)
        if !isHapp && o["rules"] != nil { return parseXrayRouting(o, activate: activate) }
        if !isHapp && o["Name"] == nil { return parseAnyJson(json, activate: activate) }

        let name = str(o, "Name").ifBlank(defaultName)
        let domainStrategy = str(o, "DomainStrategy").ifBlank("AsIs")
        let remoteDns = dnsAddress(type: str(o, "RemoteDNSType"), domain: str(o, "RemoteDNSDomain"), ip: str(o, "RemoteDNSIP"))
            .ifBlank(str(o, "RemoteDns"))
        let domesticDns = dnsAddress(type: str(o, "DomesticDNSType"), domain: str(o, "DomesticDNSDomain"),
                                     ip: str(o, "DomesticDNSIP")).ifBlank(str(o, "DomesticDns"))

        var rules: [RoutingRule] = []
        var order = 0
        // Xray ANDs `domain` and `ip` in one rule: a list of each becomes two rules.
        func addList(_ sitesKey: String, _ ipsKey: String, _ outbound: RouteOutbound, _ label: String) {
            let sites = strList(o, sitesKey).map(normalizeDomain).filter { !$0.isBlank }
            let ips = strList(o, ipsKey).filter { !$0.isBlank }
            if !sites.isEmpty {
                rules.append(RoutingRule(name: label, outbound: outbound, domains: sites, source: "provider", sortOrder: order))
                order += 1
            }
            if !ips.isEmpty {
                rules.append(RoutingRule(name: label + ipSuffix, outbound: outbound, ips: ips, source: "provider", sortOrder: order))
                order += 1
            }
        }
        let routeOrder = str(o, "RouteOrder").lowercased()
        let steps = routeOrder.isBlank ? ["block", "proxy", "direct"]
            : routeOrder.components(separatedBy: CharacterSet(charactersIn: "-, ")).filter { !$0.isBlank }
        for step in steps {
            switch step {
            case "block": addList("BlockSites", "BlockIp", .block, ruleBlock)
            case "proxy": addList("ProxySites", "ProxyIp", .proxy, ruleProxy)
            case "direct": addList("DirectSites", "DirectIp", .direct, ruleDirect)
            default: break
            }
        }
        if rules.isEmpty {
            addList("BlockSites", "BlockIp", .block, ruleBlock)
            addList("ProxySites", "ProxyIp", .proxy, ruleProxy)
            addList("DirectSites", "DirectIp", .direct, ruleDirect)
        }
        rules.insert(RoutingRule(name: ruleLan, outbound: .direct, ips: ["geoip:private"], source: "provider", sortOrder: -1), at: 0)
        // GlobalProxy=false: the provider's default route is direct.
        if !bool(o, "GlobalProxy", true) {
            rules.append(RoutingRule(name: ruleRestDirect, outbound: .direct, network: "tcp,udp", source: "provider", sortOrder: order))
        }
        var parsed = Parsed(name: name, rules: rules.enumerated().map { i, r in var x = r; x.sortOrder = i; return x })
        parsed.domainStrategy = domainStrategy
        parsed.remoteDns = remoteDns
        parsed.domesticDns = domesticDns
        parsed.rawJson = json
        parsed.activate = activate
        parsed.geoipUrl = str(o, "Geoipurl").ifBlank(str(o, "GeoipUrl")).trimmed
        parsed.geositeUrl = str(o, "Geositeurl").ifBlank(str(o, "GeositeUrl")).trimmed
        parsed.geoVersion = str(o, "LastUpdated").trimmed
        parsed.dnsHostsJson = dnsHosts(o)
        return parsed
    }

    /// Happ describes a DNS server as type + domain + ip; DoH wants a URL, plain DNS an address.
    static func dnsAddress(type: String, domain: String, ip: String) -> String {
        let d = domain.trimmed
        let i = ip.trimmed
        switch type.trimmed.lowercased() {
        case "dou", "udp", "dns", "plain":
            return i.ifBlank(d.substringAfter("://").substringBefore("/"))
        case "dot", "tls":
            if !d.isBlank { return d.contains("://") ? d : "tls://\(d)" }
            return i.isBlank ? "" : "tls://\(i)"
        case "doh", "https":
            if d.hasPrefixIgnoringCase("https://") { return d }
            if !d.isBlank { return "https://\(d)/dns-query" }
            return i.isBlank ? "" : "https://\(i)/dns-query"
        default:
            return d.ifBlank(i)
        }
    }

    private static func dnsHosts(_ o: JSONObject) -> String {
        guard let el = o["DnsHosts"], !(el is NSNull) else { return "" }
        var obj: JSONObject?
        if let x = el as? JSONObject { obj = x } else if let s = el as? String { obj = JSON.object(s) }
        guard let hosts = obj, !hosts.isEmpty else { return "" }
        return JSON.string(hosts)
    }

    private static func parseXrayRouting(_ routing: JSONObject, activate: Bool) -> Parsed {
        let rules = routing.arr("rules").map { RoutingEngine.parseRules(JSON.string($0)) } ?? []
        var p = Parsed(name: defaultName, rules: rules.map { var r = $0; r.source = "provider"; return r })
        p.domainStrategy = routing.str("domainStrategy").ifBlank("AsIs")
        p.rawJson = JSON.string(routing)
        p.activate = activate
        return p
    }

    private static func parseAnyJson(_ json: String, activate: Bool) -> Parsed? {
        guard let o = JSON.object(json) else { return nil }
        if o["DirectSites"] != nil || o["GlobalProxy"] != nil { return parseHappJson(json, activate: activate) }
        if o["rules"] != nil { return parseXrayRouting(o, activate: activate) }
        if let routing = o.obj("routing") { return parseXrayRouting(routing, activate: activate) }
        // No known container: stop here rather than bounce back into RoutingEngine.parseRules.
        let rules = isHappSchema(o) ? (parseHappJson(json, activate: activate)?.rules ?? []) : []
        if rules.isEmpty { return nil }
        var p = Parsed(name: str(o, "Name").ifBlank(defaultName), rules: rules.map { var r = $0; r.source = "provider"; return r })
        p.rawJson = json
        p.activate = activate
        return p
    }

    /// `domain:` only on bare host names; prefixed entries, wildcards and addresses pass.
    private static func normalizeDomain(_ d: String) -> String {
        let t = d.trimmed
        if t.isEmpty { return t }
        for p in ["geosite:", "domain:", "full:", "regexp:", "keyword:", "ext:"] where t.hasPrefixIgnoringCase(p) { return t }
        if t.hasPrefix("*") { return "domain:" + String(t.dropFirst()).removingPrefix(".") }
        if t.contains("/") || t.contains(":") || t.allSatisfy({ $0.isNumber || $0 == "." }) { return "" }
        return "domain:\(t)"
    }

    // MARK: - Helpers

    private static func str(_ o: JSONObject, _ key: String) -> String {
        switch o[key] {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }

    private static func bool(_ o: JSONObject, _ key: String, _ fallback: Bool) -> Bool {
        switch o[key] {
        case let n as NSNumber: return n.boolValue
        case let s as String:
            switch s.lowercased() {
            case "true", "1", "yes": return true
            case "false", "0", "no": return false
            default: return fallback
            }
        default: return fallback
        }
    }

    private static func strList(_ o: JSONObject, _ key: String) -> [String] {
        if let list = o[key] as? [Any] {
            return list.compactMap { $0 as? String }
                .flatMap { $0.components(separatedBy: CharacterSet(charactersIn: "\n\r")) }
                .map { $0.trimmed }.filter { !$0.isEmpty }
        }
        if let s = o[key] as? String {
            return s.components(separatedBy: CharacterSet(charactersIn: ",;\n\r")).map { $0.trimmed }.filter { !$0.isEmpty }
        }
        return []
    }

    static func base64Data(_ text: String) -> Data? {
        var s = text.filter { !$0.isWhitespace }
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }

    private static func decodeBase64Json(_ b64: String) -> String? {
        guard let data = base64Data(b64) else { return nil }
        let text = String(decoding: data, as: UTF8.self).trimmed
        return text.hasPrefix("{") ? text : nil
    }

    private static func decodeBase64Text(_ value: String) -> String? {
        let cleaned = value.trimmed.filter { !$0.isWhitespace }
        if cleaned.count < 12 { return nil }
        guard let data = base64Data(cleaned), let text = String(data: data, encoding: .utf8) else { return nil }
        return text.contains("://") || text.trimmed.hasPrefix("{") ? text : nil
    }
}
