package com.ars3nb.volta.core.vpn

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ars3nb.volta.core.HwidProvider
import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thin wrapper around libv2ray (2dust/AndroidLibXrayLite, Xray-core v26) loaded through
 * reflection so the project still compiles when `app/libs/libv2ray.aar` is absent.
 *
 * Verified native surface:
 *   Libv2ray.initCoreEnv(assetPath, xudpBaseKey)
 *   Libv2ray.newCoreController(CoreCallbackHandler) -> CoreController
 *   Libv2ray.measureOutboundDelay(configJson, url) -> long
 *   CoreController.startLoop(configJson, tunFd)   // tunFd == 0 -> no TUN inbound
 *   CoreController.stopLoop() / getIsRunning() / queryStats(tag, direction)
 *
 * Each instance owns its own controller: measuring a node never disturbs a live tunnel.
 */
class XrayCore(
    private val context: Context,
    private val onCoreShutdown: () -> Unit = {}
) {

    companion object {
        private const val TAG = "XrayCore"
        private const val LIB_CLASS = "libv2ray.Libv2ray"
        private const val CALLBACK_CLASS = "libv2ray.CoreCallbackHandler"

        private val envReady = AtomicBoolean(false)

        val isCoreAvailable: Boolean by lazy {
            try {
                Class.forName(LIB_CLASS)
                true
            } catch (_: Throwable) {
                Log.w(TAG, "libv2ray not found — VPN core in stub mode")
                false
            }
        }

        fun version(): String = runCatching {
            Class.forName(LIB_CLASS).getMethod("checkVersionX").invoke(null) as String
        }.getOrDefault("")

        /**
         * Real end-to-end delay through [configJson]'s proxy outbound, in ms.
         * Runs its own short-lived core instance, so it is safe while the tunnel is up.
         * Returns -1 when the node does not answer.
         */
        fun measureOutboundDelay(context: Context, configJson: String, url: String): Long {
            if (!isCoreAvailable) return -1
            return try {
                prepareEnv(context)
                val value = Class.forName(LIB_CLASS)
                    .getMethod("measureOutboundDelay", String::class.java, String::class.java)
                    .invoke(null, configJson, url) as? Long ?: -1L
                if (value > 0) value else -1L
            } catch (e: Throwable) {
                Log.d(TAG, "measureOutboundDelay failed: ${e.cause?.message ?: e.message}")
                -1L
            }
        }

        /**
         * Copies geoip/geosite out of the APK once and points the core at them.
         * The .dat files ship inside libv2ray.aar and are merged into app assets by AGP.
         */
        @Synchronized
        private fun prepareEnv(context: Context) {
            if (envReady.get()) return
            installGeodata(context)
            runCatching {
                Class.forName(LIB_CLASS)
                    .getMethod("initCoreEnv", String::class.java, String::class.java)
                    .invoke(null, context.filesDir.absolutePath, xudpBaseKey(context))
            }.onFailure { Log.w(TAG, "initCoreEnv failed", it) }
            envReady.set(true)
        }

        private fun installGeodata(context: Context) {
            // A new build ships new geoip/geosite; without a stamp the old copies would
            // stay forever and routing rules would drift out of date.
            val stamp = File(context.filesDir, "geodata.version")
            val expected = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    ?: "0"
            }.getOrDefault("0")
            val current = runCatching { stamp.readText().trim() }.getOrDefault("")
            val reinstall = current != expected

            listOf("geoip.dat", "geosite.dat").forEach { name ->
                val destination = File(context.filesDir, name)
                if (!reinstall && destination.isFile && destination.length() > 0) return@forEach

                val temporary = File(context.filesDir, "$name.tmp")
                temporary.delete()
                try {
                    context.assets.open(name).use { input ->
                        temporary.outputStream().buffered().use(input::copyTo)
                    }
                    check(temporary.length() > 0) { "Asset $name is empty" }
                    // rename over an existing file fails on some filesystems.
                    destination.delete()
                    check(temporary.renameTo(destination)) { "Could not install $name" }
                } catch (e: Exception) {
                    temporary.delete()
                    throw IllegalStateException(
                        "Не удалось подготовить данные маршрутизации ($name). " +
                            "Переустановите приложение.",
                        e
                    )
                }
            }
            runCatching { stamp.writeText(expected) }
        }

        /** Stable per-device XUDP base key so UDP over VLESS is not sent in the clear. */
        private fun xudpBaseKey(context: Context): String = runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(HwidProvider.getHwid(context).toByteArray(Charsets.UTF_8))
            Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
        }.getOrDefault("")
    }

    private var controller: Any? = null

    /** Only true while THIS instance owns a running core. */
    fun isRunning(): Boolean {
        val c = controller ?: return false
        if (c === STUB) return true
        return runCatching {
            c.javaClass.getMethod("getIsRunning").invoke(c) as? Boolean ?: false
        }.getOrDefault(false)
    }

    fun start(configJson: String, tunFd: Int = 0): Result<Unit> {
        if (controller != null) stop()
        return try {
            if (!isCoreAvailable) {
                // Stub mode exists only so the UI can be exercised in builds without the core.
                Log.i(TAG, "Stub core started (${configJson.length} config bytes)")
                controller = STUB
                return Result.success(Unit)
            }

            prepareEnv(context)

            val libClass = Class.forName(LIB_CLASS)
            val callbackType = Class.forName(CALLBACK_CLASS)
            val callback = Proxy.newProxyInstance(
                callbackType.classLoader,
                arrayOf(callbackType),
                ::handleCallback
            )
            val instance = libClass
                .getMethod("newCoreController", callbackType)
                .invoke(null, callback)
                ?: error("libv2ray не вернул CoreController")

            // Second argument is the TUN fd; 0 means "no tun inbound".
            // ponytail: the core can drive the TUN itself, which would retire
            // hev-socks5-tunnel — swap only with a device to test the data path on.
            instance.javaClass
                .getMethod("startLoop", String::class.java, Int::class.javaPrimitiveType)
                .invoke(instance, configJson, tunFd)

            controller = instance
            Result.success(Unit)
        } catch (e: Throwable) {
            val cause = e.cause ?: e
            Log.e(TAG, "Failed to start core", cause)
            controller = null
            Result.failure(
                IllegalStateException(cause.message?.takeIf { it.isNotBlank() }
                    ?: "Ядро VPN не запустилось", cause)
            )
        }
    }

    fun stop() {
        val instance = controller
        controller = null
        if (instance == null || instance === STUB) return
        runCatching {
            instance.javaClass.getMethod("stopLoop").invoke(instance)
        }.onFailure { Log.w(TAG, "stopLoop error", it.cause ?: it) }
    }

    /**
     * Cumulative bytes for an outbound tag. `direction` is "uplink" or "downlink".
     * Xray resets the counter on read, so callers accumulate deltas themselves.
     */
    fun queryStats(tag: String, direction: String): Long {
        val instance = controller ?: return 0
        if (instance === STUB) return 0
        return runCatching {
            instance.javaClass
                .getMethod("queryStats", String::class.java, String::class.java)
                .invoke(instance, tag, direction) as? Long ?: 0L
        }.getOrDefault(0L)
    }

    private fun handleCallback(proxy: Any, method: Method, args: Array<out Any?>?): Any {
        return when (method.name) {
            "startup" -> 0L
            "shutdown" -> {
                controller = null
                runCatching { onCoreShutdown() }
                0L
            }

            "onEmitStatus" -> {
                Log.d(TAG, "core status=${args?.getOrNull(0)} msg=${args?.getOrNull(1)}")
                0L
            }

            "toString" -> "VoltaCoreCallback"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.getOrNull(0)
            else -> 0L
        }
    }
}

/** Marker for "running without the native core", so isRunning() stays honest. */
private val STUB = Any()
