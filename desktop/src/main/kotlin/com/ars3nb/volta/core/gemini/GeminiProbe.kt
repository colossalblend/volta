package com.ars3nb.volta.core.gemini

import com.ars3nb.volta.core.vpn.CertPins
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.desktop.core.Ports
import com.ars3nb.volta.desktop.core.WindowsCompat
import com.ars3nb.volta.desktop.core.XrayCore
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Opens gemini.google.com through a server and reads the verdict ([GeminiPage]), as the
 * Android app does.
 *
 * Volta's own requests never go through the Windows proxy it sets, so a plain request
 * would ask from this computer's address. It goes through a SOCKS port instead: the
 * running tunnel's check port (ConfigBuilder.EXIT_CHECK_PORT's inbound, which always
 * leaves through the exit node, whatever the routing says) for the server in use, or,
 * for any other server, a short-lived xray.exe of its own with just that server and a
 * local SOCKS inbound: the same throw-away core a real-delay measurement starts. The
 * host name goes to the exit unresolved ([SocksHttp]).
 */
class GeminiProbe(private val core: XrayCore, private val workDir: File) {

    enum class Route {
        /** Through the running tunnel's check port. */
        TUNNEL,

        /** Through a core of its own made for this check. */
        OWN_CORE,

        /** Only through the tunnel: the server runs in a helper process (OpenFlux, AmneziaWG…). */
        ONLY_WHEN_CONNECTED,

        /** Not at all in this build. */
        NONE
    }

    companion object {
        /** One throw-away core at a time is plenty: each is a whole Xray process. */
        private val ownCore = Mutex()

        /** Servers whose traffic only a helper process carries: OpenFlux, AmneziaWG, Hysteria v1. */
        fun needsHelper(server: ServerEntity): Boolean =
            server.protocol == ProtocolType.OPENFLUX ||
                server.protocol == ProtocolType.AMNEZIAWG ||
                server.protocol == ProtocolType.HYSTERIA

        /**
         * How [server] can be checked, given which server (if any) the tunnel runs on.
         * A plain WireGuard `.conf` is dialled by the core itself on Windows (WindowsCompat),
         * so it needs no helper here.
         */
        fun routeFor(server: ServerEntity, liveServerId: Long?, coreAvailable: Boolean): Route {
            val adapted = WindowsCompat.adapt(server)
            return when {
                liveServerId != null && liveServerId == server.id -> Route.TUNNEL
                ConfigBuilder.dialProblem(adapted) != null -> Route.NONE
                needsHelper(adapted) -> Route.ONLY_WHEN_CONNECTED
                !coreAvailable -> Route.NONE
                else -> Route.OWN_CORE
            }
        }

        /**
         * The smallest config that serves [server] on a local SOCKS port: the probe
         * config real-delay measuring uses (the proxy outbound first, so it is the
         * default), plus one inbound.
         */
        internal fun ownCoreConfig(probeConfig: String, socksPort: Int): String {
            val root = JsonParser.parseString(probeConfig).asJsonObject
            root.add("inbounds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", "gemini-check")
                    addProperty("listen", "127.0.0.1")
                    addProperty("port", socksPort)
                    addProperty("protocol", "socks")
                    add("settings", JsonObject().apply {
                        addProperty("auth", "noauth")
                        addProperty("udp", false)
                    })
                })
            })
            return root.toString()
        }

        private const val START_TIMEOUT_MS = 6_000L

        /** gemini.google.com through the SOCKS proxy on [port], read and judged. */
        internal suspend fun fetch(port: Int, url: String = GeminiMarkers.URL, timeoutMs: Long = GeminiMarkers.TIMEOUT_MS): GeminiResult {
            val socket = AtomicReference<Socket?>(null)
            // A cancelled check lets go of its connection at once instead of waiting it out.
            val closer = currentCoroutineContext()[Job]?.invokeOnCompletion { runCatching { socket.get()?.close() } }
            return try {
                withContext(Dispatchers.IO) {
                    // A browser keeps what a redirect sets; Google's hops may rely on it.
                    // The consent answer comes first, so a European exit isn't stopped there.
                    val cookies = mutableListOf(
                        SocksHttp.Cookie(
                            GeminiMarkers.CONSENT_COOKIE_NAME,
                            GeminiMarkers.CONSENT_COOKIE_VALUE,
                            GeminiMarkers.CONSENT_COOKIE_DOMAIN
                        )
                    )
                    val response = SocksHttp.get(
                        url = url,
                        socksPort = port,
                        headers = linkedMapOf(
                            "User-Agent" to GeminiMarkers.USER_AGENT,
                            "Accept" to GeminiMarkers.ACCEPT,
                            "Accept-Language" to GeminiMarkers.ACCEPT_LANGUAGE
                        ),
                        cookies = cookies,
                        timeoutMs = timeoutMs,
                        bodyLimit = GeminiMarkers.BODY_LIMIT_BYTES,
                        onSocket = socket
                    )
                    GeminiPage.evaluate(response.code, response.finalUrl, response.body)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                GeminiPage.failed(timeout = SocksHttp.isTimeout(e))
            } finally {
                closer?.dispose()
            }
        }
    }

    /**
     * The check for [server]. [liveServerId] is the server the tunnel runs on while
     * connected, else null; [tunnelPort] the running tunnel's check port then. Never
     * throws: a failure is a [GeminiStatus.BLOCKED_OR_ERROR].
     */
    suspend fun check(server: ServerEntity, liveServerId: Long?, tunnelPort: Int?): GeminiResult {
        val now = System.currentTimeMillis()
        val result = when (routeFor(server, liveServerId, core.isPresent)) {
            Route.TUNNEL -> tunnelPort?.let { fetch(it) } ?: GeminiPage.failed(timeout = false)
            Route.OWN_CORE -> throughOwnCore(server)
            Route.ONLY_WHEN_CONNECTED ->
                GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.NEEDS_CONNECTION)
            Route.NONE -> GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CANNOT_DIAL)
        }
        return result.copy(checkedAt = now)
    }

    private suspend fun throughOwnCore(server: ServerEntity): GeminiResult = ownCore.withLock {
        val adapted = WindowsCompat.adapt(server)
        val pinned = withContext(Dispatchers.IO) { runCatching { CertPins.apply(adapted) }.getOrDefault(adapted) }
        val probe = runCatching { ConfigBuilder.buildProbe(pinned) }.getOrNull()
            ?: return GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CANNOT_DIAL)
        val port = runCatching { Ports.pickMany(1).single() }.getOrNull()
            ?: return GeminiPage.failed(timeout = false)
        val file = File(workDir, "gemini-${UUID.randomUUID()}.json")
        var process: Process? = null
        try {
            val started = withContext(Dispatchers.IO) {
                workDir.mkdirs()
                file.writeText(ownCoreConfig(probe, port))
                val log = File(workDir, "gemini.log")
                if (log.length() > 256 * 1024) log.delete()
                process = runCatching { core.start(file, log) }.getOrNull()
                process != null && Ports.awaitListening(listOf(port), process, START_TIMEOUT_MS)
            }
            if (!started) return GeminiPage.failed(timeout = false)
            fetch(port)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                process?.let { p ->
                    p.destroy()
                    if (!runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)) p.destroyForcibly()
                }
                file.delete()
            }
        }
    }
}
