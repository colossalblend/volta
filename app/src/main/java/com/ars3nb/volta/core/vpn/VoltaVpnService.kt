package com.ars3nb.volta.core.vpn

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

        private const val SOCKS_PORT_DEFAULT = 10808
        private const val HEALTH_ATTEMPTS = 3
        private const val MAX_FAILOVERS = 4

        private val _state = MutableStateFlow(VpnConnectionState.DISCONNECTED)
        val state: StateFlow<VpnConnectionState> = _state.asStateFlow()

        private val _serverName = MutableStateFlow("")
        val serverName: StateFlow<String> = _serverName.asStateFlow()

        private val _serverId = MutableStateFlow(0L)
        val serverId: StateFlow<Long> = _serverId.asStateFlow()

        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        private val _traffic = MutableStateFlow(TrafficStats())
        val traffic: StateFlow<TrafficStats> = _traffic.asStateFlow()

        private val _connectedSince = MutableStateFlow(0L)
        val connectedSince: StateFlow<Long> = _connectedSince.asStateFlow()

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
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        /**
         * Raises the tunnel in a connect mode (or the saved one), with the service in the
         * foreground from the first moment. Tiles and boot use this: the pick takes
         * seconds, and Android only lets a background app start a foreground service
         * right after the tap or broadcast, not once the measuring is done.
         */
        fun autoConnect(context: Context, modeName: String? = null) {
            val i = Intent(context, VoltaVpnService::class.java)
                .setAction(ACTION_AUTO_CONNECT)
                .putExtra(EXTRA_MODE, modeName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun disconnect(context: Context) {
            context.startService(
                Intent(context, VoltaVpnService::class.java).setAction(ACTION_DISCONNECT)
            )
        }

        fun toggle(context: Context) {
            val i = Intent(context, VoltaVpnService::class.java).setAction(ACTION_TOGGLE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }

    /** Everything the service needs to raise a tunnel, built by the repository. */
    data class ConnectionPlan(
        val serverId: Long,
        val serverName: String,
        val configJson: String,
        val mtu: Int = 1420,
        val socksPort: Int = SOCKS_PORT_DEFAULT,
        /** Split tunnelling, applied to the VpnService.Builder. */
        val perAppMode: PerAppMode = PerAppMode.OFF,
        val perAppPackages: Set<String> = emptySet()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var tunFd: ParcelFileDescriptor? = null
    private var xray: XrayCore? = null
    private var tun2Socks: Tun2Socks? = null
    private val tunnelActive = AtomicBoolean(false)
    private val connectionMutex = Mutex()
    private var watchdogJob: Job? = null

    private var lastConfig: String? = null
    private var lastServerName: String = ""
    private var lastServerId: Long = 0
    private var lastMtu: Int = 1420
    private var socksPort: Int = SOCKS_PORT_DEFAULT
    private var perAppMode: PerAppMode = PerAppMode.OFF
    private var perAppPackages: Set<String> = emptySet()
    private var failoverAttempts = 0
    private var healthJob: Job? = null
    private var lastUnderlying: Network? = null

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }

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
                startHealthCheck("Сеть сменилась, а сервер не отвечает", initialDelayMs = 6_000)
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
        tun2Socks = Tun2Socks(filesDir)
        VpnNotificationHelper.ensureChannel(this)
        runCatching { connectivity?.registerDefaultNetworkCallback(underlyingCallback) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val config = intent.getStringExtra(EXTRA_CONFIG).orEmpty()
                val name = intent.getStringExtra(EXTRA_SERVER_NAME).orEmpty()
                // Foreground must be entered synchronously or Android kills the start.
                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID,
                    buildNotification(name, connecting = true)
                )
                val sameConfig = config == lastConfig
                if ((_state.value == VpnConnectionState.CONNECTING ||
                        _state.value == VpnConnectionState.CONNECTED) && sameConfig
                ) {
                    Log.d(TAG, "Ignoring duplicate connect while state=${_state.value}")
                } else if (_state.value == VpnConnectionState.CONNECTED && !sameConfig) {
                    // Switching servers must actually move the traffic, not just relabel it.
                    Log.i(TAG, "Reconnecting onto a new config")
                    lastConfig = config
                    lastServerName = name
                    lastServerId = intent.getLongExtra(EXTRA_SERVER_ID, 0)
                    lastMtu = intent.getIntExtra(EXTRA_MTU, 1420)
                    socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, SOCKS_PORT_DEFAULT)
                    perAppMode = runCatching {
                        PerAppMode.valueOf(intent.getStringExtra(EXTRA_PER_APP_MODE).orEmpty())
                    }.getOrDefault(PerAppMode.OFF)
                    perAppPackages =
                        intent.getStringArrayExtra(EXTRA_PER_APP_PACKAGES)?.toSet().orEmpty()
                    failoverAttempts = 0
                    _state.value = VpnConnectionState.CONNECTING
                    _serverName.value = name
                    _serverId.value = lastServerId
                    _error.value = null
                    scope.launch { connectionMutex.withLock { doConnect(config, name, lastMtu) } }
                } else {
                    lastConfig = config
                    lastServerName = name
                    lastServerId = intent.getLongExtra(EXTRA_SERVER_ID, 0)
                    lastMtu = intent.getIntExtra(EXTRA_MTU, 1420)
                    socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, SOCKS_PORT_DEFAULT)
                    perAppMode = runCatching {
                        PerAppMode.valueOf(intent.getStringExtra(EXTRA_PER_APP_MODE).orEmpty())
                    }.getOrDefault(PerAppMode.OFF)
                    perAppPackages =
                        intent.getStringArrayExtra(EXTRA_PER_APP_PACKAGES)?.toSet().orEmpty()
                    failoverAttempts = 0
                    _state.value = VpnConnectionState.CONNECTING
                    _serverName.value = name
                    _serverId.value = lastServerId
                    _error.value = null
                    scope.launch { connectionMutex.withLock { doConnect(config, name, lastMtu) } }
                }
            }

            ACTION_DISCONNECT -> scope.launch { connectionMutex.withLock { doDisconnect() } }

            ACTION_TOGGLE -> when (_state.value) {
                VpnConnectionState.CONNECTED, VpnConnectionState.CONNECTING ->
                    scope.launch { connectionMutex.withLock { doDisconnect() } }

                VpnConnectionState.DISCONNECTING -> Unit
                VpnConnectionState.DISCONNECTED, VpnConnectionState.ERROR -> {
                    val cfg = lastConfig
                    if (cfg == null) {
                        // A fresh service knows no config; connect in the saved mode. Also
                        // keeps the startForegroundService() promise, which stopSelf() broke.
                        startOnItsOwn()
                    } else {
                        startForeground(
                            VpnNotificationHelper.NOTIFICATION_ID,
                            buildNotification(lastServerName, connecting = true)
                        )
                        _state.value = VpnConnectionState.CONNECTING
                        _error.value = null
                        scope.launch {
                            connectionMutex.withLock { doConnect(cfg, lastServerName, lastMtu) }
                        }
                    }
                }
            }

            ACTION_AUTO_CONNECT -> startOnItsOwn(intent.getStringExtra(EXTRA_MODE))

            // Always-on VPN (android.net.VpnService) or a sticky restart (null intent).
            SERVICE_INTERFACE, null -> startOnItsOwn()

            else -> if (tunnelActive.get()) {
                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID,
                    buildNotification(lastServerName, connecting = false)
                )
            }
        }
        // Sticky while a tunnel is wanted: if Android kills the process, it brings the
        // service back and startOnItsOwn() reconnects in the same mode.
        return if (tunnelActive.get() || _state.value == VpnConnectionState.CONNECTING) {
            START_STICKY
        } else {
            START_NOT_STICKY
        }
    }

    private fun startOnItsOwn(modeName: String? = null) {
        if (tunnelActive.get() || _state.value == VpnConnectionState.CONNECTING) {
            startForeground(
                VpnNotificationHelper.NOTIFICATION_ID,
                buildNotification(lastServerName, connecting = !tunnelActive.get())
            )
            return
        }
        startForeground(
            VpnNotificationHelper.NOTIFICATION_ID,
            buildNotification(lastServerName, connecting = true)
        )
        _state.value = VpnConnectionState.CONNECTING
        _error.value = null
        // The provider's connect may carry the very config this instance last used;
        // it must not be dropped as a duplicate of a connect that is not running.
        lastConfig = null
        val provider = autoStartProvider
        scope.launch {
            if (provider == null) {
                fail("Не удалось запустить VPN автоматически")
                return@launch
            }
            // The provider ends in connect(), which arrives here as ACTION_CONNECT.
            runCatching { provider(modeName) }.onFailure { e ->
                Log.w(TAG, "automatic start failed", e)
                val message = e.message ?: "Не удалось подключиться"
                // Started from a tile or at boot, there is no screen to show the error on.
                // Posted to the main looper: it must outlive this service's scope.
                val app = applicationContext
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(app, "Volta: $message", Toast.LENGTH_LONG).show()
                }
                connectionMutex.withLock { fail(message) }
            }
        }
    }

    /**
     * A fresh tunnel says nothing about whether the server answers: the core starts
     * fine against a dead node. Check right away (Happ/Hiddify do the same) and move
     * on to the next node within seconds, not after the one-minute watchdog.
     */
    private fun startHealthCheck(reason: String, initialDelayMs: Long = 2_500) {
        healthJob?.cancel()
        healthJob = scope.launch {
            delay(initialDelayMs)
            repeat(HEALTH_ATTEMPTS) { attempt ->
                if (!tunnelActive.get()) return@launch
                // No network at all is not the server's fault. The tunnel stays up and
                // the check runs again when a network comes back (underlyingCallback).
                if (!hasUnderlyingNetwork()) return@launch
                if (probeThroughTunnel()) {
                    failoverAttempts = 0
                    val id = lastServerId
                    healthyListener?.let { listener -> runCatching { listener(id) } }
                    return@launch
                }
                Log.w(TAG, "health check ${attempt + 1}/$HEALTH_ATTEMPTS failed")
                delay(2_500)
            }
            // Separate launch: recovery restarts the tunnel, which cancels this job.
            if (tunnelActive.get()) scope.launch { onTunnelBroken(reason) }
        }
    }

    private suspend fun doConnect(config: String, name: String, mtu: Int) {
        if (config.isBlank()) {
            fail("Пустой конфиг")
            return
        }
        _state.value = VpnConnectionState.CONNECTING
        _serverName.value = name
        _error.value = null

        try {
            cleanup()

            // The core comes up first. Raising the TUN before it would black-hole every
            // packet on the device while geodata is unpacked and the core boots.
            val core = XrayCore(this) { onCoreStoppedItself() }.also { xray = it }
            core.start(config, tunFd = 0).getOrThrow()

            val useIpv6 = hasGlobalIpv6()
            val builder = Builder()
                .setSession("Volta · $name")
                .setMtu(mtu.coerceIn(1280, 9000))
                .addAddress("10.10.14.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .setBlocking(false)

            applyPerAppRules(builder)

            // Claiming ::/0 on an IPv4-only network makes apps prefer a route that
            // cannot carry traffic, which reads as "VPN connected but nothing loads".
            if (useIpv6) {
                builder.addAddress("fd00:10:10::1", 126)
                builder.addRoute("::", 0)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }

            val fd = builder.establish() ?: throw IllegalStateException(
                "Не удалось создать VPN-интерфейс. Отзовите разрешение другого VPN и повторите."
            )
            tunFd = fd

            val bridge = tun2Socks ?: Tun2Socks(filesDir).also { tun2Socks = it }
            bridge.start(fd.fd, mtu, socksPort).getOrThrow()
            check(bridge.isRunning()) { "TUN-мост остановился при запуске" }

            tunnelActive.set(true)
            _state.value = VpnConnectionState.CONNECTED
            _connectedSince.value = System.currentTimeMillis()
            _traffic.value = TrafficStats()
            startForeground(
                VpnNotificationHelper.NOTIFICATION_ID,
                buildNotification(name, connecting = false)
            )

            watchdogJob?.cancel()
            watchdogJob = scope.launch { watchdog() }
            startHealthCheck("Сервер не отвечает")
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            fail(e.message ?: "Не удалось подключиться")
        }
    }

    /**
     * One low-cost loop: refreshes traffic counters, notices a dead component and,
     * once a minute, checks that bytes actually flow through the tunnel.
     */
    private suspend fun watchdog() {
        var uplinkTotal = 0L
        var downlinkTotal = 0L
        var lastProbe = System.currentTimeMillis()
        var probeFailures = 0
        var notificationTick = 0

        while (tunnelActive.get()) {
            delay(2_000)
            if (!tunnelActive.get()) break

            if (xray?.isRunning() != true || tun2Socks?.isRunning() != true) {
                Log.w(TAG, "component died — core=${xray?.isRunning()} tun=${tun2Socks?.isRunning()}")
                // Launched separately: recovery restarts the tunnel, which cancels this job.
                scope.launch { onTunnelBroken("Соединение прервалось") }
                return
            }

            // Xray resets a counter when it is read, so these are deltas.
            val upDelta = xray?.queryStats(ConfigBuilder.PROXY_TAG, "uplink") ?: 0
            val downDelta = xray?.queryStats(ConfigBuilder.PROXY_TAG, "downlink") ?: 0
            uplinkTotal += upDelta
            downlinkTotal += downDelta
            _traffic.value = TrafficStats(
                uplinkBytes = uplinkTotal,
                downlinkBytes = downlinkTotal,
                uplinkSpeed = upDelta / 2,
                downlinkSpeed = downDelta / 2
            )

            if (++notificationTick >= 5) {
                notificationTick = 0
                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID,
                    buildNotification(lastServerName, connecting = false)
                )
            }

            val now = System.currentTimeMillis()
            if (now - lastProbe >= 60_000) {
                lastProbe = now
                // Traffic in the last minute already proves the tunnel works.
                if (downDelta > 0 || !hasUnderlyingNetwork()) {
                    probeFailures = 0
                } else if (!probeThroughTunnel()) {
                    probeFailures++
                    Log.w(TAG, "tunnel probe failed ($probeFailures)")
                    if (probeFailures >= 2) {
                        scope.launch { onTunnelBroken("Сервер перестал отвечать") }
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
        scope.launch { onTunnelBroken("Ядро VPN остановилось") }
    }

    /** Try the next best node before giving up, so a dead exit is not a dead app. */
    private suspend fun onTunnelBroken(reason: String) = connectionMutex.withLock {
        if (!tunnelActive.get()) return@withLock
        val provider = failoverProvider
        if (provider != null && failoverAttempts < MAX_FAILOVERS) {
            failoverAttempts++
            // Short backoff so a flapping network is not hammered with reconnects.
            delay(700L * failoverAttempts)
            _state.value = VpnConnectionState.CONNECTING
            startForeground(
                VpnNotificationHelper.NOTIFICATION_ID,
                buildNotification(lastServerName, connecting = true)
            )
            val plan = runCatching { provider(lastServerId) }.getOrNull()
            if (plan != null) {
                Log.i(TAG, "failover #$failoverAttempts -> ${plan.serverName}")
                lastConfig = plan.configJson
                lastServerName = plan.serverName
                lastServerId = plan.serverId
                lastMtu = plan.mtu
                socksPort = plan.socksPort
                perAppMode = plan.perAppMode
                perAppPackages = plan.perAppPackages
                _serverName.value = plan.serverName
                _serverId.value = plan.serverId
                doConnect(plan.configJson, plan.serverName, plan.mtu)
                return@withLock
            }
        }
        fail(reason)
    }

    private fun fail(message: String) {
        _error.value = message
        _state.value = VpnConnectionState.ERROR
        cleanup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun doDisconnect() {
        _state.value = VpnConnectionState.DISCONNECTING
        cleanup()
        _state.value = VpnConnectionState.DISCONNECTED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanup() {
        watchdogJob?.cancel()
        watchdogJob = null
        healthJob?.cancel()
        healthJob = null
        tunnelActive.set(false)
        _connectedSince.value = 0
        _traffic.value = TrafficStats()
        runCatching { tun2Socks?.stop() }
        runCatching { xray?.stop() }
        xray = null
        runCatching { tunFd?.close() }
        tunFd = null
    }

    private fun buildNotification(serverName: String, connecting: Boolean): Notification =
        VpnNotificationHelper.build(
            context = this,
            serverName = serverName,
            connecting = connecting,
            connected = !connecting && tunnelActive.get(),
            traffic = _traffic.value
        )

    override fun onDestroy() {
        runCatching { connectivity?.unregisterNetworkCallback(underlyingCallback) }
        cleanup()
        scope.cancel()
        if (_state.value != VpnConnectionState.DISCONNECTED &&
            _state.value != VpnConnectionState.ERROR
        ) {
            _state.value = VpnConnectionState.DISCONNECTED
        }
        super.onDestroy()
    }

    override fun onRevoke() {
        // Another VPN took over. Tear down under the same lock a connect would hold.
        scope.launch { connectionMutex.withLock { doDisconnect() } }
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
    private fun applyPerAppRules(builder: Builder) {
        val packages = perAppPackages.filter { it.isNotBlank() && it != packageName }
        when {
            perAppMode == PerAppMode.INCLUDE && packages.isNotEmpty() ->
                packages.forEach { pkg ->
                    runCatching { builder.addAllowedApplication(pkg) }
                        .onFailure { Log.w(TAG, "allowed app missing: $pkg") }
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
