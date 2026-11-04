import Foundation

/// Where a routing rule sends what it matches. Stored by these names, as on Android.
enum RouteOutbound: String, Codable, CaseIterable {
    case proxy = "PROXY", direct = "DIRECT", block = "BLOCK"
}

/// One routing rule, field for field the Android `RoutingRuleDto` (backups and provider
/// JSON carry exactly these names).
struct RoutingRule: Codable, Hashable, Identifiable {
    var id: String = UUID().uuidString
    var name: String = ""
    var enabled: Bool = true
    var outbound: RouteOutbound = .proxy
    var domains: [String] = []
    var ips: [String] = []
    var ports: String = ""
    var sourcePorts: String = ""
    /// tcp / udp / empty.
    var network: String = ""
    /// http, tls, bittorrent…
    var `protocol`: [String] = []
    var inboundTags: [String] = []
    var users: [String] = []
    var attributes: String = ""
    /// user | provider | builtin
    var source: String = "user"
    var sortOrder: Int = 0

    init(id: String = UUID().uuidString, name: String = "", enabled: Bool = true, outbound: RouteOutbound = .proxy,
         domains: [String] = [], ips: [String] = [], ports: String = "", sourcePorts: String = "",
         network: String = "", protocol proto: [String] = [], inboundTags: [String] = [], users: [String] = [],
         attributes: String = "", source: String = "user", sortOrder: Int = 0) {
        self.id = id
        self.name = name
        self.enabled = enabled
        self.outbound = outbound
        self.domains = domains
        self.ips = ips
        self.ports = ports
        self.sourcePorts = sourcePorts
        self.network = network
        self.protocol = proto
        self.inboundTags = inboundTags
        self.users = users
        self.attributes = attributes
        self.source = source
        self.sortOrder = sortOrder
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.value(.id, UUID().uuidString)
        name = c.value(.name, "")
        enabled = c.value(.enabled, true)
        outbound = c.value(.outbound, .proxy)
        domains = c.value(.domains, [])
        ips = c.value(.ips, [])
        ports = c.value(.ports, "")
        sourcePorts = c.value(.sourcePorts, "")
        network = c.value(.network, "")
        self.protocol = c.value(.protocol, [])
        inboundTags = c.value(.inboundTags, [])
        users = c.value(.users, [])
        attributes = c.value(.attributes, "")
        source = c.value(.source, "user")
        sortOrder = c.value(.sortOrder, 0)
    }

    /// What the rule matches on, without its name, id and order: two rules with the same
    /// key do the same thing.
    var matchKey: String {
        [outbound.rawValue, domains.sorted().joined(separator: ","), ips.sorted().joined(separator: ","), ports,
         sourcePorts, network, self.protocol.sorted().joined(separator: ","),
         inboundTags.sorted().joined(separator: ","), users.sorted().joined(separator: ","), attributes]
            .joined(separator: "|")
    }

    var isEmpty: Bool {
        domains.isEmpty && ips.isEmpty && ports.isBlank && self.protocol.isEmpty && attributes.isBlank && network.isBlank
    }
}

/// A set of routing rules (Android `RoutingProfileEntity`).
struct RoutingProfile: Codable, Hashable, Identifiable {
    var id: Int64
    var name: String
    var description: String = ""
    /// Only meaningful for app-level profiles (sourceSubscriptionId == nil).
    var isActive: Bool = false
    var isBuiltIn: Bool = false
    /// Set: this profile came from that subscription and is private to it.
    var sourceSubscriptionId: Int64? = nil
    /// The profile may use the provider rules stored with its subscription.
    var useProviderRules: Bool = false
    var rules: [RoutingRule] = []
    var domainStrategy: String = "AsIs"
    var remoteDns: String = "https://1.1.1.1/dns-query"
    var domesticDns: String = "https://dns.google/dns-query"
    var updatedAt: Double = Date().timeIntervalSince1970
    /// Happ `Geoipurl` / `Geositeurl`: the provider's own lists, per subscription.
    var geoipUrl: String = ""
    var geositeUrl: String = ""
    /// Happ `LastUpdated`.
    var geoVersion: String = ""
    var geoUpdatedAt: Double = 0
    /// Happ `DnsHosts` as a flat JSON object.
    var dnsHostsJson: String = ""

    var isShareable: Bool { sourceSubscriptionId == nil }

