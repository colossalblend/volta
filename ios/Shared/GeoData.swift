import Foundation

/// geoip.dat / geosite.dat for a Network Extension that has about 50 MB.
///
/// The app ships the full lists (v2fly's, as the Android core does) and, before every
/// connect, writes small copies holding only the lists the config names: geosite:category-ru
/// is 26 KB of a 2.4 MB file, geoip:ru 0.4 MB of 5. The config points Xray at them with
/// its `env` section (`xray.location.asset`), so rules keep their Android spelling
/// (`geosite:…`, `geoip:…`, `ext:file:code`) and the core only ever loads what it uses.
///
/// Both files are protobuf: a list of entries (field 1), each starting with its code
/// (field 1, a string). Entries are copied byte for byte; nothing inside is decoded.
enum GeoData {

    /// One list in a .dat file: its code (upper case) and where its bytes are.
    struct Index {
        var entries: [String: Range<Int>] = [:]
        var data: Data

        init(_ data: Data) {
            // Offsets are counted from 0: a slice is copied out first.
            self.data = data.startIndex == 0 ? data : Data(Array(data))
            entries = GeoData.scan(self.data)
        }

        func has(_ code: String) -> Bool { entries[code.uppercased()] != nil }
        func size(_ code: String) -> Int { entries[code.uppercased()]?.count ?? 0 }
        var codes: [String] { entries.keys.sorted() }

        /// A file with only [codes], in the same format.
        func trimmed(to codes: Set<String>) -> Data {
            var out = Data()
            for code in codes.sorted() {
                guard let range = entries[code.uppercased()] else { continue }
                out.append(0x0A)
                GeoData.appendVarint(&out, UInt64(range.count))
                out.append(data.subdata(in: range))
            }
            return out
        }
    }

    static func scan(_ data: Data) -> [String: Range<Int>] {
        var out: [String: Range<Int>] = [:]
        let bytes = [UInt8](data)
        var i = 0
        while i < bytes.count {
            guard let (key, afterKey) = varint(bytes, i) else { break }
            i = afterKey
            let field = key >> 3
            let wire = key & 7
            switch wire {
            case 0:
                guard let (_, next) = varint(bytes, i) else { return out }
                i = next
            case 2:
                guard let (length, next) = varint(bytes, i), next + Int(length) <= bytes.count else { return out }
                let range = next..<(next + Int(length))
                if field == 1, let code = entryCode(bytes, range) { out[code.uppercased()] = range }
                i = range.upperBound
            case 1: i += 8
            case 5: i += 4
            default: return out
            }
        }
        return out
    }

    private static func entryCode(_ bytes: [UInt8], _ range: Range<Int>) -> String? {
        var i = range.lowerBound
        while i < range.upperBound {
            guard let (key, afterKey) = varint(bytes, i) else { return nil }
            i = afterKey
            let wire = key & 7
            if wire == 2 {
                guard let (length, next) = varint(bytes, i), next + Int(length) <= range.upperBound else { return nil }
                if key >> 3 == 1 { return String(decoding: bytes[next..<(next + Int(length))], as: UTF8.self) }
                i = next + Int(length)
            } else if wire == 0 {
                guard let (_, next) = varint(bytes, i) else { return nil }
                i = next
            } else {
                return nil
            }
        }
        return nil
    }

    private static func varint(_ b: [UInt8], _ start: Int) -> (UInt64, Int)? {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        var i = start
        while i < b.count && shift < 64 {
            let c = b[i]
            result |= UInt64(c & 0x7F) << shift
            i += 1
            if c < 0x80 { return (result, i) }
            shift += 7
        }
        return nil
    }

    static func appendVarint(_ out: inout Data, _ value: UInt64) {
        var v = value
        while v >= 0x80 {
            out.append(UInt8(v & 0x7F) | 0x80)
            v >>= 7
        }
        out.append(UInt8(v))
    }

    // MARK: - Writing a config's lists

    /// Where the lists come from: the app's own files and a subscription's (by file name,
    /// as `ext:` names them: `geoip_s<ID>.dat`).
    struct Sources {
        var geosite: Index?
        var geoip: Index?
        var ext: [String: Index] = [:]
    }

    /// The biggest single list (bytes) the extension is given. geoip:ru is 0.4 MB, the
    /// biggest country lists (us, au, nl…) are several MB each and would not fit.
    static let maxListBytes = 1_200_000
    /// All lists of one config together.
    static let maxTotalBytes = 2_500_000

    struct TooBig: LocalizedError {
        let list: String
        var errorDescription: String? { L10n.tr("geo.error.tooBig", list) }
    }

    struct Result {
        /// The config with every list that could not be found taken out.
        var config: JSONObject
        /// File name → contents, for the asset folder.
        var files: [String: Data]
        /// Lists named in the config but not present in any file (left out of it).
        var missing: [String]
    }

