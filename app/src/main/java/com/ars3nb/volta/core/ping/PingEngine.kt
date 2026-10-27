package com.ars3nb.volta.core.ping

import android.content.Context
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.core.vpn.XrayCore
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SiteProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

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
        when (type) {
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
        try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            }
            PingResult(host, PingType.TCP, (System.nanoTime() - start) / 1_000_000, true)
        } catch (e: Exception) {
            PingResult(host, PingType.TCP, -1, false, e.message.orEmpty())
        }
    }

    /** Actual latency through the node, measured by the core itself. */
    private suspend fun realDelay(server: ServerEntity): PingResult = withContext(Dispatchers.IO) {
        val type = PingType.REAL_DELAY
        val problem = ConfigBuilder.dialProblem(server)
        if (problem != null) {
            return@withContext PingResult(server.remark, type, -1, false, problem)
        }
        if (!XrayCore.isCoreAvailable) {
            // No native core in this build: fall back to something honest.
            return@withContext tcpPing(server.address, server.port).copy(type = type)
        }
        val config = runCatching { ConfigBuilder.buildProbe(server) }.getOrNull()
            ?: return@withContext PingResult(server.remark, type, -1, false, "Конфиг не собран")
        val ms = withTimeoutOrNull((if (batterySaver) 6 else 10) * 1000L) {
            XrayCore.measureOutboundDelay(context, config, DELAY_TEST_URL)
        } ?: -1L
        PingResult(
            target = server.remark,
            type = type,
            latencyMs = ms,
            success = ms > 0,
            message = if (ms > 0) "" else "Узел не ответил"
        )
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
     * [rounds] more samples for each of a few finalists, one after another per node so
     * the samples are spread in time, nodes in parallel.
     */
    suspend fun resample(
        servers: List<ServerEntity>,
        type: PingType,
        rounds: Int
    ): Map<Long, List<PingResult>> = coroutineScope {
        val gate = Semaphore(if (batterySaver) 2 else 4)
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
        maxParallel: Int = if (batterySaver) 4 else 8
    ): List<Pair<Long, PingResult>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()

        if (type == PingType.REAL_DELAY) {
            val tcp = pingServers(servers, PingType.TCP, maxParallel)
            val reachable = reachableIds(tcp)
            val heavyGate = Semaphore(if (batterySaver) 2 else 4)
            val measured = servers.filter { it.id in reachable }
                .map { server ->
                    async { heavyGate.withPermit { server.id to pingServer(server, type) } }
                }
                .awaitAll()
                .toMap()
            return@coroutineScope servers.map { server ->
                server.id to (
                    measured[server.id] ?: PingResult(
                        target = server.remark,
                        type = type,
                        latencyMs = -1,
                        success = false,
                        message = "Адрес недоступен"
                    )
                    )
            }
        }

        val gate = Semaphore(maxParallel)
        servers.map { server ->
            async { gate.withPermit { server.id to pingServer(server, type) } }
        }.awaitAll()
    }
}
