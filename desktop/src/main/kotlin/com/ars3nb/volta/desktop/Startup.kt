package com.ars3nb.volta.desktop

import com.ars3nb.volta.desktop.platform.SystemProxy
import com.ars3nb.volta.desktop.platform.WinApi
import com.ars3nb.volta.desktop.platform.WinWindow
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * Start-up's own journal, %APPDATA%\Volta\logs\volta.log: what start-up decided and why
 * (graphics mode, a second start, a hung instance). Small and never in the way.
 */
object StartupLog {
    @Volatile
    private var file: File? = null
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun init(paths: AppPaths) {
        file = paths.appLog
        runCatching {
            paths.logDir.mkdirs()
            if (paths.appLog.length() > 1_000_000) {
                val old = File(paths.logDir, "volta.old.log")
                old.delete()
                paths.appLog.renameTo(old)
            }
        }
    }

    @Synchronized
    fun line(text: String) {
        val f = file ?: return
        runCatching { f.appendText("${LocalDateTime.now().format(stamp)} [${ProcessHandle.current().pid()}] $text\n", Charsets.UTF_8) }
    }

    fun tail(lines: Int): String = runCatching {
        file?.takeIf { it.isFile }?.readLines(Charsets.UTF_8)?.takeLast(lines)?.joinToString("\n")
    }.getOrNull().orEmpty()
}

/** Russian for Russian-speaking systems, English otherwise; for the few words said before the UI. */
internal object StartText {
    val ru: Boolean = Locale.getDefault().language in setOf("ru", "uk", "be", "kk", "uz", "ky", "tg")

    fun pick(ru: String, en: String) = if (this.ru) ru else en
}

/**
 * A crash never goes unnoticed: every fatal error is written to
 * %APPDATA%\Volta\logs\crash-<time>.log (with the system, Java and graphics details) and
 * shown in a plain native dialog with the log's path and a button that opens its folder.
 * The user's proxy setting is put back first, whatever the dialog does.
 */
object CrashReport {

    @Volatile
    var paths: AppPaths? = null
        private set

    /** Where start-up is, for the log. */
    @Volatile
    var phase: String = "starting"

    @Volatile
    var windowShown: Boolean = false

    /** The render API the window actually got (ComposeWindow.renderApi), once known. */
    @Volatile
    var renderApiInUse: String? = null

    /** Puts the user's network back (the tunnel down, the proxy restored); replaced once the app is up. */
    @Volatile
    var cleanup: () -> Unit = {}

    private val fatalOnce = AtomicBoolean(false)

    fun install(paths: AppPaths) {
        this.paths = paths
        Thread.setDefaultUncaughtExceptionHandler { thread, error -> uncaught(thread, error) }
    }

    private fun uncaught(thread: Thread, error: Throwable) {
        val ui = thread.name.startsWith("AWT-EventQueue")
        val drawing = error.stackTrace.any {
            it.className.startsWith("androidx.compose") || it.className.startsWith("org.jetbrains.skiko")
        }
        // Before the window is up anything is fatal; afterwards only what breaks drawing
        // (an exception in the window's composition or the renderer leaves it dead).
        if (!windowShown || (ui && drawing) || error is OutOfMemoryError) {
            fatal(error, "thread ${thread.name}")
        } else {
            StartupLog.line("uncaught in ${thread.name}: ${error::class.java.name}: ${error.message}")
            StartupLog.line(stackOf(error).lineSequence().take(12).joinToString("\n"))
        }
    }

