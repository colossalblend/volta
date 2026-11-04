import Foundation

/// The backup file, the same JSON as Android's BackupCodec, so a file saved on an Android
/// phone opens on an iPhone and the other way round.
///
/// In: subscriptions (address, name, colour, groups markup, chosen rules), servers added by
/// hand, the user's own rule sets and edited built-in ones, the settings. Out: servers of
/// subscriptions (a refresh brings them back), provider rule sets, measurements. A hidden
/// subscription address is written encrypted with Volta's key, like a volta://crypt link.
/// Reading is forgiving: a broken entry is skipped and counted, missing fields take defaults.
enum BackupCodec {

    static let format = "volta-backup"
    static let version = 1
    static let maxBytes = 4 * 1024 * 1024

    struct BackupError: LocalizedError, Equatable {
        let key: String
        var errorDescription: String? { L10n.tr(key) }
    }

    struct Snapshot {
        var subscriptions: [Subscription]
        /// Subscription id → name of the app-level rule set it is bound to.
        var subscriptionRouting: [Int64: String]
        var servers: [Server]
        var routing: [RoutingProfile]
        var activeRouting: String?
        var settings: [String: SettingValue]
        var appVersion: String
        /// Milliseconds since 1970, as Android writes it.
        var createdAt: Int64
    }

    struct RestoredSubscription {
        var subscription: Subscription
        var routingName: String?
    }

    struct Contents {
        var createdAt: Int64
        var subscriptions: [RestoredSubscription]
        var servers: [Server]
        var routing: [RoutingProfile]
        var activeRouting: String?
        var settings: [String: SettingValue]
        var skipped: Int

        var isEmpty: Bool { subscriptions.isEmpty && servers.isEmpty && routing.isEmpty && settings.isEmpty }
    }

    private static let hiddenUrl = "hiddenUrl"
    private static let routingName = "routing"

    // MARK: - Writing

    static func encode(_ snapshot: Snapshot, encryptHidden: (String) throws -> String = { try SubCrypto.encrypt($0) }) rethrows -> String {
        var root: JSONObject = [
            "format": format, "version": version, "createdAt": snapshot.createdAt, "app": snapshot.appVersion
        ]
        var subs: [JSONObject] = []
        for sub in snapshot.subscriptions {
            var out: JSONObject = [
                "name": sub.name, "nameLocked": sub.nameLocked, "userAgent": sub.userAgent,
                "updateIntervalHours": sub.updateIntervalHours, "isEnabled": sub.isEnabled, "sortOrder": sub.sortOrder,
                "hwidEnabled": sub.hwidEnabled, "colorHex": sub.colorHex, "serverTypes": sub.serverTypes
            ]
            if sub.urlHidden { out[hiddenUrl] = try encryptHidden(sub.url) } else { out["url"] = sub.url }
            if let name = snapshot.subscriptionRouting[sub.id] { out[routingName] = name }
            subs.append(out)
        }
        root["subscriptions"] = subs
        root["servers"] = snapshot.servers.map(serverJson)
        root["routing"] = snapshot.routing.map { p -> JSONObject in
            [
                "name": p.name, "description": p.description, "isBuiltIn": p.isBuiltIn,
                "useProviderRules": p.useProviderRules, "rulesJson": RoutingEngine.serializeRules(p.rules),
                "domainStrategy": p.domainStrategy, "remoteDns": p.remoteDns, "domesticDns": p.domesticDns,
                "dnsHostsJson": p.dnsHostsJson
            ]
        }
        if let active = snapshot.activeRouting { root["activeRouting"] = active }
        var settings: JSONObject = [:]
        for (k, v) in snapshot.settings { settings[k] = v.any }
        root["settings"] = settings
        return JSON.string(root, pretty: true)
    }

    /// A server as Android's ServerEntity serialises it, minus measurements and row links.
    static func serverJson(_ s: Server) -> JSONObject {
        [
            "remark": s.remark, "protocol": s.proto.rawValue, "address": s.address, "port": s.port,
            "uuid": s.uuid, "flow": s.flow, "encryption": s.encryption, "network": s.network,
            "security": s.security, "sni": s.sni, "fp": s.fp, "pbk": s.pbk, "sid": s.sid, "spx": s.spx,
            "alpn": s.alpn, "path": s.path, "host": s.host, "serviceName": s.serviceName,
            "headerType": s.headerType, "rawLink": s.rawLink, "rawJson": s.rawJson,
            "extraJson": s.extras.isEmpty ? "" : JSON.string(s.extras),
            "isFavorite": s.isFavorite, "role": s.role.rawValue, "roleManual": s.roleManual,
            "sortOrder": s.sortOrder, "tags": s.tags, "roleFromProvider": s.roleFromProvider
        ]
    }

