package com.ars3nb.volta.core.vpn

import android.util.Log
import java.io.File

/**
 * JNI bridge for hev-socks5-tunnel (MIT). The native library registers its methods
 * against this exact class path in JNI_OnLoad, so neither the package nor the method
 * names may be renamed — see proguard-rules.pro.
 */
class Tun2Socks(private val workDir: File) {

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    fun start(tunFd: Int, mtu: Int, socksPort: Int = 10808): Result<Unit> = runCatching {
        check(libraryLoaded) { "Библиотека TUN-моста не загрузилась" }
        check(tunFd >= 0) { "Некорректный TUN fd" }
        if (isRunning()) stop()

        val config = File(workDir, "tun2socks.yml")
        config.writeText(
            """
            tunnel:
              mtu: ${mtu.coerceIn(1280, 9000)}
              ipv4: 10.10.14.1
              ipv6: 'fd00:10:10::1'
              icmp: 'reply'
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
              pipeline: false
            misc:
              task-stack-size: 86016
              connect-timeout: 15000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-file: stderr
              log-level: error
            """.trimIndent()
        )
        check(TProxyStartService(config.absolutePath, tunFd)) {
            "Не удалось запустить TUN-мост"
        }
        Log.i(TAG, "Bridge started: fd=$tunFd mtu=$mtu socks=127.0.0.1:$socksPort")
    }

    /** Never throws: cleanup paths must not mask the original failure. */
    fun stop() {
        if (!libraryLoaded) return
        runCatching { if (TProxyIsRunning()) TProxyStopService() }
            .onFailure { Log.w(TAG, "stop error", it) }
    }

    fun isRunning(): Boolean =
        libraryLoaded && runCatching { TProxyIsRunning() }.getOrDefault(false)

    fun stats(): LongArray =
        if (!libraryLoaded) LongArray(4)
        else runCatching { TProxyGetStats() }.getOrDefault(LongArray(4))

    companion object {
        private const val TAG = "Tun2Socks"

        /** Loading in a companion init would crash the process on a broken build. */
        val libraryLoaded: Boolean = try {
            System.loadLibrary("hev-socks5-tunnel")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "hev-socks5-tunnel not available", e)
            false
        }
    }
}
