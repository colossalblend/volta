package com.ars3nb.volta.core.ping

import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.IpInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

object IpChecker {

    /**
     * Free lookup services, all asked at once. Several of them throttle or refuse
     * well-known VPN exits, so one of them failing must never leave the screen empty.
     */
    private val endpoints = listOf(
        "https://ipwho.is/",
        "https://api.ip.sb/geoip",
        "https://ipapi.co/json/",
        "https://ipinfo.io/json",
        "https://ifconfig.co/json",
        "https://get.geojs.io/v1/ip/geo.json",
        "https://free.freeipapi.com/api/json",
        "https://api.ipify.org?format=json"
    )

    /** How long to wait for any answer that also names the country. */
    private const val DEADLINE_MS = 7_000L

    /**
     * @param socksPort when the tunnel is up, the local SOCKS port to go through.
     *
     * Volta's own requests skip the system proxy it sets, so a plain request here reports
     * the real ISP address even while connected. Routing the lookup through the tunnel's
     * exit-check port is the only way to show the exit address.
     */
    suspend fun fetch(socksPort: Int? = null): IpInfo {
        val proxy = if (socksPort == null) Proxy.NO_PROXY
        else Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val answers = Channel<IpInfo?>(Channel.UNLIMITED)
        // Blocking sockets: each ask runs on its own IO thread and is left behind once
        // an answer is in (the timeouts end it shortly after).
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        endpoints.forEach { url ->
            scope.launch { answers.send(runCatching { query(proxy, url) }.getOrNull()) }
        }
        // The first answer with a country wins; one with only the address is kept in
        // case nothing better arrives.
        var addressOnly: IpInfo? = null
        val best = withTimeoutOrNull(DEADLINE_MS) {
            var found: IpInfo? = null
            repeat(endpoints.size) {
                if (found != null) return@repeat
                val info = answers.receive()
                if (info != null && info.ip.isNotBlank()) {
                    if (info.country.isNotBlank()) found = info
                    else if (addressOnly == null) addressOnly = info
                }
            }
            found
        }
        scope.cancel()
        return best ?: addressOnly ?: IpInfo(queriedAt = System.currentTimeMillis())
    }

    private fun query(proxy: Proxy, url: String): IpInfo? {
        val connection = URI(url).toURL().openConnection(proxy) as HttpURLConnection
        return try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.setRequestProperty("User-Agent", "Volta/1.0 (Windows)")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) null
            else parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(body: String): IpInfo? {
        return try {
            val o = JsonParser.parseString(body).asJsonObject
            // ipwho.is answers 200 with success=false when it refuses.
            if (o.has("success") && o.get("success").isJsonPrimitive && !o.get("success").asBoolean) {
                return null
            }
            val ip = o.s("ip", "query", "ipAddress")
            if (ip.isBlank()) return null
            val code = o.s("country_code", "countryCode", "country_iso")
                .ifBlank { o.s("country").takeIf { it.length == 2 }.orEmpty() }
            val named = o.s("country_name", "countryName", "country").takeIf { it.length > 2 }
            val country = when {
                code.length == 2 -> Countries.byCode(code).name
                named != null -> named
                else -> ""
            }
            IpInfo(
                ip = ip,
                country = country,
                countryCode = code.takeIf { it.length == 2 }?.uppercase().orEmpty(),
                city = o.s("city", "cityName"),
                isp = o.s("isp", "org", "organization", "asn_org", "organization_name"),
                org = o.s("org", "organization"),
                timezone = o.s("timezone", "time_zone"),
                queriedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun JsonObject.s(vararg keys: String): String {
        for (k in keys) {
            val v = get(k) ?: continue
            if (!v.isJsonNull && v.isJsonPrimitive) {
                val value = v.asString
                if (value.isNotBlank()) return value
            }
        }
        return ""
    }
}
