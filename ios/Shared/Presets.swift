import Foundation

/// A ready-made list of sites the user can drop into a rule set with one tap (Android's
/// Presets). Spelled out as `domain:` and CIDR entries, not geosite names: a subscription
/// may bring its own geodata, and a missing category would stop the core.
struct SitePreset: Identifiable, Hashable {
    let id: String
    /// Also the name of the rule it adds, and how the screen tells it is added: the same
    /// in every language. Brand names show as they are, the rest through `labelKey`.
    let title: String
    let subtitleKey: String
    let suggested: RouteOutbound
    let domains: [String]
    var ips: [String] = []
    var labelKey: String? = nil

    var shownTitle: String { labelKey.map { L10n.tr($0) } ?? title }

    func toRule(_ outbound: RouteOutbound? = nil) -> RoutingRule {
        RoutingRule(name: title, outbound: outbound ?? suggested, domains: domains.map { "domain:\($0)" },
                    ips: ips, source: "user")
    }
}

enum Presets {

    static let sites: [SitePreset] = [
        SitePreset(id: "youtube", title: "YouTube", subtitleKey: "routing_preset_youtube_sub", suggested: .proxy, domains: [
            "youtube.com", "ytimg.com", "ggpht.com", "googlevideo.com", "youtubekids.com",
            "youtu.be", "yt.be", "youtube-nocookie.com", "wide-youtube.l.google.com",
            "ytimg.l.google.com", "youtubei.googleapis.com",
            "youtubeembeddedplayer.googleapis.com", "youtube-ui.l.google.com",
            "yt-video-upload.l.google.com", "jnn-pa.googleapis.com",
            "returnyoutubedislikeapi.com", "yt3.googleusercontent.com"
        ]),
        SitePreset(id: "meta", title: "Instagram, WhatsApp, Facebook", subtitleKey: "routing_preset_meta_sub", suggested: .proxy, domains: [
            "instagram.com", "ig.me", "cdninstagram.com", "facebook.com", "facebook.net",
            "fb.com", "fbcdn.net", "fbsbx.com", "meta.com", "threads.net", "threads.com",
            "whatsapp.com", "whatsapp.net", "whatsapp.biz", "wa.me", "oculus.com"
        ]),
        SitePreset(id: "telegram", title: "Telegram", subtitleKey: "routing_preset_telegram_sub", suggested: .proxy, domains: [
            "telegram.org", "telegram.me", "t.me", "telegra.ph", "telegram.dog",
            "cdn-telegram.org", "telegram-cdn.org", "tdesktop.com", "telesco.pe",
            "graph.org", "fragment.com", "tg.dev", "usercontent.dev", "comments.app"
        ], ips: [
            "91.105.192.0/23", "91.108.4.0/22", "91.108.8.0/21", "91.108.16.0/21",
            "91.108.56.0/22", "95.161.64.0/20", "149.154.160.0/20", "185.76.151.0/24"
        ]),
        SitePreset(id: "discord", title: "Discord", subtitleKey: "routing_preset_discord_sub", suggested: .proxy, domains: [
            "discord.com", "discord.gg", "discord.co", "discord.media", "discord.new",
            "discord.gift", "discord.dev", "discordapp.com", "discordapp.net",
            "discordcdn.com", "dis.gd", "discordsays.com", "discord-activities.com",
            "discordactivities.com", "discord-attachments-uploads-prd.storage.googleapis.com"
        ], ips: ["66.22.192.0/18", "138.128.136.0/21"]),
        SitePreset(id: "x", title: "X (Twitter)", subtitleKey: "routing_preset_x_sub", suggested: .proxy, domains: [
            "x.com", "twitter.com", "t.co", "twimg.com", "twitter.biz", "tweetdeck.com",
            "twttr.com", "twttr.net", "twvid.com", "pscp.tv", "periscope.tv"
        ]),
        SitePreset(id: "tiktok", title: "TikTok", subtitleKey: "routing_preset_tiktok_sub", suggested: .proxy, domains: [
            "tiktok.com", "tiktokv.com", "tiktokv.us", "tiktokv.eu", "tiktokw.us",
            "tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokd.net",
            "tiktokd.org", "ttwstatic.com", "byteoversea.com", "ibytedtos.com",
            "muscdn.com", "musical.ly"
        ]),
        SitePreset(id: "ai", title: "ChatGPT, Claude, Gemini", subtitleKey: "routing_preset_ai_sub", suggested: .proxy, domains: [
            "openai.com", "chatgpt.com", "chat.com", "oaistatic.com", "oaiusercontent.com",
            "sora.com", "anthropic.com", "claude.ai", "claude.com", "gemini.google.com",
            "gemini.google", "aistudio.google.com", "ai.google.dev", "notebooklm.google.com",
            "notebooklm.google", "generativelanguage.googleapis.com", "deepmind.com",
            "deepmind.google", "labs.google", "jules.google.com"
        ]),
        SitePreset(id: "streaming", title: "Spotify и Netflix", subtitleKey: "routing_preset_streaming_sub", suggested: .proxy, domains: [
            "spotify.com", "spotifycdn.com", "scdn.co", "spoti.fi", "spotify.design",
            "netflix.com", "netflix.net", "nflxext.com", "nflximg.com", "nflximg.net",
            "nflxso.net", "nflxvideo.net"
        ], labelKey: "routing_preset_streaming"),
        SitePreset(id: "rezka", title: "HDRezka", subtitleKey: "routing_preset_rezka_sub", suggested: .proxy, domains: [
            "hdrezka.ag", "hdrezka.ac", "hdrezka.me", "hdrezka.tv", "hdrezka.app",
            "hdrezka.tech", "hd-rezka.tv", "rezka.ag", "rezka.tv", "rezka.cc", "rezka.fi",
            "voidboost.cc", "hdrzk.org"
        ]),
        SitePreset(id: "ru", title: "Российские сайты", subtitleKey: "routing_preset_ru_sub", suggested: .direct,
                   domains: ["ru", "su", "xn--p1ai"] + RoutingEngine.ruWhitelistDomains, labelKey: "routing_preset_ru")
    ]

    enum Entry: Equatable {
        case domain(String)
        case ip(String)

        var value: String {
            switch self {
            case let .domain(v), let .ip(v): return v
            }
        }
    }

    private static let ipv4 = Rx("^\\d{1,3}(\\.\\d{1,3}){3}(/\\d{1,2})?$")
    private static let ipv6 = Rx("^[0-9a-f]*:[0-9a-f]*:[0-9a-f:]*(/\\d{1,3})?$")

    /// What a typed entry means: an IP or subnet, a geo list, or a site. A pasted link is
    /// cut down to its host, and a bare host matches its subdomains too.
    static func parseEntry(_ raw: String) -> Entry? {
        var text = raw.trimmed.lowercased()
        if text.isEmpty { return nil }
        if text.hasPrefix("geoip:") { return .ip(text) }
        for p in ["geosite:", "domain:", "full:", "regexp:", "keyword:"] where text.hasPrefix(p) { return .domain(text) }
        if ipv4.matchesEntirely(text) || ipv6.matchesEntirely(text) { return .ip(text) }
        text = text.substringAfter("://", missing: text)
        text = text.substringBefore("/").substringBefore("?").substringBefore("#")
        text = text.substringAfter("@", missing: text).removingPrefix("*.").removingPrefix(".")
        text = text.substringBefore(":")
        if ipv4.matchesEntirely(text) { return .ip(text) }
        if !text.contains(".") { return nil }
        return .domain("domain:\(text)")
    }
}
