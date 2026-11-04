package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.balancer.AutoBalancer
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

    val ui: StateFlow<VoltaUiState> = combine(store.data, runtime) { d, r ->
        VoltaUiState(
            subscriptions = d.subscriptions.sortedBy { it.sortOrder },
            servers = d.servers,
            routingProfiles = d.routingProfiles,
            settings = d.settings,
            networkRegime = d.networkRegime,
            runtime = r
        )
    }.stateIn(scope, SharingStarted.Eagerly, VoltaUiState(settings = store.value.settings))

    private val settings: AppSettings get() = store.value.settings
    private val connectLock = Mutex()
    private var connectJob: Job? = null
    private var trafficJob: Job? = null
    private var guardJob: Job? = null

    init {
        I18n.use(settings.language)
        ensureSeeded()
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
            val ok = if (v) tunnel.applySystemProxy() else tunnel.systemProxy.restore()
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
        connectJob = scope.launch { runConnect() }
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
    private fun raise(server: ServerEntity, strict: Boolean): String? {
        val s = I18n.s
        val adapted = WindowsCompat.adapt(server)
        ConfigBuilder.dialProblem(adapted)?.let { return it }
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
                        exitCheckPort = p.exitCheck,
                        metricsPort = p.metrics
                    )
                },
                onExit = { code -> onCoreDied(code) }
            )
        } catch (e: Tunnel.StartException) {
            return s.errCoreStart(e.message.orEmpty())
        } catch (e: Exception) {
            return e.userMessage(s.errUnsupported)
        }
        if (!tunnel.check()) {
            if (strict) {
                tunnel.stop(restoreProxy = false)
                return s.errServerSilent
            }
            message(s.msgServerSilent)
        }
        if (settings.systemProxy) {
            if (!tunnel.applySystemProxy()) message(s.msgProxyFailed)
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
                error = null
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
                    name = name.trim().ifBlank { s.subscription },
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
            name = routing.name.ifBlank { "$displayName · ${I18n.s.routingTitle}" },
            description = displayName,
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
