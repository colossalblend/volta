package com.ars3nb.volta.desktop.update

import com.ars3nb.volta.desktop.i18n.R
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The installer of a Windows build, as the build's windows-update.json lists it.
 * [silentArgs]: how to run it unattended as an update (Volta's own installer: `--silent
 * --update --launch`, it closes Volta, keeps %APPDATA%\Volta and starts the new one);
 * empty for an installer that only knows its own window.
 */
data class UpdateAsset(val name: String, val size: Long, val sha256: String, val silentArgs: List<String> = emptyList())

/** A build on GitHub and its installer. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val xray: String,
    val asset: UpdateAsset,
    /** A plain download link of the release asset: public, no key, no API. */
    val url: String
)

/** The same states as the Android updater (core/update/AppUpdater.kt). */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAt: Long) : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState

    /** Downloaded and checked; the installer is starting (or could not be started). */
    data class Ready(val info: UpdateInfo, val needsPermission: Boolean = false) : UpdateState

    /** [message]: what went wrong, a text key (R.string). */
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

/**
 * Updates from the repository's GitHub Releases, the way the Android app updates itself.
 * Every Windows build (windows.yml) puts a windows-update.json next to its installer:
 * the build number (100 + the run number, as the APKs count), the version, and the
 * installer's name, size and SHA-256. Volta reads it from the newest published release
 * through GitHub's plain download link (`releases/latest/download/…`), and with
 * «Бета-версии» also from the main branch's rolling pre-release `apk-main`; whichever is
 * newer wins. A newer build's installer is downloaded, its SHA-256 checked, started, and
 * Volta quits so the installer can replace it. Requests go directly first and, only if
 * that fails while the VPN is on, once more through Volta's own proxy.
 *
 * [currentCode] is this build's number (0 for a build made by hand, which then takes any
 * published build). [tunnelPort] gives the running tunnel's HTTP port, or null.
 */
class AppUpdater(
    private val scope: CoroutineScope,
    private val downloadDir: File,
    private val repo: String = DEFAULT_REPO,
    /** Where releases are served; a local server in the self-test. */
    private val site: String = SITE,
    private val currentCode: Int = currentVersionCode(),
    private val userAgent: String = "Volta/Windows",
    private val tunnelPort: () -> Int? = { null },
    private val beta: () -> Boolean = { false },
    /** Starts the installer (with these arguments) and quits Volta; false when it could not be started. */
    private val launchInstaller: (File, List<String>) -> Boolean = { _, _ -> false }
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var download: Job? = null

    @Volatile
    var lastCheck: Long = 0
        private set

    /** For the self-test's pictures: show [s] as if it had been found. */
    internal fun pretend(s: UpdateState) {
        _state.value = s
    }

    /** On start-up and then now and then: at most every [every] ms. */
    suspend fun checkIfDue(every: Long = LAUNCH_EVERY_MS): UpdateState {
        val recent = System.currentTimeMillis() - lastCheck < every
        if (_state.value is UpdateState.Idle || !recent) return check()
        return _state.value
    }

    /** Forget what was found (the channel changed) and look again. */
    suspend fun recheck(): UpdateState {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
        return check()
    }

    suspend fun check(): UpdateState {
        val current = _state.value
        if (current is UpdateState.Checking || current is UpdateState.Downloading) return current
        _state.value = UpdateState.Checking
        val now = System.currentTimeMillis()
        val next = try {
            withContext(Dispatchers.IO) {
                val found = findNewest()
                when {
                    found == null -> UpdateState.UpToDate(now)
                    found.versionCode <= currentCode -> {
                        downloadDir.deleteRecursively()
                        UpdateState.UpToDate(now)
                    }

                    verified(found) -> UpdateState.Ready(found)
                    else -> UpdateState.Available(found)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Whoever asked went away mid-check. Left at Checking, every later check would
            // think one is still running and never look again (as fixed on Android).
            if (_state.value is UpdateState.Checking) _state.value = current
            throw e
        } catch (e: Exception) {
            // Not only network errors: a malformed answer must not leave it at Checking.
            UpdateState.Failed(R.string.settings_update_error_check)
        }
        lastCheck = now
        _state.value = next
        return next
    }

    /** The button: download what was found (or start what already is) and install it. */
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

    private suspend fun fetchAndInstall(info: UpdateInfo, retry: Boolean) {
        val result = withContext(Dispatchers.IO) { runCatching { if (!verified(info)) fetch(info) } }
        val error = result.exceptionOrNull()
        when {
            error == null -> install(info)
            error is CancellationException -> throw error
            // A newer build replaced this one on GitHub meanwhile: take that one.
            error is GoneException && retry -> {
                _state.value = UpdateState.Available(info)
                when (val next = check()) {
                    is UpdateState.Available -> fetchAndInstall(next.info, retry = false)
                    is UpdateState.Ready -> install(next.info)
                    else -> Unit
                }
            }

            else -> _state.value = UpdateState.Failed(
                if (error is ChecksumException) R.string.settings_update_error_corrupt
                else R.string.settings_update_error_download,
                info
            )
        }
    }

    private fun install(info: UpdateInfo) {
        _state.value = UpdateState.Ready(info)
        val ok = runCatching { launchInstaller(file(info), info.asset.silentArgs) }.getOrDefault(false)
        if (!ok) _state.value = UpdateState.Failed(R.string.settings_update_error_installer, info)
    }

    private fun findNewest(): UpdateInfo? {
        val stable = fetchText(latestUrl(repo, MANIFEST, site))?.let(::parseManifest)
        val test = if (beta()) {
            // A missing or broken test build must not hide a good release.
            runCatching { fetchText(betaUrl(repo, MANIFEST, site))?.let(::parseManifest) }.getOrNull()
        } else null
        val manifest = listOfNotNull(stable, test).maxByOrNull { it.versionCode } ?: return null
        val asset = manifest.installer ?: return null
        return UpdateInfo(
            versionCode = manifest.versionCode,
            versionName = manifest.versionName,
            xray = manifest.xray,
            asset = asset,
            url = assetUrl(repo, manifest.tag, asset.name, site)
        )
    }

    private fun fetchText(url: String): String? = route { proxy ->
        val c = open(url, proxy)
        try {
            when (val code = c.responseCode) {
                in 200..299 -> c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                in NOT_THERE -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    private fun fetch(info: UpdateInfo) {
        val target = file(info)
        downloadDir.mkdirs()
        downloadDir.listFiles()?.forEach { it.delete() }
        val part = File(target.path + ".part")
        _state.value = UpdateState.Downloading(info, 0f)
        route { proxy ->
            val c = open(info.url, proxy)
            try {
                val code = c.responseCode
                if (code in NOT_THERE) throw GoneException()
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = info.asset.size.takeIf { it > 0 } ?: c.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var shown = -1
                c.inputStream.use { input ->
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
                if (!hex(digest.digest()).equals(info.asset.sha256, ignoreCase = true)) {
                    part.delete()
                    throw ChecksumException()
                }
            } finally {
                c.disconnect()
            }
        }
        target.delete()
        if (!part.renameTo(target)) throw IOException("could not keep the download")
    }

    /** Directly first, then (only if that failed and the VPN is on) through Volta's proxy. */
    private fun <T> route(call: (Proxy) -> T): T = try {
        call(Proxy.NO_PROXY)
    } catch (e: IOException) {
        if (e is GoneException || e is ChecksumException) throw e
        val port = tunnelPort() ?: throw e
        call(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port)))
    }

    private fun open(url: String, proxy: Proxy): HttpURLConnection =
        (URI(url).toURL().openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
        }

    private fun file(info: UpdateInfo) = File(downloadDir, info.asset.name)

    private fun verified(info: UpdateInfo): Boolean {
        val f = file(info)
        if (!f.isFile || f.length() != info.asset.size) return false
        return sha256(f).equals(info.asset.sha256, ignoreCase = true)
    }

    private class GoneException : IOException("gone")
    private class ChecksumException : IOException("checksum mismatch")

    data class Manifest(
        val versionCode: Int,
        val versionName: String,
        val xray: String,
        val tag: String,
        val installer: UpdateAsset?
    )

    companion object {
        const val DEFAULT_REPO = "colossalblend/volta"
        const val MANIFEST = "windows-update.json"

        /** The rolling pre-release CI keeps for the main branch (as on Android). */
        const val BETA_TAG = "apk-main"

        val LAUNCH_EVERY_MS: Long = TimeUnit.MINUTES.toMillis(30)
        val BACKGROUND_EVERY_MS: Long = TimeUnit.HOURS.toMillis(6)

        private val NOT_THERE = setOf(401, 403, 404)

        /** The build number the packaged app was made with (-Dvolta.versionCode). */
        fun currentVersionCode(): Int = System.getProperty("volta.versionCode")?.toIntOrNull() ?: 0

        const val SITE = "https://github.com"

        fun betaUrl(repo: String, file: String, site: String = SITE) = "$site/$repo/releases/download/$BETA_TAG/$file"
        fun latestUrl(repo: String, file: String, site: String = SITE) = "$site/$repo/releases/latest/download/$file"

        /** Pinned to the tag when the manifest names one, so the file can't change mid-download. */
        fun assetUrl(repo: String, tag: String, file: String, site: String = SITE) =
            if (tag.isBlank()) latestUrl(repo, file, site) else "$site/$repo/releases/download/$tag/$file"

        /**
         * windows-update.json: `versionCode`, `versionName`, `tag`, `xray`, and the
         * installer as `installer: {name, size, sha256, silent}` (`silent`: the arguments
         * for an unattended update, absent for an older build's installer). An installer without a proper
         * checksum is never taken: nothing could tell a broken download from it.
         */
        fun parseManifest(json: String): Manifest? = runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val installer = root.get("installer")?.takeIf { it.isJsonObject }?.asJsonObject?.let { o ->
                val silent = o.get("silent")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }
                    ?.filter { it.startsWith("-") || it.startsWith("/") }
                    .orEmpty()
                UpdateAsset(o.str("name"), o.get("size")?.asLong ?: 0L, o.str("sha256"), silent)
            }?.takeIf { it.name.endsWith(".exe", true) && it.sha256.length == 64 && it.size > 0 }
            Manifest(
                versionCode = root.get("versionCode").asInt,
                versionName = root.str("versionName"),
                xray = root.str("xray"),
                tag = root.str("tag"),
                installer = installer
            )
        }.getOrNull()

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return hex(digest.digest())
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
