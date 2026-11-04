package com.ars3nb.volta.desktop

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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

    val paths = AppPaths.default().apply { ensure() }
    val incoming = MutableSharedFlow<List<String>>(extraBufferCapacity = 8)
    if (!SingleInstance.acquire(paths) { incoming.tryEmit(it) }) {
        SingleInstance.send(paths, args.toList())
        exitProcess(0)
    }

    // A Volta that ended while connected left the user's proxy pointing at it: put it back.
    val systemProxy = SystemProxy(paths.proxyBackup)
    systemProxy.recoverAfterCrash()
    val controller = VoltaController(paths, systemProxy = systemProxy)
    controller.tunnel.killLeftover()
    Runtime.getRuntime().addShutdownHook(Thread { controller.shutdown() })
    registerUrlScheme()

    val autostarted = Autostart.ARG in args
    val links = args.filter { it.startsWith("volta://", ignoreCase = true) }
    val bridge = AwtBridge()

    application {
        val state by controller.ui.collectAsState()
        val view = remember { AppViewState() }
        var visible by remember { mutableStateOf(!autostarted) }
        val trayState = rememberTrayState()
        val windowState = rememberWindowState(size = DpSize(1000.dp, 720.dp), position = WindowPosition(Alignment.Center))
        val strings = Lang.resolve(state.settings.language).strings

        fun show() {
            visible = true
            windowState.isMinimized = false
        }

        fun quit() {
            controller.shutdown()
            exitApplication()
        }

        LaunchedEffect(Unit) {
            controller.openUrl = bridge::openUrl
            // After an update's installer starts: out the same way as «Выход» in the tray.
            controller.quitApp = { javax.swing.SwingUtilities.invokeLater { quit() } }
            controller.startup()
            links.forEach { controller.importText(it) }
        }
        LaunchedEffect(Unit) {
            controller.notices.collect { text ->
                if (!visible) trayState.sendNotification(Notification("Volta", text, Notification.Type.Info))
            }
        }
        LaunchedEffect(Unit) {
            incoming.collect { more ->
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
            LaunchedEffect(visible) {
                if (visible) {
                    window.toFront()
                    window.requestFocus()
                }
            }
            VoltaDesktopApp(controller, state, view, bridge)
        }
    }
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
