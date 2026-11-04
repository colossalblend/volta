package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Servers whose share link still says allowInsecure / insecure=1.
 *
 * Xray removed allowInsecure and refuses any config that sets it; the replacement is
 * pinning the server's certificate (pinnedPeerCertSha256). For such a link the
 * certificate the server presents right now is pinned, which accepts exactly what
 * allowInsecure accepted before. On Android the core reads it; here a plain TLS
 * handshake from Java does, outside the tunnel, and the pin is kept for a while.
 *
 * Hysteria2 runs over QUIC, which Java cannot open: such a server is verified as usual.
 */
object CertPins {

    private data class Pin(val sha256: String, val at: Long)

    private val cache = ConcurrentHashMap<String, Pin>()

    /** [server] with its certificate pinned when it asks to skip verification. */
    fun apply(server: ServerEntity): ServerEntity {
        if (!ConfigBuilder.asksInsecure(server) || ConfigBuilder.pinsOf(server).isNotEmpty()) {
            return server
        }
        if (server.protocol == ProtocolType.HYSTERIA2 || server.rawJson.isNotBlank()) return server
        val serverName = ConfigBuilder.tlsServerName(server)
        val key = "${server.protocol}|${server.address}|${server.port}|$serverName"
        val now = System.currentTimeMillis()
        val pin = cache[key]?.takeIf { now - it.at < TTL_MS }?.sha256
            ?: fetchCertSha256(server.address, server.port, serverName)?.also { cache[key] = Pin(it, now) }
            // Unreachable now: verify as usual, which works whenever the certificate is
            // in fact valid (most "insecure" links are).
            ?: return server
        return server.copy(extraJson = withPin(server.extraJson, pin))
    }

    /** SHA-256 of the certificate [address] presents for [serverName], as hex; null on failure. */
    fun fetchCertSha256(address: String, port: Int, serverName: String, timeoutMs: Int = 4_000): String? =
        runCatching {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustAll), null)
            Socket().use { raw ->
                raw.connect(InetSocketAddress(address, port), timeoutMs)
                raw.soTimeout = timeoutMs
                val tls = context.socketFactory.createSocket(raw, serverName, port, true) as SSLSocket
                tls.use { socket ->
                    if (serverName.isNotBlank() && !serverName.first().isDigit() && !serverName.contains(':')) {
                        socket.sslParameters = socket.sslParameters.apply {
                            serverNames = listOf(SNIHostName(serverName))
                        }
                    }
                    socket.startHandshake()
                    val leaf = socket.session.peerCertificates.firstOrNull() ?: return null
                    MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                        .joinToString("") { "%02x".format(it) }
                }
            }
        }.getOrNull()

    private fun withPin(extraJson: String, sha256: String): String {
        val obj = runCatching { JsonParser.parseString(extraJson).asJsonObject }
            .getOrNull() ?: JsonObject()
        obj.addProperty("pcs", sha256)
        return obj.toString()
    }

    /** Certificates get renewed; a stale pin would lock the server out. */
    private const val TTL_MS = 30 * 60_000L
}
