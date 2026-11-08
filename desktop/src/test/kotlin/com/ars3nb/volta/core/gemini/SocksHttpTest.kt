package com.ars3nb.volta.core.gemini

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Gemini check's own little HTTP client against a SOCKS5 proxy on this machine: the
 * host name must reach the proxy unresolved (so the exit resolves it, never Windows),
 * redirects are followed with the cookies they set, chunked pages are read, and what
 * comes back is judged by the same rules as on Android.
 */
class SocksHttpTest {

    /** A SOCKS5 proxy that answers every request itself with [respond], keyed by the path. */
    private class FakeProxy(private val respond: (host: String, path: String, request: String) -> String) : AutoCloseable {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val hosts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val port: Int get() = server.localPort

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(s) }; runCatching { s.close() } }
                }
            }
        }

        private fun serve(s: Socket) {
            val input = DataInputStream(s.getInputStream())
            val out = s.getOutputStream()
            // Greeting: version, methods.
            require(input.readUnsignedByte() == 5)
            val methods = input.readUnsignedByte()
            input.readFully(ByteArray(methods))
            out.write(byteArrayOf(5, 0))
            // Request: VER CMD RSV ATYP ...
            require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1)
            input.readUnsignedByte()
            val host = when (input.readUnsignedByte()) {
                3 -> ByteArray(input.readUnsignedByte()).also { input.readFully(it) }.toString(Charsets.US_ASCII)
                1 -> "ip:" + ByteArray(4).also { input.readFully(it) }.joinToString(".") { (it.toInt() and 0xFF).toString() }
                else -> "other"
            }
            input.readUnsignedShort()
            hosts += host
            out.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 0))
            out.flush()
            val request = readHead(input)
            requests += request
            val path = request.lineSequence().first().split(' ')[1]
            out.write(respond(host, path, request).toByteArray(Charsets.UTF_8))
            out.flush()
        }

        private fun readHead(input: InputStream): String {
            val b = StringBuilder()
            while (!b.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c < 0) break
                b.append(c.toChar())
            }
            return b.toString()
        }

        override fun close() = server.close()
    }

    private fun chunked(body: String): String {
        val half = body.length / 2
        return "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "${Integer.toHexString(body.substring(0, half).toByteArray().size)}\r\n${body.substring(0, half)}\r\n" +
            "${Integer.toHexString(body.substring(half).toByteArray().size)}\r\n${body.substring(half)}\r\n0\r\n\r\n"
    }

    private val geminiPage = "<html><script>window.WIZ_global_data = {\"Im6cmf\":\"/_/BardChatUi\",\"45631641\":true};</script>" +
        "<script>var og=[null,[1,2,3],2,1,200,\"USA\",null,\"en\"];</script></html>"

    @Test
    fun theNameGoesToTheProxyAndRedirectsCarryTheirCookies() {
        FakeProxy { _, path, _ ->
            when (path) {
                "/" -> "HTTP/1.1 302 Found\r\nLocation: /app?hl=en\r\nSet-Cookie: NID=abc; Domain=.gemini.invalid; Path=/\r\nContent-Length: 0\r\n\r\n"
                else -> chunked(geminiPage)
            }
        }.use { proxy ->
            val cookies = mutableListOf(SocksHttp.Cookie("SOCS", "CAI", "gemini.invalid"))
            val response = SocksHttp.get(
                url = "http://gemini.invalid/",
                socksPort = proxy.port,
                headers = mapOf("User-Agent" to GeminiMarkers.USER_AGENT),
                cookies = cookies,
                timeoutMs = 5_000,
                bodyLimit = GeminiMarkers.BODY_LIMIT_BYTES
            )
            assertEquals(200, response.code)
            assertEquals("http://gemini.invalid/app?hl=en", response.finalUrl)
            assertEquals(geminiPage, response.body)
            // Unresolved: the proxy got the name, not an address this machine looked up.
            assertEquals(listOf("gemini.invalid", "gemini.invalid"), proxy.hosts.toList())
            assertTrue(proxy.requests[0].contains("Cookie: SOCS=CAI"))
            assertTrue(proxy.requests[1].contains("NID=abc"))
            assertTrue(proxy.requests[1].contains("Accept-Encoding: identity"))
        }
    }

    @Test
    fun thePageIsJudgedAsOnAndroid() = runBlocking {
        FakeProxy { _, _, _ -> chunked(geminiPage) }.use { proxy ->
            val result = GeminiProbe.fetch(proxy.port, url = "http://gemini.invalid/")
            assertEquals(GeminiStatus.AVAILABLE, result.status)
            assertEquals(GeminiReason.FLAG_ON, result.reason)
            assertEquals("USA", result.region)
        }
    }

    @Test
    fun nothingListeningIsANetworkFailureNotACrash() = runBlocking {
        val free = ServerSocket(0).use { it.localPort }
        val result = GeminiProbe.fetch(free, url = "http://gemini.invalid/", timeoutMs = 2_000)
        assertEquals(GeminiStatus.BLOCKED_OR_ERROR, result.status)
        assertEquals(GeminiReason.NETWORK, result.reason)
    }

    @Test
    fun aSilentProxyRunsOutOfTime() = runBlocking {
        // Accepts and never answers.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { silent ->
            thread(isDaemon = true) { runCatching { while (true) silent.accept() } }
            val result = GeminiProbe.fetch(silent.localPort, url = "http://gemini.invalid/", timeoutMs = 600)
            assertEquals(GeminiReason.TIMEOUT, result.reason)
        }
    }

    @Test
    fun cookiesAreKeptPerSiteAndAnExpiryTakesThemAway() {
        val jar = mutableListOf<SocksHttp.Cookie>()
        SocksHttp.remember(jar, "A=1; Domain=.google.com; Path=/", "accounts.google.com")
        SocksHttp.remember(jar, "B=2; Path=/", "gemini.google.com")
        // A domain the host isn't in is not taken: the cookie stays with the host.
        SocksHttp.remember(jar, "C=3; Domain=example.org", "gemini.google.com")
        assertEquals(setOf("google.com", "gemini.google.com"), jar.map { it.domain }.toSet())
        assertTrue(SocksHttp.domainMatches("gemini.google.com", "google.com"))
        assertTrue(!SocksHttp.domainMatches("evilgoogle.com", "google.com"))
        SocksHttp.remember(jar, "A=gone; Domain=google.com; Max-Age=0", "gemini.google.com")
        assertEquals(listOf("B", "C"), jar.map { it.name }.sorted())
    }

    @Suppress("unused")
    private fun OutputStream.line(text: String) = write("$text\r\n".toByteArray())
}
