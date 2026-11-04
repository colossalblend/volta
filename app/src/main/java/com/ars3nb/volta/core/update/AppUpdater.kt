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
import kotlinx.coroutines.flow.combine
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
    /** A plain download link of the release asset: public, no key, no API. */
    val url: String
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
 * Updates from the repository's GitHub Releases. A release (made by CI from a `v…` tag)
 * carries an update.json naming its build number and the APK for each CPU type. The app
 * reads that file from the latest release through GitHub's plain download link —
 * `releases/latest/download/update.json`, which always points at the newest published
 * release and never at a pre-release or a branch — so no key, no API and no rate limit.
 * When the release is newer than the app, the APK for this phone is downloaded, its
 * SHA-256 checked and handed to Android's installer. Every build is signed with the same
 * key, so it installs over.
 *
 * Builds for Google Play have no updater at all ([BuildConfig.OTA] is false): there the
 * store updates the app, and an app may not update itself around it.
 */
class AppUpdater(private val context: Context, private val scope: CoroutineScope) {

    private val prefs = AppPreferences(context)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * «Проверка обновлений»: on, Volta looks on launch and in the background and sends a
     * notification; off, it never looks by itself, but the «Проверить» button still works.
     * The old «Обновления из приложения» switch (1.1.30 only) counts as off here too.
     */
    val autoCheck = combine(prefs.updateAutoCheck, prefs.otaEnabled) { auto, legacy -> auto && legacy }

    /** False in the Google Play build: the store does the updating. */
    val enabled: Boolean get() = BuildConfig.OTA

    private var download: Job? = null

    private val direct = OkHttpClient.Builder()
        // Short, so a blocked GitHub falls through to the tunnel quickly.
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** Through the running tunnel, for networks that let only a whitelist through. */
    private val tunnel: OkHttpClient by lazy {
        direct.newBuilder()
            .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", VoltaRepository.LOCAL_HTTP_PORT)))
            .build()
    }

    /** «Бета-версии»: also the test build of the main branch, whichever is newer. */
    val beta = prefs.updateBeta

    suspend fun setBeta(enabled: Boolean) {
        prefs.setUpdateBeta(enabled)
        // Forget what was found on the other channel and look again.
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
        check()
    }

