package com.ars3nb.volta.core.ping

import android.content.Context
import com.ars3nb.volta.core.vpn.CertPins
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.core.vpn.XrayCore
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SiteProbeResult
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Measures how good a node is. Battery-friendly: short timeouts, bounded parallelism,
 * no persistent connections.
 *
 * [PingType.REAL_DELAY] asks the core for an actual round trip through the proxy via
 * `Libv2ray.measureOutboundDelay`, which spins a private throw-away instance — unlike
 * the old approach it never touches the running tunnel, so it works while connected.
 */
class PingEngine(
    private val context: Context,
    private val batterySaver: Boolean = true
) {
    companion object {
        const val DELAY_TEST_URL = "https://www.gstatic.com/generate_204"

        /** A finalist slower than this to answer is no good to connect to anyway. */
        const val QUICK_TIMEOUT_MS = 4_500L

        /** In a whole-list sweep a node that needs longer than this is no use anyway. */
        const val SWEEP_TIMEOUT_MS = 4_000L

        /**
         * TCP handshakes cost next to nothing, so a sweep opens many at once; real-delay
         * runs each spin up a small core, so fewer of those overlap.
         */
        const val TCP_PARALLEL = 32
        const val HEAVY_PARALLEL = 8

        /** How long the connect-time race waits for any node to answer at all. */
        const val RACE_DEADLINE_MS = 3_000L

        /** UDP nodes measured at connect time on top of the TCP finalists. */
        private const val UDP_FINALISTS = 2

        /** The background check is patient: a slow mobile network is not a dead server. */
        const val GUARD_TIMEOUT_MS = 7_000L
        private const val GUARD_RETRY_PAUSE_MS = 1_500L

        /** Home of native measurements that outlive their caller's timeout. */
        private val detached = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * QUIC/UDP nodes (Hysteria2, WireGuard, mKCP): a TCP handshake or an HTTP request
         * says nothing about them, so they skip the TCP sweep and go straight to a real
         * round trip instead of being written off before they were measured.
         */
        fun speaksUdp(server: ServerEntity): Boolean =
            server.protocol == ProtocolType.HYSTERIA2 ||
                server.protocol == ProtocolType.WIREGUARD ||
                server.network.lowercase() in setOf("hysteria", "kcp", "mkcp")

        /** Nodes worth spending a real-delay measurement on: the TCP-reachable ones. */
        internal fun reachableIds(tcpResults: List<Pair<Long, PingResult>>): Set<Long> =
            tcpResults.asSequence()
                .filter { it.second.success }
                .map { it.first }
                .toSet()
    }

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(if (batterySaver) 2 else 4, TimeUnit.SECONDS)
            .readTimeout(if (batterySaver) 2 else 4, TimeUnit.SECONDS)
            .writeTimeout(2, TimeUnit.SECONDS)
            .callTimeout(if (batterySaver) 3 else 5, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val connectTimeoutMs = if (batterySaver) 2000 else 4000
    private val httpCallTimeoutMs = if (batterySaver) 3000L else 5000L

    suspend fun pingServer(server: ServerEntity, type: PingType = PingType.TCP): PingResult =
        if (speaksUdp(server) && (type == PingType.TCP || type == PingType.HTTP_GET)) {
            realDelay(server).copy(type = type)
        } else when (type) {
            PingType.TCP -> tcpPing(server.address, server.port)
            PingType.ICMP -> icmpPing(server.address)
            PingType.HTTP_GET -> httpGetPing(server)
            PingType.REAL_DELAY -> realDelay(server)
        }

    /**
     * Plain HTTP GET at the node itself. Unlike a TCP handshake this proves something is
     * actually serving on the port, which is what distinguishes a live CDN-fronted node
     * from an address that merely accepts connections.
     */
    private suspend fun httpGetPing(server: ServerEntity): PingResult = withContext(Dispatchers.IO) {
        if (server.address.isBlank()) {
            return@withContext PingResult(server.remark, PingType.HTTP_GET, -1, false, "Пустой адрес")
        }
        val authority = if (server.address.contains(':') && !server.address.startsWith("[")) {
            "[${server.address}]:${server.port}"
        } else {
            "${server.address}:${server.port}"
        }
        for (url in listOf("https://$authority/", "http://$authority/")) {
            val result = httpPing(url)
            if (result.success) {
                return@withContext PingResult(
                    target = server.remark,
                    type = PingType.HTTP_GET,
                    latencyMs = result.latencyMs,
                    success = true,
                    message = result.message
                )
            }
        }
        PingResult(server.remark, PingType.HTTP_GET, -1, false, "Сервер не ответил по HTTP")
    }

    suspend fun tcpPing(host: String, port: Int): PingResult = withContext(Dispatchers.IO) {
        if (host.isBlank()) {
            return@withContext PingResult(host, PingType.TCP, -1, false, "Пустой адрес")
        }
        val start = System.nanoTime()
        val socket = Socket()
        // A blocked connect() ignores cancellation; closing the socket ends it, so a
        // cancelled sweep lets go at once instead of sitting out every timeout.
        val closer = launch {
            try {
                delay(Long.MAX_VALUE)
            } finally {
                runCatching { socket.close() }
            }
        }
        try {
            socket.use {
                it.tcpNoDelay = true
                it.connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            PingResult(host, PingType.TCP, (System.nanoTime() - start) / 1_000_000, true)
        } catch (e: Exception) {
            PingResult(host, PingType.TCP, -1, false, e.message.orEmpty())
        } finally {
            closer.cancel()
        }
    }

    /**
     * Connect-time race: TCP handshakes to every node at once, and the first to answer
     * wins. That is usually also the nearest one. Null when none answers in time.
     */
    suspend fun firstToAnswer(
        servers: List<ServerEntity>,
        deadlineMs: Long = RACE_DEADLINE_MS
    ): ServerEntity? {
        val candidates = servers.filterNot(::speaksUdp)
        if (candidates.isEmpty()) return null
        val race = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val winner = CompletableDeferred<ServerEntity?>()
        val gate = Semaphore(TCP_PARALLEL)
        val runners = candidates.map { server ->
            race.launch {
                gate.withPermit {
                    if (!winner.isCompleted && tcpPing(server.address, server.port).success) {
                        winner.complete(server)
                    }
                }
            }
        }
        race.launch {
            runners.joinAll()
            winner.complete(null)
        }
        return try {
            withTimeoutOrNull(deadlineMs) { winner.await() }
        } finally {
            race.cancel()
        }
    }

    /** Actual latency through the node, measured by the core itself. */
    private suspend fun realDelay(
        server: ServerEntity,
        timeoutMs: Long = (if (batterySaver) 6 else 10) * 1000L
    ): PingResult = withContext(Dispatchers.IO) {
        val type = PingType.REAL_DELAY
        val problem = ConfigBuilder.dialProblem(server)
        if (problem != null) {
            return@withContext PingResult(server.remark, type, -1, false, problem)
        }
        if (server.protocol == ProtocolType.OPENFLUX) {
            // OpenFlux only runs while connected through it; from outside, whether its
            // carrier (the exit's port, or the document's host) answers is what shows.
            return@withContext tcpPing(server.address, server.port).copy(type = type)
        }
        if (!XrayCore.isCoreAvailable) {
            // No native core in this build: fall back to something honest.
            return@withContext tcpPing(server.address, server.port).copy(type = type)
        }
        val config = runCatching { ConfigBuilder.buildProbe(server) }.getOrNull()
            ?: return@withContext PingResult(server.remark, type, -1, false, "Конфиг не собран")
        // The native call blocks and ignores cancellation, so it runs detached: the
        // timeout then really ends the wait instead of sitting out the core's own
        // 10+ s deadline on a dead node.
        val measuring = detached.async {
            // A certificate pin costs a handshake, so it is made here, under the deadline.
            val pinned = CertPins.apply(server)
            val probe = if (pinned === server) config else {
                runCatching { ConfigBuilder.buildProbe(pinned) }.getOrDefault(config)
            }
            XrayCore.measureOutboundDelay(context, probe, DELAY_TEST_URL)
        }
        val ms = withTimeoutOrNull(timeoutMs) { measuring.await() } ?: -1L
        PingResult(
            target = server.remark,
            type = type,
            latencyMs = ms,
            success = ms > 0,
            message = if (ms > 0) "" else "Узел не ответил"
        )
    }

    /**
     * Does [server] still carry traffic? A real round trip through it, made by this app,
     * which is outside its own tunnel: the answer is about the server itself, not about
     * the tunnel running on it. One more try before saying no, so a single lost packet
     * is not taken for a dead server.
     */
    suspend fun stillAnswers(server: ServerEntity, attempts: Int = 2): PingResult {
        var result = realDelay(server, GUARD_TIMEOUT_MS)
        repeat(attempts - 1) {
            if (result.success) return result
            delay(GUARD_RETRY_PAUSE_MS)
            result = realDelay(server, GUARD_TIMEOUT_MS)
        }
        return result
    }

    suspend fun httpPing(url: String): PingResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Volta/1.0")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val ms = (System.nanoTime() - start) / 1_000_000
                // Any response (even 4xx/5xx/redirect) means the host is reachable.
                val ok = resp.code in 100..599
                PingResult(url, PingType.TCP, if (ok) ms else -1, ok, "HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            PingResult(url, PingType.TCP, -1, false, e.message.orEmpty())
        }
    }

    private suspend fun icmpPing(host: String): PingResult = withContext(Dispatchers.IO) {
        val timeoutSeconds = if (batterySaver) 2 else 4
        val start = System.nanoTime()
        try {
            val process = ProcessBuilder(
                "/system/bin/ping", "-c", "1", "-W", timeoutSeconds.toString(), host
            ).redirectErrorStream(true).start()
            val finished = process.waitFor((timeoutSeconds + 1).toLong(), TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val reportedMs = Regex("time[=<]([0-9.]+)\\s*ms")
                .find(output)?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val success = finished && process.exitValue() == 0
            PingResult(
                host,
                PingType.ICMP,
                if (success) reportedMs ?: elapsedMs else -1,
                success,
                if (success) "ICMP" else output.lineSequence().firstOrNull().orEmpty()
            )
        } catch (e: Exception) {
            PingResult(host, PingType.ICMP, -1, false, e.message.orEmpty())
        }
    }

    /**
     * Network regime probe. Delegates to [NetworkProbe], which runs the two-stage
     * ladder (foreign 204 endpoints, then Russian hosts over DNS/TCP/HEAD).
     */
    suspend fun probeSites(): List<SiteProbeResult> = NetworkProbe(batterySaver).run().probes

    /**
     * Connect-time measuring: a wide TCP sweep, then a real round trip through only the
     * [finalists] nearest nodes, all at once and with a short deadline. Returns results
     * only for nodes it actually judged — the TCP-dead and the finalists — so nodes it
     * skipped are not marked as failed.
     */
    suspend fun pingServersQuick(
        servers: List<ServerEntity>,
        type: PingType,
        finalists: Int = 4
    ): List<Pair<Long, PingResult>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()
        val (udp, tcpBased) = servers.partition(::speaksUdp)
        // UDP nodes have no TCP latency to rank by: the few with the best record so far.
        val udpPicks = udp.sortedBy { it.score }.take(UDP_FINALISTS)
        val tcp = pingServers(tcpBased, PingType.TCP, maxParallel = TCP_PARALLEL)
        if (type == PingType.TCP) {
            return@coroutineScope tcp + udpPicks.map { server ->
                async { server.id to realDelay(server, QUICK_TIMEOUT_MS) }
            }.awaitAll()
        }

        val dead = tcp.filter { !it.second.success }
        val nearest = tcp.filter { it.second.success }
            .sortedBy { it.second.latencyMs }
            .take(finalists)
            .mapNotNull { (id, _) -> servers.firstOrNull { it.id == id } }
        val measured = (nearest + udpPicks).map { server ->
            async {
                server.id to if (type == PingType.REAL_DELAY) {
                    realDelay(server, QUICK_TIMEOUT_MS)
                } else {
                    pingServer(server, type)
                }
            }
        }.awaitAll()
        dead + measured
    }

    /**
     * [rounds] more samples for each of a few finalists, one after another per node so
     * the samples are spread in time, nodes in parallel.
     */
    suspend fun resample(
        servers: List<ServerEntity>,
        type: PingType,
        rounds: Int
    ): Map<Long, List<PingResult>> = coroutineScope {
        val gate = Semaphore(if (batterySaver) 4 else 6)
        servers.map { server ->
            async {
                gate.withPermit {
                    val results = ArrayList<PingResult>(rounds)
                    repeat(rounds) { results += pingServer(server, type) }
                    server.id to results.toList()
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * Measures a whole list. Real-delay runs are expensive, so a cheap TCP sweep
     * filters out dead addresses first and only the reachable ones are timed properly.
     */
    suspend fun pingServers(
        servers: List<ServerEntity>,
        type: PingType,
        maxParallel: Int = TCP_PARALLEL,
        heavyParallel: Int = if (batterySaver) HEAVY_PARALLEL - 2 else HEAVY_PARALLEL,
        realDelayTimeoutMs: Long? = null,
        /** Called as each node is judged, so a long list fills in as it goes. */
        onResult: (suspend (Long, PingResult) -> Unit)? = null
    ): List<Pair<Long, PingResult>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()

        if (type == PingType.REAL_DELAY) {
            val tcp = pingServers(servers.filterNot(::speaksUdp), PingType.TCP, maxParallel)
            val reachable = reachableIds(tcp) + servers.filter(::speaksUdp).map { it.id }
            val unreachable = servers.filter { it.id !in reachable }.map { server ->
                server.id to PingResult(
                    target = server.remark,
                    type = type,
                    latencyMs = -1,
                    success = false,
                    message = "Адрес недоступен"
                )
            }
            unreachable.forEach { (id, result) -> onResult?.invoke(id, result) }
            val heavyGate = Semaphore(heavyParallel)
            val measured = servers.filter { it.id in reachable }
                .map { server ->
                    async {
                        heavyGate.withPermit {
                            val result = if (realDelayTimeoutMs != null) {
                                realDelay(server, realDelayTimeoutMs)
                            } else {
                                pingServer(server, type)
                            }
                            onResult?.invoke(server.id, result)
                            server.id to result
                        }
                    }
                }
                .awaitAll()
                .toMap()
            val failed = unreachable.toMap()
            return@coroutineScope servers.mapNotNull { server ->
                (measured[server.id] ?: failed[server.id])?.let { server.id to it }
            }
        }

        val gate = Semaphore(maxParallel)
        servers.map { server ->
            async {
                gate.withPermit {
                    val result = pingServer(server, type)
                    onResult?.invoke(server.id, result)
                    server.id to result
                }
            }
        }.awaitAll()
    }
}
