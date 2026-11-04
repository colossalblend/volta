import Foundation

/// Traffic through the VPN per calendar day, kept in the shared container: the extension
/// adds to it while the tunnel runs (the app may be asleep), the app shows the card.
struct TrafficDays: Codable, Equatable {
    /// Day number (days since 1970 on the phone's calendar) → bytes up and down.
    var days: [Int: [Int64]] = [:]

    mutating func add(day: Int, up: Int64, down: Int64) {
        let u = max(0, up), d = max(0, down)
        if u == 0 && d == 0 { return }
        let old = days[day] ?? [0, 0]
        days[day] = [old[0] + u, (old.count > 1 ? old[1] : 0) + d]
    }

    func total(_ day: Int) -> Int64 { (days[day] ?? []).reduce(0, +) }

    /// Keeps the last [keep] days: enough for all of last month, whatever today is.
    mutating func prune(today: Int, keep: Int = TrafficTally.windowDays) {
        days = days.filter { $0.key > today - keep && $0.key <= today + 1 }
    }
}

/// What the traffic card shows (Android's TrafficSummary).
struct TrafficSummary: Equatable {
    var today: Int64 = 0
    var thisMonth: Int64 = 0
    var lastMonth: Int64 = 0
    /// Bytes per day for the last two weeks, oldest first, today last.
    var recent: [Int64] = []
    /// 1…12
    var thisMonthOf: Int = 1
    var lastMonthOf: Int = 12

    var isEmpty: Bool { thisMonth <= 0 && lastMonth <= 0 && recent.allSatisfy { $0 <= 0 } }
}

enum TrafficTally {
    static let recentDays = 14
    static let windowDays = 70

    static func day(of date: Date, calendar: Calendar = .current) -> Int {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        let midnight = utc.date(from: DateComponents(year: c.year, month: c.month, day: c.day)) ?? date
        return Int(floor(midnight.timeIntervalSince1970 / 86_400))
    }

    static func components(ofDay day: Int) -> DateComponents {
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(identifier: "UTC")!
        return utc.dateComponents([.year, .month, .day], from: Date(timeIntervalSince1970: Double(day) * 86_400))
    }

    static func summarize(_ stored: TrafficDays, today: Int) -> TrafficSummary {
        let t = components(ofDay: today)
        let year = t.year ?? 1970, month = t.month ?? 1
        let lastYear = month == 1 ? year - 1 : year
        let lastMonth = month == 1 ? 12 : month - 1
        var summary = TrafficSummary(thisMonthOf: month, lastMonthOf: lastMonth)
        for (day, _) in stored.days where day <= today {
            // A day after today means the clock was set back; it belongs to no total.
            let bytes = max(0, stored.total(day))
            let c = components(ofDay: day)
            if c.year == year && c.month == month {
                summary.thisMonth += bytes
            } else if c.year == lastYear && c.month == lastMonth {
                summary.lastMonth += bytes
            }
        }
        summary.today = max(0, stored.total(today))
        summary.recent = (0..<recentDays).reversed().map { max(0, stored.total(today - $0)) }
        return summary
    }
}

/// Is there internet under the tunnel at all? From the phone's own network state, before
/// the disc is tapped (Android's InternetCheck; iOS gives no signal bars or radio type to
/// apps, so the weak-mobile warning only comes from a path iOS itself calls constrained).
enum InternetCheck {
    enum Status: Equatable {
        case ok, airplane, noNetwork, weakMobile

        var blocksConnect: Bool { self == .airplane || self == .noNetwork }
    }

    struct Signals {
        var satisfied: Bool
        var hasNonCellular: Bool
        var hasCellular: Bool
        /// iOS reports no route because the radio is off (airplane mode, cellular off).
        var radioOff: Bool = false
        /// Low Data Mode or a link iOS itself considers poor.
        var constrainedCellular: Bool = false
    }

    static func decide(_ s: Signals) -> Status {
        if s.satisfied && s.hasNonCellular { return .ok }
        if !s.satisfied || (!s.hasCellular && !s.hasNonCellular) { return s.radioOff ? .airplane : .noNetwork }
        if s.hasCellular && s.constrainedCellular { return .weakMobile }
        return .ok
    }
}
