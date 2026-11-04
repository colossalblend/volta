import Foundation
import LibXray

/// The one door into Xray-core: libXray's `Invoke(requestJSON) -> responseJSON`
/// (API version 3), built by gomobile into LibXray.xcframework.
///
/// The app uses it to ping servers (each ping batch runs a temporary core, so only while
/// no tunnel core runs in the same process; the tunnel lives in the extension, another
/// process). The extension uses it to run and stop the tunnel's core.
enum XrayBridge {

    struct Response {
        var ok: Bool
        var data: Any?
        var error: String
    }

    static func invoke(_ method: String, _ payload: JSONObject = [:]) -> Response {
        let request: JSONObject = ["apiVersion": 3, "method": method, "payload": payload]
        let raw: String? = LibXrayInvoke(JSON.string(request))
        guard let text = raw, let obj = JSON.object(text) else {
            return Response(ok: false, data: nil, error: "LibXray: no answer")
        }
        let ok = (obj["success"] as? Bool) ?? false
        return Response(ok: ok, data: obj["data"], error: obj.str("error"))
    }

    // MARK: - Tunnel core (extension)

    static func run(config: String) -> Response { invoke("runXray", ["xrayJson": config]) }

    @discardableResult
    static func stop() -> Response { invoke("stopXray") }

    static var isRunning: Bool {
        ((invoke("getXrayState").data as? JSONObject)?["running"] as? Bool) ?? false
    }

    /// Checks that the core would load [config], without starting it.
    static func test(config: String) -> Response { invoke("testXray", ["xrayJson": config]) }

    static var version: String {
        (invoke("xrayVersion").data as? JSONObject)?.str("version") ?? ""
    }

    /// [count] free local TCP ports, or nothing when the call fails.
    static func freePorts(_ count: Int) -> [Int] {
        let ports = (invoke("getFreePorts", ["count": count]).data as? JSONObject)?["ports"] as? [Any] ?? []
        return ports.compactMap { ($0 as? NSNumber)?.intValue }
    }

    // MARK: - Pings (app)

    /// Real delay through each server, at most five per call (libXray's limit). One
    /// result per config, in order: milliseconds, or -1 when the server did not answer.
    static func pingBatch(_ configs: [String], timeoutSeconds: Int = 5, url: String = ConfigBuilder.probeURL) -> [Int] {
        precondition(configs.count <= 5)
        if configs.isEmpty { return [] }
        let response = invoke("pingBatch", [
            "configs": configs.map { ["xrayJson": $0] },
            "timeout": timeoutSeconds,
            "url": url
        ])
        guard response.ok,
              let results = (response.data as? JSONObject)?["results"] as? [Any],
              results.count == configs.count else {
            return Array(repeating: -1, count: configs.count)
        }
        return results.map { item in
            guard let r = item as? JSONObject, (r["success"] as? Bool) == true else { return -1 }
            let delay = r.int("delay", -1)
            // libXray reports 10000 for an error and 11000 for a timeout.
            return delay >= 0 && delay < 10_000 ? max(1, delay) : -1
        }
    }
}
