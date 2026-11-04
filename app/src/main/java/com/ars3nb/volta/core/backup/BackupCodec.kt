package com.ars3nb.volta.core.backup

import android.content.res.Resources
import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.core.link.SubCrypto
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DecimalStyle
import java.util.Locale

/**
 * The backup file: everything worth taking to a new phone, as one JSON file the user
 * keeps wherever they like. Android's own backup is off for this app (allowBackup),
 * so without it a new phone starts from nothing.
 *
 * What goes in: subscriptions (address, name, colour, groups markup, chosen rules),
 * servers added by hand, the user's own rule sets and edited built-in ones, and the
 * settings. What stays out: servers of subscriptions (a refresh brings them back),
 * provider rule sets (likewise), measurements, and the app's bookkeeping.
 *
 * A subscription whose provider keeps the link private ([SubscriptionEntity.urlHidden])
 * is written encrypted with Volta's own key, the same way as a `volta://crypt` link, so
 * the file does not show it in plain text. Like those links, that keeps it from being
 * read or pasted elsewhere, not from someone who takes the app apart.
 *
 * Reading is forgiving: a broken or unknown entry is skipped and counted, and fields
 * missing from an older file take the app's defaults.
 */
object BackupCodec {

    const val FORMAT = "volta-backup"
    const val VERSION = 1

    /** Bigger than any real backup, small enough to read into memory. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /** A file Volta won't take; [reason] says why, in the user's language. */
    class BackupException(@param:StringRes val reason: Int) : Exception("not a usable backup file")

    /** What the app hands over to be written. */
    data class Snapshot(
        val subscriptions: List<SubscriptionEntity>,
        /** Subscription id → name of the app-level rule set it is bound to. */
        val subscriptionRouting: Map<Long, String>,
        /** Servers added by hand (no subscription). */
        val servers: List<ServerEntity>,
        /** App-level rule sets worth keeping (see [worthKeeping]). */
        val routing: List<RoutingProfileEntity>,
        val activeRouting: String?,
        val settings: Map<String, Any>,
        val appVersion: String,
        val createdAt: Long
    )

    data class RestoredSubscription(val entity: SubscriptionEntity, val routingName: String?)

    /** What a file held, ready to be put into the app. Ids are all 0. */
    data class Contents(
        val createdAt: Long,
        val subscriptions: List<RestoredSubscription>,
        val servers: List<ServerEntity>,
        val routing: List<RoutingProfileEntity>,
        val activeRouting: String?,
        val settings: Map<String, Any?>,
        /** Entries that could not be read: damaged, or from a newer app. */
        val skipped: Int
    ) {
        val isEmpty: Boolean
            get() = subscriptions.isEmpty() && servers.isEmpty() && routing.isEmpty() && settings.isEmpty()
    }

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create()

    /** Subscription fields a backup carries. The address is handled on its own. */
    private val SUBSCRIPTION_FIELDS = listOf(
        "name", "nameLocked", "userAgent", "updateIntervalHours", "isEnabled", "sortOrder",
        "hwidEnabled", "colorHex", "serverTypes"
    )

    /** Server fields that are measurements or links to this phone's rows, not config. */
    private val SERVER_LOCAL_FIELDS = setOf(
        "id", "subscriptionId", "latencyMs", "ewmaMs", "failStreak", "lastPingAt", "isSelected"
    )

    /** Rule-set fields a backup carries. Provider geodata belongs to a subscription. */
    private val ROUTING_FIELDS = listOf(
        "name", "description", "isBuiltIn", "useProviderRules", "rulesJson", "domainStrategy",
        "remoteDns", "domesticDns", "dnsHostsJson"
    )

    private const val HIDDEN_URL = "hiddenUrl"
    private const val ROUTING_NAME = "routing"

    // ------------------------------------------------------------------ writing

    fun encode(snapshot: Snapshot, encryptHidden: (String) -> String = { SubCrypto.encrypt(it) }): String {
        val root = JsonObject()
        root.addProperty("format", FORMAT)
        root.addProperty("version", VERSION)
        root.addProperty("createdAt", snapshot.createdAt)
        root.addProperty("app", snapshot.appVersion)

        root.add("subscriptions", JsonArray().apply {
            snapshot.subscriptions.forEach { sub ->
                val tree = gson.toJsonTree(sub).asJsonObject
                val out = JsonObject()
                SUBSCRIPTION_FIELDS.forEach { key -> tree.get(key)?.let { out.add(key, it) } }
                if (sub.urlHidden) {
                    out.addProperty(HIDDEN_URL, encryptHidden(sub.url))
                } else {
                    out.addProperty("url", sub.url)
                }
                snapshot.subscriptionRouting[sub.id]?.let { out.addProperty(ROUTING_NAME, it) }
                add(out)
            }
        })

        root.add("servers", JsonArray().apply {
            snapshot.servers.forEach { server ->
                val tree = gson.toJsonTree(server).asJsonObject
                SERVER_LOCAL_FIELDS.forEach { tree.remove(it) }
                add(tree)
            }
        })

        root.add("routing", JsonArray().apply {
            snapshot.routing.forEach { profile ->
                val tree = gson.toJsonTree(profile).asJsonObject
                val out = JsonObject()
                ROUTING_FIELDS.forEach { key -> tree.get(key)?.let { out.add(key, it) } }
                add(out)
            }
        })
        snapshot.activeRouting?.let { root.addProperty("activeRouting", it) }
        root.add("settings", gson.toJsonTree(snapshot.settings))
        return gson.toJson(root)
    }

