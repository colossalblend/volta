import Foundation
import NetworkExtension

/// The VPN profile in iOS Settings and the connection behind it. One profile, «Volta»,
/// made on the first connect (iOS asks the user once), pointing at the PacketTunnel
/// extension.
@MainActor
final class VPNController {

    struct ControlError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private(set) var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?
    var onStatusChange: ((NEVPNStatus) -> Void)?

    var status: NEVPNStatus { manager?.connection.status ?? .invalid }
    var connectedDate: Date? { manager?.connection.connectedDate }

    static var tunnelBundleId: String {
        (Bundle.main.object(forInfoDictionaryKey: "VoltaTunnelBundleID") as? String)
            .flatMap { $0.isBlank || $0.contains("$(") ? nil : $0 }
            ?? "\(Bundle.main.bundleIdentifier ?? "com.ars3nb.volta").tunnel"
    }

    /// Finds the existing profile, if any, and starts following its status.
    func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        manager = managers.first { ($0.protocolConfiguration as? NETunnelProviderProtocol)?.providerBundleIdentifier == Self.tunnelBundleId }
            ?? managers.first
        observe()
        onStatusChange?(status)
    }

    /// The profile, made or repaired when needed. Making it shows iOS's "add VPN
    /// configuration" prompt.
    func prepare(keepConnected: Bool) async throws -> NETunnelProviderManager {
        let m = manager ?? NETunnelProviderManager()
        let proto = (m.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        var changed = manager == nil
        if proto.providerBundleIdentifier != Self.tunnelBundleId {
            proto.providerBundleIdentifier = Self.tunnelBundleId
            changed = true
        }
        if proto.serverAddress != "Volta" {
            proto.serverAddress = "Volta"
            changed = true
        }
        if m.protocolConfiguration == nil || changed {
            m.protocolConfiguration = proto
        }
        if m.localizedDescription != "Volta" {
            m.localizedDescription = "Volta"
            changed = true
        }
        if !m.isEnabled {
            m.isEnabled = true
            changed = true
        }
        let rules: [NEOnDemandRule] = keepConnected ? [NEOnDemandRuleConnect()] : []
        if m.isOnDemandEnabled != keepConnected {
            m.onDemandRules = rules
            m.isOnDemandEnabled = keepConnected
            changed = true
        }
        if changed {
            do {
                try await m.saveToPreferences()
                try await m.loadFromPreferences()
            } catch {
                throw ControlError(message: Self.explain(error))
            }
        }
        manager = m
        observe()
        return m
    }

    func start(keepConnected: Bool) async throws {
        let m = try await prepare(keepConnected: keepConnected)
        do {
            try m.connection.startVPNTunnel()
        } catch {
            throw ControlError(message: Self.explain(error))
        }
    }

    func stop() async {
        guard let m = manager else { return }
        // With "keep connected" iOS would bring the tunnel straight back.
        if m.isOnDemandEnabled {
            m.isOnDemandEnabled = false
            try? await m.saveToPreferences()
        }
        m.connection.stopVPNTunnel()
    }

    /// Turns "keep connected" on or off for the existing profile.
    func setKeepConnected(_ on: Bool) async {
        guard let m = manager else { return }
        m.onDemandRules = on ? [NEOnDemandRuleConnect()] : []
        m.isOnDemandEnabled = on
        try? await m.saveToPreferences()
    }

    /// Why the last connection ended, from the extension (iOS 16+).
    func lastError() async -> String? {
        if let text = AppGroup.readLastError() { return text }
        guard let connection = manager?.connection else { return nil }
        return await withCheckedContinuation { (continuation: CheckedContinuation<String?, Never>) in
            connection.fetchLastDisconnectError { error in
                continuation.resume(returning: error.map { Self.explain($0) })
            }
        }
    }

    /// Traffic counters from the running extension.
    func stats() async -> TunnelMessage.Stats? {
        guard let session = manager?.connection as? NETunnelProviderSession, status == .connected else { return nil }
        return await withCheckedContinuation { (continuation: CheckedContinuation<TunnelMessage.Stats?, Never>) in
            do {
                try session.sendProviderMessage(Data(TunnelMessage.stats.utf8)) { data in
                    let stats = data.flatMap { try? JSONDecoder().decode(TunnelMessage.Stats.self, from: $0) }
                    continuation.resume(returning: stats)
                }
            } catch {
                continuation.resume(returning: nil)
            }
        }
    }

    private func observe() {
        if let old = observer { NotificationCenter.default.removeObserver(old) }
        guard let connection = manager?.connection else { return }
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: connection, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                guard let self = self else { return }
                self.onStatusChange?(self.status)
            }
        }
    }

    /// iOS's errors in words a person can act on.
    nonisolated static func explain(_ error: Error) -> String {
        let ns = error as NSError
        if ns.domain == NEVPNErrorDomain, let code = NEVPNError.Code(rawValue: ns.code) {
            switch code {
            case .configurationInvalid, .configurationDisabled:
                return L("vpn.error.config")
            case .connectionFailed:
                return L("vpn.error.failed")
            case .configurationStale:
                return L("vpn.error.stale")
            case .configurationReadWriteFailed:
                return L("vpn.error.permission")
            case .configurationUnknown:
                return L("vpn.error.entitlement")
            @unknown default:
                break
            }
        }
        let text = ns.localizedDescription
        if text.localizedCaseInsensitiveContains("permission") || text.localizedCaseInsensitiveContains("entitlement") {
            return L("vpn.error.entitlement")
        }
        return text
    }
}
