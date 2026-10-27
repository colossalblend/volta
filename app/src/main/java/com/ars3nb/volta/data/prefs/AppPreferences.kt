package com.ars3nb.volta.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.PingType
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "volta_prefs")

enum class DesignTheme {
    SYSTEM, VOLTA, LIGHT, OCEAN, EMBER
}

class AppPreferences(private val context: Context) {

    private object Keys {
        val designTheme = stringPreferencesKey("design_theme")
        val autoFailover = booleanPreferencesKey("auto_failover")
        val perAppMode = stringPreferencesKey("per_app_mode")
        val perAppPackages = stringSetPreferencesKey("per_app_packages")
        val defaultPingType = stringPreferencesKey("default_ping_type")
        val pingOnStart = booleanPreferencesKey("ping_on_start")
        val localDns = booleanPreferencesKey("local_dns")
        val mtu = intPreferencesKey("mtu")
        val sendHwid = booleanPreferencesKey("send_hwid")
        val networkRegime = stringPreferencesKey("network_regime")
        val batterySaver = booleanPreferencesKey("battery_saver")
        val autoConnect = booleanPreferencesKey("auto_connect")
        val lastIpJson = stringPreferencesKey("last_ip_json")
    }

    val designTheme: Flow<DesignTheme> = context.dataStore.data.map {
        runCatching { DesignTheme.valueOf(it[Keys.designTheme] ?: DesignTheme.VOLTA.name) }
            .getOrDefault(DesignTheme.VOLTA)
    }

    /** Split tunnelling: which apps the tunnel covers. */
    val perAppMode: Flow<PerAppMode> = context.dataStore.data.map {
        runCatching { PerAppMode.valueOf(it[Keys.perAppMode] ?: PerAppMode.OFF.name) }
            .getOrDefault(PerAppMode.OFF)
    }

    val perAppPackages: Flow<Set<String>> = context.dataStore.data.map {
        it[Keys.perAppPackages] ?: emptySet()
    }

    val autoFailover: Flow<Boolean> = context.dataStore.data.map {
        it[Keys.autoFailover] ?: true
    }

    val defaultPingType: Flow<PingType> = context.dataStore.data.map {
        when (val stored = it[Keys.defaultPingType]) {
            // Legacy names from the pre-v26 ping engine. HTTP_GET is deliberately absent:
            // it is a real value again and must not be rewritten to REAL_DELAY.
            "PROXY_GET", "PROXY_HEAD", "HTTPS_GET", "CORE_DELAY" -> PingType.REAL_DELAY
            "ICMP_LIKE" -> PingType.ICMP
            else -> runCatching { PingType.valueOf(stored ?: PingType.REAL_DELAY.name) }
                .getOrDefault(PingType.REAL_DELAY)
        }
    }

    val pingOnStart: Flow<Boolean> = context.dataStore.data.map { it[Keys.pingOnStart] ?: true }
    val localDns: Flow<Boolean> = context.dataStore.data.map { it[Keys.localDns] ?: true }
    val sendHwid: Flow<Boolean> = context.dataStore.data.map { it[Keys.sendHwid] ?: false }
    val batterySaver: Flow<Boolean> = context.dataStore.data.map { it[Keys.batterySaver] ?: true }
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoConnect] ?: false }
    val mtu: Flow<Int> = context.dataStore.data.map { (it[Keys.mtu] ?: 1420).coerceIn(1280, 9000) }
    val networkRegime: Flow<String> = context.dataStore.data.map {
        it[Keys.networkRegime] ?: "UNKNOWN"
    }

    suspend fun setDesignTheme(theme: DesignTheme) {
        context.dataStore.edit { it[Keys.designTheme] = theme.name }
    }

    suspend fun setPerAppMode(mode: PerAppMode) {
        context.dataStore.edit { it[Keys.perAppMode] = mode.name }
    }

    suspend fun setPerAppPackages(packages: Set<String>) {
        context.dataStore.edit { it[Keys.perAppPackages] = packages }
    }

    suspend fun setAutoFailover(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoFailover] = enabled }
    }

    suspend fun setDefaultPingType(type: PingType) {
        context.dataStore.edit { it[Keys.defaultPingType] = type.name }
    }

    suspend fun setPingOnStart(enabled: Boolean) {
        context.dataStore.edit { it[Keys.pingOnStart] = enabled }
    }

    suspend fun setLocalDns(enabled: Boolean) {
        context.dataStore.edit { it[Keys.localDns] = enabled }
    }

    suspend fun setSendHwid(enabled: Boolean) {
        context.dataStore.edit { it[Keys.sendHwid] = enabled }
    }

    suspend fun setBatterySaver(enabled: Boolean) {
        context.dataStore.edit { it[Keys.batterySaver] = enabled }
    }

    suspend fun setAutoConnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoConnect] = enabled }
    }

    suspend fun setMtu(value: Int) {
        context.dataStore.edit { it[Keys.mtu] = value.coerceIn(1280, 9000) }
    }

    suspend fun setNetworkRegime(regime: String) {
        context.dataStore.edit { it[Keys.networkRegime] = regime }
    }

    suspend fun setLastIpJson(json: String) {
        context.dataStore.edit { it[Keys.lastIpJson] = json }
    }
}
