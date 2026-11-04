package com.ars3nb.volta.desktop.core

import com.ars3nb.volta.desktop.AppPaths
import com.ars3nb.volta.desktop.platform.WinApi
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The bundled Xray core: where it is, which version, and its geo lists copied into the
 * writable asset folder (where subscriptions may add lists of their own).
 */
class XrayCore(
    val exe: File,
    private val bundledDir: File,
    val assetDir: File
) {
    val isPresent: Boolean get() = exe.isFile

    @Volatile
    private var cachedVersion: String? = null

    /** "26.7.28", read from `xray version`; empty when the core can't be run. */
    fun version(): String {
        cachedVersion?.let { return it }
        val v = runCatching {
            val p = ProcessBuilder(exe.absolutePath, "version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor(10, TimeUnit.SECONDS)
            Regex("Xray\\s+([0-9][0-9A-Za-z.\\-]*)").find(out)?.groupValues?.get(1).orEmpty()
        }.getOrDefault("")
        if (v.isNotBlank()) cachedVersion = v
        return v
    }

    /** Copies geoip.dat / geosite.dat next to any per-subscription lists, when they changed. */
    fun prepareAssets() {
        assetDir.mkdirs()
        for (name in listOf("geoip.dat", "geosite.dat")) {
            val src = File(bundledDir, name)
            if (!src.isFile) continue
            val dst = File(assetDir, name)
            if (dst.isFile && dst.length() == src.length() && dst.lastModified() >= src.lastModified()) continue
            runCatching {
                val tmp = File(assetDir, "$name.part")
                src.copyTo(tmp, overwrite = true)
                if (!tmp.renameTo(dst)) {
                    dst.delete()
                    tmp.renameTo(dst)
                }
            }
        }
    }

    /**
     * `xray run -test`: null when the core accepts [configFile], otherwise its complaint.
     */
    fun test(configFile: File): String? = runCatching {
        val p = processBuilder(listOf("run", "-test", "-c", configFile.absolutePath))
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return "timeout"
        }
        if (out.contains("Configuration OK")) null else out.trim().lines().takeLast(3).joinToString("\n")
    }.getOrElse { it.message ?: it.toString() }

    fun processBuilder(args: List<String>): ProcessBuilder =
        ProcessBuilder(listOf(exe.absolutePath) + args).apply {
            directory(exe.parentFile)
            environment()["XRAY_LOCATION_ASSET"] = assetDir.absolutePath
        }

    /** Starts `xray run -c [configFile]`, its output going to [log]. Tied to Volta's lifetime. */
    fun start(configFile: File, log: File): Process {
        log.parentFile?.mkdirs()
        val process = processBuilder(listOf("run", "-c", configFile.absolutePath))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
            .start()
        WinApi.bindToAppLifetime(process.pid())
        return process
    }

    companion object {
        fun from(paths: AppPaths): XrayCore {
            val dir = AppPaths.resourcesDir()
            return XrayCore(AppPaths.xrayExe(dir), dir, paths.assetDir)
        }
    }
}

object Ports {
    /** [preferred] when nothing listens there, else a free port Windows picks. */
    fun pick(preferred: Int, avoid: Set<Int> = emptySet()): Int {
        if (preferred in 1..65535 && preferred !in avoid && isFree(preferred)) return preferred
        repeat(20) {
            val p = ephemeral()
            if (p !in avoid) return p
        }
        return ephemeral()
    }

    fun pickMany(count: Int, avoid: Set<Int> = emptySet()): List<Int> {
        val out = LinkedHashSet<Int>()
        // Held open together so Windows can't hand out the same one twice.
        val held = mutableListOf<ServerSocket>()
        try {
            while (out.size < count) {
                val s = ServerSocket()
                s.reuseAddress = false
                s.bind(InetSocketAddress("127.0.0.1", 0))
                held += s
                if (s.localPort !in avoid) out += s.localPort
            }
        } finally {
            held.forEach { runCatching { it.close() } }
        }
        return out.toList()
    }

    fun isFree(port: Int): Boolean = runCatching {
        ServerSocket().use { s ->
            s.reuseAddress = false
            s.bind(InetSocketAddress("127.0.0.1", port))
        }
        true
    }.getOrDefault(false)

    private fun ephemeral(): Int = ServerSocket().use { s ->
        s.reuseAddress = false
        s.bind(InetSocketAddress("127.0.0.1", 0))
        s.localPort
    }

    fun isListening(port: Int, timeoutMs: Int = 300): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), timeoutMs) }
        true
    }.getOrDefault(false)

    /** Waits until every port accepts connections, the process dies, or [timeoutMs] passes. */
    fun awaitListening(ports: List<Int>, process: Process?, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process != null && !process.isAlive) return false
            if (ports.all { isListening(it) }) return true
            Thread.sleep(60)
        }
        return false
    }
}

/** The last lines of a log, for an error message. */
fun File.tailLines(n: Int): String = runCatching {
    if (!isFile) return ""
    val bytes = readBytes()
    val text = String(bytes, maxOf(0, bytes.size - 8192), minOf(bytes.size, 8192), Charsets.UTF_8)
    text.trim().lines().filter { it.isNotBlank() }.takeLast(n).joinToString("\n")
}.getOrDefault("")
