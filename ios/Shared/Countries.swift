import Foundation

/// Which country a server is in, read from its name: the flag a provider puts there
/// (🇩🇪), else a country or city named in Russian or English ("Германия", "Frankfurt"),
/// else a two-letter code standing on its own ("DE"). Only for grouping the list.
enum Countries {

    struct Country: Hashable {
        let code: String
        let name: String
        let flag: String
    }

    static let unknownCode = "ZZ"

    /// The language country names are shown in; the app sets it to its own language.
    static var displayLanguage: String = Bundle.main.preferredLocalizations.first ?? "ru"

    static func of(_ remark: String) -> Country {
        guard let code = codeOf(remark) else { return unknown }
        return byCode(code)
    }

    /// "Другие": named by the app (see L10n), the code is enough here.
    static var unknown: Country { Country(code: unknownCode, name: "", flag: "🌐") }

    static func byCode(_ code: String) -> Country {
        let upper = code.uppercased()
        if upper == unknownCode { return unknown }
        let lang = displayLanguage
        var name = Locale(identifier: lang).localizedString(forRegionCode: upper) ?? upper
        if lang.hasPrefix("ru"), let fixed = nameFixes[upper] { name = fixed }
        return Country(code: upper, name: name, flag: flagOf(upper))
    }

    /// 🇩🇪 for "DE".
    static func flagOf(_ code: String) -> String {
        var out = ""
        for scalar in code.uppercased().unicodeScalars {
            guard scalar.value >= 65 && scalar.value <= 90,
                  let flag = Unicode.Scalar(0x1F1E6 + scalar.value - 65) else { return "🌐" }
            out.unicodeScalars.append(flag)
        }
        return out
    }

    private static func isRegional(_ s: Unicode.Scalar) -> Bool { (0x1F1E6...0x1F1FF).contains(s.value) }

    /// The name without the leading flag, when the flag is shown next to it anyway.
    static func withoutLeadingFlag(_ remark: String) -> String {
        let scalars = Array(remark.unicodeScalars.drop { $0 == " " || $0 == "\t" })
        guard scalars.count >= 3, isRegional(scalars[0]), isRegional(scalars[1]) else { return remark }
        var rest = String(String.UnicodeScalarView(scalars.dropFirst(2)))
        rest = rest.trimmingCharacters(in: CharacterSet(charactersIn: " |·-—:\u{00A0}"))
        return rest.isBlank ? remark : rest
    }

    static func codeOf(_ remark: String) -> String? {
        if let flag = flagCode(remark) { return flag }
        let lower = remark.lowercased()
        // Phrases first ("южная корея"), so "корея" alone cannot take it.
        for (phrase, code) in phrases where lower.contains(phrase) { return code }
        let words = wordPattern.allGroups(lower).map { $0[0] }
        for word in words {
            if let code = singleWords[word] { return code }
            // Russian endings: "в Германии", "Финляндии".
            if let hit = stems.first(where: { word.hasPrefix($0.stem) && word.count <= $0.full.count + 2 }) {
                return hit.code
            }
        }
        for token in codeToken.allGroups(remark).map({ $0[0] }) where commonCodes.contains(token) {
            return token == "UK" ? "GB" : token
        }
        return nil
    }

    private static func flagCode(_ remark: String) -> String? {
        let scalars = Array(remark.unicodeScalars)
        var i = 0
        while i + 1 < scalars.count {
            if isRegional(scalars[i]) && isRegional(scalars[i + 1]) {
                let a = Character(Unicode.Scalar(scalars[i].value - 0x1F1E6 + 65)!)
                let b = Character(Unicode.Scalar(scalars[i + 1].value - 0x1F1E6 + 65)!)
                return String([a, b])
            }
            i += 1
        }
        return nil
    }

    private static let wordPattern = Rx("[\\p{L}]+(?:-[\\p{L}]+)?")
    private static let codeToken = Rx("(?<![A-Za-z])[A-Z]{2}(?![A-Za-z])")

    /// Codes common enough on VPN lists to trust as a bare "DE" / "NL".
    private static let commonCodes: Set<String> = [
        "DE", "NL", "FI", "SE", "NO", "DK", "US", "GB", "UK", "FR", "PL", "EE", "LV", "LT",
        "TR", "KZ", "RU", "JP", "SG", "HK", "CA", "CH", "AT", "ES", "AE", "IL", "UA", "CZ",
        "RO", "MD", "AM", "GE", "IE", "BG", "RS", "HU", "PT", "KR", "AU", "BR", "IN", "BY",
        "UZ", "KG", "AZ", "CY", "GR", "LU", "BE", "SK", "SI", "HR", "IS", "AR", "MX", "TW"
    ]

    /// Shorter Russian names than the official ones.
    private static let nameFixes: [String: String] = [
        "US": "США", "GB": "Великобритания", "AE": "ОАЭ", "KR": "Южная Корея",
        "HK": "Гонконг", "MD": "Молдова", "CZ": "Чехия"
    ]

