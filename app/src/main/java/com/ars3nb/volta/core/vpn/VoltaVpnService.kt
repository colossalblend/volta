package com.ars3nb.volta.core.vpn

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
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
        const val EXTRA_CONFIG = "config"
        const val EXTRA_SERVER_NAME = "server_name"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_SOCKS_PORT = "socks_port"
        const val EXTRA_PER_APP_MODE = "per_app_mode"
        const val EXTRA_PER_APP_PACKAGES = "per_app_packages"
        const val EXTRA_SERVER_ID = "server_id"

        private const val SOCKS_PORT_DEFAULT = 10808

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
                        stopSelf()
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

            else -> if (tunnelActive.get()) {
                startForeground(
                    VpnNotificationHelper.NOTIFICATION_ID,
                    buildNotification(lastServerName, connecting = false)
                )
            }
        }
        return START_NOT_STICKY
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
                if (downDelta > 0) {
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
                }
            }
        }
    }

    private suspend fun probeThroughTunnel(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val client = probeClient.newBuilder()
                .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
                .build()
            client.newCall(
                Request.Builder().url(PingEngineProbeUrl).head().build()
            ).execute().use { it.code in 100..599 }
        }.getOrDefault(false)
    }

    private fun onCoreStoppedItself() {
        if (!tunnelActive.get()) return
        scope.launch { onTunnelBroken("Ядро VPN остановилось") }
    }

    /** Try the next best node before giving up, so a dead exit is not a dead app. */
    private suspend fun onTunnelBroken(reason: String) = connectionMutex.withLock {
        if (!tunnelActive.get()) return@withLock
        val provider = failoverProvider
        if (provider != null && failoverAttempts < 3) {
            failoverAttempts++
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

    /** True when the underlying network actually has a routable IPv6 address. */
    private fun hasGlobalIpv6(): Boolean = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence().any { nif ->
            nif.isUp && !nif.isLoopback && nif.inetAddresses.asSequence().any { addr ->
                addr is java.net.Inet6Address &&
                    !addr.isLinkLocalAddress &&
                    !addr.isLoopbackAddress &&
                    !addr.isAnyLocalAddress
            }
        }
    }.getOrDefault(false)
}

private const val PingEngineProbeUrl = "https://www.gstatic.com/generate_204"
