package com.ars3nb.volta.core.gemini

import android.content.Context
import android.util.Log
import com.ars3nb.volta.core.vpn.CertPins
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.core.vpn.XrayCore
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer

/**
 * Opens gemini.google.com through a server and reads the verdict ([GeminiPage]).
 *
 * The app is outside its own VPN, so a plain request would ask from the phone's own
 * address. It goes through a SOCKS port instead: the running tunnel's check port
 * ([ConfigBuilder.EXIT_CHECK_PORT], which always leaves through the exit node, whatever
 * the routing says) for the server in use, or, for any other server, a short-lived core
 * of its own with just that server and a local SOCKS inbound: the same throw-away
 * instance a real-delay measurement makes, only with a port to send a whole page through.
 * The host name goes to the exit unresolved, so Google sees the exit's DNS and address.
 */
class GeminiProbe(private val context: Context) {

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
        private const val TAG = "GeminiProbe"

        /** One throw-away core at a time is plenty: each is a whole Xray instance. */
        private val ownCore = Mutex()

        private val base: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(GeminiMarkers.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(GeminiMarkers.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(GeminiMarkers.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .callTimeout(GeminiMarkers.TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(false)
                .build()
        }

        /** Servers whose traffic only a helper process carries: OpenFlux, AmneziaWG, Hysteria v1. */
        fun needsHelper(server: ServerEntity): Boolean =
            server.protocol == ProtocolType.OPENFLUX ||
                server.protocol == ProtocolType.AMNEZIAWG ||
                server.protocol == ProtocolType.HYSTERIA

        /** How [server] can be checked, given which server (if any) the tunnel runs on. */
        fun routeFor(server: ServerEntity, liveServerId: Long?): Route = when {
            liveServerId != null && liveServerId == server.id -> Route.TUNNEL
            ConfigBuilder.dialProblem(server) != null -> Route.NONE
            needsHelper(server) -> Route.ONLY_WHEN_CONNECTED
            !XrayCore.isCoreAvailable -> Route.NONE
            else -> Route.OWN_CORE
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
    }

    /**
     * The check for [server]. [liveServerId] is the server the tunnel runs on while
     * connected, else null. Never throws: a failure is a [GeminiStatus.BLOCKED_OR_ERROR].
     */
    suspend fun check(server: ServerEntity, liveServerId: Long?): GeminiResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val result = when (routeFor(server, liveServerId)) {
            Route.TUNNEL -> fetch(ConfigBuilder.EXIT_CHECK_PORT)
            Route.OWN_CORE -> throughOwnCore(server)
            Route.ONLY_WHEN_CONNECTED ->
                GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.NEEDS_CONNECTION)
            Route.NONE -> GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CANNOT_DIAL)
        }
        result.copy(checkedAt = now)
    }

    private suspend fun throughOwnCore(server: ServerEntity): GeminiResult = ownCore.withLock {
        val pinned = runCatching { CertPins.apply(server) }.getOrDefault(server)
        val probe = runCatching { ConfigBuilder.buildProbe(pinned) }.getOrNull()
            ?: return GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.CANNOT_DIAL)
        val port = freePort()
            ?: return GeminiPage.failed(timeout = false)
        val core = XrayCore(context)
        try {
            val started = core.start(ownCoreConfig(probe, port), tunFd = 0)
            if (started.isFailure || !listens(port)) {
                Log.d(TAG, "own core did not start: ${started.exceptionOrNull()?.message}")
                return GeminiPage.failed(timeout = false)
            }
            fetch(port)
        } finally {
            runCatching { core.stop() }
        }
    }

    /** gemini.google.com through the SOCKS proxy on [port], read and judged. */
    private fun fetch(port: Int): GeminiResult {
        val client = base.newBuilder()
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
            // A browser keeps what a redirect sets; Google's hops may rely on it.
            .cookieJar(MemoryCookies())
            .build()
        val request = Request.Builder()
            .url(GeminiMarkers.URL)
            .header("User-Agent", GeminiMarkers.USER_AGENT)
            .header("Accept", GeminiMarkers.ACCEPT)
            .header("Accept-Language", GeminiMarkers.ACCEPT_LANGUAGE)
            .get()
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.let { body ->
                    val source = body.source()
                    val buffer = Buffer()
                    while (buffer.size < GeminiMarkers.BODY_LIMIT_BYTES) {
                        if (source.read(buffer, 16_384) == -1L) break
                    }
                    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    buffer.readString(minOf(buffer.size, GeminiMarkers.BODY_LIMIT_BYTES.toLong()), charset)
                }.orEmpty()
                GeminiPage.evaluate(response.code, response.request.url.toString(), text)
            }
        } catch (_: InterruptedIOException) {
            GeminiPage.failed(timeout = true)
        } catch (e: Exception) {
            Log.d(TAG, "request failed: ${e.message}")
            GeminiPage.failed(timeout = false)
        }
    }

    private fun freePort(): Int? = runCatching {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
    }.getOrNull()

    /** The core binds its inbound while starting; a few tries cover a slow phone. */
    private fun listens(port: Int): Boolean {
        repeat(10) {
            val ok = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
            }.isSuccess
            if (ok) return true
            Thread.sleep(100)
        }
        return false
    }

    /** Cookies for one check only, starting with the consent answer: nothing is kept afterwards. */
    private class MemoryCookies : CookieJar {
        private val cookies = mutableListOf(
            Cookie.Builder()
                .name(GeminiMarkers.CONSENT_COOKIE_NAME)
                .value(GeminiMarkers.CONSENT_COOKIE_VALUE)
                .domain(GeminiMarkers.CONSENT_COOKIE_DOMAIN)
                .path("/")
                .secure()
                .build()
        )

        @Synchronized
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { c -> this.cookies.removeAll { it.name == c.name && it.domain == c.domain } }
            this.cookies += cookies
        }

        @Synchronized
        override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }
}
