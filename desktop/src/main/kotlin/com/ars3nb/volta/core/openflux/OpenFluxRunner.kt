package com.ars3nb.volta.core.openflux

import com.ars3nb.volta.desktop.core.Ports
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import com.ars3nb.volta.desktop.platform.WinApi
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.Writer
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * The Android app's OpenFluxRunner on Windows: the OpenFlux client as a helper process
 * (openflux.exe, built by CI from the OpenFlux core at openflux/VERSION and
 * openflux/voltaflux for windows/amd64): a SOCKS5 proxy on [PORT] whose traffic leaves
 * through the carriers of an openflux:// link. The tunnel's proxy outbound points at
 * that port, so routing and filters work for an OpenFlux server as for any other.
 *
 * The same protocol as on Android: the request on the first line of stdin, commands on
 * the following ones, events on stdout. Its stdin stays open while it should run, so
 * it exits with Volta, and it is in Volta's job object, so Windows ends it if Volta is
 * killed.
 *
 * A captcha: Android shows the page in its own browser view and hands the cookies back.
 * Windows has no browser view inside Volta, so the page opens in the user's browser
 * ([onCaptcha]); the carrier gets no cookies from there, so «Готово» lets it carry on
 * (it retries, or moves on to the link's next carrier).
 */
object OpenFluxRunner {

    private const val PREFERRED_PORT = 10850
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val CAPTCHA_TIMEOUT_MS = 180_000L

    sealed interface Status {
        data object Idle : Status
        data object Starting : Status
        data class Connected(val carrier: String) : Status

        /** A carrier hit a captcha or a login wall: the page must be passed in a browser. */
        data class Captcha(
            val url: String,
            /** The page belongs to the exit: it should be opened through this proxy. */
            val proxy: String,
            val reason: String,
            val carrier: String
        ) : Status

        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Told when a carrier asks for a check; Volta opens the page in the browser. */
    @Volatile
    var onCaptcha: ((Status.Captcha) -> Unit)? = null

    @Volatile
    private var exe: File? = null

    @Volatile
    private var dataDir: File? = null

    @Volatile
    private var log: File? = null

    /** The helper's port for the next config; [start] picks it before the core starts. */
    @Volatile
    var PORT: Int = PREFERRED_PORT
        private set

    /** False when this build carries no OpenFlux helper (openflux.exe is missing). */
    val available: Boolean get() = exe?.isFile == true

    @Volatile
    private var process: Process? = null

    @Volatile
    private var input: Writer? = null

    @Volatile
    private var runningLink: String? = null

    fun init(dir: File, data: File, logFile: File?) {
        exe = File(dir, if (WinApi.isWindows) "openflux.exe" else "openflux")
        dataDir = data
        log = logFile
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /** Whether the helper runs on [link] right now (only one runs at a time). */
    fun runs(link: String): Boolean = link.isNotBlank() && link == runningLink && isRunning()

    fun connectedOn(link: String): Boolean = runs(link) && _status.value is Status.Connected

    fun waitsForCheck(link: String): Boolean = runs(link) && _status.value is Status.Captcha

    /**
     * Starts the helper for [link] and waits until its carriers reach the exit. A captcha
     * on the way stretches the wait while the user passes it.
     */
    suspend fun start(link: String): Result<Unit> = withContext(Dispatchers.IO) {
        if (connectedOn(link)) return@withContext Result.success(Unit)
        stop()
        val binary = exe
        if (binary == null || !binary.isFile) {
            return@withContext Result.failure(IllegalStateException(Res.string(R.string.servers_openflux_not_in_build)))
        }
        val dir = (dataDir ?: binary.parentFile).apply { mkdirs() }
        val port = Ports.pick(PREFERRED_PORT)
        val started = runCatching {
            ProcessBuilder(binary.absolutePath).directory(dir).start()
                .also { WinApi.bindToAppLifetime(it.pid()) }
        }.getOrElse {
            return@withContext Result.failure(
                IllegalStateException(Res.string(R.string.servers_openflux_start_failed, it.message.orEmpty()), it)
            )
        }
        process = started
        runningLink = link
        PORT = port
        _status.value = Status.Starting
        input = started.outputStream.bufferedWriter().also { writer ->
            val request = JsonObject().apply {
                addProperty("link", link)
                addProperty("listen", "127.0.0.1:$port")
                addProperty("dataDir", dir.absolutePath)
                addProperty("debug", 0)
            }
            runCatching {
                writer.write(request.toString())
                writer.write("\n")
                writer.flush()
            }
        }
        readEvents(started)
        readLog(started)

        var deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        var outcome: Result<Unit>? = null
        while (outcome == null) {
            val now = System.currentTimeMillis()
            when (val s = _status.value) {
                is Status.Connected -> outcome = Result.success(Unit)
                is Status.Failed -> outcome = Result.failure(IllegalStateException(s.message))
                is Status.Captcha -> deadline = maxOf(deadline, now + CAPTCHA_TIMEOUT_MS)
                else -> Unit
            }
            if (outcome != null) break
            outcome = when {
                !started.isAlive && _status.value !is Status.Failed ->
                    Result.failure(IllegalStateException(Res.string(R.string.servers_openflux_stopped_on_start)))

                now > deadline -> Result.failure(
                    IllegalStateException(
                        Res.string(
                            if (_status.value is Status.Captcha) R.string.servers_openflux_check_failed
                            else R.string.servers_openflux_timeout
                        )
                    )
                )

                else -> null
            }
            if (outcome == null) delay(200)
        }
        val result = outcome ?: Result.failure(IllegalStateException(Res.string(R.string.servers_openflux_not_connected)))
        if (result.isFailure) stop()
        result
    }

    /** Stops the helper: closing its stdin lets it shut the proxy down cleanly first. */
    fun stop() {
        val p = process ?: return
        process = null
        runningLink = null
        runCatching { input?.close() }
        input = null
        runCatching {
            if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                p.destroy()
                if (!p.waitFor(1000, TimeUnit.MILLISECONDS)) p.destroyForcibly()
            }
        }
        _status.value = Status.Idle
    }

    /** For the self-test's pictures: back to nothing running. */
    internal fun reset() {
        stop()
        _status.value = Status.Idle
    }

    /** The Cookie header of a passed captcha page. */
    fun submitCookies(cookies: String) {
        command("cookies", "cookies", cookies)
        if (_status.value is Status.Captcha) _status.value = Status.Starting
    }

    /** The user is done with the page (or gave up): the carrier carries on without it. */
    fun cancelCaptcha() {
        command("cancelCaptcha", null, null)
        if (_status.value is Status.Captcha) _status.value = Status.Starting
    }

    private fun command(name: String, key: String?, value: String?) {
        val writer = input ?: return
        runCatching {
            val cmd = JsonObject().apply {
                addProperty("cmd", name)
                if (key != null) addProperty(key, value)
            }
            synchronized(writer) {
                writer.write(cmd.toString())
                writer.write("\n")
                writer.flush()
            }
        }
    }

    private fun readEvents(p: Process) = Thread({
        runCatching {
            p.inputStream.bufferedReader().forEachLine { line ->
                val e = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEachLine
                if (process !== p) return@forEachLine
                onEvent(e)
            }
        }
        if (process === p && _status.value !is Status.Failed) {
            _status.value = Status.Failed(Res.string(R.string.servers_openflux_stopped))
        }
    }, "openflux-events").apply { isDaemon = true }.start()

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
    }, "openflux-log").apply { isDaemon = true }.start()

    internal fun onEvent(e: JsonObject) {
        fun str(key: String) = e.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        when (str("event")) {
            "connected" -> _status.value = Status.Connected(str("transport"))
            "disconnected" -> if (_status.value !is Status.Captcha) _status.value = Status.Starting
            "captcha" -> {
                val captcha = Status.Captcha(str("url"), str("proxy"), str("reason"), str("transport"))
                _status.value = captcha
                runCatching { onCaptcha?.invoke(captcha) }
            }

            "error" -> _status.value = Status.Failed(describe(str("code"), str("message")))
        }
    }

    /** The helper's own words are Russian already; link problems get plainer ones. */
    private fun describe(code: String, message: String): String =
        problemRes(code)?.let { Res.string(it) }
            ?: Res.string(R.string.servers_openflux_error, message.ifBlank { Res.string(R.string.servers_openflux_connect_failed) })

    /** The plain words for a link problem the helper reports by [code]; null for the rest. */
    internal fun problemRes(code: String): String? = when (code) {
        "not_link", "damaged", "case_changed" -> R.string.servers_openflux_link_damaged
        "unsupported_version" -> R.string.servers_openflux_link_newer
        "unknown_transport", "not_shareable" -> R.string.servers_openflux_unknown_transport
        "session_secret", "short_secret" -> R.string.servers_openflux_short_key
        else -> null
    }
}
