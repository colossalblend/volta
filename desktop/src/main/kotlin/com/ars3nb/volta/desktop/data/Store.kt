package com.ars3nb.volta.desktop.data

import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.google.gson.GsonBuilder
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The colour themes; Android's wallpaper-based one has no Windows counterpart. */
enum class DesignTheme { SYSTEM, VOLTA, LIGHT, OCEAN, EMBER }

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
    val trayHintShown: Boolean = false
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

    private fun load(): StoreData {
        val text = runCatching { file.readText() }.getOrNull() ?: return StoreData()
        return runCatching { sanitize(gson.fromJson(text, StoreData::class.java)) }
            .getOrElse {
                // Keep the unreadable file for a look later rather than losing it.
                runCatching { file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true) }
                StoreData()
            }
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
            settings = d.settings ?: AppSettings(),
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
