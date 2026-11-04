package com.ars3nb.volta.core.vpn

import android.content.Context
import android.util.Log
import com.ars3nb.volta.R
import java.io.File

/**
 * JNI bridge for hev-socks5-tunnel (MIT). The native library registers its methods
 * against this exact class path in JNI_OnLoad, so neither the package nor the method
 * names may be renamed — see proguard-rules.pro.
 *
 * [context] is the service's: its files folder, and its language for the error texts.
 */
class Tun2Socks(private val context: Context) {

    private val workDir: File get() = context.filesDir

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    fun start(tunFd: Int, mtu: Int, socksPort: Int = 10808): Result<Unit> = runCatching {
        check(libraryLoaded) { context.getString(R.string.home_tun_unavailable) }
        check(tunFd >= 0) { context.getString(R.string.home_tun_refused) }
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
            context.getString(R.string.home_tun_bridge_failed)
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