    init(id: Int64, name: String, description: String = "", isActive: Bool = false, isBuiltIn: Bool = false,
         sourceSubscriptionId: Int64? = nil, useProviderRules: Bool = false, rules: [RoutingRule] = [],
         domainStrategy: String = "AsIs") {
        self.id = id
        self.name = name
        self.description = description
        self.isActive = isActive
        self.isBuiltIn = isBuiltIn
        self.sourceSubscriptionId = sourceSubscriptionId
        self.useProviderRules = useProviderRules
        self.rules = rules
        self.domainStrategy = domainStrategy
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.value(.id, 0)
        name = c.value(.name, "")
        description = c.value(.description, "")
        isActive = c.value(.isActive, false)
        isBuiltIn = c.value(.isBuiltIn, false)
        sourceSubscriptionId = (try? c.decodeIfPresent(Int64.self, forKey: .sourceSubscriptionId)) ?? nil
        useProviderRules = c.value(.useProviderRules, false)
        rules = c.value(.rules, [])
        domainStrategy = c.value(.domainStrategy, "AsIs")
        remoteDns = c.value(.remoteDns, "https://1.1.1.1/dns-query")
        domesticDns = c.value(.domesticDns, "https://dns.google/dns-query")
        updatedAt = c.value(.updatedAt, Date().timeIntervalSince1970)
        geoipUrl = c.value(.geoipUrl, "")
        geositeUrl = c.value(.geositeUrl, "")
        geoVersion = c.value(.geoVersion, "")
        geoUpdatedAt = c.value(.geoUpdatedAt, 0)
        dnsHostsJson = c.value(.dnsHostsJson, "")
    }
}

/// Routing profiles into Xray routing: a port of the Android RoutingEngine.
///
/// Merge contract (keeps subscriptions apart): `profileRules` come from one profile, which
/// belongs to at most one subscription; `providerRoutingJson` is only ever the JSON of that
/// same subscription; a profile with `useProviderRules = false` is an explicit override.
enum RoutingEngine {

    struct FormatError: LocalizedError {
        let key: String
        var errorDescription: String? { L10n.tr(key) }
    }

    // Stored names of the built-in sets and their rules (Russian, as on Android; backups find
    // the sets by them). The screen shows them through RoutingNames.
    static let builtinAllName = "Весь трафик через VPN"
    static let builtinAllDescription = "Обычный режим: наружу идёт всё, кроме локальной сети"
    static let builtinLanRule = "Локальная сеть напрямую"
    static let builtinRuName = "Российские сайты напрямую"
    static let builtinRuDescription = "RU-домены и «белый список» мимо VPN, остальное через VPN"
    static let builtinRuRule = "Россия и «белый список» напрямую"

    // MARK: - Parsing

    static func parseRules(_ json: String) -> [RoutingRule] {
        let text = json.trimmed
        if text.isEmpty || text == "[]" { return [] }
        guard let parsed = JSON.parse(text) else { return [] }
        if let list = parsed as? [Any] {
            if text.contains("\"outbound\""), let objs = list as? [JSONObject], objs.first != nil {
                return decodeDtos(list)
            }
            return xrayRulesToDto(list)
        }
        guard let obj = parsed as? JSONObject else { return [] }
        if let rules = obj.arr("rules") ?? obj.obj("routing")?.arr("rules") { return xrayRulesToDto(rules) }
        if HappRoutingParser.isHappSchema(obj) {
            return HappRoutingParser.parseHappJson(text, activate: false)?.rules ?? []
        }
        return []
    }

    static func serializeRules(_ rules: [RoutingRule]) -> String {
        guard let data = try? JSONEncoder().encode(rules) else { return "[]" }
        return String(decoding: data, as: UTF8.self)
    }

    /// Rules pasted by the user: a list, `{"rules": […]}` or a whole config with `routing`.
    static func importRules(_ json: String) throws -> [RoutingRule] {
        guard let parsed = JSON.parse(json.trimmed) else { throw FormatError(key: "routing_import_no_rules") }
        let element: Any?
        if parsed is [Any] {
            element = parsed
        } else if let obj = parsed as? JSONObject {
            element = obj.obj("routing")?["rules"] ?? obj["rules"]
        } else {
            element = nil
        }
        guard let rules = element else { throw FormatError(key: "routing_import_no_rules") }
        guard let list = rules as? [Any] else { throw FormatError(key: "routing_import_rules_not_array") }
        if list.isEmpty { return [] }
        let text = JSON.string(list)
        return text.contains("\"outbound\"") ? decodeDtos(list) : xrayRulesToDto(list)
    }

