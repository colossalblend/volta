package com.ars3nb.volta.desktop.platform

import com.google.gson.Gson
import java.io.File

/**
 * The Windows proxy setting for the current user, switched to Volta's local HTTP proxy
 * while connected and given back afterwards.
 *
 * What was there before is written to [backupFile] *before* anything is changed, and
 * the file stays until the old settings are back. So whatever ends Volta (a crash, a
 * killed process, a power cut), the next start finds the file and restores them: the
 * user is never left with a proxy pointing at a port nobody listens on.
 */
class SystemProxy(
    private val backupFile: File,
    private val api: Api = WindowsApi
) {
    /** What Windows stores; null means "the value was not there". */
    data class Snapshot(
        val proxyEnable: Int? = null,
        val proxyServer: String? = null,
        val proxyOverride: String? = null,
        val autoConfigUrl: String? = null,
        val flags: Int? = null
    )

    private data class Backup(val previous: Snapshot = Snapshot(), val applied: String = "")

    /** The few Windows calls this needs, so the logic can be tested without Windows. */
    interface Api {
        val available: Boolean
        fun read(): Snapshot
        fun write(snapshot: Snapshot): Boolean
    }

    private val gson = Gson()

    /** True while Volta's proxy is the one Windows uses. */
    fun isApplied(): Boolean {
        val backup = readBackup() ?: return false
        val now = api.read()
        return now.proxyEnable == 1 && now.proxyServer == backup.applied
    }

    /**
     * Points Windows at 127.0.0.1:[httpPort]. Local addresses keep going direct. False
     * when Windows refused; the previous settings are then put back at once.
     */
    @Synchronized
    fun apply(httpPort: Int): Boolean {
        if (!api.available) return false
        val target = "127.0.0.1:$httpPort"
        // Already switched (a reconnect): keep the backup of the user's own settings.
        val previous = readBackup()?.previous ?: api.read()
        if (!writeBackup(Backup(previous, target))) return false
        val ours = Snapshot(
            proxyEnable = 1,
            proxyServer = target,
            proxyOverride = BYPASS,
            autoConfigUrl = null,
            flags = WinApi.PROXY_TYPE_DIRECT or WinApi.PROXY_TYPE_PROXY
        )
        if (!api.write(ours)) {
            restore()
            return false
        }
        val now = api.read()
        if (now.proxyEnable != 1 || now.proxyServer != target) {
            restore()
            return false
        }
        return true
    }

    /**
     * Puts back what was there before [apply]. If the user (or another program) changed
     * the proxy in the meantime, their setting stays and only the backup goes.
     */
    @Synchronized
    fun restore(): Boolean {
        val backup = readBackup() ?: return true
        if (!api.available) return false
        val now = api.read()
        val stillOurs = now.proxyServer == backup.applied || now.proxyServer.isNullOrBlank()
        val ok = if (stillOurs) api.write(backup.previous) else true
        if (ok) backupFile.delete()
        return ok
    }

    /** At start: a backup left behind means Volta ended while connected. */
    fun recoverAfterCrash(): Boolean = if (backupFile.isFile) restore() else false

    private fun readBackup(): Backup? = runCatching {
        if (!backupFile.isFile) null else gson.fromJson(backupFile.readText(), Backup::class.java)
    }.getOrNull()

    private fun writeBackup(backup: Backup): Boolean = runCatching {
        backupFile.parentFile?.mkdirs()
        val tmp = File(backupFile.parentFile, backupFile.name + ".tmp")
        tmp.writeText(gson.toJson(backup))
        if (!tmp.renameTo(backupFile)) {
            backupFile.delete()
            tmp.renameTo(backupFile) || run { tmp.copyTo(backupFile, overwrite = true); tmp.delete(); true }
        } else true
    }.getOrDefault(false)

    companion object {
        /** Addresses that never go through the proxy: this PC and the local network. */
        const val BYPASS = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;" +
            "172.22.*;172.23.*;172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;" +
            "172.31.*;192.168.*;169.254.*;<local>"
    }
}

/** The real thing: HKCU Internet Settings plus WinINet's per-connection options. */
object WindowsApi : SystemProxy.Api {
    private const val KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private val root = WinApi.Root.CURRENT_USER

    override val available: Boolean get() = WinApi.isWindows

    override fun read(): SystemProxy.Snapshot = SystemProxy.Snapshot(
        proxyEnable = WinApi.regGetInt(root, KEY, "ProxyEnable"),
        proxyServer = WinApi.regGetString(root, KEY, "ProxyServer"),
        proxyOverride = WinApi.regGetString(root, KEY, "ProxyOverride"),
        autoConfigUrl = WinApi.regGetString(root, KEY, "AutoConfigURL"),
        flags = WinApi.queryPerConnectionFlags()
    )

    override fun write(snapshot: SystemProxy.Snapshot): Boolean {
        val enable = snapshot.proxyEnable ?: 0
        // WinINet first: it keeps the binary DefaultConnectionSettings in step, which is
        // what the Settings page and browsers read.
        val flags = snapshot.flags ?: run {
            var f = WinApi.PROXY_TYPE_DIRECT
            if (enable == 1) f = f or WinApi.PROXY_TYPE_PROXY
            if (!snapshot.autoConfigUrl.isNullOrBlank()) f = f or WinApi.PROXY_TYPE_AUTO_PROXY_URL
            f
        }
        val perConnection = WinApi.setPerConnection(
            flags = flags,
            server = snapshot.proxyServer,
            bypass = snapshot.proxyOverride,
            autoConfigUrl = snapshot.autoConfigUrl
        )
        // Then the plain values, exactly as they were (absent ones removed).
        var ok = WinApi.regSetInt(root, KEY, "ProxyEnable", enable)
        ok = setOrDelete("ProxyServer", snapshot.proxyServer) && ok
        ok = setOrDelete("ProxyOverride", snapshot.proxyOverride) && ok
        ok = setOrDelete("AutoConfigURL", snapshot.autoConfigUrl) && ok
        WinApi.notifyProxyChanged()
        return ok || perConnection
    }

    private fun setOrDelete(name: String, value: String?): Boolean =
        if (value == null) WinApi.regDelete(root, KEY, name) else WinApi.regSetString(root, KEY, name, value)
}
