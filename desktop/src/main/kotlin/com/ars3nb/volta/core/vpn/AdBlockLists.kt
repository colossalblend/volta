package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.data.model.AdBlockMode

/**
 * Volta's own tracker list, on top of the bundled `geosite:category-ads-all`.
 *
 * Why it exists: the geosite list (v2fly's) names the hosts the tracking code actually
 * talks to (mc.yandex.ru, report.appmetrica.yandex.net, notify.bugsnag.com,
 * analytics.tiktok.com…), and AdGuard DNS is careful not to block anything a site or
 * an app needs. Neither has the neighbouring hosts of the same services: their consoles,
 * secondary collectors, script CDNs (metrika.yandex.ru, ads.vk.com, browser.sentry-cdn.com,
 * o0.ingest.sentry.io, us.i.posthog.com, ads-api.tiktok.com…). Ad-block test sites probe
 * exactly those, and some of them do carry tracking. Checked against the geosite.dat in
 * app/libs/libv2ray.aar: only entries the bundled list lacks are here.
 *
 * Every entry goes into the same routing rule as the geosite list (one rule, one domain
 * matcher in the core), so it is blocked by the site name sniffing reads out of the
 * connection even when the address came from somewhere other than the core's DNS. The
 * core's DNS answers these names with 0.0.0.0 as well, so an app fails at once instead
 * of trying, and a browser cannot reuse an open HTTP/2 connection to a sibling host
 * for them (which is how a blocked name can slip past an SNI-only rule).
 *
 * `domain:` matches the name and everything under it; `full:` the name alone.
 */
object AdBlockLists {

    /** Ads, analytics, error and session trackers: safe to block. Standard and up. */
    val STANDARD: List<String> = listOf(
        // Yandex: Metrica, AppMetrica and the ad cabinet (the beacons are in geosite).
        "domain:metrika.yandex.ru",
        "domain:appmetrica.yandex.ru",
        "domain:advertising.yandex.ru",
        "domain:appmetrica.yandex.com",
        // VK ads (VK itself stays: see [NEVER_BLOCK] about vk.com/rtrg).
        "domain:ads.vk.com",
        // Error and session recording.
        "domain:ingest.sentry.io",
        "domain:ingest.us.sentry.io",
        "domain:ingest.de.sentry.io",
        "domain:sentry-cdn.com",
        "domain:app.getsentry.com",
        "domain:api.bugsnag.com",
        "domain:app.bugsnag.com",
        "domain:lr-ingest.com",
        "domain:lr-ingest.io",
        // Product analytics. posthog.com itself (site, docs) stays reachable.
        "domain:i.posthog.com",
        "domain:app.posthog.com",
        "domain:eu.posthog.com",
        "domain:us.posthog.com",
        "domain:rudderstack.com",
        "domain:snowplowanalytics.com",
        // Browser fingerprinting.
        "domain:fingerprintjs.com",
        // TikTok ads (the app's own hosts are untouched).
        "domain:ads.tiktok.com",
        "domain:ads-api.tiktok.com",
        "domain:ads-sg.tiktok.com",
        "domain:ads-us.tiktok.com",
        "domain:business-api.tiktok.com",
        "domain:pangleglobal.com",
        // X / Twitter ads and analytics.
        "domain:analytics.twitter.com",
        "domain:analytics.x.com",
        "domain:ads.x.com",
        // Social pixels. Their logins and embeds stay: see [NEVER_BLOCK].
        "domain:snap.licdn.com",
        "domain:ct.pinterest.com",
        "domain:pixel.quora.com",
        "domain:qevents.quora.com",
        "domain:px.srvcs.tumblr.com",
        "domain:tr.facebook.com",
        // Amazon ads and logging (shopping itself is untouched).
        "domain:mads-eu.amazon.com",
        "domain:mads-fe.amazon.com",
        "domain:advertising-api.amazon.com",
        "domain:advertising-api-eu.amazon.com",
        "domain:advertising-api-fe.amazon.com",
        "domain:fls-na.amazon.com",
        "domain:fls-eu.amazon.com",
        "domain:fls-fe.amazon.com",
        // Microsoft / Bing ads.
        "domain:ads.microsoft.com",
        "domain:c.bing.com",
        // Yahoo ads and analytics.
        "domain:udc.yahoo.com",
        "domain:udcm.yahoo.com",
        "domain:advertising.yahoo.com",
        "domain:log.fc.yahoo.com",
        "domain:gemini.yahoo.com",
        "domain:adtech.yahooinc.com",
        // Phone makers' ad services.
        "domain:ads.huawei.com",
        "domain:ngfts.lge.com",
        "domain:advertising.apple.com",
        // Ad networks and affiliate trackers (CJ: tkqlhce, zenaps).
        "domain:ironsource.mobi",
        "domain:tkqlhce.com",
        "domain:zenaps.com"
    )

