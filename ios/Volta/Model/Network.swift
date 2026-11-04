import Foundation
import Network
import UIKit

/// Downloads a subscription the way the Android app does: its User-Agent, the device
/// headers providers look at, and the provider's own headers turned into `#` lines the
/// parser already reads.
enum SubscriptionFetcher {

    struct FetchError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    static var userAgent: String { "Volta/1.0 (iOS)" }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 40
        config.requestCachePolicy = .reloadIgnoringLocalCacheData
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    static func fetch(url raw: String, userAgent custom: String = "", hwid: String?) async throws -> String {
        guard let url = URL(string: raw.trimmed) else { throw FetchError(message: L("sub.error.badLink")) }
        var request = URLRequest(url: url)
        request.setValue(custom.isBlank ? userAgent : custom.trimmed, forHTTPHeaderField: "User-Agent")
        request.setValue("iOS", forHTTPHeaderField: "x-device-os")
        request.setValue(await UIDevice.current.systemVersion, forHTTPHeaderField: "x-ver-os")
        request.setValue(deviceModel, forHTTPHeaderField: "x-device-model")
        request.setValue(appVersion, forHTTPHeaderField: "x-app-version")
        if let hwid = hwid { request.setValue(hwid, forHTTPHeaderField: "x-hwid") }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: request)
        } catch let error as URLError {
            switch error.code {
            case .notConnectedToInternet, .cannotFindHost, .dnsLookupFailed, .cannotConnectToHost, .networkConnectionLost:
                throw FetchError(message: L("sub.error.noConnection"))
            case .timedOut:
                throw FetchError(message: L("sub.error.timeout"))
            case .secureConnectionFailed, .serverCertificateUntrusted, .serverCertificateHasBadDate,
                 .serverCertificateNotYetValid, .serverCertificateHasUnknownRoot, .clientCertificateRejected:
                throw FetchError(message: L("sub.error.tls"))
            case .cancelled:
                throw CancellationError()
            default:
                throw FetchError(message: L("sub.error.download"))
            }
        }
        guard let http = response as? HTTPURLResponse else { throw FetchError(message: L("sub.error.download")) }
        guard (200..<300).contains(http.statusCode) else {
            throw FetchError(message: L("sub.error.status", http.statusCode))
        }
        let body = String(decoding: data, as: UTF8.self).removingPrefix("\u{FEFF}")
        if body.isBlank { throw FetchError(message: L("sub.error.empty")) }

        func header(_ name: String) -> String {
            for (key, value) in http.allHeaderFields {
                if let k = key as? String, k.caseInsensitiveCompare(name) == .orderedSame {
                    return (value as? String) ?? "\(value)"
                }
            }
            return ""
        }
        var meta = ""
        var title = header("profile-title")
        if title.isBlank { title = titleFromContentDisposition(header("content-disposition")) }
        if !title.isBlank { meta += "#profile-title: \(title)\n" }
        for (name, line) in [
            ("subscription-userinfo", "#subscription-userinfo"),
            ("support-url", "#support-url"),
            ("announce", "#announce"),
            ("profile-update-interval", "#profile-update-interval"),
            ("profile-web-page-url", "#profile-web-page-url"),
            // Happ-style rules: happ://routing/onadd/BASE64 (Android does the same).
            ("routing", "#routing-link")
        ] {
            let value = header(name)
            if !value.isBlank { meta += "\(line): \(value)\n" }
        }
        return meta + body
    }

    private static func titleFromContentDisposition(_ cd: String) -> String {
        guard !cd.isBlank,
              let groups = Rx("filename\\*?=(?:UTF-8'')?\"?([^\";]+)", [.caseInsensitive]).firstGroups(cd) else { return "" }
        return ConfigParser.decodeProfileTitle(groups[1].trimmed.trimmingChars("\""))
    }

    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        let mirror = Mirror(reflecting: info.machine)
        let id = mirror.children.compactMap { $0.value as? Int8 }.filter { $0 != 0 }
            .map { String(UnicodeScalar(UInt8($0))) }.joined()
        return id.isEmpty ? "iPhone" : id
    }

    static var appVersion: String {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? "1.0.0"
    }
}

