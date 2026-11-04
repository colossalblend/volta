package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Decides what the *local* network currently allows, so the app knows which server
 * group can work here.
 *
 * Six hosts are probed in parallel, each tried DNS → TCP:443 → TCP:80 → HTTPS HEAD →
 * HTTP HEAD, so a host that answers on any layer counts as reachable:
 *
 *  - Home probes — google.com, ars3nb.ru, calculator888.ru. Any of them answering
 *    means the open internet works: [NetworkRegime.OPEN], use Home servers.
 *  - LTE probes — vk.ru, ya.ru, appbank.pw. When only these answer the device sits
 *    behind a mobile whitelist: [NetworkRegime.WHITELIST_ONLY], use LTE servers.
 *
 * Nothing answering means there is no usable network at all, which is reported as
 * [NetworkRegime.BLOCKED] — the caller must not waste a ping sweep on it.
 *
 * This app is excluded from its own tunnel, so the probe describes the real network
 * even while the VPN is up.
 */
class NetworkProbe(
    private val batterySaver: Boolean = true
) {

    /** Hosts that only answer on an unrestricted (home) connection. */
    val homeHosts: List<Pair<String, String>> = HOME_HOSTS

    /** Hosts that stay reachable under a mobile-operator whitelist. */
    val lteHosts: List<Pair<String, String>> = LTE_HOSTS

    data class Outcome(
        val regime: NetworkRegime,
        val probes: List<SiteProbeResult>,
        val foreignOk: Int,
        val foreignTotal: Int,
        val localOk: Int,
        val localTotal: Int
    ) {
        val hasAnyNetwork: Boolean get() = foreignOk > 0 || localOk > 0
    }

    /** All six hosts at once: an answer costs the slowest probe, not the sum. */
    suspend fun run(): Outcome = coroutineScope {
        val gate = Semaphore(if (batterySaver) 4 else 6)

        val home = homeHosts.map { (host, label) ->
            async { gate.withPermit { hostProbe(host, label, SiteCategory.FOREIGN) } }
        }
        val lte = lteHosts.map { (host, label) ->
            async { gate.withPermit { hostProbe(host, label, SiteCategory.RU_WHITELIST) } }
        }
        val homeResults = home.awaitAll()
        val lteResults = lte.awaitAll()
        val homeOk = homeResults.count { it.reachable }
        val lteOk = lteResults.count { it.reachable }

        Outcome(
            regime = classify(homeOk, lteOk),
            probes = homeResults + lteResults,
            foreignOk = homeOk,
            foreignTotal = homeResults.size,
            localOk = lteOk,
            localTotal = lteResults.size
        )
    }

    /**
     * The connect-time variant: answers as soon as the answer is clear instead of
     * waiting for every probe to time out.
     *
     *  - Any Home host answering settles it at once: open internet (typically <0.5 s).
     *  - LTE hosts answering while no Home host has after [FAST_MIN_WAIT_MS] means a
     *    whitelist: blocked hosts never answer, so waiting out their whole timeout
     *    ladder (≈12 s) told us nothing new.
     *  - Nothing at all: wait for the single attempt per host to finish.
     *
     * Probes run in their own scope, so returning early doesn't wait for sockets that
     * are still blocked in connect().
     */
    suspend fun runFast(): Outcome {
        val started = System.currentTimeMillis()
        val results = ConcurrentHashMap<String, SiteProbeResult>()
        val probeScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val all = homeHosts.map { it to SiteCategory.FOREIGN } + lteHosts.map { it to SiteCategory.RU_WHITELIST }
        val jobs = all.map { (host, category) ->
            probeScope.launch {
                results[host.first] = hostProbe(host.first, host.second, category, attempts = 1)
            }
        }
        try {
            while (true) {
                val done = results.values
                if (done.any { it.category == SiteCategory.FOREIGN && it.reachable }) break
                val lteOk = done.any { it.category == SiteCategory.RU_WHITELIST && it.reachable }
                if (lteOk && System.currentTimeMillis() - started >= FAST_MIN_WAIT_MS) break
                if (jobs.all { it.isCompleted }) break
                delay(40)
            }
        } finally {
            probeScope.cancel()
        }

        val probes = all.map { (host, category) ->
            results[host.first] ?: SiteProbeResult(host.first, host.second, category, -1, false)
        }
        val homeOk = probes.count { it.category == SiteCategory.FOREIGN && it.reachable }
        val lteOk = probes.count { it.category == SiteCategory.RU_WHITELIST && it.reachable }
        return Outcome(
            regime = classify(homeOk, lteOk),
            probes = probes,
            foreignOk = homeOk,
            foreignTotal = homeHosts.size,
            localOk = lteOk,
            localTotal = lteHosts.size
        )
    }

    /** Half or more of the endpoints answering counts as "the open internet works". */
    fun isMajority(success: Int, total: Int): Boolean = total > 0 && success * 2 >= total

    // ------------------------------------------------------------------ probing

    private suspend fun hostProbe(
        host: String,
        label: String,
        category: SiteCategory,
        attempts: Int = ATTEMPTS
    ): SiteProbeResult {
        repeat(attempts) {
            val ms = hostAttempt(host)
            if (ms >= 0) return SiteProbeResult(host, label, category, ms, true)
        }
        return SiteProbeResult(host, label, category, -1, false)
    }

    private suspend fun hostAttempt(host: String): Long = withContext(Dispatchers.IO) {
        if (resolve(host) < 0) return@withContext -1L

        tcp(host, 443).takeIf { it >= 0 }?.let { return@withContext it }
        tcp(host, 80).takeIf { it >= 0 }?.let { return@withContext it }
        head("https://$host").takeIf { it >= 0 }?.let { return@withContext it }
        head("http://$host")
    }

    // ------------------------------------------------------------------ helpers

    /** DNS on a worker thread: a hung resolver otherwise blocks past every timeout. */
    private fun resolve(host: String): Long {
        val start = System.nanoTime()
        return try {
            dnsExecutor.submit<InetAddress> { InetAddress.getByName(host) }
                .get(DNS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            elapsed(start)
        } catch (_: TimeoutException) {
            -1L
        } catch (_: Exception) {
            -1L
        }
    }

    private fun tcp(host: String, port: Int): Long {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), TCP_TIMEOUT_MS)
                if (!socket.isConnected) throw SocketTimeoutException("not connected")
            }
            elapsed(start)
        } catch (_: Exception) {
            -1L
        }
    }

    private fun head(url: String): Long {
        val request = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", USER_AGENT)
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val start = System.nanoTime()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code in 200..399) elapsed(start) else -1L
            }
        } catch (_: Exception) {
            -1L
        }
    }

    private fun elapsed(startNanos: Long): Long =
        ((System.nanoTime() - startNanos) / 1_000_000L).coerceAtLeast(1L)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(TCP_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .callTimeout(HTTP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .connectionPool(ConnectionPool(8, 30, TimeUnit.SECONDS))
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    companion object {
        val HOME_HOSTS: List<Pair<String, String>> = listOf(
            "google.com" to "Google",
            "ars3nb.ru" to "ars3nb",
            "calculator888.ru" to "calculator888"
        )

        val LTE_HOSTS: List<Pair<String, String>> = listOf(
            "vk.ru" to "VK",
            "ya.ru" to "Yandex",
            "appbank.pw" to "appbank"
        )

        /**
         * Any Home host answering wins: whitelists never let google.com through, so a
         * single success proves the open internet. Only LTE hosts → mobile whitelist.
         */
        fun classify(homeOk: Int, lteOk: Int): NetworkRegime = when {
            homeOk > 0 -> NetworkRegime.OPEN
            lteOk > 0 -> NetworkRegime.WHITELIST_ONLY
            else -> NetworkRegime.BLOCKED
        }

        private const val ATTEMPTS = 2

        /** Long enough for a Home host's DNS + TCP on a slow but open network. */
        const val FAST_MIN_WAIT_MS = 2_000L
        private const val DNS_TIMEOUT_MS = 1200L
        private const val TCP_TIMEOUT_MS = 1200
        private const val HTTP_TIMEOUT_MS = 1800L
        private const val USER_AGENT = "Volta/1.0"

        private val dnsExecutor: ExecutorService = Executors.newCachedThreadPool()
    }
}
