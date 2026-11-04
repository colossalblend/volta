import Foundation

/// Role of a server for the app's own auto pick.
enum ServerRole: String, Codable, CaseIterable {
    /// Regular foreign exit, for an open connection ("Home").
    case normal = "NORMAL"
    /// Whitelist-bypass node, for a mobile network behind a whitelist ("LTE").
    case bypass = "BYPASS_WHITELIST"
    /// A provider-side balancer («Авто», «Балансер»…). Never part of Home or LTE: Volta
    /// does the balancing itself.
    case auto = "AUTO"
}

enum ServerPool: String, CaseIterable {
    case all, normal, bypass, auto

    func matches(_ role: ServerRole) -> Bool {
        switch self {
        case .all: return true
        case .normal: return role == .normal
        case .bypass: return role == .bypass
        case .auto: return role == .auto
        }
    }
}

/// What a server speaks. Xray-core dials everything up to `hysteria2`; the rest are
/// recognised so they can be named, but this iOS build has no helper to run them.
enum ProtocolType: String, Codable, CaseIterable {
    case vless = "VLESS", vmess = "VMESS", trojan = "TROJAN", shadowsocks = "SHADOWSOCKS"
    case socks = "SOCKS", http = "HTTP", wireguard = "WIREGUARD", hysteria2 = "HYSTERIA2"
    case amneziawg = "AMNEZIAWG", hysteria = "HYSTERIA", openflux = "OPENFLUX"
    case rawJson = "RAW_JSON", unknown = "UNKNOWN"

    var label: String {
        switch self {
        case .vless: return "VLESS"
        case .vmess: return "VMess"
        case .trojan: return "Trojan"
        case .shadowsocks: return "Shadowsocks"
        case .socks: return "SOCKS5"
        case .http: return "HTTP"
        case .wireguard: return "WireGuard"
        case .hysteria2: return "Hysteria2"
        case .amneziawg: return "AmneziaWG"
        case .hysteria: return "Hysteria"
        case .openflux: return "OpenFlux"
        case .rawJson: return "JSON"
        case .unknown: return "—"
        }
    }
}

/// Which servers an auto pick considers.
enum BalancerScope: String, Codable, CaseIterable {
    /// Check the network first, then the group that suits it.
    case all
    /// Home only.
    case normal
    /// LTE only.
    case bypass
}

/// What the power disc does: three auto groups or the server picked by hand.
enum ConnectMode: String, Codable, CaseIterable {
    case autoHomeLte = "AUTO_HOME_LTE"
    case autoHome = "AUTO_HOME"
    case autoLte = "AUTO_LTE"
    case manual = "MANUAL"

    var scope: BalancerScope? {
        switch self {
        case .autoHomeLte: return .all
        case .autoHome: return .normal
        case .autoLte: return .bypass
        case .manual: return nil
        }
    }

    static let autoModes: [ConnectMode] = [.autoHomeLte, .autoHome, .autoLte]
}

/// What the local network allows, from the site check.
enum NetworkRegime: String, Codable {
    /// Foreign sites answer: Home servers.
    case open
    /// Only whitelisted Russian sites answer: LTE servers.
    case whitelistOnly
    /// Nothing answers.
    case blocked
    case unknown
}

/// How the packets get from the VPN interface into Xray.
enum TunnelEngine: String, Codable, CaseIterable {
    /// hev-socks5-tunnel into Xray's SOCKS port, as on Android.
    case hev
    /// Xray-core's own TUN inbound on the extension's interface.
    case xray
}

/// A subscription: a provider's link and what it last said about itself.
struct Subscription: Codable, Identifiable, Hashable {
    var id: Int64
    var name: String
    var nameLocked: Bool = false
    var url: String
    /// Seconds since 1970; 0 = never.
    var lastUpdated: Double = 0
    var userAgent: String = ""
    var updateIntervalHours: Int = 12
    var isEnabled: Bool = true
    var sortOrder: Int = 0
    /// Seconds since 1970; 0 = no expiry given.
    var expireAt: Double = 0
    var trafficUsed: Int64 = 0
    var trafficTotal: Int64 = 0
    var supportUrl: String = ""
    var announce: String = ""
    var webPageUrl: String = ""
    var colorHex: String = "#C4784A"
    /// The provider keeps the link private: never shown, copied or edited.
    var urlHidden: Bool = false
    /// `server-type` markup from the import link (see ServerMarkup).
    var serverTypes: String = ""

    static let colors = ["#C4784A", "#4A8FC4", "#5FB07A", "#B05FA8", "#C4A24A", "#4AC4B8"]
}

extension Subscription {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.value(.id, 0)
        name = c.value(.name, "")
        nameLocked = c.value(.nameLocked, false)
        url = c.value(.url, "")
        lastUpdated = c.value(.lastUpdated, 0)
        userAgent = c.value(.userAgent, "")
        updateIntervalHours = c.value(.updateIntervalHours, 12)
        isEnabled = c.value(.isEnabled, true)
        sortOrder = c.value(.sortOrder, 0)
        expireAt = c.value(.expireAt, 0)
        trafficUsed = c.value(.trafficUsed, 0)
        trafficTotal = c.value(.trafficTotal, 0)
        supportUrl = c.value(.supportUrl, "")
        announce = c.value(.announce, "")
        webPageUrl = c.value(.webPageUrl, "")
        colorHex = c.value(.colorHex, "#C4784A")
        urlHidden = c.value(.urlHidden, false)
        serverTypes = c.value(.serverTypes, "")
    }
}

