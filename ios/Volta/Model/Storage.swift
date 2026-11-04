import CryptoKit
import Foundation
import Security

enum PingMethod: String, Codable, CaseIterable {
    /// Real delay through the server, measured by the core. Recommended.
    case realDelay
    /// A plain TCP handshake with the server's address: quick, but a server can answer it
    /// and still not work.
    case tcp
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
    var russiaDirect = false
    var engine: TunnelEngine = .hev
    var sendHwid = true
    var keepConnected = false
    var hideBalancers = true
    /// The server that last carried traffic in each group (BalancerScope raw value).
    var remembered: [String: Int64] = [:]
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
    }
}

/// Subscriptions and servers as JSON files in the shared container, settings in its
/// defaults. Small enough to write whole on every change.
enum Store {
    private static var subscriptionsURL: URL { AppGroup.containerURL.appendingPathComponent("subscriptions.json") }
    private static var serversURL: URL { AppGroup.containerURL.appendingPathComponent("servers.json") }
    private static let settingsKey = "volta.settings"

    static func loadSubscriptions() -> [Subscription] { load(subscriptionsURL) ?? [] }
    static func loadServers() -> [Server] { load(serversURL) ?? [] }

    static func save(subscriptions: [Subscription]) { write(subscriptions, to: subscriptionsURL) }
    static func save(servers: [Server]) { write(servers, to: serversURL) }

    static func loadSettings() -> AppSettings {
        guard let data = AppGroup.defaults.data(forKey: settingsKey),
              let settings = try? JSONDecoder().decode(AppSettings.self, from: data) else { return AppSettings() }
        return settings
    }

    static func save(settings: AppSettings) {
        if let data = try? JSONEncoder().encode(settings) { AppGroup.defaults.set(data, forKey: settingsKey) }
    }

    private static func load<T: Decodable>(_ url: URL) -> T? {
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(T.self, from: data)
    }

    private static func write<T: Encodable>(_ value: T, to url: URL) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: url, options: [.atomic])
    }
}

/// A device code for providers that limit devices per subscription (x-hwid). Random,
/// kept in the keychain so it survives reinstalling, hashed with the app's salt as on
/// Android.
enum Hwid {
    private static let service = "com.ars3nb.volta.hwid"
    private static let account = "device"

    static let value: String = {
        let raw = readRaw() ?? {
            let fresh = UUID().uuidString
            storeRaw(fresh)
            return fresh
        }()
        let digest = SHA256.hash(data: Data("volta-hwid-v1:\(raw)".utf8))
        return digest.map { String(format: "%02x", $0) }.joined().take(32)
    }()

    private static func readRaw() -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess, let data = item as? Data {
            return String(data: data, encoding: .utf8)
        }
        return UserDefaults.standard.string(forKey: service)
    }

    private static func storeRaw(_ raw: String) {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(raw.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        if SecItemAdd(attributes as CFDictionary, nil) != errSecSuccess {
            UserDefaults.standard.set(raw, forKey: service)
        }
    }
}
