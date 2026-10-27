package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.IpInfo
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

object IpChecker {

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val endpoints = listOf(
        "https://ipapi.co/json/",
        "https://ipinfo.io/json",
        "https://api.ipify.org?format=json"
    )

    /**
     * @param socksPort when the tunnel is up, the local SOCKS port to go through.
     *
     * The app excludes itself from its own VPN so the core can dial out, which means a
     * plain request here reports the real ISP address even while connected. Routing the
     * lookup through the tunnel is the only way to show the exit address.
     */
    suspend fun fetch(socksPort: Int? = null): IpInfo = withContext(Dispatchers.IO) {
        val http = if (socksPort == null) {
            client
        } else {
            client.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .build()
        }
        for (url in endpoints) {
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Volta/1.0")
                    .get()
                    .build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val parsed = parse(resp.body?.string().orEmpty())
                    if (parsed.ip.isNotBlank()) return@withContext parsed
                }
            } catch (_: Exception) {
                // try the next endpoint
            }
        }
        IpInfo(queriedAt = System.currentTimeMillis())
    }

    private fun parse(body: String): IpInfo {
        return try {
            val o = JsonParser.parseString(body).asJsonObject
            fun s(vararg keys: String): String {
                for (k in keys) {
                    if (o.has(k) && !o.get(k).isJsonNull && o.get(k).isJsonPrimitive) {
                        val value = o.get(k).asString
                        if (value.isNotBlank()) return value
                    }
                }
                return ""
            }
            IpInfo(
                ip = s("ip", "query"),
                country = s("country_name", "country"),
                city = s("city"),
                isp = s("org", "isp"),
                org = s("org"),
                timezone = s("timezone"),
                queriedAt = System.currentTimeMillis()
            )
        } catch (_: Exception) {
            IpInfo(queriedAt = System.currentTimeMillis())
        }
    }
}