/// One server, from a link, a subscription or a JSON config.
struct Server: Codable, Identifiable, Hashable {
    var id: Int64 = 0
    var subscriptionId: Int64? = nil
    var remark: String
    var proto: ProtocolType = .vless
    var address: String
    var port: Int
    /// UUID, password or key, depending on the protocol.
    var uuid: String = ""
    var flow: String = ""
    var encryption: String = "none"
    var network: String = "tcp"
    var security: String = "none"
    var sni: String = ""
    var fp: String = ""
    var pbk: String = ""
    var sid: String = ""
    var spx: String = ""
    var alpn: String = ""
    var path: String = ""
    var host: String = ""
    var serviceName: String = ""
    var headerType: String = ""
    var rawLink: String = ""
    /// The provider's whole outbound, when imported from JSON.
    var rawJson: String = ""
    /// Every other parameter the link carried (mode, extra, obfs, mport, wg keys…).
    var extras: [String: String] = [:]
    var latencyMs: Int = -1
    /// Smoothed latency for ranking; -1 = never measured.
    var ewmaMs: Int = -1
    var failStreak: Int = 0
    var lastPingAt: Double = 0
    var isFavorite: Bool = false
    var role: ServerRole = .normal
    var roleManual: Bool = false
    var sortOrder: Int = 0
    var tags: String = ""
    /// The provider said which group it is in; the name check never overrides that.
    var roleFromProvider: Bool = false

    /// Lower is better; unmeasured and flaky nodes sink.
    var score: Int {
        let base: Int
        if ewmaMs >= 0 { base = ewmaMs } else if latencyMs >= 0 { base = latencyMs } else { return Int.max / 4 }
        return base + failStreak * 1_000 - (isFavorite ? 60 : 0)
    }

    var isHealthy: Bool { failStreak == 0 && (ewmaMs >= 0 || latencyMs >= 0) }

    /// The last measurement, falling back to the smoothed one; -1 when never measured.
    var shownMs: Int { latencyMs >= 0 ? latencyMs : ewmaMs }

    func extra(_ keys: String...) -> String { Server.extra(extras, keys) }

    static func extra(_ extras: [String: String], _ keys: [String]) -> String {
        for k in keys {
            if let hit = extras.first(where: { $0.key.caseInsensitiveCompare(k) == .orderedSame }),
               !hit.value.isBlank {
                return hit.value
            }
        }
        return ""
    }

    static func newId() -> Int64 { Int64.random(in: 1...Int64(Int32.max) * 1_000_000) }
}

extension Server {
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.value(.id, 0)
        subscriptionId = (try? c.decodeIfPresent(Int64.self, forKey: .subscriptionId)) ?? nil
        remark = c.value(.remark, "")
        proto = c.value(.proto, .unknown)
        address = c.value(.address, "")
        port = c.value(.port, 443)
        uuid = c.value(.uuid, "")
        flow = c.value(.flow, "")
        encryption = c.value(.encryption, "none")
        network = c.value(.network, "tcp")
        security = c.value(.security, "none")
        sni = c.value(.sni, "")
        fp = c.value(.fp, "")
        pbk = c.value(.pbk, "")
        sid = c.value(.sid, "")
        spx = c.value(.spx, "")
        alpn = c.value(.alpn, "")
        path = c.value(.path, "")
        host = c.value(.host, "")
        serviceName = c.value(.serviceName, "")
        headerType = c.value(.headerType, "")
        rawLink = c.value(.rawLink, "")
        rawJson = c.value(.rawJson, "")
        extras = c.value(.extras, [:])
        latencyMs = c.value(.latencyMs, -1)
        ewmaMs = c.value(.ewmaMs, -1)
        failStreak = c.value(.failStreak, 0)
        lastPingAt = c.value(.lastPingAt, 0)
        isFavorite = c.value(.isFavorite, false)
        role = c.value(.role, .normal)
        roleManual = c.value(.roleManual, false)
        sortOrder = c.value(.sortOrder, 0)
        tags = c.value(.tags, "")
        roleFromProvider = c.value(.roleFromProvider, false)
    }
}

extension KeyedDecodingContainer {
    /// A field that may be missing or of an older shape: then the default.
    func value<T: Decodable>(_ key: Key, _ fallback: T) -> T {
        ((try? decodeIfPresent(T.self, forKey: key)) ?? nil) ?? fallback
    }
}

/// What a subscription body (or a pasted text) turned into.
struct ImportResult {
    var servers: [Server]
    var title: String = ""
    var expireAt: Double = 0
    var trafficUsed: Int64 = 0
    var trafficTotal: Int64 = 0
    var announce: String = ""
    var supportUrl: String = ""
    var webPageUrl: String = ""
    var updateIntervalHours: Int = 12
    /// Protocols recognised but not dialable in this build (TUIC, AmneziaWG…).
    var skipped: [String] = []
}

/// Everything the VPN extension needs to start: written by the app into the shared
/// container, read by the extension (also when iOS starts it on its own).
struct TunnelPlan: Codable {
    var xrayConfig: String
    var engine: TunnelEngine
    var serverName: String
    var serverId: Int64
    /// Server addresses that skip the tunnel, so pings from the app measure the server
    /// itself and not the server through the server.
    var excludedAddresses: [String] = []
    var mtu: Int = 1500
    /// DNS servers given to iOS; their queries go into the tunnel.
    var dnsServers: [String] = ["1.1.1.1", "8.8.8.8"]
    var ipv6: Bool = true
    var createdAt: Double = Date().timeIntervalSince1970
}
