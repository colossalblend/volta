import Foundation

// Small helpers the Kotlin code got from its standard library, so the ported logic reads
// the same: substringBefore/After, isBlank, ifBlank, regexes and loose JSON access.

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }

    var isBlank: Bool { trimmed.isEmpty }

    func ifBlank(_ other: @autoclosure () -> String) -> String { isBlank ? other() : self }

    /// Text before the first [delimiter]; the whole string when it is not there.
    func substringBefore(_ delimiter: String) -> String {
        guard let r = range(of: delimiter) else { return self }
        return String(self[..<r.lowerBound])
    }

    /// Text after the first [delimiter]; [missing] (the whole string by default) when absent.
    func substringAfter(_ delimiter: String, missing: String? = nil) -> String {
        guard let r = range(of: delimiter) else { return missing ?? self }
        return String(self[r.upperBound...])
    }

    func substringBeforeLast(_ delimiter: String) -> String {
        guard let r = range(of: delimiter, options: .backwards) else { return self }
        return String(self[..<r.lowerBound])
    }

    func substringAfterLast(_ delimiter: String, missing: String? = nil) -> String {
        guard let r = range(of: delimiter, options: .backwards) else { return missing ?? self }
        return String(self[r.upperBound...])
    }

    func hasPrefixIgnoringCase(_ prefix: String) -> Bool {
        range(of: prefix, options: [.caseInsensitive, .anchored]) != nil
    }

    func containsIgnoringCase(_ other: String) -> Bool {
        range(of: other, options: .caseInsensitive) != nil
    }

    func removingPrefix(_ prefix: String) -> String {
        hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
    }

    func trimmingChars(_ chars: String) -> String {
        trimmingCharacters(in: CharacterSet(charactersIn: chars))
    }

    func trimmingTrailing(_ chars: String) -> String {
        var s = Substring(self)
        while let last = s.last, chars.contains(last) { s = s.dropLast() }
        return String(s)
    }

    /// The first [n] characters.
    func take(_ n: Int) -> String { String(prefix(max(0, n))) }
}

/// NSRegularExpression with the few calls the parsers need.
struct Rx {
    let re: NSRegularExpression

    init(_ pattern: String, _ options: NSRegularExpression.Options = []) {
        // Patterns are literals in this code base; a bad one is a programming error.
        re = try! NSRegularExpression(pattern: pattern, options: options)
    }

    private func whole(_ s: String) -> NSRange { NSRange(s.startIndex..<s.endIndex, in: s) }

    func matches(_ s: String) -> Bool {
        re.firstMatch(in: s, options: [], range: whole(s)) != nil
    }

    /// The whole string must match.
    func matchesEntirely(_ s: String) -> Bool {
        guard let m = re.firstMatch(in: s, options: [], range: whole(s)) else { return false }
        return m.range.location == 0 && m.range.length == (s as NSString).length
    }

    /// Groups of the first match: [0] is the whole match; missing groups are "".
    func firstGroups(_ s: String) -> [String]? {
        guard let m = re.firstMatch(in: s, options: [], range: whole(s)) else { return nil }
        return Rx.groups(m, in: s)
    }

    func allGroups(_ s: String) -> [[String]] {
        re.matches(in: s, options: [], range: whole(s)).map { Rx.groups($0, in: s) }
    }

    func allMatches(_ s: String) -> [NSTextCheckingResult] {
        re.matches(in: s, options: [], range: whole(s))
    }

    func replace(_ s: String, with template: String) -> String {
        re.stringByReplacingMatches(in: s, options: [], range: whole(s), withTemplate: template)
    }

    static func groups(_ m: NSTextCheckingResult, in s: String) -> [String] {
        (0..<m.numberOfRanges).map { i in
            let r = m.range(at: i)
            guard r.location != NSNotFound, let range = Range(r, in: s) else { return "" }
            return String(s[range])
        }
    }
}

// MARK: - Loose JSON

typealias JSONObject = [String: Any]

enum JSON {
    static func parse(_ text: String) -> Any? {
        guard let data = text.data(using: .utf8) else { return nil }
        return try? JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    static func object(_ text: String) -> JSONObject? { parse(text) as? JSONObject }

    static func string(_ value: Any, pretty: Bool = false) -> String {
        guard JSONSerialization.isValidJSONObject(value) else { return "" }
        var options: JSONSerialization.WritingOptions = [.withoutEscapingSlashes]
        if pretty { options.insert(.prettyPrinted); options.insert(.sortedKeys) }
        guard let data = try? JSONSerialization.data(withJSONObject: value, options: options) else { return "" }
        return String(decoding: data, as: UTF8.self)
    }
}

extension Dictionary where Key == String, Value == Any {
    /// A string field: strings as they are, numbers and booleans as text, anything else "".
    func str(_ key: String) -> String {
        switch self[key] {
        case let s as String: return s
        case let n as NSNumber:
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return n.boolValue ? "true" : "false" }
            return n.stringValue
        default: return ""
        }
    }

    func int(_ key: String, _ fallback: Int) -> Int {
        switch self[key] {
        case let n as NSNumber: return n.intValue
        case let s as String: return Int(s.trimmed) ?? fallback
        default: return fallback
        }
    }

    func obj(_ key: String) -> JSONObject? { self[key] as? JSONObject }

    func arr(_ key: String) -> [Any]? { self[key] as? [Any] }
}

// MARK: - Base64 and percent-decoding

enum Codec {
    /// Standard or URL-safe base64, padded or not, whitespace ignored. Invalid UTF-8 turns
    /// into replacement characters rather than failing, as Java's decoder did.
    static func decodeBase64(_ input: String) -> String? {
        guard let data = decodeBase64Data(input) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    static func decodeBase64Data(_ input: String) -> Data? {
        let cleaned = input.unicodeScalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) }
        var s = String(String.UnicodeScalarView(cleaned))
        if s.isEmpty { return nil }
        let standard = s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "+" || $0 == "/" || $0 == "=") }
        let urlSafe = s.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber || $0 == "-" || $0 == "_" || $0 == "=") }
        if !standard {
            guard urlSafe else { return nil }
            s = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        }
        let pad = (4 - s.count % 4) % 4
        s += String(repeating: "=", count: pad)
        return Data(base64Encoded: s)
    }

    /// Percent-decoding that leaves `+` alone: WireGuard keys, base64 credentials and
    /// passwords may legally hold a `+`, and form decoding would turn it into a space.
    static func percentDecode(_ raw: String) -> String {
        if raw.isBlank || !raw.contains("%") { return raw }
        var out = ""
        var bytes: [UInt8] = []
        func flush() {
            if !bytes.isEmpty {
                out += String(decoding: bytes, as: UTF8.self)
                bytes.removeAll()
            }
        }
        let units = Array(raw.utf8)
        var i = 0
        while i < units.count {
            let c = units[i]
            if c == UInt8(ascii: "%"), i + 2 < units.count,
               let hi = hexValue(units[i + 1]), let lo = hexValue(units[i + 2]) {
                bytes.append(hi << 4 | lo)
                i += 3
                continue
            }
            flush()
            // Copy one whole UTF-8 sequence starting here.
            var j = i + 1
            while j < units.count, units[j] & 0xC0 == 0x80 { j += 1 }
            out += String(decoding: units[i..<j], as: UTF8.self)
            i = j
        }
        flush()
        return out
    }

    private static func hexValue(_ c: UInt8) -> UInt8? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return c - UInt8(ascii: "A") + 10
        default: return nil
        }
    }
}
