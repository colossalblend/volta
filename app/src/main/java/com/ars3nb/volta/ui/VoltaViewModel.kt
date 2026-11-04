package com.ars3nb.volta.ui

import android.app.Application
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ars3nb.volta.BuildConfig
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.link.VoltaLinks
import com.ars3nb.volta.core.link.ImportDetector
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.parser.AmneziaConfigs
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.IpInfo
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteProbeResult
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.prefs.DesignTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ConnectionSlice(
    val vpnState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val selectedServer: ServerEntity? = null,
    val traffic: TrafficStats = TrafficStats(),
    val connectedSince: Long = 0,
    val error: String? = null
)

data class LocalSlice(
    val busy: Boolean = false,
    val message: String? = null,
    val probes: List<SiteProbeResult> = emptyList(),
    val regime: NetworkRegime = NetworkRegime.UNKNOWN,
    val ip: IpInfo? = null
)

data class VoltaUiState(
    val vpnState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val selectedServer: ServerEntity? = null,
    val traffic: TrafficStats = TrafficStats(),
    val connectedSince: Long = 0,
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val servers: List<ServerEntity> = emptyList(),
    val routingProfiles: List<RoutingProfileEntity> = emptyList(),
    val designTheme: DesignTheme = DesignTheme.VOLTA,
    val perAppMode: PerAppMode = PerAppMode.OFF,
    val networkRegime: NetworkRegime = NetworkRegime.UNKNOWN,
    val probes: List<SiteProbeResult> = emptyList(),
    val ipInfo: IpInfo? = null,
    val hwidShort: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null
) {
    val isConnected: Boolean get() = vpnState == VpnConnectionState.CONNECTED
    val isBusyConnecting: Boolean
        get() = vpnState == VpnConnectionState.CONNECTING ||
            vpnState == VpnConnectionState.DISCONNECTING
}

class VoltaViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = (app as VoltaApp).repository
    private val updater = (app as VoltaApp).updater

    private val _busy = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    private val _probes = MutableStateFlow<List<SiteProbeResult>>(emptyList())
    private val _regime = MutableStateFlow(NetworkRegime.UNKNOWN)
    private val _ip = MutableStateFlow<IpInfo?>(null)
    private val _needsVpnPermission = MutableStateFlow(false)
    private val _startupDone = MutableStateFlow(false)
    private val _serverPool = MutableStateFlow(ServerPool.ALL)
    private val _query = MutableStateFlow("")
    private val _runningScope = MutableStateFlow<BalancerScope?>(null)
    private val _connectPhase = MutableStateFlow(ConnectPhase.IDLE)
    private var retryJob: Job? = null
    private var connectJob: Job? = null

    val needsVpnPermission = _needsVpnPermission.asStateFlow()
    val serverPool = _serverPool.asStateFlow()
    val query = _query.asStateFlow()

    /** Which balancer button is currently working, for the spinner in the UI. */
    val runningScope = _runningScope.asStateFlow()

    /**
     * What the big button is doing before the service takes over. Also lights up when
     * a tile or Always-on started the pick, not only the button.
     */
    val connectPhase = combine(_connectPhase, repo.autoPicking, repo.pickPhase) { own, picking, phase ->
        when {
            own != ConnectPhase.IDLE -> own
            picking -> phase?.let(ConnectPhase::of) ?: ConnectPhase.PREPARING
            else -> ConnectPhase.IDLE
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConnectPhase.IDLE)

    val connectMode = repo.connectMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectMode.AUTO_HOME_LTE)
    val refreshOnLaunch = repo.refreshOnLaunch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val followNetwork = repo.followNetwork
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val autoRecheck = repo.autoRecheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val hideBalancers = repo.hideBalancers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Null until the stored value is read, so the app never flashes the wrong screen. */
    val simpleMode = repo.simpleMode.map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Live speed for the graph, sampled every 2 s while connected. Sampled on a timer
     * rather than per traffic update: an idle tunnel emits nothing, yet the graph must
     * still scroll to show the quiet.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val speedHistory: StateFlow<SpeedHistory> = repo.vpnState
        .map { it == VpnConnectionState.CONNECTED }
        .distinctUntilChanged()
        .flatMapLatest { connected ->
            if (!connected) {
                flowOf(SpeedHistory())
            } else {
                flow {
                    var history = SpeedHistory()
                    while (true) {
                        val t = repo.traffic.value
                        history = history.push(t.downlinkSpeed, t.uplinkSpeed)
                        emit(history)
                        delay(2_000)
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpeedHistory())

    /** Why the auto mode picked what it picked: the best few with their numbers. */
    val pickSummary: StateFlow<PickSummary?> = repo.lastDecision
        .map { decision -> decision?.let(PickSummary::of) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val connection = combine(
        repo.vpnState, repo.selectedServer, repo.traffic, repo.connectedSince, repo.vpnError
    ) { state, server, traffic, since, error ->
        ConnectionSlice(state, server, traffic, since, error)
    }

    private val local = combine(_busy, _message, _probes, _regime, _ip) { busy, msg, probes, regime, ip ->
        LocalSlice(busy, msg, probes, regime, ip)
    }

    private val library = combine(
        repo.subscriptions, repo.servers, repo.routingProfiles
    ) { subs, servers, profiles -> Triple(subs, servers, profiles) }

    private val settings = combine(repo.designTheme, repo.perAppMode) { theme, perApp ->
        theme to perApp
    }

    val uiState: StateFlow<VoltaUiState> = combine(
        connection, library, settings, local
    ) { conn, lib, prefs, loc ->
        VoltaUiState(
            vpnState = conn.vpnState,
            selectedServer = conn.selectedServer,
            traffic = conn.traffic,
            connectedSince = conn.connectedSince,
            subscriptions = lib.first,
            servers = lib.second,
            routingProfiles = lib.third,
            designTheme = prefs.first,
            perAppMode = prefs.second,
            networkRegime = loc.regime,
            probes = loc.probes,
            ipInfo = loc.ip,
            hwidShort = repo.shortHwid(),
            busy = loc.busy,
            message = loc.message,
            error = conn.error
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        VoltaUiState(hwidShort = repo.shortHwid())
    )

    val routingProfiles = repo.routingProfiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val designTheme = repo.designTheme
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DesignTheme.VOLTA)
    val perAppMode = repo.perAppMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PerAppMode.OFF)
    val perAppPackages = repo.perAppPackages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())
    val autoFailover = repo.autoFailover
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val pingOnStart = repo.pingOnStart
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val batterySaver = repo.batterySaver
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val localDns = repo.localDns
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val adBlock = repo.adBlock
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AdBlockMode.OFF)
    val blockTorrents = repo.blockTorrents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Routing changed while the tunnel is up; it takes effect on the next connect. */
    private val _routingDirty = MutableStateFlow(false)
    val routingDirty = _routingDirty.asStateFlow()
    val sendHwid = repo.sendHwid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val autoConnect = repo.autoConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val defaultPingType = repo.defaultPingType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PingType.REAL_DELAY)

    /** The OpenFlux helper, for its captcha banner. */
    val openFluxStatus = OpenFluxRunner.status

    /** A newer build on GitHub: found, downloading, ready to install. */
    val updateState = updater.state
    val updateAutoCheck = updater.autoCheck
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    /** This build can update itself at all: the GitHub one, not the Google Play one. */
    val updaterBuilt: Boolean get() = BuildConfig.OTA

    fun checkForUpdate() = viewModelScope.launch { updater.check() }
    fun installUpdate() = updater.downloadAndInstall()
    fun setUpdateAutoCheck(v: Boolean) = viewModelScope.launch { updater.setAutoCheck(v) }

    fun coreAvailable(): Boolean = repo.isCoreAvailable()
    fun openFluxAvailable(): Boolean = OpenFluxRunner.available
    fun coreVersion(): String = repo.coreVersion()

    init {
        viewModelScope.launch {
            repo.ensureSeeded()
            // Last verdict first, so the home screen is not blank while the probe runs,
            // then every later one, including those of the background check.
            launch {
                repo.lastRegime.collect { name ->
                    _regime.value = runCatching { NetworkRegime.valueOf(name) }
                        .getOrDefault(NetworkRegime.UNKNOWN)
                }
            }
            launch { runCatching { updater.checkOnLaunch() } }
            launch { followExitAddress() }
            if (repo.pingOnStart.first()) runStartupProbes()
            // Happ-style: subscriptions and their routing are pulled on every launch.
            if (repo.refreshOnLaunch.first()) {
                launch { runCatching { repo.refreshAllSubscriptions() } }
            }
            // The setting says "connect on launch", so honour it here too — the boot
            // receiver alone only covered a reboot.
            if (repo.autoConnect.first() &&
                uiState.value.vpnState == VpnConnectionState.DISCONNECTED &&
                repo.servers.first().isNotEmpty()
            ) {
                connect()
            }
        }
    }

    /**
     * The address on top follows the tunnel: the exit while connected, looked up again
     * after every switch to another server, and the real one once it is off.
     */
    private suspend fun followExitAddress() {
        combine(repo.vpnState, repo.liveServerId) { state, liveId ->
            state to (if (state == VpnConnectionState.CONNECTED) liveId else null)
        }
            .distinctUntilChanged()
            .collectLatest { (state, _) ->
                when (state) {
                    VpnConnectionState.CONNECTED -> {
                        _ip.value = null
                        // The core needs a moment before its local proxy answers, and a
                        // node that is still warming up may drop the first lookups.
                        for (wait in longArrayOf(500, 1_500, 3_000, 6_000, 12_000, 30_000)) {
                            delay(wait)
                            val info = runCatching { repo.fetchIp() }.getOrNull()
                            if (info != null && info.ip.isNotBlank()) {
                                _ip.value = info
                                break
                            }
                        }
                    }
                    VpnConnectionState.DISCONNECTED -> {
                        if (_ip.value != null) _ip.value = null
                        delay(500)
                        runCatching { repo.fetchIp() }.getOrNull()
                            ?.takeIf { it.ip.isNotBlank() }
                            ?.let { _ip.value = it }
                    }
                    else -> {
                        _ip.value = null
                    }
                }
            }
    }

    fun runStartupProbes() {
        if (_startupDone.value) return
        runStartupProbesForce()
    }

    fun runStartupProbesForce() {
        viewModelScope.launch {
            try {
                // Probing while the tunnel is up would describe the exit, not this network.
                // Without LTE servers there is no Home-or-LTE choice to make: no probe.
                if (!repo.isConnected() &&
                    com.ars3nb.volta.core.balancer.GuardPolicy.hasLte(repo.servers.first())
                ) {
                    val (regime, probes) = repo.probeNetwork()
                    _regime.value = regime
                    _probes.value = probes
                }
                _startupDone.value = true
            } catch (_: Exception) {
            }
            runCatching { repo.fetchIp() }.getOrNull()
                ?.takeIf { it.ip.isNotBlank() }
                ?.let { _ip.value = it }
        }
    }

    // ------------------------------------------------------------- connection

    fun toggleConnect() {
        // A tap while the server is still being chosen stops the attempt.
        if (connectPhase.value != ConnectPhase.IDLE) {
            cancelConnect()
            return
        }
        when (uiState.value.vpnState) {
            VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING -> disconnect()
            else -> connect()
        }
    }

    /**
     * Stops a connect that is still probing the network or measuring servers. A tunnel
     * that was already up stays on its server; one that was off stays off.
     */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        repo.cancelPick()
        _connectPhase.value = ConnectPhase.IDLE
        _busy.value = false
        _message.value = "Подключение отменено"
    }

    fun connect() {
        if (VpnService.prepare(getApplication()) != null) {
            _needsVpnPermission.value = true
            return
        }
        doConnect()
    }

    /** A launcher shortcut: switch to [mode] and bring the tunnel up there. */
    fun connectIn(mode: ConnectMode) {
        viewModelScope.launch {
            val same = repo.connectMode.first() == mode
            repo.setConnectMode(mode)
            // Already up in that group: nothing to do. Up elsewhere: move it here.
            if (same && uiState.value.isConnected) return@launch
            if (connectPhase.value == ConnectPhase.IDLE) connect()
        }
    }

    fun onVpnPermissionResult(granted: Boolean) {
        _needsVpnPermission.value = false
        if (granted) doConnect() else _message.value = "Нужно разрешение на VPN"
    }

    private fun doConnect() {
        _routingDirty.value = false
        connectJob = viewModelScope.launch {
            _busy.value = true
            _connectPhase.value = ConnectPhase.PREPARING
            try {
                val decision = repo.connectWithMode { phase ->
                    _connectPhase.value = ConnectPhase.of(phase)
                }
                if (decision != null) {
                    if (decision.probes.isNotEmpty()) {
                        _regime.value = decision.regime
                        _probes.value = decision.probes
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось подключиться")
            } finally {
                _busy.value = false
                _connectPhase.value = ConnectPhase.IDLE
            }
        }
    }

    /** Picking a mode while connected moves the tunnel to that group right away. */
    fun setConnectMode(mode: ConnectMode) {
        viewModelScope.launch {
            repo.setConnectMode(mode)
            if (uiState.value.isConnected && mode != ConnectMode.MANUAL) doConnect()
        }
    }

    fun setRefreshOnLaunch(v: Boolean) = viewModelScope.launch { repo.setRefreshOnLaunch(v) }
    fun setFollowNetwork(v: Boolean) = viewModelScope.launch { repo.setFollowNetwork(v) }
    fun setAutoRecheck(v: Boolean) = viewModelScope.launch { repo.setAutoRecheck(v) }
    fun setHideBalancers(v: Boolean) = viewModelScope.launch { repo.setHideBalancers(v) }

    fun setSimpleMode(v: Boolean) = viewModelScope.launch {
        val wasElsewhere = uiState.value.isConnected &&
            repo.connectMode.first() != ConnectMode.AUTO_HOME_LTE
        repo.setSimpleMode(v)
        // Switching on while connected in another mode moves the tunnel to Home+LTE.
        if (v && wasElsewhere && connectPhase.value == ConnectPhase.IDLE) doConnect()
    }


    fun disconnect() = repo.disconnect()

    // ---------------------------------------------------------------- servers

    /** Choosing a server by hand switches the button to "Свой сервер". */
    fun selectServer(id: Long) = viewModelScope.launch {
        repo.setConnectMode(ConnectMode.MANUAL)
        repo.selectServer(id)
    }
    fun toggleFavorite(id: Long) = viewModelScope.launch { repo.toggleFavorite(id) }
    fun setServerRole(id: Long, role: ServerRole) =
        viewModelScope.launch { repo.updateServerRole(id, role) }

    fun deleteServer(id: Long) = viewModelScope.launch { repo.deleteServer(id) }

    fun setServerPool(pool: ServerPool) {
        _serverPool.value = pool
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    fun pingServer(id: Long) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = repo.pingServer(id)
                _message.value = if (result.success && result.estimate) {
                    // OpenFlux judged from outside: its carrier answered, the exit is
                    // measured for real once traffic goes through it.
                    "Отклик: около ${result.latencyMs} мс" +
                        result.message.takeIf { it.isNotBlank() }?.let { " · канал: $it" }.orEmpty()
                } else if (result.success) {
                    "Отклик: ${result.latencyMs} мс"
                } else {
                    result.message.ifBlank { "Сервер не отвечает" }
                }
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось проверить сервер")
            } finally {
                _busy.value = false
            }
        }
    }

    fun pingAll(type: PingType? = null, pool: ServerPool = _serverPool.value) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val ok = repo.pingAll(type, pool)
                _message.value = "Ответили $ok серверов"
            } catch (e: Exception) {
                _message.value = e.userMessage("Проверка не удалась")
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * One of the three user-facing autobalancers. When the network itself is down the
     * run measures nothing and schedules a single retry, so the user is not left with a
     * list of servers all falsely marked dead.
     */
    fun runBalancer(scope: BalancerScope) {
        retryJob?.cancel()
        retryJob = null
        viewModelScope.launch { runBalancerOnce(scope, allowRetry = true) }
    }

    private suspend fun runBalancerOnce(scope: BalancerScope, allowRetry: Boolean) {
        _busy.value = true
        _runningScope.value = scope
        try {
            val decision = repo.runAutobalancer(scope)
            if (decision.probes.isNotEmpty()) {
                _regime.value = decision.regime
                _probes.value = decision.probes
            }
            _message.value = decision.reason
            if (decision.retryLater && allowRetry) scheduleRetry(scope)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _message.value = e.userMessage("Подбор не удался")
        } finally {
            _busy.value = false
            _runningScope.value = null
        }
    }

    private fun scheduleRetry(scope: BalancerScope) {
        retryJob = viewModelScope.launch {
            delay(RETRY_DELAY_MS)
            runBalancerOnce(scope, allowRetry = false)
        }
    }

    fun selectFastest() = runBalancer(BalancerScope.ALL)

    // ---------------------------------------------------------- subscriptions

    fun addSubscription(name: String, url: String, onComplete: (Boolean) -> Unit = {}) {
        // An import or encrypted link pasted into the address field.
        if (url.trim().startsWith("volta://", ignoreCase = true)) {
            handleDeepLink(url, onComplete, nameOverride = name)
            return
        }
        viewModelScope.launch {
            _message.value = null
            _busy.value = true
            try {
                val id = repo.addSubscription(name, url)
                val count = repo.servers.first().count { it.subscriptionId == id }
                _message.value = if (count > 0) "Добавлено серверов: $count"
                else "Подписка добавлена"
                onComplete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось добавить подписку")
                onComplete(false)
            } finally {
                _busy.value = false
            }
        }
    }

    fun refreshSubscription(id: Long) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repo.refreshSubscription(id)
                val count = repo.servers.first().count { it.subscriptionId == id }
                _message.value = if (count > 0) "Обновлено: $count серверов" else "Подписка обновлена"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось обновить")
            } finally {
                _busy.value = false
            }
        }
    }

    fun updateSubscription(id: Long, name: String, url: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _busy.value = true
            try {
                repo.updateSubscription(id, name, url)
                _message.value = "Подписка обновлена"
                onComplete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось изменить подписку")
                onComplete(false)
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteSubscription(id: Long) = viewModelScope.launch { repo.deleteSubscription(id) }

    fun refreshAllSubscriptions() {
        viewModelScope.launch {
            _busy.value = true
            try {
                _message.value = "Обновлено подписок: ${repo.refreshAllSubscriptions()}"
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось обновить подписки")
            } finally {
                _busy.value = false
            }
        }
    }

    // ------------------------------------------------------------- deep links

    /**
     * Every `volta://` link that reaches the app (see [VoltaLinks]): adding a plain,
     * hidden or encrypted subscription, switching the tunnel on or off, or content for
     * the ordinary importer.
     */
    fun handleDeepLink(raw: String, onComplete: (Boolean) -> Unit = {}, nameOverride: String = "") {
        fun named(a: VoltaLinks.AddSubscription) =
            if (nameOverride.isBlank()) a else a.copy(name = nameOverride.trim())
        when (val action = VoltaLinks.parse(raw)) {
            is VoltaLinks.AddSubscription -> addFromLink(named(action), onComplete)
            is VoltaLinks.AddEncrypted -> viewModelScope.launch {
                // ECDH + AES: a millisecond, but not on the main thread.
                val opened = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    VoltaLinks.openEncrypted(action)
                }
                when (opened) {
                    is VoltaLinks.AddSubscription -> addFromLink(named(opened), onComplete)
                    is VoltaLinks.Invalid -> {
                        _message.value = opened.message
                        onComplete(false)
                    }
                    else -> onComplete(false)
                }
            }
            // Strictly shorter than the link it came from, so this always ends.
            is VoltaLinks.ImportContent -> importLink(action.value, onComplete)
            is VoltaLinks.Connect -> {
                val mode = action.mode
                if (mode != null) {
                    connectIn(mode)
                } else if (!uiState.value.isConnected && !uiState.value.isBusyConnecting &&
                    connectPhase.value == ConnectPhase.IDLE
                ) {
                    connect()
                }
                onComplete(true)
            }
            VoltaLinks.Disconnect -> {
                if (connectPhase.value != ConnectPhase.IDLE) cancelConnect()
                if (uiState.value.isConnected || uiState.value.isBusyConnecting) disconnect()
                onComplete(true)
            }
            VoltaLinks.Toggle -> {
                toggleConnect()
                onComplete(true)
            }
            is VoltaLinks.Invalid -> {
                _message.value = action.message
                onComplete(false)
            }
        }
    }

    private fun addFromLink(link: VoltaLinks.AddSubscription, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val id = repo.addSubscription(
                    name = link.name,
                    url = link.url,
                    hidden = link.hidden,
                    serverTypes = link.serverTypes,
                    reveal = link.visible == true
                )
                val count = repo.servers.first().count { it.subscriptionId == id }
                val hidden = repo.subscriptions.first().firstOrNull { it.id == id }?.urlHidden == true
                _message.value = "Подписка добавлена: $count серверов" + if (hidden) " · ссылка скрыта" else ""
                onComplete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The repository already took a hidden address out of the error text.
                _message.value = e.userMessage("Не удалось добавить подписку")
                onComplete(false)
            } finally {
                _busy.value = false
            }
        }
    }

    fun importLink(link: String, onComplete: (Boolean) -> Unit = {}) {
        // A volta:// link pasted or shared into the app works like one that was opened.
        if (link.trim().startsWith("volta://", ignoreCase = true)) {
            handleDeepLink(link, onComplete)
            return
        }
        importContent(link, onComplete)
    }

    private fun importContent(link: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val value = link.trim().let { OpenFluxLink.unwrap(it) ?: it }
                when {
                    value.startsWith("http://", true) || value.startsWith("https://", true) -> {
                        val id = repo.addSubscription("", value)
                        val count = repo.servers.first().count { it.subscriptionId == id }
                        _message.value = "Подписка добавлена: $count серверов"
                    }

                    // A WireGuard / AmneziaWG .conf, or an AmneziaVPN key with several protocols.
                    AmneziaConfigs.looksLikeConf(value) || value.startsWith("vpn://", true) -> {
                        val n = repo.importJson(value)
                        require(n > 0) { "В файле нет данных сервера" }
                        _message.value = if (n == 1) "Сервер добавлен" else "Импортировано серверов: $n"
                    }

                    value.trimStart().startsWith("{") || value.trimStart().startsWith("[") -> {
                        val n = repo.importJson(value)
                        require(n > 0) { "Здесь нет серверов, которые Volta умеет открывать" }
                        _message.value = "Импортировано серверов: $n"
                    }

                    // Several links pasted at once (a few OpenFlux exits, a server list, a
                    // base64 body): every one of them, as the screen promised.
                    ImportDetector.detect(value) is ImportDetector.Kind.Servers -> {
                        val n = repo.importJson(value)
                        require(n > 0) { "Здесь нет серверов, которые Volta умеет открывать" }
                        _message.value = "Импортировано серверов: $n"
                    }

                    else -> {
                        val s = repo.importShareLink(value)
                            ?: error("Формат ссылки не поддерживается")
                        _message.value = "Добавлен: ${s.remark}"
                    }
                }
                onComplete(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось импортировать")
                onComplete(false)
            } finally {
                _busy.value = false
            }
        }
    }

    // ---------------------------------------------------------------- routing

    private fun touchRouting() {
        if (uiState.value.isConnected) _routingDirty.value = true
    }

    /** Reconnects so pending routing changes reach the running tunnel. */
    fun applyRoutingNow() {
        _routingDirty.value = false
        if (uiState.value.isConnected && connectPhase.value == ConnectPhase.IDLE) doConnect()
    }

    fun setActiveRouting(id: Long) = viewModelScope.launch {
        repo.setActiveRouting(id)
        touchRouting()
    }

    fun setAdBlock(mode: AdBlockMode) = viewModelScope.launch {
        repo.setAdBlock(mode)
        touchRouting()
    }

    fun setBlockTorrents(v: Boolean) = viewModelScope.launch {
        repo.setBlockTorrents(v)
        touchRouting()
    }

    fun renameRoutingProfile(id: Long, name: String) = viewModelScope.launch {
        repo.renameRoutingProfile(id, name)
    }

    fun bindSubscriptionRouting(subscriptionId: Long, profileId: Long) {
        viewModelScope.launch {
            try {
                repo.bindSubscriptionRouting(subscriptionId, profileId)
                touchRouting()
                _message.value = "Правила применены к подписке"
            } catch (e: Exception) {
                _message.value = e.userMessage("Не удалось применить правила")
            }
        }
    }

    fun saveRules(profileId: Long, rules: List<RoutingRuleDto>) {
        viewModelScope.launch {
            repo.updateRoutingRules(profileId, rules)
            touchRouting()
        }
    }

    fun setRoutingProviderRules(profileId: Long, enabled: Boolean) =
        viewModelScope.launch {
            repo.setRoutingProviderRules(profileId, enabled)
            touchRouting()
        }

    fun setRoutingDns(profileId: Long, remote: String, domestic: String) =
        viewModelScope.launch { repo.setRoutingDns(profileId, remote, domestic) }

    fun createRoutingProfile(name: String) {
        viewModelScope.launch {
            repo.saveRoutingProfile(
                RoutingProfileEntity(
                    name = name,
                    description = "Свой набор правил",
                    rulesJson = RoutingEngine.serializeRules(emptyList()),
                    updatedAt = System.currentTimeMillis()
                )
            )
            _message.value = "Набор создан"
        }
    }

    fun copyRoutingProfile(id: Long) {
        viewModelScope.launch {
            repo.copyRoutingProfile(id)
            _message.value = "Создана редактируемая копия"
        }
    }

    fun deleteRoutingProfile(profile: RoutingProfileEntity) =
        viewModelScope.launch { repo.deleteRoutingProfile(profile) }

    fun parseRules(json: String) = RoutingEngine.parseRules(json)
    fun importRules(json: String) = RoutingEngine.importRules(json)
    fun serializeRules(rules: List<RoutingRuleDto>) = RoutingEngine.serializeRules(rules)

    // --------------------------------------------------------------- settings

    fun setDesignTheme(t: DesignTheme) = viewModelScope.launch { repo.setDesignTheme(t) }
    fun setPerAppMode(mode: PerAppMode) = viewModelScope.launch {
        repo.setPerAppMode(mode)
        touchRouting()
    }
    fun setPerAppPackages(packages: Set<String>) =
        viewModelScope.launch {
            repo.setPerAppPackages(packages)
            touchRouting()
        }
    fun setAutoFailover(v: Boolean) = viewModelScope.launch { repo.setAutoFailover(v) }
    fun setPingOnStart(v: Boolean) = viewModelScope.launch { repo.setPingOnStart(v) }
    fun setDefaultPingType(t: PingType) = viewModelScope.launch { repo.setDefaultPingType(t) }
    fun setBatterySaver(v: Boolean) = viewModelScope.launch { repo.setBatterySaver(v) }
    fun setLocalDns(v: Boolean) = viewModelScope.launch { repo.setLocalDns(v) }
    fun setSendHwid(v: Boolean) = viewModelScope.launch { repo.setSendHwid(v) }
    fun setAutoConnect(v: Boolean) = viewModelScope.launch { repo.setAutoConnect(v) }

    fun refreshIp() = viewModelScope.launch { runCatching { _ip.value = repo.fetchIp() } }

    fun clearMessage() {
        _message.value = null
    }

    fun showMessage(text: String) {
        _message.value = text
    }

    fun hwidFull(): String = repo.hwid()
}

