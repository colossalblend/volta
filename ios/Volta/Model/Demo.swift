import Foundation

/// Sample data for screenshots in the simulator (CI), where no VPN can run:
///
///     -volta-demo                    sample subscription and servers, no network
///     -volta-tab servers             home | servers | subscriptions | settings | import
///     -volta-state connected         connected | connecting | error | off
///     -volta-theme light             system | volta | light | ocean | ember
enum Demo {
    static var arguments: [String] { ProcessInfo.processInfo.arguments }
    static var isOn: Bool { arguments.contains("-volta-demo") }

    static func value(_ name: String) -> String? {
        guard let i = arguments.firstIndex(of: name), i + 1 < arguments.count else { return nil }
        return arguments[i + 1]
    }

    static let subscriptionId: Int64 = 1001

    static var subscriptions: [Subscription] {
        var sub = Subscription(id: subscriptionId, name: "Volta Demo", url: "https://sub.example.com/demo")
        sub.lastUpdated = Date().timeIntervalSince1970 - 600
        sub.trafficUsed = 37 * 1024 * 1024 * 1024
        sub.trafficTotal = 200 * 1024 * 1024 * 1024
        sub.expireAt = Date().timeIntervalSince1970 + 23 * 86_400
        sub.announce = "Новые серверы в Финляндии и Нидерландах"
        var second = Subscription(id: 1002, name: "Северный ветер", url: "https://sub.example.org/n", colorHex: "#4A8FC4")
        second.lastUpdated = Date().timeIntervalSince1970 - 7200
        return [sub, second]
    }

    static var servers: [Server] {
        let rows: [(String, ServerRole, Int, Int64)] = [
            ("🇩🇪 Германия · Франкфурт", .normal, 48, subscriptionId),
            ("🇩🇪 Германия · Берлин", .normal, 61, subscriptionId),
            ("🇳🇱 Нидерланды · Амстердам", .normal, 57, subscriptionId),
            ("🇫🇮 Финляндия · Хельсинки", .normal, 39, subscriptionId),
            ("🇸🇪 Швеция · Стокгольм", .normal, 112, subscriptionId),
            ("🇺🇸 США · Нью-Йорк", .normal, 168, 1002),
            ("🇷🇺 Москва · обход LTE", .bypass, 22, subscriptionId),
            ("🇷🇺 Санкт-Петербург · LTE", .bypass, 31, subscriptionId),
            ("🇰🇿 Алматы · LTE", .bypass, 74, 1002),
            ("⚡ Автовыбор", .auto, -1, subscriptionId)
        ]
        return rows.enumerated().map { (index, row) -> Server in
            var s = Server(
                id: Int64(2000 + index),
                subscriptionId: row.3,
                remark: row.0,
                proto: .vless,
                address: "node\(index).example.com",
                port: 443,
                uuid: "00000000-0000-0000-0000-00000000000\(index % 10)",
                network: "tcp",
                security: "reality",
                sni: "www.example.com",
                pbk: "demo"
            )
            s.role = row.1
            s.latencyMs = row.2
            s.ewmaMs = row.2
            s.lastPingAt = Date().timeIntervalSince1970 - 120
            s.isFavorite = index == 3
            s.sortOrder = index
            return s
        }
    }

    @MainActor
    static func apply(to model: AppModel) {
        model.subscriptions = subscriptions
        model.servers = servers
        model.settings.selectedServerId = 2003
        if let theme = value("-volta-theme").flatMap(DesignTheme.init(rawValue:)) { model.settings.theme = theme }
        model.regime = .open
        model.ipInfo = IpChecker.Info(ip: "203.0.113.24", country: Countries.byCode("FI").name, countryCode: "FI")
        model.pick = PickSummary(rows: [
            .init(name: "🇫🇮 Финляндия · Хельсинки", ms: 39, chosen: true),
            .init(name: "🇩🇪 Германия · Франкфурт", ms: 48, chosen: false),
            .init(name: "🇳🇱 Нидерланды · Амстердам", ms: 57, chosen: false)
        ], measured: 6, alive: 6, at: Date())
        switch value("-volta-state") {
        case "connected":
            model.vpnState = .connected
            model.connectedSince = Date().addingTimeInterval(-754)
            model.traffic = Traffic(uplink: 18_400_000, downlink: 412_000_000, upSpeed: 96_000, downSpeed: 2_350_000)
            model.speedHistory = (0..<60).map { (i: Int) -> SpeedPoint in
                let wave = (sin(Double(i) / 6) + 1.3) * 900_000
                return SpeedPoint(down: Int64(wave + Double((i * 7919) % 400_000)), up: Int64(wave / 18))
            }
        case "connecting":
            model.vpnState = .connecting
            model.phase = .measuring
        case "error":
            model.vpnState = .error
            model.lastError = L("vpn.error.entitlement")
        default:
            break
        }
        switch value("-volta-tab") {
        case "servers": model.tab = .servers
        case "subscriptions": model.tab = .subscriptions
        case "settings": model.tab = .settings
        case "import":
            model.importDraft = "https://sub.example.com/demo"
            model.importOpen = true
        default: model.tab = .home
        }
    }
}
