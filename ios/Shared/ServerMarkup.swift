import Foundation

/// How a provider tells Volta which servers are Home, LTE and provider balancers, the
/// same three ways as on Android:
///  1. JSON groups: `{"wifi": [...], "lte": [...], "balancers": [...]}` (ConfigParser);
///  2. a name prefix: `[H]`, `[L]`, `[A]` (also `[HOME]`, `[WIFI]`, `[LTE]`, `[AUTO]`);
///  3. the import link: `server-type=lte:1,2,5-7;home:3;auto:9` (1-based positions).
/// A server none of them marks falls back to the name check (ServerClassifier).
enum ServerMarkup {

    struct TypeMap {
        var roles: [Int: ServerRole]
        var explicit: Set<ServerRole>
        var isEmpty: Bool { roles.isEmpty }
        static let empty = TypeMap(roles: [:], explicit: [])
    }

    struct Prefixed {
        var role: ServerRole
        var remark: String
    }

    private static let prefix = Rx(
        "^\\s*[\\[(【{]\\s*(l|lte|h|home|wifi|wi-fi|a|auto|bal|balancer)\\s*[\\])】}]\\s*[-|·:•]?\\s*",
        [.caseInsensitive]
    )

    static func prefixRole(_ remark: String) -> Prefixed? {
        guard let match = prefix.allMatches(remark).first,
              let whole = Range(match.range, in: remark),
              let wordRange = Range(match.range(at: 1), in: remark),
              let role = roleForWord(String(remark[wordRange])) else { return nil }
        let rest = String(remark[whole.upperBound...]).trimmed
        return Prefixed(role: role, remark: rest.ifBlank(remark.trimmed))
    }

    /// Group named by a JSON key or a `server-type` word; nil when it names none.
    static func groupForKey(_ key: String) -> ServerRole? {
        roleForWord(String(key.lowercased().filter { $0.isLetter || $0.isNumber }))
    }

    private static func roleForWord(_ raw: String) -> ServerRole? {
        switch raw.lowercased().replacingOccurrences(of: "-", with: "") {
        case "h", "home", "wifi", "wlan", "normal", "regular", "homes":
            return .normal
        case "l", "lte", "mobile", "cellular", "bypass", "whitelist", "4g", "5g", "ltes":
            return .bypass
        case "a", "auto", "autos", "bal", "balancer", "balancers", "autobalancer", "autobalancers",
             "autobalance", "loadbalance", "loadbalancer", "loadbalancers":
            return .auto
        default:
            return nil
        }
    }

    private static let groupPattern = Rx("([\\p{L}\\d-]+)\\s*[:=]\\s*([\\d,+\\s\\-]*)")
    private static let rangePattern = Rx("^(\\d{1,4})\\s*-\\s*(\\d{1,4})$")

    /// Reads `lte:1,2,5-7;home:3|auto:9`. Junk is skipped, never fatal.
    static func parseServerTypes(_ raw: String?) -> TypeMap {
        guard let raw = raw, !raw.isBlank else { return .empty }
        var roles: [Int: ServerRole] = [:]
        var explicit = Set<ServerRole>()
        for g in groupPattern.allGroups(raw) {
            guard let role = groupForKey(g[1]) else { continue }
            let positions = parsePositions(g[2])
            if positions.isEmpty { continue }
            explicit.insert(role)
            // First mention wins: "lte:1;home:1" keeps server 1 in LTE.
            for p in positions where roles[p] == nil { roles[p] = role }
        }
        return TypeMap(roles: roles, explicit: explicit)
    }

    private static func parsePositions(_ text: String) -> [Int] {
        var out: [Int] = []
        let tokens = text.components(separatedBy: CharacterSet(charactersIn: ",+ \t")).map { $0.trimmed }.filter { !$0.isEmpty }
        for token in tokens {
            if let r = rangePattern.firstGroups(token), let a = Int(r[1]), let b = Int(r[2]) {
                if a >= 1 && a <= b && b <= maxPosition { out += Array(a...b) }
            } else if let n = Int(token), n >= 1, n <= maxPosition {
                out.append(n)
            }
        }
        var seen = Set<Int>()
        return out.filter { seen.insert($0).inserted }
    }

    /// The canonical form kept with the subscription: `lte:1,2;home:3;auto:9`.
    static func normalize(_ raw: String?) -> String {
        let map = parseServerTypes(raw)
        if map.isEmpty { return "" }
        return [ServerRole.bypass, .normal, .auto].compactMap { role -> String? in
            let positions = map.roles.filter { $0.value == role }.keys.sorted()
            if positions.isEmpty { return nil }
            return "\(wordFor(role)):\(positions.map { String($0) }.joined(separator: ","))"
        }.joined(separator: ";")
    }

    static func wordFor(_ role: ServerRole) -> String {
        switch role {
        case .normal: return "home"
        case .bypass: return "lte"
        case .auto: return "auto"
        }
    }

