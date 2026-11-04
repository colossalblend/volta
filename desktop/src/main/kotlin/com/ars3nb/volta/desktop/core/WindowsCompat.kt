package com.ars3nb.volta.desktop.core

import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.Gson

/**
 * What changes when a server is opened on Windows instead of Android.
 *
 * A plain WireGuard `.conf` (no AmneziaWG fields) runs on Android through the voltaproxy
 * helper; Xray dials WireGuard itself, so here it becomes an ordinary WireGuard server.
 * AmneziaWG proper, Hysteria v1 and OpenFlux stay as they are and are reported as not
 * available on Windows.
 */
object WindowsCompat {

    private val gson = Gson()

    /** Protocols that need an Android-only helper. */
    fun needsAndroidHelper(server: ServerEntity): Boolean =
        server.protocol == ProtocolType.AMNEZIAWG ||
            server.protocol == ProtocolType.HYSTERIA ||
            server.protocol == ProtocolType.OPENFLUX

    fun adapt(server: ServerEntity): ServerEntity {
        if (server.protocol != ProtocolType.AMNEZIAWG || server.network != "wg") return server
        return wireGuardFromConf(server) ?: server
    }

    private fun wireGuardFromConf(server: ServerEntity): ServerEntity? {
        val conf = server.rawLink
        if (!conf.contains("[Interface]", ignoreCase = true)) return null
        var section = ""
        val iface = HashMap<String, String>()
        val peer = HashMap<String, String>()
        conf.lineSequence().map { it.trim() }.forEach { line ->
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
                line.startsWith("[") && line.endsWith("]") -> section = line.trim('[', ']').trim().lowercase()
                else -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        val key = line.substring(0, eq).trim().lowercase()
                        val value = line.substring(eq + 1).trim()
                        when (section) {
                            "interface" -> iface.putIfAbsent(key, value)
                            "peer" -> peer.putIfAbsent(key, value)
                        }
                    }
                }
            }
        }
        val privateKey = iface["privatekey"].orEmpty()
        val publicKey = peer["publickey"].orEmpty()
        if (privateKey.isBlank() || publicKey.isBlank()) return null
        val extras = buildMap {
            put("privateKey", privateKey)
            put("publicKey", publicKey)
            iface["address"]?.let { put("address", it) }
            iface["mtu"]?.let { put("mtu", it) }
            peer["presharedkey"]?.let { put("preSharedKey", it) }
            peer["allowedips"]?.let { put("allowedIPs", it) }
            peer["reserved"]?.let { put("reserved", it) }
        }
        return server.copy(
            protocol = ProtocolType.WIREGUARD,
            uuid = privateKey,
            pbk = publicKey,
            network = "udp",
            security = "none",
            extraJson = gson.toJson(extras)
        )
    }
}
