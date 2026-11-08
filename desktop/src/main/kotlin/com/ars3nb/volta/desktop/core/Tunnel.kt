package com.ars3nb.volta.desktop.core

import com.ars3nb.volta.desktop.AppPaths
import com.ars3nb.volta.desktop.platform.SystemProxy
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/** The local ports of a running tunnel. */
data class LocalPorts(val socks: Int, val http: Int, val exitCheck: Int, val metrics: Int)

/**
 * The running connection: one Xray process with Volta's config, and the Windows proxy
 * pointing at it. Only one at a time; [start] replaces whatever ran before.
 */
class Tunnel(
    private val core: XrayCore,
    private val paths: AppPaths,
    val systemProxy: SystemProxy
) {
    class StartException(message: String) : Exception(message)

    @Volatile
    private var process: Process? = null

    @Volatile
    var ports: LocalPorts? = null
        private set

    /** Set just before Volta stops the core itself, so its exit isn't taken for a crash. */
    @Volatile
    private var stopping = false

    val isRunning: Boolean get() = process?.isAlive == true

    /** Free local ports for a new tunnel, the preferred ones when nobody else holds them. */
    private fun choosePorts(preferredSocks: Int, preferredHttp: Int): LocalPorts {
        val socks = Ports.pick(preferredSocks)
        val http = Ports.pick(preferredHttp, avoid = setOf(socks))
        val (exit, metrics) = Ports.pickMany(2, avoid = setOf(socks, http))
        return LocalPorts(socks, http, exit, metrics)
    }

    /**
     * Stops whatever ran before (so its ports are free again), builds the config for the
     * new ports, starts the core and waits for it. Throws [StartException] with the
     * core's own last words when it doesn't come up.
     */
    fun start(
        preferredSocks: Int,
        preferredHttp: Int,
        buildConfig: (LocalPorts) -> String,
        onExit: (Int) -> Unit
    ): LocalPorts {
        stop(restoreProxy = false)
        val ports = choosePorts(preferredSocks, preferredHttp)
        val config = buildConfig(ports)
        if (!core.isPresent) throw StartException("xray.exe")
        paths.runDir.mkdirs()
        val file = File(paths.runDir, "config.json")
        file.writeText(config)
        if (paths.coreLog.length() > 2L * 1024 * 1024) paths.coreLog.delete()
        runCatching { paths.logDir.mkdirs(); paths.coreLog.appendText("\n---- ${java.time.LocalDateTime.now()}\n") }
        stopping = false
        val p = try {
            core.start(file, paths.coreLog)
        } catch (e: Exception) {
            throw StartException(e.message ?: e.toString())
        }
        runCatching { paths.corePid.writeText(p.pid().toString()) }
        if (!Ports.awaitListening(listOf(ports.socks, ports.http, ports.exitCheck), p, 8_000)) {
            val alive = p.isAlive
            p.destroyForcibly()
            val why = paths.coreLog.tailLines(3).ifBlank { if (alive) "timeout" else "exit ${runCatching { p.exitValue() }.getOrNull()}" }
            throw StartException(why)
        }
        process = p
        this.ports = ports
        p.onExit().thenAccept { gone ->
            if (process === gone) {
                process = null
                this.ports = null
                if (!stopping) {
                    // Nobody may be left pointing at a dead port.
                    systemProxy.restore()
                    onExit(runCatching { gone.exitValue() }.getOrDefault(-1))
                }
            }
        }
        return ports
    }

    /**
     * Another node under the running tunnel: the old core goes, the new one comes up on
     * the same local ports (the old one has just let go of them), so Windows' proxy
     * setting keeps pointing at the right place and programs only see a short pause.
     * When a port was taken meanwhile, a free one is used and [LocalPorts.http] differs
     * from the old one: the caller then points Windows at the new port. Throws
     * [StartException] like [start]; nothing runs then, and the caller decides.
     */
    fun swap(
        buildConfig: (LocalPorts) -> String,
        onExit: (Int) -> Unit,
        fallbackSocks: Int,
        fallbackHttp: Int
    ): LocalPorts {
        val old = ports
        return start(old?.socks ?: fallbackSocks, old?.http ?: fallbackHttp, buildConfig, onExit)
    }

    /** Points Windows at the tunnel. */
    fun applySystemProxy(bypass: List<String> = emptyList()): Boolean {
        val p = ports ?: return false
        return systemProxy.apply(p.http, bypass)
    }

    fun stop(restoreProxy: Boolean = true) {
        stopping = true
        if (restoreProxy) systemProxy.restore()
        val p = process
        process = null
        ports = null
        if (p != null) {
            p.destroy()
            if (!runCatching { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }.getOrDefault(false)) {
                p.destroyForcibly()
            }
        }
        runCatching { paths.corePid.delete() }
    }

    /**
     * A round trip through the exit-check port, which always leaves through the server:
     * true when the tunnel carries traffic.
     */
    fun check(timeoutMs: Int = 8_000): Boolean {
        val p = ports ?: return false
        return CHECK_URLS.any { url -> answers(p.exitCheck, url, timeoutMs) }
    }

    /**
     * The check right after a switch, short since the user is watching it: both hosts at
     * once, the first answer wins, so a working node is confirmed in about a second and
     * one slow host doesn't double the wait.
     */
    suspend fun checkQuick(timeoutMs: Int = 3_500): Boolean {
        val p = ports ?: return false
        val race = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val verdict = CompletableDeferred<Boolean>()
        val runners = CHECK_URLS.map { url ->
            race.launch { if (answers(p.exitCheck, url, timeoutMs)) verdict.complete(true) }
        }
        race.launch {
            runners.joinAll()
            verdict.complete(false)
        }
        return try {
            verdict.await()
        } finally {
            // The calls still waiting are left to their own timeouts, not waited for.
            race.cancel()
        }
    }

    private fun answers(port: Int, url: String, timeoutMs: Int): Boolean = runCatching {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
        val c = URI(url).toURL().openConnection(proxy) as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("Connection", "close")
            c.responseCode in 200..399
        } finally {
            c.disconnect()
        }
    }.getOrDefault(false)

    /** A core left running by a Volta that was killed: found by its pid file and ended. */
    fun killLeftover() {
        val pid = runCatching { paths.corePid.readText().trim().toLong() }.getOrNull() ?: return
        ProcessHandle.of(pid).ifPresent { h ->
            val cmd = h.info().command().orElse("")
            if (cmd.endsWith("xray.exe", ignoreCase = true) || cmd.endsWith("/xray")) h.destroyForcibly()
        }
        runCatching { paths.corePid.delete() }
    }

    companion object {
        val CHECK_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204"
        )
    }
}
