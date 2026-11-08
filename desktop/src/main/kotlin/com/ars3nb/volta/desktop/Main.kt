package com.ars3nb.volta.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.desktop.data.DesignTheme
import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.platform.Autostart
import com.ars3nb.volta.desktop.platform.SystemProxy
import com.ars3nb.volta.desktop.platform.WinApi
import com.ars3nb.volta.desktop.platform.WinWindow
import com.ars3nb.volta.desktop.ui.AppViewState
import com.ars3nb.volta.desktop.ui.DesktopBridge
import com.ars3nb.volta.desktop.ui.Page
import com.ars3nb.volta.desktop.ui.VoltaDesktopApp
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI
import kotlin.concurrent.thread
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.MutableSharedFlow
import org.jetbrains.skia.Image

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") exitProcess(SelfTest.run(args.drop(1)))

    // Nothing may fail silently from here on: a crash anywhere in start-up ends in a log
    // file and a plain dialog (Startup.kt), never in a process that just vanishes.
    val paths = try {
        AppPaths.default().apply { ensure() }
    } catch (t: Throwable) {
        CrashReport.fatal(t, "data folder (%APPDATA%\\Volta)")
    }
    StartupLog.init(paths)
    CrashReport.install(paths)
    try {
        start(paths, args)
    } catch (t: Throwable) {
        CrashReport.fatal(t, "start-up (${CrashReport.phase})")
    }
    // Everything is down (the shutdown hook puts the network back); don't wait for stray threads.
    exitProcess(0)
}

/** What the UI registers so the instance listener can reach it from its own thread. */
private class UiHooks {
    @Volatile
    var ready = false

    @Volatile
    var quit: (() -> Unit)? = null
}

