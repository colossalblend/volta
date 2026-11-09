package com.ars3nb.volta.core.gemini

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.nio.charset.Charset
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * A page fetched through a local SOCKS5 port, the way Android's OkHttp does it for the
 * Gemini check: the host name goes to the proxy unresolved, so the exit resolves it and
 * Google sees the exit's DNS and address, never this computer's. Redirects are followed
 * with the cookies they set (a browser's way: Google's hops rely on them), and at most
 * [bodyLimit] bytes of the page are read.
 *
 * Java's own HttpURLConnection resolves the name on this computer before it asks the
 * proxy, and java.net.http has no SOCKS at all: hence this small client. HTTP/1.1 with
 * `Connection: close`, identity encoding, chunked bodies; TLS with the host name checked.
 */
internal object SocksHttp {

    data class Response(val code: Int, val finalUrl: String, val body: String)

    /** One cookie as the client keeps it for a single check. */
    data class Cookie(val name: String, val value: String, val domain: String)

    private const val MAX_REDIRECTS = 8
    private const val MAX_HEADER_BYTES = 64 * 1024

    /**
     * GET [url] through the SOCKS proxy on 127.0.0.1:[socksPort]. Throws [SocketTimeoutException]
     * when [timeoutMs] runs out, any other [IOException] when the request fails.
     * [onSocket] gets each socket opened, so a cancelled check can close it at once.
     */
    fun get(
        url: String,
        socksPort: Int,
        headers: Map<String, String>,
        cookies: MutableList<Cookie>,
        timeoutMs: Long,
        bodyLimit: Int,
        onSocket: AtomicReference<Socket?>? = null
    ): Response {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = URI(url)
        repeat(MAX_REDIRECTS + 1) {
            val hop = once(current, socksPort, headers, cookies, deadline, bodyLimit, onSocket)
            val location = hop.location
            if (hop.code in REDIRECTS && location != null) {
                current = current.resolve(location.trim().replace(" ", "%20"))
                return@repeat
            }
            return Response(hop.code, current.toString(), hop.body)
        }
        throw IOException("too many redirects")
    }

    private val REDIRECTS = setOf(301, 302, 303, 307, 308)

    private class Hop(val code: Int, val location: String?, val body: String)

