package com.ars3nb.volta.desktop

import com.ars3nb.volta.desktop.platform.WinApi
import java.io.File
import java.security.MessageDigest

/**
 * Where Volta keeps things. The app folder (with the bundled core) is read-only as far as
 * Volta is concerned; everything it writes goes under [dataDir], %APPDATA%\Volta.
 */
class AppPaths(val dataDir: File) {

    val stateFile = File(dataDir, "volta.json")
    val proxyBackup = File(dataDir, "proxy-backup.json")
    val runDir = File(dataDir, "run")
    val logDir = File(dataDir, "logs")

    /** Xray's asset folder: the bundled geo lists plus any a subscription brings. */
    val assetDir = File(dataDir, "assets")

    val coreLog = File(logDir, "xray.log")
    val appLog = File(logDir, "volta.log")
    val corePid = File(runDir, "xray.pid")

    fun ensure() {
        listOf(dataDir, runDir, logDir, assetDir).forEach { it.mkdirs() }
    }

    companion object {
        fun default(): AppPaths {
            val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let(::File)
                ?: File(System.getProperty("user.home"), if (WinApi.isWindows) "AppData/Roaming" else ".config")
            return AppPaths(File(base, "Volta"))
        }

        /**
         * The folder with xray.exe, geoip.dat and geosite.dat: next to the app when
         * installed (Compose puts it in compose.application.resources.dir), the source
         * tree's resources/windows-x64 when run from Gradle.
         */
        fun resourcesDir(): File {
            System.getProperty("compose.application.resources.dir")?.let { File(it) }
                ?.takeIf { it.isDirectory }?.let { return it }
            val dev = listOf("resources/windows-x64", "desktop/resources/windows-x64")
                .map(::File).firstOrNull { it.isDirectory }
            return dev ?: File("resources/windows-x64")
        }

        fun xrayExe(dir: File = resourcesDir()): File =
            File(dir, if (WinApi.isWindows) "xray.exe" else "xray")

        val appVersion: String
            get() = System.getProperty("volta.version")?.takeIf { it.isNotBlank() } ?: "1.1.0-dev"
    }
}

/** A stable per-installation id for providers that limit devices (sent only when allowed). */
object Hwid {
    private const val SALT = "volta-hwid-v1"

    @Volatile
    private var cached: String? = null

    fun get(paths: AppPaths): String {
        cached?.let { return it }
        val raw = WinApi.machineGuid()
            ?: fallback(paths)
        val id = MessageDigest.getInstance("SHA-256")
            .digest("$SALT:$raw".toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)
        cached = id
        return id
    }

    private fun fallback(paths: AppPaths): String {
        val file = File(paths.dataDir, "device-id")
        return runCatching { file.readText().trim() }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().also { id ->
                runCatching { paths.dataDir.mkdirs(); file.writeText(id) }
            }
    }
}