    /** Writes the crash log, puts the network back, tells the user, and ends the process. */
    fun fatal(error: Throwable, where: String): Nothing {
        if (!fatalOnce.compareAndSet(false, true)) {
            // Another thread is already reporting; let it finish.
            while (true) Thread.sleep(60_000)
        }
        val log = runCatching { write(error, where) }.getOrNull()
        StartupLog.line("FATAL ($where): ${error::class.java.name}: ${error.message}; log: ${log?.absolutePath}")
        // The network first: the user may leave the dialog open for a long time.
        val done = Thread {
            runCatching { cleanup() }
        }.apply { isDaemon = true; start() }
        done.join(6_000)

        val title = if (windowShown) StartText.pick("Volta закрылась из-за ошибки", "Volta closed because of an error")
        else StartText.pick("Volta не запустилась", "Volta could not start")
        val where2 = log?.absolutePath ?: paths?.logDir?.absolutePath ?: "%APPDATA%\\Volta\\logs"
        val graphics = if (!windowShown) StartText.pick(
            "\n\nПри следующем запуске Volta включит безопасный режим графики (без видеокарты).",
            "\n\nNext time Volta will start in safe graphics mode (without the graphics card)."
        ) else ""
        val text = StartText.pick(
            "Произошла ошибка, и Volta пришлось закрыть. Настройки сети компьютера возвращены.\n\n" +
                "Подробности записаны в журнал:\n$where2\n\n" +
                "Если ошибка повторяется, отправьте этот файл разработчику.",
            "Something went wrong and Volta had to close. Your network settings have been restored.\n\n" +
                "The details are in this log:\n$where2\n\n" +
                "If it keeps happening, send this file to the developer."
        ) + graphics
        val open = StartText.pick("Открыть папку журнала", "Open the log folder")
        val close = StartText.pick("Закрыть", "Close")
        val choice = NativeDialog.choose(title, text, listOf(open, close), error = true, default = 0)
        if (choice == 0) openFolder(log ?: paths?.logDir)
        Runtime.getRuntime().halt(1)
        throw IllegalStateException("unreachable")
    }

    /** %APPDATA%\Volta\logs\crash-<time>.log; the ten newest are kept. */
    fun write(error: Throwable, where: String): File? {
        val dir = paths?.logDir ?: File(System.getProperty("java.io.tmpdir"), "Volta")
        dir.mkdirs()
        val name = "crash-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".log"
        val file = File(dir, name)
        file.writeText(report(error, where), Charsets.UTF_8)
        runCatching {
            dir.listFiles { f -> f.name.startsWith("crash-") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
        }
        return file
    }

    fun report(error: Throwable, where: String): String = buildString {
        fun prop(k: String) = System.getProperty(k).orEmpty()
        appendLine("Volta ${AppPaths.appVersion} (build ${prop("volta.versionCode").ifBlank { "0" }})")
        appendLine("Time:      ${LocalDateTime.now()}")
        appendLine("Where:     $where (phase: $phase, window shown: $windowShown)")
        appendLine("OS:        ${prop("os.name")} ${prop("os.version")} ${prop("os.arch")}; ${windowsBuild()}")
        appendLine("Session:   ${System.getenv("SESSIONNAME").orEmpty()}")
        appendLine("Java:      ${prop("java.vendor")} ${prop("java.runtime.version")} (${prop("java.home")})")
        appendLine("App:       ${prop("jpackage.app-path")}")
        appendLine("Data:      ${paths?.dataDir?.absolutePath}")
        appendLine("Encoding:  file=${prop("file.encoding")} jnu=${prop("sun.jnu.encoding")} native=${prop("native.encoding")} locale=${Locale.getDefault()}")
        appendLine("Graphics:  requested=${prop("skiko.renderApi").ifBlank { "default" }} in use=${renderApiInUse ?: "unknown"} safe mode=${paths?.let { GraphicsMode.describe(it) } ?: "?"}")
        appendLine("GPU:       ${gpus()}")
        appendLine("Memory:    max ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB, cpus ${Runtime.getRuntime().availableProcessors()}")
        appendLine()
        appendLine(stackOf(error))
        val tail = StartupLog.tail(40)
        if (tail.isNotBlank()) {
            appendLine("--- volta.log (last lines)")
            appendLine(tail)
        }
    }

    private fun stackOf(error: Throwable): String {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private fun windowsBuild(): String = if (!WinApi.isWindows) "" else runCatching {
        val key = "SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion"
        fun s(name: String) = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, key, name) }.getOrNull().orEmpty()
        val ubr = runCatching { Advapi32Util.registryGetIntValue(WinReg.HKEY_LOCAL_MACHINE, key, "UBR") }.getOrNull()
        "${s("ProductName")} ${s("DisplayVersion")} build ${s("CurrentBuild")}${ubr?.let { ".$it" }.orEmpty()}"
    }.getOrDefault("")

