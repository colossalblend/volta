package com.ars3nb.volta.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.ptr.IntByReference

/**
 * Every call Volta makes into Windows itself, in one place: the registry, WinINet (the
 * user's proxy settings), a job object that takes the core down with the app, and the
 * window frame colours. Everything here is best effort and never throws.
 */
object WinApi {

    val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    enum class Root { CURRENT_USER, LOCAL_MACHINE }

    private fun hkey(root: Root): WinReg.HKEY =
        if (root == Root.CURRENT_USER) WinReg.HKEY_CURRENT_USER else WinReg.HKEY_LOCAL_MACHINE

    // ------------------------------------------------------------------ registry

    fun regGetString(root: Root, path: String, name: String): String? = runCatching {
        if (!isWindows || !Advapi32Util.registryValueExists(hkey(root), path, name)) return null
        when (val v = Advapi32Util.registryGetValue(hkey(root), path, name)) {
            is String -> v
            null -> null
            else -> v.toString()
        }
    }.getOrNull()

    fun regGetInt(root: Root, path: String, name: String): Int? = runCatching {
        if (!isWindows || !Advapi32Util.registryValueExists(hkey(root), path, name)) return null
        when (val v = Advapi32Util.registryGetValue(hkey(root), path, name)) {
            is Int -> v
            is Number -> v.toInt()
            is String -> v.toIntOrNull()
            else -> null
        }
    }.getOrNull()

    fun regSetString(root: Root, path: String, name: String, value: String): Boolean = runCatching {
        if (!isWindows) return false
        if (!Advapi32Util.registryKeyExists(hkey(root), path)) Advapi32Util.registryCreateKey(hkey(root), path)
        Advapi32Util.registrySetStringValue(hkey(root), path, name, value)
        true
    }.getOrDefault(false)

    fun regSetInt(root: Root, path: String, name: String, value: Int): Boolean = runCatching {
        if (!isWindows) return false
        if (!Advapi32Util.registryKeyExists(hkey(root), path)) Advapi32Util.registryCreateKey(hkey(root), path)
        Advapi32Util.registrySetIntValue(hkey(root), path, name, value)
        true
    }.getOrDefault(false)

    fun regDelete(root: Root, path: String, name: String): Boolean = runCatching {
        if (!isWindows) return false
        if (Advapi32Util.registryValueExists(hkey(root), path, name)) {
            Advapi32Util.registryDeleteValue(hkey(root), path, name)
        }
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------ WinINet

    private interface WinInetLib : Library {
        fun InternetSetOptionW(hInternet: Pointer?, dwOption: Int, lpBuffer: Pointer?, dwBufferLength: Int): Boolean
        fun InternetQueryOptionW(hInternet: Pointer?, dwOption: Int, lpBuffer: Pointer?, lpdwBufferLength: IntByReference): Boolean
    }

    private val wininet: WinInetLib? by lazy {
        if (!isWindows) null else runCatching { Native.load("wininet", WinInetLib::class.java) }.getOrNull()
    }

    const val PROXY_TYPE_DIRECT = 0x1
    const val PROXY_TYPE_PROXY = 0x2
    const val PROXY_TYPE_AUTO_PROXY_URL = 0x4
    const val PROXY_TYPE_AUTO_DETECT = 0x8

    private const val INTERNET_OPTION_REFRESH = 37
    private const val INTERNET_OPTION_SETTINGS_CHANGED = 39
    private const val INTERNET_OPTION_PER_CONNECTION_OPTION = 75
    private const val INTERNET_OPTION_PROXY_SETTINGS_CHANGED = 95
    private const val INTERNET_PER_CONN_FLAGS = 1
    private const val INTERNET_PER_CONN_PROXY_SERVER = 2
    private const val INTERNET_PER_CONN_PROXY_BYPASS = 3
    private const val INTERNET_PER_CONN_AUTOCONFIG_URL = 4

    // INTERNET_PER_CONN_OPTION_LISTW and INTERNET_PER_CONN_OPTIONW, laid out by hand.
    private val ptr = Native.POINTER_SIZE
    private val optionSize = if (ptr == 8) 16 else 12
    private val optionValueAt = if (ptr == 8) 8L else 4L
    private val listSize = if (ptr == 8) 32 else 20
    private val listConnectionAt = if (ptr == 8) 8L else 4L
    private val listCountAt = if (ptr == 8) 16L else 8L
    private val listErrorAt = if (ptr == 8) 20L else 12L
    private val listOptionsAt = if (ptr == 8) 24L else 16L

    private fun wide(text: String): Memory =
        Memory((text.length + 1) * 2L).apply { clear(); setWideString(0, text) }

    /**
     * The LAN (default) connection's proxy settings through WinINet's per-connection
     * options, which is what the Windows settings page, browsers and WinHTTP's IE import
     * read. Also writes them to the registry for the default connection.
     */
    fun setPerConnection(flags: Int, server: String?, bypass: String?, autoConfigUrl: String?): Boolean = runCatching {
        val lib = wininet ?: return false
        val keep = mutableListOf<Memory>()
        val entries = buildList {
            add(INTERNET_PER_CONN_FLAGS to null)
            add(INTERNET_PER_CONN_PROXY_SERVER to (server ?: ""))
            add(INTERNET_PER_CONN_PROXY_BYPASS to (bypass ?: ""))
            add(INTERNET_PER_CONN_AUTOCONFIG_URL to (autoConfigUrl ?: ""))
        }
        val options = Memory(optionSize.toLong() * entries.size).apply { clear() }
        entries.forEachIndexed { i, (option, text) ->
            val base = i.toLong() * optionSize
            options.setInt(base, option)
            if (text == null) {
                options.setInt(base + optionValueAt, flags)
            } else {
                val m = wide(text).also { keep += it }
                options.setPointer(base + optionValueAt, m)
            }
        }
        val list = Memory(listSize.toLong()).apply { clear() }
        list.setInt(0, listSize)
        list.setPointer(listConnectionAt, null)
        list.setInt(listCountAt, entries.size)
        list.setInt(listErrorAt, 0)
        list.setPointer(listOptionsAt, options)
        val ok = lib.InternetSetOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, listSize)
        keep.clear()
        ok
    }.getOrDefault(false)

