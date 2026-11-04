package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingRuleDto
import java.util.UUID

/**
 * A ready-made list of sites the user can drop into a rule set with one tap.
 *
 * Everything here is spelled out as `domain:` and CIDR entries instead of geosite and
 * geoip names: a subscription may bring its own geodata files, and a category missing
 * from them would stop the core from starting.
 */
data class SitePreset(
    val id: String,
    val title: String,
    val subtitle: String,
    /** Where the preset points by default; the user can change it before adding. */
    val suggested: RouteOutbound,
    val domains: List<String>,
    val ips: List<String> = emptyList()
) {
    fun toRule(outbound: RouteOutbound = suggested) = RoutingRuleDto(
        id = UUID.randomUUID().toString(),
        name = title,
        outbound = outbound,
        domains = domains.map { "domain:$it" },
        ips = ips,
        source = "user"
    )
}

object Presets {

    // Service domains and Telegram's subnets follow itdoginfo/allow-domains, the lists
    // Russian routers use for the same job; the rest are the services' own domains.
    val sites: List<SitePreset> = listOf(
        SitePreset(
            id = "youtube",
            title = "YouTube",
            subtitle = "Видео, Shorts, YouTube Music",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "youtube.com", "ytimg.com", "ggpht.com", "googlevideo.com", "youtubekids.com",
                "youtu.be", "yt.be", "youtube-nocookie.com", "wide-youtube.l.google.com",
                "ytimg.l.google.com", "youtubei.googleapis.com",
                "youtubeembeddedplayer.googleapis.com", "youtube-ui.l.google.com",
                "yt-video-upload.l.google.com", "jnn-pa.googleapis.com",
                "returnyoutubedislikeapi.com", "yt3.googleusercontent.com"
            )
        ),
        SitePreset(
            id = "meta",
            title = "Instagram, WhatsApp, Facebook",
            subtitle = "Всё от Meta, включая Threads",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "instagram.com", "ig.me", "cdninstagram.com", "facebook.com", "facebook.net",
                "fb.com", "fbcdn.net", "fbsbx.com", "meta.com", "threads.net", "threads.com",
                "whatsapp.com", "whatsapp.net", "whatsapp.biz", "wa.me", "oculus.com"
            )
        ),
        SitePreset(
            id = "telegram",
            title = "Telegram",
            subtitle = "Сайты и сети серверов Telegram",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "telegram.org", "telegram.me", "t.me", "telegra.ph", "telegram.dog",
                "cdn-telegram.org", "telegram-cdn.org", "tdesktop.com", "telesco.pe",
                "graph.org", "fragment.com", "tg.dev", "usercontent.dev", "comments.app"
            ),
            ips = listOf(
                "91.105.192.0/23", "91.108.4.0/22", "91.108.8.0/21", "91.108.16.0/21",
                "91.108.56.0/22", "95.161.64.0/20", "149.154.160.0/20", "185.76.151.0/24"
            )
        ),
        SitePreset(
            id = "discord",
            title = "Discord",
            subtitle = "Чаты и голосовые каналы",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "discord.com", "discord.gg", "discord.co", "discord.media", "discord.new",
                "discord.gift", "discord.dev", "discordapp.com", "discordapp.net",
                "discordcdn.com", "dis.gd", "discordsays.com", "discord-activities.com",
                "discordactivities.com", "discord-attachments-uploads-prd.storage.googleapis.com"
            ),
            ips = listOf("66.22.192.0/18", "138.128.136.0/21")
        ),
        SitePreset(
            id = "x",
            title = "X (Twitter)",
            subtitle = "Лента, картинки и видео",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "x.com", "twitter.com", "t.co", "twimg.com", "twitter.biz", "tweetdeck.com",
                "twttr.com", "twttr.net", "twvid.com", "pscp.tv", "periscope.tv"
            )
        ),
        SitePreset(
            id = "tiktok",
            title = "TikTok",
            subtitle = "Приложение и видео",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "tiktok.com", "tiktokv.com", "tiktokv.us", "tiktokv.eu", "tiktokw.us",
                "tiktokcdn.com", "tiktokcdn-us.com", "tiktokcdn-eu.com", "tiktokd.net",
                "tiktokd.org", "ttwstatic.com", "byteoversea.com", "ibytedtos.com",
                "muscdn.com", "musical.ly"
            )
        ),
        SitePreset(
            id = "ai",
            title = "ChatGPT, Claude, Gemini",
            subtitle = "Нейросети, закрытые для России",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "openai.com", "chatgpt.com", "chat.com", "oaistatic.com", "oaiusercontent.com",
                "sora.com", "anthropic.com", "claude.ai", "claude.com", "gemini.google.com",
                "gemini.google", "aistudio.google.com", "ai.google.dev", "notebooklm.google.com",
                "notebooklm.google", "generativelanguage.googleapis.com", "deepmind.com",
                "deepmind.google", "labs.google", "jules.google.com"
            )
        ),
        SitePreset(
            id = "streaming",
            title = "Spotify и Netflix",
            subtitle = "Музыка и сериалы",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "spotify.com", "spotifycdn.com", "scdn.co", "spoti.fi", "spotify.design",
                "netflix.com", "netflix.net", "nflxext.com", "nflximg.com", "nflximg.net",
                "nflxso.net", "nflxvideo.net"
            )
        ),
        SitePreset(
            id = "rezka",
            title = "HDRezka",
            subtitle = "Онлайн-кинотеатр и его зеркала",
            suggested = RouteOutbound.PROXY,
            domains = listOf(
                "hdrezka.ag", "hdrezka.ac", "hdrezka.me", "hdrezka.tv", "hdrezka.app",
                "hdrezka.tech", "hd-rezka.tv", "rezka.ag", "rezka.tv", "rezka.cc", "rezka.fi",
                "voidboost.cc", "hdrzk.org"
            )
        ),
        SitePreset(
            id = "ru",
            title = "Российские сайты",
            subtitle = "Всё в зонах .ru, .su, .рф и крупные сервисы",
            suggested = RouteOutbound.DIRECT,
            domains = listOf("ru", "su", "xn--p1ai") + RoutingEngine.ruWhitelistDomains()
        )
    )

    /**
     * What a typed entry means: an IP or subnet, a geo list, or a site. A pasted link is
     * cut down to its host, and a bare host matches its subdomains too.
     */
    fun parseEntry(raw: String): Entry? {
        var text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        if (text.startsWith("geoip:")) return Entry.Ip(text)
        if (text.startsWith("geosite:") || text.startsWith("domain:") ||
            text.startsWith("full:") || text.startsWith("regexp:") || text.startsWith("keyword:")
        ) return Entry.Domain(text)
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}(/\d{1,2})?$""")
        val ipv6 = Regex("""^[0-9a-f]*:[0-9a-f]*:[0-9a-f:]*(/\d{1,3})?$""")
        if (ipv4.matches(text) || ipv6.matches(text)) return Entry.Ip(text)
        text = text.substringAfter("://").substringBefore('/').substringBefore('?')
            .substringBefore('#').substringAfter('@').removePrefix("*.").removePrefix(".")
        text = text.substringBefore(':')
        if (ipv4.matches(text)) return Entry.Ip(text)
        if (!text.contains('.')) return null
        return Entry.Domain("domain:$text")
    }

    sealed interface Entry {
        val value: String

        data class Domain(override val value: String) : Entry
        data class Ip(override val value: String) : Entry
    }
}