    private static func decodeDtos(_ list: [Any]) -> [RoutingRule] {
        list.compactMap { item in
            guard let obj = item as? JSONObject,
                  let data = try? JSONSerialization.data(withJSONObject: obj) else { return nil }
            return try? JSONDecoder().decode(RoutingRule.self, from: data)
        }
    }

    // MARK: - Merging

    /// User rules win, then the subscription's own rules, then whatever built-in rules the
    /// profile carries. Duplicates collapse on what they match.
    static func mergeRules(profileRules: [RoutingRule], useProviderRules: Bool,
                           providerRoutingJson: String, serverJsonRouting: String) -> [RoutingRule] {
        let user = profileRules.filter { $0.source == "user" && $0.enabled }
        let storedProvider = profileRules.filter { $0.source == "provider" }
        var provider: [RoutingRule] = []
        if useProviderRules {
            if !storedProvider.isEmpty {
                provider = storedProvider
            } else if !providerRoutingJson.isBlank {
                provider = parseRules(providerRoutingJson)
            } else if !serverJsonRouting.isBlank {
                provider = parseRules(serverJsonRouting)
            }
            provider = provider.map { var r = $0; r.source = "provider"; return r }
        }
        let builtin = profileRules.filter { $0.source == "builtin" }
        var seen = Set<String>()
        var merged: [RoutingRule] = []
        for r in user + provider + builtin where r.enabled {
            if seen.insert(r.matchKey).inserted { merged.append(r) }
        }
        return merged.enumerated().map { i, r in var x = r; x.sortOrder = i; return x }
    }

    // MARK: - Xray

    /// - Parameters:
    ///   - proxyBalancerTag: PROXY rules target this balancer instead of `proxy`.
    ///   - dnsRuleEnabled: plain DNS goes to the core's own resolver.
    ///   - dnsAddress: the DNS server the tunnel names to iOS (nothing listens there): its
    ///     lookups go to the core over UDP and TCP, anything else sent there is refused.
    static func toXrayRouting(_ rules: [RoutingRule], domainStrategy: String = "IPIfNonMatch",
                              proxyBalancerTag: String? = nil, dnsRuleEnabled: Bool = true,
                              dnsAddress: String? = nil, exitInboundTag: String? = nil) -> JSONObject {
        var out: [JSONObject] = []
        func target(_ rule: inout JSONObject) {
            if let tag = proxyBalancerTag, !tag.isBlank { rule["balancerTag"] = tag } else { rule["outboundTag"] = "proxy" }
        }
        if dnsRuleEnabled {
            out.append(["type": "field", "port": "53", "network": ["udp"], "outboundTag": "dns-out"])
            if let dns = dnsAddress {
                out.append(["type": "field", "ip": [dns], "port": "53", "network": ["tcp"], "outboundTag": "dns-out"])
                out.append(["type": "field", "ip": [dns], "outboundTag": "block"])
            }
        }
        if let tag = exitInboundTag {
            var r: JSONObject = ["type": "field", "inboundTag": [tag]]
            target(&r)
            out.append(r)
        }
        out.append(["type": "field", "ip": ["geoip:private"], "outboundTag": "direct"])
        // Stable, like Kotlin's sortedBy: rules of equal order keep theirs.
        let ordered = rules.filter { $0.enabled }.enumerated()
            .sorted { $0.element.sortOrder != $1.element.sortOrder ? $0.element.sortOrder < $1.element.sortOrder : $0.offset < $1.offset }
            .map(\.element)
        for rule in ordered {
            out.append(ruleToXray(rule, proxyBalancerTag: proxyBalancerTag))
        }
        var last: JSONObject = ["type": "field", "network": ["tcp", "udp"]]
        target(&last)
        out.append(last)
        return ["domainStrategy": domainStrategy, "rules": out]
    }

    static func ruleToXray(_ rule: RoutingRule, proxyBalancerTag: String?) -> JSONObject {
        var o: JSONObject = ["type": "field"]
        if !rule.domains.isEmpty { o["domain"] = rule.domains }
        if !rule.ips.isEmpty { o["ip"] = rule.ips }
        if !rule.ports.isBlank { o["port"] = rule.ports }
        if !rule.sourcePorts.isBlank { o["sourcePort"] = rule.sourcePorts }
        if !rule.network.isBlank { o["network"] = rule.network }
        if !rule.protocol.isEmpty { o["protocol"] = rule.protocol }
        if !rule.inboundTags.isEmpty { o["inboundTag"] = rule.inboundTags }
        if !rule.users.isEmpty { o["user"] = rule.users }
        if !rule.attributes.isBlank {
            // `attrs` comes as an object (legacy) or an expression string: re-emit its shape.
            if let obj = JSON.object(rule.attributes) { o["attrs"] = obj } else { o["attrs"] = rule.attributes }
        }
        switch rule.outbound {
        case .proxy:
            if let tag = proxyBalancerTag, !tag.isBlank { o["balancerTag"] = tag } else { o["outboundTag"] = "proxy" }
        case .direct: o["outboundTag"] = "direct"
        case .block: o["outboundTag"] = "block"
        }
        return o
    }

