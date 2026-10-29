package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Servers whose share link still says allowInsecure / insecure=1.
 *
 * Xray removed allowInsecure and refuses any config that sets it; the replacement is
 * pinning the server's certificate (pinnedPeerCertSha256). For such a link the
 * certificate the server presents right now is pinned, which accepts exactly what
 * allowInsecure accepted before. It is read by this app, outside its own tunnel, and
 * kept for a while so connecting and pinging don't repeat the handshake each time.
 */
object CertPins {

    private data class Pin(val sha256: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Pin>()

    /** [server] with its certificate pinned when it asks to skip verification. */
    fun apply(server: ServerEntity): ServerEntity {
        if (!ConfigBuilder.asksInsecure(server) || ConfigBuilder.pinsOf(server).isNotEmpty()) {
            return server
        }
        // The voltaproxy helper reads the link as it is; a pin here would never reach it.
        if (ConfigBuilder.helperSpec(server) != null) return server
        val serverName = ConfigBuilder.tlsServerName(server)
        val key = "${server.protocol}|${server.address}|${server.port}|$serverName"
        val now = System.currentTimeMillis()
        val pin = cache[key]?.takeIf { now - it.at < TTL_MS }?.sha256
            ?: XrayCore.fetchCertSha256(
                address = server.address,
                port = server.port,
                serverName = serverName,
                quic = server.protocol == ProtocolType.HYSTERIA2
            )?.also { cache[key] = Pin(it, now) }
            // Unreachable now: verify as usual, which works whenever the certificate is
            // in fact valid (most "insecure" links are).
            ?: return server
        return server.copy(extraJson = withPin(server.extraJson, pin))
    }

    private fun withPin(extraJson: String, sha256: String): String {
        val obj = runCatching { JsonParser.parseString(extraJson).asJsonObject }
            .getOrNull() ?: JsonObject()
        obj.addProperty("pcs", sha256)
        return obj.toString()
    }

    /** Certificates get renewed; a stale pin would lock the server out. */
    private const val TTL_MS = 30 * 60_000L
}