    suspend fun setAutoCheck(enabled: Boolean) {
        prefs.setUpdateAutoCheck(enabled)
        if (enabled) {
            prefs.setOtaEnabled(true)
            check()
        } else {
            // Nothing announced while checks are off.
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    /** On opening the app: once per process, then at most every half hour. */
    suspend fun checkOnLaunch() {
        if (!enabled || !autoCheck.first()) return
        val recent = System.currentTimeMillis() - prefs.updateLastCheck.first() < LAUNCH_EVERY_MS
        if (_state.value is UpdateState.Idle || !recent) check()
    }

    /** From the hourly worker: every few hours, and a notification once per new build. */
    suspend fun checkInBackground() {
        if (!enabled || !autoCheck.first()) return
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
        if (!enabled) return UpdateState.Idle
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return current
        _state.value = UpdateState.Checking
        val now = System.currentTimeMillis()
        val next = withContext(Dispatchers.IO) {
            try {
                val found = findNewest()
                when {
                    // Nothing published yet: nothing to update to, and nothing wrong.
                    found == null -> UpdateState.UpToDate(now)

                    found.versionCode <= BuildConfig.VERSION_CODE -> {
                        // Whatever was downloaded before is installed by now.
                        updateDir().deleteRecursively()
                        UpdateState.UpToDate(now)
                    }

                    verified(found) -> UpdateState.Ready(found)
                    else -> UpdateState.Available(found)
                }
            } catch (e: IOException) {
                UpdateState.Failed("Не получилось проверить обновления. Проверьте интернет или включите VPN.")
            }
        }
        prefs.setUpdateLastCheck(now)
        _state.value = next
        return next
    }

    /** The button: download what was found (or install what already is) and install it. */
    fun downloadAndInstall() {
        if (!enabled) return
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
        val result = withContext(Dispatchers.IO) {
            runCatching { if (!verified(info)) fetchApk(info) }
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

    /**
     * The latest published release, or null when there is none yet. With «Бета-версии»
     * on, also the main branch's rolling test build, and whichever is newer wins. Every
     * build is signed with the same key and numbered by one counter, so either installs
     * over the other.
     */
    private suspend fun findNewest(): UpdateInfo? {
        val repo = BuildConfig.UPDATE_REPO
        val stable = fetchText(latestUrl(repo, "update.json"))?.let { parseManifest(it) }
        val beta = if (prefs.updateBeta.first()) {
            // A missing or broken test build must not hide a good release.
            runCatching { fetchText(betaUrl(repo, "update.json"))?.let { parseManifest(it) } }.getOrNull()
        } else {
            null
        }
        val manifest = listOfNotNull(stable, beta).maxByOrNull { it.versionCode } ?: return null
        val apk = pickApk(manifest.apks, Build.SUPPORTED_ABIS.toList(), BuildConfig.DEBUG) ?: return null
        return UpdateInfo(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            xray = manifest.xray,
            apk = apk,
            url = assetUrl(repo, manifest.tag, apk.name)
        )
    }

    /** The body, or null when GitHub says there's nothing there (or not for us). */
    private fun fetchText(url: String): String? = route { client ->
        client.newCall(request(url)).execute().use { response ->
            when {
                response.isSuccessful -> response.body?.string()
                response.code in NOT_THERE -> null
                else -> throw IOException("HTTP ${response.code}")
            }
        }
    }

    private fun fetchApk(info: UpdateInfo) {
        val target = apkFile(info)
        // Older downloads are of no use any more.
        updateDir().apply { mkdirs() }.listFiles()?.forEach { it.delete() }
        val part = File(target.path + ".part")
        _state.value = UpdateState.Downloading(info, 0f)
        route { client ->
            client.newCall(request(info.url))
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

    /**
     * Always directly first (Volta is outside its own tunnel), then — only if that failed
     * and the VPN is on — once more through the tunnel, for networks where GitHub is
     * blocked or only a whitelist gets through.
     */
    private fun <T> route(call: (OkHttpClient) -> T): T {
        return try {
            call(direct)
        } catch (e: IOException) {
            if (VoltaVpnService.state.value != VpnConnectionState.CONNECTED) throw e
            call(tunnel)
        }
    }

    private fun request(url: String): Request =
        Request.Builder().url(url).header("User-Agent", "Volta/${BuildConfig.VERSION_NAME}").build()

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
        val apks: List<UpdateApk>,
        /** The release's tag, so the APK is fetched from this very release. */
        val tag: String = ""
    )

    companion object {
        private const val CHANNEL_ID = "volta_updates"
        private const val NOTIFICATION_ID = 7102
        private val LAUNCH_EVERY_MS = TimeUnit.MINUTES.toMillis(30)
        private val BACKGROUND_EVERY_MS = TimeUnit.HOURS.toMillis(6)

        /** No release published yet (404), or the repository went private (401/403). */
        private val NOT_THERE = setOf(401, 403, 404)

        /** The rolling pre-release CI keeps for the main branch (android.yml: apk-<branch>). */
        internal const val BETA_TAG = "apk-main"

        /** A file of the main branch's test build. */
        internal fun betaUrl(repo: String, file: String): String =
            "https://github.com/$repo/releases/download/$BETA_TAG/$file"

        /** GitHub's link to a file of the newest published (non-pre-)release. */
        internal fun latestUrl(repo: String, file: String): String =
            "https://github.com/$repo/releases/latest/download/$file"

        /**
         * The APK's own link in its release. Pinned to the tag when the manifest names
         * one, so a release published mid-download can't swap the file under the checksum.
         */
        internal fun assetUrl(repo: String, tag: String, file: String): String =
            if (tag.isBlank()) latestUrl(repo, file)
            else "https://github.com/$repo/releases/download/$tag/$file"

        internal fun parseManifest(json: String): Manifest? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            Manifest(
                versionCode = root.get("versionCode").asInt,
                versionName = root.get("versionName")?.asString.orEmpty(),
                xray = root.get("xray")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                tag = root.get("tag")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
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