private fun start(paths: AppPaths, args: Array<String>) {
    CrashReport.phase = "single instance"
    StartupLog.line("start ${AppPaths.appVersion} (build ${System.getProperty("volta.versionCode") ?: "0"}), args ${args.toList()}, app ${System.getProperty("jpackage.app-path") ?: "-"}")
    val quitOnly = SingleInstance.QUIT in args
    if (!SingleInstance.acquire(paths)) {
        if (!handOver(paths, args.toList())) return
    }
    // Nothing running and only asked to quit it: done.
    if (quitOnly) return

    // Replayed: a link handed over while the window is still coming up is not lost.
    val incoming = MutableSharedFlow<List<String>>(replay = 8, extraBufferCapacity = 8)
    val hooks = UiHooks()
    val controllerRef = arrayOfNulls<VoltaController>(1)
    SingleInstance.listen(paths) { more ->
        if (SingleInstance.QUIT in more) {
            StartupLog.line("instance: asked to quit")
            javax.swing.SwingUtilities.invokeLater {
                hooks.quit?.invoke() ?: run {
                    runCatching { controllerRef[0]?.shutdown() }
                    exitProcess(0)
                }
            }
            return@listen true
        }
        incoming.tryEmit(more)
        // Still starting: the message waits in the queue and the window comes up anyway.
        if (!hooks.ready) return@listen true
        // The answer means the UI thread is alive: it ran this.
        val taken = java.util.concurrent.CountDownLatch(1)
        javax.swing.SwingUtilities.invokeLater { taken.countDown() }
        taken.await(1_800, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    // Before any Compose class loads: the renderer for this start.
    CrashReport.phase = "graphics"
    GraphicsMode.prepare(paths)

    CrashReport.phase = "proxy recovery"
    // A Volta that ended while connected left the user's proxy pointing at it: put it back.
    val systemProxy = SystemProxy(paths.proxyBackup)
    systemProxy.recoverAfterCrash()
    CrashReport.cleanup = { restoreProxyQuietly(paths) }

    CrashReport.phase = "loading data"
    val controller = VoltaController(paths, systemProxy = systemProxy)
    controllerRef[0] = controller
    CrashReport.cleanup = { controller.shutdown() }
    controller.tunnel.killLeftover()
    Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })
    registerUrlScheme()

    val autostarted = Autostart.ARG in args
    val links = args.filter { it.startsWith("volta://", ignoreCase = true) }
    val bridge = AwtBridge()

    CrashReport.phase = "window"
    if (!autostarted) GraphicsMode.arm(paths)
    application {
        val state by controller.ui.collectAsState()
        val view = remember { AppViewState() }
        var visible by remember { mutableStateOf(!autostarted) }
        var raised by remember { mutableStateOf(0) }
        val trayState = rememberTrayState()
        val windowState = rememberWindowState(size = DpSize(1000.dp, 720.dp), position = WindowPosition(Alignment.Center))
        val strings = Lang.resolve(state.settings.language).strings

        fun show() {
            visible = true
            windowState.isMinimized = false
            raised++
        }

        fun quit() {
            controller.shutdown()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            controller.openUrl = bridge::openUrl
            // After an update's installer starts: out the same way as «Выход» in the tray.
            controller.quitApp = { javax.swing.SwingUtilities.invokeLater { quit() } }
            hooks.quit = ::quit
            hooks.ready = true
            controller.startup()
            links.forEach { controller.importText(it) }
            if (autostarted) {
                // Up in the tray without a window: start-up is done; the window is checked when first shown.
                CrashReport.windowShown = true
                CrashReport.phase = "running (tray)"
            }
        }
        LaunchedEffect(Unit) {
            controller.notices.collect { text ->
                if (!visible) trayState.sendNotification(Notification("Volta", text, Notification.Type.Info))
            }
        }
        LaunchedEffect(Unit) {
            incoming.collect { more ->
                StartupLog.line("instance: another start asked to show the window ${more.filter { it.startsWith("volta://", true) }.size} link(s)")
                show()
                more.filter { it.startsWith("volta://", ignoreCase = true) }.forEach {
                    view.page = Page.HOME
                    controller.importText(it)
                }
            }
        }

        val connected = state.vpnState == VpnConnectionState.CONNECTED
        val busy = state.vpnState == VpnConnectionState.CONNECTING || state.phase != ConnectPhase.IDLE
        Tray(
            icon = remember(connected, busy) {
                resourcePainter(
                    when {
                        connected -> "icons/tray-on.png"
                        busy -> "icons/tray-busy.png"
                        else -> "icons/tray-off.png"
                    }
                )
            },
            state = trayState,
            tooltip = if (connected) {
                strings.trayTooltipOn(
                    state.servers.firstOrNull { it.id == state.runtime.connectedServerId }
                        ?.let { Countries.withoutLeadingFlag(it.remark) }.orEmpty()
                )
            } else {
                strings.trayTooltipOff
            },
            onAction = ::show,
            menu = {
                Item(strings.trayOpen, onClick = ::show)
                Item(
                    if (connected || busy) strings.trayDisconnect else strings.trayConnect,
                    enabled = state.servers.isNotEmpty() || connected,
                    onClick = controller::toggleConnect
                )
                Separator()
                Item(strings.trayExit, onClick = ::quit)
            }
        )

        Window(
            onCloseRequest = {
                if (state.settings.minimizeToTray) {
                    visible = false
                    if (!state.settings.trayHintShown) {
                        trayState.sendNotification(Notification("Volta", strings.msgMinimized, Notification.Type.Info))
                        controller.markTrayHintShown()
                    }
                } else {
                    quit()
                }
            },
            visible = visible,
            state = windowState,
            title = "Volta",
            icon = remember { resourcePainter("icons/volta.png") }
        ) {
            LaunchedEffect(Unit) { window.minimumSize = java.awt.Dimension(800, 600) }
            val dark = isDark(state.settings.designTheme)
            LaunchedEffect(state.settings.designTheme, dark, visible) {
                val (bg, fg) = frameColors(state.settings.designTheme, dark)
                WinApi.styleWindowFrame(window.windowHandle, dark, bg, fg, bg)
            }
            LaunchedEffect(visible, raised) {
                if (visible) {
                    window.toFront()
                    window.requestFocus()
                    if (raised > 0) {
                        val front = WinWindow.raise(window.windowHandle)
                        StartupLog.line("instance: window raised (foreground: $front)")
                    }
                }
            }
            // The first frames on screen: this renderer works here (see GraphicsMode).
            var drawn by remember { mutableStateOf(false) }
            LaunchedEffect(visible) {
                if (visible && !drawn) {
                    if (autostarted) GraphicsMode.arm(paths)
                    withFrameNanos { }
                    withFrameNanos { }
                    kotlinx.coroutines.delay(1_500)
                    drawn = true
                    GraphicsMode.disarm(paths)
                    CrashReport.renderApiInUse = runCatching { window.renderApi.toString() }.getOrNull()
                    CrashReport.windowShown = true
                    CrashReport.phase = "running"
                    StartupLog.line("window shown, renderer ${CrashReport.renderApiInUse}")
                }
            }
            VoltaDesktopApp(controller, state, view, bridge)
        }
    }
}

/**
 * Another Volta holds the lock. Normally it takes [args], shows its window and this start
 * ends quietly (false). If it does not answer, it is hung (or invisible): the user is asked
 * whether to end it and start afresh; true when this process may carry on as the one Volta.
 */
private fun handOver(paths: AppPaths, args: List<String>): Boolean {
    var result = SingleInstance.send(paths, args)
    // Just starting (no listener yet): give it a moment before calling it hung.
    val until = System.currentTimeMillis() + 3_000
    while (result == SingleInstance.Sent.NOT_LISTENING && System.currentTimeMillis() < until) {
        Thread.sleep(300)
        if (SingleInstance.acquire(paths)) return true
        result = SingleInstance.send(paths, args)
    }
    StartupLog.line("instance: another Volta is running (pid ${SingleInstance.ownerPid(paths)}), handed over: $result")
    when (result) {
        SingleInstance.Sent.ACKED, SingleInstance.Sent.OLD_PEER -> return false
        else -> Unit
    }
    // Asked to quit, or a link that can wait: never kill anything without the user.
    if (SingleInstance.QUIT in args) return false
    val restart = StartText.pick("Закрыть старую и открыть", "Close it and start again")
    val cancel = StartText.pick("Отмена", "Cancel")
    val choice = NativeDialog.choose(
        StartText.pick("Volta уже запущена, но не отвечает", "Volta is already running but not responding"),
        StartText.pick(
            "Volta уже запущена, но не отвечает.\n\nМожно закрыть зависшую копию и открыть Volta заново. " +
                "Настройки прокси Windows будут возвращены.",
            "Volta is already running but not responding.\n\nYou can close the stuck copy and start Volta again. " +
                "The Windows proxy settings will be restored."
        ),
        listOf(restart, cancel),
        error = false,
        default = 0
    )
    if (choice != 0) return false
    // Its network first, then the process (its core goes with it: it is bound to Volta's lifetime).
    restoreProxyQuietly(paths)
    if (SingleInstance.killOwner(paths)) {
        restoreProxyQuietly(paths)
        return true
    }
    NativeDialog.choose(
        "Volta",
        StartText.pick(
            "Не удалось закрыть зависшую копию Volta. Завершите процесс Volta.exe в Диспетчере задач и запустите Volta снова.",
            "Could not close the stuck copy of Volta. End Volta.exe in Task Manager and start Volta again."
        ),
        listOf("OK"),
        error = true
    )
    return false
}

@Composable
private fun isDark(theme: DesignTheme): Boolean = when (theme) {
    DesignTheme.LIGHT -> false
    DesignTheme.SYSTEM, DesignTheme.DYNAMIC -> isSystemInDarkTheme()
    else -> true
}

/** The title bar in the page colour, so the window reads as one sheet. */
private fun frameColors(theme: DesignTheme, dark: Boolean): Pair<Int, Int> = when {
    theme == DesignTheme.DYNAMIC -> {
        val scheme = com.ars3nb.volta.desktop.ui.theme.Accent.scheme(com.ars3nb.volta.desktop.ui.theme.Accent.seed, dark)
        fun rgb(c: androidx.compose.ui.graphics.Color) =
            ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
        rgb(scheme.background) to rgb(scheme.onBackground)
    }
    theme == DesignTheme.OCEAN -> 0x0B1A20 to 0xCBE7E3
    theme == DesignTheme.EMBER -> 0x161210 to 0xF4E7DC
    !dark -> 0xF6F3EE to 0x1A1C1E
    else -> 0x101316 to 0xEEE8DF
}

fun resourcePainter(path: String): Painter {
    val bytes = Thread.currentThread().contextClassLoader?.getResourceAsStream(path)?.use { it.readBytes() }
        ?: AwtBridge::class.java.getResourceAsStream("/$path")?.use { it.readBytes() }
        ?: error("missing resource $path")
    return BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}

/** volta:// links opened anywhere in Windows come to Volta (HKCU\Software\Classes\volta). */
private fun registerUrlScheme() {
    val exe = Autostart.launcher() ?: return
    thread(isDaemon = true) {
        val root = WinApi.Root.CURRENT_USER
        val key = "Software\\Classes\\volta"
        WinApi.regSetString(root, key, "", "URL:Volta")
        WinApi.regSetString(root, key, "URL Protocol", "")
        WinApi.regSetString(root, "$key\\DefaultIcon", "", "\"${exe.absolutePath}\",0")
        WinApi.regSetString(root, "$key\\shell\\open\\command", "", "\"${exe.absolutePath}\" \"%1\"")
    }
}

/** Clipboard, files and the browser through AWT. */
class AwtBridge : DesktopBridge {
    override fun readClipboard(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun writeClipboard(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun pickFile(): String? = runCatching {
        val dialog = FileDialog(null as Frame?, "Volta", FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val file = File(dialog.directory, name)
        // A settings file is a few kilobytes; anything far bigger is not one.
        if (file.length() > 512 * 1024) return null
        file.readText(Charsets.UTF_8)
    }.getOrNull()

    override fun openUrl(url: String) {
        thread(isDaemon = true) { runCatching { Desktop.getDesktop().browse(URI(url)) } }
    }

    override fun openFolder(dir: File) {
        thread(isDaemon = true) { runCatching { dir.mkdirs(); Desktop.getDesktop().open(dir) } }
    }

    override fun chooseSaveFile(suggestedName: String): File? = runCatching {
        val dialog = FileDialog(null as Frame?, "Volta", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val name = dialog.file ?: return null
        File(dialog.directory, if (name.contains('.')) name else "$name.json")
    }.getOrNull()

    override fun chooseOpenFile(): File? = runCatching {
        val dialog = FileDialog(null as Frame?, "Volta", FileDialog.LOAD)
        dialog.file = "*.json"
        dialog.isVisible = true
        val name = dialog.file ?: return null
        File(dialog.directory, name)
    }.getOrNull()
}
