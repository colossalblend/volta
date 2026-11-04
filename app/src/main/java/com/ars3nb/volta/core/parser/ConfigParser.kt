package com.ars3nb.volta.core.parser

import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.routing.HappRoutingParser
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionImportResult
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Parses everything a provider can hand us into [ServerEntity] rows:
 * share links (vless/vmess/trojan/ss/socks/hysteria/hysteria2/wireguard), AmneziaWG
 * and WireGuard `.conf` files and AmneziaVPN `vpn://` keys, plain or base64
 * subscription bodies, single Xray outbounds and full Xray configs.
 *
 * Protocol-specific knobs that have no dedicated column live in
 * [ServerEntity.extraJson] so new core options keep working without a migration.
 */
object ConfigParser {

    private val gson = Gson()

    /** Schemes we can turn into a working outbound. */
    private const val SUPPORTED_SCHEMES =
        "vless|vmess|trojan|ss|socks5|socks|hysteria2|hy2|hysteria|wireguard|wg|openflux|volta|vpn"

    /** A share link ends at any of these; everything else may legally appear in a remark. */
    private val LINK_TERMINATORS: Set<Char> = setOf(
        10.toChar(), 13.toChar(), 60.toChar(), 62.toChar(), 34.toChar(), 39.toChar(), 92.toChar()
    )

    /** Schemes we recognise but this core cannot dial — reported, never silently dropped. */
    private val UNSUPPORTED_SCHEMES = mapOf(
        "tuic" to "TUIC",
        "ssr" to "ShadowsocksR",
        "snell" to "Snell",
        "juicity" to "Juicity",
        "anytls" to "AnyTLS"
    )

    fun parseSubscriptionBody(body: String, subscriptionId: Long? = null): SubscriptionImportResult {
        var title = ""
        var expireAt = 0L
        var trafficUsed = 0L
        var trafficTotal = 0L
        var announce = ""
        var supportUrl = ""
        var webPageUrl = ""
        var updateInterval = 12
        var routingJson = ""

        // 1) Отделяем служебные строки (#…) от полезной нагрузки.
        //    Иначе https:// в #support-url мешает понять, что тело — base64.
        val normalizedBody = body.removePrefix("\uFEFF").trim()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        // A WireGuard / AmneziaWG .conf: its # lines are comments, not metadata.
        if (AmneziaConfigs.looksLikeConf(normalizedBody)) {
            return SubscriptionImportResult(
                servers = listOfNotNull(AmneziaConfigs.parseConf(normalizedBody, subscriptionId))
            )
        }
        val metaLines = mutableListOf<String>()
        val payloadLines = mutableListOf<String>()
        normalizedBody.lineSequence().forEach { line ->
            val t = line.trim().removePrefix("\uFEFF")
            when {
                t.isEmpty() -> Unit
                t.startsWith("#") || t.startsWith("//") -> metaLines += t
                else -> payloadLines += t
            }
        }

        fun applyMeta(t: String) {
            when {
                t.startsWith("#profile-title:", ignoreCase = true) ||
                    t.startsWith("//profile-title:", ignoreCase = true) ->
                    title = decodeProfileTitle(t.substringAfter(':').trim().trim('"'))

                t.startsWith("#profile-update-interval:", ignoreCase = true) ->
                    updateInterval = t.substringAfter(':').trim().toIntOrNull() ?: 12

                t.startsWith("#support-url:", ignoreCase = true) ->
                    supportUrl = t.substringAfter(':').trim()

                t.startsWith("#announce:", ignoreCase = true) ->
                    announce = decodeProfileTitle(t.substringAfter(':').trim())

                t.startsWith("#profile-web-page-url:", ignoreCase = true) ->
                    webPageUrl = t.substringAfter(':').trim()

                t.startsWith("#subscription-userinfo:", ignoreCase = true) -> {
                    parseUserInfo(t.substringAfter(':').trim())?.let { (u, tot, exp) ->
                        trafficUsed = u
                        trafficTotal = tot
                        expireAt = exp
                    }
                }

                t.startsWith("#routing-link:", ignoreCase = true) ||
                    t.startsWith("happ://routing/", ignoreCase = true) -> {
                    if (routingJson.isBlank()) {
                        routingJson = t.removePrefix("#routing-link:").trim()
                    }
                }

                t.startsWith("#routing-profile:", ignoreCase = true) -> {
                    routingJson = t.substringAfter(':').trim()
                }
            }
        }
        metaLines.forEach(::applyMeta)

        var content = payloadLines.joinToString("\n").trim()
            .removePrefix("\uFEFF")

        // 2) Если payload — сплошной base64 (как у многих панелей) — расшифровываем
        if (looksLikeBase64Payload(content)) {
            decodeBase64(content)?.let { decoded ->
                content = decoded.trim()
                // метаданные могли быть внутри base64
                content.lineSequence().forEach { line ->
                    val t = line.trim()
                    if (t.startsWith("#") || t.startsWith("//")) applyMeta(t)
                }
            }
        } else if (!content.contains("://") && !content.startsWith("{") && content.isNotBlank()) {
            // запасной путь: пробуем base64 даже если формат неочевиден
            decodeBase64(content)?.let { decoded ->
                if (decoded.contains("://") || decoded.trimStart().startsWith("{")) {
                    content = decoded.trim()
                }
            }
        }

        // 3a) JSON по группам от провайдера: {"wifi": [...], "lte": [...], "balancers": [...]}
        if (content.trimStart().startsWith("{")) {
            parseGroupedJson(content, subscriptionId)?.let { grouped ->
                return grouped.copy(
                    title = title.ifBlank { grouped.title },
                    expireAt = expireAt,
                    trafficUsed = trafficUsed,
                    trafficTotal = trafficTotal,
                    announce = announce.ifBlank { grouped.announce },
                    supportUrl = supportUrl.ifBlank { grouped.supportUrl },
                    webPageUrl = webPageUrl,
                    updateIntervalHours = updateInterval,
                    routingJson = routingJson.ifBlank { grouped.routingJson }
                )
            }
        }

        // 3) Полный Xray JSON (один объект или массив конфигов, как у v2rayNG)
        if (content.trimStart().startsWith("{")) {
            return parseXrayJson(content, subscriptionId).copy(
                title = title.ifBlank { "JSON config" },
                expireAt = expireAt,
                trafficUsed = trafficUsed,
                trafficTotal = trafficTotal,
                announce = announce,
                supportUrl = supportUrl,
                webPageUrl = webPageUrl,
                updateIntervalHours = updateInterval,
                routingJson = routingJson.ifBlank {
                    runCatching {
                        JsonParser.parseString(content).asJsonObject
                            .getAsJsonObject("routing")?.toString().orEmpty()
                    }.getOrDefault("")
                }
            )
        }
        if (content.trimStart().startsWith("[")) {
            parseXrayJsonArray(content, subscriptionId)?.let { parsed ->
                return parsed.copy(
                    title = title.ifBlank { parsed.title },
                    expireAt = expireAt,
                    trafficUsed = trafficUsed,
                    trafficTotal = trafficTotal,
                    announce = announce,
                    supportUrl = supportUrl,
                    webPageUrl = webPageUrl,
                    updateIntervalHours = updateInterval,
                    routingJson = routingJson.ifBlank { parsed.routingJson }
                )
            }
        }

        if (routingJson.isBlank()) {
            HappRoutingParser.extractRoutingProfileJson(content)?.let { routingJson = it }
        }

        // 4) Список ссылок (в т.ч. несколько в одной строке)
        val servers = extractShareLinks(content).flatMap { link ->
            // An AmneziaVPN key can hold several protocols of one server.
            if (link.startsWith("vpn://", ignoreCase = true)) {
                AmneziaConfigs.parseAmneziaKey(link, subscriptionId)
            } else {
                listOfNotNull(parseShareLink(link, subscriptionId))
            }
        }

        return SubscriptionImportResult(
            servers = servers,
            routingJson = routingJson,
            title = title,
            expireAt = expireAt,
            trafficUsed = trafficUsed,
            trafficTotal = trafficTotal,
            announce = announce,
            supportUrl = supportUrl,
            webPageUrl = webPageUrl,
            updateIntervalHours = updateInterval,
            skipped = unsupportedProtocols(content)
        )
    }

