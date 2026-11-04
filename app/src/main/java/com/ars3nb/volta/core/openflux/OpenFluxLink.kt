package com.ars3nb.volta.core.openflux

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * What an `openflux://v1/<base64url(DEFLATE(JSON))>` link says, read only far enough to
 * list it: name, carriers, where it leads. Connecting is left to the OpenFlux core,
 * which reads the link itself, so the two can never disagree about what it means.
 *
 * One link is one exit (OpenFlux's share.Config: "what a client needs to connect to one
 * exit"). Several transports in it are several roads to that same exit: the core runs
 * them all at once in one session and sends traffic over the best one that reaches the
 * exit, falling back to the next by priority. So a link with several carriers is one
 * server in Volta, and the helper does the choosing between its carriers. Several exits
 * come as several links (a subscription, a list, a JSON array of configs).
 *
 * Lenient the way the core is: whitespace and line breaks inside the link, base64
 * padding and the standard alphabet all pass.
 */
data class OpenFluxLink(
    val name: String,
    val transports: List<Carrier>,
    val encrypted: Boolean,
    /** "" for the packet tunnel, "stream" for a PHP exit on ordinary web hosting. */
    val mode: String,
    /** The encryption context (the exit's --url), part of what tells exits apart. */
    val context: String = ""
) {
    data class Carrier(val type: String, val url: String, val dial: String, val priority: Int)

    /** A place a carrier connects to: the exit itself for direct, the service otherwise. */
    data class Endpoint(val type: String, val host: String, val port: Int, val priority: Int)

    /** The carriers as people know them, best first: «Яндекс Документы + напрямую». */
    val carriersLabel: String
        get() = transports.sortedByDescending { it.priority }
            .map { carrierName(it.type) }
            .distinct()
            .joinToString(" + ")

    /** The exit's own address when the link dials it directly, else the first carrier's host. */
    val endpoint: Pair<String, Int>
        get() {
            transports.firstOrNull { it.type == "direct" && it.dial.isNotBlank() }?.let { direct ->
                return hostPort(direct.dial)
            }
            val url = transports.firstOrNull { urlHost(it.url).isNotBlank() }?.url.orEmpty()
            return urlHost(url).ifBlank { carrierHosts[transports.firstOrNull()?.type].orEmpty() } to 443
        }

    /**
     * Where each carrier connects, best first (the order the core prefers them in):
     * direct dials the exit, the others reach their service (a Yandex or Mail.ru
     * document, Cups, Avito…). A carrier whose host can't be known is left out.
     */
    val endpoints: List<Endpoint>
        get() = transports.sortedByDescending { it.priority }.mapNotNull { carrier ->
            if (carrier.type == "direct") {
                if (carrier.dial.isBlank()) return@mapNotNull null
                val (host, port) = hostPort(carrier.dial)
                Endpoint(carrier.type, host, port, carrier.priority)
            } else {
                val host = urlHost(carrier.url).ifBlank { carrierHosts[carrier.type].orEmpty() }
                if (host.isBlank()) null else Endpoint(carrier.type, host, 443, carrier.priority)
            }
        }.filter { it.host.isNotBlank() }.distinctBy { it.host.lowercase() to it.port }

    /**
     * Which exit this is, whatever the link is called: its carriers and their documents
     * or addresses, and the encryption context. Two links to different exits over the
     * same service (two Yandex documents) differ here, a renamed link does not.
     */
    val identity: String
        get() {
            val parts = transports
                .map { "${it.type}|${it.url.trim()}|${it.dial.trim()}" }
                .sorted() + "ctx|${context.trim()}" + "mode|$mode"
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(parts.joinToString("\n").toByteArray(Charsets.UTF_8))
            return digest.take(12).joinToString("") { "%02x".format(it) }
        }

    /**
     * Every carrier rides a service the white lists of mobile networks let through
     * (documents, boards, Cups, Avito, Telemost), none goes to the exit's own address.
     */
    val onlyThroughServices: Boolean
        get() = transports.isNotEmpty() && transports.all { it.type in SERVICE_CARRIERS }

    companion object {
        const val PREFIX = "openflux://v1/"
        private const val MAX_PAYLOAD = 16 shl 10

        /**
         * Carriers that ride a Russian service rather than dial the exit: what keeps
         * working on a mobile network that only lets white-listed sites through.
         */
        val SERVICE_CARRIERS = setOf(
            "yandex", "vyandex", "boards", "mailru", "cupsonline", "avito", "telemost", "oneme"
        )

        fun isLink(text: String): Boolean = text.trim().startsWith("openflux://")

        /**
         * Splits off a `#name` a provider may put after the link, the way every other
         * share link carries its name. The OpenFlux core does not know it (a `#` is not
         * base64url, so the link could never carry one by itself): the name is Volta's,
         * the rest goes to the core untouched. Returns the link and the raw name.
         */
        fun splitName(text: String): Pair<String, String> {
            val t = text.trim()
            val hash = t.indexOf('#')
            return if (hash < 0) t to "" else t.substring(0, hash).trim() to t.substring(hash + 1).trim()
        }

        /**
         * Whether a JSON object is an OpenFlux share configuration (share.Config, what
         * `openflux --make-link` takes): a list of transports, each with a type.
         */
        fun isShareConfig(obj: JsonObject): Boolean {
            if (obj.has("outbounds") || obj.has("protocol")) return false
            val transports = obj.get("transports")?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
            return transports.size() > 0 && transports.all { t ->
                t.isJsonObject && t.asJsonObject.get("type")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.isNotBlank() == true
            }
        }

        /**
         * The link for a share configuration given as JSON, made the way the core makes
         * one (raw DEFLATE, base64url without padding), so the helper reads it like any
         * scanned link. Null when it is not a configuration with carriers.
         */
        fun encode(config: JsonObject): String? = runCatching {
            if (!isShareConfig(config)) return null
            val raw = config.toString().toByteArray(Charsets.UTF_8)
            if (raw.size > MAX_PAYLOAD) return null
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            val packed = try {
                deflater.setInput(raw)
                deflater.finish()
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!deflater.finished()) {
                    val n = deflater.deflate(buffer)
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } finally {
                deflater.end()
            }
            val link = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(packed)
            link.takeIf { parse(it) != null }
        }.getOrNull()

        /**
         * Volta's own wrappers around an OpenFlux link, so a tap on one opens Volta even
         * with another OpenFlux app installed: `volta://openflux://v1/…`, and the short
         * `volta://of/<payload>` (what follows `openflux://v1/`). Null for anything else.
         */
        fun unwrap(text: String): String? {
            val t = text.trim()
            return when {
                t.startsWith("volta://openflux://", ignoreCase = true) -> t.substring("volta://".length)
                t.startsWith("volta://of/", ignoreCase = true) ->
                    PREFIX + t.substring("volta://of/".length).removePrefix("v1/")

                else -> null
            }
        }

        fun parse(link: String): OpenFluxLink? = runCatching {
            val trimmed = splitName(link).first
            if (!trimmed.startsWith(PREFIX)) return null
            val body = trimmed.removePrefix(PREFIX)
                .filterNot { it.isWhitespace() || it == '=' }
                .replace('+', '-')
                .replace('/', '_')
            val packed = Base64.getUrlDecoder().decode(body)
            val json = inflate(packed) ?: return null
            val root = JsonParser.parseString(json).asJsonObject
            val carriers = root.getAsJsonArray("transports")?.mapNotNull { item ->
                val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                Carrier(
                    type = o.get("type")?.asString.orEmpty(),
                    url = o.get("url")?.asString.orEmpty(),
                    dial = o.get("dial")?.asString.orEmpty(),
                    priority = o.get("priority")?.asInt ?: 0
                )
            }.orEmpty().filter { it.type.isNotBlank() }
            if (carriers.isEmpty()) return null
            OpenFluxLink(
                name = root.get("name")?.asString.orEmpty().trim(),
                transports = carriers,
                encrypted = !root.get("secret")?.asString.isNullOrBlank(),
                mode = root.get("mode")?.asString.orEmpty(),
                context = root.get("context")?.asString.orEmpty()
            )
        }.getOrNull()

        /** `host:port`, `[v6]:port` or a bare host (port 443). */
        private fun hostPort(dial: String): Pair<String, Int> {
            val d = dial.trim()
            if (d.startsWith("[")) {
                val end = d.indexOf(']')
                if (end > 0) {
                    val port = d.substring(end + 1).removePrefix(":").toIntOrNull() ?: 443
                    return d.substring(1, end) to port
                }
            }
            return if (d.count { it == ':' } == 1) {
                d.substringBefore(':') to (d.substringAfter(':').toIntOrNull() ?: 443)
            } else {
                d to 443
            }
        }

        /** The host of an http(s) address; "" for anything else (a Mail.ru weblink, a Cups room list). */
        private fun urlHost(url: String): String {
            val u = url.trim()
            if (!u.startsWith("https://", true) && !u.startsWith("http://", true)) return ""
            return u.substringAfter("://").substringBefore('/').substringBefore('?')
                .substringBefore('#').substringAfterLast('@').substringBefore(':')
        }

        /** Raw DEFLATE, as Go's compress/flate writes it; null past the core's own size cap. */
        private fun inflate(packed: ByteArray): String? {
            val inflater = Inflater(true)
            return try {
                inflater.setInput(packed)
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (!inflater.finished()) {
                    val n = inflater.inflate(buffer)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_PAYLOAD) return null
                }
                out.toString(Charsets.UTF_8.name())
            } finally {
                inflater.end()
            }
        }

        fun carrierName(type: String): String = when (type) {
            "yandex" -> "Яндекс Документы"
            "vyandex" -> "Яндекс Волга"
            "boards" -> "Яндекс Доски"
            "mailru" -> "Mail.ru"
            "cupsonline" -> "Cups"
            "avito" -> "Авито"
            "telemost" -> "Телемост"
            "direct" -> "напрямую"
            else -> type
        }

        private val carrierHosts = mapOf(
            "yandex" to "docs.yandex.ru",
            "vyandex" to "docs.yandex.ru",
            "boards" to "boards.yandex.ru",
            "mailru" to "cloud.mail.ru",
            "cupsonline" to "cups.online",
            "avito" to "code.avito.ru",
            "telemost" to "telemost.yandex.ru"
        )
    }
}