    /** The LAN connection's PROXY_TYPE_* flags right now; null when they can't be read. */
    fun queryPerConnectionFlags(): Int? = runCatching {
        val lib = wininet ?: return null
        val options = Memory(optionSize.toLong()).apply { clear() }
        options.setInt(0, INTERNET_PER_CONN_FLAGS)
        val list = Memory(listSize.toLong()).apply { clear() }
        list.setInt(0, listSize)
        list.setPointer(listConnectionAt, null)
        list.setInt(listCountAt, 1)
        list.setPointer(listOptionsAt, options)
        val size = IntByReference(listSize)
        if (!lib.InternetQueryOptionW(null, INTERNET_OPTION_PER_CONNECTION_OPTION, list, size)) return null
        options.getInt(optionValueAt)
    }.getOrNull()

    /** Tells every WinINet user (browsers, the settings page) to read the settings again. */
    fun notifyProxyChanged(): Boolean = runCatching {
        val lib = wininet ?: return false
        val a = lib.InternetSetOptionW(null, INTERNET_OPTION_SETTINGS_CHANGED, null, 0)
        val b = lib.InternetSetOptionW(null, INTERNET_OPTION_REFRESH, null, 0)
        runCatching { lib.InternetSetOptionW(null, INTERNET_OPTION_PROXY_SETTINGS_CHANGED, null, 0) }
        a && b
    }.getOrDefault(false)

    // ------------------------------------------------------------------ job object

    private interface Kernel32Lib : Library {
        fun CreateJobObjectW(attributes: Pointer?, name: WString?): Pointer?
        fun SetInformationJobObject(job: Pointer, infoClass: Int, info: Pointer, length: Int): Boolean
        fun OpenProcess(access: Int, inherit: Boolean, pid: Int): Pointer?
        fun AssignProcessToJobObject(job: Pointer, process: Pointer): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private val kernel32: Kernel32Lib? by lazy {
        if (!isWindows) null else runCatching { Native.load("kernel32", Kernel32Lib::class.java) }.getOrNull()
    }

    /** One job for the app's lifetime: when Volta's process ends, Windows ends the job's processes. */
    private val job: Pointer? by lazy {
        runCatching {
            val k = kernel32 ?: return@lazy null
            val handle = k.CreateJobObjectW(null, null) ?: return@lazy null
            // JOBOBJECT_EXTENDED_LIMIT_INFORMATION (144 bytes on x64): LimitFlags at 16.
            val info = Memory(144).apply { clear() }
            info.setInt(16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
            if (!k.SetInformationJobObject(handle, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, info, 144)) {
                k.CloseHandle(handle)
                return@lazy null
            }
            handle
        }.getOrNull()
    }

    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    /** Ties process [pid] to Volta's lifetime, so a crash can't leave an orphaned core. */
    fun bindToAppLifetime(pid: Long): Boolean = runCatching {
        val k = kernel32 ?: return false
        val j = job ?: return false
        val process = k.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, pid.toInt()) ?: return false
        try {
            k.AssignProcessToJobObject(j, process)
        } finally {
            k.CloseHandle(process)
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ window frame

    private interface DwmLib : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: DwmLib? by lazy {
        if (!isWindows) null else runCatching { Native.load("dwmapi", DwmLib::class.java) }.getOrNull()
    }

    /**
     * Paints the title bar in the app's colours (Windows 11; Windows 10 takes the dark
     * flag only). Colours are 0xRRGGBB.
     */
    fun styleWindowFrame(hwnd: Long, dark: Boolean, caption: Int?, text: Int?, border: Int?) {
        runCatching {
            val lib = dwm ?: return
            if (hwnd == 0L) return
            val handle = Pointer(hwnd)
            fun set(attribute: Int, value: Int) {
                val m = Memory(4).apply { setInt(0, value) }
                lib.DwmSetWindowAttribute(handle, attribute, m, 4)
            }
            set(DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0)
            set(DWMWA_USE_IMMERSIVE_DARK_MODE_OLD, if (dark) 1 else 0)
            caption?.let { set(DWMWA_CAPTION_COLOR, colorRef(it)) }
            text?.let { set(DWMWA_TEXT_COLOR, colorRef(it)) }
            border?.let { set(DWMWA_BORDER_COLOR, colorRef(it)) }
        }
    }

    private fun colorRef(rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE_OLD = 19
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_BORDER_COLOR = 34
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    // ------------------------------------------------------------------ identity

    /** Windows' own install id (HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid). */
    fun machineGuid(): String? =
        regGetString(Root.LOCAL_MACHINE, "SOFTWARE\\Microsoft\\Cryptography", "MachineGuid")
}