    private fun once(
        uri: URI,
        socksPort: Int,
        headers: Map<String, String>,
        cookies: MutableList<Cookie>,
        deadline: Long,
        bodyLimit: Int,
        onSocket: AtomicReference<Socket?>?
    ): Hop {
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: throw IOException("no scheme")
        if (scheme != "https" && scheme != "http") throw IOException("unsupported scheme $scheme")
        val host = uri.host ?: throw IOException("no host")
        val tls = scheme == "https"
        val port = if (uri.port > 0) uri.port else if (tls) 443 else 80
        val raw = Socket(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
        onSocket?.set(raw)
        try {
            raw.soTimeout = remaining(deadline)
            // Unresolved: Java's SOCKS client then sends the name itself (SOCKS5 "domain").
            raw.connect(InetSocketAddress.createUnresolved(host, port), remaining(deadline))
            val socket: Socket = if (tls) {
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, port, true) as SSLSocket
                ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                ssl.soTimeout = remaining(deadline)
                ssl.startHandshake()
                onSocket?.set(ssl)
                ssl
            } else {
                raw
            }
            socket.use { s ->
                val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") +
                    (uri.rawQuery?.let { "?$it" } ?: "")
                val request = StringBuilder()
                    .append("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(if (uri.port > 0) "$host:${uri.port}" else host).append("\r\n")
                headers.forEach { (k, v) -> request.append(k).append(": ").append(v).append("\r\n") }
                request.append("Accept-Encoding: identity\r\n")
                request.append("Connection: close\r\n")
                val sent = cookies.filter { domainMatches(host, it.domain) }
                if (sent.isNotEmpty()) {
                    request.append("Cookie: ").append(sent.joinToString("; ") { "${it.name}=${it.value}" }).append("\r\n")
                }
                request.append("\r\n")
                s.getOutputStream().apply {
                    write(request.toString().toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
                val input = s.getInputStream().buffered()
                val head = readHead(input, deadline, s)
                val lines = head.split("\r\n")
                val status = lines.firstOrNull().orEmpty().split(' ')
                val code = status.getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line")
                val fields = lines.drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim().lowercase(Locale.ROOT) to line.substring(i + 1).trim()
                }
                fields.filter { it.first == "set-cookie" }.forEach { (_, v) -> remember(cookies, v, host) }
                val location = fields.firstOrNull { it.first == "location" }?.second
                if (code in REDIRECTS && location != null) return Hop(code, location, "")
                val type = fields.firstOrNull { it.first == "content-type" }?.second.orEmpty()
                val chunked = fields.any { it.first == "transfer-encoding" && it.second.lowercase(Locale.ROOT).contains("chunked") }
                val length = fields.firstOrNull { it.first == "content-length" }?.second?.toLongOrNull()
                val bytes = when {
                    chunked -> readChunked(input, bodyLimit, deadline, s)
                    length != null -> readUpTo(input, minOf(length, bodyLimit.toLong()).toInt(), deadline, s)
                    else -> readUpTo(input, bodyLimit, deadline, s)
                }
                return Hop(code, null, String(bytes, charsetOf(type)))
            }
        } finally {
            onSocket?.compareAndSet(raw, null)
            runCatching { raw.close() }
        }
    }

    /** The cookie a `Set-Cookie` line sets, or takes away (an expiry in the past). */
    internal fun remember(cookies: MutableList<Cookie>, header: String, host: String) {
        val parts = header.split(';').map { it.trim() }
        val pair = parts.firstOrNull() ?: return
        val eq = pair.indexOf('=')
        if (eq <= 0) return
        val name = pair.substring(0, eq).trim()
        val value = pair.substring(eq + 1).trim()
        val attrs = parts.drop(1).associate { a ->
            val i = a.indexOf('=')
            if (i < 0) a.lowercase(Locale.ROOT) to "" else a.substring(0, i).trim().lowercase(Locale.ROOT) to a.substring(i + 1).trim()
        }
        val domain = attrs["domain"]?.trimStart('.')?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() && domainMatches(host, it) }
            ?: host.lowercase(Locale.ROOT)
        cookies.removeAll { it.name == name && it.domain == domain }
        val gone = attrs["max-age"]?.toLongOrNull()?.let { it <= 0 } == true
        if (!gone) cookies += Cookie(name, value, domain)
    }

    internal fun domainMatches(host: String, domain: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        val d = domain.lowercase(Locale.ROOT).trimStart('.')
        return h == d || h.endsWith(".$d")
    }

    private fun remaining(deadline: Long): Int {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) throw SocketTimeoutException("deadline")
        return left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun readHead(input: InputStream, deadline: Long, socket: Socket): String {
        val out = ByteArrayOutputStream()
        var matched = 0
        val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (matched < 4) {
            socket.soTimeout = remaining(deadline)
            val b = input.read()
            if (b < 0) throw IOException("connection closed in the headers")
            out.write(b)
            matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            if (out.size() > MAX_HEADER_BYTES) throw IOException("headers too long")
        }
        return out.toString(Charsets.ISO_8859_1.name()).removeSuffix("\r\n\r\n")
    }

    private fun readUpTo(input: InputStream, limit: Int, deadline: Long, socket: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16_384)
        while (out.size() < limit) {
            socket.soTimeout = remaining(deadline)
            val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun readLine(input: InputStream, deadline: Long, socket: Socket): String {
        val out = StringBuilder()
        while (true) {
            socket.soTimeout = remaining(deadline)
            val b = input.read()
            if (b < 0) break
            if (b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
            if (out.length > 1024) throw IOException("chunk line too long")
        }
        return out.toString()
    }

    private fun readChunked(input: InputStream, limit: Int, deadline: Long, socket: Socket): ByteArray {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val size = readLine(input, deadline, socket).substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) break
            val take = minOf(size, limit - out.size())
            out.write(readUpTo(input, take, deadline, socket))
            if (take < size) break
            readLine(input, deadline, socket) // the CRLF after the chunk
        }
        return out.toByteArray()
    }

    private fun charsetOf(contentType: String): Charset {
        val name = contentType.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')?.trim('"', ' ')
        return name?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    /** True when [e] means the time ran out rather than the request failing. */
    fun isTimeout(e: Throwable): Boolean = e is SocketTimeoutException || e is InterruptedIOException
}
