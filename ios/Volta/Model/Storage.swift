import CryptoKit
import Foundation
import Security

/// Subscriptions and servers as JSON files in the shared container, settings in its
/// defaults. Small enough to write whole on every change.
enum Store {
    private static var subscriptionsURL: URL { AppGroup.containerURL.appendingPathComponent("subscriptions.json") }
    private static var serversURL: URL { AppGroup.containerURL.appendingPathComponent("servers.json") }
    private static let settingsKey = "volta.settings"

    private static var routingURL: URL { AppGroup.containerURL.appendingPathComponent("routing.json") }

    static func loadProfiles() -> [RoutingProfile]? { load(routingURL) }
    static func save(profiles: [RoutingProfile]) { write(profiles, to: routingURL) }

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
