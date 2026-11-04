package com.ars3nb.volta.desktop.core

import com.ars3nb.volta.core.vpn.CertPins
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Real delay: a round trip through each server, made by a throw-away Xray of its own.
 *
 * One core serves a whole batch: an HTTP inbound per server, each routed to that server's
 * outbound, so fifty servers cost one process start instead of fifty. The live tunnel is
 * never touched, so this works while connected. A server whose settings the core refuses
 * would take the whole batch down with it; when a batch fails to start, its servers are
 * tried one by one so only the broken one comes out failed.
 */
class DelayTester(
    private val core: XrayCore,
    private val workDir: File
) {
    companion object {
        const val DEFAULT_URL = "https://www.gstatic.com/generate_204"
        private const val BATCH = 48
        private const val PARALLEL = 16
        private const val START_TIMEOUT_MS = 6_000L
    }

    /**
     * [rounds] samples per server, in ms (-1 = no answer). Servers the core can't dial at
     * all come back with -1 too.
     */
    suspend fun measure(
        servers: List<ServerEntity>,
        timeoutMs: Long,
        rounds: Int = 1,
        url: String = DEFAULT_URL,
        onResult: (suspend (Long, Long) -> Unit)? = null
    ): Map<Long, List<Long>> = withContext(Dispatchers.IO) {
        if (servers.isEmpty() || !core.isPresent) {
            return@withContext servers.associate { it.id to List(rounds) { -1L } }
        }
        // Pins cost a handshake each: made in parallel, before the core starts.
        val pinned = servers.map { s -> async { runCatching { CertPins.apply(s) }.getOrDefault(s) } }.awaitAll()
        val (dialable, broken) = pinned.partition { ConfigBuilder.canDial(it) }
        val out = HashMap<Long, List<Long>>()
        broken.forEach { out[it.id] = List(rounds) { -1L } }
        // Servers that bring chained outbounds of their own get a core each: their tags
        // could clash with another server's in a shared one.
        val (chained, plain) = dialable.partition { hasChain(it) }
        val batches = plain.chunked(BATCH) + chained.map { listOf(it) }
        for (batch in batches) {
            out.putAll(runBatch(batch, timeoutMs, rounds, url, onResult))
        }
        out
    }

    private suspend fun runBatch(
        batch: List<ServerEntity>,
        timeoutMs: Long,
        rounds: Int,
        url: String,
        onResult: (suspend (Long, Long) -> Unit)?
    ): Map<Long, List<Long>> {
        val started = startCore(batch)
        if (started == null) {
            if (batch.size == 1) {
                onResult?.invoke(batch[0].id, -1)
                return mapOf(batch[0].id to List(rounds) { -1L })
            }
            // One bad apple: find it by giving each server its own core.
            val out = HashMap<Long, List<Long>>()
            for (one in batch) out.putAll(runBatch(listOf(one), timeoutMs, rounds, url, onResult))
            return out
        }
        val (process, ports, file) = started
        try {
            val results = batch.associate { it.id to ArrayList<Long>(rounds) }
            val gate = Semaphore(PARALLEL)
            repeat(rounds) { round ->
                coroutineScope {
                    batch.mapIndexed { i, server ->
                        async(Dispatchers.IO) {
                            gate.withPermit {
                                val ms = timeOnce(ports[i], url, timeoutMs)
                                results.getValue(server.id).add(ms)
                                if (round == 0) onResult?.invoke(server.id, ms)
                            }
                        }
                    }.awaitAll()
                }
            }
            return results
        } finally {
            process.destroy()
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            file.delete()
        }
    }

    private data class Started(val process: Process, val ports: List<Int>, val file: File)

    private fun startCore(batch: List<ServerEntity>): Started? {
        val ports = Ports.pickMany(batch.size)
        val config = runCatching { probeConfig(batch, ports) }.getOrNull() ?: return null
        workDir.mkdirs()
        val file = File(workDir, "probe-${UUID.randomUUID()}.json")
        file.writeText(config)
        val log = File(workDir, "probe.log")
        if (log.length() > 512 * 1024) log.delete()
        val process = runCatching { core.start(file, log) }.getOrNull() ?: run { file.delete(); return null }
        if (!Ports.awaitListening(ports, process, START_TIMEOUT_MS)) {
            process.destroyForcibly()
            file.delete()
            return null
        }
        return Started(process, ports, file)
    }

    /** One local HTTP inbound per server, each routed to that server alone. */
    fun probeConfig(batch: List<ServerEntity>, ports: List<Int>): String {
        val root = JsonObject()
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        val inbounds = JsonArray()
        val outbounds = JsonArray()
        val rules = JsonArray()
        val extra = LinkedHashMap<String, JsonObject>()
        batch.forEachIndexed { i, server ->
            inbounds.add(JsonObject().apply {
                addProperty("tag", "in-$i")
                addProperty("listen", "127.0.0.1")
                addProperty("port", ports[i])
                addProperty("protocol", "http")
                add("settings", JsonObject())
            })
            outbounds.add(ConfigBuilder.outboundFor(server, "out-$i"))
            ConfigBuilder.chainedOutbounds(server, "out-$i").forEach { ob ->
                ob.get("tag")?.asString?.let { extra.putIfAbsent(it, ob) }
            }
            rules.add(JsonObject().apply {
                addProperty("type", "field")
                add("inboundTag", JsonArray().apply { add("in-$i") })
                addProperty("outboundTag", "out-$i")
            })
        }
        extra.values.forEach { outbounds.add(it) }
        outbounds.add(JsonObject().apply {
            addProperty("tag", "direct")
            addProperty("protocol", "freedom")
        })
        root.add("inbounds", inbounds)
        root.add("outbounds", outbounds)
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", rules)
        })
        return root.toString()
    }

    private fun hasChain(server: ServerEntity): Boolean =
        server.rawJson.isNotBlank() && ConfigBuilder.chainedOutbounds(server, "out").isNotEmpty()

    /** One GET through the local proxy on [port]: the whole round trip, in ms; -1 on failure. */
    fun timeOnce(port: Int, url: String, timeoutMs: Long): Long {
        val start = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            connection = (URI(url).toURL().openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = timeoutMs.toInt()
                readTimeout = timeoutMs.toInt()
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("User-Agent", "Volta/1.0 (Windows)")
                setRequestProperty("Connection", "close")
            }
            val code = connection.responseCode
            val ms = (System.nanoTime() - start) / 1_000_000
            if (code in 200..399 && ms <= timeoutMs * 2) ms.coerceAtLeast(1) else -1
        } catch (_: Exception) {
            -1
        } finally {
            connection?.disconnect()
        }
    }
}
