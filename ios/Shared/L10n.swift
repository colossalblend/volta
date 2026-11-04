import Foundation

/// Localized text by key, from Localizable.strings (ru is the source language; see
/// tools/strings.py). Shared code uses it too, so error messages from the parser and the
/// config builder come out in the app's language.
enum L10n {
    static func tr(_ key: String, _ args: CVarArg...) -> String { tr(key, args: args) }

    static func tr(_ key: String, args: [CVarArg]) -> String {
        let format = NSLocalizedString(key, tableName: nil, bundle: .main, value: key, comment: "")
        if args.isEmpty { return format }
        return String(format: format, locale: Locale.current, arguments: args)
    }

    /// The app's language as iOS picked it (the per-app setting included).
    static var language: String { Bundle.main.preferredLocalizations.first ?? "ru" }

    /// Plural category for [n] in the current language: one, few, many or other.
    static func pluralCategory(_ n: Int, language: String = L10n.language) -> String {
        let lang = String(language.prefix(2))
        let mod10 = n % 10
        let mod100 = n % 100
        switch lang {
        case "ru", "uk":
            if mod10 == 1 && mod100 != 11 { return "one" }
            if (2...4).contains(mod10) && !(12...14).contains(mod100) { return "few" }
            return "many"
        case "en", "de", "es", "kk", "uz", "tr", "fa":
            return n == 1 ? "one" : "other"
        default:
            return "other"
        }
    }

    /// `key.one` / `key.few` / `key.many` / `key.other` with the number put in.
    static func plural(_ key: String, _ n: Int) -> String {
        let category = pluralCategory(n)
        var format = NSLocalizedString("\(key).\(category)", tableName: nil, bundle: .main, value: "", comment: "")
        if format.isEmpty || format == "\(key).\(category)" {
            format = NSLocalizedString("\(key).other", tableName: nil, bundle: .main, value: "%d", comment: "")
        }
        return String(format: format, locale: Locale.current, n)
    }
}
