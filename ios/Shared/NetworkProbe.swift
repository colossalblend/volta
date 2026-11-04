import Foundation

/// What the local network allows, as on Android: Home sites answering means the open
/// internet works (Home servers); only LTE sites answering means a mobile whitelist
/// (LTE servers); nothing means no network. Only meaningful while the VPN is off, since
/// on iOS the app's own traffic goes through the tunnel.
enum NetworkProbe {
    static let homeHosts = ["google.com", "ars3nb.ru", "calculator888.ru"]
    static let lteHosts = ["vk.ru", "ya.ru", "appbank.pw"]

    private static let session: URLSession = {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 4
        config.timeoutIntervalForResource = 5
        config.urlCache = nil
        config.waitsForConnectivity = false
        return URLSession(configuration: config)
    }()

    static func run() async -> NetworkRegime {
        await withTaskGroup(of: (Bool, Bool).self, returning: NetworkRegime.self) { group in
            for host in homeHosts { group.addTask { (true, await reachable(host)) } }
            for host in lteHosts { group.addTask { (false, await reachable(host)) } }
            var lte = false
            for await (isHome, ok) in group {
                if ok && isHome {
                    group.cancelAll()
                    return .open
                }
                if ok { lte = true }
            }
            return Balancer.detectRegime(homeReachable: false, lteReachable: lte)
        }
    }

    private static func reachable(_ host: String) async -> Bool {
        guard let url = URL(string: "https://\(host)/") else { return false }
        var request = URLRequest(url: url)
        request.httpMethod = "HEAD"
        do {
            let (_, response) = try await session.data(for: request)
            return response is HTTPURLResponse
        } catch {
            return false
        }
    }
}
