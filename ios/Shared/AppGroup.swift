import Foundation

/// The container the app and the VPN extension share: the plan for the next tunnel, the
/// last error and the settings both need. Without the App Group entitlement (an app
/// re-signed by a tool that dropped it) the app keeps its data in its own folder, and
/// the VPN cannot start anyway.
enum AppGroup {

    static var identifier: String {
        (Bundle.main.object(forInfoDictionaryKey: "VoltaAppGroup") as? String)
            .flatMap { $0.isBlank || $0.contains("$(") ? nil : $0 } ?? "group.com.ars3nb.volta"
    }

    /// The shared container, or nil when the app was signed without the group.
    static var sharedURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
    }

    static var containerURL: URL {
        if let url = sharedURL { return url }
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? URL(fileURLWithPath: NSTemporaryDirectory())
        let dir = base.appendingPathComponent("Volta", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static var defaults: UserDefaults {
        if sharedURL != nil, let shared = UserDefaults(suiteName: identifier) { return shared }
        return .standard
    }

    static var planURL: URL { containerURL.appendingPathComponent("tunnel-plan.json") }
    static var lastErrorURL: URL { containerURL.appendingPathComponent("tunnel-error.txt") }

    static func writePlan(_ plan: TunnelPlan) throws {
        let data = try JSONEncoder().encode(plan)
        try data.write(to: planURL, options: [.atomic])
    }

    static func readPlan() -> TunnelPlan? {
        guard let data = try? Data(contentsOf: planURL) else { return nil }
        return try? JSONDecoder().decode(TunnelPlan.self, from: data)
    }

    /// What went wrong in the extension, for the app to show under the disc.
    static func writeLastError(_ text: String?) {
        if let text = text, !text.isBlank {
            try? Data(text.utf8).write(to: lastErrorURL, options: [.atomic])
        } else {
            try? FileManager.default.removeItem(at: lastErrorURL)
        }
    }

    static func readLastError() -> String? {
        guard let data = try? Data(contentsOf: lastErrorURL) else { return nil }
        let text = String(decoding: data, as: UTF8.self).trimmed
        return text.isEmpty ? nil : text
    }
}

/// Messages the app sends to the running extension (NETunnelProviderSession).
enum TunnelMessage {
    static let stats = "stats"

    /// The extension's answer to `stats`.
    struct Stats: Codable {
        var uplink: Int64
        var downlink: Int64
        var engine: String
        /// Resident memory of the extension, bytes (0 when unknown).
        var memory: Int64 = 0
    }
}