    private static let aliases: [String: String] = [
        "сша": "US", "usa": "US", "америка": "US", "штаты": "US", "america": "US",
        "англия": "GB", "британия": "GB", "england": "GB", "britain": "GB",
        "голландия": "NL", "holland": "NL", "оаэ": "AE", "uae": "AE",
        "эмираты": "AE", "emirates": "AE", "дубай": "AE", "dubai": "AE",
        "корея": "KR", "korea": "KR", "чехия": "CZ", "czechia": "CZ",
        "молдавия": "MD", "turkey": "TR", "türkiye": "TR",
        "москва": "RU", "moscow": "RU", "питер": "RU", "спб": "RU",
        "амстердам": "NL", "amsterdam": "NL", "роттердам": "NL",
        "франкфурт": "DE", "frankfurt": "DE", "берлин": "DE", "berlin": "DE",
        "мюнхен": "DE", "munich": "DE", "нюрнберг": "DE", "nuremberg": "DE",
        "хельсинки": "FI", "helsinki": "FI", "стокгольм": "SE", "stockholm": "SE",
        "лондон": "GB", "london": "GB", "париж": "FR", "paris": "FR",
        "варшава": "PL", "warsaw": "PL", "таллин": "EE", "таллинн": "EE",
        "tallinn": "EE", "рига": "LV", "riga": "LV", "вильнюс": "LT",
        "vilnius": "LT", "стамбул": "TR", "istanbul": "TR", "алматы": "KZ",
        "almaty": "KZ", "астана": "KZ", "astana": "KZ", "токио": "JP",
        "tokyo": "JP", "торонто": "CA", "toronto": "CA", "цюрих": "CH",
        "zurich": "CH", "вена": "AT", "vienna": "AT", "милан": "IT",
        "milan": "IT", "мадрид": "ES", "madrid": "ES", "прага": "CZ",
        "prague": "CZ", "бухарест": "RO", "bucharest": "RO", "кишинев": "MD",
        "кишинёв": "MD", "chisinau": "MD", "ереван": "AM", "yerevan": "AM",
        "тбилиси": "GE", "tbilisi": "GE", "осло": "NO", "oslo": "NO",
        "копенгаген": "DK", "copenhagen": "DK", "дублин": "IE", "dublin": "IE",
        "белград": "RS", "belgrade": "RS", "будапешт": "HU", "budapest": "HU",
        "лиссабон": "PT", "lisbon": "PT", "ташкент": "UZ", "tashkent": "UZ",
        "бишкек": "KG", "bishkek": "KG", "баку": "AZ", "baku": "AZ",
        "минск": "BY", "minsk": "BY", "киев": "UA", "kyiv": "UA",
        "нью-йорк": "US", "майами": "US", "miami": "US", "даллас": "US",
        "dallas": "US", "чикаго": "US", "chicago": "US", "сиэтл": "US",
        "seattle": "US", "фремонт": "US", "fremont": "US"
    ]

    /// Country names that are also everyday words.
    private static let ambiguous: Set<String> = ["того", "мали", "чад", "гана", "chad", "jersey", "man", "мэн"]

    /// Every country's Russian and English name, plus the aliases.
    private static let names: [(String, String)] = {
        var out: [(String, String)] = []
        let locales = [Locale(identifier: "ru"), Locale(identifier: "en")]
        let codes = Locale.Region.isoRegions.map { $0.identifier }.filter { $0.count == 2 && $0.allSatisfy { $0.isLetter } }
        for code in codes {
            for locale in locales {
                guard let name = locale.localizedString(forRegionCode: code)?.lowercased() else { continue }
                if name.isBlank || name == code.lowercased() || ambiguous.contains(name) { continue }
                out.append((name, code))
            }
        }
        for (code, name) in nameFixes { out.append((name.lowercased(), code)) }
        for (alias, code) in aliases { out.append((alias, code)) }
        return out
    }()

    private static let phrases: [(String, String)] =
        names.filter { $0.0.contains(" ") }.sorted { $0.0.count > $1.0.count }

    private static let singleWords: [String: String] = {
        var map: [String: String] = [:]
        // Later entries win, as with Kotlin's toMap(): aliases override locale names.
        for (name, code) in names where !name.contains(" ") { map[name] = code }
        return map
    }()

    private struct Stem { let stem: String; let full: String; let code: String }

    /// Names long enough to be declined safely ("германии" → "германи…").
    private static let stems: [Stem] = names.compactMap { (pair: (String, String)) -> Stem? in
        let (name, code) = pair
        guard !name.contains(" "), name.count >= 5,
              name.unicodeScalars.contains(where: { $0.value >= 0x430 && $0.value <= 0x44F }) else { return nil }
        return Stem(stem: String(name.dropLast()), full: name, code: code)
    }
}
