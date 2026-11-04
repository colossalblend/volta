package com.ars3nb.volta.core.routing

import android.content.Context
import android.content.res.Resources
import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.core.locale.AnyLanguage
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

    /** Pasted rules that are not rules at all; [reason] says why, for the screen. */
    class RulesFormatException(@param:StringRes val reason: Int) : IllegalArgumentException()

    // Names of the built-in sets and their rules. They are stored in the database and
    // backups find the built-in sets by them, so they stay as they always were; the
    // screen shows them through [RoutingNames].
    const val BUILTIN_ALL_NAME = "Весь трафик через VPN"
    const val BUILTIN_ALL_DESCRIPTION = "Обычный режим: наружу идёт всё, кроме локальной сети"
    const val BUILTIN_LAN_RULE = "Локальная сеть напрямую"
    const val BUILTIN_RU_NAME = "Российские сайты напрямую"
    const val BUILTIN_RU_DESCRIPTION = "RU-домены и «белый список» мимо VPN, остальное через VPN"
    const val BUILTIN_RU_RULE = "Россия и «белый список» напрямую"

    fun parseRules(json: String): List<RoutingRuleDto> {
        if (json.isBlank() || json == "[]") return emptyList()
        return try {
            if (json.trimStart().startsWith("[")) {
                val first = json.trimStart().drop(1).trimStart()
                if (first.startsWith("{") && json.contains("\"outbound\"")) {
                    sanitize(gson.fromJson(json, listType) ?: emptyList())
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

    /**
     * Gson fills a Kotlin class by reflection, past its types: a field the JSON gives as
     * null comes out null, an outbound this build doesn't know comes out null, and ids
     * may repeat. Rules from a provider or a pasted file are made whole here, so neither
     * the config builder (a null list) nor the rules screen (a list keyed by id: a repeat
     * crashes it) trips over them. A rule whose outbound is unknown is left out.
     */
    @Suppress("USELESS_CAST", "USELESS_ELVIS", "SENSELESS_COMPARISON")
    internal fun sanitize(rules: List<RoutingRuleDto?>): List<RoutingRuleDto> {
        val seen = HashSet<String>()
        fun strings(list: List<String>?): List<String> =
            (list as List<String?>?)?.filterNotNull().orEmpty()
        return rules.filterNotNull().mapNotNull { r ->
            val outbound = (r.outbound as RouteOutbound?) ?: return@mapNotNull null
            val id = (r.id as String?)?.takeIf { it.isNotBlank() && it !in seen }
                ?: java.util.UUID.randomUUID().toString()
            seen += id
            RoutingRuleDto(
                id = id,
                name = (r.name as String?).orEmpty(),
                enabled = r.enabled,
                outbound = outbound,
                domains = strings(r.domains),
                ips = strings(r.ips),
                ports = (r.ports as String?).orEmpty(),
                sourcePorts = (r.sourcePorts as String?).orEmpty(),
                network = (r.network as String?).orEmpty(),
                protocol = strings(r.protocol),
                inboundTags = strings(r.inboundTags),
                users = strings(r.users),
                attributes = (r.attributes as String?).orEmpty(),
                source = (r.source as String?)?.takeIf { it.isNotBlank() } ?: "user",
                sortOrder = r.sortOrder
            )
        }
    }

    fun importRules(json: String): List<RoutingRuleDto> {
        val element = JsonParser.parseString(json)
        val rulesElement = when {
            element.isJsonArray -> element
            element.isJsonObject && element.asJsonObject.has("routing") ->
                element.asJsonObject.getAsJsonObject("routing").get("rules")

            element.isJsonObject -> element.asJsonObject.get("rules")
            else -> null
        } ?: throw RulesFormatException(R.string.routing_import_no_rules)

        if (!rulesElement.isJsonArray) throw RulesFormatException(R.string.routing_import_rules_not_array)
        if (rulesElement.asJsonArray.size() == 0) return emptyList()

        val text = rulesElement.toString()
        return if (text.contains("\"outbound\"")) {
            sanitize(gson.fromJson<List<RoutingRuleDto?>>(text, listType) ?: emptyList())
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
     * @param dnsAddress the DNS server the tunnel names to Android (nothing listens
     *        there): its lookups go to the core over UDP and TCP, the rest is refused.
     */
    fun toXrayRouting(
        rules: List<RoutingRuleDto>,
        domainStrategy: String = "IPIfNonMatch",
        proxyBalancerTag: String? = null,
        dnsRuleEnabled: Boolean = true,
        dnsAddress: String? = null,
        /** Inbound whose traffic always goes to the exit, ahead of every profile rule. */
        exitInboundTag: String? = null
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
            if (dnsAddress != null) {
                // Android repeats a lookup over TCP when the answer is too long for UDP.
                arr.add(JsonObject().apply {
                    addProperty("type", "field")
                    add("ip", jsonArray(dnsAddress))
                    addProperty("port", "53")
                    add("network", jsonArray("tcp"))
                    addProperty("outboundTag", "dns-out")
                })
                // Everything else sent there, above all Android checking it for
                // DNS-over-TLS on port 853, is turned away at once rather than left to
                // time out, so Android settles on the plain lookups the core answers.
                arr.add(JsonObject().apply {
                    addProperty("type", "field")
                    add("ip", jsonArray(dnsAddress))
                    addProperty("outboundTag", "block")
                })
            }
        }

        // Ahead of every profile rule: the exit-address check always leaves via the exit.
        if (exitInboundTag != null) {
            arr.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", jsonArray(exitInboundTag))
                applyProxyTarget(this, proxyBalancerTag)
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
                name = BUILTIN_ALL_NAME,
                description = BUILTIN_ALL_DESCRIPTION,
                isActive = true,
                isBuiltIn = true,
                rulesJson = serializeRules(
                    listOf(
                        RoutingRuleDto(
                            name = BUILTIN_LAN_RULE,
                            outbound = RouteOutbound.DIRECT,
                            ips = listOf("geoip:private"),
                            source = "builtin"
                        )
                    )
                ),
                updatedAt = now
            ),
            RoutingProfileEntity(
                name = BUILTIN_RU_NAME,
                description = BUILTIN_RU_DESCRIPTION,
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
     * start; whoever used one gets the matching filter switched on instead. Matched
     * against stored names, so never translated.
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
                name = BUILTIN_RU_RULE,
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

/**
 * Names Volta gives the sets and rules it makes itself, and how the screen shows them.
 *
 * The names are stored with the sets and rules, and things are found by them: a ready-made
 * list counts as added when a rule bears its name, quick-added sites gather in the rule
 * named for their direction, a backup finds the built-in sets by name. So the stored text
 * never changes with the language (it stays the Russian it has always been) and only what
 * the screen shows is translated. A name the user typed is shown as it is.
 */
object RoutingNames {

    /** Rules the quick-add field fills, one per direction. */
    const val MY_SITES_VPN = "Свои сайты через VPN"
    const val MY_SITES_DIRECT = "Свои сайты напрямую"
    const val BLOCKED_SITES = "Заблокированные сайты"

    /** An empty rule the user is about to fill in. */
    const val NEW_RULE = "Новое правило"

    private val rules: Map<String, Int> by lazy {
        mapOf(
            MY_SITES_VPN to R.string.routing_rule_my_sites_vpn,
            MY_SITES_DIRECT to R.string.routing_rule_my_sites_direct,
            BLOCKED_SITES to R.string.routing_rule_blocked_sites,
            NEW_RULE to R.string.routing_rule_new,
            RoutingEngine.BUILTIN_LAN_RULE to R.string.routing_builtin_lan_rule,
            RoutingEngine.BUILTIN_RU_RULE to R.string.routing_builtin_ru_rule,
            HappRoutingParser.RULE_BLOCK to R.string.routing_sub_rule_block,
            HappRoutingParser.RULE_PROXY to R.string.routing_sub_rule_vpn,
            HappRoutingParser.RULE_DIRECT to R.string.routing_sub_rule_direct,
            HappRoutingParser.RULE_LAN to R.string.routing_sub_rule_lan,
            HappRoutingParser.RULE_REST_DIRECT to R.string.routing_sub_rule_rest_direct
        ) + Presets.sites.mapNotNull { preset -> preset.label?.let { preset.title to it } }
    }

    /**
     * What to show for a rule's stored [name]: the resource and whether " · IP" follows
     * it (a subscription's list of addresses), or null for a name of the user's own.
     */
    fun rule(name: String): Pair<Int, Boolean>? {
        rules[name]?.let { return it to false }
        if (name.endsWith(HappRoutingParser.IP_SUFFIX)) {
            rules[name.removeSuffix(HappRoutingParser.IP_SUFFIX)]?.let { return it to true }
        }
        return null
    }

    fun ruleName(name: String, res: Resources): String =
        rule(name)?.let { (id, ip) -> res.getString(id) + if (ip) HappRoutingParser.IP_SUFFIX else "" } ?: name

    @StringRes
    fun profileName(profile: RoutingProfileEntity): Int? = when {
        profile.isBuiltIn && profile.name == RoutingEngine.BUILTIN_ALL_NAME -> R.string.routing_builtin_all_name
        profile.isBuiltIn && profile.name == RoutingEngine.BUILTIN_RU_NAME -> R.string.routing_builtin_ru_name
        profile.sourceSubscriptionId != null && profile.name == HappRoutingParser.DEFAULT_NAME ->
            R.string.routing_sub_rules_name
        else -> null
    }

    /**
     * The set's name as shown, in the language of [context]. Besides the built-in sets this
     * knows the names Volta made in the language of the day and stored: "My VPN · rules"
     * for a subscription's set and "… · copy" for a copy, in any language (older builds
     * stored them in Russian). Anything else is the user's or the provider's own name.
     */
    fun profileName(profile: RoutingProfileEntity, context: Context): String {
        profileName(profile)?.let { return context.getString(it) }
        val name = profile.name
        if (profile.isBuiltIn) return name
        if (profile.sourceSubscriptionId != null) {
            return relabel(context, R.string.home_routing_rules_name, name) { subscriptionName(context, it) } ?: name
        }
        return relabel(context, R.string.home_routing_copy_name, name) { base -> shownBaseName(base, context) } ?: name
    }

    @StringRes
    fun profileDescription(profile: RoutingProfileEntity): Int? = when {
        !profile.isBuiltIn -> null
        profile.description == RoutingEngine.BUILTIN_ALL_DESCRIPTION -> R.string.routing_builtin_all_description
        profile.description == RoutingEngine.BUILTIN_RU_DESCRIPTION -> R.string.routing_builtin_ru_description
        else -> null
    }

    /** The set's description as shown; see [profileName]. */
    fun profileDescription(profile: RoutingProfileEntity, context: Context): String {
        profileDescription(profile)?.let { return context.getString(it) }
        val text = profile.description
        if (profile.isBuiltIn || text.isBlank()) return text
        if (profile.sourceSubscriptionId != null) {
            return relabel(context, R.string.home_routing_from_subscription, text) { subscriptionName(context, it) }
                ?: text
        }
        return relabel(context, R.string.home_routing_copy_description, text) { base -> shownBaseName(base, context) }
            ?: text
    }

    /** A name a copy was made from: a built-in set's or "… · rules", shown in today's language. */
    private fun shownBaseName(name: String, context: Context): String = when (name) {
        RoutingEngine.BUILTIN_ALL_NAME -> context.getString(R.string.routing_builtin_all_name)
        RoutingEngine.BUILTIN_RU_NAME -> context.getString(R.string.routing_builtin_ru_name)
        HappRoutingParser.DEFAULT_NAME -> context.getString(R.string.routing_sub_rules_name)
        else -> relabel(context, R.string.home_routing_rules_name, name) { subscriptionName(context, it) } ?: name
    }

    /**
     * A subscription's name as shown: the default name Volta gives a nameless subscription
     * ("Subscription", stored by older builds as "Подписка") in the language of [context];
     * a name the user typed or the provider sent as it is.
     */
    fun subscriptionName(context: Context, name: String): String =
        AnyLanguage.translated(context, R.string.home_default_subscription_name, name)

    /**
     * [text] made from the one-argument string [id] in some language, made again in the
     * language of [context] (its argument passed through [argument]); null when it isn't.
     */
    private fun relabel(
        context: Context,
        @StringRes id: Int,
        text: String,
        argument: (String) -> String = { it }
    ): String? {
        val arg = AnyLanguage.argumentIn(context.getString(id), text)
            ?: AnyLanguage.argumentOf(context, id, text)
            ?: return null
        return context.getString(id, argument(arg))
    }
}
