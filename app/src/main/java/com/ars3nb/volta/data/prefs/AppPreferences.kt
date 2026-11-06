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
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerSort
import com.ars3nb.volta.data.model.ServersView
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
        val liteMode = booleanPreferencesKey("lite_mode")
        val autoFailover = booleanPreferencesKey("auto_failover")
        val perAppMode = stringPreferencesKey("per_app_mode")
        val perAppPackages = stringSetPreferencesKey("per_app_packages")
        val defaultPingType = stringPreferencesKey("default_ping_type")
        val pingOnStart = booleanPreferencesKey("ping_on_start")
        val localDns = booleanPreferencesKey("local_dns")
        val adBlock = stringPreferencesKey("ad_block")
        val blockTorrents = booleanPreferencesKey("block_torrents")
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
        val simpleMode = booleanPreferencesKey("simple_mode")
        val lastGoodHome = longPreferencesKey("last_good_home")
        val lastGoodLte = longPreferencesKey("last_good_lte")
        val otaEnabled = booleanPreferencesKey("ota_enabled")
        val updateAutoCheck = booleanPreferencesKey("update_auto_check")
        val updateBeta = booleanPreferencesKey("update_beta")
        val updateLastCheck = longPreferencesKey("update_last_check")
        val updateNotified = intPreferencesKey("update_notified")
        val reconnectAfterUpdate = booleanPreferencesKey("reconnect_after_update")
        val autoBySubscription = booleanPreferencesKey("auto_by_subscription")
        val autoSubscription = longPreferencesKey("auto_subscription")
        val serversPool = stringPreferencesKey("servers_pool")
        val serversQuery = stringPreferencesKey("servers_query")
        val serversSort = stringPreferencesKey("servers_sort")
        val serversByCountry = booleanPreferencesKey("servers_by_country")
        val serversFavorites = booleanPreferencesKey("servers_favorites")
        val serversSubscription = longPreferencesKey("servers_subscription")
        val serversOpen = stringSetPreferencesKey("servers_open_countries")
        val serversOpenTouched = booleanPreferencesKey("servers_open_touched")
        val geminiAfterConnect = booleanPreferencesKey("gemini_after_connect")
        val geminiResults = stringPreferencesKey("gemini_results")
    }

    /**
     * «Авто по подпискам»: the auto modes may be limited to one subscription's servers
     * (the choice under the mode selector). Off hides that choice and ignores it.
     */
    val autoBySubscription: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoBySubscription] ?: true }

    suspend fun setAutoBySubscription(enabled: Boolean) {
        context.dataStore.edit { it[Keys.autoBySubscription] = enabled }
    }

    /** The subscription the auto modes pick from; 0: every subscription. */
    val autoSubscription: Flow<Long> = context.dataStore.data.map { it[Keys.autoSubscription] ?: 0L }

    suspend fun setAutoSubscription(subscriptionId: Long) {
        context.dataStore.edit { it[Keys.autoSubscription] = subscriptionId.coerceAtLeast(0) }
    }

    /** The server list as it was left (see [ServersView]). */
    val serversView: Flow<ServersView> = context.dataStore.data.map { p ->
        ServersView(
            pool = runCatching { ServerPool.valueOf(p[Keys.serversPool].orEmpty()) }.getOrDefault(ServerPool.ALL),
            query = p[Keys.serversQuery].orEmpty(),
            sort = runCatching { ServerSort.valueOf(p[Keys.serversSort].orEmpty()) }.getOrDefault(ServerSort.BEST),
            byCountry = p[Keys.serversByCountry] ?: true,
            favoritesOnly = p[Keys.serversFavorites] ?: false,
            subscriptionId = p[Keys.serversSubscription]?.takeIf { it > 0 },
            openCountries = if (p[Keys.serversOpenTouched] == true) p[Keys.serversOpen].orEmpty() else null
        )
    }

    suspend fun setServersView(view: ServersView) {
        context.dataStore.edit {
            it[Keys.serversPool] = view.pool.name
            it[Keys.serversQuery] = view.query
            it[Keys.serversSort] = view.sort.name
            it[Keys.serversByCountry] = view.byCountry
            it[Keys.serversFavorites] = view.favoritesOnly
            it[Keys.serversSubscription] = view.subscriptionId ?: 0L
            it[Keys.serversOpenTouched] = view.openCountries != null
            it[Keys.serversOpen] = view.openCountries.orEmpty()
        }
    }

    /** Open gemini.google.com through each server once it connects. Off: it costs traffic. */
    val geminiAfterConnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.geminiAfterConnect] ?: false }

    suspend fun setGeminiAfterConnect(enabled: Boolean) {
        context.dataStore.edit { it[Keys.geminiAfterConnect] = enabled }
    }

    /** The last Gemini verdict per server, as GeminiChecks writes it (JSON). */
    val geminiResults: Flow<String> = context.dataStore.data.map { it[Keys.geminiResults].orEmpty() }

    suspend fun setGeminiResults(json: String) {
        context.dataStore.edit { it[Keys.geminiResults] = json }
    }

    /**
     * Left from 1.1.30, where updating could be switched off as a whole. Now folded into
     * [updateAutoCheck]: false here means automatic checks are off until switched on.
     */
    val otaEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.otaEnabled] ?: true }

    suspend fun setOtaEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.otaEnabled] = enabled }
    }

    /** Look for a newer build on GitHub on launch and now and then in the background. */
    val updateAutoCheck: Flow<Boolean> = context.dataStore.data.map { it[Keys.updateAutoCheck] ?: true }

    /** When the last check ran, so launches and the worker don't check too often. */
    val updateLastCheck: Flow<Long> = context.dataStore.data.map { it[Keys.updateLastCheck] ?: 0L }

    /** The build last announced in a notification, so each one is announced once. */
    val updateNotified: Flow<Int> = context.dataStore.data.map { it[Keys.updateNotified] ?: 0 }

    /** The VPN was on when the update was installed: turn it back on after the restart. */
    val reconnectAfterUpdate: Flow<Boolean> = context.dataStore.data.map { it[Keys.reconnectAfterUpdate] ?: false }

    /** Also offer test builds from the main branch, not only published releases. */
    val updateBeta: Flow<Boolean> = context.dataStore.data.map { it[Keys.updateBeta] ?: false }

    suspend fun setUpdateBeta(enabled: Boolean) {
        context.dataStore.edit { it[Keys.updateBeta] = enabled }
    }

    suspend fun setUpdateAutoCheck(enabled: Boolean) {
        context.dataStore.edit { it[Keys.updateAutoCheck] = enabled }
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

    /** Простой режим: one big on/off button, always Home+LTE auto. */
    val simpleMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.simpleMode] ?: false }

    suspend fun setSimpleMode(enabled: Boolean) {
        context.dataStore.edit { it[Keys.simpleMode] = enabled }
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
    val adBlock: Flow<AdBlockMode> = context.dataStore.data.map {
        runCatching { AdBlockMode.valueOf(it[Keys.adBlock] ?: AdBlockMode.OFF.name) }
            .getOrDefault(AdBlockMode.OFF)
    }
    val blockTorrents: Flow<Boolean> = context.dataStore.data.map { it[Keys.blockTorrents] ?: false }
    val sendHwid: Flow<Boolean> = context.dataStore.data.map { it[Keys.sendHwid] ?: false }
    val batterySaver: Flow<Boolean> = context.dataStore.data.map { it[Keys.batterySaver] ?: true }
    val autoConnect: Flow<Boolean> = context.dataStore.data.map { it[Keys.autoConnect] ?: false }
    val mtu: Flow<Int> = context.dataStore.data.map { (it[Keys.mtu] ?: 1420).coerceIn(1280, 9000) }
    val networkRegime: Flow<String> = context.dataStore.data.map {
        it[Keys.networkRegime] ?: "UNKNOWN"
    }

    /** «Lite-оформление»: fewer animations, for slower phones. */
    val liteMode: Flow<Boolean> = context.dataStore.data.map { it[Keys.liteMode] ?: false }

    suspend fun setLiteMode(on: Boolean) {
        context.dataStore.edit { it[Keys.liteMode] = on }
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

    suspend fun setAdBlock(mode: AdBlockMode) {
        context.dataStore.edit { it[Keys.adBlock] = mode.name }
    }

    suspend fun setBlockTorrents(enabled: Boolean) {
        context.dataStore.edit { it[Keys.blockTorrents] = enabled }
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

    // ------------------------------------------------------------------ backup

    /*
     * What a backup file carries: the user's own choices. The app's bookkeeping (the
     * last network, the last good servers, update checks) is about the phone it was
     * made on and stays there.
     */
    private val backupBooleans = listOf(
        Keys.autoFailover, Keys.pingOnStart, Keys.localDns, Keys.blockTorrents, Keys.sendHwid,
        Keys.batterySaver, Keys.autoConnect, Keys.refreshOnLaunch, Keys.followNetwork,
        Keys.autoRecheck, Keys.hideBalancers, Keys.simpleMode, Keys.autoBySubscription, Keys.geminiAfterConnect
    )
    private val backupStrings = listOf(
        Keys.designTheme, Keys.perAppMode, Keys.defaultPingType, Keys.adBlock, Keys.connectMode
    )
    private val backupInts = listOf(Keys.mtu)
    private val backupSets = listOf(Keys.perAppPackages)

    /** The settings the user has set, by their stored names. Untouched ones stay out. */
    suspend fun backupSettings(): Map<String, Any> {
        val stored = context.dataStore.data.first()
        val out = LinkedHashMap<String, Any>()
        backupBooleans.forEach { key -> stored[key]?.let { out[key.name] = it } }
        backupStrings.forEach { key -> stored[key]?.let { out[key.name] = it } }
        backupInts.forEach { key -> stored[key]?.let { out[key.name] = it } }
        backupSets.forEach { key -> stored[key]?.let { out[key.name] = it.sorted() } }
        return out
    }

    /**
     * Puts back what [backupSettings] wrote. Unknown names and values of the wrong kind
     * are skipped; a bad enum name is harmless, the readers above fall back to defaults.
     * Returns how many settings were applied.
     */
    suspend fun restoreSettings(values: Map<String, Any?>): Int {
        var applied = 0
        context.dataStore.edit { prefs ->
            backupBooleans.forEach { key ->
                (values[key.name] as? Boolean)?.let { prefs[key] = it; applied++ }
            }
            backupStrings.forEach { key ->
                (values[key.name] as? String)?.takeIf { it.isNotBlank() }?.let { prefs[key] = it; applied++ }
            }
            backupInts.forEach { key ->
                (values[key.name] as? Number)?.let { prefs[key] = it.toInt().coerceIn(1280, 9000); applied++ }
            }
            backupSets.forEach { key ->
                (values[key.name] as? List<*>)?.let { list ->
                    prefs[key] = list.filterIsInstance<String>().filter { it.isNotBlank() }.toSet()
                    applied++
                }
            }
        }
        return applied
    }
}
