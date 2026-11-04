package com.ars3nb.volta.desktop.platform

import java.io.File

/**
 * "Start with Windows": a value under HKCU\…\Run, the per-user way that needs no admin
 * rights and shows up in Task Manager's Startup tab. The registry is the only truth, so
 * a user who switches it off in Task Manager sees it off in Volta too.
 */
object Autostart {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val NAME = "Volta"
    const val ARG = "--autostart"

    /** The installed Volta.exe; null when running from a development build. */
    fun launcher(): File? = System.getProperty("jpackage.app-path")
        ?.let(::File)
        ?.takeIf { it.isFile }

    val supported: Boolean get() = WinApi.isWindows && launcher() != null

    fun isEnabled(): Boolean {
        val value = WinApi.regGetString(WinApi.Root.CURRENT_USER, KEY, NAME) ?: return false
        return value.isNotBlank()
    }

    fun setEnabled(enabled: Boolean): Boolean {
        if (!WinApi.isWindows) return false
        if (!enabled) return WinApi.regDelete(WinApi.Root.CURRENT_USER, KEY, NAME)
        val exe = launcher() ?: return false
        return WinApi.regSetString(WinApi.Root.CURRENT_USER, KEY, NAME, "\"${exe.absolutePath}\" $ARG")
    }

    /** After an update the path may have moved: keep the entry pointing at this Volta.exe. */
    fun refreshPath() {
        if (isEnabled()) setEnabled(true)
    }
}