    /** The display adapters and their driver versions, from the registry (no admin rights needed). */
    fun gpus(): String = if (!WinApi.isWindows) "" else runCatching {
        val cls = "SYSTEM\\CurrentControlSet\\Control\\Class\\{4d36e968-e325-11ce-bfc1-08002be10318}"
        Advapi32Util.registryGetKeys(WinReg.HKEY_LOCAL_MACHINE, cls)
            .filter { it.length == 4 && it.all(Char::isDigit) }
            .mapNotNull { k ->
                runCatching {
                    val desc = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "$cls\\$k", "DriverDesc")
                    val ver = runCatching { Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE, "$cls\\$k", "DriverVersion") }.getOrNull()
                    if (ver != null) "$desc (driver $ver)" else desc
                }.getOrNull()
            }
            .joinToString("; ")
            .ifBlank { "unknown" }
    }.getOrDefault("unknown")

    fun openFolder(target: File?) {
        target ?: return
        runCatching {
            if (WinApi.isWindows) {
                if (target.isFile) ProcessBuilder("explorer.exe", "/select,", target.absolutePath).start()
                else ProcessBuilder("explorer.exe", target.absolutePath).start()
            } else {
                java.awt.Desktop.getDesktop().open(if (target.isFile) target.parentFile else target)
            }
        }
    }
}

/**
 * The graphics Volta starts with. Compose draws through the graphics card (Direct3D);
 * on some drivers, remote desktops and virtual machines that fails or even takes the
 * process down. A start that never got its window on screen leaves [marker] behind, and
 * the next start then draws in software («Безопасный режим графики», also a switch in
 * Settings). Decided before any Compose class loads: the renderer reads the property once.
 */
object GraphicsMode {
    const val SOFTWARE = "SOFTWARE"

    private fun file(paths: AppPaths) = File(paths.dataDir, "graphics-mode.txt")
    private fun marker(paths: AppPaths) = File(paths.runDir, "starting.marker")

    private fun mode(paths: AppPaths): String =
        runCatching { file(paths).readText().trim() }.getOrDefault("")

    fun isSafe(paths: AppPaths): Boolean = mode(paths).startsWith("software")

    /** Turned on by Volta itself after a failed start (not by the user). */
    fun isAuto(paths: AppPaths): Boolean = mode(paths) == "software-auto"

    fun describe(paths: AppPaths): String = mode(paths).ifBlank { "off" }

    fun setSafe(paths: AppPaths, on: Boolean) {
        runCatching {
            if (on) file(paths).writeText("software") else file(paths).delete()
        }
        StartupLog.line("graphics: safe mode ${if (on) "on" else "off"} (from Settings)")
    }

    /** The switch in Settings: its title and hint in the app's language (Russian or English). */
    fun settingsText(lang: String): Pair<String, String> =
        if (lang in setOf("ru", "uk", "be", "kk", "uz", "ky", "tg")) {
            "Безопасный режим графики" to
                "Рисовать окно без видеокарты. Включите, если окно Volta не открывается, мерцает или остаётся чёрным " +
                "(бывает со старыми драйверами, на удалённом рабочем столе и в виртуальных машинах). Действует после перезапуска."
        } else {
            "Safe graphics mode" to
                "Draw the window without the graphics card. Turn on if the Volta window doesn't open, flickers or stays black " +
                "(old drivers, Remote Desktop, virtual machines). Takes effect after a restart."
        }

