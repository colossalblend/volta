package com.ars3nb.volta.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference

/**
 * The few user32 calls start-up needs: bringing the window to the front when a second
 * start asks for it (Windows only lets a background process take the foreground when the
 * foreground one allows it), and a plain message box for when nothing else can draw.
 * Best effort; never throws.
 */
object WinWindow {

    private interface User32Lib : Library {
        fun AllowSetForegroundWindow(pid: Int): Boolean
        fun SetForegroundWindow(hwnd: Pointer): Boolean
        fun GetForegroundWindow(): Pointer?
        fun ShowWindow(hwnd: Pointer, cmd: Int): Boolean
        fun IsIconic(hwnd: Pointer): Boolean
        fun BringWindowToTop(hwnd: Pointer): Boolean
        fun GetWindowThreadProcessId(hwnd: Pointer, pid: IntByReference): Int
        fun MessageBoxW(hwnd: Pointer?, text: WString, caption: WString, type: Int): Int
    }

    private val user32: User32Lib? by lazy {
        if (!WinApi.isWindows) null else runCatching { Native.load("user32", User32Lib::class.java) }.getOrNull()
    }

    private const val ASFW_ANY = -1
    private const val SW_RESTORE = 9
    private const val SW_SHOW = 5

    /** Lets the running Volta take the foreground (called by the second start, which has it). */
    fun allowForeground(pid: Long?): Boolean = runCatching {
        user32?.AllowSetForegroundWindow(pid?.toInt() ?: ASFW_ANY) ?: false
    }.getOrDefault(false)

    /** Restores and raises [hwnd]; true when it is the foreground window afterwards. */
    fun raise(hwnd: Long): Boolean = runCatching {
        val lib = user32 ?: return false
        if (hwnd == 0L) return false
        val p = Pointer(hwnd)
        lib.ShowWindow(p, if (lib.IsIconic(p)) SW_RESTORE else SW_SHOW)
        lib.BringWindowToTop(p)
        lib.SetForegroundWindow(p)
        Pointer.nativeValue(lib.GetForegroundWindow() ?: return false) == hwnd
    }.getOrDefault(false)

    /** The process that owns the foreground window, or null. */
    fun foregroundPid(): Long? = runCatching {
        val lib = user32 ?: return null
        val w = lib.GetForegroundWindow() ?: return null
        val pid = IntByReference()
        lib.GetWindowThreadProcessId(w, pid)
        pid.value.toLong()
    }.getOrNull()

    const val MB_OK = 0x0
    const val MB_OKCANCEL = 0x1
    const val MB_YESNO = 0x4
    const val MB_ICONERROR = 0x10
    const val MB_ICONWARNING = 0x30
    const val MB_SETFOREGROUND = 0x10000
    const val MB_TOPMOST = 0x40000
    const val IDOK = 1
    const val IDYES = 6

    /** A native message box; 0 when it could not be shown. */
    fun messageBox(title: String, text: String, type: Int): Int = runCatching {
        user32?.MessageBoxW(null, WString(text), WString(title), type or MB_SETFOREGROUND or MB_TOPMOST) ?: 0
    }.getOrDefault(0)
}
