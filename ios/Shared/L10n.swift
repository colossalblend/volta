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

    /// A plural text with the number put in. The String Catalog holds every language's
    /// forms (one/few/many/other, Arabic's zero/two too); iOS picks the right one.
    static func plural(_ key: String, _ n: Int) -> String {
        let format = NSLocalizedString(key, tableName: nil, bundle: .main, value: "%ld", comment: "")
        if format == key { return String(n) }
        return String.localizedStringWithFormat(format, n)
    }
}
