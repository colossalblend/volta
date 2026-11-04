package com.ars3nb.volta.core.ping

import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.desktop.core.DelayTester
import com.ars3nb.volta.desktop.i18n.I18n
import java.net.InetSocketAddress
import java.net.Socket
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

/**
 * Measures how good a node is: the Android engine's interface over the Windows core.
 *
 * [PingType.REAL_DELAY] is a real round trip through the node, made by a throw-away Xray
 * ([DelayTester]) that never touches the running tunnel. [PingType.TCP] is a plain
 * handshake to the node's address. The other Android types fall back to these two.
 */
class PingEngine(
    private val tester: DelayTester?,
    private val batterySaver: Boolean = false
) {
    companion object {
        const val DELAY_TEST_URL = DelayTester.DEFAULT_URL
        const val QUICK_TIMEOUT_MS = 4_500L
        const val SWEEP_TIMEOUT_MS = 4_500L
        const val TCP_PARALLEL = 48
        const val HEAVY_PARALLEL = 8
        const val RACE_DEADLINE_MS = 3_000L
        private const val UDP_FINALISTS = 2

        /** QUIC/UDP nodes: a TCP handshake says nothing about them, so they get a real round trip. */
        fun speaksUdp(server: ServerEntity): Boolean =
            server.protocol == ProtocolType.HYSTERIA2 ||
                server.protocol == ProtocolType.HYSTERIA ||
                server.protocol == ProtocolType.WIREGUARD ||
                server.protocol == ProtocolType.AMNEZIAWG ||
                server.network.lowercase() in setOf("hysteria", "kcp", "mkcp")

        /**
         * OpenFlux nodes have a check of their own (see [openFluxDelay]), as on Android:
         * they skip the TCP sweep and are always judged.
         */
        fun judgedByCarriers(server: ServerEntity): Boolean = server.protocol == ProtocolType.OPENFLUX

        /** An OpenFlux node judged from outside counts this many of its carrier's handshakes. */
        const val OPENFLUX_ROUND_TRIPS = 3

        internal fun reachableIds(tcpResults: List<Pair<Long, PingResult>>): Set<Long> =
            tcpResults.asSequence().filter { it.second.success }.map { it.first }.toSet()
    }

    private val connectTimeoutMs = if (batterySaver) 2000 else 3000

    suspend fun pingServer(server: ServerEntity, type: PingType = PingType.TCP): PingResult =
        when {
            type == PingType.REAL_DELAY || speaksUdp(server) || judgedByCarriers(server) -> realDelay(listOf(server))[server.id]
                ?: failed(server, PingType.REAL_DELAY)
            else -> tcpPing(server.address, server.port).copy(target = server.remark)
        }

    suspend fun tcpPing(host: String, port: Int): PingResult = withContext(Dispatchers.IO) {
        if (host.isBlank()) return@withContext PingResult(host, PingType.TCP, -1, false, "")
        val start = System.nanoTime()
        val socket = Socket()
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
            PingResult(host, PingType.TCP, ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1), true)
        } catch (e: Exception) {
            PingResult(host, PingType.TCP, -1, false, e.message.orEmpty())
        } finally {
            closer.cancel()
        }
    }

    /** First node to answer a TCP handshake wins; null when none does in time. */
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

    /** Real round trips for many servers at once, one sample each. */
    suspend fun realDelay(
        servers: List<ServerEntity>,
        timeoutMs: Long = SWEEP_TIMEOUT_MS,
        onResult: (suspend (Long, PingResult) -> Unit)? = null
    ): Map<Long, PingResult> {
        if (servers.isEmpty()) return emptyMap()
        val byId = servers.associateBy { it.id }
        val samples = sampleMany(servers, timeoutMs, 1) { id, ms ->
            onResult?.invoke(id, result(byId.getValue(id), ms))
        }
        return servers.associate { s -> s.id to result(s, samples[s.id]?.firstOrNull() ?: -1) }
    }

    private suspend fun sampleMany(
        servers: List<ServerEntity>,
        timeoutMs: Long,
        rounds: Int,
        onFirst: (suspend (Long, Long) -> Unit)? = null
    ): Map<Long, List<Long>> = coroutineScope {
        // As on Android: AmneziaWG and Hysteria v1 are timed by the voltaproxy helper,
        // OpenFlux by its own check, the rest by a throw-away core.
        val (helperNodes, others) = servers.partition { ConfigBuilder.helperSpec(it) != null }
        val (fluxNodes, coreNodes) = others.partition(::judgedByCarriers)
        val helper = helperNodes.map { s ->
            async {
                val spec = ConfigBuilder.helperSpec(s)!!
                val list = List(rounds) {
                    if (ConfigBuilder.dialProblem(s) != null) -1L
                    else ProxyHelper.probe(spec, DELAY_TEST_URL, timeoutMs)
                }
                onFirst?.invoke(s.id, list.first())
                s.id to list
            }
        }
        val flux = fluxNodes.map { s ->
            async {
                val r = openFluxDelay(s, timeoutMs)
                val list = List(rounds) { r.latencyMs }
                onFirst?.invoke(s.id, list.first())
                s.id to list
            }
        }
        val t = tester
        val core: Map<Long, List<Long>> = when {
            coreNodes.isEmpty() -> emptyMap()
            t == null -> coreNodes.map { s ->
                // No core to measure with: the honest fallback is a TCP handshake.
                async {
                    val list = List(rounds) { tcpPing(s.address, s.port).latencyMs }
                    onFirst?.invoke(s.id, list.first())
                    s.id to list
                }
            }.awaitAll().toMap()

            else -> t.measure(coreNodes, timeoutMs, rounds, onResult = onFirst)
        }
        core + helper.awaitAll().toMap() + flux.awaitAll().toMap()
    }

    /**
     * An OpenFlux node, as on Android: through the helper when it runs on this very link
     * (a real round trip through the exit), else judged from outside: all its carriers
     * are knocked on at once and the best one that answers gives the figure, three of its
     * handshakes, never less than the node's smoothed figure.
     */
    private suspend fun openFluxDelay(server: ServerEntity, timeoutMs: Long): PingResult {
        val type = PingType.REAL_DELAY
        if (!OpenFluxRunner.available) {
            return PingResult(server.remark, type, -1, false, Res.string(R.string.servers_openflux_not_in_build))
        }
        if (OpenFluxRunner.runs(server.rawLink)) {
            val ms = withContext(Dispatchers.IO) { throughOpenFlux(maxOf(timeoutMs, 10_000L)) }
            return PingResult(
                server.remark, type, ms, ms > 0,
                if (ms > 0) "" else Res.string(R.string.servers_ping_node_no_answer)
            )
        }
        val endpoints = OpenFluxLink.parse(server.rawLink)?.endpoints.orEmpty()
        if (endpoints.isEmpty()) {
            return PingResult(server.remark, type, -1, false, Res.string(R.string.servers_openflux_link_unreadable))
        }
        val knocks = coroutineScope { endpoints.map { e -> async { e to tcpPing(e.host, e.port) } }.awaitAll() }
        val (carrier, knock) = knocks.firstOrNull { it.second.success }
            ?: return PingResult(
                server.remark, type, -1, false, Res.string(R.string.servers_openflux_carriers_silent), estimate = true
            )
        val figure = maxOf(knock.latencyMs.coerceAtLeast(1) * OPENFLUX_ROUND_TRIPS, server.ewmaMs)
        return PingResult(server.remark, type, figure, true, OpenFluxLink.carrierName(carrier.type), estimate = true)
    }

    /** A page through the running OpenFlux helper's proxy: its exit's round trip, in ms. */
    private fun throughOpenFlux(timeoutMs: Long): Long {
        val start = System.nanoTime()
        val ok = runCatching {
            val c = URI(DELAY_TEST_URL).toURL()
                .openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", OpenFluxRunner.PORT))) as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs.toInt()
                c.readTimeout = timeoutMs.toInt()
                c.responseCode in 100..599
            } finally {
                c.disconnect()
            }
        }.getOrDefault(false)
        return if (ok) ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L) else -1L
    }

    private fun result(server: ServerEntity, ms: Long) = PingResult(
        target = server.remark,
        type = PingType.REAL_DELAY,
        latencyMs = if (ms > 0) ms else -1,
        success = ms > 0,
        message = if (ms > 0) "" else (ConfigBuilder.dialProblem(server) ?: I18n.s.errServerSilent)
    )

    private fun failed(server: ServerEntity, type: PingType) =
        PingResult(server.remark, type, -1, false, I18n.s.errServerSilent)

    /** Does the node still carry traffic? Two tries before saying no. */
    suspend fun stillAnswers(server: ServerEntity, attempts: Int = 2): PingResult {
        var r = realDelay(listOf(server), 7_000L)[server.id] ?: failed(server, PingType.REAL_DELAY)
        repeat(attempts - 1) {
            if (r.success) return r
            delay(1_500)
            r = realDelay(listOf(server), 7_000L)[server.id] ?: failed(server, PingType.REAL_DELAY)
        }
        return r
    }

    /**
     * Connect-time measuring: a wide TCP sweep, then real round trips through only the
     * nearest few. Returns results only for the nodes it judged.
     */
    suspend fun pingServersQuick(
        servers: List<ServerEntity>,
        type: PingType,
        finalists: Int = 4
    ): List<Pair<Long, PingResult>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()
        val (udp, tcpBased) = servers.partition { speaksUdp(it) || judgedByCarriers(it) }
        val udpPicks = udp.sortedBy { it.score }.take(UDP_FINALISTS)
        val tcp = pingServers(tcpBased, PingType.TCP)
        if (type == PingType.TCP) {
            return@coroutineScope tcp + realDelay(udpPicks, QUICK_TIMEOUT_MS).toList()
        }
        val dead = tcp.filter { !it.second.success }
        val nearest = tcp.filter { it.second.success }
            .sortedBy { it.second.latencyMs }
            .take(finalists)
            .mapNotNull { (id, _) -> servers.firstOrNull { it.id == id } }
        dead + realDelay(nearest + udpPicks, QUICK_TIMEOUT_MS).toList()
    }

    /** [rounds] more samples for each of a few finalists. */
    suspend fun resample(
        servers: List<ServerEntity>,
        type: PingType,
        rounds: Int
    ): Map<Long, List<PingResult>> {
        if (servers.isEmpty()) return emptyMap()
        if (type == PingType.TCP) {
            return coroutineScope {
                servers.map { s ->
                    async { s.id to List(rounds) { tcpPing(s.address, s.port) } }
                }.awaitAll().toMap()
            }
        }
        val byId = servers.associateBy { it.id }
        return sampleMany(servers, QUICK_TIMEOUT_MS, rounds)
            .mapValues { (id, list) -> list.map { result(byId.getValue(id), it) } }
    }

    /**
     * Measures a whole list. Real-delay runs cost a core, so a cheap TCP sweep filters out
     * dead addresses first and only the reachable ones are timed properly.
     */
    suspend fun pingServers(
        servers: List<ServerEntity>,
        type: PingType,
        maxParallel: Int = TCP_PARALLEL,
        @Suppress("UNUSED_PARAMETER") heavyParallel: Int = HEAVY_PARALLEL,
        realDelayTimeoutMs: Long? = null,
        onResult: (suspend (Long, PingResult) -> Unit)? = null
    ): List<Pair<Long, PingResult>> = coroutineScope {
        if (servers.isEmpty()) return@coroutineScope emptyList()

        if (type == PingType.REAL_DELAY) {
            val swept = servers.filterNot { speaksUdp(it) || judgedByCarriers(it) }
            val tcp = pingServers(swept, PingType.TCP, maxParallel)
            val reachable = reachableIds(tcp) + servers.filter { speaksUdp(it) || judgedByCarriers(it) }.map { it.id }
            val unreachable = servers.filter { it.id !in reachable }.map { server ->
                server.id to PingResult(server.remark, type, -1, false, I18n.s.errServerSilent)
            }
            unreachable.forEach { (id, result) -> onResult?.invoke(id, result) }
            val measured = realDelay(
                servers.filter { it.id in reachable },
                realDelayTimeoutMs ?: SWEEP_TIMEOUT_MS,
                onResult
            )
            val failed = unreachable.toMap()
            return@coroutineScope servers.mapNotNull { server ->
                (measured[server.id] ?: failed[server.id])?.let { server.id to it }
            }
        }

        val gate = Semaphore(maxParallel)
        servers.map { server ->
            async {
                gate.withPermit {
                    val result = tcpPing(server.address, server.port).copy(target = server.remark)
                    onResult?.invoke(server.id, result)
                    server.id to result
                }
            }
        }.awaitAll()
    }
}
