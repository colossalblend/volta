import Foundation

/// Updates for the iPhone build. iOS lets no app replace itself, so Volta only finds out:
/// it reads `ios-update.json`, which CI publishes next to the IPA, and offers the release
/// page. Installing goes through whatever signed the copy (AltStore, Sideloadly, TestFlight).
///
/// The same channels as Android: the latest published release through GitHub's plain link
/// (`releases/latest/download/…`: never a pre-release, no key, no API limit), and with
/// «Бета-версии» also the main branch's rolling test build, whichever is newer.
enum Updates {

    static let repo = "colossalblend/volta"
    static let manifestName = "ios-update.json"
    static let betaTag = "apk-main"

    struct Manifest: Codable, Equatable {
        var versionCode: Int
        var versionName: String
        var tag: String = ""
        var commit: String = ""
        var branch: String = ""
        var xray: String = ""
        var ipa: String = ""
        var signed: Bool = false

        init(versionCode: Int, versionName: String, tag: String = "", ipa: String = "") {
            self.versionCode = versionCode
            self.versionName = versionName
            self.tag = tag
            self.ipa = ipa
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            versionCode = try c.decode(Int.self, forKey: .versionCode)
            versionName = c.value(.versionName, "")
            tag = c.value(.tag, "")
            commit = c.value(.commit, "")
            branch = c.value(.branch, "")
            xray = c.value(.xray, "")
            ipa = c.value(.ipa, "")
            signed = c.value(.signed, false)
        }

        /// The release page: what the user opens to download the new IPA.
        var pageURL: URL {
            URL(string: tag.isBlank ? "https://github.com/\(repo)/releases/latest"
                : "https://github.com/\(repo)/releases/tag/\(tag)")!
        }

        var ipaURL: URL? {
            guard !ipa.isBlank else { return nil }
            return URL(string: tag.isBlank ? "https://github.com/\(repo)/releases/latest/download/\(ipa)"
                       : "https://github.com/\(repo)/releases/download/\(tag)/\(ipa)")
        }
    }

    static var latestURL: URL { URL(string: "https://github.com/\(repo)/releases/latest/download/\(manifestName)")! }
    static var betaURL: URL { URL(string: "https://github.com/\(repo)/releases/download/\(betaTag)/\(manifestName)")! }

    static func parse(_ data: Data) -> Manifest? { try? JSONDecoder().decode(Manifest.self, from: data) }

    /// The newer of the two channels, if it is newer than [current].
    static func newest(stable: Manifest?, beta: Manifest?, current: Int) -> Manifest? {
        let best = [stable, beta].compactMap { $0 }.max { $0.versionCode < $1.versionCode }
        guard let found = best, found.versionCode > current else { return nil }
        return found
    }

    /// On opening the app: at most every half hour.
    static let launchEvery: TimeInterval = 30 * 60
}
