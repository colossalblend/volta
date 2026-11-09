package com.ars3nb.volta.desktop.update

import com.ars3nb.volta.desktop.i18n.R
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * The whole update path against a release site on this machine, laid out as GitHub's:
 * `latest/download/windows-update.json`, the `apk-main` pre-release with a newer test
 * build, and the installers. Used by the unit tests and by the packaged app's self-test,
 * so what ships is known to find, download, check and start an update, and to refuse a
 * damaged download.
 */
object UpdateDrill {

    /** A short report; throws when anything is off. */
    fun run(work: File): String {
        val repo = "volta/test"
        val good = ByteArray(300_000) { (it * 31 % 251).toByte() }
        val sha = MessageDigest.getInstance("SHA-256").digest(good).joinToString("") { "%02x".format(it) }
        fun manifest(code: Int, tag: String, name: String, hash: String) =
            """{"versionCode":$code,"versionName":"1.1.$code","tag":"$tag","xray":"v0",""" +
                """"installer":{"name":"$name","size":${good.size},"sha256":"$hash"}}"""
        val files = mapOf(
            "/$repo/releases/latest/download/windows-update.json" to manifest(500, "v1.1.500", "stable-setup.exe", sha).toByteArray(),
            "/$repo/releases/download/apk-main/windows-update.json" to manifest(501, "apk-main", "beta-setup.exe", sha).toByteArray(),
            "/$repo/releases/download/apk-main/beta-setup.exe" to good,
            "/$repo/releases/download/v1.1.500/stable-setup.exe" to good,
            "/bad/releases/latest/download/windows-update.json" to manifest(600, "v9", "bad-setup.exe", "0".repeat(64)).toByteArray(),
            "/bad/releases/download/v9/bad-setup.exe" to good
        )
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = files[ex.requestURI.path]
            if (body == null) {
                ex.sendResponseHeaders(404, -1)
            } else {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        server.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val site = "http://127.0.0.1:${server.address.port}"
            var started: File? = null
            var beta = false
            val updater = AppUpdater(
                scope = scope,
                downloadDir = File(work, "update"),
                repo = repo,
                site = site,
                currentCode = 400,
                beta = { beta },
                launchInstaller = { f, _ -> started = f; true }
            )
            // Stable only: the published release.
            val stable = runBlocking { updater.check() }
            require(stable is UpdateState.Available && stable.info.versionCode == 500) { "stable: $stable" }
            // «Бета-версии»: the newer test build of main wins.
            beta = true
            val found = runBlocking { updater.recheck() }
            require(found is UpdateState.Available && found.info.versionCode == 501) { "beta: $found" }
            updater.downloadAndInstall()
            require(waitFor { started != null || updater.state.value is UpdateState.Failed }) { "no install: ${updater.state.value}" }
            val file = started ?: error("download failed: ${updater.state.value}")
            require(AppUpdater.sha256(file) == sha && updater.state.value is UpdateState.Ready) { "${updater.state.value}" }

            // Already this build: nothing to do.
            val same = AppUpdater(scope, File(work, "same"), repo, site, currentCode = 501, beta = { true })
            require(runBlocking { same.check() } is UpdateState.UpToDate) { "up to date expected" }

            // A file that doesn't match its checksum is never started.
            var badStarted = false
            val bad = AppUpdater(scope, File(work, "bad"), "bad", site, currentCode = 1, launchInstaller = { _, _ -> badStarted = true; true })
            require(runBlocking { bad.check() } is UpdateState.Available) { "bad manifest not offered" }
            bad.downloadAndInstall()
            require(waitFor { bad.state.value is UpdateState.Failed }) { "bad: ${bad.state.value}" }
            require(!badStarted && (bad.state.value as UpdateState.Failed).message == R.string.settings_update_error_corrupt)
            return "found 500, beta 501, ${good.size} B checked and started, damaged file refused"
        } finally {
            scope.cancel()
            server.stop(0)
        }
    }

    private fun waitFor(timeoutMs: Long = 15_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
