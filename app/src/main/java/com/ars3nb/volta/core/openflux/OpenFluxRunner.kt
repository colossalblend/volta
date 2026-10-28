package com.ars3nb.volta.core.openflux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ars3nb.volta.BuildConfig
import com.ars3nb.volta.R
import com.ars3nb.volta.ui.openflux.OpenFluxCaptchaActivity
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
 * The OpenFlux client, run as a helper process (libopenflux.so, built in CI from the
 * OpenFlux core and openflux/voltaflux): a SOCKS5 proxy on [PORT] whose traffic leaves
 * through the carriers of an openflux:// link — Yandex Docs, Mail.ru, a direct line to
 * the exit. The tunnel's proxy outbound points at that port, so routing, DNS and
 * split tunnelling work for an OpenFlux server exactly as for any other.
 *
 * Its own process rather than a library: the OpenFlux core is a Go runtime of its own,
 * which next to Xray's in one process would fight it over signal handlers, and it is
 * GPL-licensed, so it stays a separate program that Volta talks to over a socket. It
 * runs under Volta's uid, so it is outside the VPN like the rest of the app. Its stdin
 * stays open while it should run: if Volta dies, the helper notices and exits.
 */
object OpenFluxRunner {

    const val PORT = 10850
    private const val BINARY = "libopenflux.so"
    private const val TAG = "OpenFlux"
    private const val CHANNEL_ID = "volta_openflux"
    private const val NOTIFICATION_ID = 7103
    private const val CONNECT_TIMEOUT_MS = 30_000L
    private const val CAPTCHA_TIMEOUT_MS = 180_000L

    sealed interface Status {
        data object Idle : Status
        data object Starting : Status
        data class Connected(val carrier: String) : Status

        /** A carrier hit a captcha or a login wall: the page must be passed in a browser. */
        data class Captcha(
            val url: String,
            /** Open the page through this proxy: the check belongs to the exit. */
            val proxy: String,
            val reason: String,
            val carrier: String
        ) : Status

        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** False when this build carries no OpenFlux helper; set once at app start. */
    @Volatile
    var available: Boolean = true
        private set

    @Volatile
    private var process: Process? = null

    @Volatile
    private var input: Writer? = null

    @Volatile
    private var runningLink: String? = null

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        available = binary(context).canExecute()
    }

    fun isRunning(): Boolean = process?.isAlive == true

    /**
     * Starts the helper for [link] and waits until its carriers reach the exit. A captcha
     * on the way stretches the wait while the user passes it. Already running for the
     * same link and connected: nothing to do.
     */
    suspend fun start(context: Context, link: String): Result<Unit> = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        if (link == runningLink && isRunning() && _status.value is Status.Connected) {
            return@withContext Result.success(Unit)
        }
        stop()
        val exe = binary(context)
        if (!exe.canExecute()) {
            return@withContext Result.failure(IllegalStateException("OpenFlux не входит в эту сборку Volta"))
        }
        val dataDir = File(context.filesDir, "openflux").apply { mkdirs() }
        val started = runCatching {
            ProcessBuilder(exe.absolutePath)
                .directory(dataDir)
                .apply { environment()["TMPDIR"] = context.cacheDir.absolutePath }
                .start()
        }.getOrElse {
            return@withContext Result.failure(IllegalStateException("OpenFlux не запустился: ${it.message}", it))
        }
        process = started
        runningLink = link
        _status.value = Status.Starting
        input = started.outputStream.bufferedWriter().also { writer ->
            val request = JsonObject().apply {
                addProperty("link", link)
                addProperty("listen", "127.0.0.1:$PORT")
                addProperty("dataDir", dataDir.absolutePath)
                addProperty("debug", if (BuildConfig.DEBUG) 2 else 0)
            }
            writer.write(request.toString())
            writer.write("\n")
            writer.flush()
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
                    Result.failure(IllegalStateException("OpenFlux остановился при запуске"))

                now > deadline -> Result.failure(
                    IllegalStateException(
                        if (_status.value is Status.Captcha) {
                            "OpenFlux: проверка не пройдена"
                        } else {
                            "OpenFlux: узел не ответил за 30 секунд"
                        }
                    )
                )

                else -> null
            }
            if (outcome == null) delay(200)
        }
        val result = outcome ?: Result.failure(IllegalStateException("OpenFlux не подключился"))
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
        clearCaptchaNotice()
        _status.value = Status.Idle
    }

    /** The Cookie header of the passed captcha page. Still refused, it is asked again. */
    fun submitCookies(cookies: String) {
        command("cookies", "cookies", cookies)
        if (_status.value is Status.Captcha) _status.value = Status.Starting
        clearCaptchaNotice()
    }

    fun cancelCaptcha() {
        command("cancelCaptcha", null, null)
        if (_status.value is Status.Captcha) _status.value = Status.Starting
        clearCaptchaNotice()
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
                val e = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull()
                    ?: return@forEachLine
                if (process !== p) return@forEachLine
                onEvent(e)
            }
        }
        // The helper is gone. When nobody stopped it, that is a failure (its own error
        // event, if it sent one, says more).
        if (process === p && _status.value !is Status.Failed) {
            _status.value = Status.Failed("OpenFlux остановился")
        }
    }, "openflux-events").apply { isDaemon = true }.start()

    private fun readLog(p: Process) = Thread({
        runCatching {
            p.errorStream.bufferedReader().forEachLine { Log.d(TAG, it) }
        }
    }, "openflux-log").apply { isDaemon = true }.start()

    private fun onEvent(e: JsonObject) {
        fun str(key: String) = e.get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        when (str("event")) {
            "connected" -> {
                clearCaptchaNotice()
                _status.value = Status.Connected(str("transport"))
            }

            "disconnected" -> if (_status.value !is Status.Captcha) _status.value = Status.Starting
            "captcha" -> {
                val captcha = Status.Captcha(str("url"), str("proxy"), str("reason"), str("transport"))
                _status.value = captcha
                showCaptchaNotice(captcha)
            }

            "captchaFailed" -> Log.w(TAG, "captcha answer refused: ${str("message")}")
            "error" -> _status.value = Status.Failed(describe(str("code"), str("message")))
        }
    }

    /** The helper's own words are Russian already; link problems get plainer ones. */
    internal fun describe(code: String, message: String): String = when (code) {
        "not_link", "damaged", "case_changed" -> "Ссылка OpenFlux повреждена, скопируйте её заново"
        "unsupported_version" -> "Ссылка OpenFlux новее, чем умеет эта версия Volta"
        "unknown_transport", "not_shareable" -> "В ссылке OpenFlux транспорт, который Volta не знает"
        "session_secret", "short_secret" -> "В ссылке OpenFlux слишком короткий ключ"
        else -> "OpenFlux: ${message.ifBlank { "не удалось подключиться" }}"
    }

    private fun binary(context: Context) = File(context.applicationInfo.nativeLibraryDir, BINARY)

    private fun showCaptchaNotice(captcha: Status.Captcha) {
        val context = appContext ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "OpenFlux", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Проверки, которые просит пройти OpenFlux" }
        )
        val open = PendingIntent.getActivity(
            context, 30,
            Intent(context, OpenFluxCaptchaActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle("OpenFlux просит пройти проверку")
            .setContentText(
                "${OpenFluxLink.carrierName(captcha.carrier.substringBefore('-'))}: " +
                    "нажмите, чтобы открыть"
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    private fun clearCaptchaNotice() {
        val context = appContext ?: return
        runCatching {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
