package com.ars3nb.volta.core.parser

import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.Inflater

/**
 * AmneziaWG and WireGuard in the forms they are handed out in: a `.conf` file, an
 * AmneziaVPN `vpn://` key, or a `wireguard://` link carrying AmneziaWG fields. All of
 * them become one [ProtocolType.AMNEZIAWG] server whose [ServerEntity.rawLink] is the
 * `.conf` text the voltaproxy helper runs. [ServerEntity.network] names the generation
 * (awg3, awg2, awg1.5, awg1, wg) for the server card.
 */
object AmneziaConfigs {

    /** AmneziaWG 3: header protection and the timers and paddings that came with it. */
    private val AWG3_KEYS = setOf(
        "headerprotectionkey", "randomtrailers", "contentpaddingaddition", "disablecookies",
        "rekeyaftertime", "rekeytimeout", "rejectaftertime", "keepalivetimeout",
        "maxhandshakeattempts"
    )

    /** AmneziaWG 2: padded cookie and data packets. Ranged H1–H4 are 2.x too. */
    private val AWG2_KEYS = setOf("s3", "s4")

    /** AmneziaWG 1.5: signature packets before the handshake. */
    private val AWG15_KEYS = setOf("i1", "i2", "i3", "i4", "i5", "j1", "j2", "j3", "itime")

    /** AmneziaWG 1: junk packets, paddings and custom message types. */
    private val AWG1_KEYS = setOf("jc", "jmin", "jmax", "s1", "s2", "h1", "h2", "h3", "h4")

    /** The AmneziaWG fields a share link may carry, spelled as in a .conf. */
    private val LINK_KEYS = (AWG1_KEYS + AWG2_KEYS + AWG15_KEYS + AWG3_KEYS)

    private val CONF_NAMES = mapOf(
        "jc" to "Jc", "jmin" to "Jmin", "jmax" to "Jmax",
        "s1" to "S1", "s2" to "S2", "s3" to "S3", "s4" to "S4",
        "h1" to "H1", "h2" to "H2", "h3" to "H3", "h4" to "H4",
        "i1" to "I1", "i2" to "I2", "i3" to "I3", "i4" to "I4", "i5" to "I5",
        "j1" to "J1", "j2" to "J2", "j3" to "J3", "itime" to "Itime",
        "headerprotectionkey" to "HeaderProtectionKey",
        "randomtrailers" to "RandomTrailers",
        "contentpaddingaddition" to "ContentPaddingAddition",
        "disablecookies" to "DisableCookies",
        "rekeyaftertime" to "RekeyAfterTime",
        "rekeytimeout" to "RekeyTimeout",
        "rejectaftertime" to "RejectAfterTime",
        "keepalivetimeout" to "KeepaliveTimeout",
        "maxhandshakeattempts" to "MaxHandshakeAttempts"
    )

    /** Default DNS AmneziaVPN puts in place of its placeholders. */
    private const val PRIMARY_DNS = "1.1.1.1"
    private const val SECONDARY_DNS = "1.0.0.1"

    private class Section(val name: String) {
        val entries = mutableListOf<Pair<String, String>>()
        fun get(key: String): String =
            entries.firstOrNull { it.first.equals(key, ignoreCase = true) }?.second.orEmpty()
    }

    /** A WireGuard or AmneziaWG `.conf` rather than links or JSON. */
    fun looksLikeConf(text: String): Boolean =
        INTERFACE_SECTION.containsMatchIn(text) && PEER_SECTION.containsMatchIn(text)

    // Compiled once: the import screen asks on every keystroke.
    private val INTERFACE_SECTION = Regex("(?im)^\\s*\\[Interface]")
    private val PEER_SECTION = Regex("(?im)^\\s*\\[Peer]")

