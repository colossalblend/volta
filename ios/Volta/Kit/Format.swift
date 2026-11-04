import Foundation

/// Numbers and names as the screens show them, in the app's language.
enum Format {

    static func latency(_ ms: Int) -> String { ms < 0 ? "—" : L("unit.ms", ms) }

    static func bytes(_ value: Int64) -> String {
        let b = Double(abs(value))
        let kb = 1024.0, mb = kb * 1024, gb = mb * 1024, tb = gb * 1024
        if b >= tb { return L("unit.tb", String(format: "%.2f", b / tb)) }
        if b >= gb { return L("unit.gb", String(format: "%.1f", b / gb)) }
        if b >= mb { return L("unit.mb", String(format: "%.0f", b / mb)) }
        if b >= kb { return L("unit.kb", String(format: "%.0f", b / kb)) }
        return L("unit.b", String(Int(b)))
    }

    static func speed(_ perSecond: Int64) -> String {
        let v = Double(max(0, perSecond))
        if v >= 1024 * 1024 { return L("unit.mbps", String(format: "%.1f", v / (1024 * 1024))) }
        if v >= 1024 { return L("unit.kbps", String(format: "%.0f", v / 1024)) }
        return L("unit.bps", String(Int(v)))
    }

    static func duration(since: Date?, now: Date) -> String {
        guard let since = since else { return "00:00" }
        let total = max(0, Int(now.timeIntervalSince(since)))
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }

    static func groupName(_ role: ServerRole) -> String {
        switch role {
        case .normal: return "Home"
        case .bypass: return "LTE"
        case .auto: return L("group.balancer")
        }
    }

    static func modeShort(_ mode: ConnectMode) -> String {
        switch mode {
        case .autoHomeLte: return "Home+LTE"
        case .autoHome: return "Home"
        case .autoLte: return "LTE"
        case .manual: return L("mode.manual")
        }
    }

    /// "осталось 5 дн.", "истекает сегодня".
    static func expiry(_ at: Double) -> String {
        guard at > 0 else { return "" }
        let days = Int(floor((at - Date().timeIntervalSince1970) / 86_400))
        if days < 0 { return L("expiry.expired") }
        if days == 0 { return L("expiry.today") }
        return L10n.plural("expiry.daysLeft", days)
    }

    static func daysLeft(_ at: Double) -> Int { Int(floor((at - Date().timeIntervalSince1970) / 86_400)) }

    /// "обновлена 5 минут назад".
    static func updated(_ at: Double) -> String {
        guard at > 0 else { return L("sub.neverUpdated") }
        let formatter = RelativeDateTimeFormatter()
        formatter.locale = Locale(identifier: L10n.language)
        formatter.unitsStyle = .full
        let when = formatter.localizedString(for: Date(timeIntervalSince1970: at), relativeTo: Date())
        return L("sub.updated", when)
    }

    static func countryName(_ country: Countries.Country) -> String {
        country.code == Countries.unknownCode ? L("country.other") : country.name
    }
}