    // MARK: - Built-in sets

    static func builtInProfiles(nextId: () -> Int64 = { Server.newId() }) -> [RoutingProfile] {
        var all = RoutingProfile(id: nextId(), name: builtinAllName, description: builtinAllDescription,
                                 isActive: true, isBuiltIn: true)
        all.rules = [RoutingRule(name: builtinLanRule, outbound: .direct, ips: ["geoip:private"], source: "builtin")]
        var ru = RoutingProfile(id: nextId(), name: builtinRuName, description: builtinRuDescription,
                                isBuiltIn: true, domainStrategy: "IPIfNonMatch")
        ru.rules = ruBypassRules()
        return [all, ru]
    }

    /// Domains known to work under the Russian mobile whitelist.
    static let ruWhitelistDomains = [
        "gosuslugi.ru", "mos.ru", "kremlin.ru", "government.ru",
        "cbr.ru", "nalog.gov.ru", "pochta.ru",
        "vk.com", "vk.ru", "ok.ru", "mail.ru", "dzen.ru", "rutube.ru",
        "yandex.ru", "ya.ru", "yandex.net",
        "ozon.ru", "wildberries.ru", "avito.ru", "megamarket.ru",
        "tinkoff.ru", "tbank.ru", "vtb.ru", "alfabank.ru", "sberbank.ru",
        "mts.ru", "megafon.ru", "beeline.ru", "tele2.ru",
        "rzd.ru", "aeroflot.ru", "hh.ru", "2gis.ru",
        "kinopoisk.ru", "ivi.ru", "okko.tv", "max.ru"
    ]

    static func ruBypassRules() -> [RoutingRule] {
        let domains = ruWhitelistDomains.map { "domain:\($0)" } + ["geosite:category-ru", "geosite:private"]
        return [RoutingRule(name: builtinRuRule, outbound: .direct, domains: domains,
                            ips: ["geoip:ru", "geoip:private"], source: "builtin")]
    }

    // MARK: - Xray rules → rules

    static func xrayRulesToDto(_ list: [Any]) -> [RoutingRule] {
        list.compactMap { item -> RoutingRule? in
            guard let o = item as? JSONObject else { return nil }
            let type = o.str("type")
            guard type == "field" || o["type"] == nil else { return nil }
            let tag = o.str("outboundTag").ifBlank(o.str("outbound").ifBlank(o.str("balancerTag")))
            // DNS plumbing is rebuilt by the app; importing it would pin a dead tag.
            if tag.caseInsensitiveCompare("dns-out") == .orderedSame { return nil }
            let outbound: RouteOutbound
            switch tag.lowercased() {
            case "direct", "freedom": outbound = .direct
            case "block", "blackhole", "blocked": outbound = .block
            default: outbound = .proxy
            }
            var attrs = ""
            if let a = o["attrs"], !(a is NSNull) {
                if let s = a as? String { attrs = s } else { attrs = JSON.string(a) }
            }
            let rule = RoutingRule(
                name: tag.ifBlank("rule"), outbound: outbound,
                domains: strList(o["domain"]), ips: strList(o["ip"]),
                ports: scalar(o["port"]), sourcePorts: scalar(o["sourcePort"]), network: scalar(o["network"]),
                protocol: strList(o["protocol"]), inboundTags: strList(o["inboundTag"]), users: strList(o["user"]),
                attributes: attrs, source: "provider"
            )
            let useful = !rule.domains.isEmpty || !rule.ips.isEmpty || !rule.ports.isBlank ||
                !rule.protocol.isEmpty || !rule.attributes.isBlank
            return useful ? rule : nil
        }
    }

    static func strList(_ value: Any?) -> [String] {
        if let list = value as? [Any] { return list.compactMap { scalarOrNil($0) } }
        if let s = scalarOrNil(value) { return [s] }
        return []
    }