    /// A built-in rule set goes in only when the user changed it.
    static func worthKeeping(_ profile: RoutingProfile, defaults: [RoutingProfile] = RoutingEngine.builtInProfiles()) -> Bool {
        if profile.sourceSubscriptionId != nil { return false }
        if !profile.isBuiltIn { return true }
        guard let stock = defaults.first(where: { $0.name == profile.name }) else { return false }
        // Rule ids are random per install; everything else must match.
        func rules(_ list: [RoutingRule]) -> [RoutingRule] { list.map { var r = $0; r.id = ""; return r } }
        return rules(profile.rules) != rules(stock.rules) || profile.domainStrategy != stock.domainStrategy ||
            profile.remoteDns != stock.remoteDns || profile.domesticDns != stock.domesticDns
    }

    /// `volta-2026-11-04.json`
    static func fileName(_ date: Date = Date()) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd"
        return "volta-\(f.string(from: date)).json"
    }

    // MARK: - Reading

    static func decode(_ text: String, decryptHidden: (String) throws -> String = { try SubCrypto.decrypt($0) }) throws -> Contents {
        let clean = text.trimmed.removingPrefix("\u{FEFF}")
        guard let root = JSON.object(clean) else { throw BackupError(key: "settings_backup_error_damaged") }
        guard (root["format"] as? String) == format else { throw BackupError(key: "settings_backup_error_not_volta") }
        let fileVersion = (root["version"] as? NSNumber)?.intValue ?? 0
        if fileVersion > version { throw BackupError(key: "settings_backup_error_newer") }

        var skipped = 0
        func objects(_ key: String) -> [JSONObject] {
            guard let list = root[key] as? [Any] else { return [] }
            skipped += list.filter { !($0 is JSONObject) }.count
            return list.compactMap { $0 as? JSONObject }
        }
        var subscriptions: [RestoredSubscription] = []
        for obj in objects("subscriptions") {
            if let s = readSubscription(obj, decryptHidden) { subscriptions.append(s) } else { skipped += 1 }
        }
        var servers: [Server] = []
        for obj in objects("servers") {
            if let s = readServer(obj) { servers.append(s) } else { skipped += 1 }
        }
        var routing: [RoutingProfile] = []
        for obj in objects("routing") {
            if let p = readRouting(obj) { routing.append(p) } else { skipped += 1 }
        }
        var settings: [String: SettingValue] = [:]
        if let obj = root["settings"] as? JSONObject {
            for (k, v) in obj where !(v is JSONObject) && !(v is NSNull) {
                if let value = SettingValue(any: v) { settings[k] = value }
            }
        }
        let active = (root["activeRouting"] as? String).flatMap { $0.isBlank ? nil : $0 }
        return Contents(
            createdAt: (root["createdAt"] as? NSNumber)?.int64Value ?? 0,
            subscriptions: subscriptions, servers: servers, routing: routing,
            activeRouting: active, settings: settings, skipped: skipped
        )
    }

    private static func string(_ o: JSONObject, _ key: String) -> String? { o[key] as? String }
    private static func bool(_ o: JSONObject, _ key: String) -> Bool? {
        guard let n = o[key] as? NSNumber else { return nil }
        return n.boolValue
    }
    private static func int(_ o: JSONObject, _ key: String) -> Int? { (o[key] as? NSNumber)?.intValue }

    private static func readSubscription(_ obj: JSONObject, _ decryptHidden: (String) throws -> String) -> RestoredSubscription? {
        let hidden = string(obj, hiddenUrl)
        let url: String?
        if let h = hidden { url = try? decryptHidden(h) } else { url = string(obj, "url") }
        // Volta takes only protected links.
        guard let link = url?.trimmed, link.hasPrefixIgnoringCase("https://"), link.count > "https://".count else { return nil }
        var sub = Subscription(id: 0, name: "", url: link)
        sub.name = (string(obj, "name") ?? "").trimmed.ifBlank("Подписка")
        sub.nameLocked = bool(obj, "nameLocked") ?? false
        sub.userAgent = string(obj, "userAgent") ?? ""
        sub.updateIntervalHours = int(obj, "updateIntervalHours") ?? 12
        sub.isEnabled = bool(obj, "isEnabled") ?? true
        sub.sortOrder = int(obj, "sortOrder") ?? 0
        sub.hwidEnabled = bool(obj, "hwidEnabled") ?? true
        sub.colorHex = string(obj, "colorHex") ?? "#C4784A"
        sub.serverTypes = string(obj, "serverTypes") ?? ""
        sub.urlHidden = hidden != nil
        let routing = string(obj, routingName).flatMap { $0.isBlank ? nil : $0 }
        return RestoredSubscription(subscription: sub, routingName: routing)
    }

    private static func readServer(_ obj: JSONObject) -> Server? {
        // A protocol this version does not know cannot be dialled.
        guard let p = string(obj, "protocol"), let proto = ProtocolType(rawValue: p), proto != .unknown else { return nil }
        var s = Server(remark: string(obj, "remark") ?? "", proto: proto, address: string(obj, "address") ?? "",
                       port: int(obj, "port") ?? 0)
        func text(_ key: String, _ set: (String) -> Void) { if let v = string(obj, key) { set(v) } }
        text("uuid") { s.uuid = $0 }
        text("flow") { s.flow = $0 }
        text("encryption") { s.encryption = $0 }
        text("network") { s.network = $0 }
        text("security") { s.security = $0 }
        text("sni") { s.sni = $0 }
        text("fp") { s.fp = $0 }
        text("pbk") { s.pbk = $0 }
        text("sid") { s.sid = $0 }
        text("spx") { s.spx = $0 }
        text("alpn") { s.alpn = $0 }
        text("path") { s.path = $0 }
        text("host") { s.host = $0 }
        text("serviceName") { s.serviceName = $0 }
        text("headerType") { s.headerType = $0 }
        text("rawLink") { s.rawLink = $0 }
        text("rawJson") { s.rawJson = $0 }
        text("tags") { s.tags = $0 }
        if let extra = string(obj, "extraJson"), let map = JSON.object(extra) {
            var extras: [String: String] = [:]
            for (k, v) in map {
                if let str = v as? String { extras[k] = str } else if let n = v as? NSNumber { extras[k] = n.stringValue }
            }
            s.extras = extras
        }
        s.isFavorite = bool(obj, "isFavorite") ?? false
        // An unknown group falls back to the ordinary one rather than losing the server.
        s.role = string(obj, "role").flatMap(ServerRole.init(rawValue:)) ?? .normal
        s.roleManual = bool(obj, "roleManual") ?? false
        s.sortOrder = int(obj, "sortOrder") ?? 0
        s.roleFromProvider = bool(obj, "roleFromProvider") ?? false
        s.id = 0
        s.subscriptionId = nil
        return s
    }

    private static func readRouting(_ obj: JSONObject) -> RoutingProfile? {
        guard let name = string(obj, "name")?.trimmed, !name.isBlank else { return nil }
        var p = RoutingProfile(id: 0, name: name)
        p.description = string(obj, "description") ?? ""
        p.isBuiltIn = bool(obj, "isBuiltIn") ?? false
        p.useProviderRules = bool(obj, "useProviderRules") ?? false
        p.rules = RoutingEngine.parseRules(string(obj, "rulesJson") ?? "[]")
        p.domainStrategy = string(obj, "domainStrategy") ?? "AsIs"
        p.remoteDns = string(obj, "remoteDns") ?? "https://1.1.1.1/dns-query"
        p.domesticDns = string(obj, "domesticDns") ?? "https://dns.google/dns-query"
        p.dnsHostsJson = string(obj, "dnsHostsJson") ?? ""
        return p
    }

    /// The same server, whatever it is called: one already there is not added twice.
    static func sameServerKey(_ s: Server) -> String {
        [s.proto.rawValue, s.address.lowercased(), String(s.port), s.uuid, s.path, s.rawLink, s.rawJson]
            .joined(separator: "\u{1}")
    }
}
