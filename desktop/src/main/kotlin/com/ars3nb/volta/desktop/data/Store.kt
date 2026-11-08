package com.ars3nb.volta.desktop.data

import com.ars3nb.volta.core.stats.TrafficDayEntity
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The colour themes, named as on Android (a backup carries the name). DYNAMIC is the
 * Material theme: Android takes its colours from the wallpaper, Windows from the accent
 * colour the user picked in Windows' own settings.
 */
enum class DesignTheme { SYSTEM, VOLTA, LIGHT, OCEAN, EMBER, DYNAMIC }

/** Every setting, with the Android app's defaults where the two apps share one. */
data class AppSettings(
    val designTheme: DesignTheme = DesignTheme.VOLTA,
    /** "system" or a language code (see Lang). */
    val language: String = "system",
    val connectMode: ConnectMode = ConnectMode.AUTO_HOME_LTE,
    val autoConnect: Boolean = false,
    val autoFailover: Boolean = true,
    /** Point Windows at Volta's proxy while connected. */
    val systemProxy: Boolean = true,
    val minimizeToTray: Boolean = true,
    val refreshOnLaunch: Boolean = true,
    val pingOnStart: Boolean = true,
    val pingType: PingType = PingType.REAL_DELAY,
    val localDns: Boolean = true,
    val sendHwid: Boolean = false,
    val hideBalancers: Boolean = true,
    /** The ports Volta tries first; taken ones are swapped for free ones at connect time. */
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val trayHintShown: Boolean = false,
    /** Ad blocking, as on Android (see AdBlockMode and ConfigBuilder.filterRules). */
    val adBlock: AdBlockMode = AdBlockMode.OFF,
    val blockTorrents: Boolean = false,
    /** «Проверка обновлений»: look for a newer build on start and now and then. */
    val updateAutoCheck: Boolean = true,
    /** «Бета-версии»: also the main branch's test build. */
    val updateBeta: Boolean = false,
    /** The build last announced, so each one is announced once. */
    val updateNotified: Int = 0,
    /** Sites that skip Volta altogether (Windows' proxy bypass list). */
    val bypassSites: List<String> = emptyList(),
    /**
     * Android settings a backup brought that Windows has no use for (per-app lists, MTU,
     * battery saver…), kept so the next backup carries them back to a phone.
     */
    val foreign: Map<String, Any> = emptyMap()
)

/** Everything Volta remembers, saved as one JSON file. */
data class StoreData(
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val servers: List<ServerEntity> = emptyList(),
    val routingProfiles: List<RoutingProfileEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val nextId: Long = 1,
    /** The node that last carried traffic in each auto group ("NORMAL"/"BYPASS"). */
    val remembered: Map<String, Long> = emptyMap(),
    val networkRegime: NetworkRegime = NetworkRegime.UNKNOWN,
    /** Bytes through the VPN server per day (see TrafficTally), the last 400 days. */
    val trafficDays: List<TrafficDayEntity> = emptyList(),
    val schema: Int = 1
)

/**
 * The state in memory, written to disk after every change (atomically: a temp file then a
 * rename, so a crash mid-write never leaves a broken file).
 */
