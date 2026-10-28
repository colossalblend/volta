package com.ars3nb.volta.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.PingType
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "volta_prefs")

enum class DesignTheme {
    SYSTEM, VOLTA, LIGHT, OCEAN, EMBER,

    /** Material You: colours taken from the wallpaper on Android 12+. */
    DYNAMIC
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
        val connectMode = stringPreferencesKey("connect_mode")
        val refreshOnLaunch = booleanPreferencesKey("refresh_on_launch")
        val followNetwork = booleanPreferencesKey("follow_network")
        val autoRecheck = booleanPreferencesKey("auto_recheck")
        val hideBalancers = booleanPreferencesKey("hide_balancers")
        val lastGoodHome = longPreferencesKey("last_good_home")
        val lastGoodLte = longPreferencesKey("last_good_lte")
        val updateAutoCheck = booleanPreferencesKey("update_auto_check")
        val updateToken = stringPreferencesKey("update_token")
        val updateLastCheck = longPreferencesKey("update_last_check")
        val updateNotified = intPreferencesKey("update_notified")
        val reconnectAfterUpdate = booleanPreferencesKey("reconnect_after_update")
    }

    /** Look for a newer build on GitHub on launch and now and then in the background. */
    val updateAutoCheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.updateAutoCheck] ?: true }

    /** A GitHub token that can read the private repository; blank for public releases. */
    val updateToken: Flow<String> = context.dataStore.data.map { it[Keys.updateToken].orEmpty() }
    val updateLastCheck: Flow<Long> = context.dataStore.data.map { it[Keys.updateLastCheck] ?: 0L }

    /** The build last announced in a notification, so each one is announced once. */
    val updateNotified: Flow<Int> = context.dataStore.data.map { it[Keys.updateNotified] ?: 0 }

    /** The VPN was on when the update was installed: turn it back on after the restart. */
    val reconnectAfterUpdate: Flow<Boolean> = context.dataStore.data.map { it[Keys.reconnectAfterUpdate] ?: false }

    suspend fun setUpdateAutoCheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.updateAutoCheck] = enabled }
    }

    suspend fun setUpdateToken(token: String) {
        context.dataStore.edit { it[Keys.updateToken] = token.trim() }
    }

    suspend fun setUpdateLastCheck(at: Long) {
        context.dataStore.edit { it[Keys.updateLastCheck] = at }
    }

    suspend fun setUpdateNotified(versionCode: Int) {
        context.dataStore.edit { it[Keys.updateNotified] = versionCode }
    }

    suspend fun setReconnectAfterUpdate(enabled: Boolean) {
        context.dataStore.edit { it[Keys.reconnectAfterUpdate] = enabled }
    }

    /** What the big button connects to. Home+LTE by default: it works everywhere. */
    val connectMode: Flow<ConnectMode> = context.dataStore.data.map {
        runCatching { ConnectMode.valueOf(it[Keys.connectMode] ?: ConnectMode.AUTO_HOME_LTE.name) }
            .getOrDefault(ConnectMode.AUTO_HOME_LTE)
    }

    /** Happ-style: pull every subscription (and its routing) when the app opens. */
    val refreshOnLaunch: Flow<Boolean> = context.dataStore.data.map { it[Keys.refreshOnLaunch] ?: true }

    /** Re-pick the group when the phone moves between Wi-Fi and mobile data. */
    val followNetwork: Flow<Boolean> = context.dataStore.data.map { it[Keys.followNetwork] ?: true }

    /** Clash url-test style: re-measure the group in the background while connected. */
    val autoRecheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoRecheck] ?: true }

    /** The node that last carried traffic in each group, tried first next time. */
    val lastGoodHome: Flow<Long> = context.dataStore.data.map { it[Keys.lastGoodHome] ?: 0L }
    val lastGoodLte: Flow<Long> = context.dataStore.data.map { it[Keys.lastGoodLte] ?: 0L }

    /** Provider balancers («Авто», «Балансер») out of the server list. */
    val hideBalancers: Flow<Boolean> = context.dataStore.data.map { it[Keys.hideBalancers] ?: true }

    suspend fun setHideBalancers(enabled: Boolean) {
        context.dataStore.edit { it[Keys.hideBalancers] = enabled }
    }

    suspend fun setAutoRecheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoRecheck] = enabled }
    }

    suspend fun setLastGood(home: Boolean, serverId: Long) {
        context.dataStore.edit {
            if (home) it[Keys.lastGoodHome] = serverId else it[Keys.lastGoodLte] = serverId
        }
    }

    suspend fun setConnectMode(mode: ConnectMode) {
        context.dataStore.edit { it[Keys.connectMode] = mode.name }
    }

    suspend fun setRefreshOnLaunch(enabled: Boolean) {
        context.dataStore.edit { it[Keys.refreshOnLaunch] = enabled }
    }

    suspend fun setFollowNetwork(enabled: Boolean) {
        context.dataStore.edit { it[Keys.followNetwork] = enabled }
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
