package com.ars3nb.volta.desktop

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * One Volta at a time: two would fight over the proxy setting and the ports. A second
 * start (a click on the shortcut, a volta:// link opened in the browser) hands its
 * arguments to the running one, which shows its window, and quits.
 */
object SingleInstance {

    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** True when this process is the one Volta; [onMessage] then gets later starts' arguments. */
    fun acquire(paths: AppPaths, onMessage: (List<String>) -> Unit): Boolean {
        paths.dataDir.mkdirs()
        val lockFile = File(paths.dataDir, "instance.lock")
        val channel = runCatching { RandomAccessFile(lockFile, "rw").channel }.getOrNull() ?: return true
        val acquired = runCatching { channel.tryLock() }.getOrNull() ?: return false
        lock = acquired
        runCatching {
            val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            server = socket
            File(paths.dataDir, "instance.port").writeText(socket.localPort.toString())
            thread(isDaemon = true, name = "volta-instance") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        client.use { c ->
                            c.soTimeout = 3_000
                            val lines = c.getInputStream().bufferedReader().readLines()
                            if (lines.firstOrNull() == MAGIC) onMessage(lines.drop(1))
                        }
                    }
                }
            }
        }
        return true
    }

    /** Hands [args] to the Volta that is already running. */
    fun send(paths: AppPaths, args: List<String>): Boolean = runCatching {
        val port = File(paths.dataDir, "instance.port").readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().bufferedWriter().use { w ->
                w.write(MAGIC)
                w.write("\n")
                args.forEach { w.write(it.replace("\n", " ")); w.write("\n") }
            }
        }
        true
    }.getOrDefault(false)

    private const val MAGIC = "volta-instance-v1"
}
