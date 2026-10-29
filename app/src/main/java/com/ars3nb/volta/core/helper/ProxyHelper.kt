package com.ars3nb.volta.core.helper

import android.content.Context
import android.util.Log
import com.ars3nb.volta.BuildConfig
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.Writer
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Protocols the Xray core cannot speak — AmneziaWG (1.x, 2.x, 3.x) and Hysteria v1, plus
 * Hysteria2 with obfuscation — run in a helper process (libvoltaproxy.so, built in CI
 * from helper/voltaproxy on amneziawg-go and sing-quic). It serves a SOCKS5 proxy on
 * [PORT]; the tunnel's proxy outbound points there, so routing, DNS and split
 * tunnelling work for these servers exactly as for any other.
 *
 * Its own process for the same reasons as OpenFlux: a second Go runtime next to
 * Xray's would fight it over signals, and sing-quic is GPL. It runs under Volta's uid,
 * so it is outside the VPN; its stdin stays open while it should run, so it exits
 * with the app.
 */
object ProxyHelper {

    const val PORT = 10860
    private const val BINARY = "libvoltaproxy.so"
    private const val TAG = "VoltaProxy"
    private const val START_TIMEOUT_MS = 15_000L

    /** What the helper runs: a kind and its config (.conf text or share link). */
    data class Spec(val kind: String, val config: String) {
        companion object {
            const val AWG = "awg"
            const val HYSTERIA = "hysteria"
            const val HYSTERIA2 = "hysteria2"
        }
    }

    /** False when this build carries no helper; set once at app start. */
    @Volatile
    var available: Boolean = true
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    private var input: Writer? = null

    @Volatile
    var running: Spec? = null
        private set

    private var nativeDir: String = ""

    fun init(context: Context) {
        nativeDir = context.applicationInfo.nativeLibraryDir
        available = binary().canExecute()
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts the helper for [spec] and waits until its proxy listens. Already running
     * for the same server: nothing to do.
     */
    @Synchronized
    fun start(spec: Spec): Result<Unit> {
        if (spec == running && isRunning()) return Result.success(Unit)
        stop()
        val exe = binary()
        if (!exe.canExecute()) {
            return Result.failure(IllegalStateException("${label(spec)} не входит в эту сборку Volta"))
        }
        val started = runCatching { launch(exe) }.getOrElse {
            return Result.failure(IllegalStateException("${label(spec)} не запустился: ${it.message}", it))
        }
        val writer = started.outputStream.bufferedWriter()
        val request = JsonObject().apply {
            addProperty("kind", spec.kind)
            addProperty("config", spec.config)
            addProperty("listen", "127.0.0.1:$PORT")
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
            started.destroy()
            val why = first?.str("message")?.takeIf { it.isNotBlank() }
                ?: if (first == null) "не ответил вовремя" else "остановился"
            return Result.failure(IllegalStateException("${label(spec)}: $why"))
        }
        process = started
        input = writer
        running = spec
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
        val exe = binary()
        if (!exe.canExecute()) return@withContext -1L
        val p = runCatching { launch(exe) }.getOrNull() ?: return@withContext -1L
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
            p.destroy()
        }
    }

    private fun probeLive(url: String, timeoutMs: Long): Long {
        val client = OkHttpClient.Builder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", PORT)))
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val start = System.nanoTime()
        return runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { }
            ((System.nanoTime() - start) / 1_000_000).coerceAtLeast(1)
        }.getOrDefault(-1L)
    }

    private fun launch(exe: File): Process =
        ProcessBuilder(exe.absolutePath)
            .apply { if (BuildConfig.DEBUG) environment()["VOLTA_DEBUG"] = "1" }
            .start()

    /** The helper's first event line, or null if none came in [timeoutMs]. */
    private fun firstEvent(p: Process, timeoutMs: Long): JsonObject? {
        var line: String? = null
        val reader = Thread({
            line = runCatching { p.inputStream.bufferedReader().readLine() }.getOrNull()
        }, "voltaproxy-events").apply {
            isDaemon = true
            start()
        }
        reader.join(timeoutMs)
        return line?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
    }

    private fun readLog(p: Process) = Thread({
        runCatching { p.errorStream.bufferedReader().forEachLine { Log.d(TAG, it) } }
    }, "voltaproxy-log").apply { isDaemon = true }.start()

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()

    private fun label(spec: Spec) = when (spec.kind) {
        Spec.AWG -> "AmneziaWG"
        Spec.HYSTERIA -> "Hysteria"
        else -> "Hysteria2"
    }

    private fun binary() = File(nativeDir, BINARY)
}