/// Measures servers. Real delay goes through each server with a short-lived core
/// (libXray's pingBatch, five at a time); TCP only times the handshake.
enum Pinger {

    /// Milliseconds per server id, -1 for no answer. [progress] gets every batch as it lands.
    static func measure(_ servers: [Server], method: PingMethod, timeout: Int = 4,
                        progress: @escaping @MainActor ([Int64: Int]) -> Void) async -> [Int64: Int] {
        switch method {
        case .realDelay: return await realDelay(servers, timeout: timeout, progress: progress)
        case .tcp: return await tcp(servers, timeout: Double(timeout), progress: progress)
        }
    }

    private static func realDelay(_ servers: [Server], timeout: Int,
                                  progress: @escaping @MainActor ([Int64: Int]) -> Void) async -> [Int64: Int] {
        var results: [Int64: Int] = [:]
        var probes: [(Int64, String)] = []
        for s in servers {
            if let config = try? ConfigBuilder.buildProbe(server: s) { probes.append((s.id, config)) } else { results[s.id] = -1 }
        }
        var index = 0
        while index < probes.count {
            if Task.isCancelled { break }
            let chunk = Array(probes[index..<min(index + 5, probes.count)])
            index += 5
            let delays = await Task.detached(priority: .userInitiated) {
                XrayBridge.pingBatch(chunk.map { $0.1 }, timeoutSeconds: timeout)
            }.value
            var batch: [Int64: Int] = [:]
            for (i, item) in chunk.enumerated() { batch[item.0] = i < delays.count ? delays[i] : -1 }
            results.merge(batch) { _, new in new }
            await progress(batch)
        }
        return results
    }

    private static func tcp(_ servers: [Server], timeout: Double,
                            progress: @escaping @MainActor ([Int64: Int]) -> Void) async -> [Int64: Int] {
        var results: [Int64: Int] = [:]
        await withTaskGroup(of: (Int64, Int).self) { group in
            for s in servers {
                group.addTask { (s.id, await tcpOnce(host: s.address, port: s.port, timeout: timeout)) }
            }
            for await (id, ms) in group {
                results[id] = ms
                await progress([id: ms])
            }
        }
        return results
    }

    static func tcpOnce(host: String, port: Int, timeout: Double) async -> Int {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: max(1, port))) else { return -1 }
        return await withCheckedContinuation { (continuation: CheckedContinuation<Int, Never>) in
            let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
            let start = DispatchTime.now()
            let once = Once()
            func finish(_ value: Int) {
                guard once.claim() else { return }
                connection.stateUpdateHandler = nil
                connection.cancel()
                continuation.resume(returning: value)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    finish(max(1, Int((DispatchTime.now().uptimeNanoseconds - start.uptimeNanoseconds) / 1_000_000)))
                case .failed, .waiting, .cancelled:
                    finish(-1)
                default:
                    break
                }
            }
            connection.start(queue: .global(qos: .userInitiated))
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { finish(-1) }
        }
    }

    /// True for the first caller only.
    private final class Once {
        private let lock = NSLock()
        private var done = false
        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if done { return false }
            done = true
            return true
        }
    }
}

/// The address the internet sees right now, and its country.
enum IpChecker {
    struct Info: Equatable {
        var ip: String
        var country: String
        var countryCode: String
    }

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 6
        config.urlCache = nil
        return URLSession(configuration: config)
    }()

    static func fetch() async -> Info? {
        for endpoint in ["https://ipwho.is/", "https://api.ip.sb/geoip", "https://ipapi.co/json/"] {
            if Task.isCancelled { return nil }
            guard let url = URL(string: endpoint) else { continue }
            var request = URLRequest(url: url)
            request.setValue("Volta/1.0 (iOS)", forHTTPHeaderField: "User-Agent")
            guard let answer = try? await session.data(for: request),
                  let obj = try? JSONSerialization.jsonObject(with: answer.0) as? JSONObject else { continue }
            let ip = obj.str("ip")
            if ip.isBlank { continue }
            let code = obj.str("country_code").ifBlank(obj.str("countryCode")).uppercased()
            let name = code.count == 2 ? Countries.byCode(code).name : obj.str("country")
            return Info(ip: ip, country: name.ifBlank(obj.str("country")), countryCode: code)
        }
        return nil
    }
}
