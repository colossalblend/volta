import Foundation

/// The colour themes, as on Android. `dynamic` takes iOS's own system colours and accent
/// (Android's Material You takes the wallpaper's; iOS gives apps no wallpaper colours).
enum DesignTheme: String, CaseIterable, Codable {
    case system, volta, light, ocean, ember, dynamic

    /// The name Android stores (`design_theme`).
    var androidName: String { rawValue.uppercased() }

    init?(androidName: String) {
        guard let t = DesignTheme(rawValue: androidName.lowercased()) else { return nil }
        self = t
    }
}

enum PingMethod: String, Codable, CaseIterable {
    /// Real delay through the server, measured by the core. Recommended.
    case realDelay
    /// A plain TCP handshake with the server's address: quick, but a server can answer it
    /// and still not work.
    case tcp

    var androidName: String { self == .tcp ? "TCP" : "REAL_DELAY" }
}

/// A setting value as a backup file carries it.
enum SettingValue: Codable, Equatable {
    case bool(Bool), int(Int), string(String), list([String])

    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if let b = try? c.decode(Bool.self) { self = .bool(b) }
        else if let i = try? c.decode(Int.self) { self = .int(i) }
        else if let s = try? c.decode(String.self) { self = .string(s) }
        else { self = .list((try? c.decode([String].self)) ?? []) }
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case let .bool(b): try c.encode(b)
        case let .int(i): try c.encode(i)
        case let .string(s): try c.encode(s)
        case let .list(l): try c.encode(l)
        }
    }

    var any: Any {
        switch self {
        case let .bool(b): return b
        case let .int(i): return i
        case let .string(s): return s
        case let .list(l): return l
        }
    }

    init?(any: Any?) {
        switch any {
        case let n as NSNumber where CFGetTypeID(n) == CFBooleanGetTypeID(): self = .bool(n.boolValue)
        case let n as NSNumber: self = .int(n.intValue)
        case let s as String: self = .string(s)
        case let l as [String]: self = .list(l)
        case let l as [Any]: self = .list(l.compactMap { $0 as? String })
        default: return nil
        }
    }
}

/// Everything the user can switch, kept in the shared container's defaults.
struct AppSettings: Codable, Equatable {
    var theme: DesignTheme = .volta
    var mode: ConnectMode = .autoHomeLte
    var selectedServerId: Int64?
    var refreshOnLaunch = true
    var probeOnLaunch = true
    var pingMethod: PingMethod = .realDelay
    var localDns = true
    /// Old switch «Российские сайты напрямую»; now the built-in rule set of that name.
    var russiaDirect = false
    var engine: TunnelEngine = .hev
    var sendHwid = true
    var keepConnected = false
    var hideBalancers = true
    /// The server that last carried traffic in each group (BalancerScope raw value).
    var remembered: [String: Int64] = [:]
    var adBlock: AdBlockMode = .off
    var blockTorrents = false
    /// Wi-Fi ↔ mobile data: the server is checked and changed by the guard.
    var followNetwork = true
    /// Now and then compare the group and move to a clearly faster server.
    var autoRecheck = true
    var mtu = 1500
    /// «Проверка обновлений» and «Бета-версии».
    var updateAutoCheck = true
    var updateBeta = false
    var updateLastCheck: Double = 0
    /// Android settings with no iOS counterpart, kept so a backup made here still carries
    /// them back to an Android phone.
    var foreign: [String: SettingValue] = [:]
}

extension AppSettings {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        theme = c.value(.theme, .volta)
        mode = c.value(.mode, .autoHomeLte)
        selectedServerId = (try? c.decodeIfPresent(Int64.self, forKey: .selectedServerId)) ?? nil
        refreshOnLaunch = c.value(.refreshOnLaunch, true)
        probeOnLaunch = c.value(.probeOnLaunch, true)
        pingMethod = c.value(.pingMethod, .realDelay)
        localDns = c.value(.localDns, true)
        russiaDirect = c.value(.russiaDirect, false)
        engine = c.value(.engine, .hev)
        sendHwid = c.value(.sendHwid, true)
        keepConnected = c.value(.keepConnected, false)
        hideBalancers = c.value(.hideBalancers, true)
        remembered = c.value(.remembered, [:])
        adBlock = c.value(.adBlock, .off)
        blockTorrents = c.value(.blockTorrents, false)
        followNetwork = c.value(.followNetwork, true)
        autoRecheck = c.value(.autoRecheck, true)
        mtu = c.value(.mtu, 1500)
        updateAutoCheck = c.value(.updateAutoCheck, true)
        updateBeta = c.value(.updateBeta, false)
        updateLastCheck = c.value(.updateLastCheck, 0)
        foreign = c.value(.foreign, [:])
    }

    // MARK: - Backups (Android's stored names)

    /// The settings as Android's AppPreferences.backupSettings writes them.
    func backupValues() -> [String: SettingValue] {
        var out = foreign
        out["design_theme"] = .string(theme.androidName)
        out["connect_mode"] = .string(mode.rawValue)
        out["default_ping_type"] = .string(pingMethod.androidName)
        out["ad_block"] = .string(adBlock.rawValue)
        out["local_dns"] = .bool(localDns)
        out["block_torrents"] = .bool(blockTorrents)
        out["send_hwid"] = .bool(sendHwid)
        out["refresh_on_launch"] = .bool(refreshOnLaunch)
        out["ping_on_start"] = .bool(probeOnLaunch)
        out["follow_network"] = .bool(followNetwork)
        out["auto_recheck"] = .bool(autoRecheck)
        out["hide_balancers"] = .bool(hideBalancers)
        if mtu != 1500 { out["mtu"] = .int(mtu) }
        return out
    }

    static let androidOnlyKeys: Set<String> = [
        "auto_failover", "battery_saver", "auto_connect", "simple_mode", "per_app_mode", "per_app_packages"
    ]

    /// Puts back what a backup holds. Unknown names and values of the wrong kind are
    /// skipped. Returns how many settings were applied.
    mutating func restore(_ values: [String: SettingValue]) -> Int {
        var applied = 0
        func bool(_ key: String, _ set: (Bool) -> Void) {
            if case let .bool(b)? = values[key] { set(b); applied += 1 }
        }
        func string(_ key: String, _ set: (String) -> Bool) {
            if case let .string(s)? = values[key], !s.isBlank, set(s) { applied += 1 }
        }
        string("design_theme") { s in DesignTheme(androidName: s).map { theme = $0 } != nil }
        string("connect_mode") { s in ConnectMode(rawValue: s).map { mode = $0 } != nil }
        string("default_ping_type") { s in pingMethod = s == "TCP" ? .tcp : .realDelay; return true }
        string("ad_block") { s in AdBlockMode(rawValue: s).map { adBlock = $0 } != nil }
        bool("local_dns") { localDns = $0 }
        bool("block_torrents") { blockTorrents = $0 }
        bool("send_hwid") { sendHwid = $0 }
        bool("refresh_on_launch") { refreshOnLaunch = $0 }
        bool("ping_on_start") { probeOnLaunch = $0 }
        bool("follow_network") { followNetwork = $0 }
        bool("auto_recheck") { autoRecheck = $0 }
        bool("hide_balancers") { hideBalancers = $0 }
        if case let .int(m)? = values["mtu"] { mtu = min(9000, max(1280, m)); applied += 1 }
        for key in Self.androidOnlyKeys {
            if let v = values[key] { foreign[key] = v; applied += 1 }
        }
        return applied
    }
}