    static let maxPosition = 2_000
}

/// The default group of a server when the provider does not say.
enum ServerClassifier {

    /// Provider balancers. Checked before the LTE words, so «Балансер LTE» is a balancer.
    private static let autoWords = Rx(
        "(auto|авто|баланс|balanc|url.?test|fallback|лучший\\s*сервер|best\\s*server)",
        [.caseInsensitive]
    )

    /// What providers put on whitelist-bypass ("LTE") nodes. `LTE` must stand alone, so a
    /// host name that merely contains the letters does not count.
    private static let bypassWords = Rx(
        "(обход|бел(?:ый|ые|ого)?\\s*спис|white.?list|whitelist|глушилк|анти.?глуш|anti.?glush|" +
            "bypass|(?<![\\p{L}\\d])(?:lte|4g|5g)(?![\\p{L}\\d])|мобильн|моб\\.?\\s*инет|mobile)",
        [.caseInsensitive]
    )

    static func classify(_ remark: String) -> ServerRole {
        if let p = ServerMarkup.prefixRole(remark) { return p.role }
        let t = remark.trimmed
        if autoWords.matches(t) { return .auto }
        if bypassWords.matches(t) { return .bypass }
        return .normal
    }

    /// The name check, minus the groups the provider listed explicitly: a marked group is
    /// complete, so a stray "LTE" in an unmarked name does not add to it.
    static func roleByName(_ remark: String, explicit: Set<ServerRole> = []) -> ServerRole {
        let guess = classify(remark)
        return guess != .normal && explicit.contains(guess) ? .normal : guess
    }

    /// One server's group: JSON group → `[L]/[H]/[A]` prefix → `server-type` position
    /// ([position] is 0-based) → the name check.
    static func classify(_ server: Server, types: ServerMarkup.TypeMap, position: Int,
                         explicit: Set<ServerRole>? = nil) -> Server {
        var out = server
        let prefixed = ServerMarkup.prefixRole(server.remark)
        let remark = prefixed?.remark ?? server.remark
        let providerRole: ServerRole?
        if server.roleFromProvider {
            providerRole = server.role
        } else if let p = prefixed {
            providerRole = p.role
        } else {
            providerRole = types.roles[position + 1]
        }
        out.roleManual = false
        if let role = providerRole {
            out.remark = remark
            out.role = role
            out.roleFromProvider = true
        } else {
            out.role = roleByName(remark, explicit: explicit ?? types.explicit)
            out.roleFromProvider = false
        }
        return out
    }

    static func explicitGroups(_ incoming: [Server], types: ServerMarkup.TypeMap) -> Set<ServerRole> {
        var set = types.explicit
        for s in incoming {
            if s.roleFromProvider { set.insert(s.role) } else if let p = ServerMarkup.prefixRole(s.remark) { set.insert(p.role) }
        }
        return set
    }

    /// Identity of a node across refreshes. The transport is part of it: providers publish
    /// the same host twice (ws and grpc), and those must stay two servers.
    static func stableKey(_ s: Server) -> String {
        [
            s.proto.rawValue,
            s.address.trimmed.lowercased(),
            String(s.port),
            s.uuid.trimmed,
            s.network.trimmed.lowercased(),
            s.security.trimmed.lowercased(),
            s.path.trimmed,
            s.serviceName.trimmed
        ].joined(separator: "|")
    }

    /// A fresh provider list over the stored one, keeping what the user did (favourite,
    /// manual group) and what was measured.
    static func mergeProviderUpdate(previous: [Server], incoming: [Server], subscriptionId: Int64,
                                    serverTypes: String = "") -> [Server] {
        var oldByKey: [String: Server] = [:]
        for s in previous where oldByKey[stableKey(s)] == nil { oldByKey[stableKey(s)] = s }
        let types = ServerMarkup.parseServerTypes(serverTypes)
        let explicit = explicitGroups(incoming, types: types)
        var claimed = Set<Int64>()
        return incoming.enumerated().map { (index, raw) -> Server in
            var fresh = raw
            fresh.subscriptionId = subscriptionId
            fresh.sortOrder = index
            var classified = classify(fresh, types: types, position: index, explicit: explicit)
            guard let old = oldByKey[stableKey(classified)], !claimed.contains(old.id) else {
                classified.id = Server.newId()
                return classified
            }
            claimed.insert(old.id)
            classified.id = old.id
            classified.isFavorite = old.isFavorite
            if old.roleManual { classified.role = old.role }
            classified.roleManual = old.roleManual
            classified.latencyMs = old.latencyMs
            classified.ewmaMs = old.ewmaMs
            classified.failStreak = old.failStreak
            classified.lastPingAt = old.lastPingAt
            classified.tags = classified.tags.ifBlank(old.tags)
            return classified
        }
    }
}