    /** Before Compose: picks this start's renderer. */
    fun prepare(paths: AppPaths) {
        val m = marker(paths)
        if (m.isFile) {
            val previous = runCatching { m.readText() }.getOrDefault("")
            StartupLog.line("graphics: the previous start did not show its window (${previous.lineSequence().firstOrNull().orEmpty()})")
            if (!previous.contains(SOFTWARE) && !isSafe(paths)) {
                runCatching { file(paths).writeText("software-auto") }
                StartupLog.line("graphics: safe mode switched on for the next starts")
            }
            runCatching { m.delete() }
        }
        if (isSafe(paths) && System.getenv("SKIKO_RENDER_API").isNullOrBlank()) {
            System.setProperty("skiko.renderApi", SOFTWARE)
        }
        StartupLog.line("graphics: renderer ${System.getProperty("skiko.renderApi") ?: "default"}, safe mode ${describe(paths)}")
    }

    /** The window is about to be drawn for the first time. */
    fun arm(paths: AppPaths) {
        runCatching {
            paths.runDir.mkdirs()
            marker(paths).writeText(
                "renderApi=${System.getProperty("skiko.renderApi") ?: "default"}\npid=${ProcessHandle.current().pid()}\ntime=${LocalDateTime.now()}\n"
            )
        }
    }

    /** The window drew its first frames: this renderer works here. */
    fun disarm(paths: AppPaths) {
        runCatching { marker(paths).delete() }
    }
}

/**
 * A dialog that works before (and without) Compose: Swing's option pane in the Windows
 * look, or, if Swing can't show it within a few seconds, a plain user32 message box.
 */
object NativeDialog {

    /** The index of the chosen option; -1 when closed. */
    fun choose(title: String, message: String, options: List<String>, error: Boolean, default: Int = 0): Int {
        if (!GraphicsEnvironment.isHeadless()) {
            if (SwingUtilities.isEventDispatchThread()) {
                runCatching { return swing(title, message, options, error, default) {} }
            } else {
                val opened = CountDownLatch(1)
                val done = CountDownLatch(1)
                val result = AtomicInteger(Int.MIN_VALUE)
                runCatching {
                    SwingUtilities.invokeLater {
                        try {
                            result.set(swing(title, message, options, error, default) { opened.countDown() })
                        } catch (_: Throwable) {
                        } finally {
                            opened.countDown()
                            done.countDown()
                        }
                    }
                }
                if (opened.await(5, TimeUnit.SECONDS)) {
                    done.await()
                    if (result.get() != Int.MIN_VALUE) return result.get()
                }
            }
        }
        return native(title, message, options, error)
    }

    private fun swing(
        title: String,
        message: String,
        options: List<String>,
        error: Boolean,
        default: Int,
        onOpened: () -> Unit
    ): Int {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val pane = JOptionPane(
            message,
            if (error) JOptionPane.ERROR_MESSAGE else JOptionPane.WARNING_MESSAGE,
            JOptionPane.DEFAULT_OPTION,
            null,
            options.toTypedArray(),
            options.getOrNull(default)
        )
        val dialog = pane.createDialog(null as java.awt.Component?, title)
        dialog.isAlwaysOnTop = true
        dialog.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowOpened(e: java.awt.event.WindowEvent?) {
                onOpened()
                dialog.toFront()
            }
        })
        dialog.isVisible = true
        dialog.dispose()
        return options.indexOf(pane.value as? String)
    }

    /** user32's MessageBox: two options become «Yes» (the first) and «No». */
    private fun native(title: String, message: String, options: List<String>, error: Boolean): Int {
        val icon = if (error) WinWindow.MB_ICONERROR else WinWindow.MB_ICONWARNING
        return if (options.size >= 2) {
            val text = message + "\n\n" + options[0] + "?"
            val r = WinWindow.messageBox(title, text, WinWindow.MB_YESNO or icon)
            if (r == WinWindow.IDYES) 0 else 1
        } else {
            WinWindow.messageBox(title, message, WinWindow.MB_OK or icon)
            0
        }
    }
}

/** For the crash path before the app's own objects exist: the proxy back as it was. */
internal fun restoreProxyQuietly(paths: AppPaths) {
    runCatching { SystemProxy(paths.proxyBackup).recoverAfterCrash() }
}
