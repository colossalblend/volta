package com.ars3nb.volta.desktop.data

import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.PingType

/**
 * The settings part of a backup file, in the Android app's own names (its AppPreferences
 * keys), so a file made on a phone sets the same switches on Windows and back.
 *
 * Settings only Android has (the per-app list, MTU, battery saver, following the network,
 * the simple mode) are kept as they came in [AppSettings.foreign] and written out again,
 * so a phone → Windows → phone trip loses nothing. Windows' own settings go in with a
 * `windows_` prefix, which the Android app skips.
 */
object SettingsBackup {

    /** Android keys Windows keeps without using: they only ride along to the next file. */
    val ANDROID_ONLY = setOf(
        "battery_saver", "follow_network", "auto_recheck", "simple_mode", "per_app_mode", "mtu", "per_app_packages"
    )

    fun toBackup(s: AppSettings): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        s.foreign.forEach { (k, v) -> if (k in ANDROID_ONLY) out[k] = normalize(v) }
        out["auto_failover"] = s.autoFailover
        out["ping_on_start"] = s.pingOnStart
        out["local_dns"] = s.localDns
        out["block_torrents"] = s.blockTorrents
        out["send_hwid"] = s.sendHwid
        out["auto_connect"] = s.autoConnect
        out["refresh_on_launch"] = s.refreshOnLaunch
        out["hide_balancers"] = s.hideBalancers
        out["auto_by_subscription"] = s.autoBySubscription
        out["gemini_after_connect"] = s.geminiAfterConnect
        out["design_theme"] = s.designTheme.name
        out["default_ping_type"] = s.pingType.name
        out["ad_block"] = s.adBlock.name
        out["connect_mode"] = s.connectMode.name
        out["windows_system_proxy"] = s.systemProxy
        out["windows_minimize_to_tray"] = s.minimizeToTray
        out["windows_bypass_sites"] = s.bypassSites
        return out
    }

    /** [s] with what [values] (a backup's settings) set; also how many were applied. */
    fun fromBackup(s: AppSettings, values: Map<String, Any?>): Pair<AppSettings, Int> {
        var applied = 0
        var r = s
        fun bool(key: String, set: (Boolean) -> AppSettings) {
            (values[key] as? Boolean)?.let { r = set(it); applied++ }
        }
        fun <E : Enum<E>> enum(key: String, entries: List<E>, set: (E) -> AppSettings) {
            val name = values[key] as? String ?: return
            // Android's own legacy ping names, as its AppPreferences reads them.
            val mapped = when (name) {
                "PROXY_GET", "PROXY_HEAD", "HTTPS_GET", "CORE_DELAY" -> "REAL_DELAY"
                "ICMP_LIKE" -> "ICMP"
                else -> name
            }
            entries.firstOrNull { it.name == mapped }?.let { r = set(it); applied++ }
        }
        bool("auto_failover") { r.copy(autoFailover = it) }
        bool("ping_on_start") { r.copy(pingOnStart = it) }
        bool("local_dns") { r.copy(localDns = it) }
        bool("block_torrents") { r.copy(blockTorrents = it) }
        bool("send_hwid") { r.copy(sendHwid = it) }
        bool("auto_connect") { r.copy(autoConnect = it) }
        bool("refresh_on_launch") { r.copy(refreshOnLaunch = it) }
        bool("hide_balancers") { r.copy(hideBalancers = it) }
        bool("auto_by_subscription") { r.copy(autoBySubscription = it) }
        bool("gemini_after_connect") { r.copy(geminiAfterConnect = it) }
        bool("windows_system_proxy") { r.copy(systemProxy = it) }
        bool("windows_minimize_to_tray") { r.copy(minimizeToTray = it) }
        enum("design_theme", DesignTheme.entries) { r.copy(designTheme = it) }
        // Windows times nodes two ways; Android's other two fall back to the real one.
        enum("default_ping_type", PingType.entries) {
            r.copy(pingType = if (it == PingType.TCP) PingType.TCP else PingType.REAL_DELAY)
        }
        enum("ad_block", AdBlockMode.entries) { r.copy(adBlock = it) }
        enum("connect_mode", ConnectMode.entries) { r.copy(connectMode = it) }
        (values["windows_bypass_sites"] as? List<*>)?.let { list ->
            r = r.copy(bypassSites = list.filterIsInstance<String>().filter { it.isNotBlank() })
            applied++
        }
        val foreign = LinkedHashMap(r.foreign)
        ANDROID_ONLY.forEach { key -> values[key]?.let { foreign[key] = normalize(it); applied++ } }
        r = r.copy(foreign = foreign)
        return r to applied
    }

    /** Whole numbers as Int (Gson reads every number back as a Double). */
    private fun normalize(v: Any): Any = when (v) {
        is Double -> if (v % 1.0 == 0.0) v.toInt() else v
        is Float -> if (v % 1f == 0f) v.toInt() else v
        is Long -> v.toInt()
        is List<*> -> v.filterNotNull().map { normalize(it) }
        else -> v
    }
}
