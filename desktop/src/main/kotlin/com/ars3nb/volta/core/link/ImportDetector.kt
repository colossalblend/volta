package com.ars3nb.volta.core.link

import com.ars3nb.volta.core.parser.AmneziaConfigs
import com.ars3nb.volta.core.parser.ConfigParser

/**
 * What the user pasted into the "Добавить" field, decided as they type, so the screen
 * can say it back ("Подписка", "Сервер VLESS", "Конфиг WireGuard") and name the button
 * after what will actually happen. Cheap string checks only: it runs on every keystroke.
 */
object ImportDetector {

    sealed interface Kind {
        /** Goes to addSubscription; [hidden] when the link itself hides the address. */
        val isSubscription: Boolean get() = false

        data object Empty : Kind
        data class Subscription(val hidden: Boolean) : Kind { override val isSubscription get() = true }
        data object Encrypted : Kind { override val isSubscription get() = true }
        data class Server(val protocol: String) : Kind
        data class Servers(val count: Int) : Kind
        data object AmneziaKey : Kind
        data object Conf : Kind
        data object Json : Kind
        data object Control : Kind
        data object Unknown : Kind
    }

    fun detect(raw: String): Kind {
        val t = raw.trim()
        if (t.isEmpty()) return Kind.Empty
        if (t.startsWith("volta://", ignoreCase = true)) {
            return when (val a = VoltaLinks.parse(t)) {
                is VoltaLinks.AddSubscription -> Kind.Subscription(a.hidden)
                is VoltaLinks.AddEncrypted -> Kind.Encrypted
                is VoltaLinks.ImportContent -> detect(a.value).takeUnless { it == Kind.Empty } ?: Kind.Unknown
                is VoltaLinks.Connect, VoltaLinks.Disconnect, VoltaLinks.Toggle -> Kind.Control
                is VoltaLinks.Invalid -> Kind.Unknown
            }
        }
        if (t.startsWith("https://", true) || t.startsWith("http://", true)) {
            // A single address, not a pasted page of links.
            return if (t.none { it.isWhitespace() }) Kind.Subscription(hidden = false) else Kind.Unknown
        }
        if (AmneziaConfigs.looksLikeConf(t)) return Kind.Conf
        if (t.startsWith("vpn://", true)) return Kind.AmneziaKey
        if (t.startsWith("{") || t.startsWith("[")) return Kind.Json
        val links = ConfigParser.extractShareLinks(t)
        return when {
            links.size > 1 -> Kind.Servers(links.size)
            links.size == 1 -> Kind.Server(protocolOf(links[0]))
            // A base64 subscription body pasted as text.
            t.length > 64 && t.none { it.isWhitespace() } &&
                ConfigParser.decodeBase64(t)?.contains("://") == true -> Kind.Servers(
                ConfigParser.extractShareLinks(ConfigParser.decodeBase64(t).orEmpty()).size.coerceAtLeast(1)
            )
            else -> Kind.Unknown
        }
    }

    private fun protocolOf(link: String): String = when (link.substringBefore("://").lowercase()) {
        "vless" -> "VLESS"
        "vmess" -> "VMess"
        "trojan" -> "Trojan"
        "ss" -> "Shadowsocks"
        "socks", "socks5" -> "SOCKS5"
        "hysteria2", "hy2" -> "Hysteria2"
        "hysteria" -> "Hysteria"
        "wireguard", "wg" -> "WireGuard"
        "openflux" -> "OpenFlux"
        else -> link.substringBefore("://").uppercase()
    }
}
