package com.ars3nb.volta.core.server

import com.ars3nb.volta.data.model.ServerRole

/**
 * How a VPN provider can tell Volta which servers are Home (Wi-Fi), LTE and provider
 * balancers. Any of three ways works, alone or mixed:
 *
 *  1. **JSON groups** — the subscription body is a JSON object with the lists under
 *     group keys: `{"wifi": [...], "lte": [...], "balancers": [...]}` (parsed by
 *     ConfigParser; the keys are matched by [groupForKey]).
 *  2. **Name prefix** — the server name starts with `[H]`, `[L]` or `[A]`
 *     (also `[HOME]`, `[WIFI]`, `[LTE]`, `[AUTO]`). The tag is cut from the shown name.
 *  3. **Import link** — `server-type=lte:1,2,5-7;home:3;auto:9`: servers by their
 *     1-based position in the subscription.
 *
 * Precedence for one server: JSON group, then prefix, then `server-type`. A server none
 * of them marks falls back to the name check ([ServerClassifier.autoRole]; an OpenFlux
 * server riding only documents and other Russian services goes to LTE) — except that a
 * group the provider marked explicitly is treated as complete, so the name check never
 * adds more servers to it.
 *
 * All of it works the same for every protocol, OpenFlux included: an `openflux://` link
 * takes a `#[L] name` after it like any share link, a JSON group may list OpenFlux links
 * or OpenFlux configurations (`{"name", "secret", "transports": [...]}`).
 */
object ServerMarkup {

    /** A server-type map: position (1-based) → group. */
    data class TypeMap(val roles: Map<Int, ServerRole>, val explicit: Set<ServerRole>) {
        val isEmpty: Boolean get() = roles.isEmpty()

        companion object {
            val EMPTY = TypeMap(emptyMap(), emptySet())
        }
    }

    data class Prefixed(val role: ServerRole, val remark: String)

    /**
     * `[L]`, `[H]`, `[A]` and their long forms at the very start of a name, with any
     * bracket style providers tend to use. Only at the start: a tag in the middle of a
     * name is somebody's decoration, not markup.
     */
    private val prefix = Regex(
        "^\\s*[\\[(【{]\\s*(l|lte|h|home|wifi|wi-fi|a|auto|bal|balancer)\\s*[\\])】}]\\s*[-|·:•]?\\s*",
        RegexOption.IGNORE_CASE
    )

    fun prefixRole(remark: String): Prefixed? {
        val match = prefix.find(remark) ?: return null
        val role = roleForWord(match.groupValues[1]) ?: return null
        val rest = remark.substring(match.range.last + 1).trim()
        return Prefixed(role, rest.ifBlank { remark.trim() })
    }

    /** Group of a JSON key or a `server-type` word; null when it names no group. */
    fun groupForKey(key: String): ServerRole? =
        roleForWord(key.lowercase().filter { it.isLetterOrDigit() })

    private fun roleForWord(raw: String): ServerRole? = when (raw.lowercase().replace("-", "")) {
        "h", "home", "wifi", "wlan", "normal", "regular", "homes" -> ServerRole.NORMAL
        "l", "lte", "mobile", "cellular", "bypass", "whitelist", "4g", "5g", "ltes" -> ServerRole.BYPASS_WHITELIST
        "a", "auto", "autos", "bal", "balancer", "balancers", "autobalancer", "autobalancers",
        "autobalance", "loadbalance", "loadbalancer", "loadbalancers" -> ServerRole.AUTO
        else -> null
    }

    /**
     * Reads `lte:1,2,5-7;home:3|auto:9`. Groups are split by `;`, `|`, `/` or a space
     * before a new `word:`; numbers by `,`, `+` or spaces (a `+` in a query string arrives
     * as a space). Ranges `a-b` are expanded up to [MAX_POSITION]. Junk is skipped, never
     * fatal: a typo in one group must not cost the provider the others.
     */
    fun parseServerTypes(raw: String?): TypeMap {
        if (raw.isNullOrBlank()) return TypeMap.EMPTY
        val roles = linkedMapOf<Int, ServerRole>()
        val explicit = linkedSetOf<ServerRole>()
        val groups = Regex("([\\p{L}\\d-]+)\\s*[:=]\\s*([\\d,+\\s\\-]*)").findAll(raw)
        for (g in groups) {
            val role = groupForKey(g.groupValues[1]) ?: continue
            val positions = parsePositions(g.groupValues[2])
            if (positions.isEmpty()) continue
            explicit += role
            // First mention wins: "lte:1;home:1" keeps server 1 in LTE.
            positions.forEach { roles.putIfAbsent(it, role) }
        }
        return TypeMap(roles, explicit)
    }

    private fun parsePositions(text: String): List<Int> {
        val out = mutableListOf<Int>()
        text.split(',', '+', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }.forEach { token ->
            val range = Regex("^(\\d{1,4})\\s*-\\s*(\\d{1,4})$").find(token)
            if (range != null) {
                val a = range.groupValues[1].toInt()
                val b = range.groupValues[2].toInt()
                if (a in 1..b && b <= MAX_POSITION) out += (a..b)
            } else {
                token.toIntOrNull()?.takeIf { it in 1..MAX_POSITION }?.let { out += it }
            }
        }
        return out.distinct()
    }

    /** The canonical form kept in the database: `lte:1,2;home:3;auto:9`. */
    fun normalize(raw: String?): String {
        val map = parseServerTypes(raw)
        if (map.isEmpty) return ""
        return listOf(ServerRole.BYPASS_WHITELIST, ServerRole.NORMAL, ServerRole.AUTO)
            .mapNotNull { role ->
                val positions = map.roles.filterValues { it == role }.keys.sorted()
                if (positions.isEmpty()) null else "${wordFor(role)}:${positions.joinToString(",")}"
            }
            .joinToString(";")
    }

    fun wordFor(role: ServerRole): String = when (role) {
        ServerRole.NORMAL -> "home"
        ServerRole.BYPASS_WHITELIST -> "lte"
        ServerRole.AUTO -> "auto"
    }

    /** More than any real subscription has; keeps a typo like `1-99999` cheap. */
    const val MAX_POSITION = 2_000
}