    /**
     * Extracts links even when a provider returns one line, HTML, or mixed metadata.
     *
     * A link runs until the next scheme, a line break or an HTML delimiter — stopping at
     * the first space would truncate the very common unencoded "#Название сервера".
     */
    fun extractShareLinks(content: String): List<String> {
        val starts = Regex("(?i)(?:$SUPPORTED_SCHEMES)://")
            .findAll(content)
            .map { it.range.first }
            .toList()
        if (starts.isEmpty()) return emptyList()

        val found = LinkedHashSet<String>()
        starts.forEachIndexed { index, start ->
            val hardEnd = starts.getOrNull(index + 1) ?: content.length
            var end = start
            while (end < hardEnd && content[end] !in LINK_TERMINATORS) end++
            var raw = content.substring(start, end)
            // Spaces are only legal inside the remark, i.e. after '#'.
            if (!raw.contains('#')) raw = raw.substringBefore(' ')
            raw.trim().trimEnd(',', ';', ')', ']', '}', '.')
                .takeIf { it.length > 8 }
                ?.let { found += it }
        }
        return found.toList()
    }

    /**
     * Human names of links present in the body that the installed core cannot dial.
     * `hysteria://` is matched exactly so `hysteria2://` never trips it.
     */
    fun unsupportedProtocols(content: String): List<String> =
        UNSUPPORTED_SCHEMES
            .filterKeys { scheme -> Regex("(?i)(?<![a-z0-9])$scheme://").containsMatchIn(content) }
            .values
            .distinct()
            .toList()

    /** Тело подписки часто — один сплошной base64 без переносов. */
    private fun looksLikeBase64Payload(content: String): Boolean {
        if (content.isBlank()) return false
        if (content.contains("://")) return false
        if (content.trimStart().startsWith("{")) return false
        val cleaned = content.replace("\\s".toRegex(), "")
        if (cleaned.length < 16) return false
        if (!cleaned.matches(Regex("^[A-Za-z0-9+/=_-]+$"))) return false
        // типичный старт vless:// / vmess:// / ss:// в base64
        val head = cleaned.take(12)
        return head.startsWith("dmxlc3") || // vless
            head.startsWith("dm1lc3") || // vmess
            head.startsWith("c3M6Ly") || // ss://
            head.startsWith("dHJvam") || // trojan
            head.startsWith("aHlzdG") || // hysteria
            head.startsWith("c29ja3") || // socks
            head.startsWith("d2lyZW") || // wireguard
            head.startsWith("b3BlbmZsdX") || // openflux
            head.startsWith("dnBuOi8v") || // vpn:// (AmneziaVPN)
            cleaned.length > 64
    }

    fun parseShareLink(link: String, subscriptionId: Long? = null): ServerEntity? {
        val trimmed = link.trim()
        val scheme = trimmed.substringBefore("://", "").lowercase()
        return when (scheme) {
            "vless" -> parseVless(trimmed, subscriptionId)
            "vmess" -> parseVmess(trimmed, subscriptionId)
            "trojan" -> parseTrojan(trimmed, subscriptionId)
            "ss" -> parseShadowsocks(trimmed, subscriptionId)
            "socks", "socks5" -> parseSocks(trimmed, subscriptionId)
            "hysteria2", "hy2" -> parseHysteria2(trimmed, subscriptionId)
            "hysteria" -> parseHysteria(trimmed, subscriptionId)
            "wireguard", "wg" -> parseWireGuard(trimmed, subscriptionId)
            "vpn" -> AmneziaConfigs.parseAmneziaKey(trimmed, subscriptionId).firstOrNull()
            "openflux" -> parseOpenFlux(trimmed, subscriptionId)
            "volta" -> OpenFluxLink.unwrap(trimmed)?.let { parseOpenFlux(it, subscriptionId) }
            else -> if (trimmed.startsWith("{")) {
                parseSingleOutboundJson(trimmed, subscriptionId)
            } else null
        }
    }

