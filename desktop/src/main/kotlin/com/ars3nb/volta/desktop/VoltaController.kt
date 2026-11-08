package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.backup.BackupCodec
import com.ars3nb.volta.core.backup.RestoreReport
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.balancer.LastWins
import com.ars3nb.volta.core.balancer.SubscriptionScope
import com.ars3nb.volta.core.gemini.GeminiChecks
import com.ars3nb.volta.core.gemini.GeminiProbe
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.locale.uiText
import com.ars3nb.volta.core.ping.SweepBoard
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.core.vpn.Fallback
import com.ars3nb.volta.core.vpn.SwitchOrigin
import com.ars3nb.volta.core.vpn.TunnelRequests
import com.ars3nb.volta.data.model.ServersView
import com.ars3nb.volta.data.model.SwitchTarget
import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.stats.PendingTraffic
import com.ars3nb.volta.core.stats.TrafficDayEntity
import com.ars3nb.volta.core.stats.TrafficTally
import com.ars3nb.volta.core.vpn.InternetCheck
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.desktop.data.SettingsBackup
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import com.ars3nb.volta.desktop.update.AppUpdater
import com.ars3nb.volta.desktop.update.UpdateState
import com.ars3nb.volta.core.link.VoltaLinks
import com.ars3nb.volta.core.parser.AmneziaConfigs
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.ping.IpChecker
import com.ars3nb.volta.core.ping.NetworkProbe
import com.ars3nb.volta.core.ping.PingEngine
import com.ars3nb.volta.core.routing.GeoDataManager
import com.ars3nb.volta.core.routing.GeoNames
import com.ars3nb.volta.core.routing.HappRoutingParser
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.routing.RoutingNames
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.core.server.ServerClassifier
import com.ars3nb.volta.core.server.ServerMarkup
import com.ars3nb.volta.core.vpn.CertPins
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.model.matches
import com.ars3nb.volta.desktop.core.DelayTester
import com.ars3nb.volta.desktop.core.LocalPorts
import com.ars3nb.volta.desktop.core.TrafficMeter
import com.ars3nb.volta.desktop.core.Tunnel
import com.ars3nb.volta.desktop.core.WindowsCompat
import com.ars3nb.volta.desktop.core.XrayCore
import com.ars3nb.volta.desktop.data.AppSettings
import com.ars3nb.volta.desktop.data.DesignTheme
import com.ars3nb.volta.desktop.data.Store
import com.ars3nb.volta.desktop.data.StoreData
import com.ars3nb.volta.desktop.data.SubscriptionFetcher
import com.ars3nb.volta.desktop.i18n.I18n
import com.ars3nb.volta.desktop.platform.Autostart
import com.ars3nb.volta.desktop.platform.SystemProxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Volta for Windows in one object: the Android app's repository and view model together.
 * Subscriptions, servers, the auto pick, the tunnel and the settings all go through here;
 * the screens only read [ui] and call these methods.
 */