class Store(private val file: File) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val _data = MutableStateFlow(load())
    val data: StateFlow<StoreData> = _data.asStateFlow()

    val value: StoreData get() = _data.value

    @Synchronized
    fun update(change: (StoreData) -> StoreData): StoreData {
        val next = sanitize(change(_data.value))
        _data.value = next
        save(next)
        return next
    }

    /** Hands out ids for new rows, the way the Android database's autoincrement does. */
    @Synchronized
    fun newIds(count: Int): List<Long> {
        val first = _data.value.nextId
        update { it.copy(nextId = first + count) }
        return List(count) { first + it }
    }

    /**
     * The saved state. Never silently empty when there is something on disk: an empty
     * state would be written over the user's subscriptions with the next change. A file
     * that can't be read (locked by an antivirus or a backup tool for a moment) is retried
     * and otherwise stops the start with an error; a file that can't be parsed is kept
     * aside and the last good copy (volta.json.bak, made at every good start) comes back.
     */
    private fun load(): StoreData {
        if (!file.exists()) return parse(backup())?.also { restoredFromBackup("missing, restored from ${file.name}.bak") } ?: StoreData()
        var text: String? = null
        var failure: Exception? = null
        for (attempt in 0 until 10) {
            try {
                text = file.readText(Charsets.UTF_8)
                break
            } catch (e: java.io.IOException) {
                failure = e
                Thread.sleep(300)
            }
        }
        if (text == null) throw java.io.IOException("can't read ${file.absolutePath}", failure)
        parse(text)?.let { good ->
            runCatching { file.copyTo(File(file.parentFile, file.name + ".bak"), overwrite = true) }
            return good
        }
        // Keep the unreadable file for a look later rather than losing it.
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        runCatching { file.copyTo(File(file.parentFile, "${file.name}.broken-$stamp"), overwrite = true) }
        return parse(backup())?.also { restoredFromBackup("broken, restored from ${file.name}.bak") } ?: StoreData()
    }

    private fun backup(): String? =
        runCatching { File(file.parentFile, file.name + ".bak").takeIf { it.isFile }?.readText(Charsets.UTF_8) }.getOrNull()

    private fun parse(text: String?): StoreData? = text?.takeIf { it.isNotBlank() }?.let {
        runCatching { sanitize(gson.fromJson(withDefaults(it), StoreData::class.java)) }.getOrNull()
            ?: salvage(it)
    }

    /**
     * A file this version can't read whole (a field whose shape changed, one bad entry):
     * everything that still reads is kept, field by field and list entry by list entry,
     * rather than the user's subscriptions going with the one part that doesn't.
     */
    private fun salvage(text: String): StoreData? = runCatching {
        val root = JsonParser.parseString(text).asJsonObject
        val base = gson.toJsonTree(StoreData()).asJsonObject
        fun reads(key: String, value: com.google.gson.JsonElement): Boolean = runCatching {
            val probe = base.deepCopy().apply { add(key, value) }
            gson.fromJson(probe, StoreData::class.java)
        }.isSuccess
        val kept = com.google.gson.JsonObject()
        val dropped = mutableListOf<String>()
        for ((key, value) in root.entrySet()) {
            when {
                reads(key, value) -> kept.add(key, value)
                value.isJsonArray -> {
                    val good = com.google.gson.JsonArray()
                    value.asJsonArray.forEach { e ->
                        if (reads(key, com.google.gson.JsonArray().apply { add(e) })) good.add(e) else dropped += "$key[]"
                    }
                    kept.add(key, good)
                }
                else -> dropped += key
            }
        }
        val stamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        runCatching { file.copyTo(File(file.parentFile, "${file.name}.unreadable-$stamp"), overwrite = true) }
        restoredFromBackup("partly unreadable (left out: ${dropped.joinToString()}; the file as it was: ${file.name}.unreadable-$stamp)")
        sanitize(gson.fromJson(withDefaults(kept.toString()), StoreData::class.java))
    }.getOrNull()

    private fun restoredFromBackup(why: String) {
        runCatching {
            File(file.parentFile, "logs").mkdirs()
            File(file.parentFile, "logs/volta.log").appendText("${java.time.LocalDateTime.now()} store: ${file.name} $why\n")
        }
    }

    /**
     * The file over the defaults: a setting an older Volta did not write takes its
     * default (Gson alone would make a missing true switch false).
     */
    private fun withDefaults(text: String): JsonObject {
        val file = JsonParser.parseString(text).asJsonObject
        val defaults = gson.toJsonTree(AppSettings()).asJsonObject
        file.get("settings")?.takeIf { it.isJsonObject }?.asJsonObject?.entrySet()?.forEach { (k, v) ->
            if (!v.isJsonNull) defaults.add(k, v)
        }
        file.add("settings", defaults)
        return file
    }

    /** Gson ignores Kotlin's non-null types: anything missing in an old file gets its default. */
    @Suppress("SENSELESS_COMPARISON", "USELESS_ELVIS")
    private fun sanitize(d: StoreData?): StoreData {
        if (d == null) return StoreData()
        val subs = (d.subscriptions ?: emptyList()).filterNotNull()
        val servers = (d.servers ?: emptyList()).filterNotNull()
            .filter { it.address != null && it.remark != null && it.protocol != null }
        val profiles = (d.routingProfiles ?: emptyList()).filterNotNull()
        val maxId = (subs.map { it.id } + servers.map { it.id } + profiles.map { it.id }).maxOrNull() ?: 0
        return d.copy(
            subscriptions = subs,
            servers = servers,
            routingProfiles = profiles,
            settings = (d.settings ?: AppSettings()).let { st ->
                st.copy(
                    designTheme = st.designTheme ?: DesignTheme.VOLTA,
                    language = st.language ?: "system",
                    connectMode = st.connectMode ?: ConnectMode.AUTO_HOME_LTE,
                    pingType = st.pingType ?: PingType.REAL_DELAY,
                    adBlock = st.adBlock ?: AdBlockMode.OFF,
                    bypassSites = (st.bypassSites ?: emptyList()).filterNotNull(),
                    foreign = st.foreign ?: emptyMap()
                )
            },
            trafficDays = (d.trafficDays ?: emptyList()).filterNotNull(),
            nextId = maxOf(d.nextId, maxId + 1),
            remembered = d.remembered ?: emptyMap(),
            networkRegime = d.networkRegime ?: NetworkRegime.UNKNOWN
        )
    }

    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "volta-store").apply { isDaemon = true }
    }
    private val pending = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Coalesces bursts (a ping sweep writes each result) into one write of the latest state. */
    private fun save(@Suppress("UNUSED_PARAMETER") d: StoreData) {
        if (pending.compareAndSet(false, true)) {
            writer.execute {
                Thread.sleep(250)
                pending.set(false)
                write(_data.value)
            }
        }
    }

    /** Writes now and waits; for shutting down. */
    fun flush() {
        runCatching {
            writer.submit { write(_data.value) }.get(5, java.util.concurrent.TimeUnit.SECONDS)
        }
    }

    /** Only ever runs on [writer], one write at a time. */
    private fun write(d: StoreData) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(gson.toJson(d))
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: Exception) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }
}
