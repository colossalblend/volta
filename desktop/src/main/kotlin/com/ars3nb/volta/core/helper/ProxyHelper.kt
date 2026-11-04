package com.ars3nb.volta.core.helper

import com.ars3nb.volta.desktop.core.Ports
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import com.ars3nb.volta.desktop.platform.WinApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.Writer
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Android app's ProxyHelper on Windows: AmneziaWG (1.x, 2.x, 3.x), Hysteria v1 and
 * Hysteria2 with obfuscation, which the Xray core cannot speak, run in voltaproxy.exe —
 * the same helper/voltaproxy program, built by CI for windows/amd64 and shipped next to
 * xray.exe. It serves a SOCKS5 proxy on [PORT]; the tunnel's proxy outbound points
 * there, so routing, filters and the system proxy work for these servers exactly as for
 * any other.
 *
 * Its stdin stays open while it should run, so it exits with Volta; it is also put in
 * Volta's job object, so Windows ends it if Volta is killed.
 */
object ProxyHelper {

    /** The port Android uses; another one when something on this computer holds it. */
    private const val PREFERRED_PORT = 10860
    private const val START_TIMEOUT_MS = 15_000L

    /** What the helper runs: a kind and its config (.conf text or share link). */
    data class Spec(val kind: String, val config: String) {
        companion object {
            const val AWG = "awg"
            const val HYSTERIA = "hysteria"
            const val HYSTERIA2 = "hysteria2"
        }
    }

    @Volatile
    private var exe: File? = null

    @Volatile
    private var log: File? = null

    /** The helper's port for the next config; [start] picks it before the core starts. */
    @Volatile
    var PORT: Int = PREFERRED_PORT
        private set

    /** False when this build carries no helper (voltaproxy.exe is missing). */
    val available: Boolean get() = exe?.isFile == true

    @Volatile
    private var process: Process? = null

    @Volatile
    private var input: Writer? = null

    @Volatile
    var running: Spec? = null
        private set

    /** [dir] holds voltaproxy.exe; the helper's own log goes to [logFile]. */
    fun init(dir: File, logFile: File?) {
        exe = File(dir, if (WinApi.isWindows) "voltaproxy.exe" else "voltaproxy")
        log = logFile
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts the helper for [spec] and waits until its proxy listens. Already running for
     * the same server: nothing to do.
     */
    @Synchronized
    fun start(spec: Spec): Result<Unit> {
        if (spec == running && isRunning()) return Result.success(Unit)
        stop()
        val binary = exe
        if (binary == null || !binary.isFile) {
            return Result.failure(IllegalStateException(Res.string(R.string.home_helper_not_in_build, label(spec))))
        }
        val port = Ports.pick(PREFERRED_PORT)
        val started = runCatching { launch(binary) }.getOrElse {
            return Result.failure(
                IllegalStateException(Res.string(R.string.home_helper_launch_failed, label(spec), it.message.orEmpty()), it)
            )
        }
        val writer = started.outputStream.bufferedWriter()
        val request = JsonObject().apply {
            addProperty("kind", spec.kind)
            addProperty("config", spec.config)
            addProperty("listen", "127.0.0.1:$port")
        }
        runCatching {
            writer.write(request.toString())
            writer.write("\n")
            writer.flush()
        }
        readLog(started)
        val first = firstEvent(started, START_TIMEOUT_MS)
        if (first?.str("event") != "ready") {
            runCatching { writer.close() }
            started.destroyForcibly()
            val why = first?.str("message")?.takeIf { it.isNotBlank() }
                ?: Res.string(if (first == null) R.string.home_helper_no_answer else R.string.home_helper_stopped)
            return Result.failure(IllegalStateException("${label(spec)}: $why"))
        }
        process = started
        input = writer
        running = spec
        PORT = port
        return Result.success(Unit)
    }

    /** Stops the helper: closing its stdin lets it shut the proxy down cleanly. */
    @Synchronized
    fun stop() {
        val p = process ?: return
        process = null
        running = null
        runCatching { input?.close() }
        input = null
        runCatching {
            if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                p.destroy()
                if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        }
    }

    /**
     * Round trip to [url] through the server, in ms; -1 when it doesn't answer. The
     * server the tunnel runs on is measured through the running helper: a second
     * AmneziaWG client with the same keys would steal the live session.
     */
    suspend fun probe(spec: Spec, url: String, timeoutMs: Long): Long = withContext(Dispatchers.IO) {
        if (spec == running && isRunning()) return@withContext probeLive(url, timeoutMs)
        val binary = exe
        if (binary == null || !binary.isFile) return@withContext -1L
        val p = runCatching { launch(binary) }.getOrNull() ?: return@withContext -1L
        try {
            p.outputStream.bufferedWriter().use { w ->
                val request = JsonObject().apply {
                    addProperty("kind", spec.kind)
                    addProperty("config", spec.config)
                    addProperty("probe", url)
                    addProperty("timeoutMs", timeoutMs)
                }
                w.write(request.toString())
                w.write("\n")
            }
            readLog(p)
            val event = firstEvent(p, timeoutMs + 3_000)
            if (event?.str("event") == "delay") event.get("ms")?.asLong ?: -1L else -1L
        } catch (_: Exception) {
            -1L
        } finally {
            p.destroyForcibly()
        }
    }

    private fun probeLive(url: String, timeoutMs: Long): Long {
        val start = System.nanoTime()
        return runCatching {
            val c = URI(url).toURL()
                .openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", PORT))) as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs.toInt()
                c.readTimeout = timeoutMs.toInt()
                c.requestMethod = "HEAD"
                c.responseCode
            } finally {
                c.disconnect()
            }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        }.getOrDefault(-1L)
    }

    private fun launch(binary: File): Process =
        ProcessBuilder(binary.absolutePath)
            .directory(binary.parentFile)
            .start()
            .also { WinApi.bindToAppLifetime(it.pid()) }

    /**
     * The helper's first event line, or null if none came in [timeoutMs]. Its stdout is
     * read to the end on a thread of its own, so a chatty helper never blocks on a full pipe.
     */
    private fun firstEvent(p: Process, timeoutMs: Long): JsonObject? {
        val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        Thread({
            runCatching { p.inputStream.bufferedReader().forEachLine { lines.offer(it) } }
        }, "voltaproxy-events").apply {
            isDaemon = true
            start()
        }
        val line = lines.poll(timeoutMs, TimeUnit.MILLISECONDS)
        return line?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
    }

    private fun readLog(p: Process) = Thread({
        val target = log
        runCatching {
            p.errorStream.bufferedReader().forEachLine { line ->
                if (target != null) runCatching {
                    if (target.length() > 1_000_000) target.delete()
                    target.appendText(line + "\n")
                }
            }
        }
    }, "voltaproxy-log").apply { isDaemon = true }.start()

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun label(spec: Spec) = when (spec.kind) {
        Spec.AWG -> "AmneziaWG"
        Spec.HYSTERIA -> "Hysteria"
        else -> "Hysteria2"
    }
}