private const val RETRY_DELAY_MS = 20_000L

/** The last [CAPACITY] speed samples, oldest first. [version] grows with each sample. */
data class SpeedHistory(
    val down: List<Long> = emptyList(),
    val up: List<Long> = emptyList(),
    val version: Long = 0
) {
    fun push(downSpeed: Long, upSpeed: Long) = SpeedHistory(
        down = (down + downSpeed.coerceAtLeast(0)).takeLast(CAPACITY),
        up = (up + upSpeed.coerceAtLeast(0)).takeLast(CAPACITY),
        version = version + 1
    )

    companion object {
        const val CAPACITY = 40
    }
}

/** One finalist of an auto pick, as the "why this node" card shows it. */
data class PickRow(
    val serverId: Long,
    val name: String,
    val medianMs: Long,
    val jitterMs: Long,
    val samples: Int,
    val chosen: Boolean
)

data class PickSummary(
    val reason: String,
    val rows: List<PickRow>,
    val measured: Int,
    val alive: Int,
    val at: Long
) {
    companion object {
        fun of(decision: AutoBalancer.Decision): PickSummary? {
            val alive = decision.ranked.filter { it.ok }
            if (alive.isEmpty()) return null
            val chosenId = decision.selected?.id
            val top = alive.take(3).toMutableList()
            alive.firstOrNull { it.server.id == chosenId }
                ?.takeIf { c -> top.none { it.server.id == c.server.id } }
                ?.let { top[top.lastIndex] = it }
            return PickSummary(
                reason = decision.reason,
                rows = top.map {
                    PickRow(
                        serverId = it.server.id,
                        name = it.server.remark,
                        medianMs = it.latencyMs,
                        jitterMs = it.jitterMs,
                        samples = it.samples,
                        chosen = it.server.id == chosenId
                    )
                },
                measured = decision.ranked.size,
                alive = alive.size,
                at = System.currentTimeMillis()
            )
        }
    }
}

enum class ConnectPhase(val caption: String) {
    IDLE(""),
    PREPARING("Готовлю подключение…"),
    PROBING("Проверяю сеть: Home или LTE…"),
    MEASURING("Ищу самый быстрый сервер…"),
    REFINING("Сравниваю лучшие серверы…");

    companion object {
        fun of(phase: AutoBalancer.Phase): ConnectPhase = when (phase) {
            AutoBalancer.Phase.PROBING_NETWORK -> PROBING
            AutoBalancer.Phase.MEASURING -> MEASURING
            AutoBalancer.Phase.REFINING -> REFINING
        }
    }
}

private fun Throwable.userMessage(fallback: String): String =
    message?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