    /** One server from a `.conf`. Null when it names no endpoint or keys. */
    fun parseConf(text: String, subscriptionId: Long? = null, name: String = ""): ServerEntity? {
        val conf = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').trim()
        val sections = sections(conf)
        val iface = sections.firstOrNull { it.name.equals("interface", true) } ?: return null
        val peer = sections.firstOrNull { it.name.equals("peer", true) } ?: return null
        if (iface.get("PrivateKey").isBlank() || peer.get("PublicKey").isBlank()) return null
        val (host, port) = splitEndpoint(peer.get("Endpoint")) ?: return null
        val generation = generation(iface)
        return ServerEntity(
            subscriptionId = subscriptionId,
            remark = name.ifBlank { "${generationLabel(generation)} · $host" },
            protocol = ProtocolType.AMNEZIAWG,
            address = host,
            port = port,
            // As for WireGuard links: the client key tells two configs of one server apart.
            uuid = iface.get("PrivateKey"),
            pbk = peer.get("PublicKey"),
            network = generation,
            security = "none",
            rawLink = conf
        )
    }

    /** Which AmneziaWG the interface section speaks: awg3, awg2, awg1.5, awg1 or wg. */
    private fun generation(iface: Section): String {
        val keys = iface.entries.map { it.first.lowercase() }.toSet()
        val ranged = listOf("h1", "h2", "h3", "h4").any { iface.get(it).contains('-') }
        return when {
            keys.any { it in AWG3_KEYS } -> "awg3"
            keys.any { it in AWG2_KEYS } || ranged -> "awg2"
            keys.any { it in AWG15_KEYS } -> "awg1.5"
            keys.any { it in AWG1_KEYS } -> "awg1"
            else -> "wg"
        }
    }

    fun generationLabel(generation: String): String = when (generation) {
        "wg" -> "WireGuard"
        "awg3" -> "AmneziaWG 3"
        "awg2" -> "AmneziaWG 2"
        "awg1.5" -> "AmneziaWG 1.5"
        "awg1" -> "AmneziaWG 1"
        else -> "AmneziaWG"
    }

    /**
     * A `wireguard://` link with AmneziaWG fields (jc, s1, h1…), as some apps share
     * them. Null when it carries none: then the core's own WireGuard dials it.
     */
    fun fromWireGuardLink(
        privateKey: String,
        host: String,
        port: Int,
        params: Map<String, String>,
        remark: String,
        subscriptionId: Long?
    ): ServerEntity? {
        val lower = params.mapKeys { it.key.lowercase() }
        val awg = lower.filterKeys { it in LINK_KEYS }
        if (awg.isEmpty()) return null
        fun param(vararg keys: String) = keys.firstNotNullOfOrNull { k -> lower[k]?.takeIf { it.isNotBlank() } }
        val address = param("address", "ip", "local_address", "localaddress") ?: return null
        val publicKey = param("publickey", "public_key", "peer_public_key", "pbk") ?: return null
        val conf = buildString {
            appendLine("[Interface]")
            appendLine("PrivateKey = $privateKey")
            appendLine("Address = $address")
            param("dns")?.let { appendLine("DNS = $it") }
            param("mtu")?.let { appendLine("MTU = $it") }
            awg.forEach { (k, v) -> appendLine("${CONF_NAMES[k] ?: k} = $v") }
            appendLine()
            appendLine("[Peer]")
            appendLine("PublicKey = $publicKey")
            param("presharedkey", "preshared_key", "pre_shared_key", "psk")
                ?.let { appendLine("PresharedKey = $it") }
            appendLine("Endpoint = ${joinHostPort(host, port)}")
            appendLine("AllowedIPs = ${param("allowedips", "allowed_ips") ?: "0.0.0.0/0, ::/0"}")
            param("keepalive", "persistentkeepalive", "persistent_keepalive")
                ?.let { appendLine("PersistentKeepalive = $it") }
        }
        return parseConf(conf, subscriptionId, remark)
    }

