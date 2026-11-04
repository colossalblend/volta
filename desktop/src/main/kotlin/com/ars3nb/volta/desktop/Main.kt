package com.ars3nb.volta.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.renderComposeScene
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.io.File
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.firstOrNull() == "--self-test") {
        val log = File(args.getOrNull(1) ?: "volta-self-test.log")
        val dir = System.getProperty("compose.application.resources.dir").orEmpty()
        val xray = File(dir, "xray.exe")
        val lines = mutableListOf("resources: $dir", "xray: ${xray.isFile}")
        val ok = xray.isFile && runCatching {
            val p = ProcessBuilder(xray.absolutePath, "version").redirectErrorStream(true).start()
            lines += p.inputStream.bufferedReader().readText()
            p.waitFor() == 0
        }.getOrElse { lines += it.toString(); false }
        val image = renderComposeScene(320, 200) {
            MaterialTheme {
                Surface {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PowerSettingsNew, null)
                        Text("Volta")
                    }
                }
            }
        }
        lines += "rendered ${image.width}x${image.height}"
        log.writeText(lines.joinToString("\n"))
        exitProcess(if (ok) 0 else 1)
    }
    application {
        Window(onCloseRequest = ::exitApplication, title = "Volta") {
            MaterialTheme {
                Surface {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Volta")
                    }
                }
            }
        }
    }
}