    // ------------------------------------------------------------- OpenFlux

    /**
     * An openflux:// link is kept whole: the OpenFlux core reads it when connecting.
     * Only its name, carriers and exit address are read here, to list it.
     *
     * One link is one server, however many carriers it has: they are roads to the same
     * exit, which the core runs together and fails over between (see [OpenFluxLink]).
     * A `#name` after the link, the way providers name every other share link, names
     * the server (and may carry `[L]`/`[H]` markup); the core never sees it.
     */
    private fun parseOpenFlux(link: String, subscriptionId: Long?): ServerEntity? {
        val (bare, label) = OpenFluxLink.splitName(link)
        val parsed = OpenFluxLink.parse(bare) ?: return null
        val (host, port) = parsed.endpoint
        val carriers = parsed.transports.sortedByDescending { it.priority }.map { it.type }.distinct()
        return ServerEntity(
            subscriptionId = subscriptionId,
            remark = decodeRemark(label).trim()
                .ifBlank { parsed.name }
                .ifBlank { "OpenFlux · ${parsed.carriersLabel}" },
            protocol = ProtocolType.OPENFLUX,
            address = host,
            port = port,
            network = carriers.joinToString("+"),
            security = if (parsed.encrypted) "aes-gcm" else "none",
            rawLink = bare.filterNot { it.isWhitespace() }
        )
    }

    /**
     * An OpenFlux configuration given as JSON, the share.Config the core puts inside a
     * link (`{"name", "secret", "transports": [...]}`): turned into the link the core
     * would make for it, so it connects exactly like a scanned one.
     */
    private fun parseOpenFluxConfig(obj: JsonObject, subscriptionId: Long?): ServerEntity? =
        OpenFluxLink.encode(obj)?.let { parseOpenFlux(it, subscriptionId) }

    // ---------------------------------------------------------------- VLESS

    fun parseVless(link: String, subscriptionId: Long? = null): ServerEntity? {
        return try {
            val parts = splitShareLink(link, "vless") ?: return null
            val params = parseQuery(parts.query)
            val (address, port) = splitHostPort(parts.hostPort, 443)
            if (address.isBlank() || parts.userInfo.isBlank()) return null

            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "VLESS" },
                protocol = ProtocolType.VLESS,
                address = address,
                port = port,
                uuid = parts.userInfo,
                flow = params["flow"].orEmpty(),
                encryption = params["encryption"]?.ifBlank { "none" } ?: "none",
                network = normalizeNetwork(params["type"] ?: params["network"]),
                security = normalizeSecurity(params["security"]),
                sni = params["sni"].orEmpty(),
                fp = params["fp"].orEmpty(),
                pbk = params["pbk"].orEmpty(),
                sid = params["sid"].orEmpty(),
                spx = params["spx"].orEmpty(),
                alpn = params["alpn"].orEmpty(),
                path = params["path"].orEmpty(),
                host = params["host"].orEmpty(),
                serviceName = params["serviceName"].orEmpty(),
                headerType = params["headerType"].orEmpty(),
                rawLink = link,
                extraJson = encodeExtras(params)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- VMess

    private fun parseVmess(link: String, subscriptionId: Long?): ServerEntity? {
        // Modern panels also emit the VLESS-style vmess://uuid@host:port?...#name
        if (link.contains('@')) {
            parseVmessUriStyle(link, subscriptionId)?.let { return it }
        }
        return try {
            val b64 = link.substringAfter("://")
            val json = decodeBase64(b64) ?: return null
            val obj = JsonParser.parseString(json).asJsonObject
            val address = obj.str("add").ifBlank { obj.str("address") }
            if (address.isBlank()) return null
            val extras = buildMap {
                put("alterId", obj.int("aid", 0).toString())
                obj.str("type").takeIf { it.isNotBlank() }?.let { put("headerType", it) }
                obj.str("mode").takeIf { it.isNotBlank() }?.let { put("mode", it) }
                obj.str("allowInsecure").takeIf { it.isNotBlank() }?.let { put("allowInsecure", it) }
            }
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = obj.str("ps").ifBlank { obj.str("remarks").ifBlank { "VMess" } },
                protocol = ProtocolType.VMESS,
                address = address,
                port = obj.int("port", 443),
                uuid = obj.str("id"),
                encryption = obj.str("scy").ifBlank { "auto" },
                network = normalizeNetwork(obj.str("net")),
                security = normalizeSecurity(obj.str("tls")),
                sni = obj.str("sni").ifBlank { obj.str("host") },
                path = obj.str("path"),
                host = obj.str("host"),
                serviceName = obj.str("path").takeIf { obj.str("net") == "grpc" }.orEmpty(),
                headerType = obj.str("type"),
                alpn = obj.str("alpn"),
                fp = obj.str("fp"),
                rawLink = link,
                rawJson = "",
                extraJson = encodeExtras(extras)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parseVmessUriStyle(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val parts = splitShareLink(link, "vmess") ?: return null
            val params = parseQuery(parts.query)
            val (address, port) = splitHostPort(parts.hostPort, 443)
            if (address.isBlank() || parts.userInfo.isBlank()) return null
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "VMess" },
                protocol = ProtocolType.VMESS,
                address = address,
                port = port,
                uuid = parts.userInfo,
                encryption = params["encryption"]?.ifBlank { "auto" } ?: "auto",
                network = normalizeNetwork(params["type"] ?: params["network"]),
                security = normalizeSecurity(params["security"]),
                sni = params["sni"].orEmpty(),
                fp = params["fp"].orEmpty(),
                alpn = params["alpn"].orEmpty(),
                path = params["path"].orEmpty(),
                host = params["host"].orEmpty(),
                serviceName = params["serviceName"].orEmpty(),
                headerType = params["headerType"].orEmpty(),
                rawLink = link,
                extraJson = encodeExtras(params)
            )
        } catch (_: Exception) {
            null
        }
    }