class VoltaController(
    val paths: AppPaths,
    val core: XrayCore = XrayCore.from(paths),
    systemProxy: SystemProxy = SystemProxy(paths.proxyBackup),
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    val store = Store(paths.stateFile)
    val tunnel = Tunnel(core, paths, systemProxy)
    private val tester = DelayTester(core, paths.runDir)
    private val geoData = GeoDataManager(paths.assetDir)

    private val runtime = MutableStateFlow(RuntimeState())
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /**
     * What Android says in a notification (a new build, a check OpenFlux waits for): Main
     * shows these by the tray icon too, so they reach a user whose window is hidden.
     */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

    val ui: StateFlow<VoltaUiState> = combine(store.data, runtime) { d, r ->
        VoltaUiState(
            subscriptions = d.subscriptions.sortedBy { it.sortOrder },
            servers = d.servers,
            routingProfiles = d.routingProfiles,
            settings = d.settings,
            networkRegime = d.networkRegime,
            trafficDays = d.trafficDays,
            runtime = r
        )
    }.stateIn(scope, SharingStarted.Eagerly, VoltaUiState(settings = store.value.settings))

    private val settings: AppSettings get() = store.value.settings

    /** Opens a page in the user's browser; Main sets it (the captcha of an OpenFlux carrier). */
    @Volatile
    var openUrl: (String) -> Unit = {}

    /** Ends Volta the way the tray's «Выход» does; Main sets it (after an update starts). */
    @Volatile
    var quitApp: () -> Unit = {}

    /** Updates from GitHub Releases, as on Android (see AppUpdater). */
    val updater = AppUpdater(
        scope = scope,
        downloadDir = java.io.File(paths.dataDir, "update"),
        userAgent = "Volta/${AppPaths.appVersion} (Windows)",
        tunnelPort = { if (ui.value.isConnected) tunnel.ports?.http else null },
        beta = { settings.updateBeta },
        launchInstaller = ::launchInstaller
    )

    /**
     * Whether Gemini works through each server, as on Android: the checks and their last
     * verdicts (kept in the store). The tunnel's server goes through its check port, any
     * other through a short-lived xray.exe of its own.
     */
    val gemini = GeminiChecks(
        scope = scope,
        probe = GeminiProbe(core, paths.runDir),
        serverById = { id -> store.value.servers.firstOrNull { it.id == id } },
        live = {
            val rt = runtime.value
            val id = rt.connectedServerId
            val port = tunnel.ports?.exitCheck
            if (rt.vpnState == VpnConnectionState.CONNECTED && id != null && port != null) GeminiChecks.Live(id, port) else null
        },
        load = { store.value.geminiResults },
        save = { json -> store.update { it.copy(geminiResults = json) } }
    )

    /** How [server] can be checked for Gemini right now. */
    fun geminiRoute(server: ServerEntity): GeminiProbe.Route = gemini.routeFor(server, core.isPresent)

    private val pendingTraffic = PendingTraffic()
    @Volatile
    private var trafficFlushedAt = 0L
    private val connectLock = Mutex()
    private var connectJob: Job? = null
    private var trafficJob: Job? = null
    private var guardJob: Job? = null
    private var refineJob: Job? = null

    init {
        I18n.use(settings.language)
        ensureSeeded()
        val resources = AppPaths.resourcesDir()
        ProxyHelper.init(resources, java.io.File(paths.logDir, "voltaproxy.log"))
        OpenFluxRunner.init(resources, java.io.File(paths.dataDir, "openflux"), java.io.File(paths.logDir, "openflux.log"))
        OpenFluxRunner.onCaptcha = { captcha ->
            // Android shows the page in a browser view of its own; here it is the user's
            // browser. The carrier is told when the user presses «Готово» (see Home).
            if (captcha.url.startsWith("http", ignoreCase = true)) runCatching { openUrl(captcha.url) }
            message(Res.string(R.string.servers_openflux_captcha_title))
            _notices.tryEmit(Res.string(R.string.servers_openflux_captcha_title))
        }
        // «Проверять Gemini после подключения»: the server the tunnel settles on, once up.
        gemini.start(
            ui.map { st ->
                val id = st.runtime.connectedServerId
                if (st.settings.geminiAfterConnect && st.isConnected && st.runtime.switching == null && id != null) id else 0L
            }.distinctUntilChanged()
        )
    }

    fun message(text: String) {
        _messages.tryEmit(text)
    }

    private inline fun <T> busy(block: () -> T): T {
        runtime.update { it.copy(busy = it.busy + 1) }
        try {
            return block()
        } finally {
            runtime.update { it.copy(busy = (it.busy - 1).coerceAtLeast(0)) }
        }
    }

    private fun Throwable.userMessage(fallback: String): String =
        message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    // ================================================================ start and stop

    /** After the window is up: what the settings ask for at launch. */
    fun startup() {
        scope.launch(Dispatchers.IO) {
            runtime.update { it.copy(coreVersion = core.version(), autostart = Autostart.isEnabled()) }
            Autostart.refreshPath()
            core.prepareAssets()
        }
        scope.launch(Dispatchers.IO) {
            if (settings.refreshOnLaunch && store.value.subscriptions.isNotEmpty()) {
                runCatching { refreshAllInternal(quiet = true) }
            }
            if (settings.autoConnect && store.value.servers.isNotEmpty() && !ui.value.isConnected) {
                connect()
            } else {
                if (settings.pingOnStart) launch { runCatching { probeNetworkInternal() } }
                launch { refreshIp() }
            }
        }
        // Is there a network at all: the home screen says so, a connect is not started.
        scope.launch {
            InternetCheck.watch().collect { status -> runtime.update { it.copy(internet = status) } }
        }
        // Updates: once at start, then every few hours, each new build announced once.
        scope.launch(Dispatchers.IO) {
            delay(5_000)
            while (isActive) {
                if (settings.updateAutoCheck) {
                    val found = runCatching {
                        updater.checkIfDue(if (updater.lastCheck == 0L) 0 else AppUpdater.BACKGROUND_EVERY_MS)
                    }.getOrNull()
                    val info = (found as? UpdateState.Available)?.info ?: (found as? UpdateState.Ready)?.info
                    if (info != null && info.versionCode > settings.updateNotified) {
                        message(Res.string(R.string.settings_update_notify_text, info.versionName))
                        _notices.tryEmit(Res.string(R.string.settings_update_notify_title) + ": " + info.versionName)
                        updateSettings { it.copy(updateNotified = info.versionCode) }
                    }
                }
                delay(60 * 60_000L)
            }
        }
        // Like the Android worker: each subscription again once its own interval has passed.
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(30 * 60_000L)
                val now = System.currentTimeMillis()
                store.value.subscriptions
                    .filter { it.isEnabled && it.url.startsWith("http", ignoreCase = true) }
                    .filter { now - it.lastUpdated > it.updateIntervalHours.coerceIn(1, 72) * 3_600_000L }
                    .forEach { sub -> runCatching { refreshSubscriptionInternal(sub.id) } }
            }
        }
    }

    /** Before the process ends: the tunnel down and the user's proxy back. */
    fun shutdown() {
        connectJob?.cancel()
        switcher.cancel()
        refineJob?.cancel()
        sweepJob?.cancel()
        trafficJob?.cancel()
        guardJob?.cancel()
        runCatching { tunnel.stop(restoreProxy = true) }
        runCatching { stopHelpers() }
        runCatching { flushTraffic() }
        store.flush()
    }

    private fun ensureSeeded() {
        if (store.value.routingProfiles.none { it.isBuiltIn }) {
            val builtIns = RoutingEngine.builtInProfiles()
            val ids = store.newIds(builtIns.size)
            store.update { d ->
                d.copy(routingProfiles = d.routingProfiles + builtIns.mapIndexed { i, p -> p.copy(id = ids[i]) })
            }
        }
        if (store.value.routingProfiles.none { it.isActive && it.sourceSubscriptionId == null }) {
            store.update { d ->
                val first = d.routingProfiles.firstOrNull { it.isBuiltIn }?.id
                d.copy(routingProfiles = d.routingProfiles.map { it.copy(isActive = it.id == first) })
            }
        }
    }

    // ================================================================ settings

    fun updateSettings(change: (AppSettings) -> AppSettings) {
        store.update { it.copy(settings = change(it.settings)) }
    }

    fun setTheme(theme: DesignTheme) = updateSettings { it.copy(designTheme = theme) }

    fun setLanguage(code: String) {
        I18n.use(code)
        updateSettings { it.copy(language = code) }
    }

    fun setAutoConnect(v: Boolean) = updateSettings { it.copy(autoConnect = v) }
    fun setAutoFailover(v: Boolean) = updateSettings { it.copy(autoFailover = v) }
    fun setMinimizeToTray(v: Boolean) = updateSettings { it.copy(minimizeToTray = v) }
    fun setRefreshOnLaunch(v: Boolean) = updateSettings { it.copy(refreshOnLaunch = v) }
    fun setPingOnStart(v: Boolean) = updateSettings { it.copy(pingOnStart = v) }
    fun setPingType(t: PingType) = updateSettings { it.copy(pingType = t) }
    fun setSendHwid(v: Boolean) = updateSettings { it.copy(sendHwid = v) }
    fun setHideBalancers(v: Boolean) = updateSettings { it.copy(hideBalancers = v) }
    fun markTrayHintShown() = updateSettings { it.copy(trayHintShown = true) }

    /** «Lite-оформление»: every animation in the window off at once (ui/theme/LiteMotion.kt). */
    fun setLiteMode(v: Boolean) = updateSettings { it.copy(liteMode = v) }

    /** «Проверять Gemini после подключения». */
    fun setGeminiAfterConnect(v: Boolean) = updateSettings { it.copy(geminiAfterConnect = v) }

    /** Changes how the server list is looked at; kept for the next visit and the next start. */
    fun updateServersView(change: (ServersView) -> ServersView) {
        val now = settings.serversView
        val next = change(now)
        if (next != now) updateSettings { it.copy(serversView = next) }
    }

    /** «Авто по подпискам». Switching it off lifts a limit the tunnel was picked under. */
    fun setAutoBySubscription(v: Boolean) {
        val limited = settings.autoSubscription > 0
        updateSettings { it.copy(autoBySubscription = v) }
        if (!v && limited) moveIfAuto()
    }

    /**
     * «Авто по подписке»: the auto modes pick only from [subscriptionId]'s servers (0: from
     * all). A live tunnel moves into that subscription right away, as a switch.
     */
    fun setAutoSubscription(subscriptionId: Long) {
        if (settings.autoSubscription == subscriptionId) return
        updateSettings { it.copy(autoSubscription = subscriptionId.coerceAtLeast(0)) }
        moveIfAuto()
    }

    fun setLocalDns(v: Boolean) {
        updateSettings { it.copy(localDns = v) }
        reconnectIfUp()
    }

    fun setSystemProxy(v: Boolean) {
        updateSettings { it.copy(systemProxy = v) }
        scope.launch(Dispatchers.IO) {
            if (!ui.value.isConnected) return@launch
            val ok = if (v) tunnel.applySystemProxy(settings.bypassSites) else tunnel.systemProxy.restore()
            if (v && !ok) message(I18n.s.msgProxyFailed)
            runtime.update { it.copy(proxyActive = tunnel.systemProxy.isApplied()) }
        }
    }

    fun setAutostart(v: Boolean) {
        scope.launch(Dispatchers.IO) {
            if (!Autostart.setEnabled(v)) message(I18n.s.msgAutostartFailed)
            runtime.update { it.copy(autostart = Autostart.isEnabled()) }
        }
    }

    /** The app-wide routing set: 0 = everything through the VPN, 1 = Russian sites direct. */
    fun setBuiltInRouting(index: Int) {
        val builtIns = store.value.routingProfiles.filter { it.isBuiltIn && it.sourceSubscriptionId == null }
        val target = builtIns.getOrNull(index) ?: return
        store.update { d -> d.copy(routingProfiles = d.routingProfiles.map { it.copy(isActive = it.id == target.id) }) }
        reconnectIfUp()
    }

    fun activeBuiltInRouting(): Int {
        val builtIns = store.value.routingProfiles.filter { it.isBuiltIn && it.sourceSubscriptionId == null }
        return builtIns.indexOfFirst { it.isActive }.coerceAtLeast(0)
    }

    fun hwid(): String = Hwid.get(paths)

    private fun reconnectIfUp() {
        if (ui.value.isConnected && connectJob?.isActive != true) {
            val current = runtime.value.connectedServerId
            connectJob = scope.launch { runConnect(fixed = current) }
        }
    }

    // ================================================================ connection

    fun toggleConnect() {
        if (runtime.value.phase != ConnectPhase.IDLE && runtime.value.vpnState != VpnConnectionState.CONNECTED ||
            connectJob?.isActive == true && runtime.value.vpnState == VpnConnectionState.CONNECTING
        ) {
            cancelConnect()
            return
        }
        when (runtime.value.vpnState) {
            VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING -> disconnect()
            else -> connect()
        }
    }

    fun connect() {
        if (connectJob?.isActive == true) return
        // No network at all: say so instead of spinning through a pick that can't work.
        if (InternetCheck.status().blocksConnect) {
            message(Res.string(R.string.network_connect_none))
            return
        }
        refineJob?.cancel()
        connectJob = scope.launch { runConnect() }
        watchInternetWhileConnecting(connectJob)
    }

    /** The network going away while the disc still connects stops the attempt, with the reason. */
    private fun watchInternetWhileConnecting(job: Job?) {
        job ?: return
        scope.launch {
            while (job.isActive) {
                delay(2_000)
                if (job.isActive && runtime.value.vpnState == VpnConnectionState.CONNECTING &&
                    InternetCheck.status().blocksConnect
                ) {
                    job.cancel()
                    message(Res.string(R.string.network_connect_none))
                    return@launch
                }
            }
        }
    }

    /** Stops a connect in progress; runConnect cleans up after itself. */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        message(I18n.s.msgConnectCancelled)
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        switcher.cancel()
        refineJob?.cancel()
        runtime.update {
            it.copy(vpnState = VpnConnectionState.DISCONNECTING, phase = ConnectPhase.IDLE, switching = null)
        }
        scope.launch(Dispatchers.IO) {
            // After whatever still held the tunnel (a switch being checked) has let go.
            connectLock.withLock {
                stopTunnel()
                lastGoodId = null
                runtime.update {
                    it.copy(
                        vpnState = VpnConnectionState.DISCONNECTED,
                        connectedServerId = null,
                        connectedSince = 0,
                        traffic = TrafficStats(),
                        ports = null,
                        proxyActive = false,
                        error = null,
                        switching = null,
                        phase = ConnectPhase.IDLE
                    )
                }
            }
            refreshIp()
        }
    }

    private fun stopTunnel() {
        trafficJob?.cancel()
        guardJob?.cancel()
        tunnel.stop(restoreProxy = true)
        stopHelpers()
        flushTraffic()
    }

    private fun stopHelpers() {
        ProxyHelper.stop()
        OpenFluxRunner.stop()
    }

    /**
     * The helper [server] needs, as on Android (VoltaVpnService): OpenFlux runs its own,
     * AmneziaWG and Hysteria v1 run in voltaproxy, everything else needs none (and any
     * that ran is stopped). Null when ready; otherwise why not.
     */
    private suspend fun prepareHelper(server: ServerEntity): String? {
        if (server.protocol == ProtocolType.OPENFLUX) {
            ProxyHelper.stop()
            return OpenFluxRunner.start(server.rawLink).exceptionOrNull()?.let { it.message ?: I18n.s.errUnsupported }
        }
        val spec = ConfigBuilder.helperSpec(server)
        if (spec != null) {
            OpenFluxRunner.stop()
            return withContext(Dispatchers.IO) { ProxyHelper.start(spec) }.exceptionOrNull()
                ?.let { it.message ?: I18n.s.errUnsupported }
        }
        stopHelpers()
        return null
    }

    fun setConnectMode(mode: ConnectMode) {
        updateSettings { it.copy(connectMode = mode) }
        // A live tunnel follows the new mode by itself, shown as a switch, never as off.
        if (mode != ConnectMode.MANUAL) moveIfAuto()
    }

    /**
     * Choosing a server by hand switches the mode to "Свой сервер". The list marks it at
     * once; a live tunnel follows it once the clicks settle (clicking through five
     * servers moves the tunnel once, to the last one), and the screen says "switching
     * to …" from the first click on. The tunnel stays up all the while.
     */
    fun selectServer(id: Long) {
        updateSettings { it.copy(connectMode = ConnectMode.MANUAL) }
        store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == id) }) }
        refineJob?.cancel()
        val rt = runtime.value
        val server = store.value.servers.firstOrNull { it.id == id } ?: return
        when {
            rt.vpnState == VpnConnectionState.CONNECTED && (rt.connectedServerId != id || rt.switching != null) -> {
                // An automatic move under way (a new mode, the guard) gives way to the click.
                connectJob?.cancel()
                runtime.update { it.copy(switching = SwitchTarget(id, server.remark), phase = ConnectPhase.IDLE) }
                switcher.submit(id)
            }

            // A connect still on its way starts over on the new pick.
            rt.vpnState == VpnConnectionState.CONNECTING && rt.connectedServerId != id -> {
                switcher.cancel()
                connectJob?.cancel()
                connectJob = scope.launch { runConnect(fixed = id) }
            }

            else -> switcher.cancel()
        }
    }

    /** Connects to [id] at once, whatever the mode; a live tunnel moves there without a pause. */
    fun connectTo(id: Long) {
        updateSettings { it.copy(connectMode = ConnectMode.MANUAL) }
        store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == id) }) }
        refineJob?.cancel()
        switcher.cancel()
        val server = store.value.servers.firstOrNull { it.id == id } ?: return
        if (runtime.value.vpnState == VpnConnectionState.CONNECTED) {
            if (runtime.value.connectedServerId == id && runtime.value.switching == null) return
            connectJob?.cancel()
            runtime.update { it.copy(switching = SwitchTarget(id, server.remark), phase = ConnectPhase.IDLE) }
            switcher.submit(id)
            return
        }
        connectJob?.cancel()
        connectJob = scope.launch { runConnect(fixed = id) }
    }

    private fun engine(sweep: Boolean = false) = PingEngine(tester, sweep = sweep)

    /**
     * The subscription the auto modes pick from, or null for all of them (see
     * [SubscriptionScope.effective]: a gone subscription simply lifts the limit).
     */
    private fun autoLimit(mode: ConnectMode = settings.connectMode): Long? = SubscriptionScope.effective(
        chosen = settings.autoSubscription,
        enabled = settings.autoBySubscription,
        mode = mode,
        simpleMode = false,
        subscriptionIds = store.value.subscriptions.map { it.id }
    )

    /** What an auto pick for [scope] may choose from. */
    private fun autoPool(servers: List<ServerEntity>, scope: BalancerScope?): List<ServerEntity> =
        SubscriptionScope.pool(servers, autoLimit(), scope)

    /**
     * The whole connect: pick the server (auto modes race the best-known nodes), raise
     * the tunnel, check it carries traffic, point Windows at it. In an auto mode the
     * next best servers are tried when the first one fails, and a full comparison runs
     * once the tunnel is up (it moves the tunnel when it finds a clearly faster node).
     */
    private suspend fun runConnect(fixed: Long? = null) = connectLock.withLock { connectLocked(fixed) }

    private suspend fun connectLocked(fixed: Long? = null, exclude: Long? = null) {
        val s = I18n.s
        runtime.update {
            it.copy(vpnState = VpnConnectionState.CONNECTING, phase = ConnectPhase.PREPARING, error = null, switching = null)
        }
        try {
            if (!core.isPresent) error(s.errCoreMissing)
            withContext(Dispatchers.IO) { core.prepareAssets() }
            val all = store.value.servers
            if (all.isEmpty()) error(s.errNoServerToConnect)
            val mode = settings.connectMode
            val candidates: List<ServerEntity> = when {
                fixed != null -> listOfNotNull(all.firstOrNull { it.id == fixed })
                mode == ConnectMode.MANUAL -> listOfNotNull(all.firstOrNull { it.isSelected } ?: run {
                    error(s.errChooseServer)
                })
                else -> pickForMode(mode, exclude)
            }
            if (candidates.isEmpty()) error(s.errNoServerToConnect)
            runtime.update { it.copy(phase = ConnectPhase.IDLE) }

            var lastError: String? = null
            for ((index, server) in candidates.withIndex()) {
                val outcome = withContext(Dispatchers.IO) { raise(server, strict = candidates.size > 1) }
                if (outcome == null) {
                    val auto = fixed == null && mode != ConnectMode.MANUAL
                    onConnected(server, auto = auto)
                    if (index > 0) message(s.msgPicked(server.remark))
                    if (auto && lastPickWasQuick) refineAfterConnect(mode)
                    return
                }
                lastError = outcome
                markFailed(server.id)
            }
            throw IllegalStateException(lastError ?: s.errNothingAnswered)
        } catch (e: CancellationException) {
            // Whatever was half raised goes down again, and the user's proxy comes back.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { stopTunnel() }
            runtime.update {
                it.copy(
                    vpnState = VpnConnectionState.DISCONNECTED,
                    phase = ConnectPhase.IDLE,
                    connectedServerId = null,
                    ports = null,
                    proxyActive = false,
                    switching = null
                )
            }
            throw e
        } catch (e: Exception) {
            withContext(Dispatchers.IO) { stopTunnel() }
            runtime.update {
                it.copy(
                    vpnState = VpnConnectionState.ERROR,
                    phase = ConnectPhase.IDLE,
                    error = e.userMessage(s.errNothingAnswered),
                    connectedServerId = null,
                    ports = null,
                    proxyActive = false,
                    switching = null
                )
            }
        }
    }

    /** The last connect-time pick was the quick race: a full comparison should follow. */
    @Volatile
    private var lastPickWasQuick = false

    /** The network as the last pick found it, so the comparison after it needn't ask again. */
    @Volatile
    private var lastNetwork: NetworkProbe.Outcome? = null

    /**
     * Auto modes, the Android way: the node that worked here last and the best ones on
     * record race each other (a handshake each, all at once) and the winner goes first,
     * the next best two behind it. Only when nothing was ever measured does the group
     * get measured before connecting. [exclude]: a node that has just failed.
     */
    private suspend fun pickForMode(mode: ConnectMode, exclude: Long? = null): List<ServerEntity> {
        val s = I18n.s
        val scope = mode.scope ?: BalancerScope.ALL
        val all = autoPool(store.value.servers, scope).filter { it.id != exclude }.map(WindowsCompat::adapt)
        val current = all.firstOrNull { it.isSelected }
        val remembered = store.value.remembered.mapNotNull { (k, v) ->
            runCatching { BalancerScope.valueOf(k) to v }.getOrNull()
        }.toMap()
        val balancer = AutoBalancer(engine(), NetworkProbe())
        // Home+LTE asks the network first. The fast probe answers as soon as one Home
        // site does, instead of sitting out the timeouts of the ones a network blocks.
        val network = if (scope == BalancerScope.ALL) {
            runtime.update { it.copy(phase = ConnectPhase.PROBING) }
            NetworkProbe().runFast().also { outcome ->
                store.update { it.copy(networkRegime = outcome.regime) }
                runtime.update { it.copy(probes = outcome.probes) }
            }
        } else null
        lastNetwork = network
        val decision = balancer.choose(
            servers = all,
            scope = scope,
            current = current,
            pingType = settings.pingType,
            onPhase = { phase -> runtime.update { it.copy(phase = ConnectPhase.of(phase)) } },
            remembered = remembered,
            quick = true,
            knownNetwork = network
        )
        applyDecision(decision)
        lastPickWasQuick = decision.quick
        if (decision.regime == NetworkRegime.BLOCKED && decision.ranked.isEmpty()) error(s.errNoInternet)
        val selected = decision.selected ?: error(
            if (decision.ranked.isEmpty() && decision.probes.isEmpty()) s.errGroupEmpty else s.errNothingAnswered
        )
        val runnersUp = if (decision.quick) {
            // No table yet: the next best on record in the same group stand behind it.
            val dialable = all.filter { ConfigBuilder.canDial(it) }
            val group = if (scope == BalancerScope.ALL) AutoBalancer.filterByRegime(dialable, decision.regime)
            else AutoBalancer.poolFor(dialable, scope)
            group.filter { it.id != selected.id && it.isHealthy && it.latencyMs > 0 }.sortedBy { it.score }
        } else {
            decision.ranked.filter { it.ok && it.server.id != selected.id }.map { it.server }
        }
        return (listOf(selected) + runnersUp.take(2)).distinctBy { it.id }
    }

    /**
     * After a quick pick: once the tunnel is up, the group is measured properly (the
     * user doesn't sit through it) and the tunnel moves to a clearly faster node, as a
     * switch. Anything the user does meanwhile (another server, off) cancels it.
     */
    private fun refineAfterConnect(mode: ConnectMode) {
        refineJob?.cancel()
        refineJob = scope.launch(Dispatchers.IO) {
            delay(REFINE_DELAY_MS)
            if (!ui.value.isConnected || runtime.value.switching != null || settings.connectMode != mode) return@launch
            val scopeOf = mode.scope ?: BalancerScope.ALL
            val all = autoPool(store.value.servers, scopeOf).map(WindowsCompat::adapt)
            val live = runtime.value.connectedServerId
            val remembered = store.value.remembered.mapNotNull { (k, v) ->
                runCatching { BalancerScope.valueOf(k) to v }.getOrNull()
            }.toMap()
            val decision = runCatching {
                AutoBalancer(engine(), NetworkProbe()).choose(
                    servers = all,
                    scope = scopeOf,
                    current = all.firstOrNull { it.id == live },
                    pingType = settings.pingType,
                    remembered = remembered,
                    quick = false,
                    knownNetwork = lastNetwork
                )
            }.getOrNull() ?: return@launch
            if (!isActive || !ui.value.isConnected || settings.connectMode != mode) return@launch
            applyDecision(decision)
            val pick = decision.selected ?: return@launch
            if (!decision.switched || pick.id == runtime.value.connectedServerId) return@launch
            connectLock.withLock {
                if (!ui.value.isConnected || runtime.value.switching != null || settings.connectMode != mode) return@withLock
                if (runtime.value.connectedServerId == pick.id) return@withLock
                val target = store.value.servers.firstOrNull { it.id == pick.id } ?: return@withLock
                hotSwap(target, SwitchOrigin.AUTO)
            }
        }
    }

    /**
     * A live tunnel in an auto mode picks again (a new mode, a new subscription limit):
     * the race runs while the tunnel stays up, and the tunnel moves only when the winner
     * is another node. Not connected: nothing to do, the next connect picks.
     */
    private fun moveIfAuto() {
        if (!ui.value.isConnected || settings.connectMode == ConnectMode.MANUAL) return
        if (connectJob?.isActive == true) return
        refineJob?.cancel()
        switcher.cancel()
        val mode = settings.connectMode
        connectJob = scope.launch {
            connectLock.withLock {
                if (!ui.value.isConnected) return@withLock
                val picked = try {
                    pickForMode(mode)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    runtime.update { it.copy(phase = ConnectPhase.IDLE) }
                    message(e.userMessage(I18n.s.errNothingAnswered))
                    return@withLock
                }
                runtime.update { it.copy(phase = ConnectPhase.IDLE) }
                val target = picked.firstOrNull() ?: return@withLock
                if (target.id == runtime.value.connectedServerId) {
                    store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == target.id) }) }
                    return@withLock
                }
                hotSwap(store.value.servers.firstOrNull { it.id == target.id } ?: target, SwitchOrigin.AUTO)
            }
            if (lastPickWasQuick && ui.value.isConnected && runtime.value.switching == null) refineAfterConnect(mode)
        }
    }

    /** Writes an auto pick back: the measurements, the network and the summary card. */
    private fun applyDecision(decision: AutoBalancer.Decision) {
        val byId = decision.ranked.associateBy { it.server.id }
        store.update { d ->
            d.copy(
                servers = d.servers.map { server ->
                    val r = byId[server.id] ?: return@map server
                    server.copy(
                        latencyMs = r.latencyMs,
                        ewmaMs = r.ewmaMs,
                        failStreak = if (r.ok) 0 else (server.failStreak + 1).coerceAtMost(9),
                        lastPingAt = System.currentTimeMillis()
                    )
                },
                networkRegime = if (decision.regime != NetworkRegime.UNKNOWN) decision.regime else d.networkRegime
            )
        }
        runtime.update {
            it.copy(
                pick = PickSummary.of(decision) ?: it.pick,
                probes = decision.probes.ifEmpty { it.probes },
                runningScope = null
            )
        }
    }

    private fun markFailed(id: Long) {
        store.update { d ->
            d.copy(servers = d.servers.map {
                if (it.id == id) it.copy(failStreak = (it.failStreak + 1).coerceAtMost(9), latencyMs = -1) else it
            })
        }
    }

    /** The core's config for [server] on [ports], as on Android (the same ConfigBuilder). */
    private fun configFor(server: ServerEntity, routing: RoutingChoice, p: LocalPorts): String = ConfigBuilder.build(
        server = server,
        profile = routing.profile,
        providerRoutingJson = routing.providerJson,
        socksPort = p.socks,
        httpPort = p.http,
        batterySaver = true,
        localDns = settings.localDns,
        enableStats = true,
        geo = routing.geo,
        preferServerRouting = routing.preferServerRouting,
        adBlock = settings.adBlock,
        blockTorrents = settings.blockTorrents,
        exitCheckPort = p.exitCheck,
        metricsPort = p.metrics
    )

    /** Whether the last tunnel raised passed its traffic check (it may stay up without). */
    @Volatile
    private var lastRaiseChecked = false

    /**
     * Raises the tunnel on [server]. Null when it is up; otherwise why not. With [strict]
     * a server that doesn't pass the traffic check counts as failed (the next one is
     * tried); without it, the tunnel stays up and the user is told.
     */
    private suspend fun raise(server: ServerEntity, strict: Boolean): String? {
        val s = I18n.s
        val adapted = WindowsCompat.adapt(server)
        ConfigBuilder.dialProblem(adapted)?.let { return it }
        prepareHelper(adapted)?.let { return it }
        val pinned = runCatching { CertPins.apply(adapted) }.getOrDefault(adapted)
        val routing = routingFor(adapted)
        val ports = try {
            tunnel.start(
                preferredSocks = settings.socksPort,
                preferredHttp = settings.httpPort,
                buildConfig = { p -> configFor(pinned, routing, p) },
                onExit = { code -> onCoreDied(code) }
            )
        } catch (e: Tunnel.StartException) {
            stopHelpers()
            return s.errCoreStart(e.message.orEmpty())
        } catch (e: Exception) {
            stopHelpers()
            return e.userMessage(s.errUnsupported)
        }
        lastRaiseChecked = tunnel.check()
        if (!lastRaiseChecked) {
            if (strict) {
                tunnel.stop(restoreProxy = false)
                stopHelpers()
                return s.errServerSilent
            }
            message(s.msgServerSilent)
        }
        if (settings.systemProxy) {
            if (!tunnel.applySystemProxy(settings.bypassSites)) message(s.msgProxyFailed)
        }
        runtime.update { it.copy(ports = ports, proxyActive = tunnel.systemProxy.isApplied()) }
        return null
    }

    private fun onConnected(server: ServerEntity, auto: Boolean) {
        val now = System.currentTimeMillis()
        lastGoodId = if (lastRaiseChecked) server.id else null
        store.update { d ->
            val group = when (server.role) {
                ServerRole.NORMAL -> BalancerScope.NORMAL.name
                ServerRole.BYPASS_WHITELIST -> BalancerScope.BYPASS.name
                ServerRole.AUTO -> null
            }
            d.copy(
                servers = d.servers.map {
                    val sel = it.id == server.id
                    if (sel) it.copy(isSelected = true, failStreak = 0) else it.copy(isSelected = false)
                },
                remembered = if (auto && group != null) d.remembered + (group to server.id) else d.remembered
            )
        }
        runtime.update {
            it.copy(
                vpnState = VpnConnectionState.CONNECTED,
                phase = ConnectPhase.IDLE,
                connectedServerId = server.id,
                connectedSince = now,
                traffic = TrafficStats(),
                speed = SpeedHistory(),
                error = null,
                routingDirty = false,
                switching = null
            )
        }
        startTrafficPolling()
        startGuard()
        scope.launch(Dispatchers.IO) { refreshIp() }
    }

    // ================================================================ switching a live tunnel

    /** The node that last proved it carries traffic in this run of the tunnel; where a failed pick by hand goes back to. */
    @Volatile
    private var lastGoodId: Long? = null

    /** Manual picks on a live tunnel, settled: the last click wins (see [selectServer]). */
    private val switcher = LastWins<Long>(scope, SWITCH_SETTLE_MS) { id -> switchByHand(id) }

    /** Moves a live tunnel to [id] by hand, the clicks having settled. */
    private suspend fun switchByHand(id: Long) {
        connectLock.withLock {
            val rt = runtime.value
            if (rt.vpnState != VpnConnectionState.CONNECTED) {
                runtime.update { it.copy(switching = null) }
                return@withLock
            }
            // Clicked back to where the tunnel is (or an earlier move was cut short there):
            // nothing to move, the guard watches it again.
            if (rt.connectedServerId == id) {
                runtime.update { it.copy(switching = null) }
                startGuard()
                return@withLock
            }
            val target = store.value.servers.firstOrNull { it.id == id }
            if (target == null || !target.isSelected) {
                runtime.update { it.copy(switching = null) }
                startGuard()
                return@withLock
            }
            // A node the core can't dial leaves the tunnel where it is, and the list goes
            // back to the node in use.
            val problem = ConfigBuilder.dialProblem(WindowsCompat.adapt(target))
            if (problem != null) {
                val live = store.value.servers.firstOrNull { it.id == rt.connectedServerId }
                if (live != null) {
                    store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == live.id) }) }
                }
                runtime.update { it.copy(switching = null) }
                message(
                    Res.string(
                        R.string.home_switch_kept,
                        Countries.withoutLeadingFlag(target.remark),
                        Countries.withoutLeadingFlag(live?.remark.orEmpty())
                    )
                )
                return@withLock
            }
            hotSwap(target, SwitchOrigin.MANUAL)
        }
    }

    /**
     * Holding the connect lock, with the tunnel up: moves it to [target] without taking
     * it down. The core is replaced on the same local ports (Windows keeps pointing at
     * them), the new node is checked at once with short tries, and only then is the
     * switch over. A node that doesn't come up or doesn't carry traffic: a pick by hand
     * goes back to the node that worked before (and says so); anything else moves on.
     */
    private suspend fun hotSwap(target: ServerEntity, origin: SwitchOrigin) {
        val s = I18n.s
        guardJob?.cancel()
        runtime.update { it.copy(switching = SwitchTarget(target.id, target.remark)) }
        val problem = try {
            withContext(Dispatchers.IO) { swapCore(target) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.userMessage(s.errUnsupported)
        }
        val verdict: Boolean? = if (problem == null) switchCheck() else false
        if (verdict != false) {
            onSwitched(target, origin, checked = verdict == true)
            return
        }
        switchFailed(target, origin, problem ?: s.errServerSilent)
    }

    /** The new core under the running tunnel; null when it is up, otherwise why not. */
    private suspend fun swapCore(target: ServerEntity): String? {
        val s = I18n.s
        val adapted = WindowsCompat.adapt(target)
        ConfigBuilder.dialProblem(adapted)?.let { return it }
        prepareHelper(adapted)?.let { return it }
        val pinned = runCatching { CertPins.apply(adapted) }.getOrDefault(adapted)
        val routing = routingFor(adapted)
        val oldHttp = tunnel.ports?.http
        fun start() = tunnel.swap(
            buildConfig = { p -> configFor(pinned, routing, p) },
            onExit = { code -> onCoreDied(code) },
            fallbackSocks = settings.socksPort,
            fallbackHttp = settings.httpPort
        )
        val ports = try {
            start()
        } catch (e: Tunnel.StartException) {
            // The core before it has only just let go of the ports: one more try.
            delay(SWAP_RETRY_PAUSE_MS)
            try {
                start()
            } catch (e2: Tunnel.StartException) {
                runtime.update { it.copy(ports = null) }
                return s.errCoreStart(e2.message.orEmpty())
            }
        }
        // The tunnel runs on this node now, checked or not: the screen names it.
        runtime.update { it.copy(connectedServerId = target.id, ports = ports) }
        if (settings.systemProxy && (ports.http != oldHttp || !tunnel.systemProxy.isApplied())) {
            if (!tunnel.applySystemProxy(settings.bypassSites)) message(s.msgProxyFailed)
        }
        runtime.update { it.copy(proxyActive = tunnel.systemProxy.isApplied()) }
        return null
    }

    /**
     * Right after a swap: does a page load through the new node? Short tries, both probe
     * hosts at once, so a working node is confirmed in about a second and a dead one is
     * given up within a few tries instead of the half-minute of the guard. No network
     * at all says nothing about the node: null, the switch stands.
     */
    private suspend fun switchCheck(): Boolean? {
        delay(SWITCH_FIRST_PROBE_MS)
        repeat(SWITCH_ATTEMPTS) { attempt ->
            if (InternetCheck.status().blocksConnect) return null
            if (tunnel.checkQuick()) return true
            if (attempt < SWITCH_ATTEMPTS - 1) delay(SWITCH_RETRY_PAUSE_MS)
        }
        return false
    }

    private fun onSwitched(target: ServerEntity, origin: SwitchOrigin, checked: Boolean) {
        if (checked) lastGoodId = target.id
        val auto = origin == SwitchOrigin.AUTO && settings.connectMode != ConnectMode.MANUAL
        store.update { d ->
            val group = when (target.role) {
                ServerRole.NORMAL -> BalancerScope.NORMAL.name
                ServerRole.BYPASS_WHITELIST -> BalancerScope.BYPASS.name
                ServerRole.AUTO -> null
            }
            d.copy(
                servers = d.servers.map {
                    if (it.id == target.id) it.copy(isSelected = true, failStreak = if (checked) 0 else it.failStreak)
                    else it.copy(isSelected = false)
                },
                remembered = if (auto && checked && group != null) d.remembered + (group to target.id) else d.remembered
            )
        }
        runtime.update {
            it.copy(
                vpnState = VpnConnectionState.CONNECTED,
                phase = ConnectPhase.IDLE,
                connectedServerId = target.id,
                error = null,
                routingDirty = false,
                switching = null
            )
        }
        startGuard()
        scope.launch(Dispatchers.IO) { refreshIp() }
    }

    /** Holding the connect lock: the node the tunnel was just moved to didn't come up or doesn't answer. */
    private suspend fun switchFailed(target: ServerEntity, origin: SwitchOrigin, reason: String) {
        markFailed(target.id)
        val back = lastGoodId?.let { id -> store.value.servers.firstOrNull { it.id == id } }
        when (TunnelRequests.fallbackFor(origin, target.id, back?.id)) {
            Fallback.BACK -> {
                val home = back ?: return failForward(target, reason)
                store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == home.id) }) }
                val text = Res.string(
                    R.string.home_switch_back,
                    Countries.withoutLeadingFlag(target.remark),
                    Countries.withoutLeadingFlag(home.remark)
                )
                message(text)
                _notices.tryEmit(text)
                hotSwap(home, SwitchOrigin.FALLBACK)
            }

            Fallback.NEXT -> failForward(target, reason)
        }
    }

    /**
     * Nowhere to go back to. In an auto mode the next best node takes over (a connect
     * without the failed one); otherwise the tunnel goes down with the reason, and the
     * user's proxy comes back.
     */
    private suspend fun failForward(failed: ServerEntity, reason: String) {
        runtime.update { it.copy(switching = null) }
        if (settings.connectMode != ConnectMode.MANUAL && settings.autoFailover) {
            connectLocked(exclude = failed.id)
            return
        }
        withContext(Dispatchers.IO) { stopTunnel() }
        lastGoodId = null
        runtime.update {
            it.copy(
                vpnState = VpnConnectionState.ERROR,
                phase = ConnectPhase.IDLE,
                error = reason,
                connectedServerId = null,
                ports = null,
                proxyActive = false,
                switching = null
            )
        }
    }

    private fun onCoreDied(code: Int) {
        if (runtime.value.vpnState != VpnConnectionState.CONNECTED) return
        trafficJob?.cancel()
        guardJob?.cancel()
        refineJob?.cancel()
        stopHelpers()
        flushTraffic()
        lastGoodId = null
        runtime.update {
            it.copy(
                vpnState = VpnConnectionState.ERROR,
                error = I18n.s.errCoreStopped + if (code != 0) " ($code)" else "",
                ports = null,
                proxyActive = false,
                switching = null
            )
        }
        if (settings.autoFailover) {
            scope.launch {
                delay(1_500)
                if (runtime.value.vpnState == VpnConnectionState.ERROR) connect()
            }
        }
    }

    /**
     * The core's counters, read every two seconds. They start at nothing with each core,
     * so a switch (a new core under the same tunnel) carries on counting: a reading lower
     * than the one before means a fresh core.
     */
    private fun startTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = scope.launch(Dispatchers.IO) {
            var last: TrafficMeter.Totals? = null
            var lastAt = System.nanoTime()
            var sessionUp = 0L
            var sessionDown = 0L
            while (isActive) {
                delay(2_000)
                val ports = tunnel.ports ?: continue
                val now = TrafficMeter.read(ports.metrics) ?: continue
                val t = System.nanoTime()
                val seconds = ((t - lastAt) / 1e9).coerceAtLeast(0.5)
                val prev = last?.takeIf { now.uplink >= it.uplink && now.downlink >= it.downlink }
                val dUp = (now.uplink - (prev?.uplink ?: 0)).coerceAtLeast(0)
                val dDown = (now.downlink - (prev?.downlink ?: 0)).coerceAtLeast(0)
                val up = if (last == null) 0 else (dUp / seconds).toLong()
                val down = if (last == null) 0 else (dDown / seconds).toLong()
                // A fresh core's first reading counts whole.
                countTraffic(dUp, dDown)
                sessionUp += dUp
                sessionDown += dDown
                last = now
                lastAt = t
                runtime.update {
                    it.copy(
                        traffic = TrafficStats(sessionUp, sessionDown, up, down),
                        speed = it.speed.push(down, up)
                    )
                }
            }
        }
    }

    /**
     * Every 30 s while connected: does the tunnel still carry traffic? Two misses move it
     * in an auto mode, as a switch: the tunnel stays up while the next node is found.
     */
    private fun startGuard() {
        guardJob?.cancel()
        guardJob = scope.launch(Dispatchers.IO) {
            var misses = 0
            while (isActive) {
                delay(30_000)
                if (runtime.value.vpnState != VpnConnectionState.CONNECTED || runtime.value.switching != null) continue
                if (tunnel.check(10_000)) {
                    misses = 0
                    continue
                }
                misses++
                if (misses < 2) continue
                misses = 0
                val failed = runtime.value.connectedServerId
                if (settings.autoFailover && settings.connectMode != ConnectMode.MANUAL && connectJob?.isActive != true) {
                    failed?.let(::markFailed)
                    val mode = settings.connectMode
                    connectJob = scope.launch {
                        connectLock.withLock {
                            if (!ui.value.isConnected) return@withLock
                            val next = runCatching { pickForMode(mode, exclude = failed) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()?.firstOrNull()
                            runtime.update { it.copy(phase = ConnectPhase.IDLE) }
                            if (next == null || next.id == failed) {
                                message(I18n.s.msgServerSilent)
                                return@withLock
                            }
                            hotSwap(store.value.servers.firstOrNull { it.id == next.id } ?: next, SwitchOrigin.AUTO)
                        }
                        val now = ui.value.selectedServer
                        if (now != null && now.id != failed && ui.value.isConnected) {
                            message(I18n.s.msgSwitched(now.remark))
                        }
                    }
                } else {
                    message(I18n.s.msgServerSilent)
                }
            }
        }
    }

    private data class RoutingChoice(
        val profile: RoutingProfileEntity?,
        val providerJson: String,
        val geo: GeoNames?,
        val preferServerRouting: Boolean
    )

    /** The same isolation rules as on Android (see RoutingResolver). */
    private fun routingFor(server: ServerEntity): RoutingChoice {
        val d = store.value
        val sub = server.subscriptionId?.let { id -> d.subscriptions.firstOrNull { it.id == id } }
        val profiles = d.routingProfiles
        val profile = RoutingResolver.resolve(sub, profiles)
        val geo = if (sub != null && profile?.sourceSubscriptionId == sub.id) geoData.installed(sub.id) else null
        val explicitAppProfile = sub != null && sub.routingProfileId > 0 &&
            profiles.firstOrNull { it.id == sub.routingProfileId }?.sourceSubscriptionId == null
        return RoutingChoice(
            profile = profile,
            providerJson = RoutingResolver.providerJsonFor(sub, profile),
            geo = geo,
            preferServerRouting = sub != null && !explicitAppProfile
        )
    }

    // ================================================================ network and address

    fun recheckNetwork() {
        scope.launch(Dispatchers.IO) { runCatching { probeNetworkInternal() } }
    }

    private suspend fun probeNetworkInternal() {
        val outcome = NetworkProbe().run()
        store.update { it.copy(networkRegime = outcome.regime) }
        runtime.update { it.copy(probes = outcome.probes) }
    }

    private suspend fun refreshIp() {
        val ports = if (ui.value.isConnected) tunnel.ports else null
        val info = runCatching { IpChecker.fetch(ports?.exitCheck) }.getOrNull() ?: return
        if (info.ip.isNotBlank() || runtime.value.ipInfo == null) runtime.update { it.copy(ipInfo = info) }
    }

    // ================================================================ servers

    private val _sweep = MutableStateFlow(SweepBoard.IDLE)

    /** "Check all" as it goes; each row reads its own entry (see [SweepBoard]). */
    val sweep: StateFlow<SweepBoard> = _sweep.asStateFlow()

    private var sweepJob: Job? = null

    /**
     * Checks every server of [pool], a handful at a time. Results land in [sweep] as they
     * come, so the list fills in row by row; the store gets them all at once at the end
     * (one write, one new list), so a list sorted by speed is re-sorted once, not with
     * every answer, and nothing jumps under the pointer. [cancelPingAll] stops it and
     * keeps what was measured.
     */
    fun pingAll(pool: ServerPool = ServerPool.ALL) {
        if (sweepJob?.isActive == true) return
        sweepJob = scope.launch(Dispatchers.IO) {
            val list = store.value.servers.filter { pool.matches(it.role) }.map(WindowsCompat::adapt)
            if (list.isEmpty()) return@launch
            val landed = java.util.concurrent.ConcurrentHashMap<Long, PingResult>()
            _sweep.value = SweepBoard.IDLE.start(list.map { it.id })
            try {
                engine(sweep = true).pingServers(
                    servers = list,
                    type = settings.pingType,
                    maxParallel = PingEngine.SWEEP_PARALLEL,
                    realDelayTimeoutMs = PingEngine.SWEEP_TIMEOUT_MS
                ) { id, r ->
                    landed[id] = r
                    _sweep.update { it.record(id, r) }
                }
                message(I18n.s.msgPingDone(landed.count { it.value.success }, list.size))
            } catch (e: CancellationException) {
                val board = _sweep.value
                if (board.total > 0) message(Res.string(R.string.servers_check_stopped, board.done, board.total))
                throw e
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    persistHealthAll(landed)
                    _sweep.update { it.finish() }
                }
            }
        }
    }

    /** Stops "check all"; what was measured stays. */
    fun cancelPingAll() {
        sweepJob?.cancel()
        sweepJob = null
    }

    /**
     * Checks one server. The menu that asked shows the answer in place
     * ([RuntimeState.serverCheck]); closed meanwhile, the answer comes as a message instead.
     */
    fun pingServer(id: Long) {
        scope.launch(Dispatchers.IO) {
            val server = store.value.servers.firstOrNull { it.id == id }?.let(WindowsCompat::adapt) ?: return@launch
            runtime.update { it.copy(serverCheck = ServerCheck(id, running = true)) }
            val done = try {
                val r = engine().pingServer(server, settings.pingType)
                persistHealth(id, r)
                val text = when {
                    r.success && r.estimate -> {
                        // OpenFlux judged from outside: its carrier answered, the exit is
                        // measured for real once traffic goes through it.
                        val channel = r.message.takeIf { it.isNotBlank() }
                        if (channel != null) UiText.of(R.string.home_ping_estimate_channel, "${r.latencyMs}", channel)
                        else UiText.of(R.string.home_ping_estimate, "${r.latencyMs}")
                    }
                    r.success -> UiText.of(R.string.home_ping_result, "${r.latencyMs}")
                    else -> r.message.takeIf { it.isNotBlank() }?.let(UiText::Raw)
                        ?: UiText.of(R.string.home_server_not_responding)
                }
                ServerCheck(id, running = false, text = text, ok = r.success, latencyMs = r.latencyMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ServerCheck(id, running = false, text = e.uiText(UiText.of(R.string.home_ping_failed)))
            }
            if (runtime.value.serverCheck?.serverId == id) {
                runtime.update { it.copy(serverCheck = done) }
            } else {
                done.text?.let { message("${Countries.withoutLeadingFlag(server.remark)}: ${it.resolve()}") }
            }
        }
    }

    /** The menu showing a check closed: a late answer goes to a message. */
    fun clearServerCheck() {
        runtime.update { it.copy(serverCheck = null) }
    }

    /** One of the three "best server in the group" pads. */
    fun runBalancer(scope: BalancerScope) {
        this@VoltaController.scope.launch {
            if (runtime.value.runningScope != null) return@launch
            runtime.update { it.copy(runningScope = scope) }
            try {
                val all = autoPool(store.value.servers, scope).map(WindowsCompat::adapt)
                val decision = AutoBalancer(engine(), NetworkProbe()).choose(
                    servers = all,
                    scope = scope,
                    current = all.firstOrNull { it.isSelected },
                    pingType = settings.pingType
                )
                applyDecision(decision)
                val pick = decision.selected
                if (pick == null) {
                    message(if (decision.retryLater) I18n.s.errNothingAnswered else I18n.s.errGroupEmpty)
                } else {
                    val mode = when (scope) {
                        BalancerScope.ALL -> ConnectMode.AUTO_HOME_LTE
                        BalancerScope.NORMAL -> ConnectMode.AUTO_HOME
                        BalancerScope.BYPASS -> ConnectMode.AUTO_LTE
                    }
                    updateSettings { it.copy(connectMode = mode) }
                    store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == pick.id) }) }
                    message(I18n.s.msgPicked(pick.remark))
                    if (ui.value.isConnected && runtime.value.connectedServerId != pick.id && connectJob?.isActive != true) {
                        refineJob?.cancel()
                        switcher.cancel()
                        val target = store.value.servers.firstOrNull { it.id == pick.id }
                        if (target != null) {
                            connectJob = this@VoltaController.scope.launch {
                                connectLock.withLock { if (ui.value.isConnected) hotSwap(target, SwitchOrigin.AUTO) }
                            }
                        }
                    }
                }
            } finally {
                runtime.update { it.copy(runningScope = null) }
            }
        }
    }

    private fun persistHealth(id: Long, result: PingResult) {
        store.update { d -> d.copy(servers = d.servers.map { s -> if (s.id != id) s else healthOf(s, result) }) }
    }

    /** A whole sweep's answers in one write: the list changes (and re-sorts) once. */
    private fun persistHealthAll(results: Map<Long, PingResult>) {
        if (results.isEmpty()) return
        store.update { d -> d.copy(servers = d.servers.map { s -> results[s.id]?.let { healthOf(s, it) } ?: s }) }
    }

    private fun healthOf(s: ServerEntity, result: PingResult): ServerEntity {
        val ok = result.success && result.latencyMs >= 0
        return s.copy(
            latencyMs = if (ok) result.latencyMs else -1,
            ewmaMs = if (ok && !result.estimate) AutoBalancer.blendEwma(s.ewmaMs, result.latencyMs) else s.ewmaMs,
            failStreak = if (ok) 0 else (s.failStreak + 1).coerceAtMost(9),
            lastPingAt = result.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    fun toggleFavorite(id: Long) {
        store.update { d -> d.copy(servers = d.servers.map { if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it }) }
    }

    fun setServerRole(id: Long, role: ServerRole) {
        store.update { d ->
            d.copy(servers = d.servers.map { if (it.id == id) it.copy(role = role, roleManual = true) else it })
        }
    }

    fun deleteServer(id: Long) {
        val gone = store.value.servers.firstOrNull { it.id == id } ?: return
        store.update { d -> d.copy(servers = d.servers.filter { it.id != id }) }
        if (gone.isSelected) selectReplacement(gone)
        reconcileTunnel()
    }

    /** Who takes over when the selected server disappears: one of the same kind, the best first. */
    private fun selectReplacement(gone: ServerEntity?) {
        val dialable = store.value.servers.filter { ConfigBuilder.canDial(WindowsCompat.adapt(it)) }
        val regular = dialable.filter { it.role != ServerRole.AUTO }
        val sameKind = regular.filter { gone != null && it.role == gone.role }
        val next = sameKind.ifEmpty { regular }.ifEmpty { dialable }.minByOrNull { it.score } ?: return
        store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == next.id) }) }
    }

    /** The server under the tunnel was removed: move to the selected one, or stop. */
    private fun reconcileTunnel() {
        if (!ui.value.isConnected) return
        val live = runtime.value.connectedServerId ?: return
        if (store.value.servers.any { it.id == live }) return
        val next = store.value.servers.firstOrNull { it.isSelected }
        if (next == null) disconnect() else {
            connectJob?.cancel()
            connectJob = scope.launch { runConnect(fixed = next.id) }
        }
    }

    // ================================================================ subscriptions

    /** Adds a subscription or a server, whatever was pasted. */
    fun importText(text: String, name: String = "", onComplete: (Boolean) -> Unit = {}) {
        val value = text.trim()
        scope.launch(Dispatchers.IO) {
            val ok = runCatching { importInternal(value, name) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    message(e.userMessage(I18n.s.errAddSubscription))
                }
                .isSuccess
            withContext(Dispatchers.immediateOrDefault()) { onComplete(ok) }
        }
    }

    private suspend fun importInternal(value: String, name: String) = busy {
        val s = I18n.s
        if (value.startsWith("volta://", ignoreCase = true)) {
            handleVoltaLink(value, name)
            return@busy
        }
        val content = com.ars3nb.volta.core.openflux.OpenFluxLink.unwrap(value) ?: value
        when {
            content.startsWith("http://", true) || content.startsWith("https://", true) -> {
                val id = addSubscriptionInternal(name, content)
                val count = store.value.servers.count { it.subscriptionId == id }
                message(if (count > 0) s.msgAddedServers(count) else s.msgSubscriptionAdded)
            }

            else -> {
                val n = importServers(content)
                if (n <= 0) error(s.errNothingToImport)
                message(if (n == 1) s.msgServerAdded else s.msgImported(n))
            }
        }
    }

    private suspend fun handleVoltaLink(link: String, nameOverride: String) {
        val s = I18n.s
        val action = when (val a = VoltaLinks.parse(link)) {
            is VoltaLinks.AddEncrypted -> VoltaLinks.openEncrypted(a)
            else -> a
        }
        when (action) {
            is VoltaLinks.AddSubscription -> {
                val id = addSubscriptionInternal(
                    name = nameOverride.ifBlank { action.name },
                    url = action.url,
                    hidden = action.hidden,
                    serverTypes = action.serverTypes,
                    reveal = action.visible == true
                )
                val count = store.value.servers.count { it.subscriptionId == id }
                message(if (count > 0) s.msgAddedServers(count) else s.msgSubscriptionAdded)
            }

            is VoltaLinks.ImportContent -> {
                val n = importServers(action.value)
                if (n <= 0) error(s.errNothingToImport)
                message(if (n == 1) s.msgServerAdded else s.msgImported(n))
            }

            is VoltaLinks.Connect -> {
                action.mode?.let { m -> updateSettings { it.copy(connectMode = m) } }
                connect()
            }

            VoltaLinks.Disconnect -> disconnect()
            VoltaLinks.Toggle -> toggleConnect()
            is VoltaLinks.Invalid -> error(action.message)
            is VoltaLinks.AddEncrypted -> error(s.errNotRecognized)
        }
    }

    /** Share links, a .conf, a vpn:// key or JSON: added to the list as servers without a subscription. */
    private fun importServers(content: String): Int {
        val parsed = when {
            AmneziaConfigs.looksLikeConf(content) -> listOfNotNull(AmneziaConfigs.parseConf(content))
            content.startsWith("vpn://", true) -> AmneziaConfigs.parseAmneziaKey(content)
            else -> ConfigParser.parseSubscriptionBody(content).servers
        }.map { ServerClassifier.classify(WindowsCompat.adapt(it)) }
        if (parsed.isEmpty()) return 0
        // Nothing here this app can open: say so instead of filling the list with dead rows.
        if (parsed.all { WindowsCompat.needsHelper(it) && ConfigBuilder.dialProblem(it) != null }) {
            error(ConfigBuilder.dialProblem(parsed.first()) ?: I18n.s.errNotOnWindows(parsed.first().protocol.label))
        }
        val ids = store.newIds(parsed.size)
        store.update { d ->
            val added = parsed.mapIndexed { i, srv -> srv.copy(id = ids[i], subscriptionId = null, sortOrder = d.servers.size + i) }
            val anySelected = d.servers.any { it.isSelected }
            val servers = d.servers + added
            d.copy(servers = if (anySelected) servers else selectBest(servers))
        }
        return parsed.size
    }

    private fun selectBest(servers: List<ServerEntity>): List<ServerEntity> {
        val dialable = servers.filter { ConfigBuilder.canDial(WindowsCompat.adapt(it)) }
        val pick = (dialable.filter { it.role != ServerRole.AUTO }.ifEmpty { dialable }.ifEmpty { servers })
            .minByOrNull { it.score } ?: return servers
        return servers.map { it.copy(isSelected = it.id == pick.id) }
    }

    fun addSubscription(name: String, url: String, onComplete: (Boolean) -> Unit = {}) =
        importText(url, name, onComplete)

    private suspend fun addSubscriptionInternal(
        name: String,
        url: String,
        hidden: Boolean = false,
        serverTypes: String = "",
        reveal: Boolean = false
    ): Long {
        val s = I18n.s
        val normalized = SubscriptionFetcher.normalizeUrl(url)
        require(normalized.isNotBlank()) { s.errPasteLink }
        require(!normalized.startsWith("http://", ignoreCase = true)) { s.errHttps }
        val types = ServerMarkup.normalize(serverTypes)
        store.value.subscriptions.firstOrNull { it.url == normalized }?.let { existing ->
            val updated = existing.copy(
                name = name.trim().ifBlank { existing.name },
                nameLocked = existing.nameLocked || name.isNotBlank(),
                urlHidden = when {
                    hidden -> true
                    reveal -> false
                    else -> existing.urlHidden
                },
                serverTypes = types.ifBlank { existing.serverTypes },
                isEnabled = true
            )
            store.update { d -> d.copy(subscriptions = d.subscriptions.map { if (it.id == existing.id) updated else it }) }
            try {
                refreshSubscriptionInternal(existing.id)
            } catch (e: Exception) {
                store.update { d -> d.copy(subscriptions = d.subscriptions.map { if (it.id == existing.id) existing else it }) }
                throw e
            }
            return existing.id
        }
        val id = store.newIds(1).first()
        store.update { d ->
            d.copy(
                subscriptions = d.subscriptions + SubscriptionEntity(
                    id = id,
                    name = name.trim().ifBlank { Res.string(R.string.home_default_subscription_name) },
                    nameLocked = name.trim().isNotBlank(),
                    url = normalized,
                    colorHex = SUBSCRIPTION_COLORS[d.subscriptions.size % SUBSCRIPTION_COLORS.size],
                    urlHidden = hidden,
                    serverTypes = types,
                    sortOrder = d.subscriptions.size
                )
            )
        }
        try {
            refreshSubscriptionInternal(id)
        } catch (e: Exception) {
            store.update { d -> d.copy(subscriptions = d.subscriptions.filter { it.id != id }) }
            throw e
        }
        return id
    }

    fun refreshSubscription(id: Long) {
        scope.launch(Dispatchers.IO) {
            busy {
                try {
                    refreshSubscriptionInternal(id)
                    val n = store.value.servers.count { it.subscriptionId == id }
                    message(I18n.s.msgSubscriptionUpdated(n))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message(e.userMessage(I18n.s.errRefresh))
                }
            }
        }
    }

    fun refreshAll() {
        scope.launch(Dispatchers.IO) { busy { refreshAllInternal(quiet = false) } }
    }

    private suspend fun refreshAllInternal(quiet: Boolean) {
        val subs = store.value.subscriptions.filter { it.isEnabled && it.url.startsWith("http", true) }
        var ok = 0
        for (sub in subs) {
            val r = runCatching { refreshSubscriptionInternal(sub.id) }
            if (r.isSuccess) ok++
            else if (!quiet) r.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        }
        if (!quiet || ok < subs.size) message(I18n.s.msgRefreshedAll(ok, subs.size))
    }

    private suspend fun refreshSubscriptionInternal(id: Long) = withContext(Dispatchers.IO) {
        val s = I18n.s
        val sub = store.value.subscriptions.firstOrNull { it.id == id } ?: return@withContext
        val hwid = if (settings.sendHwid) hwid() else null
        val fetched = try {
            try {
                SubscriptionFetcher.fetch(sub.url, sub.userAgent, AppPaths.appVersion, hwid)
            } catch (direct: Exception) {
                // Blocked directly? Once more through the tunnel, when there is one.
                val port = tunnel.ports?.http
                if (port == null || !ui.value.isConnected) throw direct
                runCatching { SubscriptionFetcher.fetch(sub.url, sub.userAgent, AppPaths.appVersion, hwid, port) }
                    .getOrElse { throw direct }
            }
        } catch (e: Exception) {
            if (sub.urlHidden) throw IllegalStateException(SubscriptionFetcher.conceal(e.message, sub.url))
            throw e
        }
        val body = fetched.body
        val parsed = ConfigParser.parseSubscriptionBody(body, subscriptionId = id)
        val incoming = parsed.servers.map(WindowsCompat::adapt)

        val serverName = parsed.title.trim()
        val displayName = if (sub.nameLocked) sub.name else serverName.ifBlank {
            incoming.firstOrNull()?.remark?.takeIf { it.isNotBlank() }
        }.orEmpty().ifBlank { sub.name }

        val merged = ServerClassifier.mergeProviderUpdate(
            previous = store.value.servers.filter { it.subscriptionId == id },
            incoming = incoming,
            subscriptionId = id,
            serverTypes = sub.serverTypes
        )
        if (merged.isEmpty()) error(if (parsed.skipped.isNotEmpty()) s.errOnlyUnsupported else s.errNoServers)
        val newIds = store.newIds(merged.count { it.id == 0L }).iterator()
        val withIds = merged.map { if (it.id == 0L) it.copy(id = newIds.next()) else it }

        // Routing the provider shipped (header, happ://, Happ JSON), as on Android.
        val jsonConfigs = looksLikeXrayConfigs(body) && incoming.none { it.roleFromProvider }
        val routing = HappRoutingParser.parseRoutingLink(fetched.routingHeader)
            ?: if (jsonConfigs) null else {
                HappRoutingParser.extractFromBodyAndHeaders(body = body, routingHeader = "")
                    ?: parsed.routingJson.takeIf { it.isNotBlank() }?.let {
                        HappRoutingParser.extractFromBodyAndHeaders(it, "")
                    }
            }
        val providerRoutingJson = when {
            routing != null -> routing.rawJson.ifBlank { RoutingEngine.serializeRules(routing.rules) }
            jsonConfigs -> ""
            else -> parsed.routingJson
        }

        var boundProfileId = sub.routingProfileId
        if (routing != null && routing.rules.isNotEmpty()) {
            val profileId = applySubscriptionRouting(id, displayName, routing)
            if (boundProfileId == 0L) boundProfileId = profileId
        } else if (providerRoutingJson.isBlank()) {
            store.value.routingProfiles.firstOrNull { it.sourceSubscriptionId == id }?.let { stale ->
                store.update { d -> d.copy(routingProfiles = d.routingProfiles.filter { it.id != stale.id }) }
                if (boundProfileId == stale.id) boundProfileId = 0L
            }
            geoData.delete(id)
        }

        store.update { d ->
            val others = d.servers.filter { it.subscriptionId != id }
            var servers = others + withIds
            if (servers.none { it.isSelected }) servers = selectBest(servers)
            d.copy(
                servers = servers,
                subscriptions = d.subscriptions.map {
                    if (it.id != id) it else it.copy(
                        name = displayName,
                        lastUpdated = System.currentTimeMillis(),
                        rawBody = "",
                        expireAt = parsed.expireAt,
                        trafficUsed = parsed.trafficUsed,
                        trafficTotal = parsed.trafficTotal,
                        announce = parsed.announce,
                        supportUrl = parsed.supportUrl,
                        profileWebPageUrl = parsed.webPageUrl,
                        providerRoutingJson = providerRoutingJson,
                        updateIntervalHours = parsed.updateIntervalHours,
                        routingProfileId = boundProfileId
                    )
                }
            )
        }
        reconcileTunnel()
    }

    private fun applySubscriptionRouting(
        subscriptionId: Long,
        displayName: String,
        routing: HappRoutingParser.ParsedRouting
    ): Long {
        val existing = store.value.routingProfiles.firstOrNull { it.sourceSubscriptionId == subscriptionId }
        val userRules = existing?.let { RoutingEngine.parseRules(it.rulesJson) }?.filter { it.source == "user" }.orEmpty()
        val mergedRules = (userRules + routing.rules).mapIndexed { i, r -> r.copy(sortOrder = i) }
        val id = existing?.id ?: store.newIds(1).first()
        val entity = RoutingProfileEntity(
            id = id,
            name = routing.name.ifBlank { Res.string(R.string.home_routing_rules_name, displayName) },
            description = Res.string(R.string.home_routing_from_subscription, displayName),
            isActive = false,
            isBuiltIn = false,
            sourceSubscriptionId = subscriptionId,
            useProviderRules = existing?.useProviderRules ?: true,
            rulesJson = RoutingEngine.serializeRules(mergedRules),
            domainStrategy = routing.domainStrategy.ifBlank { "AsIs" },
            remoteDns = routing.remoteDns.ifBlank { existing?.remoteDns ?: "https://1.1.1.1/dns-query" },
            domesticDns = routing.domesticDns.ifBlank { existing?.domesticDns ?: "https://dns.google/dns-query" },
            updatedAt = System.currentTimeMillis(),
            geoipUrl = routing.geoipUrl,
            geositeUrl = routing.geositeUrl,
            geoVersion = routing.geoVersion,
            geoUpdatedAt = existing?.geoUpdatedAt ?: 0,
            dnsHostsJson = routing.dnsHostsJson
        )
        val withGeo = when {
            entity.geoipUrl.isBlank() && entity.geositeUrl.isBlank() -> {
                geoData.delete(subscriptionId)
                entity.copy(geoUpdatedAt = 0)
            }

            geoData.isStale(subscriptionId, entity, existing) &&
                (geoData.download(subscriptionId, entity) ||
                    (ui.value.isConnected && geoData.download(subscriptionId, entity, tunnel.ports?.http))) ->
                entity.copy(geoUpdatedAt = System.currentTimeMillis())

            else -> entity
        }
        store.update { d ->
            d.copy(routingProfiles = d.routingProfiles.filter { it.id != id } + withGeo)
        }
        return id
    }

    private fun looksLikeXrayConfigs(body: String): Boolean {
        val payload = body.lineSequence().filterNot { it.trimStart().startsWith("#") }.joinToString("\n").trim()
        return (payload.startsWith("[") || payload.startsWith("{")) && payload.contains("\"outbounds\"")
    }

    fun updateSubscription(id: Long, name: String, url: String, onComplete: (Boolean) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val ok = busy {
                runCatching {
                    val s = I18n.s
                    val current = store.value.subscriptions.firstOrNull { it.id == id } ?: return@runCatching
                    val normalized = if (current.urlHidden) current.url else SubscriptionFetcher.normalizeUrl(url)
                    require(normalized.isNotBlank()) { s.errPasteLink }
                    require(!normalized.startsWith("http://", ignoreCase = true)) { s.errHttps }
                    val updated = current.copy(
                        name = name.trim().ifBlank { current.name },
                        nameLocked = name.trim().isNotBlank(),
                        url = normalized
                    )
                    store.update { d -> d.copy(subscriptions = d.subscriptions.map { if (it.id == id) updated else it }) }
                    if (updated.url != current.url) {
                        try {
                            refreshSubscriptionInternal(id)
                        } catch (e: Exception) {
                            store.update { d -> d.copy(subscriptions = d.subscriptions.map { if (it.id == id) current else it }) }
                            throw e
                        }
                    }
                }.onFailure { message(it.userMessage(I18n.s.errRefresh)) }.isSuccess
            }
            withContext(Dispatchers.immediateOrDefault()) { onComplete(ok) }
        }
    }

    fun deleteSubscription(id: Long) {
        scope.launch(Dispatchers.IO) {
            val profile = store.value.routingProfiles.firstOrNull { it.sourceSubscriptionId == id }
            store.update { d ->
                d.copy(
                    subscriptions = d.subscriptions.filter { it.id != id }.map {
                        if (profile != null && it.routingProfileId == profile.id) it.copy(routingProfileId = 0) else it
                    },
                    servers = d.servers.filter { it.subscriptionId != id },
                    routingProfiles = d.routingProfiles.filter { it.sourceSubscriptionId != id }
                )
            }
            geoData.delete(id)
            if (settings.autoSubscription == id) updateSettings { it.copy(autoSubscription = 0) }
            gemini.forget(store.value.servers.map { it.id }.toSet())
            if (store.value.servers.none { it.isSelected }) selectReplacement(null)
            reconcileTunnel()
            message(I18n.s.msgDeleted)
        }
    }

    // ================================================================ filters and routing

    /** Changes that reach the running tunnel only with a reconnect: the screen offers one. */
    private fun touchRouting() {
        if (ui.value.isConnected) runtime.update { it.copy(routingDirty = true) }
    }

    /** Reconnects so pending routing changes reach the running tunnel. */
    fun applyRoutingNow() {
        runtime.update { it.copy(routingDirty = false) }
        if (ui.value.isConnected && connectJob?.isActive != true) {
            val current = runtime.value.connectedServerId
            connectJob = scope.launch { runConnect(fixed = current) }
        }
    }

    fun setAdBlock(mode: AdBlockMode) {
        updateSettings { it.copy(adBlock = mode) }
        touchRouting()
    }

    fun setBlockTorrents(v: Boolean) {
        updateSettings { it.copy(blockTorrents = v) }
        touchRouting()
    }

    /** The app-wide rule set (one of the user's, or a built-in one). */
    fun setActiveRouting(id: Long) {
        val target = store.value.routingProfiles.firstOrNull { it.id == id && it.sourceSubscriptionId == null } ?: return
        store.update { d -> d.copy(routingProfiles = d.routingProfiles.map { it.copy(isActive = it.id == target.id) }) }
        touchRouting()
    }

    fun createRoutingProfile(name: String) {
        val id = store.newIds(1).first()
        store.update { d ->
            d.copy(
                routingProfiles = d.routingProfiles + RoutingProfileEntity(
                    id = id,
                    name = name.trim(),
                    // Stored with the set: written in the language it was made in.
                    description = Res.string(R.string.home_routing_own_description),
                    rulesJson = RoutingEngine.serializeRules(emptyList()),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        message(Res.string(R.string.home_routing_created))
    }

    fun renameRoutingProfile(id: Long, name: String) {
        if (name.isBlank()) return
        store.update { d ->
            d.copy(routingProfiles = d.routingProfiles.map {
                if (it.id == id && !it.isBuiltIn) it.copy(name = name.trim(), updatedAt = System.currentTimeMillis()) else it
            })
        }
    }

    /** Detaches a copy from its subscription (or a built-in set) so the user can edit it freely. */
    fun copyRoutingProfile(id: Long) {
        val source = store.value.routingProfiles.firstOrNull { it.id == id } ?: run {
            message(Res.string(R.string.home_err_rules_not_found))
            return
        }
        val newId = store.newIds(1).first()
        val shown = RoutingNames.shownProfileName(source)
        store.update { d ->
            d.copy(
                routingProfiles = d.routingProfiles + source.copy(
                    id = newId,
                    name = Res.string(R.string.home_routing_copy_name, shown),
                    description = Res.string(R.string.home_routing_copy_description, shown),
                    isActive = false,
                    isBuiltIn = false,
                    sourceSubscriptionId = null,
                    useProviderRules = false,
                    rulesJson = RoutingEngine.serializeRules(
                        RoutingEngine.parseRules(source.rulesJson).map { it.copy(source = "user") }
                    ),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        message(Res.string(R.string.home_routing_copy_created))
    }

    fun deleteRoutingProfile(id: Long) {
        val profile = store.value.routingProfiles.firstOrNull { it.id == id } ?: return
        if (profile.isBuiltIn) return
        store.update { d ->
            val left = d.routingProfiles.filter { it.id != id }
            val fixed = if (left.none { it.isActive && it.sourceSubscriptionId == null }) {
                val first = left.firstOrNull { it.isBuiltIn }?.id
                left.map { it.copy(isActive = it.id == first) }
            } else left
            d.copy(
                routingProfiles = fixed,
                subscriptions = d.subscriptions.map { if (it.routingProfileId == id) it.copy(routingProfileId = 0) else it }
            )
        }
        touchRouting()
    }

    fun saveRules(profileId: Long, rules: List<RoutingRuleDto>) {
        store.update { d ->
            d.copy(routingProfiles = d.routingProfiles.map {
                if (it.id == profileId) it.copy(rulesJson = RoutingEngine.serializeRules(rules), updatedAt = System.currentTimeMillis())
                else it
            })
        }
        touchRouting()
    }

    fun setRoutingProviderRules(profileId: Long, enabled: Boolean) {
        store.update { d ->
            d.copy(routingProfiles = d.routingProfiles.map {
                if (it.id == profileId) it.copy(useProviderRules = enabled, updatedAt = System.currentTimeMillis()) else it
            })
        }
        touchRouting()
    }

    /** Binds a rule set to one subscription (0 = the app-wide one); never another subscription's set. */
    fun bindSubscriptionRouting(subscriptionId: Long, profileId: Long) {
        if (profileId != 0L) {
            val profile = store.value.routingProfiles.firstOrNull { it.id == profileId }
            if (profile == null) {
                message(Res.string(R.string.home_err_rules_not_found))
                return
            }
            if (profile.sourceSubscriptionId != null && profile.sourceSubscriptionId != subscriptionId) {
                message(Res.string(R.string.home_err_rules_of_other_subscription))
                return
            }
        }
        store.update { d ->
            d.copy(subscriptions = d.subscriptions.map { if (it.id == subscriptionId) it.copy(routingProfileId = profileId) else it })
        }
        touchRouting()
        message(Res.string(R.string.home_rules_applied))
    }

    // ================================================================ sites past the VPN

    /** Sites that skip Volta altogether (Windows' proxy bypass list); applied at once. */
    fun setBypassSites(sites: List<String>) {
        val clean = sites.mapNotNull(SystemProxy.Companion::normalizeSite).distinct()
        updateSettings { it.copy(bypassSites = clean) }
        scope.launch(Dispatchers.IO) {
            if (ui.value.isConnected && settings.systemProxy && tunnel.systemProxy.isApplied()) {
                if (!tunnel.applySystemProxy(clean)) message(I18n.s.msgProxyFailed)
            }
        }
    }

    // ================================================================ updates

    fun setUpdateAutoCheck(v: Boolean) {
        updateSettings { it.copy(updateAutoCheck = v) }
        if (v) checkUpdates()
    }

    fun setUpdateBeta(v: Boolean) {
        updateSettings { it.copy(updateBeta = v) }
        scope.launch(Dispatchers.IO) { runCatching { updater.recheck() } }
    }

    fun checkUpdates() {
        scope.launch(Dispatchers.IO) { runCatching { updater.check() } }
    }

    fun installUpdate() = updater.downloadAndInstall()

    /**
     * Starts the downloaded installer on its own (not as Volta's child, so it outlives
     * Volta), then quits so the installer can replace the files. The tunnel goes down and
     * the user's proxy comes back on the way out.
     */
    private fun launchInstaller(file: java.io.File): Boolean {
        if (!file.isFile) return false
        val started = runCatching {
            if (com.ars3nb.volta.desktop.platform.WinApi.isWindows) {
                ProcessBuilder("cmd.exe", "/c", "start", "\"\"", file.absolutePath).start()
            } else {
                ProcessBuilder(file.absolutePath).start()
            }
        }.isSuccess
        if (started) {
            scope.launch {
                delay(1_200)
                quitApp()
            }
        }
        return started
    }

    // ================================================================ traffic

    private fun countTraffic(up: Long, down: Long) {
        if (up <= 0 && down <= 0) return
        val now = System.currentTimeMillis()
        pendingTraffic.add(TrafficTally.dayOf(now), up, down)
        if (now - trafficFlushedAt >= 30_000L) flushTraffic(now)
    }

    /** Writes the counted bytes into the per-day totals; older than 400 days go. */
    private fun flushTraffic(now: Long = System.currentTimeMillis()) {
        trafficFlushedAt = now
        if (pendingTraffic.isEmpty()) return
        val drained = pendingTraffic.drain()
        val oldest = TrafficTally.dayOf(now) - 400
        store.update { d ->
            val byDay = d.trafficDays.associateBy { it.day }.toMutableMap()
            drained.forEach { (day, b) ->
                val old = byDay[day] ?: TrafficDayEntity(day)
                byDay[day] = old.copy(uplinkBytes = old.uplinkBytes + b.up, downlinkBytes = old.downlinkBytes + b.down)
            }
            d.copy(trafficDays = byDay.values.filter { it.day >= oldest }.sortedBy { it.day })
        }
    }

    // ================================================================ backup

    /** The backup file's contents, the same format as the Android app's (BackupCodec). */
    fun exportBackup(): String {
        val d = store.value
        val appProfiles = d.routingProfiles.filter { it.sourceSubscriptionId == null }
        val nameById = appProfiles.associate { it.id to it.name }
        return BackupCodec.encode(
            BackupCodec.Snapshot(
                subscriptions = d.subscriptions,
                subscriptionRouting = d.subscriptions.mapNotNull { sub -> nameById[sub.routingProfileId]?.let { sub.id to it } }.toMap(),
                servers = d.servers.filter { it.subscriptionId == null },
                routing = appProfiles.filter { BackupCodec.worthKeeping(it) },
                activeRouting = appProfiles.firstOrNull { it.isActive }?.name,
                settings = SettingsBackup.toBackup(d.settings),
                appVersion = AppPaths.appVersion,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    /** Writes a backup to [file]; false (and a message) when it could not. */
    fun saveBackup(file: java.io.File) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(exportBackup())
            }.onSuccess { message(Res.string(R.string.home_backup_saved)) }
                .onFailure { message(Res.string(R.string.home_backup_save_failed)) }
        }
    }

    /** Reads a backup file; the screen asks before [restoreBackup] puts it in. */
    fun readBackup(file: java.io.File): BackupCodec.Contents? = try {
        if (file.length() > BackupCodec.MAX_BYTES) throw BackupCodec.BackupException(R.string.settings_backup_error_too_big)
        BackupCodec.decode(file.readText()).also {
            if (it.isEmpty) message(Res.string(R.string.home_backup_nothing))
        }.takeIf { !it.isEmpty }
    } catch (e: BackupCodec.BackupException) {
        message(Res.string(e.reason))
        null
    } catch (e: Exception) {
        message(Res.string(R.string.home_backup_open_failed))
        null
    }

    fun restoreBackup(contents: BackupCodec.Contents, onDone: (RestoreReport) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val report = busy { restoreInternal(contents) }
            message(report.message())
            withContext(Dispatchers.immediateOrDefault()) { onDone(report) }
        }
    }

    /**
     * The Android app's BackupManager.restore: only adds. A subscription or server already
     * here is left as it is, so opening the same file twice changes nothing.
     */
    internal suspend fun restoreInternal(contents: BackupCodec.Contents): RestoreReport {
        ensureSeeded()
        val routingRestored = restoreRouting(contents.routing, contents.activeRouting)
        val (newSettings, settingsRestored) = SettingsBackup.fromBackup(settings, contents.settings)
        store.update { it.copy(settings = newSettings) }

        val known = store.value.servers.filter { it.subscriptionId == null }.map(BackupCodec::sameServerKey).toMutableSet()
        val toAdd = contents.servers.filter { known.add(BackupCodec.sameServerKey(it)) }
        if (toAdd.isNotEmpty()) {
            val ids = store.newIds(toAdd.size)
            store.update { d ->
                d.copy(servers = d.servers + toAdd.mapIndexed { i, srv ->
                    srv.copy(id = ids[i], subscriptionId = null, isSelected = false, sortOrder = d.servers.size + i)
                })
            }
        }

        val urls = store.value.subscriptions.map { it.url }.toMutableSet()
        val profileIds = store.value.routingProfiles.filter { it.sourceSubscriptionId == null }.associate { it.name to it.id }
        var added = 0
        var notLoaded = 0
        var alreadyThere = 0
        for (restored in contents.subscriptions) {
            if (!urls.add(restored.entity.url)) {
                alreadyThere++
                continue
            }
            val id = store.newIds(1).first()
            store.update { d ->
                d.copy(subscriptions = d.subscriptions + restored.entity.copy(
                    id = id,
                    routingProfileId = restored.routingName?.let { profileIds[it] } ?: 0L,
                    sortOrder = d.subscriptions.size
                ))
            }
            added++
            try {
                refreshSubscriptionInternal(id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notLoaded++
            }
        }
        if (store.value.servers.none { it.isSelected }) selectReplacement(null)
        I18n.use(settings.language)
        return RestoreReport(
            subscriptionsAdded = added,
            subscriptionsNotLoaded = notLoaded,
            subscriptionsAlreadyThere = alreadyThere,
            serversAdded = toAdd.size,
            routingRestored = routingRestored,
            settingsRestored = settingsRestored,
            skipped = contents.skipped
        )
    }

    /** Rule sets by name: one with the same name gets the saved rules, a new one is added. */
    private fun restoreRouting(profiles: List<RoutingProfileEntity>, activeName: String?): Int {
        var count = 0
        for (saved in profiles) {
            val existing = store.value.routingProfiles.firstOrNull { it.sourceSubscriptionId == null && it.name == saved.name }
            when {
                existing != null -> {
                    store.update { d ->
                        d.copy(routingProfiles = d.routingProfiles.map {
                            if (it.id != existing.id) it else it.copy(
                                description = saved.description.ifBlank { existing.description },
                                useProviderRules = saved.useProviderRules,
                                rulesJson = saved.rulesJson,
                                domainStrategy = saved.domainStrategy,
                                remoteDns = saved.remoteDns,
                                domesticDns = saved.domesticDns,
                                dnsHostsJson = saved.dnsHostsJson,
                                updatedAt = System.currentTimeMillis()
                            )
                        })
                    }
                    count++
                }

                !saved.isBuiltIn -> {
                    val id = store.newIds(1).first()
                    store.update { d ->
                        d.copy(routingProfiles = d.routingProfiles + saved.copy(id = id, isActive = false, updatedAt = System.currentTimeMillis()))
                    }
                    count++
                }
            }
        }
        activeName?.let { name ->
            store.value.routingProfiles.firstOrNull { it.sourceSubscriptionId == null && it.name == name }?.let { target ->
                store.update { d -> d.copy(routingProfiles = d.routingProfiles.map { it.copy(isActive = it.id == target.id) }) }
            }
        }
        if (count > 0) touchRouting()
        return count
    }

    // ================================================================ helpers

    companion object {
        /** Clicks on servers closer together than this move a live tunnel once, to the last. */
        const val SWITCH_SETTLE_MS = 350L

        /** After a core swap the new node is looked at after a moment, then briefly. */
        private const val SWITCH_FIRST_PROBE_MS = 300L
        private const val SWITCH_ATTEMPTS = 3
        private const val SWITCH_RETRY_PAUSE_MS = 700L

        /** One retry of a core that refused to start in a swap (its port still closing). */
        private const val SWAP_RETRY_PAUSE_MS = 400L

        /** The full comparison after a quick pick waits this long for the tunnel to settle. */
        private const val REFINE_DELAY_MS = 4_000L

        val SUBSCRIPTION_COLORS = listOf("#C4784A", "#4A8FC4", "#5FB07A", "#B05FA8", "#C4A24A", "#4AC4B8")

        /** For tests and the self-test: the whole state at once. */
        fun seed(controller: VoltaController, data: StoreData) {
            controller.store.update { data }
        }
    }

    /** Test hook: pretend a tunnel is up (the self-test draws the connected screens). */
    internal fun debugRuntime(change: (RuntimeState) -> RuntimeState) = runtime.update(change)

    /** For the self-test's pictures: a "check all" as if under way. */
    internal fun debugSweep(board: SweepBoard) {
        _sweep.value = board
    }

    /** Blocks until [block] finishes; for the self-test only. */
    fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
}

/** Dispatchers.Main when a UI runs (Swing), otherwise the default one (tests, self-test). */
internal fun Dispatchers.immediateOrDefault(): kotlinx.coroutines.CoroutineDispatcher =
    runCatching { Main.immediate.also { it.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) } }
        .getOrElse { Default }
