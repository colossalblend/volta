import Foundation

/// Ad blocking, in two layers as on Android: AdGuard DNS answers every lookup the core
/// handles, and the core's own list blocks connections to ad hosts by name (sniffed from
/// the connection), for apps that find addresses elsewhere. Stored by these names.
enum AdBlockMode: String, Codable, CaseIterable {
    case off = "OFF"
    /// Ads, trackers and phishing.
    case standard = "STANDARD"
    /// The same plus adult sites (also blocked by name) and safe search (DNS only).
    case family = "FAMILY"
    /// Standard's AdGuard DNS plus Volta's strict list: consent banners, personalisation, A/B tests.
    case strict = "STRICT"

    var dohUrl: String {
        switch self {
        case .off: return ""
        case .standard, .strict: return "https://dns.adguard-dns.com/dns-query"
        case .family: return "https://family.adguard-dns.com/dns-query"
        }
    }
}

/// Volta's own tracker list on top of geosite:category-ads-all, the same as Android's
/// AdBlockLists: the neighbouring hosts the geosite list and AdGuard leave alone.
/// `domain:` matches the name and everything under it; `full:` the name alone.
enum AdBlockLists {

    static let standard: [String] = [
        "domain:metrika.yandex.ru", "domain:appmetrica.yandex.ru", "domain:advertising.yandex.ru",
        "domain:appmetrica.yandex.com",
        "domain:ads.vk.com",
        "domain:ingest.sentry.io", "domain:ingest.us.sentry.io", "domain:ingest.de.sentry.io",
        "domain:sentry-cdn.com", "domain:app.getsentry.com", "domain:api.bugsnag.com", "domain:app.bugsnag.com",
        "domain:lr-ingest.com", "domain:lr-ingest.io",
        "domain:i.posthog.com", "domain:app.posthog.com", "domain:eu.posthog.com", "domain:us.posthog.com",
        "domain:rudderstack.com", "domain:snowplowanalytics.com",
        "domain:fingerprintjs.com",
        "domain:ads.tiktok.com", "domain:ads-api.tiktok.com", "domain:ads-sg.tiktok.com", "domain:ads-us.tiktok.com",
        "domain:business-api.tiktok.com", "domain:pangleglobal.com",
        "domain:analytics.twitter.com", "domain:analytics.x.com", "domain:ads.x.com",
        "domain:snap.licdn.com", "domain:ct.pinterest.com", "domain:pixel.quora.com", "domain:qevents.quora.com",
        "domain:px.srvcs.tumblr.com", "domain:tr.facebook.com",
        "domain:mads-eu.amazon.com", "domain:mads-fe.amazon.com", "domain:advertising-api.amazon.com",
        "domain:advertising-api-eu.amazon.com", "domain:advertising-api-fe.amazon.com",
        "domain:fls-na.amazon.com", "domain:fls-eu.amazon.com", "domain:fls-fe.amazon.com",
        "domain:ads.microsoft.com", "domain:c.bing.com",
        "domain:udc.yahoo.com", "domain:udcm.yahoo.com", "domain:advertising.yahoo.com", "domain:log.fc.yahoo.com",
        "domain:gemini.yahoo.com", "domain:adtech.yahooinc.com",
        "domain:ads.huawei.com", "domain:ngfts.lge.com", "domain:advertising.apple.com",
        "domain:ironsource.mobi", "domain:tkqlhce.com", "domain:zenaps.com"
    ]

    static let strict: [String] = [
        "domain:onetrust.com", "domain:cookielaw.org", "domain:consent.trustarc.com",
        "domain:consent-pref.trustarc.com", "domain:consent.truste.com", "domain:privacy-center.org",
        "domain:privacy-mgmt.com", "domain:cmp.osano.com", "domain:consent.cookiebot.com",
        "domain:consentcdn.cookiebot.com", "domain:app.usercentrics.eu", "domain:fundingchoicesmessages.google.com",
        "domain:dynamicyield.com", "domain:clientstream.launchdarkly.com",
        "full:xp.apple.com",
        "full:s.youtube.com"
    ]

    /// Hosts test sites count as trackers that Volta leaves alone on purpose: blocking them
    /// breaks logins, the Instagram and Facebook apps, YouTube playback, links in mail and
    /// support chats. No list above may match these (tests check it).
    static let neverBlock: [String] = [
        "graph.instagram.com", "i.instagram.com", "graph.facebook.com", "connect.facebook.net",
        "redirector.googlevideo.com", "bnc.lt", "click.mailchimp.com", "widget.intercom.io",
        "widgets.pinterest.com", "tagmanager.google.com", "vk.com"
    ]

    static func domains(for mode: AdBlockMode) -> [String] {
        switch mode {
        case .off: return []
        case .standard, .family: return standard
        case .strict: return standard + strict
        }
    }

    /// True when [host] would be caught by one of [entries] (`domain:` and `full:` only).
    static func matches(_ host: String, _ entries: [String]) -> Bool {
        let h = host.lowercased()
        return entries.contains { e in
            if e.hasPrefix("full:") { return h == String(e.dropFirst(5)) }
            if e.hasPrefix("domain:") {
                let d = String(e.dropFirst(7))
                return h == d || h.hasSuffix("." + d)
            }
            return false
        }
    }
}