    /**
     * An AmneziaVPN key: `vpn://` and base64url of a Qt-compressed (or plain) JSON. Each
     * of its containers keeps its last client config; the WireGuard and AmneziaWG ones
     * are `.conf` text, the Xray ones a full Xray config. The server's default container
     * comes first.
     */
    fun parseAmneziaKey(link: String, subscriptionId: Long? = null): List<ServerEntity> {
        val root = decodeKey(link) ?: return emptyList()
        val name = root.str("description").ifBlank { root.str("name") }
        val dns1 = root.str("dns1").ifBlank { PRIMARY_DNS }
        val dns2 = root.str("dns2").ifBlank { SECONDARY_DNS }
        val default = root.str("defaultContainer")
        val containers = root.get("containers")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf(JsonElement::isJsonObject)?.asJsonObject }
            .orEmpty()
            .sortedByDescending { it.str("container") == default }
        val servers = mutableListOf<ServerEntity>()
        for (container in containers) {
            for ((_, value) in container.entrySet()) {
                if (!value.isJsonObject) continue
                val last = lastConfig(value.asJsonObject) ?: continue
                val text = last.str("config")
                when {
                    looksLikeConf(text) -> {
                        val conf = text.replace("\$PRIMARY_DNS", dns1).replace("\$SECONDARY_DNS", dns2)
                        parseConf(conf, subscriptionId, name)?.let(servers::add)
                    }

                    last.has("outbounds") -> {
                        val parsed = runCatching {
                            ConfigParser.parseSubscriptionBody(last.toString(), subscriptionId).servers
                        }.getOrDefault(emptyList())
                        servers += parsed.map { s -> if (name.isNotBlank()) s.copy(remark = name) else s }
                    }
                }
            }
        }
        return servers.distinctBy { it.rawLink.ifBlank { it.rawJson } + it.address + it.port }
    }

    /** The decoded JSON of a `vpn://` key, or null. */
    private fun decodeKey(link: String): JsonObject? {
        val payload = link.trim().substringAfter("://").filterNot { it.isWhitespace() }
            .replace('+', '-').replace('/', '_').trimEnd('=')
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload) }.getOrNull() ?: return null
        val text = qUncompress(bytes) ?: String(bytes, StandardCharsets.UTF_8)
        return runCatching { JsonParser.parseString(text.trim()).asJsonObject }.getOrNull()
    }

    /** Qt's qCompress: four bytes of big-endian length, then a zlib stream. */
    private fun qUncompress(bytes: ByteArray): String? {
        if (bytes.size < 6) return null
        val inflater = Inflater()
        return try {
            inflater.setInput(bytes, 4, bytes.size - 4)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buf, 0, n)
            }
            if (!inflater.finished()) null else String(out.toByteArray(), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            null
        } finally {
            inflater.end()
        }
    }

    /** `last_config` is a JSON object, usually written out as a string. */
    private fun lastConfig(container: JsonObject): JsonObject? {
        val raw = container.get("last_config") ?: return null
        return runCatching {
            when {
                raw.isJsonObject -> raw.asJsonObject
                raw.isJsonPrimitive -> JsonParser.parseString(raw.asString).asJsonObject
                else -> null
            }
        }.getOrNull()
    }

    private fun sections(conf: String): List<Section> {
        val out = mutableListOf<Section>()
        var current: Section? = null
        conf.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") || line.startsWith(";") -> Unit
                line.startsWith("[") && line.endsWith("]") ->
                    current = Section(line.substring(1, line.length - 1).trim()).also(out::add)

                else -> {
                    val eq = line.indexOf('=')
                    if (eq > 0) {
                        current?.entries?.add(line.substring(0, eq).trim() to line.substring(eq + 1).trim())
                    }
                }
            }
        }
        return out
    }

    /** host:port, [v6]:port. Null without a port. */
    private fun splitEndpoint(endpoint: String): Pair<String, Int>? {
        val e = endpoint.trim()
        if (e.isBlank()) return null
        if (e.startsWith("[")) {
            val end = e.indexOf(']')
            if (end < 0) return null
            val port = e.substring(end + 1).removePrefix(":").toIntOrNull() ?: return null
            return e.substring(1, end) to port
        }
        val colon = e.lastIndexOf(':')
        if (colon <= 0) return null
        val port = e.substring(colon + 1).toIntOrNull() ?: return null
        return e.substring(0, colon) to port
    }

    private fun joinHostPort(host: String, port: Int) =
        if (host.contains(':') && !host.startsWith("[")) "[$host]:$port" else "$host:$port"

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
}
