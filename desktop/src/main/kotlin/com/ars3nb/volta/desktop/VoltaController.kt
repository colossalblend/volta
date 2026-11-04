package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.backup.BackupCodec
import com.ars3nb.volta.core.backup.RestoreReport
import com.ars3nb.volta.core.balancer.AutoBalancer
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

    private val pendingTraffic = PendingTraffic()
    @Volatile
    private var trafficFlushedAt = 0L
    private val connectLock = Mutex()
    private var connectJob: Job? = null
    private var trafficJob: Job? = null
    private var guardJob: Job? = null

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
        if (runtime.value.phase != ConnectPhase.IDLE || connectJob?.isActive == true &&
            runtime.value.vpnState == VpnConnectionState.CONNECTING
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
        scope.launch(Dispatchers.IO) {
            runtime.update { it.copy(vpnState = VpnConnectionState.DISCONNECTING, phase = ConnectPhase.IDLE) }
            stopTunnel()
            runtime.update {
                it.copy(
                    vpnState = VpnConnectionState.DISCONNECTED,
                    connectedServerId = null,
                    connectedSince = 0,
                    traffic = TrafficStats(),
                    ports = null,
                    proxyActive = false,
                    error = null
                )
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
        if (ui.value.isConnected && mode != ConnectMode.MANUAL && connectJob?.isActive != true) {
            connectJob = scope.launch { runConnect() }
        }
    }

    /** Choosing a server by hand switches the mode to "Свой сервер" and moves the tunnel there. */
    fun selectServer(id: Long) {
        updateSettings { it.copy(connectMode = ConnectMode.MANUAL) }
        store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == id) }) }
        if ((ui.value.isConnected || runtime.value.vpnState == VpnConnectionState.CONNECTING) &&
            runtime.value.connectedServerId != id
        ) {
            connectJob?.cancel()
            connectJob = scope.launch { runConnect(fixed = id) }
        }
    }

    /** Connects to [id] at once, whatever the mode. */
    fun connectTo(id: Long) {
        updateSettings { it.copy(connectMode = ConnectMode.MANUAL) }
        store.update { d -> d.copy(servers = d.servers.map { it.copy(isSelected = it.id == id) }) }
        connectJob?.cancel()
        connectJob = scope.launch { runConnect(fixed = id) }
    }

    private fun engine() = PingEngine(tester)

    /**
     * The whole connect: pick the server (auto modes measure first), raise the tunnel,
     * check it carries traffic, point Windows at it. In an auto mode the next best
     * servers are tried when the first one fails.
     */
    private suspend fun runConnect(fixed: Long? = null) = connectLock.withLock {
        val s = I18n.s
        runtime.update {
            it.copy(vpnState = VpnConnectionState.CONNECTING, phase = ConnectPhase.PREPARING, error = null)
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
                else -> pickForMode(mode)
            }
            if (candidates.isEmpty()) error(s.errNoServerToConnect)
            runtime.update { it.copy(phase = ConnectPhase.IDLE) }

            var lastError: String? = null
            for ((index, server) in candidates.withIndex()) {
                val outcome = withContext(Dispatchers.IO) { raise(server, strict = candidates.size > 1) }
                if (outcome == null) {
                    onConnected(server, auto = fixed == null && mode != ConnectMode.MANUAL)
                    if (index > 0) message(s.msgPicked(server.remark))
                    return@withLock
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
                    proxyActive = false
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
                    proxyActive = false
                )
            }
        }
    }

    /** Auto modes: measure the group, then the winner first and two runners-up behind it. */
    private suspend fun pickForMode(mode: ConnectMode): List<ServerEntity> {
        val s = I18n.s
        val scope = mode.scope ?: BalancerScope.ALL
        val all = store.value.servers.map(WindowsCompat::adapt)
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
        val decision = balancer.choose(
            servers = all,
            scope = scope,
            current = current,
            pingType = settings.pingType,
            onPhase = { phase -> runtime.update { it.copy(phase = ConnectPhase.of(phase)) } },
            remembered = remembered,
            quick = false,
            knownNetwork = network
        )
        applyDecision(decision)
        if (decision.regime == NetworkRegime.BLOCKED && decision.ranked.isEmpty()) error(s.errNoInternet)
        val selected = decision.selected ?: error(
            if (decision.ranked.isEmpty() && decision.probes.isEmpty()) s.errGroupEmpty else s.errNothingAnswered
        )
        val alive = decision.ranked.filter { it.ok && it.server.id != selected.id }.map { it.server }
        return (listOf(selected) + alive.take(2)).distinctBy { it.id }
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
                buildConfig = { p ->
                    ConfigBuilder.build(
                        server = pinned,
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
                },
                onExit = { code -> onCoreDied(code) }
            )
        } catch (e: Tunnel.StartException) {
            stopHelpers()
            return s.errCoreStart(e.message.orEmpty())
        } catch (e: Exception) {
            stopHelpers()
            return e.userMessage(s.errUnsupported)
        }
        if (!tunnel.check()) {
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
                routingDirty = false
            )
        }
        startTrafficPolling()
        startGuard()
        scope.launch(Dispatchers.IO) { refreshIp() }
    }

    private fun onCoreDied(code: Int) {
        if (runtime.value.vpnState != VpnConnectionState.CONNECTED) return
        trafficJob?.cancel()
        guardJob?.cancel()
        stopHelpers()
        flushTraffic()
        runtime.update {
            it.copy(
                vpnState = VpnConnectionState.ERROR,
                error = I18n.s.errCoreStopped + if (code != 0) " ($code)" else "",
                ports = null,
                proxyActive = false
            )
        }
        if (settings.autoFailover) {
            scope.launch {
                delay(1_500)
                if (runtime.value.vpnState == VpnConnectionState.ERROR) connect()
            }
        }
    }

    private fun startTrafficPolling() {
        trafficJob?.cancel()
        trafficJob = scope.launch(Dispatchers.IO) {
            var last: TrafficMeter.Totals? = null
            var lastAt = System.nanoTime()
            while (isActive) {
                delay(2_000)
                val ports = tunnel.ports ?: continue
                val now = TrafficMeter.read(ports.metrics) ?: continue
                val t = System.nanoTime()
                val seconds = ((t - lastAt) / 1e9).coerceAtLeast(0.5)
                val prev = last
                val up = if (prev == null) 0 else ((now.uplink - prev.uplink).coerceAtLeast(0) / seconds).toLong()
                val down = if (prev == null) 0 else ((now.downlink - prev.downlink).coerceAtLeast(0) / seconds).toLong()
                // The core's counters start at nothing with it: the first reading counts whole.
                countTraffic(
                    (now.uplink - (prev?.uplink ?: 0)).coerceAtLeast(0),
                    (now.downlink - (prev?.downlink ?: 0)).coerceAtLeast(0)
                )
                last = now
                lastAt = t
                runtime.update {
                    it.copy(
                        traffic = TrafficStats(now.uplink, now.downlink, up, down),
                        speed = it.speed.push(down, up)
                    )
                }
            }
        }
    }

    /** Every 30 s while connected: does the tunnel still carry traffic? Two misses move it. */
    private fun startGuard() {
        guardJob?.cancel()
        guardJob = scope.launch(Dispatchers.IO) {
            var misses = 0
            while (isActive) {
                delay(30_000)
                if (runtime.value.vpnState != VpnConnectionState.CONNECTED) continue
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
                    connectJob = scope.launch {
                        runConnect()
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

    fun pingAll(pool: ServerPool = ServerPool.ALL) {
        scope.launch(Dispatchers.IO) {
            busy {
                val list = store.value.servers.filter { pool.matches(it.role) }.map(WindowsCompat::adapt)
                if (list.isEmpty()) return@busy
                val results = engine().pingServers(
                    servers = list,
                    type = settings.pingType,
                    realDelayTimeoutMs = PingEngine.SWEEP_TIMEOUT_MS
                ) { id, r -> persistHealth(id, r) }
                message(I18n.s.msgPingDone(results.count { it.second.success }, list.size))
            }
        }
    }

    fun pingServer(id: Long) {
        scope.launch(Dispatchers.IO) {
            val server = store.value.servers.firstOrNull { it.id == id }?.let(WindowsCompat::adapt) ?: return@launch
            val r = engine().pingServer(server, settings.pingType)
            persistHealth(id, r)
            message(if (r.success) "${server.remark}: ${I18n.s.ms(r.latencyMs)}" else "${server.remark}: ${r.message}")
        }
    }

    /** One of the three "best server in the group" pads. */
    fun runBalancer(scope: BalancerScope) {
        this@VoltaController.scope.launch {
            if (runtime.value.runningScope != null) return@launch
            runtime.update { it.copy(runningScope = scope) }
            try {
                val all = store.value.servers.map(WindowsCompat::adapt)
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
                    if (ui.value.isConnected && runtime.value.connectedServerId != pick.id) {
                        connectJob?.cancel()
                        connectJob = this@VoltaController.scope.launch { runConnect(fixed = pick.id) }
                    }
                }
            } finally {
                runtime.update { it.copy(runningScope = null) }
            }
        }
    }

    private fun persistHealth(id: Long, result: PingResult) {
        val ok = result.success && result.latencyMs >= 0
        store.update { d ->
            d.copy(servers = d.servers.map { s ->
                if (s.id != id) s else s.copy(
                    latencyMs = if (ok) result.latencyMs else -1,
                    ewmaMs = if (ok) AutoBalancer.blendEwma(s.ewmaMs, result.latencyMs) else s.ewmaMs,
                    failStreak = if (ok) 0 else (s.failStreak + 1).coerceAtMost(9),
                    lastPingAt = System.currentTimeMillis()
                )
            })
        }
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
        val SUBSCRIPTION_COLORS = listOf("#C4784A", "#4A8FC4", "#5FB07A", "#B05FA8", "#C4A24A", "#4AC4B8")

        /** For tests and the self-test: the whole state at once. */
        fun seed(controller: VoltaController, data: StoreData) {
            controller.store.update { data }
        }
    }

    /** Test hook: pretend a tunnel is up (the self-test draws the connected screens). */
    internal fun debugRuntime(change: (RuntimeState) -> RuntimeState) = runtime.update(change)

    /** Blocks until [block] finishes; for the self-test only. */
    fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
}

/** Dispatchers.Main when a UI runs (Swing), otherwise the default one (tests, self-test). */
internal fun Dispatchers.immediateOrDefault(): kotlinx.coroutines.CoroutineDispatcher =
    runCatching { Main.immediate.also { it.isDispatchNeeded(kotlin.coroutines.EmptyCoroutineContext) } }
        .getOrElse { Default }
