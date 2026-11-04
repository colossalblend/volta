package com.ars3nb.volta.core.routing

import android.util.Base64
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Разбор правил из VPN-подписки (Happ и похожие сервисы).
 *
 * Источники:
 * - заголовок routing: happ://routing/onadd/BASE64
 * - строка в теле happ://routing/onadd/...
 * - JSON Happ (DirectSites / ProxySites / BlockSites …)
 * - обычный Xray routing
 */
object HappRoutingParser {

    /** Called for every domain of a provider's rules (thousands): nothing is built per call. */
    private val DOMAIN_PREFIXES = listOf("geosite:", "domain:", "full:", "regexp:", "keyword:", "ext:")

    // Names Volta gives what it makes from a provider's lists. They are stored with the
    // rules, so they stay the same in every language; [RoutingNames] shows them translated.
    /** The set's name when the provider gives none. */
    const val DEFAULT_NAME = "Правила подписки"
    const val RULE_BLOCK = "Блок (из подписки)"
    const val RULE_PROXY = "Через VPN (из подписки)"
    const val RULE_DIRECT = "Напрямую (из подписки)"
    const val RULE_LAN = "Локальная сеть"
    const val RULE_REST_DIRECT = "Остальное напрямую (из подписки)"
    /** Follows a rule's name when the provider's addresses make a rule of their own. */
    const val IP_SUFFIX = " · IP"

    data class ParsedRouting(
        val name: String,
        val rules: List<RoutingRuleDto>,
        val domainStrategy: String = "AsIs",
        val remoteDns: String = "",
        val domesticDns: String = "",
        val rawJson: String = "",
        val activate: Boolean = true,
        /** Happ `Geoipurl` — the provider's own geoip.dat. */
        val geoipUrl: String = "",
        /** Happ `Geositeurl` — the provider's own geosite.dat. */
        val geositeUrl: String = "",
        /** Happ `LastUpdated` — bumped by the provider when the lists change. */
        val geoVersion: String = "",
        /** Happ `DnsHosts` as a flat JSON object. */
        val dnsHostsJson: String = ""
    )

    fun extractFromBodyAndHeaders(body: String, routingHeader: String = ""): ParsedRouting? {
        // 1) HTTP header
        parseRoutingLink(routingHeader)?.let { return it }

        // Providers often encode the whole response as Base64. The metadata lines the
        // repository prepends (#support-url: https://...) contain URLs, so the base64
        // test has to run on the payload alone or it never fires.
        val payload = body.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .joinToString(separator = "\n")
        val decodedBody = if (!payload.contains("://") && !payload.trimStart().startsWith("{")) {
            decodeBase64Text(payload).orEmpty()
        } else ""
        val searchableBody = listOf(body, decodedBody).filter { it.isNotBlank() }.joinToString("\n")

        // 2) happ:// links in body
        val happLink = Regex(
            """happ://routing/(onadd|add)/([A-Za-z0-9+/=_\-]+)""",
            RegexOption.IGNORE_CASE
        ).find(searchableBody)
        if (happLink != null) {
            val mode = happLink.groupValues[1]
            val b64 = happLink.groupValues[2]
            decodeBase64Json(b64)?.let { json ->
                return parseHappJson(json, activate = mode.equals("onadd", ignoreCase = true))
            }
        }

        // 3) Full body is Happ routing JSON
        val trimmed = searchableBody.trim()
        if (trimmed.startsWith("{") && (
                trimmed.contains("DirectSites", ignoreCase = true) ||
                    trimmed.contains("ProxySites", ignoreCase = true) ||
                    trimmed.contains("BlockSites", ignoreCase = true) ||
                    trimmed.contains("GlobalProxy", ignoreCase = true)
                )
        ) {
            return parseHappJson(trimmed, activate = true)
        }

        // 4) #routing-profile: {...} — без regex: ICU на Android падает на \{.*?}
        extractRoutingProfileJson(searchableBody)?.let { json ->
            return parseAnyJson(json, activate = true)
        }

        // 5) Full Xray config with routing
        if (trimmed.startsWith("{") && trimmed.contains("\"routing\"")) {
            try {
                val root = JsonParser.parseString(trimmed).asJsonObject
                val routing = root.getAsJsonObject("routing") ?: return null
                return parseXrayRouting(routing, activate = true)
            } catch (_: Exception) {
                // fallthrough
            }
        }

        // 6) Bare Xray routing object
        if (trimmed.startsWith("{") && trimmed.contains("\"rules\"")) {
            try {
                val obj = JsonParser.parseString(trimmed).asJsonObject
                if (obj.has("rules")) return parseXrayRouting(obj, activate = true)
            } catch (_: Exception) {
            }
        }

        return null
    }

