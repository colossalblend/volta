package com.ars3nb.volta.core.ping

import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.IpInfo
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object IpChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

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
     * The app excludes itself from its own VPN so the core can dial out, which means a
     * plain request here reports the real ISP address even while connected. Routing the
     * lookup through the tunnel is the only way to show the exit address.
     */
    suspend fun fetch(socksPort: Int? = null): IpInfo = coroutineScope {
        val http = if (socksPort == null) {
            client
        } else {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .build()
        }
        val answers = Channel<IpInfo?>(Channel.UNLIMITED)
        val asks = endpoints.map { url ->
            launch { answers.send(runCatching { query(http, url) }.getOrNull()) }
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
        asks.forEach { it.cancel() }
        best ?: addressOnly ?: IpInfo(queriedAt = System.currentTimeMillis())
    }

    private suspend fun query(http: OkHttpClient, url: String): IpInfo? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Volta/1.0")
            .header("Accept", "application/json")
            .get()
            .build()
        return http.newCall(req).await().use { resp ->
            if (!resp.isSuccessful) null else parse(resp.body?.string().orEmpty())
        }
    }

    /** OkHttp call that is dropped as soon as the caller stops waiting for it. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }
        })
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
                code.length == 2 -> Countries.byCode(code).let { "${it.flag} ${it.name}" }
                named != null -> named
                else -> ""
            }
            IpInfo(
                ip = ip,
                country = country,
                countryCode = if (code.length == 2) code.uppercase() else "",
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