    private static func scalar(_ value: Any?) -> String {
        if let list = value as? [Any] { return list.compactMap { scalarOrNil($0) }.joined(separator: ",") }
        return scalarOrNil(value) ?? ""
    }

    private static func scalarOrNil(_ value: Any?) -> String? {
        switch value {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return nil
        }
    }
}

/// Which rules a server runs with: the one place that decides, so the screen shows what the
/// tunnel will use. A profile stamped with a subscription belongs to it and nothing else.
enum RoutingResolver {

    static func resolve(_ sub: Subscription?, _ profiles: [RoutingProfile]) -> RoutingProfile? {
        if let sub = sub, sub.routingProfileId > 0,
           let bound = profiles.first(where: { $0.id == sub.routingProfileId }),
           bound.sourceSubscriptionId == nil || bound.sourceSubscriptionId == sub.id {
            return bound
        }
        if let sub = sub, let own = profiles.first(where: { $0.sourceSubscriptionId == sub.id }) { return own }
        return appDefault(profiles)
    }

    static func appDefault(_ profiles: [RoutingProfile]) -> RoutingProfile? {
        profiles.first { $0.isActive && $0.sourceSubscriptionId == nil } ?? profiles.first { $0.isBuiltIn }
    }

    static func choicesFor(_ sub: Subscription?, _ profiles: [RoutingProfile]) -> [RoutingProfile] {
        profiles.filter { $0.sourceSubscriptionId == nil || $0.sourceSubscriptionId == sub?.id }
    }

    /// Provider JSON is readable only by its own subscription, while its profile is in use.
    static func providerJsonFor(_ sub: Subscription?, _ profile: RoutingProfile?) -> String {
        guard let owner = profile?.sourceSubscriptionId, let sub = sub, owner == sub.id else { return "" }
        return sub.providerRoutingJson
    }
}

/// Names Volta gives the sets and rules it makes, and the text keys the screen shows for
/// them. Stored names never change with the language; a name the user typed shows as is.
enum RoutingNames {
    static let mySitesVpn = "Свои сайты через VPN"
    static let mySitesDirect = "Свои сайты напрямую"
    static let blockedSites = "Заблокированные сайты"
    static let newRule = "Новое правило"

    static var ruleKeys: [String: String] {
        var map: [String: String] = [
            mySitesVpn: "routing_rule_my_sites_vpn",
            mySitesDirect: "routing_rule_my_sites_direct",
            blockedSites: "routing_rule_blocked_sites",
            newRule: "routing_rule_new",
            RoutingEngine.builtinLanRule: "routing_builtin_lan_rule",
            RoutingEngine.builtinRuRule: "routing_builtin_ru_rule",
            HappRoutingParser.ruleBlock: "routing_sub_rule_block",
            HappRoutingParser.ruleProxy: "routing_sub_rule_vpn",
            HappRoutingParser.ruleDirect: "routing_sub_rule_direct",
            HappRoutingParser.ruleLan: "routing_sub_rule_lan",
            HappRoutingParser.ruleRestDirect: "routing_sub_rule_rest_direct"
        ]
        for preset in Presets.sites { if let label = preset.labelKey { map[preset.title] = label } }
        return map
    }

    /// The rule's name as shown.
    static func ruleName(_ name: String) -> String {
        let keys = ruleKeys
        if let key = keys[name] { return L10n.tr(key) }
        if name.hasSuffix(HappRoutingParser.ipSuffix),
           let key = keys[String(name.dropLast(HappRoutingParser.ipSuffix.count))] {
            return L10n.tr(key) + HappRoutingParser.ipSuffix
        }
        return name
    }

    static func profileName(_ p: RoutingProfile) -> String {
        if p.isBuiltIn && p.name == RoutingEngine.builtinAllName { return L10n.tr("routing_builtin_all_name") }
        if p.isBuiltIn && p.name == RoutingEngine.builtinRuName { return L10n.tr("routing_builtin_ru_name") }
        if p.sourceSubscriptionId != nil && p.name == HappRoutingParser.defaultName { return L10n.tr("routing_sub_rules_name") }
        return p.name
    }

    static func profileDescription(_ p: RoutingProfile) -> String {
        if p.isBuiltIn && p.description == RoutingEngine.builtinAllDescription { return L10n.tr("routing_builtin_all_description") }
        if p.isBuiltIn && p.description == RoutingEngine.builtinRuDescription { return L10n.tr("routing_builtin_ru_description") }
        return p.description
    }
}
