package com.ars3nb.volta.core.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ars3nb.volta.BuildConfig
import com.ars3nb.volta.MainActivity
import com.ars3nb.volta.R
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.prefs.AppPreferences
import com.ars3nb.volta.data.repository.VoltaRepository
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** One APK of a build, as the build's update.json lists it. */
data class UpdateApk(val abi: String, val name: String, val size: Long, val sha256: String)

/** A build on GitHub and the APK of it that fits this phone. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val xray: String,
    val apk: UpdateApk,
    val url: String,
    /** [url] is the GitHub API's: it wants the token and an octet-stream Accept. */
    val viaApi: Boolean
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    /** Downloaded and checked; Android's installer has it or Volta may not install yet. */
    data class Ready(val info: UpdateInfo, val needsPermission: Boolean = false) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Updates from GitHub. Every CI build goes to a rolling pre-release of its branch
 * ("apk-<branch>") with an update.json naming the build's number and the APK for each
 * CPU type. The app reads that file on its own channel and on apk-main, and when a build
 * is newer than itself downloads the APK for this phone, checks its SHA-256 and hands it
 * to Android's installer. Every build is signed with the same key, so it installs over.
 *
 * The public mirror (volta-releases) is asked first, by plain download links; the app's
 * own repository is private and answers only to a token entered in the settings.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {

    private val prefs = AppPreferences(context)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val autoCheck = prefs.updateAutoCheck
    val token = prefs.updateToken

    private var download: Job? = null

    private val direct = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Through the running tunnel, for networks that let only a whitelist through. */
    private val tunnel: OkHttpClient by lazy {
        direct.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", VoltaRepository.LOCAL_HTTP_PORT)))
            .build()
    }

    /** GitHub was out of reach directly but answered through the tunnel. */
    @Volatile
    private var preferTunnel = false

    suspend fun setAutoCheck(enabled: Boolean) = prefs.setUpdateAutoCheck(enabled)

    suspend fun setToken(value: String) {
        prefs.setUpdateToken(value)
        check()
    }

    /** On opening the app: once per process, then at most every half hour. */
    suspend fun checkOnLaunch() {
        if (!prefs.updateAutoCheck.first()) return
        val recent = System.currentTimeMillis() - prefs.updateLastCheck.first() < LAUNCH_EVERY_MS
        if (_state.value is UpdateState.Idle || !recent) check()
    }

    /** From the hourly worker: every few hours, and a notification once per new build. */
    suspend fun checkInBackground() {
        if (!prefs.updateAutoCheck.first()) return
        if (System.currentTimeMillis() - prefs.updateLastCheck.first() < BACKGROUND_EVERY_MS) return
        val info = when (val found = check()) {
            is UpdateState.Available -> found.info
            is UpdateState.Ready -> found.info
            else -> return
        }
        if (info.versionCode > prefs.updateNotified.first()) {
            notifyAvailable(info)
            prefs.setUpdateNotified(info.versionCode)
        }
    }

    suspend fun check(): UpdateState {
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return current
        _state.value = UpdateState.Checking
        val token = prefs.updateToken.first()
        val now = System.currentTimeMillis()
        val next = withContext(Dispatchers.IO) {
            try {
                val found = findNewest(token)
                when {
                    found == null -> UpdateState.Failed(
                        if (token.isBlank()) {
                            "Сборки не найдены. Репозиторий закрытый: нужен публичный " +
                                "volta-releases или токен GitHub."
                        } else {
                            "Сборки не найдены: токен не подходит или не видит репозиторий."
                        }
                    )

                    found.versionCode <= BuildConfig.VERSION_CODE -> {
                        // Whatever was downloaded before is installed by now.
                        updateDir().deleteRecursively()
                        UpdateState.UpToDate(now)
                    }

                    verified(found) -> UpdateState.Ready(found)
                    else -> UpdateState.Available(found)
                }
            } catch (e: IOException) {
                UpdateState.Failed("GitHub не отвечает. Проверьте интернет или включите VPN.")
            }
        }
        prefs.setUpdateLastCheck(now)
        _state.value = next
        return next
    }

    /** The button: download what was found (or install what already is) and install it. */
    fun downloadAndInstall() {
        val info = when (val s = _state.value) {
            is UpdateState.Available -> s.info
            is UpdateState.Ready -> s.info
            is UpdateState.Failed -> s.info
            else -> null
        } ?: return
        if (download?.isActive == true) return
        download = scope.launch { fetchAndInstall(info, retry = true) }
    }

    /** True once: the VPN was on when the installer was opened, so turn it back on. */
    suspend fun takeReconnectFlag(): Boolean =
        prefs.reconnectAfterUpdate.first().also { if (it) prefs.setReconnectAfterUpdate(false) }

    private suspend fun fetchAndInstall(info: UpdateInfo, retry: Boolean) {
        val token = prefs.updateToken.first()
        val result = withContext(Dispatchers.IO) {
            runCatching { if (!verified(info)) fetchApk(info, token) }
        }
        val error = result.exceptionOrNull()
        when {
            error == null -> install(info)
            error is CancellationException -> throw error
            // A newer build replaced this one on GitHub meanwhile: take that one.
            error is GoneException && retry -> {
                _state.value = UpdateState.Available(info)
                val next = check()
                if (next is UpdateState.Available) fetchAndInstall(next.info, retry = false)
                else if (next is UpdateState.Ready) install(next.info)
            }

            else -> _state.value = UpdateState.Failed(
                if (error is ChecksumException) "Файл скачался с ошибкой. Попробуйте ещё раз."
                else "Не удалось скачать обновление. Попробуйте ещё раз.",
                info
            )
        }
    }

    /** Hands the APK to Android's installer, first asking for the right to install. */
    private suspend fun install(info: UpdateInfo) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.Ready(info, needsPermission = true)
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return
        }
        _state.value = UpdateState.Ready(info)
        // Installing restarts the app, and the tunnel with it: bring it back afterwards.
        prefs.setReconnectAfterUpdate(VoltaVpnService.state.value == VpnConnectionState.CONNECTED)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apkFile(info))
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            _state.value = UpdateState.Failed("Не удалось открыть установщик Android.", info)
        }
    }

    private fun findNewest(token: String): UpdateInfo? {
        var failure: IOException? = null
        val found = channels(BuildConfig.UPDATE_TAG).mapNotNull { tag ->
            try {
                lookup(tag, token)
            } catch (e: IOException) {
                failure = e
                null
            }
        }
        return found.maxByOrNull { it.versionCode } ?: failure?.let { throw it }
    }

    /** The build on [tag]: from the first repository that has it. */
    private fun lookup(tag: String, token: String): UpdateInfo? {
        for (repo in BuildConfig.UPDATE_REPOS.split(',').filter { it.isNotBlank() }) {
            // Public: plain download links, no API and so no rate limit.
            val base = "https://github.com/$repo/releases/download/$tag"
            fetchText("$base/update.json", token = null, raw = false)?.let { parseManifest(it) }?.let { m ->
                pickApk(m.apks, Build.SUPPORTED_ABIS.toList(), BuildConfig.DEBUG)?.let { apk ->
                    return UpdateInfo(m.versionCode, m.versionName, m.xray, apk, "$base/${apk.name}", viaApi = false)
                }
            }
            if (token.isBlank()) continue
            // Private: the API with the token.
            val release = fetchText(
                "https://api.github.com/repos/$repo/releases/tags/$tag",
                token,
                raw = false
            ) ?: continue
            val assets = parseAssets(release)
            val manifest = assets["update.json"]
                ?.let { fetchText(it, token, raw = true) }
                ?.let { parseManifest(it) } ?: continue
            val apk = pickApk(manifest.apks, Build.SUPPORTED_ABIS.toList(), BuildConfig.DEBUG) ?: continue
            val url = assets[apk.name] ?: continue
            return UpdateInfo(manifest.versionCode, manifest.versionName, manifest.xray, apk, url, viaApi = true)
        }
        return null
    }

    /** The body, or null when GitHub says there's nothing there (or not for us). */
    private fun fetchText(url: String, token: String?, raw: Boolean): String? = route { client ->
        client.newCall(request(url, token, raw)).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.string()
                response.code in NOT_THERE -> null
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    private fun fetchApk(info: UpdateInfo, token: String) {
        val target = apkFile(info)
        // Older downloads are of no use any more.
        updateDir().apply { mkdirs() }.listFiles()?.forEach { it.delete() }
        val part = File(target.path + ".part")
        _state.value = UpdateState.Downloading(info, 0f)
        route { client ->
            client.newCall(request(info.url, token.takeIf { info.viaApi }, raw = info.viaApi))
                .execute().use { response ->
                    if (response.code in NOT_THERE) throw GoneException()
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("empty body")
                    val total = info.apk.size.takeIf { it > 0 } ?: body.contentLength()
                    val digest = MessageDigest.getInstance("SHA-256")
                    var read = 0L
                    var shown = -1
                    body.byteStream().use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                output.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                read += n
                                val percent = if (total > 0) (read * 100 / total).toInt().coerceAtMost(100) else 0
                                if (percent != shown) {
                                    shown = percent
                                    _state.value = UpdateState.Downloading(info, percent / 100f)
                                }
                            }
                        }
                    }
                    if (!hex(digest.digest()).equals(info.apk.sha256, ignoreCase = true)) {
                        part.delete()
                        throw ChecksumException()
                    }
                }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("could not keep the download")
    }

    /** Direct first (Volta is outside its own tunnel); through the tunnel if that fails. */
    private fun <T> route(call: (OkHttpClient) -> T): T {
        val tunnelUp = VoltaVpnService.state.value == VpnConnectionState.CONNECTED
        val first = if (preferTunnel && tunnelUp) tunnel else direct
        return try {
            call(first)
        } catch (e: IOException) {
            if (!tunnelUp) throw e
            val second = if (first === direct) tunnel else direct
            call(second).also { preferTunnel = second === tunnel }
        }
    }

    private fun request(url: String, token: String?, raw: Boolean): Request =
        Request.Builder().url(url).apply {
            header("User-Agent", "Volta/${BuildConfig.VERSION_NAME}")
            if (url.startsWith("https://api.github.com/")) {
                header("Accept", if (raw) "application/octet-stream" else "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                // OkHttp drops it when GitHub redirects the file to its storage host.
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }
        }.build()

    private fun updateDir() = File(context.cacheDir, "update")
    private fun apkFile(info: UpdateInfo) = File(updateDir(), info.apk.name)

    private fun verified(info: UpdateInfo): Boolean {
        val file = apkFile(info)
        if (!file.isFile || file.length() != info.apk.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return hex(digest.digest()).equals(info.apk.sha256, ignoreCase = true)
    }

    private fun notifyAvailable(info: UpdateInfo) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Обновления", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Новые версии Volta" }
        )
        val open = PendingIntent.getActivity(
            context, 20,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_update)
            .setContentTitle("Доступно обновление Volta")
            .setContentText("Версия ${info.versionName}. Нажмите, чтобы установить.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // Without the notification permission on Android 13+ this just isn't shown.
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    /** The build on GitHub was replaced by a newer one: its file is gone. */
    private class GoneException : Exception()

    private class ChecksumException : IOException("checksum mismatch")

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val xray: String,
        val apks: List<UpdateApk>
    )

    companion object {
        private const val CHANNEL_ID = "volta_updates"
        private const val NOTIFICATION_ID = 7102
        private val LAUNCH_EVERY_MS = TimeUnit.MINUTES.toMillis(30)
        private val BACKGROUND_EVERY_MS = TimeUnit.HOURS.toMillis(6)

        /** 404 for a missing release; 401/403 for a private one without a good token. */
        private val NOT_THERE = setOf(401, 403, 404)

        /** The branch this build came from, then main, which every branch ends up in. */
        internal fun channels(own: String): List<String> = listOf(own, "apk-main").distinct()

        internal fun parseManifest(json: String): Manifest? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            Manifest(
                versionCode = root.get("versionCode").asInt,
                versionName = root.get("versionName")?.asString.orEmpty(),
                xray = root.get("xray")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                apks = root.getAsJsonArray("apks").map { item ->
                    val o = item.asJsonObject
                    UpdateApk(
                        abi = o.get("abi").asString,
                        name = o.get("name").asString,
                        size = o.get("size")?.asLong ?: 0L,
                        sha256 = o.get("sha256")?.asString.orEmpty()
                    )
                }
            )
        }.getOrNull()

        /** Asset name to its API download URL, from a release as the API returns it. */
        internal fun parseAssets(json: String): Map<String, String> = runCatching {
            JsonParser.parseString(json).asJsonObject.getAsJsonArray("assets").associate {
                val o = it.asJsonObject
                o.get("name").asString to o.get("url").asString
            }
        }.getOrDefault(emptyMap())

        /**
         * The APK for this phone: its own CPU type, the debug flavour for a debug build
         * where there is one, the universal APK as the last resort. An APK without a
         * checksum is never taken, since nothing could tell a broken download from it.
         */
        internal fun pickApk(apks: List<UpdateApk>, abis: List<String>, debug: Boolean): UpdateApk? {
            val usable = apks.filter { it.sha256.length == 64 && it.size > 0 }
            for (abi in abis) {
                if (debug) usable.firstOrNull { it.abi == "$abi-debug" }?.let { return it }
                usable.firstOrNull { it.abi == abi }?.let { return it }
            }
            return usable.firstOrNull { it.abi == "universal" }
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
