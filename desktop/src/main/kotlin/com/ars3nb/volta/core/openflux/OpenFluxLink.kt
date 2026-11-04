package com.ars3nb.volta.core.openflux

import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Inflater

/**
 * What an `openflux://v1/<base64url(DEFLATE(JSON))>` link says, read only far enough to
 * list it: name, carriers, where it leads. Connecting is left to the OpenFlux core,
 * which reads the link itself, so the two can never disagree about what it means.
 *
 * Lenient the way the core is: whitespace and line breaks inside the link, base64
 * padding and the standard alphabet all pass.
 */
data class OpenFluxLink(
    val name: String,
    val transports: List<Carrier>,
    val encrypted: Boolean,
    /** "" for the packet tunnel, "stream" for a PHP exit on ordinary web hosting. */
    val mode: String
) {
    data class Carrier(val type: String, val url: String, val dial: String, val priority: Int)

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
                val host = direct.dial.substringBeforeLast(':').trim('[', ']')
                val port = direct.dial.substringAfterLast(':').toIntOrNull() ?: 443
                return host to port
            }
            val url = transports.firstOrNull { it.url.isNotBlank() }?.url.orEmpty()
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?')
                .substringBefore(':')
            return host.ifBlank { carrierHosts[transports.firstOrNull()?.type].orEmpty() } to 443
        }

    companion object {
        const val PREFIX = "openflux://v1/"
        private const val MAX_PAYLOAD = 16 shl 10

        fun isLink(text: String): Boolean = text.trim().startsWith("openflux://")

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
            val trimmed = link.trim()
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
                mode = root.get("mode")?.asString.orEmpty()
            )
        }.getOrNull()

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
