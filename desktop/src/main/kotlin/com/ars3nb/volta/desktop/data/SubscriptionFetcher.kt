package com.ars3nb.volta.desktop.data

import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.desktop.i18n.I18n
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Downloads a subscription the way the Android app does: the same User-Agent shape and
 * x-* device headers, and the provider's response headers (profile-title,
 * subscription-userinfo, routing, support-url, announce, profile-update-interval,
 * profile-web-page-url, content-disposition) folded into the body as `#key: value` lines,
 * which ConfigParser reads exactly as it reads them from the body itself.
 */
object SubscriptionFetcher {

    data class Fetched(val body: String, val routingHeader: String = "")

    class FetchException(message: String) : Exception(message)

    const val DEFAULT_USER_AGENT = "Volta/1.0 (Windows)"

    fun normalizeUrl(url: String): String {
        var value = url.trim().removePrefix("﻿")
        if (value.startsWith("<") && value.endsWith(">") && value.length > 2) {
            value = value.substring(1, value.lastIndex).trim()
        }
        return value
    }

    /** Pasted content rather than an address: returned as it is. */
    private fun isInlineContent(url: String): Boolean =
        url.startsWith("data:") ||
            url.contains(Regex("(?i)(vless|vmess|trojan|ss|socks5?|hysteria2|hy2|wireguard)://")) ||
            url.startsWith("{") || url.startsWith("[") || url.contains("\n")

    /**
     * @param viaHttpPort the tunnel's local HTTP proxy, for a second try through the VPN.
     */
    fun fetch(
        url: String,
        userAgent: String,
        appVersion: String,
        hwid: String?,
        viaHttpPort: Int? = null
    ): Fetched {
        val normalized = normalizeUrl(url)
        if (isInlineContent(normalized)) return Fetched(normalized.removePrefix("data:"))

        val proxy = viaHttpPort?.let { Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", it)) } ?: Proxy.NO_PROXY
        val connection = try {
            URI(normalized).toURL().openConnection(proxy) as HttpURLConnection
        } catch (e: Exception) {
            throw FetchException(I18n.s.errNotRecognized)
        }
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", userAgent.trim().ifBlank { DEFAULT_USER_AGENT })
            connection.setRequestProperty("x-device-os", "Windows")
            connection.setRequestProperty("x-ver-os", System.getProperty("os.version").orEmpty())
            connection.setRequestProperty("x-device-model", System.getProperty("os.name").orEmpty().ifBlank { "PC" })
            connection.setRequestProperty("x-app-version", appVersion)
            connection.setRequestProperty("Accept", "*/*")
            if (!hwid.isNullOrBlank()) connection.setRequestProperty("x-hwid", hwid)

            val code = try {
                connection.responseCode
            } catch (e: UnknownHostException) {
                throw FetchException(I18n.s.errNoNet)
            } catch (e: SocketTimeoutException) {
                throw FetchException(I18n.s.errTimeout)
            } catch (e: SSLException) {
                throw FetchException(I18n.s.errTls)
            } catch (e: IOException) {
                throw FetchException(I18n.s.errLoad)
            }
            if (code !in 200..299) throw FetchException(I18n.s.errHttp(code))

            val body = try {
                connection.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }.removePrefix("﻿")
            } catch (e: SocketTimeoutException) {
                throw FetchException(I18n.s.errTimeout)
            } catch (e: IOException) {
                throw FetchException(I18n.s.errLoad)
            }
            if (body.isBlank()) throw FetchException(I18n.s.errEmpty)

            fun header(name: String): String =
                connection.headerFields.entries
                    .firstOrNull { (k, _) -> k != null && k.equals(name, ignoreCase = true) }
                    ?.value?.firstOrNull().orEmpty()

            val title = header("profile-title").ifBlank { titleFromContentDisposition(header("content-disposition")) }
            val userInfo = header("subscription-userinfo")
            val routing = header("routing")
            val extra = buildString {
                if (title.isNotBlank()) appendLine("#profile-title: $title")
                if (userInfo.isNotBlank()) appendLine("#subscription-userinfo: $userInfo")
                if (routing.isNotBlank()) appendLine("#routing-link: $routing")
                header("support-url").takeIf { it.isNotBlank() }?.let { appendLine("#support-url: $it") }
                header("announce").takeIf { it.isNotBlank() }?.let { appendLine("#announce: $it") }
                header("profile-update-interval").takeIf { it.isNotBlank() }
                    ?.let { appendLine("#profile-update-interval: $it") }
                header("profile-web-page-url").takeIf { it.isNotBlank() }
                    ?.let { appendLine("#profile-web-page-url: $it") }
            }
            return Fetched(body = extra + body, routingHeader = routing)
        } finally {
            connection.disconnect()
        }
    }

    fun titleFromContentDisposition(cd: String): String {
        if (cd.isBlank()) return ""
        val m = Regex("""filename\*?=(?:UTF-8''|")?([^";]+)""", RegexOption.IGNORE_CASE).find(cd) ?: return ""
        return ConfigParser.decodeProfileTitle(m.groupValues[1].trim().trim('"'))
    }

    /** An error text with every trace of a hidden subscription's address taken out. */
    fun conceal(message: String?, url: String): String {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        var text = message?.trim().orEmpty()
        listOf(url, host).filter { it.isNotBlank() }.forEach { text = text.replace(it, "…", ignoreCase = true) }
        text = text.replace(Regex("https?://\\S+"), "…")
        return text.ifBlank { I18n.s.errRefresh }
    }
}