    // --------------------------------------------------------------- Trojan

    private fun parseTrojan(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val parts = splitShareLink(link, "trojan") ?: return null
            val params = parseQuery(parts.query)
            val (address, port) = splitHostPort(parts.hostPort, 443)
            if (address.isBlank() || parts.userInfo.isBlank()) return null
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "Trojan" },
                protocol = ProtocolType.TROJAN,
                address = address,
                port = port,
                uuid = parts.userInfo,
                network = normalizeNetwork(params["type"] ?: params["network"]),
                // Trojan is TLS-only unless the link explicitly says reality.
                security = normalizeSecurity(params["security"]).let {
                    if (it == "none") "tls" else it
                },
                sni = params["sni"].orEmpty().ifBlank { params["peer"].orEmpty() },
                path = params["path"].orEmpty(),
                host = params["host"].orEmpty(),
                serviceName = params["serviceName"].orEmpty(),
                headerType = params["headerType"].orEmpty(),
                fp = params["fp"].orEmpty(),
                pbk = params["pbk"].orEmpty(),
                sid = params["sid"].orEmpty(),
                alpn = params["alpn"].orEmpty(),
                flow = params["flow"].orEmpty(),
                rawLink = link,
                extraJson = encodeExtras(params)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------- Shadowsocks

    private fun parseShadowsocks(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val without = link.substringAfter("://")
            val hashIdx = without.lastIndexOf('#')
            val remark = if (hashIdx >= 0) {
                decodeRemark(without.substring(hashIdx + 1))
            } else "Shadowsocks"
            val main = if (hashIdx >= 0) without.substring(0, hashIdx) else without
            val query = main.substringAfter('?', "")
            val at = main.substringBefore('?').lastIndexOf('@')
            val userInfo: String
            val hostPort: String
            if (at >= 0) {
                userInfo = main.substring(0, at)
                hostPort = main.substring(at + 1).substringBefore('?')
            } else {
                // SIP002 also allows the whole userinfo@host:port to be base64 encoded.
                val decoded = decodeBase64(main.substringBefore('?')) ?: return null
                val decodedAt = decoded.lastIndexOf('@')
                if (decodedAt < 0) return null
                userInfo = decoded.substring(0, decodedAt)
                hostPort = decoded.substring(decodedAt + 1)
            }
            // SIP002 percent-encodes the userinfo; ss2022 keys are base64 themselves,
            // so only accept a base64 decode that actually yields "method:password".
            val rawUser = percentDecode(userInfo)
            val decodedUser = decodeBase64(rawUser)?.takeIf { it.contains(':') } ?: rawUser
            val method = decodedUser.substringBefore(':')
            val password = decodedUser.substringAfter(':')
            val (address, port) = splitHostPort(hostPort, 443)
            if (address.isBlank() || method.isBlank()) return null
            val params = parseQuery(query)
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = remark,
                protocol = ProtocolType.SHADOWSOCKS,
                address = address,
                port = port,
                uuid = password,
                encryption = method,
                network = normalizeNetwork(params["type"] ?: params["network"]),
                security = normalizeSecurity(params["security"]),
                sni = params["sni"].orEmpty(),
                path = params["path"].orEmpty(),
                host = params["host"].orEmpty(),
                serviceName = params["serviceName"].orEmpty(),
                rawLink = link,
                extraJson = encodeExtras(params)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- SOCKS

    private fun parseSocks(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val parts = splitShareLink(link, link.substringBefore("://")) ?: return null
            // socks://BASE64(user:pass)@host:port or socks://BASE64(user:pass@host:port)
            var user = ""
            var pass = ""
            var hostPort = parts.hostPort
            if (parts.userInfo.isNotBlank()) {
                val decoded = decodeBase64(parts.userInfo)?.takeIf { it.contains(':') }
                    ?: parts.userInfo
                user = decoded.substringBefore(':')
                pass = decoded.substringAfter(':', "")
            } else if (hostPort.isNotBlank() && !hostPort.contains(':')) {
                val decoded = decodeBase64(hostPort) ?: return null
                val at = decoded.lastIndexOf('@')
                if (at >= 0) {
                    val cred = decoded.substring(0, at)
                    user = cred.substringBefore(':')
                    pass = cred.substringAfter(':', "")
                    hostPort = decoded.substring(at + 1)
                } else {
                    hostPort = decoded
                }
            }
            val (address, port) = splitHostPort(hostPort, 1080)
            if (address.isBlank()) return null
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "SOCKS5" },
                protocol = ProtocolType.SOCKS,
                address = address,
                port = port,
                uuid = pass,
                encryption = "none",
                network = "tcp",
                security = "none",
                host = user,
                rawLink = link,
                extraJson = encodeExtras(mapOf("user" to user))
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ Hysteria2

    private fun parseHysteria2(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val parts = splitShareLink(link, link.substringBefore("://")) ?: return null
            val params = parseQuery(parts.query)
            val (hostPort, hopPorts) = cutPortRange(parts.hostPort)
            val (address, port) = splitHostPort(hostPort, 443)
            if (address.isBlank()) return null
            // hysteria2://auth@host:port?sni=&obfs=&obfs-password=&insecure=1#name
            val auth = parts.userInfo.ifBlank { params["auth"].orEmpty() }
            val extras = params.toMutableMap()
            extras["auth"] = auth
            if (hopPorts.isNotBlank() && extras["mport"].isNullOrBlank()) extras["mport"] = hopPorts
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "Hysteria2" },
                protocol = ProtocolType.HYSTERIA2,
                address = address,
                port = port,
                uuid = auth,
                network = "hysteria",
                security = "tls",
                sni = params["sni"].orEmpty().ifBlank { params["peer"].orEmpty() },
                alpn = params["alpn"].orEmpty().ifBlank { "h3" },
                rawLink = link,
                extraJson = encodeExtras(extras)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ Hysteria v1

    /**
     * hysteria://host:port?protocol=udp&auth=&peer=&insecure=1&upmbps=&downmbps=&obfsParam=#name.
     * The core has no Hysteria v1, so the voltaproxy helper runs the link as it is.
     */
    private fun parseHysteria(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val parts = splitShareLink(link, "hysteria") ?: return null
            val params = parseQuery(parts.query)
            val (hostPort, hopPorts) = cutPortRange(parts.hostPort)
            val (address, port) = splitHostPort(hostPort, 443)
            if (address.isBlank()) return null
            val auth = params["auth"].orEmpty().ifBlank { params["auth_str"].orEmpty() }
            val extras = params.toMutableMap()
            if (hopPorts.isNotBlank() && extras["mport"].isNullOrBlank()) extras["mport"] = hopPorts
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "Hysteria" },
                protocol = ProtocolType.HYSTERIA,
                address = address,
                port = port,
                uuid = auth,
                network = "hysteria",
                security = "tls",
                sni = params["peer"].orEmpty().ifBlank { params["sni"].orEmpty() },
                alpn = params["alpn"].orEmpty().ifBlank { "hysteria" },
                rawLink = link,
                extraJson = encodeExtras(extras)
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Port hopping written into the address ("host:20000-30000", "host:443,8443"):
     * the address with its first port, and the whole port list.
     */
    private fun cutPortRange(hostPort: String): Pair<String, String> {
        val hp = hostPort.trim().trim('/')
        val colon = hp.lastIndexOf(':')
        if (colon <= 0 || hp.endsWith("]")) return hp to ""
        val spec = hp.substring(colon + 1)
        if (!spec.contains('-') && !spec.contains(',')) return hp to ""
        val first = spec.substringBefore(',').substringBefore('-')
        if (first.toIntOrNull() == null) return hp to ""
        return "${hp.substring(0, colon)}:$first" to spec
    }

    // ------------------------------------------------------------ WireGuard

    private fun parseWireGuard(link: String, subscriptionId: Long?): ServerEntity? {
        return try {
            val parts = splitShareLink(link, link.substringBefore("://")) ?: return null
            val params = parseQuery(parts.query)
            val (address, port) = splitHostPort(parts.hostPort, 51820)
            val privateKey = parts.userInfo.ifBlank {
                params["privatekey"] ?: params["privateKey"].orEmpty()
            }
            if (address.isBlank() || privateKey.isBlank()) return null
            // AmneziaWG fields in the link: the core's WireGuard can't speak them.
            AmneziaConfigs.fromWireGuardLink(
                privateKey, address, port, params, parts.remark, subscriptionId
            )?.let { return it }
            val extras = params.toMutableMap()
            extras["privateKey"] = privateKey
            ServerEntity(
                subscriptionId = subscriptionId,
                remark = parts.remark.ifBlank { "WireGuard" },
                protocol = ProtocolType.WIREGUARD,
                address = address,
                port = port,
                uuid = privateKey,
                pbk = params["publickey"] ?: params["publicKey"].orEmpty(),
                network = "udp",
                security = "none",
                rawLink = link,
                extraJson = encodeExtras(extras)
            )
        } catch (_: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ Xray JSON

    private fun parseXrayJson(json: String, subscriptionId: Long?): SubscriptionImportResult {
        val root = JsonParser.parseString(json).asJsonObject
        val servers = mutableListOf<ServerEntity>()
        val routingJson = root.getAsJsonObject("routing")?.toString().orEmpty()

        val outbounds = root.getAsJsonArray("outbounds") ?: JsonArray()
        outbounds.forEach { el ->
            // A single malformed outbound must not cost the user every other server.
            runCatching { parseOutbound(el, subscriptionId) }.getOrNull()?.let { servers += it }
        }

        if (servers.isEmpty() && root.has("protocol") && !root.has("outbounds")) {
            runCatching { parseOutbound(root, subscriptionId) }.getOrNull()?.let { servers += it }
        }

        // An OpenFlux configuration as JSON: one exit. An array of them is several.
        if (servers.isEmpty() && OpenFluxLink.isShareConfig(root)) {
            parseOpenFluxConfig(root, subscriptionId)?.let { servers += it }
        }

        // A v2rayNG-style config names its server in "remarks"; the outbound tag is just
        // "proxy". Without this every server of a JSON subscription was called "proxy",
        // and the name check (Home/LTE/balancer) had nothing to go on.
        val remarks = root.str("remarks").trim()
        val named = if (remarks.isBlank()) servers else servers.map { s ->
            val generic = s.remark.isBlank() || s.remark.startsWith("proxy", ignoreCase = true) ||
                s.remark.contains("://")
            when {
                !generic -> s
                servers.size == 1 -> s.copy(remark = remarks)
                else -> s.copy(remark = "$remarks · ${s.remark}")
            }
        }

        return SubscriptionImportResult(
            servers = named,
            routingJson = routingJson,
            title = remarks.ifBlank { "Xray JSON" }
        )
    }

    /**
     * A provider's subscription split into groups (see ServerMarkup):
     *
     * ```
     * {
     *   "title": "My VPN",
     *   "wifi":      ["vless://…#Germany", {…full Xray config…}],
     *   "lte":       [{"link": "vless://…", "name": "Moscow LTE"}],
     *   "balancers": [{…outbound…}]
     * }
     * ```
     *
     * The lists may also sit under "groups" or "servers". An item is a share link (or
     * several in one string), a full Xray config, a single outbound, or an object with
     * "link"/"url"/"config" and an optional "name"/"remark". Every server gets the role
     * of its list, marked as given by the provider. Null when the object has no group
     * key holding a list, so ordinary Xray JSON is left to the code that handles it.
     */
    private fun parseGroupedJson(json: String, subscriptionId: Long?): SubscriptionImportResult? {
        val root = runCatching { JsonParser.parseString(json) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (root.has("outbounds")) return null
        val holder = listOf("groups", "servers")
            .firstNotNullOfOrNull { key -> root.get(key)?.takeIf { it.isJsonObject }?.asJsonObject }
            ?: root
        val groups = holder.entrySet().mapNotNull { (key, value) ->
            val role = com.ars3nb.volta.core.server.ServerMarkup.groupForKey(key) ?: return@mapNotNull null
            if (!value.isJsonArray && !(value.isJsonPrimitive && value.asJsonPrimitive.isString)) {
                return@mapNotNull null
            }
            role to value
        }
        if (groups.isEmpty()) return null

        val servers = mutableListOf<ServerEntity>()
        for ((role, value) in groups) {
            val items = if (value.isJsonArray) value.asJsonArray.toList() else listOf(value)
            for (item in items) {
                runCatching { groupItem(item, subscriptionId) }.getOrNull().orEmpty().forEach { s ->
                    servers += s.copy(role = role, roleFromProvider = true)
                }
            }
        }
        // A stray "mobile": "yes" in some other JSON is not a group list.
        if (servers.isEmpty() && groups.none { it.second.isJsonArray }) return null
        val routing = root.get("routing")?.let { el ->
            when {
                el.isJsonObject -> el.toString()
                el.isJsonPrimitive -> el.asString
                else -> ""
            }
        }.orEmpty()
        return SubscriptionImportResult(
            servers = servers,
            routingJson = routing,
            title = root.str("title").ifBlank { root.str("profile-title") }.ifBlank { root.str("name") },
            announce = root.str("announce"),
            supportUrl = root.str("support-url").ifBlank { root.str("supportUrl") },
            skipped = servers.takeIf { it.isEmpty() }?.let { unsupportedProtocols(json) }.orEmpty()
        )
    }

    private fun groupItem(item: com.google.gson.JsonElement, subscriptionId: Long?): List<ServerEntity> {
        if (item.isJsonPrimitive && item.asJsonPrimitive.isString) return linksIn(item.asString, subscriptionId)
        if (!item.isJsonObject) return emptyList()
        val obj = item.asJsonObject
        val name = obj.str("name").ifBlank { obj.str("remark") }.trim()
        val link = listOf("link", "url", "config", "uri").firstNotNullOfOrNull { key ->
            obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
        }
        val parsed = when {
            OpenFluxLink.isShareConfig(obj) -> listOfNotNull(parseOpenFluxConfig(obj, subscriptionId))
            link != null -> linksIn(link, subscriptionId)
            obj.has("outbounds") -> parseXrayJson(obj.toString(), subscriptionId).servers
            obj.has("protocol") -> listOfNotNull(parseSingleOutboundJson(obj.toString(), subscriptionId))
            else -> emptyList()
        }
        return if (name.isNotBlank() && parsed.size == 1) listOf(parsed[0].copy(remark = name)) else parsed
    }

    private fun linksIn(text: String, subscriptionId: Long?): List<ServerEntity> =
        extractShareLinks(text).flatMap { link ->
            if (link.startsWith("vpn://", ignoreCase = true)) {
                AmneziaConfigs.parseAmneziaKey(link, subscriptionId)
            } else {
                listOfNotNull(parseShareLink(link, subscriptionId))
            }
        }

    private fun parseOutbound(
        el: com.google.gson.JsonElement,
        subscriptionId: Long?
    ): ServerEntity? {
            if (!el.isJsonObject) return null
            val ob = el.asJsonObject
            val tag = ob.str("tag")
            val protocol = ob.str("protocol").lowercase()
            if (protocol in setOf("freedom", "blackhole", "dns", "loopback")) return null
            if (tag in setOf("direct", "block", "dns-out", "api")) return null

            val settings = ob.getAsJsonObject("settings")
            val vnext = settings?.getAsJsonArray("vnext")?.firstOrNull()?.asJsonObject
            val serversArr = settings?.getAsJsonArray("servers")?.firstOrNull()?.asJsonObject

            var address = ""
            var port = 443
            var uuid = ""
            var flow = ""
            var encryption = "none"

            when (protocol) {
                "vless", "vmess" -> {
                    address = vnext?.str("address").orEmpty()
                    port = vnext?.int("port", 443) ?: 443
                    val user = vnext?.getAsJsonArray("users")?.firstOrNull()?.asJsonObject
                    uuid = user?.str("id").orEmpty()
                    flow = user?.str("flow").orEmpty()
                    encryption = user?.str("encryption")?.ifBlank { null }
                        ?: user?.str("security")?.ifBlank { null }
                        ?: if (protocol == "vmess") "auto" else "none"
                }

                "trojan", "shadowsocks", "socks", "http" -> {
                    address = serversArr?.str("address").orEmpty()
                    port = serversArr?.int("port", 443) ?: 443
                    uuid = serversArr?.str("password").orEmpty().ifBlank {
                        serversArr?.getAsJsonArray("users")?.firstOrNull()
                            ?.asJsonObject?.str("pass").orEmpty()
                    }
                    encryption = serversArr?.str("method").orEmpty().ifBlank { "none" }
                }

                "hysteria" -> {
                    address = settings?.str("address").orEmpty()
                    port = settings?.int("port", 443) ?: 443
                }

                "wireguard" -> {
                    uuid = settings?.str("secretKey").orEmpty()
                    val peer = settings?.getAsJsonArray("peers")?.firstOrNull()?.asJsonObject
                    val endpoint = peer?.str("endpoint").orEmpty()
                    val split = splitHostPort(endpoint, 51820)
                    address = split.first
                    port = split.second
                }

                else -> {
                    address = vnext?.str("address") ?: serversArr?.str("address").orEmpty()
                    port = vnext?.int("port", 443) ?: serversArr?.int("port", 443) ?: 443
                }
            }

            val stream = ob.getAsJsonObject("streamSettings")
            val network = normalizeNetwork(stream?.str("network"))
            val security = normalizeSecurity(stream?.str("security"))
            val tls = stream?.getAsJsonObject("tlsSettings")
            val reality = stream?.getAsJsonObject("realitySettings")
            val ws = stream?.getAsJsonObject("wsSettings")
            val grpc = stream?.getAsJsonObject("grpcSettings")
            val hysteria = stream?.getAsJsonObject("hysteriaSettings")

            if (address.isBlank() && protocol != "wireguard") return null

            val protoType = when (protocol) {
                "vless" -> ProtocolType.VLESS
                "vmess" -> ProtocolType.VMESS
                "trojan" -> ProtocolType.TROJAN
                "shadowsocks" -> ProtocolType.SHADOWSOCKS
                "socks" -> ProtocolType.SOCKS
                "http" -> ProtocolType.HTTP
                "wireguard" -> ProtocolType.WIREGUARD
                "hysteria" -> ProtocolType.HYSTERIA2
                else -> ProtocolType.RAW_JSON
            }
            if (protoType == ProtocolType.HYSTERIA2 && uuid.isBlank()) {
                uuid = hysteria?.str("auth").orEmpty()
            }

            return ServerEntity(
                subscriptionId = subscriptionId,
                remark = tag.ifBlank { "$protocol://$address:$port" },
                protocol = protoType,
                address = address,
                port = port,
                uuid = uuid,
                flow = flow,
                encryption = encryption,
                network = network,
                security = security,
                sni = reality?.str("serverName")?.ifBlank { tls?.str("serverName") }
                    ?: tls?.str("serverName").orEmpty(),
                fp = reality?.str("fingerprint")?.ifBlank { tls?.str("fingerprint") }
                    ?: tls?.str("fingerprint").orEmpty(),
                pbk = reality?.str("publicKey").orEmpty(),
                sid = reality?.getAsJsonArray("shortIds")?.firstOrNull()?.asString.orEmpty()
                    .ifBlank { reality?.str("shortId").orEmpty() },
                spx = reality?.str("spiderX").orEmpty(),
                alpn = (tls?.getAsJsonArray("alpn") ?: reality?.getAsJsonArray("alpn"))
                    ?.joinToString(",") { it.asString }.orEmpty(),
                path = ws?.str("path").orEmpty(),
                host = ws?.getAsJsonObject("headers")?.str("Host").orEmpty(),
                serviceName = grpc?.str("serviceName").orEmpty(),
                // The whole outbound is kept verbatim: exotic transports survive untouched.
                rawJson = ob.toString(),
                rawLink = ""
            )
    }

    private fun parseXrayJsonArray(json: String, subscriptionId: Long?): SubscriptionImportResult? {
        return try {
            val arr = JsonParser.parseString(json).asJsonArray
            val servers = mutableListOf<ServerEntity>()
            var routingJson = ""
            arr.forEach { el ->
                if (!el.isJsonObject) return@forEach
                val one = parseXrayJson(el.toString(), subscriptionId)
                servers += one.servers
                if (routingJson.isBlank()) routingJson = one.routingJson
            }
            if (servers.isEmpty()) null
            else SubscriptionImportResult(
                servers = servers,
                routingJson = routingJson,
                title = "JSON config"
            )
        } catch (_: Exception) {
            null
        }
    }

    /** One bare outbound object. Parses it directly — never re-enters [parseXrayJson]. */
    private fun parseSingleOutboundJson(json: String, subscriptionId: Long?): ServerEntity? =
        runCatching {
            val element = JsonParser.parseString(json)
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject
            val outbound = obj.getAsJsonArray("outbounds")?.firstOrNull() ?: obj
            parseOutbound(outbound, subscriptionId)
        }.getOrNull()

    // --------------------------------------------------------------- shared

    private data class LinkParts(
        val userInfo: String,
        val hostPort: String,
        val query: String,
        val remark: String
    )

    /**
     * Splits `scheme://userinfo@host:port?query#remark` by hand.
     * android.net.Uri chokes on raw UUIDs and unencoded remarks that providers ship.
     */
    private fun splitShareLink(link: String, scheme: String): LinkParts? {
        val withoutScheme = link.substringAfter("$scheme://", "")
            .ifBlank { link.substringAfter("://", "") }
        if (withoutScheme.isBlank()) return null
        val hashIdx = withoutScheme.indexOf('#')
        val remark = if (hashIdx >= 0) decodeRemark(withoutScheme.substring(hashIdx + 1)) else ""
        val main = if (hashIdx >= 0) withoutScheme.substring(0, hashIdx) else withoutScheme
        val qIdx = main.indexOf('?')
        val beforeQuery = if (qIdx >= 0) main.substring(0, qIdx) else main
        val query = if (qIdx >= 0) main.substring(qIdx + 1) else ""
        val atIdx = beforeQuery.lastIndexOf('@')
        val userInfo = if (atIdx >= 0) {
            decodeRemark(beforeQuery.substring(0, atIdx))
        } else ""
        val hostPort = if (atIdx >= 0) beforeQuery.substring(atIdx + 1) else beforeQuery
        return LinkParts(userInfo, hostPort.trim('/'), query, remark)
    }

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val hp = hostPort.trim().trim('/')
        if (hp.startsWith("[")) {
            val end = hp.indexOf(']')
            if (end > 0) {
                val host = hp.substring(1, end)
                val portPart = hp.substring(end + 1).removePrefix(":")
                return host to (portPart.toIntOrNull() ?: defaultPort)
            }
        }
        val colon = hp.lastIndexOf(':')
        // A bare IPv6 literal has several colons and no port.
        if (colon > 0 && hp.count { it == ':' } == 1) {
            val port = hp.substring(colon + 1).substringBefore(',').toIntOrNull()
            if (port != null) return hp.substring(0, colon) to port
        }
        return hp to defaultPort
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        return query.split('&').mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null
            else {
                val k = decodeRemark(part.substring(0, eq))
                val v = decodeRemark(part.substring(eq + 1))
                k to v
            }
        }.toMap()
    }

    /** tcp / raw are the same wire format; keep one canonical spelling. */
    private fun normalizeNetwork(value: String?): String {
        val v = value?.trim()?.lowercase().orEmpty()
        return when (v) {
            "", "raw" -> "tcp"
            "h2" -> "http"
            "splithttp" -> "xhttp"
            else -> v
        }
    }

    private fun normalizeSecurity(value: String?): String {
        val v = value?.trim()?.lowercase().orEmpty()
        return when (v) {
            "", "none", "0", "false" -> "none"
            "1", "true" -> "tls"
            else -> v
        }
    }

    private fun encodeExtras(params: Map<String, String>): String {
        val clean = params.filterValues { it.isNotBlank() }
        return if (clean.isEmpty()) "" else gson.toJson(clean)
    }

    /** Reads one protocol-specific extra out of [ServerEntity.extraJson]. */
    fun extra(extraJson: String, vararg keys: String): String {
        if (extraJson.isBlank()) return ""
        return runCatching {
            val obj = JsonParser.parseString(extraJson).asJsonObject
            for (k in keys) {
                obj.entrySet().firstOrNull { it.key.equals(k, ignoreCase = true) }?.let { e ->
                    if (!e.value.isJsonNull) {
                        val v = e.value.asString
                        if (v.isNotBlank()) return@runCatching v
                    }
                }
            }
            ""
        }.getOrDefault("")
    }

    private fun parseUserInfo(info: String): Triple<Long, Long, Long>? {
        return try {
            val map = info.split(';').mapNotNull {
                val p = it.trim().split('=')
                if (p.size == 2) p[0].trim() to p[1].trim() else null
            }.toMap()
            val upload = map["upload"]?.toLongOrNull() ?: 0L
            val download = map["download"]?.toLongOrNull() ?: 0L
            val total = map["total"]?.toLongOrNull() ?: 0L
            val expire = map["expire"]?.toLongOrNull()?.times(1000) ?: 0L
            Triple(upload + download, total, expire)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Percent-decoding that leaves `+` alone.
     *
     * URLDecoder implements form encoding, where `+` means space — that silently
     * corrupts WireGuard keys, base64 credentials and passwords, all of which are
     * legal inside a share link.
     */
    fun percentDecode(raw: String): String {
        if (raw.isBlank() || !raw.contains('%')) return raw
        val out = StringBuilder(raw.length)
        var i = 0
        val bytes = java.io.ByteArrayOutputStream()
        fun flush() {
            if (bytes.size() > 0) {
                out.append(String(bytes.toByteArray(), StandardCharsets.UTF_8))
                bytes.reset()
            }
        }
        while (i < raw.length) {
            val c = raw[i]
            if (c == '%' && i + 2 < raw.length) {
                val hex = raw.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    bytes.write(value)
                    i += 3
                    continue
                }
            }
            flush()
            out.append(c)
            i++
        }
        flush()
        return out.toString()
    }

    private fun decodeRemark(raw: String): String = percentDecode(raw)

    /**
     * Providers send the profile name as plain text, percent-encoded text or base64.
     *
     * Base64 is only *guessed* when the value cannot be an ordinary title: correct
     * padding plus a decode that yields readable text. Guessing more eagerly turned
     * names like "Premium" into mojibake.
     */
    fun decodeProfileTitle(raw: String): String {
        val value = raw.trim().trim('"')
        if (value.isBlank()) return ""

        if (value.startsWith("base64:", ignoreCase = true)) {
            return decodeBase64(value.substringAfter(':'))?.trim().orEmpty().ifBlank { value }
        }

        val decoded = percentDecode(value)
        if (decoded != value) return decoded

        if (value.length >= 12 && value.length % 4 == 0 &&
            value.matches(Regex("^[A-Za-z0-9+/]+={0,2}$"))
        ) {
            decodeBase64(value)?.trim()?.let { candidate ->
                val readable = candidate.isNotBlank() &&
                    candidate.none { it.code < 32 && it != '\n' && it != '\t' } &&
                    !candidate.contains('�') &&
                    (candidate.any { it.isWhitespace() } || candidate.any { it.code > 127 })
                if (readable) return candidate
            }
        }
        return value
    }

    fun decodeBase64(input: String): String? {
        val cleaned = input.trim().replace("\\s".toRegex(), "")
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            try {
                String(Base64.getUrlDecoder().decode(padded), StandardCharsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun JsonObject.str(key: String): String =
        if (has(key) && !get(key).isJsonNull && get(key).isJsonPrimitive) get(key).asString else ""

    private fun JsonObject.int(key: String, default: Int): Int =
        if (has(key) && !get(key).isJsonNull) {
            try {
                get(key).asInt
            } catch (_: Exception) {
                get(key).asString.toIntOrNull() ?: default
            }
        } else default
}