    /** True when the object uses Happ's own DirectSites/ProxySites/BlockSites shape. */
    fun isHappSchema(o: JsonObject): Boolean =
        o.has("DirectSites") || o.has("ProxySites") || o.has("BlockSites") ||
            o.has("GlobalProxy") || o.has("DirectIp") || o.has("ProxyIp") || o.has("BlockIp")

    fun extractRoutingProfileJson(text: String): String? {
        val marker = "#routing-profile:"
        val idx = text.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        val brace = text.indexOf('{', startIndex = idx + marker.length)
        if (brace < 0) return null
        return extractJsonObject(text, brace)
    }

    private fun extractJsonObject(text: String, start: Int): String? {
        if (start !in text.indices || text[start] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun parseRoutingLink(link: String): ParsedRouting? {
        val raw = link.trim()
        if (raw.isBlank()) return null

        val b64 = when {
            raw.contains("happ://routing/", ignoreCase = true) ->
                raw.substringAfterLast('/').trim()
            raw.startsWith("base64:", ignoreCase = true) ->
                raw.substringAfter(':')
            else -> raw
        }
        if (b64.length < 8) return null
        val json = decodeBase64Json(b64) ?: return null
        val activate = raw.contains("onadd", ignoreCase = true) || !raw.contains("/add/")
        return parseHappJson(json, activate = activate)
            ?: parseAnyJson(json, activate = activate)
    }

    fun parseHappJson(json: String, activate: Boolean = true): ParsedRouting? {
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            val isHapp = isHappSchema(o)
            if (!isHapp && o.has("rules")) {
                return parseXrayRouting(o, activate)
            }
            if (!isHapp && !o.has("Name")) {
                return parseAnyJson(json, activate)
            }

            val name = o.str("Name").ifBlank { DEFAULT_NAME }
            val domainStrategy = o.str("DomainStrategy").ifBlank { "AsIs" }
            val remoteDns = dnsAddress(
                type = o.str("RemoteDNSType"),
                domain = o.str("RemoteDNSDomain"),
                ip = o.str("RemoteDNSIP")
            ).ifBlank { o.str("RemoteDns") }
            val domesticDns = dnsAddress(
                type = o.str("DomesticDNSType"),
                domain = o.str("DomesticDNSDomain"),
                ip = o.str("DomesticDNSIP")
            ).ifBlank { o.str("DomesticDns") }

            val rules = mutableListOf<RoutingRuleDto>()
            var order = 0

            // Xray ANDs `domain` and `ip` inside one rule, so a provider list of
            // domains plus a list of IPs has to become two rules or it matches nothing.
            fun addList(sitesKey: String, ipsKey: String, outbound: RouteOutbound, label: String) {
                val sites = o.strList(sitesKey).map { normalizeDomain(it) }.filter { it.isNotBlank() }
                val ips = o.strList(ipsKey).filter { it.isNotBlank() }
                if (sites.isNotEmpty()) {
                    rules += RoutingRuleDto(
                        id = UUID.randomUUID().toString(),
                        name = label,
                        enabled = true,
                        outbound = outbound,
                        domains = sites,
                        source = "provider",
                        sortOrder = order++
                    )
                }
                if (ips.isNotEmpty()) {
                    rules += RoutingRuleDto(
                        id = UUID.randomUUID().toString(),
                        name = label + IP_SUFFIX,
                        enabled = true,
                        outbound = outbound,
                        ips = ips,
                        source = "provider",
                        sortOrder = order++
                    )
                }
            }

            // Order from RouteOrder or default block → proxy → direct extras, then global
            val routeOrder = o.str("RouteOrder").lowercase()
            val steps = if (routeOrder.isNotBlank()) {
                routeOrder.split('-', ',', ' ').filter { it.isNotBlank() }
            } else {
                listOf("block", "proxy", "direct")
            }

            steps.forEach { step ->
                when (step) {
                    "block" -> addList("BlockSites", "BlockIp", RouteOutbound.BLOCK, RULE_BLOCK)
                    "proxy" -> addList("ProxySites", "ProxyIp", RouteOutbound.PROXY, RULE_PROXY)
                    "direct" -> addList("DirectSites", "DirectIp", RouteOutbound.DIRECT, RULE_DIRECT)
                }
            }
            // If empty steps still try all
            if (rules.isEmpty()) {
                addList("BlockSites", "BlockIp", RouteOutbound.BLOCK, RULE_BLOCK)
                addList("ProxySites", "ProxyIp", RouteOutbound.PROXY, RULE_PROXY)
                addList("DirectSites", "DirectIp", RouteOutbound.DIRECT, RULE_DIRECT)
            }

            // Private always direct hint
            rules.add(
                0,
                RoutingRuleDto(
                    id = UUID.randomUUID().toString(),
                    name = RULE_LAN,
                    outbound = RouteOutbound.DIRECT,
                    ips = listOf("geoip:private"),
                    source = "provider",
                    sortOrder = -1
                )
            )

            // GlobalProxy=false means the provider's default route is direct; without a
            // trailing catch-all the app's own "everything else -> proxy" rule wins.
            val globalProxy = o.bool("GlobalProxy", default = true)
            if (!globalProxy) {
                rules += RoutingRuleDto(
                    id = UUID.randomUUID().toString(),
                    name = RULE_REST_DIRECT,
                    outbound = RouteOutbound.DIRECT,
                    network = "tcp,udp",
                    source = "provider",
                    sortOrder = order++
                )
            }

            ParsedRouting(
                name = name,
                rules = rules.mapIndexed { i, r -> r.copy(sortOrder = i) },
                domainStrategy = domainStrategy,
                remoteDns = remoteDns,
                domesticDns = domesticDns,
                rawJson = json,
                activate = activate,
                geoipUrl = o.str("Geoipurl").ifBlank { o.str("GeoipUrl") }.trim(),
                geositeUrl = o.str("Geositeurl").ifBlank { o.str("GeositeUrl") }.trim(),
                geoVersion = o.str("LastUpdated").trim(),
                dnsHostsJson = dnsHosts(o)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Happ describes a DNS server as type + domain + ip. DoH wants a URL, plain DNS
     * (DoU/UDP) wants the bare address; whichever half is present fills the gap.
     */
    fun dnsAddress(type: String, domain: String, ip: String): String {
        val d = domain.trim()
        val i = ip.trim()
        return when (type.trim().lowercase()) {
            "dou", "udp", "dns", "plain" -> i.ifBlank { d.substringAfter("://").substringBefore('/') }
            "dot", "tls" -> when {
                d.isNotBlank() -> if (d.contains("://")) d else "tls://$d"
                i.isNotBlank() -> "tls://$i"
                else -> ""
            }

            "doh", "https" -> when {
                d.startsWith("https://", ignoreCase = true) -> d
                d.isNotBlank() -> "https://$d/dns-query"
                i.isNotBlank() -> "https://$i/dns-query"
                else -> ""
            }

            else -> d.ifBlank { i }
        }
    }

    /** `DnsHosts` arrives either as an object or as a JSON string holding one. */
    private fun dnsHosts(o: JsonObject): String {
        if (!o.has("DnsHosts") || o.get("DnsHosts").isJsonNull) return ""
        val el = o.get("DnsHosts")
        val obj = when {
            el.isJsonObject -> el.asJsonObject
            el.isJsonPrimitive -> runCatching {
                JsonParser.parseString(el.asString).asJsonObject
            }.getOrNull()
            else -> null
        } ?: return ""
        return if (obj.size() == 0) "" else obj.toString()
    }

    private fun parseXrayRouting(routing: JsonObject, activate: Boolean): ParsedRouting {
        val rulesArr = routing.getAsJsonArray("rules")
        val rules = if (rulesArr != null) {
            RoutingEngine.parseRules(rulesArr.toString())
        } else emptyList()
        return ParsedRouting(
            name = DEFAULT_NAME,
            rules = rules.map { it.copy(source = "provider") },
            domainStrategy = routing.str("domainStrategy").ifBlank { "AsIs" },
            rawJson = routing.toString(),
            activate = activate
        )
    }

    private fun parseAnyJson(json: String, activate: Boolean): ParsedRouting? {
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            when {
                o.has("DirectSites") || o.has("GlobalProxy") -> parseHappJson(json, activate)
                o.has("rules") -> parseXrayRouting(o, activate)
                o.has("routing") -> parseXrayRouting(o.getAsJsonObject("routing"), activate)
                else -> {
                    // No known rule container: bail out instead of bouncing back into
                    // RoutingEngine.parseRules, which would call us again.
                    val rules = if (isHappSchema(o)) {
                        parseHappJson(json, activate)?.rules.orEmpty()
                    } else {
                        emptyList()
                    }
                    if (rules.isEmpty()) null
                    else ParsedRouting(
                        name = o.str("Name").ifBlank { DEFAULT_NAME },
                        rules = rules.map { it.copy(source = "provider") },
                        rawJson = json,
                        activate = activate
                    )
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Adds the `domain:` marker only to bare host names. Prefixed entries, wildcards,
     * regexes, external lists and anything that is really an IP/CIDR pass through —
     * `domain:0.0.0.0/0` would silently never match.
     */
    private fun normalizeDomain(d: String): String {
        val t = d.trim()
        if (t.isEmpty()) return t
        if (DOMAIN_PREFIXES.any { t.startsWith(it, ignoreCase = true) }) return t
        if (t.startsWith("*")) return "domain:" + t.removePrefix("*").removePrefix(".")
        // Looks like an address, not a host name.
        if (t.contains('/') || t.contains(':') || t.all { it in '0'..'9' || it == '.' }) return ""
        return "domain:$t"
    }

    private fun JsonObject.bool(key: String, default: Boolean): Boolean {
        if (!has(key) || get(key).isJsonNull) return default
        val el = get(key)
        if (!el.isJsonPrimitive) return default
        return runCatching { el.asBoolean }.getOrElse {
            when (el.asString.lowercase()) {
                "true", "1", "yes" -> true
                "false", "0", "no" -> false
                else -> default
            }
        }
    }

    private fun decodeBase64Json(b64: String): String? {
        val cleaned = b64.replace("\\s".toRegex(), "")
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            val bytes = try {
                Base64.decode(padded, Base64.DEFAULT)
            } catch (_: Exception) {
                Base64.decode(padded, Base64.URL_SAFE)
            }
            String(bytes, StandardCharsets.UTF_8).trim().takeIf { it.startsWith("{") }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64Text(value: String): String? {
        val cleaned = value.trim().replace("\\s".toRegex(), "")
        if (cleaned.length < 12) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return runCatching {
            String(Base64.decode(padded, Base64.DEFAULT or Base64.NO_WRAP), StandardCharsets.UTF_8)
        }.getOrNull()?.takeIf { decoded ->
            decoded.contains("://") || decoded.trimStart().startsWith("{")
        }
    }

    private fun JsonObject.str(key: String): String {
        if (!has(key) || get(key).isJsonNull) return ""
        val el = get(key)
        return when {
            el.isJsonPrimitive -> el.asString
            else -> ""
        }
    }

    private fun JsonObject.strList(key: String): List<String> {
        if (!has(key) || get(key).isJsonNull) return emptyList()
        val el = get(key)
        return when {
            el.isJsonArray -> el.asJsonArray.flatMap { item ->
                if (!item.isJsonPrimitive) emptyList()
                else item.asString.split('\n', '\r')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
            el.isJsonPrimitive -> el.asString
                .split(',', ';', '\n', '\r')
                .map { it.trim() }.filter { it.isNotEmpty() }
            else -> emptyList()
        }
    }
}