    /**
     * A built-in rule set goes into the backup only when the user changed it: an
     * untouched one is the same on the new phone, or newer there.
     */
    fun worthKeeping(
        profile: RoutingProfileEntity,
        defaults: List<RoutingProfileEntity> = RoutingEngine.builtInProfiles()
    ): Boolean {
        if (profile.sourceSubscriptionId != null) return false
        if (!profile.isBuiltIn) return true
        val stock = defaults.firstOrNull { it.name == profile.name } ?: return false
        // Rule ids are random per install; everything else must match.
        fun rules(json: String) = RoutingEngine.parseRules(json).map { it.copy(id = "") }
        return rules(profile.rulesJson) != rules(stock.rulesJson) ||
            profile.domainStrategy != stock.domainStrategy ||
            profile.remoteDns != stock.remoteDns ||
            profile.domesticDns != stock.domesticDns
    }

    /** The file name offered when saving: `volta-2026-11-04.json`. */
    fun fileName(date: LocalDate): String = "volta-$date.json"

    // ------------------------------------------------------------------ reading

    fun decode(text: String, decryptHidden: (String) -> String = { SubCrypto.decrypt(it) }): Contents {
        val root = runCatching { JsonParser.parseString(text.trim().removePrefix("﻿")) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: throw BackupException(R.string.settings_backup_error_damaged)
        if (root.stringOrNull("format") != FORMAT) {
            throw BackupException(R.string.settings_backup_error_not_volta)
        }
        val version = runCatching { root.get("version")?.asInt }.getOrNull() ?: 0
        if (version > VERSION) {
            throw BackupException(R.string.settings_backup_error_newer)
        }

        var skipped = 0
        val subscriptions = root.objects("subscriptions").mapNotNull { obj ->
            readSubscription(obj, decryptHidden).also { if (it == null) skipped++ }
        }
        val servers = root.objects("servers").mapNotNull { obj ->
            readServer(obj).also { if (it == null) skipped++ }
        }
        val routing = root.objects("routing").mapNotNull { obj ->
            readRouting(obj).also { if (it == null) skipped++ }
        }
        // Counted as entries, not as parts of the file: a damaged entry is still one.
        skipped += root.nonObjects("subscriptions") + root.nonObjects("servers") + root.nonObjects("routing")

        return Contents(
            createdAt = runCatching { root.get("createdAt")?.asLong }.getOrNull() ?: 0L,
            subscriptions = subscriptions,
            servers = servers,
            routing = routing,
            activeRouting = root.stringOrNull("activeRouting")?.takeIf { it.isNotBlank() },
            settings = readSettings(root.get("settings")),
            skipped = skipped
        )
    }

    private fun readSubscription(obj: JsonObject, decryptHidden: (String) -> String): RestoredSubscription? {
        val hidden = obj.stringOrNull(HIDDEN_URL)
        val url = if (hidden != null) {
            runCatching { decryptHidden(hidden) }.getOrNull()
        } else {
            obj.stringOrNull("url")
        }?.trim()
        // Volta takes only protected links (see VoltaRepository.addSubscription).
        if (url == null || !url.startsWith("https://", ignoreCase = true) || url.length <= "https://".length) {
            return null
        }
        val merged = gson.toJsonTree(SubscriptionEntity(name = "", url = "")).asJsonObject
        SUBSCRIPTION_FIELDS.forEach { key -> obj.valueOrNull(key)?.let { merged.add(key, it) } }
        merged.addProperty("url", url)
        merged.addProperty("urlHidden", hidden != null)
        val entity = runCatching { gson.fromJson(merged, SubscriptionEntity::class.java) }.getOrNull()
            ?: return null
        return RestoredSubscription(
            entity = entity.copy(
                id = 0,
                // A nameless one gets the default name in the app's language (BackupManager).
                name = entity.name.trim(),
                routingProfileId = 0
            ),
            routingName = obj.stringOrNull(ROUTING_NAME)?.takeIf { it.isNotBlank() }
        )
    }

    private fun readServer(obj: JsonObject): ServerEntity? {
        // A protocol this version does not know cannot be dialled: the entry is skipped.
        val protocol = obj.stringOrNull("protocol")
        if (protocol == null || ProtocolType.entries.none { it.name == protocol } ||
            protocol == ProtocolType.UNKNOWN.name
        ) {
            return null
        }
        val merged = gson.toJsonTree(ServerEntity(remark = "", address = "", port = 0)).asJsonObject
        obj.entrySet().forEach { (key, value) ->
            if (key !in SERVER_LOCAL_FIELDS && merged.has(key) && !value.isJsonNull) merged.add(key, value)
        }
        // An unknown group falls back to the ordinary one rather than losing the server.
        val role = obj.stringOrNull("role")
        if (role == null || ServerRole.entries.none { it.name == role }) {
            merged.addProperty("role", ServerRole.NORMAL.name)
        }
        val server = runCatching { gson.fromJson(merged, ServerEntity::class.java) }.getOrNull() ?: return null
        return server.copy(id = 0, subscriptionId = null, isSelected = false)
    }

    private fun readRouting(obj: JsonObject): RoutingProfileEntity? {
        val name = obj.stringOrNull("name")?.trim()
        if (name.isNullOrBlank()) return null
        val merged = gson.toJsonTree(RoutingProfileEntity(name = name)).asJsonObject
        ROUTING_FIELDS.forEach { key -> obj.valueOrNull(key)?.let { merged.add(key, it) } }
        merged.addProperty("name", name)
        val profile = runCatching { gson.fromJson(merged, RoutingProfileEntity::class.java) }.getOrNull()
            ?: return null
        return profile.copy(id = 0, isActive = false, sourceSubscriptionId = null)
    }

    /** Settings as plain values: Boolean, String, Int or a list of strings. */
    private fun readSettings(element: JsonElement?): Map<String, Any?> {
        if (element == null || !element.isJsonObject) return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in element.asJsonObject.entrySet()) {
            out[key] = when {
                value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
                value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asNumber.toInt()
                value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString
                value.isJsonArray -> value.asJsonArray
                    .filter { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                    .map { it.asString }
                else -> continue
            }
        }
        return out
    }

    // ---------------------------------------------------------------- showing

    /** The question the restore dialog asks, in plain words, in the language of [res]. */
    fun describe(contents: Contents, res: Resources, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        if (contents.createdAt > 0) {
            val date = Instant.ofEpochMilli(contents.createdAt).atZone(zone).toLocalDate()
            val locale = res.configuration.locales.get(0) ?: Locale.getDefault()
            val shown = DateTimeFormatter.ofPattern(res.getString(R.string.settings_backup_date_format), locale)
                .withDecimalStyle(DecimalStyle.of(locale))
                .format(date)
            append(res.getString(R.string.settings_backup_saved_on, shown)).append("\n\n")
        }
        append(res.getString(R.string.settings_backup_contains)).append('\n')
        fun item(text: String) = append("• ").append(text).append('\n')
        fun count(id: Int, n: Int) = item(res.getQuantityString(id, n, n))
        if (contents.subscriptions.isNotEmpty()) count(R.plurals.settings_backup_count_subscriptions, contents.subscriptions.size)
        if (contents.servers.isNotEmpty()) count(R.plurals.settings_backup_count_servers, contents.servers.size)
        if (contents.routing.isNotEmpty()) count(R.plurals.settings_backup_count_rules, contents.routing.size)
        if (contents.settings.isNotEmpty()) item(res.getString(R.string.settings_backup_item_settings))
        append('\n')
        append(
            listOfNotNull(
                res.getString(R.string.settings_backup_no_duplicates),
                res.getString(R.string.settings_backup_settings_replaced).takeIf { contents.settings.isNotEmpty() },
                res.getString(R.string.settings_backup_servers_reload).takeIf { contents.subscriptions.isNotEmpty() }
            ).joinToString(" ")
        )
    }.trim()

    /** The same server, whatever it is called: one already there is not added twice. */
    fun sameServerKey(server: ServerEntity): String = listOf(
        server.protocol.name, server.address.lowercase(), server.port.toString(), server.uuid,
        server.path, server.rawLink, server.rawJson
    ).joinToString("\u0001")

    // ---------------------------------------------------------------- helpers

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    /** A field's value unless missing or null: null would break a non-null field. */
    private fun JsonObject.valueOrNull(key: String): JsonElement? = get(key)?.takeIf { !it.isJsonNull }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray
            ?.filter { it.isJsonObject }
            ?.map { it.asJsonObject }
            .orEmpty()

    private fun JsonObject.nonObjects(key: String): Int =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.count { !it.isJsonObject } ?: 0
}