    /**
     * Strict only: cookie and consent banners, personalisation and A/B testing, and
     * telemetry some services lean on. Blocking these is harmless for most sites, but a
     * site may wait for a consent box that never comes, show a page without its
     * personal parts, or not record what was watched — hence a mode of its own.
     */
    val STRICT: List<String> = listOf(
        // Consent banners (CMPs): OneTrust, TrustArc, Didomi, Sourcepoint, Osano,
        // Cookiebot, Usercentrics, Google's Funding Choices.
        "domain:onetrust.com",
        "domain:cookielaw.org",
        "domain:consent.trustarc.com",
        "domain:consent-pref.trustarc.com",
        "domain:consent.truste.com",
        "domain:privacy-center.org",
        "domain:privacy-mgmt.com",
        "domain:cmp.osano.com",
        "domain:consent.cookiebot.com",
        "domain:consentcdn.cookiebot.com",
        "domain:app.usercentrics.eu",
        "domain:fundingchoicesmessages.google.com",
        // Personalisation and A/B testing. LaunchDarkly: only the live stream; the
        // first fetch of an app's feature flags (clientsdk, app) stays.
        "domain:dynamicyield.com",
        "domain:clientstream.launchdarkly.com",
        // Apple's App Store / Music usage metrics.
        "full:xp.apple.com",
        // YouTube watch statistics; without them watch history may not fill in.
        "full:s.youtube.com"
    )

    /**
     * Hosts ad-block test sites count as trackers that Volta leaves alone on purpose,
     * because blocking them breaks things people use. No list above may match these
     * (ConfigBuilderTest checks it); the routing screen says the tests count them anyway.
     *
     * - graph.instagram.com, i.instagram.com: the Instagram app's own API — the feed,
     *   stories and messages stop loading.
     * - graph.facebook.com: the Facebook/Messenger/Instagram apps' API, and every
     *   "Log in with Facebook" button in other apps.
     * - connect.facebook.net: the script behind "Log in with Facebook" on websites
     *   (and comments/like plug-ins).
     * - redirector.googlevideo.com: YouTube asks it which server holds the video;
     *   without it playback fails.
     * - bnc.lt: Branch deep links — links in mail and messages that should open an app
     *   lead nowhere.
     * - click.mailchimp.com: the links inside newsletters and receipts are rewritten to
     *   it; blocking it breaks every link in such mail.
     * - widget.intercom.io: the support chat on many sites and apps.
     * - widgets.pinterest.com: Pin It buttons and embedded boards.
     * - tagmanager.google.com: the Tag Manager admin interface used by site owners, not
     *   the tag script (that is googletagmanager.com, already in geosite).
     *
     * vk.com/rtrg (the VK retargeting pixel) is a path on vk.com: over HTTPS the core
     * sees only the host name, so it cannot be blocked without blocking all of VK.
     */
    val NEVER_BLOCK: List<String> = listOf(
        "graph.instagram.com",
        "i.instagram.com",
        "graph.facebook.com",
        "connect.facebook.net",
        "redirector.googlevideo.com",
        "bnc.lt",
        "click.mailchimp.com",
        "widget.intercom.io",
        "widgets.pinterest.com",
        "tagmanager.google.com",
        "vk.com"
    )

    /**
     * Volta's own entries for [mode], in addition to the geosite list. Used as routing
     * domains and as DNS-hosts keys alike: Xray reads `domain:` / `full:` the same way
     * in both.
     */
    fun domainsFor(mode: AdBlockMode): List<String> = when (mode) {
        AdBlockMode.OFF -> emptyList()
        AdBlockMode.STANDARD, AdBlockMode.FAMILY -> STANDARD
        AdBlockMode.STRICT -> STANDARD + STRICT
    }
}
