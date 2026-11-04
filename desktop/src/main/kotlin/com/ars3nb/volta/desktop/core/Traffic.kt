package com.ars3nb.volta.desktop.core

import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI

/**
 * The core's traffic counters, read from its metrics endpoint (/debug/vars). The
 * counters only grow; speeds are the difference between two readings.
 */
object TrafficMeter {

    data class Totals(val uplink: Long, val downlink: Long)

    /** Bytes through every proxy outbound ("proxy", or "proxy-N" behind a balancer). */
    fun read(metricsPort: Int): Totals? = runCatching {
        val c = URI("http://127.0.0.1:$metricsPort/debug/vars").toURL().openConnection(Proxy.NO_PROXY) as HttpURLConnection
        try {
            c.connectTimeout = 1_000
            c.readTimeout = 2_000
            if (c.responseCode != 200) return null
            parse(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    fun parse(json: String): Totals? = runCatching {
        val outbound = JsonParser.parseString(json).asJsonObject
            .getAsJsonObject("stats")?.getAsJsonObject("outbound") ?: return Totals(0, 0)
        var up = 0L
        var down = 0L
        for ((tag, value) in outbound.entrySet()) {
            if (!tag.startsWith("proxy") || !value.isJsonObject) continue
            val o = value.asJsonObject
            up += o.get("uplink")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
            down += o.get("downlink")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
        }
        Totals(up, down)
    }.getOrNull()
}
