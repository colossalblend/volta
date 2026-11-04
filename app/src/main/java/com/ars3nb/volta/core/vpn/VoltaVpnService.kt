package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.R
import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.core.locale.AppLanguage
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.locale.UiTextException
import com.ars3nb.volta.core.locale.uiText
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.stats.TrafficRecorder
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.notification.VpnNotificationHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VoltaVpnService : VpnService() {

    /** In the language picked in Settings (Android 12 and older; 13+ does it itself). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    companion object {
        private const val TAG = "VoltaVpnService"
        const val ACTION_CONNECT = "com.ars3nb.volta.CONNECT"
        const val ACTION_DISCONNECT = "com.ars3nb.volta.DISCONNECT"
        const val ACTION_TOGGLE = "com.ars3nb.volta.TOGGLE"
        const val ACTION_AUTO_CONNECT = "com.ars3nb.volta.AUTO_CONNECT"
        const val EXTRA_MODE = "mode"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_PER_APP_MODE = "per_app_mode"
        const val EXTRA_PER_APP_PACKAGES = "per_app_packages"
        const val EXTRA_SERVER_ID = "server_id"
        const val EXTRA_OPENFLUX_LINK = "openflux_link"
        const val EXTRA_HELPER_KIND = "helper_kind"
        const val EXTRA_HELPER_CONFIG = "helper_config"

        private const val SOCKS_PORT_DEFAULT = 10808
        private const val HEALTH_ATTEMPTS = 3
        private const val MAX_FAILOVERS = 4

        private val _state = MutableStateFlow(VpnConnectionState.DISCONNECTED)
        val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

        private val _serverName = MutableStateFlow("")
        val serverName: StateFlow<String> = _serverName.asStateFlow()

        private val _serverId = MutableStateFlow(0L)
        val serverId: StateFlow<Long> = _serverId.asStateFlow()

        /** Why the last attempt failed, read in the screen's language when it is shown. */
        private val _error = MutableStateFlow<UiText?>(null)
        val error: StateFlow<UiText?> = _error.asStateFlow()

        private val _traffic = MutableStateFlow(TrafficStats())
        val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

        private val _connectedSince = MutableStateFlow(0L)
        val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

        private val _alwaysOn = MutableStateFlow<AlwaysOn?>(null)

        /**
         * Android's Always-on VPN for Volta, as the running service last saw it. Null
         * until a tunnel has run in this process, and on Android 9 and older, which
         * does not tell an app.
         */
        val alwaysOn: StateFlow<AlwaysOn?> = _alwaysOn.asStateFlow()

        /**
         * Set by the repository so the service can rebuild a config after a node dies
         * without waiting for the UI process to be alive.
         */
        @Volatile
        var failoverProvider: (suspend (failedServerId: Long) -> ConnectionPlan?)? = null

        /**
         * Set by the repository: raises the tunnel in the current connect mode. Used when
         * Android itself starts the service — Always-on VPN, or a restart after the
         * system killed the process — and no plan arrived with the intent.
         */
        @Volatile
        var autoStartProvider: (suspend (modeName: String?) -> Unit)? = null

        /** Told which node just proved it carries traffic, so auto-pick can start there. */
        @Volatile
        var healthyListener: (suspend (serverId: Long) -> Unit)? = null

        fun connect(context: Context, plan: ConnectionPlan) {
            val i = Intent(context, VoltaVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_CONFIG, plan.configJson)
                putExtra(EXTRA_SERVER_NAME, plan.serverName)
                putExtra(EXTRA_SERVER_ID, plan.serverId)
                putExtra(EXTRA_MTU, plan.mtu)
                putExtra(EXTRA_SOCKS_PORT, plan.socksPort)
                putExtra(EXTRA_PER_APP_MODE, plan.perAppMode.name)
                putExtra(EXTRA_PER_APP_PACKAGES, plan.perAppPackages.toTypedArray())
                putExtra(EXTRA_OPENFLUX_LINK, plan.openFluxLink)
                putExtra(EXTRA_HELPER_KIND, plan.helper?.kind)
                putExtra(EXTRA_HELPER_CONFIG, plan.helper?.config)
            }
            startInForeground(context, i)
        }

        /**
         * Raises the tunnel in a connect mode (or the saved one), with the service in the
         * foreground from the first moment. Tiles and boot use this: the pick takes
         * seconds, and Android only lets a background app start a foreground service
         * right after the tap or broadcast, not once the measuring is done.
         */
        /**
         * False when Android refused the start: a background app may start a foreground
         * service only right after a tap, a boot or a similar exemption (Android 12+).
         */
        fun autoConnect(context: Context, modeName: String? = null): Boolean = runCatching {
            val i = Intent(context, VoltaVpnService::class.java)
                .setAction(ACTION_AUTO_CONNECT)
                .putExtra(EXTRA_MODE, modeName)
            startInForeground(context, i)
        }.onFailure { Log.w(TAG, "automatic start refused", it) }.isSuccess

        /**
         * Never throws: with the service already gone (an error ended it) there is
         * nothing to stop, and Android refuses a plain start from the background.
         */
        fun disconnect(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, VoltaVpnService::class.java).setAction(ACTION_DISCONNECT)
                )
            }.onFailure { Log.w(TAG, "disconnect not delivered", it) }
        }

        fun toggle(context: Context): Boolean = runCatching {
            startInForeground(context, Intent(context, VoltaVpnService::class.java).setAction(ACTION_TOGGLE))
        }.onFailure { Log.w(TAG, "toggle refused", it) }.isSuccess

        private fun startInForeground(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * One lock for every instance of the service. The helpers (OpenFlux, voltaproxy)
         * and the TUN bridge exist once per process, so a stopped service's cleanup must
         * be done before the next instance raises its tunnel, and never run into it.
         */
        private val connectionMutex = Mutex()

        /** Outlives an instance: the cleanup after onDestroy runs here, off the main thread. */
        private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    /** Always-on VPN is set to Volta; [lockdown]: no internet at all without the VPN. */
    data class AlwaysOn(val on: Boolean, val lockdown: Boolean)

    /** Everything the service needs to raise a tunnel, built by the repository. */
    data class ConnectionPlan(
        val serverId: Long,
        val serverName: String,
        val configJson: String,
        val mtu: Int = 1420,
        val socksPort: Int = SOCKS_PORT_DEFAULT,
        /** Split tunnelling, applied to the VpnService.Builder. */
        val perAppMode: PerAppMode = PerAppMode.OFF,
        val perAppPackages: Set<String> = emptySet(),
        /** An OpenFlux server: the helper is started on this link before the core. */
        val openFluxLink: String = "",
        /** AmneziaWG or Hysteria v1: the voltaproxy helper runs this before the core. */
        val helper: ProxyHelper.Spec? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var xray: XrayCore? = null
    private var tun2Socks: Tun2Socks? = null
    private val tunnelActive = AtomicBoolean(false)
    private val pending = PendingJobs()
    private var watchdogJob: Job? = null

    /**
     * The tunnel asked for last: by the user (a connect intent) or by a failover. Set on
     * the main thread when a request is accepted, so the next one is compared with it.
     */
    @Volatile
    private var wanted: ConnectionPlan? = null

    /** What the VPN interface that is up right now was built from; null while down. */
    @Volatile
    private var runningTun: TunSpec? = null

    /**
     * Bumped by every connect and disconnect the user asks for. A queued connect that is
     * no longer the latest request is skipped: the newer one does the work, so tapping
     * through five servers does not raise five tunnels one after another.
     */
    @Volatile
    private var requestSeq = 0

    /** The latest start id Android delivered; stopping with it never drops a newer start. */
    @Volatile
    private var lastStartId = 0

    @Volatile
    private var destroyed = false

    @Volatile
    private var failoverAttempts = 0
    private var healthJob: Job? = null
    private var lastUnderlying: Network? = null

    /**
     * Bumped whenever a core takes over the tunnel. A check that started under an older
     * core and reports it broken is about a node the tunnel already left.
     */
    @Volatile
    private var generation = 0

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

    /** The port the running core's SOCKS inbound listens on (the probes go through it). */
    private val socksPort: Int get() = runningTun?.socksPort ?: wanted?.socksPort ?: SOCKS_PORT_DEFAULT
    private val serverLabel: String get() = wanted?.serverName.orEmpty()

    /**
     * Follows the physical network under the tunnel (this app is excluded from its own
     * VPN, so its default network *is* the underlying one). Like v2rayNG we hand it to
     * setUnderlyingNetworks(): Android then reports the right metered/validated state
     * and keeps the VPN usable across Wi-Fi ↔ LTE hand-overs. After a hand-over the old
     * sockets to the server are dead, so the tunnel is re-checked straight away instead
     * of waiting a minute for the watchdog.
     */
    private val underlyingCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runCatching { setUnderlyingNetworks(arrayOf(network)) }
            val previous = lastUnderlying
            lastUnderlying = network
            if (previous != null && previous != network && tunnelActive.get()) {
                Log.i(TAG, "underlying network changed — re-checking the tunnel")
                startHealthCheck(UiText.of(R.string.home_vpn_network_changed), initialDelayMs = 6_000)
            }
        }

        override fun onLost(network: Network) {
            if (network == lastUnderlying) runCatching { setUnderlyingNetworks(null) }
        }
    }

    private val probeClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        tun2Socks = Tun2Socks(this)
        TrafficRecorder.init(this)
        VpnNotificationHelper.ensureChannel(this)
        runCatching { connectivity?.registerDefaultNetworkCallback(underlyingCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Turning Always-on or its lockdown on in Android's settings starts the service.
        noteAlwaysOn()
        when (intent?.action) {
            ACTION_CONNECT -> {
                val plan = planOf(intent)
                // Foreground must be entered synchronously or Android kills the start.
                enterForeground(plan.serverName, connecting = true)
                if (TunnelRequests.isRepeat(_state.value, plan, wanted)) {
                    Log.d(TAG, "Ignoring duplicate connect while state=${_state.value}")
                } else {
                    wanted = plan
                    val seq = ++requestSeq
                    _error.value = null
                    if (_state.value != VpnConnectionState.CONNECTED) {
                        _state.value = VpnConnectionState.CONNECTING
                        _serverName.value = plan.serverName
                        _serverId.value = plan.serverId
                    }
                    enqueue {
                        // A newer connect or a disconnect came in meanwhile: theirs stands.
                        if (seq != requestSeq) return@enqueue
                        failoverAttempts = 0
                        moveTo(plan)
                    }
                }
            }

            ACTION_DISCONNECT -> requestDisconnect()

            ACTION_TOGGLE -> {
                // Started with startForegroundService (the notification, a shortcut):
                // the foreground promise is kept on every branch.
                enterForeground(serverLabel, connecting = _state.value != VpnConnectionState.CONNECTED)
                when (_state.value) {
                    VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING -> requestDisconnect()
                    VpnConnectionState.DISCONNECTING -> Unit
                    VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR -> {
                        val plan = wanted
                        if (plan == null) {
                            // A fresh service knows no config; connect in the saved mode.
                            startOnItsOwn()
                        } else {
                            val seq = ++requestSeq
                            _state.value = VpnConnectionState.CONNECTING
                            _error.value = null
                            enqueue {
                                if (seq != requestSeq) return@enqueue
                                failoverAttempts = 0
                                doConnect(plan)
                            }
                        }
                    }
                }
            }

            ACTION_AUTO_CONNECT -> startOnItsOwn(intent.getStringExtra(EXTRA_MODE))

            // Always-on VPN (android.net.VpnService) or a sticky restart (null intent).
            SERVICE_INTERFACE, null -> startOnItsOwn()

            else -> if (tunnelActive.get()) enterForeground(serverLabel, connecting = false)
        }
        // Sticky while a tunnel is wanted: if Android kills the process, it brings the
        // service back and startOnItsOwn() reconnects in the same mode.
        return if (tunnelActive.get() || _state.value == VpnConnectionState.CONNECTING) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun planOf(intent: Intent): ConnectionPlan = ConnectionPlan(
        serverId = intent.getLongExtra(EXTRA_SERVER_ID, 0),
        serverName = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty(),
        configJson = intent.getStringExtra(EXTRA_CONFIG).orEmpty(),
        mtu = intent.getIntExtra(EXTRA_MTU, 1420),
        socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, SOCKS_PORT_DEFAULT),
        perAppMode = runCatching {
            PerAppMode.valueOf(intent.getStringExtra(EXTRA_PER_APP_MODE).orEmpty())
        }.getOrDefault(PerAppMode.OFF),
        perAppPackages = intent.getStringArrayExtra(EXTRA_PER_APP_PACKAGES)?.toSet().orEmpty(),
        openFluxLink = intent.getStringExtra(EXTRA_OPENFLUX_LINK).orEmpty(),
        helper = helperOf(intent)
    )

    /**
     * Runs [block] under the connect lock, after the jobs already waiting. Counted, so a
     * job that would stop the service sees whether another one waits behind it.
     */
    private fun enqueue(block: suspend () -> Unit) {
        pending.enter()
        scope.launch {
            try {
                connectionMutex.withLock { block() }
            } finally {
                pending.leave()
            }
        }
    }

    /**
     * The user turns the VPN off. A connect asked for after this (a quick off-and-on) is
     * a new request, never dropped as a repeat of the old one.
     */
    private fun requestDisconnect() {
        abandonStart()
        wanted = null
        ++requestSeq
        enqueue { doDisconnect() }
    }

    /**
     * Turning off while an OpenFlux helper still waits for its exit (or for a captcha)
     * must not wait those seconds or minutes for the connect to give up: the helper is
     * let go at once, and the waiting connect ends without moving to another node.
     */
    private fun abandonStart() {
        disconnectsAsked++
        if (_state.value == VpnConnectionState.CONNECTING) {
            // Stopping waits for the helper to exit: not on the main thread.
            scope.launch { runCatching { OpenFluxRunner.stop() } }
        }
    }

    /**
     * Bumped when the user turns the VPN off. A node that failed to start after that
     * is not moved on from: the user is leaving anyway.
     */
    @Volatile
    private var disconnectsAsked = 0

    /**
     * The OpenFlux helper for the node, before the core hands it anything: it waits
     * until a carrier reaches the exit, or a captcha is passed. Not reaching the exit is
     * the node's failure ([NodeStartException]), and the tunnel moves to another node
     * as it does for a node that stops answering.
     */
    private suspend fun startOpenFlux(link: String) {
        OpenFluxRunner.start(this, link).onFailure { e ->
            if (e is CancellationException) throw e
            throw NodeStartException(e.message ?: getString(R.string.home_vpn_openflux_failed), e)
        }
    }

    /** The node itself did not come up (its OpenFlux exit didn't answer). */
    private class NodeStartException(message: String, cause: Throwable?) : Exception(message, cause)

    private fun startOnItsOwn(modeName: String? = null) {
        if (tunnelActive.get() || _state.value == VpnConnectionState.CONNECTING) {
            enterForeground(serverLabel, connecting = !tunnelActive.get())
            return
        }
        enterForeground(serverLabel, connecting = true)
        _state.value = VpnConnectionState.CONNECTING
        _error.value = null
        // The provider's connect may carry the very plan this instance last used;
        // it must not be dropped as a duplicate of a connect that is not running.
        wanted = null
        val provider = autoStartProvider
        scope.launch {
            if (provider == null) {
                enqueue { fail(UiText.of(R.string.home_vpn_auto_start_failed)) }
                return@launch
            }
            // The provider ends in connect(), which arrives here as ACTION_CONNECT.
            try {
                provider(modeName)
            } catch (e: CancellationException) {
                // The service itself is going away.
                if (!isActive) throw e
                // The pick was stopped (a second tap on the disc): leave quietly.
                if (wanted == null) enqueue { if (wanted == null) doDisconnect() }
            } catch (e: Exception) {
                Log.w(TAG, "automatic start failed", e)
                val message = e.uiText(UiText.of(R.string.home_connect_failed))
                // Started from a tile or at boot, there is no screen to show the error on.
                // Posted to the main looper: it must outlive this service's scope.
                val app = applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(app, "Volta: ${message.resolve(app)}", Toast.LENGTH_LONG).show()
                }
                // Only if nothing else took over meanwhile (the user's own connect).
                if (wanted == null) enqueue { if (wanted == null) fail(message) }
            }
        }
    }

    /**
     * A fresh tunnel says nothing about whether the server answers: the core starts
     * fine against a dead node. Check right away (Happ/Hiddify do the same) and move
     * on to the next node within seconds, not after the one-minute watchdog.
     */
    @Synchronized
    private fun startHealthCheck(reason: UiText, initialDelayMs: Long = 2_500) {
        healthJob?.cancel()
        val gen = generation
        healthJob = scope.launch {
            delay(initialDelayMs)
            repeat(HEALTH_ATTEMPTS) { attempt ->
                if (!tunnelActive.get()) return@launch
                // No network at all is not the server's fault. The tunnel stays up and
                // the check runs again when a network comes back (underlyingCallback).
                if (!hasUnderlyingNetwork()) return@launch
                if (probeThroughTunnel()) {
                    failoverAttempts = 0
                    val id = _serverId.value
                    healthyListener?.let { listener -> runCatching { listener(id) } }
                    return@launch
                }
                Log.w(TAG, "health check ${attempt + 1}/$HEALTH_ATTEMPTS failed")
                delay(2_500)
            }
            // Its own job: recovery restarts the tunnel, which cancels this one.
            if (tunnelActive.get()) reportBroken(reason, gen)
        }
    }

    /**
     * Brings the tunnel to [plan]: when the VPN interface that is up fits it, only the
     * core (and its helpers) is replaced; otherwise the whole tunnel is rebuilt. Decided
     * here, under the lock, against what really runs.
     */
    private suspend fun moveTo(plan: ConnectionPlan) {
        val next = TunnelRequests.tunOf(plan)
        val coreUp = tunnelActive.get() && xray != null
        if (TunnelRequests.canSwapCore(runningTun, coreUp, next)) {
            Log.i(TAG, "Swapping the core onto a new node")
            swapCore(plan)
        } else {
            if (coreUp) Log.i(TAG, "Reconnecting onto a new config")
            doConnect(plan)
        }
    }

    private suspend fun doConnect(plan: ConnectionPlan) {
        val name = plan.serverName
        if (plan.configJson.isBlank()) {
            fail(UiText.of(R.string.home_server_none))
            return
        }
        wanted = plan
        _state.value = VpnConnectionState.CONNECTING
        _serverName.value = name
        _serverId.value = plan.serverId
        _error.value = null
        val asked = disconnectsAsked

        try {
            cleanup()

            // An OpenFlux server: its helper must carry traffic before the core hands
            // anything to it. Waits for the carriers to reach the exit, or a captcha.
            if (plan.openFluxLink.isNotBlank()) {
                enterForeground(name, connecting = true)
                startOpenFlux(plan.openFluxLink)
            }
            currentCoroutineContext().ensureActive()
            // AmneziaWG / Hysteria v1: the helper's proxy must listen before the core.
            plan.helper?.let { ProxyHelper.start(this, it).getOrThrow() }
            currentCoroutineContext().ensureActive()

            // The core comes up first. Raising the TUN before it would black-hole every
            // packet on the device while geodata is unpacked and the core boots.
            val core = newCore().also { xray = it }
            core.start(plan.configJson, tunFd = 0).getOrThrow()
            currentCoroutineContext().ensureActive()

            val tun = TunnelRequests.tunOf(plan)
            val useIpv6 = hasGlobalIpv6()
            val builder = Builder()
                .setSession("Volta · $name")
                .setMtu(tun.mtu)
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .setBlocking(false)
            // When the core answers DNS: 10.10.14.2, the other address of this /30.
            tun.dns.forEach { builder.addDnsServer(it) }

            applyPerAppRules(builder, plan.perAppMode, plan.perAppPackages)

            // Claiming ::/0 on an IPv4-only network makes apps prefer a route that
            // cannot carry traffic, which reads as "VPN connected but nothing loads".
            if (useIpv6) {
                builder.addAddress("fd00:10:10::1", 126)
                builder.addRoute("::", 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val fd = builder.establish()
                ?: throw UiTextException(UiText.of(R.string.home_vpn_no_interface), this)
            tunFd = fd
            noteAlwaysOn()

            val bridge = tun2Socks ?: Tun2Socks(this).also { tun2Socks = it }
            bridge.start(fd.fd, tun.mtu, tun.socksPort).getOrThrow()
            if (!bridge.isRunning()) throw UiTextException(UiText.of(R.string.home_vpn_not_started), this)

            generation++
            runningTun = tun
            tunnelActive.set(true)
            _state.value = VpnConnectionState.CONNECTED
            _connectedSince.value = System.currentTimeMillis()
            _traffic.value = TrafficStats()
            enterForeground(name, connecting = false)

            watchdogJob?.cancel()
            watchdogJob = scope.launch { watchdog() }
            startHealthCheck(UiText.of(R.string.home_server_not_responding))
        } catch (e: CancellationException) {
            // The service is going away; its own cleanup takes what was started.
            throw e
        } catch (e: NodeStartException) {
            // Turned off meanwhile: the disconnect queued behind this finishes the job.
            if (disconnectsAsked != asked) return
            Log.w(TAG, "node did not come up", e)
            failOver(e.uiText(UiText.of(R.string.home_connect_failed)))
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(e.uiText(UiText.of(R.string.home_connect_failed)))
        }
    }

    /**
     * Moves a live tunnel to another node by replacing only the core. The VPN interface
     * and the TUN bridge stay up (the new core listens on the same local port), so the
     * switch costs the open connections a moment, not the whole tunnel.
     */
    private suspend fun swapCore(plan: ConnectionPlan) {
        val name = plan.serverName
        wanted = plan
        watchdogJob?.cancel()
        healthJob?.cancel()
        runCatching { xray?.stop() }
        xray = null
        val asked = disconnectsAsked
        try {
            // The new node may need an OpenFlux helper (another link replaces the one
            // running: only one runs at a time), or none: then a running one stops. Its
            // exit can take seconds to answer; the VPN interface stays up meanwhile, so
            // apps wait instead of leaking past the tunnel.
            val openFluxLink = plan.openFluxLink
            if (openFluxLink.isNotBlank()) {
                if (!OpenFluxRunner.connectedOn(openFluxLink)) {
                    _state.value = VpnConnectionState.CONNECTING
                    _serverName.value = name
                    _serverId.value = plan.serverId
                    enterForeground(name, connecting = true)
                }
                startOpenFlux(openFluxLink)
            } else {
                OpenFluxRunner.stop()
            }
            currentCoroutineContext().ensureActive()
            // The same for the voltaproxy helper: needed, a different one, or none.
            val helper = plan.helper
            if (helper != null) ProxyHelper.start(this, helper).getOrThrow() else ProxyHelper.stop()
            currentCoroutineContext().ensureActive()
            val core = newCore().also { xray = it }
            core.start(plan.configJson, tunFd = 0).getOrThrow()
            generation++
            _serverName.value = name
            _serverId.value = plan.serverId
            _state.value = VpnConnectionState.CONNECTED
            enterForeground(name, connecting = false)
            watchdogJob = scope.launch { watchdog() }
            startHealthCheck(UiText.of(R.string.home_server_not_responding))
        } catch (e: CancellationException) {
            throw e
        } catch (e: NodeStartException) {
            if (disconnectsAsked != asked) return
            Log.w(TAG, "node did not come up, moving on", e)
            failOver(e.uiText(UiText.of(R.string.home_connect_failed)))
        } catch (e: Exception) {
            // The new core refused to start: rebuild the whole tunnel on it, which
            // reports a real failure the usual way.
            Log.w(TAG, "core swap failed, reconnecting", e)
            doConnect(plan)
        }
    }

    private fun helperOf(intent: Intent): ProxyHelper.Spec? {
        val kind = intent.getStringExtra(EXTRA_HELPER_KIND).orEmpty()
        val config = intent.getStringExtra(EXTRA_HELPER_CONFIG).orEmpty()
        return if (kind.isBlank() || config.isBlank()) null else ProxyHelper.Spec(kind, config)
    }

    /** A core whose own shutdown counts only while it still carries the tunnel. */
    private fun newCore(): XrayCore {
        lateinit var core: XrayCore
        core = XrayCore(this) { if (xray === core) onCoreStoppedItself() }
        return core
    }

    /**
     * One low-cost loop: refreshes traffic counters, notices a dead component and,
     * once a minute, checks that bytes actually flow through the tunnel.
     */
    private suspend fun watchdog() {
        val gen = generation
        val plan = wanted
        var uplinkTotal = 0L
        var downlinkTotal = 0L
        var lastProbe = System.currentTimeMillis()
        var probeFailures = 0
        var notificationTick = 0

        while (tunnelActive.get()) {
            delay(2_000)
            if (!tunnelActive.get()) break

            val openFluxDown = plan?.openFluxLink?.isNotBlank() == true && !OpenFluxRunner.isRunning()
            val helperDown = plan?.helper != null && !ProxyHelper.isRunning()
            if (xray?.isRunning() != true || tun2Socks?.isRunning() != true || openFluxDown || helperDown) {
                Log.w(
                    TAG,
                    "component died — core=${xray?.isRunning()} tun=${tun2Socks?.isRunning()} " +
                        "openflux=${!openFluxDown} helper=${!helperDown}"
                )
                // Its own job: recovery restarts the tunnel, which cancels this one.
                reportBroken(UiText.of(R.string.home_vpn_connection_dropped), gen)
                return
            }

            // Xray resets a counter when it is read, so these are deltas.
            val (upDelta, downDelta) = xray?.trafficDelta(ConfigBuilder.PROXY_TAG) ?: (0L to 0L)
            uplinkTotal += upDelta
            downlinkTotal += downDelta
            TrafficRecorder.count(upDelta, downDelta)
            _traffic.value = TrafficStats(
                uplinkBytes = uplinkTotal,
                downlinkBytes = downlinkTotal,
                uplinkSpeed = upDelta / 2,
                downlinkSpeed = downDelta / 2
            )

            if (++notificationTick >= 5) {
                notificationTick = 0
                enterForeground(serverLabel, connecting = false)
            }

            val now = System.currentTimeMillis()
            if (now - lastProbe >= 60_000) {
                lastProbe = now
                // Traffic in the last minute already proves the tunnel works. With the
                // screen off the check waits: ConnectionGuard runs one as it comes on.
                if (downDelta > 0 || !hasUnderlyingNetwork() || !ScreenState.on.value) {
                    probeFailures = 0
                } else if (!probeThroughTunnel()) {
                    probeFailures++
                    Log.w(TAG, "tunnel probe failed ($probeFailures)")
                    if (probeFailures >= 2) {
                        reportBroken(UiText.of(R.string.home_vpn_server_stopped_answering), gen)
                        return
                    }
                } else {
                    probeFailures = 0
                    failoverAttempts = 0
                }
            }
        }
    }

    /**
     * Any HTTP answer through the local proxy proves the exit works. Two unrelated
     * hosts, so one CDN having a bad minute does not trigger a server switch.
     */
    private suspend fun probeThroughTunnel(): Boolean = withContext(Dispatchers.IO) {
        val client = probeClient.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            .build()
        PROBE_URLS.any { url ->
            runCatching {
                client.newCall(Request.Builder().url(url).head().build())
                    .execute().use { it.code in 100..599 }
            }.getOrDefault(false)
        }
    }

    private fun onCoreStoppedItself() {
        if (!tunnelActive.get()) return
        reportBroken(UiText.of(R.string.home_vpn_core_stopped), generation)
    }

    /** Try the next best node before giving up, so a dead exit is not a dead app. */
    private fun reportBroken(reason: UiText, gen: Int) = enqueue {
        // Already moved to another node, or turned off, since the report was made.
        if (!tunnelActive.get() || gen != generation) return@enqueue
        failOver(reason)
    }

    /**
     * Holding the connect lock: the next node the repository picks in place of the one
     * in use (it stays in the group the mode asks for), or the error when there is none.
     */
    private suspend fun failOver(reason: UiText) {
        val provider = failoverProvider
        if (provider != null && failoverAttempts < MAX_FAILOVERS) {
            failoverAttempts++
            // Short backoff so a flapping network is not hammered with reconnects.
            delay(700L * failoverAttempts)
            _state.value = VpnConnectionState.CONNECTING
            enterForeground(serverLabel, connecting = true)
            val failedId = wanted?.serverId ?: _serverId.value
            val plan = try {
                provider(failedId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (plan != null) {
                Log.i(TAG, "failover #$failoverAttempts -> ${plan.serverName}")
                doConnect(plan)
                return
            }
        }
        fail(reason)
    }

    private fun fail(message: UiText) {
        _error.value = message
        _state.value = VpnConnectionState.ERROR
        cleanup()
        stopUnlessMoreQueued()
    }

    private fun doDisconnect() {
        _state.value = VpnConnectionState.DISCONNECTING
        cleanup()
        _state.value = VpnConnectionState.DISCONNECTED
        stopUnlessMoreQueued()
    }

    /**
     * Ends the service, unless another request is already on its way: one queued here,
     * or a start Android has not delivered yet (stopping with the latest start id
     * refuses then). Either one carries on in this service.
     */
    private fun stopUnlessMoreQueued() {
        if (!pending.alone()) return
        if (stopSelfResult(lastStartId)) stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun cleanup() {
        watchdogJob?.cancel()
        watchdogJob = null
        synchronized(this) {
            healthJob?.cancel()
            healthJob = null
        }
        // The bytes of the last half minute, before the counters are gone.
        TrafficRecorder.flush()
        tunnelActive.set(false)
        runningTun = null
        _connectedSince.value = 0
        _traffic.value = TrafficStats()
        runCatching { tun2Socks?.stop() }
        runCatching { xray?.stop() }
        xray = null
        runCatching { OpenFluxRunner.stop() }
        runCatching { ProxyHelper.stop() }
        runCatching { tunFd?.close() }
        tunFd = null
    }

    /**
     * Into the foreground with the status notification. Never throws: Android 12+ may
     * refuse a service it restarted itself (sticky) or one Always-on started; the tunnel
     * still comes up then, since the system holds on to an established VPN.
     */
    private fun enterForeground(serverName: String, connecting: Boolean) {
        if (destroyed) return
        try {
            startForeground(VpnNotificationHelper.NOTIFICATION_ID, buildNotification(serverName, connecting))
        } catch (e: Exception) {
            Log.w(TAG, "foreground refused", e)
        }
    }

    private fun buildNotification(serverName: String, connecting: Boolean): Notification =
        VpnNotificationHelper.build(
            // The app's context: on Android 12 and older it follows a language switch made
            // while the tunnel runs (VoltaApp.getResources); this service's own does not.
            context = applicationContext,
            serverName = serverName,
            connecting = connecting,
            connected = !connecting && tunnelActive.get(),
            traffic = _traffic.value
        )

    override fun onDestroy() {
        destroyed = true
        runCatching { connectivity?.unregisterNetworkCallback(underlyingCallback) }
        scope.cancel()
        // Stopping the helpers and the bridge can take seconds (each waits for its
        // process or thread to finish): never on the main thread, where it would freeze
        // the app. Under the shared lock, after a connect that is still finishing, and
        // before the next instance's first connect.
        cleanupScope.launch {
            connectionMutex.withLock {
                // A connect that was past its last check when the scope went may have
                // raised the tunnel after all: it is taken down, and said so.
                val cameUp = tunnelActive.get()
                cleanup()
                if (cameUp) _state.value = VpnConnectionState.DISCONNECTED
            }
        }
        if (_state.value != VpnConnectionState.DISCONNECTED &&
            _state.value != VpnConnectionState.ERROR
        ) {
            _state.value = VpnConnectionState.DISCONNECTED
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        // Another VPN took over. Tear down under the same lock a connect would hold.
        requestDisconnect()
        super.onRevoke()
    }

    /**
     * Split tunnelling.
     *
     * This app is always excluded: the core runs in this process and its sockets must not
     * re-enter the tunnel (this libv2ray build has no protect() callback). In INCLUDE mode
     * that means adding only the chosen packages — Android forbids mixing allowed and
     * disallowed lists, so the app is simply never in the allowed set.
     */
    private fun applyPerAppRules(builder: Builder, perAppMode: PerAppMode, perAppPackages: Set<String>) {
        val packages = perAppPackages.filter { it.isNotBlank() && it != packageName }
        when {
            perAppMode == PerAppMode.INCLUDE && packages.isNotEmpty() -> {
                val added = packages.count { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "allowed app missing: $pkg") }
                        .isSuccess
                }
                // None of the chosen apps is installed (say, a list restored on a new
                // phone). An empty allowed list would put every app, this one too, into
                // the tunnel and loop the core's own traffic: cover all but Volta instead.
                if (added == 0) runCatching { builder.addDisallowedApplication(packageName) }
            }

            perAppMode == PerAppMode.EXCLUDE && packages.isNotEmpty() -> {
                runCatching { builder.addDisallowedApplication(packageName) }
                packages.forEach { pkg ->
                    runCatching { builder.addDisallowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "disallowed app missing: $pkg") }
                }
            }

            else -> runCatching { builder.addDisallowedApplication(packageName) }
        }
    }

    private fun noteAlwaysOn() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching { _alwaysOn.value = AlwaysOn(on = isAlwaysOn(), lockdown = isLockdownEnabled()) }
    }

    /** This app is outside its own VPN, so its active network is the physical one. */
    private fun hasUnderlyingNetwork(): Boolean =
        runCatching { connectivity?.activeNetwork != null }.getOrDefault(true)

    /**
     * True when the underlying network actually has a routable IPv6 address. Tunnel
     * interfaces (ours or another VPN's) and ULA fd00::/8 addresses do not count: a
     * router handing out only ULA has no IPv6 internet behind it.
     */
    private fun hasGlobalIpv6(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
            nif.isUp && !nif.isLoopback && !nif.isVirtual &&
                !nif.name.startsWith("tun") &&
                nif.inetAddresses.asSequence().any { addr ->
                    addr is java.net.Inet6Address &&
                        !addr.isLinkLocalAddress &&
                        !addr.isLoopbackAddress &&
                        !addr.isAnyLocalAddress &&
                        !addr.isSiteLocalAddress &&
                        (addr.address[0].toInt() and 0xfe) != 0xfc
                }
        }
    }.getOrDefault(false)
}

private val PROBE_URLS = listOf(
    "https://www.gstatic.com/generate_204",
    "https://cp.cloudflare.com/generate_204"
)
