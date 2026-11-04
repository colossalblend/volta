import Foundation

/// The ranking half of the Android AutoBalancer: which servers a group holds, how a set
/// of ping samples becomes one comparable figure, and when a challenger is clearly
/// enough faster to replace the server in use. Measuring is the app's job.
enum Balancer {

    /// A challenger must beat the current server by at least this much…
    static let switchMarginMs = 30
    /// …and by this ratio, so 500 → 460 ms does not cause a reconnect.
    static let switchMarginRatio = 0.85
    static let ewmaAlpha = 0.4
    /// The remembered server is taken without measuring unless it failed this often.
    static let instantMaxFails = 2
    static let lossPenaltyMs = 1_200
    static let failStreakPenaltyMs = 40

    struct Quality: Equatable {
        var medianMs: Int
        var jitterMs: Int
        var loss: Double
    }

    struct Ranked {
        var server: Server
        var latencyMs: Int
        var ok: Bool
        var jitterMs: Int
        var figureMs: Int

        var score: Int {
            if !ok { return Int.max / 4 }
            if figureMs >= 0 { return figureMs - (server.isFavorite ? 40 : 0) }
            return Int.max / 8
        }
    }

    /// Median, spread and loss of a server's samples (-1 = no answer).
    static func quality(_ samples: [Int]) -> Quality {
        let good = samples.filter { $0 > 0 }.sorted()
        if good.isEmpty { return Quality(medianMs: -1, jitterMs: 0, loss: 1) }
        let mid = good.count / 2
        let median = good.count % 2 == 1 ? good[mid] : (good[mid - 1] + good[mid]) / 2
        return Quality(
            medianMs: median,
            jitterMs: good[good.count - 1] - good[0],
            loss: 1 - Double(good.count) / Double(samples.count)
        )
    }

    /// One figure per server: the median, nudged by a quarter of the spread, by lost
    /// samples and by recent failures. -1 when it never answered.
    static func effectiveMs(_ q: Quality, failStreak: Int) -> Int {
        if q.medianMs < 0 { return -1 }
        return q.medianMs + q.jitterMs / 4 + Int(q.loss * Double(lossPenaltyMs)) +
            min(max(failStreak, 0), 3) * failStreakPenaltyMs
    }

    static func blendEwma(previous: Int, sample: Int) -> Int {
        if sample < 0 { return previous }
        if previous < 0 { return sample }
        return Int(Double(previous) * (1 - ewmaAlpha) + Double(sample) * ewmaAlpha)
    }

    static func detectRegime(homeReachable: Bool, lteReachable: Bool) -> NetworkRegime {
        if homeReachable { return .open }
        if lteReachable { return .whitelistOnly }
        return .blocked
    }

    /// Servers a group picks from. Provider balancers are in none of them.
    static func pool(_ servers: [Server], _ scope: BalancerScope) -> [Server] {
        let dialable = servers.filter { $0.proto != .openflux }
        switch scope {
        case .all: return dialable.filter { $0.role != .auto }
        case .normal: return dialable.filter { $0.role == .normal }
        case .bypass: return dialable.filter { $0.role == .bypass }
        }
    }

    static func filterByRegime(_ servers: [Server], _ regime: NetworkRegime) -> [Server] {
        let all = pool(servers, .all)
        switch regime {
        case .open:
            let home = pool(servers, .normal)
            return home.isEmpty ? all : home
        case .whitelistOnly:
            let lte = pool(servers, .bypass)
            return lte.isEmpty ? all : lte
        case .blocked, .unknown:
            return all
        }
    }

    /// Which group a run serves, to know whose remembered server to favour.
    static func groupOf(_ scope: BalancerScope, _ regime: NetworkRegime) -> BalancerScope? {
        switch scope {
        case .all:
            switch regime {
            case .open: return .normal
            case .whitelistOnly: return .bypass
            default: return nil
            }
        default:
            return scope
        }
    }

    static func hasLte(_ servers: [Server]) -> Bool { servers.contains { $0.role == .bypass } }

    /// Hysteresis: a challenger replaces the holder only when it is both absolutely and
    /// relatively faster in this very run.
    static func beats(_ challenger: Ranked, _ holder: Ranked) -> Bool {
        let c = challenger.figureMs
        let h = holder.figureMs
        if c < 0 { return false }
        if h < 0 { return true }
        return (h - c) >= switchMarginMs && Double(c) <= Double(h) * switchMarginRatio
    }

    /// Ranks [pool] by its [samples] and picks: the best, unless the holder (the server in
    /// use, or remembered for this group) still answers and is not clearly beaten.
    static func choose(pool: [Server], samples: [Int64: [Int]], holder: Server?) -> (selected: Server?, ranked: [Ranked]) {
        let ranked = pool.compactMap { server -> Ranked? in
            guard let list = samples[server.id] else { return nil }
            let q = quality(list)
            let figure = effectiveMs(q, failStreak: server.failStreak)
            return Ranked(server: server, latencyMs: figure >= 0 ? q.medianMs : -1, ok: figure >= 0,
                          jitterMs: q.jitterMs, figureMs: figure)
        }.sorted { $0.score < $1.score }

        let best = ranked.first { $0.ok }
        let holderRank = holder.flatMap { h in ranked.first { $0.server.id == h.id } }
        let selected: Server?
        if best == nil {
            selected = holder ?? pool.min { $0.score < $1.score }
        } else if let h = holderRank, h.ok, let b = best {
            selected = (b.server.id == h.server.id || !beats(b, h)) ? h.server : b.server
        } else {
            selected = best?.server
        }
        return (selected, ranked)
    }
}
