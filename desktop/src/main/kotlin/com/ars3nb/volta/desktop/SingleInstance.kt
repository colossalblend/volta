package com.ars3nb.volta.desktop

import com.ars3nb.volta.desktop.platform.WinWindow
import java.io.File
import java.io.RandomAccessFile
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/**
 * One Volta at a time: two would fight over the proxy setting, the ports and volta.json.
 * A second start (a click on the shortcut, a volta:// link opened in the browser) hands
 * its arguments to the running one, which shows its window, and quits.
 *
 * The running one listens on a loopback port (instance.port) and records its process id
 * (instance.pid). Protocol v2: the caller sends [MAGIC], its arguments one per line and
 * [END]; the running Volta answers `ok` once its UI thread has taken the message (or
 * `busy` when the UI did not respond in time). No answer within [ACK_TIMEOUT_MS] means
 * the running Volta is hung: the caller can then offer to end it. `--quit` asks it to
 * quit the way «Выход» does (tunnel down, proxy restored); the installer uses that.
 * A v1 Volta (no answer, just closes the connection) is still understood both ways.
 */
object SingleInstance {

    enum class Sent {
        /** The running Volta took it. */
        ACKED,

        /** An older Volta read it and closed without an answer: it is alive. */
        OLD_PEER,

        /** Connected, but no answer in time, or the UI is stuck: hung. */
        NO_ANSWER,

        /** Nobody listens on the recorded port (or there is none). */
        NOT_LISTENING
    }

    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    private fun lockFile(paths: AppPaths) = File(paths.dataDir, "instance.lock")
    private fun portFile(paths: AppPaths) = File(paths.dataDir, "instance.port")
    private fun pidFile(paths: AppPaths) = File(paths.dataDir, "instance.pid")

    /** True when this process now is the one Volta. */
    @Synchronized
    fun acquire(paths: AppPaths): Boolean {
        if (lock != null) return true
        paths.dataDir.mkdirs()
        // A file that can't even be opened (antivirus, odd permissions): try a moment later,
        // and never just assume we're alone, as two Voltas would overwrite each other's data.
        var ch: FileChannel? = null
        for (attempt in 0 until 5) {
            ch = runCatching { RandomAccessFile(lockFile(paths), "rw").channel }.getOrNull()
            if (ch != null) break
            Thread.sleep(200)
        }
        if (ch == null) {
            // No lock to be had: being alone is decided by whether anybody answers.
            return send(paths, emptyList(), probeOnly = true) == Sent.NOT_LISTENING
        }
        val acquired = runCatching { ch.tryLock() }.getOrNull()
        if (acquired == null) {
            runCatching { ch.close() }
            return false
        }
        channel = ch
        lock = acquired
        runCatching { pidFile(paths).writeText(ProcessHandle.current().pid().toString()) }
        return true
    }

    /**
     * Starts answering later starts. [onMessage] gets their arguments on this listener's
     * thread and returns true once the UI has taken them (false: the UI is stuck).
     */
    fun listen(paths: AppPaths, onMessage: (List<String>) -> Boolean) {
        runCatching {
            val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            server = socket
            portFile(paths).writeText(socket.localPort.toString())
            thread(isDaemon = true, name = "volta-instance") {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    // One at a time is plenty; a slow client can't hold the others for long.
                    runCatching { serve(client, onMessage) }
                }
            }
        }.onFailure { StartupLog.line("instance: could not listen: $it") }
    }

    private fun serve(client: Socket, onMessage: (List<String>) -> Boolean) {
        client.use { c ->
            c.soTimeout = 3_000
            val reader = c.getInputStream().bufferedReader(Charsets.UTF_8)
            when (reader.readLine()) {
                MAGIC_V1 -> onMessage(reader.readLines())
                MAGIC -> {
                    val args = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line == END) break
                        args += line
                    }
                    val probe = args.firstOrNull() == PROBE
                    val ok = probe || onMessage(args)
                    c.getOutputStream().apply {
                        write((if (ok) "ok\n" else "busy\n").toByteArray(Charsets.UTF_8))
                        flush()
                    }
                }
            }
        }
    }

    /** Hands [args] to the Volta that is already running and says how that went. */
    fun send(paths: AppPaths, args: List<String>, probeOnly: Boolean = false): Sent {
        val port = runCatching { portFile(paths).readText().trim().toInt() }.getOrNull() ?: return Sent.NOT_LISTENING
        // The running Volta may take the foreground: this start has it, and gives it away.
        if (!probeOnly) WinWindow.allowForeground(ownerPid(paths))
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                s.soTimeout = ACK_TIMEOUT_MS
                val out = s.getOutputStream()
                val text = buildString {
                    append(MAGIC).append('\n')
                    if (probeOnly) append(PROBE).append('\n')
                    else args.forEach { append(it.replace('\n', ' ').replace('\r', ' ')).append('\n') }
                    append(END).append('\n')
                }
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { s.shutdownOutput() }
                val answer = s.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                when (answer?.trim()) {
                    "ok" -> Sent.ACKED
                    null -> Sent.OLD_PEER
                    else -> Sent.NO_ANSWER
                }
            }
        } catch (_: SocketTimeoutException) {
            Sent.NO_ANSWER
        } catch (_: ConnectException) {
            Sent.NOT_LISTENING
        } catch (_: Exception) {
            Sent.NOT_LISTENING
        }
    }

    /** The running Volta's process id, as it wrote it down. */
    fun ownerPid(paths: AppPaths): Long? =
        runCatching { pidFile(paths).readText().trim().toLong() }.getOrNull()
            ?.takeIf { it != ProcessHandle.current().pid() }

    /**
     * Ends the Volta that holds the lock but does not answer: the process recorded in
     * instance.pid if it is a Volta, otherwise any other process of this very launcher.
     * True when it is gone and the lock can be taken.
     */
    fun killOwner(paths: AppPaths): Boolean {
        val me = ProcessHandle.current()
        val myCommand = me.info().command().orElse("")
        val victims = buildList {
            ownerPid(paths)?.let { pid -> ProcessHandle.of(pid).orElse(null) }
                ?.takeIf { looksLikeVolta(it.info().command().orElse(""), myCommand) }
                ?.let { add(it) }
            if (isEmpty() && myCommand.isNotBlank()) {
                ProcessHandle.allProcesses()
                    .filter { it.pid() != me.pid() && it.info().command().orElse("").equals(myCommand, ignoreCase = true) }
                    .forEach { add(it) }
            }
        }
        StartupLog.line("instance: ending the hung Volta: ${victims.map { it.pid() }}")
        if (victims.isEmpty()) return false
        victims.forEach { p ->
            runCatching { p.descendants().forEach { it.destroyForcibly() } }
            runCatching { p.destroyForcibly() }
        }
        val deadline = System.currentTimeMillis() + 8_000
        while (System.currentTimeMillis() < deadline) {
            if (victims.none { it.isAlive } && acquire(paths)) return true
            Thread.sleep(200)
        }
        return acquire(paths)
    }

    private fun looksLikeVolta(command: String, mine: String): Boolean {
        if (command.isBlank()) return false
        val name = File(command).name.lowercase()
        return command.equals(mine, ignoreCase = true) || name == "volta.exe" || name == "java.exe" || name == "javaw.exe"
    }

    /** How long a running Volta has to answer before it counts as hung. */
    const val ACK_TIMEOUT_MS = 2_500

    const val QUIT = "--quit"
    private const val MAGIC_V1 = "volta-instance-v1"
    private const val MAGIC = "volta-instance-v2"
    private const val END = "--volta-end--"
    private const val PROBE = "--volta-probe--"
}
