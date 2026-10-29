package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Converts app routing profiles into Xray routing objects, merges provider rules
 * with user overrides and serializes them for storage.
 *
 * Merge contract (this is what keeps subscriptions isolated):
 *  - `profileRules` come from exactly one profile, which belongs to at most one subscription;
 *  - `providerRoutingJson` is only ever the JSON of that same subscription;
 *  - a profile with `useProviderRules = false` is an explicit override and provider
 *    rules are not consulted at all, even when the caller passes them.
 */
object RoutingEngine {

    private val gson = Gson()
    private val listType = object : TypeToken<List<RoutingRuleDto>>() {}.type

    fun parseRules(json: String): List<RoutingRuleDto> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            if (json.trimStart().startsWith("[")) {
                val first = json.trimStart().drop(1).trimStart()
                if (first.startsWith("{") && json.contains("\"outbound\"")) {
                    gson.fromJson(json, listType) ?: emptyList()
                } else {
                    xrayRulesToDto(JsonParser.parseString(json).asJsonArray)
                }
            } else {
                val obj = JsonParser.parseString(json).asJsonObject
                val rules = obj.getAsJsonArray("rules")
                    ?: obj.getAsJsonObject("routing")?.getAsJsonArray("rules")
                if (rules != null) {
                    xrayRulesToDto(rules)
                } else if (HappRoutingParser.isHappSchema(obj)) {
                    // Provider shipped its rules in Happ's own shape.
                    HappRoutingParser.parseHappJson(json, activate = false)?.rules.orEmpty()
                } else {
                    emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun serializeRules(rules: List<RoutingRuleDto>): String = gson.toJson(rules)

    fun importRules(json: String): List<RoutingRuleDto> {
        val element = JsonParser.parseString(json)
        val rulesElement = when {
            element.isJsonArray -> element
            element.isJsonObject && element.asJsonObject.has("routing") ->
                element.asJsonObject.getAsJsonObject("routing").get("rules")

            element.isJsonObject -> element.asJsonObject.get("rules")
            else -> null
        } ?: throw IllegalArgumentException(
            "JSON должен содержать массив правил или объект routing.rules"
        )

        require(rulesElement.isJsonArray) { "Поле rules должно быть массивом" }
        if (rulesElement.asJsonArray.size() == 0) return emptyList()

        val text = rulesElement.toString()
        return if (text.contains("\"outbound\"")) {
            gson.fromJson<List<RoutingRuleDto>>(text, listType) ?: emptyList()
        } else {
            xrayRulesToDto(rulesElement.asJsonArray)
        }
    }

    /**
     * User rules win, then the subscription's own rules, then whatever built-in
     * rules the profile carries. Duplicates collapse on their matching criteria.
     */
    fun mergeRules(
        profileRules: List<RoutingRuleDto>,
        useProviderRules: Boolean,
        providerRoutingJson: String,
        serverJsonRouting: String
    ): List<RoutingRuleDto> {
        val userRules = profileRules.filter { it.source == "user" && it.enabled }
        val storedProvider = profileRules.filter { it.source == "provider" }
        val provider = if (!useProviderRules) {
            emptyList()
        } else {
            when {
                storedProvider.isNotEmpty() -> storedProvider
                providerRoutingJson.isNotBlank() -> parseRules(providerRoutingJson)
                serverJsonRouting.isNotBlank() -> parseRules(serverJsonRouting)
                else -> emptyList()
            }.map { it.copy(source = "provider") }
        }
        val builtin = profileRules.filter { it.source == "builtin" }

        val seen = mutableSetOf<String>()
        val merged = mutableListOf<RoutingRuleDto>()
        (userRules + provider + builtin).forEach { r ->
            if (!r.enabled) return@forEach
            val key = listOf(
                r.outbound, r.domains.sorted(), r.ips.sorted(), r.ports, r.sourcePorts,
                r.network, r.protocol.sorted(), r.inboundTags.sorted(), r.users.sorted(), r.attributes
            ).joinToString("|")
            if (seen.add(key)) merged += r
        }
        return merged.mapIndexed { i, r -> r.copy(sortOrder = i) }
    }

    /**
     * @param proxyBalancerTag when set, PROXY rules target that balancer instead of
     *        the single `proxy` outbound.
     * @param dnsRuleEnabled routes plain DNS to the core's own resolver.
     */
    fun toXrayRouting(
        rules: List<RoutingRuleDto>,
        domainStrategy: String = "IPIfNonMatch",
        proxyBalancerTag: String? = null,
        dnsRuleEnabled: Boolean = true
    ): JsonObject {
        val routing = JsonObject()
        routing.addProperty("domainStrategy", domainStrategy)
        val arr = JsonArray()

        if (dnsRuleEnabled) {
            arr.add(JsonObject().apply {
                addProperty("type", "field")
                addProperty("port", "53")
                add("network", jsonArray("udp"))
                addProperty("outboundTag", "dns-out")
            })
        }

        // Private ranges never leave the device.
        arr.add(JsonObject().apply {
            addProperty("type", "field")
            add("ip", jsonArray("geoip:private"))
            addProperty("outboundTag", "direct")
        })

        rules.filter { it.enabled }.sortedBy { it.sortOrder }.forEach { rule ->
            arr.add(ruleToXray(rule, proxyBalancerTag))
        }

        // Everything else goes out through the proxy / balancer.
        arr.add(JsonObject().apply {
            addProperty("type", "field")
            add("network", jsonArray("tcp", "udp"))
            applyProxyTarget(this, proxyBalancerTag)
        })

        routing.add("rules", arr)
        return routing
    }

    fun builtInProfiles(): List<RoutingProfileEntity> {
        val now = System.currentTimeMillis()
        return listOf(
            RoutingProfileEntity(
                name = "Весь трафик через VPN",
                description = "Обычный режим: наружу идёт всё, кроме локальной сети",
                isActive = true,
                isBuiltIn = true,
                rulesJson = serializeRules(
                    listOf(
                        RoutingRuleDto(
                            name = "Локальная сеть напрямую",
                            outbound = RouteOutbound.DIRECT,
                            ips = listOf("geoip:private"),
                            source = "builtin"
                        )
                    )
                ),
                updatedAt = now
            ),
            RoutingProfileEntity(
                name = "Российские сайты напрямую",
                description = "RU-домены и «белый список» мимо VPN, остальное через VPN",
                isBuiltIn = true,
                rulesJson = serializeRules(ruBypassRules()),
                domainStrategy = "IPIfNonMatch",
                updatedAt = now
            )
        )
    }

    /**
     * Built-in sets earlier versions shipped. Ads and torrents are app-wide filters now
     * (AdGuard and the torrent switch), so these became duplicates and are removed on
     * start; whoever used one gets the matching filter switched on instead.
     */
    const val RETIRED_NO_ADS = "Без рекламы"
    const val RETIRED_STEALTH = "Максимальная маскировка"

    /** Domains known to work under Минцифры white-list (for direct rules / probes). */
    fun ruWhitelistDomains(): List<String> = listOf(
        "gosuslugi.ru", "mos.ru", "kremlin.ru", "government.ru",
        "cbr.ru", "nalog.gov.ru", "pochta.ru",
        "vk.com", "vk.ru", "ok.ru", "mail.ru", "dzen.ru", "rutube.ru",
        "yandex.ru", "ya.ru", "yandex.net",
        "ozon.ru", "wildberries.ru", "avito.ru", "megamarket.ru",
        "tinkoff.ru", "tbank.ru", "vtb.ru", "alfabank.ru", "sberbank.ru",
        "mts.ru", "megafon.ru", "beeline.ru", "tele2.ru",
        "rzd.ru", "aeroflot.ru", "hh.ru", "2gis.ru",
        "kinopoisk.ru", "ivi.ru", "okko.tv", "max.ru"
    )

    private fun ruBypassRules(): List<RoutingRuleDto> {
        val domains = ruWhitelistDomains().map { "domain:$it" } +
            listOf("geosite:category-ru", "geosite:private")
        return listOf(
            RoutingRuleDto(
                name = "Россия и «белый список» напрямую",
                outbound = RouteOutbound.DIRECT,
                domains = domains,
                ips = listOf("geoip:ru", "geoip:private"),
                source = "builtin"
            )
        )
    }

    private fun ruleToXray(rule: RoutingRuleDto, proxyBalancerTag: String?): JsonObject =
        JsonObject().apply {
            addProperty("type", "field")
            if (rule.domains.isNotEmpty()) {
                add("domain", jsonArray(*rule.domains.toTypedArray()))
            }
            if (rule.ips.isNotEmpty()) {
                add("ip", jsonArray(*rule.ips.toTypedArray()))
            }
            if (rule.ports.isNotBlank()) addProperty("port", rule.ports)
            if (rule.sourcePorts.isNotBlank()) addProperty("sourcePort", rule.sourcePorts)
            if (rule.network.isNotBlank()) addProperty("network", rule.network)
            if (rule.protocol.isNotEmpty()) {
                add("protocol", jsonArray(*rule.protocol.toTypedArray()))
            }
            if (rule.inboundTags.isNotEmpty()) {
                add("inboundTag", jsonArray(*rule.inboundTags.toTypedArray()))
            }
            if (rule.users.isNotEmpty()) add("user", jsonArray(*rule.users.toTypedArray()))
            if (rule.attributes.isNotBlank()) {
                // Providers ship `attrs` either as an object (legacy) or as an expression
                // string. Re-emit whichever shape it arrived in, or the core rejects it.
                val parsed = runCatching { JsonParser.parseString(rule.attributes) }.getOrNull()
                if (parsed != null && parsed.isJsonObject) add("attrs", parsed.asJsonObject)
                else addProperty("attrs", rule.attributes)
            }
            when (rule.outbound) {
                RouteOutbound.PROXY -> applyProxyTarget(this, proxyBalancerTag)
                RouteOutbound.DIRECT -> addProperty("outboundTag", "direct")
                RouteOutbound.BLOCK -> addProperty("outboundTag", "block")
            }
        }

    private fun applyProxyTarget(target: JsonObject, proxyBalancerTag: String?) {
        if (proxyBalancerTag.isNullOrBlank()) target.addProperty("outboundTag", "proxy")
        else target.addProperty("balancerTag", proxyBalancerTag)
    }

    private fun xrayRulesToDto(arr: JsonArray): List<RoutingRuleDto> {
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            if (o.str("type") == "field" || !o.has("type")) {
                val tag = o.str("outboundTag").ifBlank {
                    o.str("outbound").ifBlank { o.str("balancerTag") }
                }
                val outbound = when (tag.lowercase()) {
                    "direct", "freedom" -> RouteOutbound.DIRECT
                    "block", "blackhole", "blocked" -> RouteOutbound.BLOCK
                    else -> RouteOutbound.PROXY
                }
                // DNS plumbing is rebuilt by the app; importing it would pin a dead tag.
                if (tag.equals("dns-out", ignoreCase = true)) return@mapNotNull null
                RoutingRuleDto(
                    id = UUID.randomUUID().toString(),
                    name = tag.ifBlank { "rule" },
                    outbound = outbound,
                    domains = o.strList("domain"),
                    ips = o.strList("ip"),
                    ports = o.str("port"),
                    sourcePorts = o.str("sourcePort"),
                    network = o.str("network"),
                    protocol = o.strList("protocol"),
                    inboundTags = o.strList("inboundTag"),
                    users = o.strList("user"),
                    // Keep an object-shaped `attrs` as raw JSON so it round-trips.
                    attributes = o.get("attrs")?.let { el ->
                        if (el.isJsonNull) "" else if (el.isJsonPrimitive) el.asString
                        else el.toString()
                    }.orEmpty(),
                    source = "provider"
                ).takeIf {
                    it.domains.isNotEmpty() || it.ips.isNotEmpty() || it.ports.isNotBlank() ||
                        it.protocol.isNotEmpty() || it.attributes.isNotBlank()
                }
            } else null
        }
    }

    private fun jsonArray(vararg items: String): JsonArray =
        JsonArray().also { a -> items.forEach { a.add(it) } }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull) {
            val el = get(key)
            if (el.isJsonPrimitive) el.asString else ""
        } else ""

    private fun JsonObject.strList(key: String): List<String> {
        if (!has(key) || get(key).isJsonNull) return emptyList()
        val el = get(key)
        return when {
            el.isJsonArray -> el.asJsonArray.mapNotNull {
                if (it.isJsonPrimitive) it.asString else null
            }

            el.isJsonPrimitive -> listOf(el.asString)
            else -> emptyList()
        }
    }
}