    /// Finds every list [config] names, takes out the ones no file has (a rule left with
    /// nothing to match is removed), and makes the trimmed files.
    static func prepare(_ config: JSONObject, sources: Sources) throws -> Result {
        var root = config
        var wanted: [String: Set<String>] = [:]   // file name → codes
        var missing: [String] = []

        func resolve(_ entry: String, site: Bool) -> String? {
            let t = entry.trimmed
            let lower = t.lowercased()
            var file: String
            var code: String
            var index: Index?
            if lower.hasPrefix("ext:") {
                let rest = String(t.dropFirst(4))
                file = rest.substringBefore(":")
                code = rest.substringAfter(":", missing: "")
                index = sources.ext[file]
            } else if lower.hasPrefix(site ? "geosite:" : "geoip:") {
                file = site ? "geosite.dat" : "geoip.dat"
                code = t.substringAfter(":")
                index = site ? sources.geosite : sources.geoip
            } else {
                return t
            }
            // geosite:cn@ads → the list cn (Xray filters the attribute itself).
            var bare = code.substringBefore("@")
            if !site && bare.hasPrefix("!") { bare = String(bare.dropFirst()) }
            guard !bare.isBlank, let idx = index, idx.has(bare) else {
                // Private addresses are spelled out when no file has them.
                if !site && bare.lowercased() == "private" { return "\u{1}private" }
                if !missing.contains(t) { missing.append(t) }
                return nil
            }
            wanted[file, default: []].insert(bare.uppercased())
            return t
        }

        func rewrite(_ list: [String], site: Bool) -> [String] {
            var out: [String] = []
            for item in list {
                guard let r = resolve(item, site: site) else { continue }
                if r == "\u{1}private" { out += ConfigBuilder.privateRanges } else { out.append(r) }
            }
            return out
        }

        if var routing = root.obj("routing"), let rules = routing["rules"] as? [JSONObject] {
            var kept: [JSONObject] = []
            for var rule in rules {
                var emptied = false
                for (key, site) in [("domain", true), ("ip", false)] {
                    guard let list = rule[key] as? [String] else { continue }
                    let next = rewrite(list, site: site)
                    if next.isEmpty { rule.removeValue(forKey: key); emptied = true } else { rule[key] = next }
                }
                // A rule whose only lists were all missing would match everything left.
                if emptied && rule["domain"] == nil && rule["ip"] == nil && rule["port"] == nil &&
                    rule["protocol"] == nil && rule["inboundTag"] == nil && rule["attrs"] == nil {
                    continue
                }
                kept.append(rule)
            }
            routing["rules"] = kept
            root["routing"] = routing
        }

        if var dns = root.obj("dns"), let servers = dns["servers"] as? [Any] {
            dns["servers"] = servers.compactMap { item -> Any? in
                guard var server = item as? JSONObject else { return item }
                if let domains = server["domains"] as? [String] {
                    let next = rewrite(domains, site: true)
                    if next.isEmpty { return nil }
                    server["domains"] = next
                }
                if let ips = server["expectIPs"] as? [String] {
                    let next = rewrite(ips, site: false)
                    if next.isEmpty { server.removeValue(forKey: "expectIPs") } else { server["expectIPs"] = next }
                }
                return server
            }
            root["dns"] = dns
        }

        var files: [String: Data] = [:]
        var total = 0
        for (file, codes) in wanted {
            let index: Index?
            switch file {
            case "geosite.dat": index = sources.geosite
            case "geoip.dat": index = sources.geoip
            default: index = sources.ext[file]
            }
            guard let idx = index else { continue }
            for code in codes where idx.size(code) > maxListBytes {
                throw TooBig(list: (file.contains("site") ? "geosite:" : "geoip:") + code.lowercased())
            }
            let data = idx.trimmed(to: codes)
            total += data.count
            files[file] = data
        }
        if total > maxTotalBytes {
            throw TooBig(list: wanted.values.flatMap { $0 }.sorted().joined(separator: ", ").lowercased())
        }
        return Result(config: root, files: files, missing: missing)
    }
}

/// A subscription's own geo files, by the names `ext:` uses (Android's GeoNames):
/// `geoip:ru` → `ext:geoip_s7.dat:ru`; `geoip:private` and everything else stay.
struct GeoNames: Equatable {
    var geoip: String?
    var geosite: String?

    static func forSubscription(_ id: Int64, hasGeoip: Bool, hasGeosite: Bool) -> GeoNames? {
        if !hasGeoip && !hasGeosite { return nil }
        return GeoNames(geoip: hasGeoip ? "geoip_s\(id).dat" : nil, geosite: hasGeosite ? "geosite_s\(id).dat" : nil)
    }

    func rewrite(_ entry: String) -> String {
        let t = entry.trimmed
        if t.lowercased() == "geoip:private" { return entry }
        if let ip = geoip, t.hasPrefixIgnoringCase("geoip:") { return "ext:\(ip):\(t.substringAfter(":"))" }
        if let site = geosite, t.hasPrefixIgnoringCase("geosite:") { return "ext:\(site):\(t.substringAfter(":"))" }
        return entry
    }

    func rewrite(_ rule: RoutingRule) -> RoutingRule {
        var r = rule
        r.domains = rule.domains.map { self.rewrite($0) }
        r.ips = rule.ips.map { self.rewrite($0) }
        return r
    }
}

extension GeoData {
    /// Two trimmed copies of the same file as one (the lists of several configs).
    static func merge(_ a: Data, _ b: Data) -> Data {
        let x = Index(a), y = Index(b)
        var out = Data()
        var seen = Set<String>()
        for (index, codes) in [(x, x.codes), (y, y.codes)] {
            for code in codes where seen.insert(code).inserted {
                guard let range = index.entries[code] else { continue }
                out.append(0x0A)
                appendVarint(&out, UInt64(range.count))
                out.append(index.data.subdata(in: range))
            }
        }
        return out
    }
}
