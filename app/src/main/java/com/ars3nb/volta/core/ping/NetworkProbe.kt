package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Decides what the *local* network currently allows, using the two-stage ladder from
 * the SpeedTester project.
 *
 * Stage 1 — foreign captive-portal endpoints over plain HTTP GET, accepting only a
 * literal `204`. A majority of successes means the open internet is reachable and
 * stage 2 is skipped entirely.
 *
 * Stage 2 — Russian hosts, each tried DNS → TCP:443 → TCP:80 → HTTPS HEAD → HTTP HEAD,
 * so a host that answers on any layer still counts as reachable.
 *
 * Nothing answering on either stage means there is no usable network at all, which is
 * reported as [NetworkRegime.BLOCKED] — the caller must not waste a ping sweep on it.
 */
class NetworkProbe(
    private val batterySaver: Boolean = true
) {

    /** Foreign endpoints that answer 204 on an unrestricted connection. */
    val foreignTargets: List<Pair<String, String>> = listOf(
        "https://www.google.com/generate_204" to "Google",
        "https://www.gstatic.com/generate_204" to "Google Static",
        "https://connectivitycheck.gstatic.com/generate_204" to "GStatic",
        "http://connect.rom.miui.com/generate_204" to "MIUI"
    )

    /** Russian hosts that stay reachable under a white-list regime. */
    val localHosts: List<Pair<String, String>> = listOf(
        "ya.ru" to "Яндекс",
        "rzd.ru" to "РЖД",
        "ozon.ru" to "Ozon",
        "alfabank.ru" to "Альфа-Банк",
        "max.ru" to "Max"
    )

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

    /**
     * Runs the ladder. Stage 2 only happens when stage 1 fails to reach a majority,
     * so an open connection costs four parallel requests and nothing more.
     */
    suspend fun run(): Outcome = coroutineScope {
        val gate = Semaphore(if (batterySaver) 3 else 5)

        val foreign = foreignTargets.map { (url, label) ->
            async { gate.withPermit { connectivityProbe(url, label) } }
        }.awaitAll()
        val foreignOk = foreign.count { it.reachable }

        if (isMajority(foreignOk, foreign.size)) {
            return@coroutineScope Outcome(
                regime = NetworkRegime.OPEN,
                probes = foreign,
                foreignOk = foreignOk,
                foreignTotal = foreign.size,
                localOk = 0,
                localTotal = 0
            )
        }

        val local = localHosts.map { (host, label) ->
            async { gate.withPermit { hostProbe(host, label) } }
        }.awaitAll()
        val localOk = local.count { it.reachable }

        val regime = when {
            foreignOk == 0 && localOk == 0 -> NetworkRegime.BLOCKED
            foreignOk > 0 -> NetworkRegime.OPEN
            else -> NetworkRegime.WHITELIST_ONLY
        }

        Outcome(
            regime = regime,
            probes = foreign + local,
            foreignOk = foreignOk,
            foreignTotal = foreign.size,
            localOk = localOk,
            localTotal = local.size
        )
    }

    /** Half or more of the endpoints answering counts as "the open internet works". */
    fun isMajority(success: Int, total: Int): Boolean = total > 0 && success * 2 >= total

    // ------------------------------------------------------------------ stage 1

    private suspend fun connectivityProbe(url: String, label: String): SiteProbeResult {
        repeat(ATTEMPTS) {
            val ms = connectivityAttempt(url)
            if (ms >= 0) {
                return SiteProbeResult(url, label, SiteCategory.FOREIGN, ms, true)
            }
        }
        return SiteProbeResult(url, label, SiteCategory.FOREIGN, -1, false)
    }

    private suspend fun connectivityAttempt(url: String): Long = withContext(Dispatchers.IO) {
        val host = runCatching { java.net.URL(url).host }.getOrNull()
            ?: return@withContext -1L
        if (resolve(host) < 0) return@withContext -1L

        val request = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            // A cached 204 would make a blocked network look open.
            .cacheControl(CacheControl.FORCE_NETWORK)
            .build()
        val start = System.nanoTime()
        runCatching {
            client.newCall(request).execute().use { response ->
                // Only a literal 204 counts: a portal or a block page answers 200.
                if (response.code == 204) elapsed(start) else -1L
            }
        }.getOrDefault(-1L)
    }

    // ------------------------------------------------------------------ stage 2

    private suspend fun hostProbe(host: String, label: String): SiteProbeResult {
        repeat(ATTEMPTS) {
            val ms = hostAttempt(host)
            if (ms >= 0) {
                return SiteProbeResult(host, label, SiteCategory.RU_WHITELIST, ms, true)
            }
        }
        return SiteProbeResult(host, label, SiteCategory.RU_WHITELIST, -1, false)
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
        private const val ATTEMPTS = 2
        private const val DNS_TIMEOUT_MS = 1200L
        private const val TCP_TIMEOUT_MS = 1200
        private const val HTTP_TIMEOUT_MS = 1800L
        private const val USER_AGENT = "Volta/1.0"

        private val dnsExecutor: ExecutorService = Executors.newCachedThreadPool()
    }
}
