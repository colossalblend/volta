package com.ars3nb.volta.core.vpn

import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.routing.GeoNames
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Builds an Xray-core v26 config from a server (or a pool of servers) plus a routing profile.
 *
 * Two exit shapes:
 *  - single outbound tagged `proxy`;
 *  - a pool `proxy-0 … proxy-N` behind an observatory-driven balancer, so the core
 *    itself fails over to the fastest live node without reconnecting the tunnel.
 */
object ConfigBuilder {

    const val PROXY_TAG = "proxy"
    const val BALANCER_TAG = "proxy-balancer"
    private const val POOL_PREFIX = "proxy-"
    const val DEFAULT_PROBE_URL = "https://www.gstatic.com/generate_204"

    /**
     * A second local SOCKS port whose traffic always leaves through the exit node, before
     * any profile, provider or AdGuard rule is looked at. The exit-address lookup uses it,
     * so a rule that sends a geo-IP service direct (or a filter that blocks it) cannot
     * make the app report the home address, or nothing at all, while connected.
     */
    const val EXIT_CHECK_PORT = 10807
    const val EXIT_CHECK_TAG = "exit-check"

    /**
     * The DNS server Android is given while the core answers lookups itself: the free
     * address in the tunnel's own 10.10.14.0/30 (v2rayNG names the same one with its
     * local DNS on; sing-box does the same with 172.19.0.2). Nothing listens there; the
     * routing hands every lookup sent to it to the core's resolver (see
     * [RoutingEngine.toXrayRouting]).
     *
     * A public resolver here (the 1.1.1.1 and 8.8.8.8 the tunnel used to name) quietly
     * defeats the core's DNS: Android's Private DNS on «Automatic», the default, finds
     * that those addresses speak DNS-over-TLS and switches to port 853, and Chrome's
     * secure DNS upgrades them to DNS-over-HTTPS. Both then travel as ordinary encrypted
     * traffic through the proxy and never reach the port-53 rule, so AdGuard never saw
     * a single lookup. An address no one knows offers neither, and Android stays on
     * plain lookups the core can answer.
     */
    const val TUN_DNS = "10.10.14.2"

    /** Where lookups the core does not answer itself (MX, SRV, TXT…) are passed on. */
    private const val PASS_THROUGH_DNS = "1.1.1.1"

    private const val DNS_OUT_TAG = "dns-out"

    /**
     * Raised when the installed core physically cannot dial a node. [reason] is a string
     * resource and [args] what goes into it; the text is looked up when it is read, so it
     * comes in the app's language wherever it is shown (the server list, a failed connect).
     */
    class UnsupportedProtocolException(@StringRes val reason: Int, vararg val args: Any) :
        IllegalStateException() {
        override val message: String get() = describe(reason, args)
    }

    /** Raised when a node's link lacks something it needs; [reason] as above. */
    class IncompleteServerException(@StringRes val reason: Int, vararg val args: Any) :
        IllegalArgumentException() {
        override val message: String get() = describe(reason, args)
    }

    /** The words for a reason; in unit tests, where there is no app, only its number. */
    private fun describe(@StringRes reason: Int, args: Array<out Any>): String =
        runCatching { VoltaApp.instance.getString(reason, *args) }
            .getOrElse { "#$reason ${args.joinToString()}".trim() }

    fun build(
        server: ServerEntity,
        profile: RoutingProfileEntity?,
        providerRoutingJson: String = "",
        socksPort: Int = 10808,
        httpPort: Int = 10809,
        batterySaver: Boolean = true,
        localDns: Boolean = true,
        enableStats: Boolean = true,
        /** More than one entry switches on the in-core balancer. */
        balancerPool: List<ServerEntity> = emptyList(),
        probeUrl: String = DEFAULT_PROBE_URL,
        probeIntervalSeconds: Int = 300,
        /** The subscription's own Happ geodata; null = the bundled geoip/geosite. */
        geo: GeoNames? = null,
        /**
         * A JSON subscription carries its routing inside every config. Unless the user
         * bound another profile explicitly, that routing is used exactly as shipped.
         */
        preferServerRouting: Boolean = false,
        /**
         * AdGuard DNS for every lookup in the tunnel (switches the DNS module on), plus
         * the core's own ad list on every connection.
         */
        adBlock: AdBlockMode = AdBlockMode.OFF,
        /** Drops BitTorrent traffic whatever the profile says. */
        blockTorrents: Boolean = false
    ): String {
        // AdGuard needs the core to answer the lookups itself.
        val dnsInCore = localDns || adBlock != AdBlockMode.OFF
        val root = JsonObject()
        root.add("log", JsonObject().apply {
            addProperty("loglevel", if (batterySaver) "warning" else "info")
            addProperty("access", "none")
        })

        root.add("inbounds", buildInbounds(socksPort, httpPort, batterySaver))

        val pool = balancerPool
            .distinctBy { it.id }
            .filter { canDial(it) }
        val useBalancer = pool.size > 1

        val outbounds = JsonArray()
        val chained = LinkedHashMap<String, JsonObject>()
        if (useBalancer) {
            pool.forEachIndexed { index, node ->
                val tag = POOL_PREFIX + index
                outbounds.add(outboundFor(node, tag))
                chainedOutbounds(node, tag).forEach { extra ->
                    extra.get("tag")?.asString?.let { chained.putIfAbsent(it, extra) }
                }
            }
        } else {
            outbounds.add(outboundFor(server, PROXY_TAG))
            chainedOutbounds(server, PROXY_TAG).forEach { extra ->
                extra.get("tag")?.asString?.let { chained.putIfAbsent(it, extra) }
            }
        }
        // Nodes that dial through another outbound need that outbound present too.
        chained.values.forEach { outbounds.add(it) }
        outbounds.add(directOutbound())
        outbounds.add(blockOutbound())
        if (dnsInCore) outbounds.add(dnsOutbound())
        root.add("outbounds", outbounds)

        if (useBalancer) {
            root.add("observatory", JsonObject().apply {
                add("subjectSelector", jsonArray(POOL_PREFIX))
                addProperty("probeURL", probeUrl)
                addProperty("probeInterval", "${probeIntervalSeconds}s")
                addProperty("enableConcurrency", true)
            })
        }

        val serverRouting = serverRoutingOf(server)
        val serverRules = if (preferServerRouting) {
            serverRouting?.let { RoutingEngine.parseRules(it.toString()) }.orEmpty()
        } else {
            emptyList()
        }
        val useServerRouting = serverRules.isNotEmpty()
        val rules = if (useServerRouting) {
            serverRules.map { it.copy(source = "provider") }
        } else {
            resolveRules(server, profile, providerRoutingJson)
        }.let { list -> if (geo == null) list else list.map(geo::rewrite) }
            // After the rewrite: the filters always use the bundled geosite.dat, which is
            // known to carry their lists; a subscription's own file may not.
            .let { list -> filterRules(adBlock, blockTorrents) + list }
        val domainStrategy = if (useServerRouting) {
            serverRouting?.get("domainStrategy")?.takeIf { it.isJsonPrimitive }?.asString
        } else {
            profile?.domainStrategy
        }
        val routing = RoutingEngine.toXrayRouting(
            rules = rules,
            domainStrategy = domainStrategy?.ifBlank { null } ?: "IPIfNonMatch",
            proxyBalancerTag = if (useBalancer) BALANCER_TAG else null,
            dnsRuleEnabled = dnsInCore,
            dnsAddress = TUN_DNS,
            exitInboundTag = EXIT_CHECK_TAG
        )
        if (useBalancer) {
            routing.add("balancers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("tag", BALANCER_TAG)
                    add("selector", jsonArray(POOL_PREFIX))
                    addProperty("fallbackTag", POOL_PREFIX + "0")
                    add("strategy", JsonObject().apply { addProperty("type", "leastPing") })
                })
            })
        }
        root.add("routing", routing)

        if (dnsInCore) root.add("dns", buildDns(profile, adBlock))

        if (enableStats) {
            root.add("stats", JsonObject())
            root.add("policy", JsonObject().apply {
                add("system", JsonObject().apply {
                    addProperty("statsOutboundUplink", true)
                    addProperty("statsOutboundDownlink", true)
                })
            })
        }

        return root.toString()
    }

    /**
     * Smallest config `Libv2ray.measureOutboundDelay` accepts: one proxy outbound,
     * no inbounds, no routing. Used to time a node without touching the live tunnel.
     */
    fun buildProbe(server: ServerEntity): String {
        val root = JsonObject()
        root.add("log", JsonObject().apply { addProperty("loglevel", "none") })
        root.add("outbounds", JsonArray().apply {
            add(outboundFor(server, PROXY_TAG))
            add(directOutbound())
        })
        return root.toString()
    }

    /** False when this build of the core has no transport/proxy for the node. */
    fun canDial(server: ServerEntity): Boolean = runCatching {
        outboundFor(server, PROXY_TAG)
        true
    }.getOrDefault(false)

    /** Reason a node cannot be dialled, or null when it can. */
    fun dialProblem(server: ServerEntity): String? = try {
        outboundFor(server, PROXY_TAG)
        null
    } catch (e: Exception) {
        e.message ?: describe(R.string.routing_dial_unsupported, emptyArray())
    }

    /**
     * True when [configJson] answers the phone's DNS lookups itself (it has the `dns-out`
     * hand-over), so the tunnel may name [TUN_DNS] as its DNS server. A config without
     * it must get a real resolver instead, or nothing would resolve at all.
     */
    fun answersAppDns(configJson: String): Boolean = configJson.contains(DNS_OUT_TAG) && runCatching {
        JsonParser.parseString(configJson).asJsonObject
            .getAsJsonArray("outbounds")
            ?.any { el ->
                el.isJsonObject &&
                    el.asJsonObject.get("tag")?.takeIf { it.isJsonPrimitive }?.asString == DNS_OUT_TAG &&
                    el.asJsonObject.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString == "dns"
            } == true
    }.getOrDefault(false)

    /** The DNS servers the VPN interface names to Android for [configJson]. */
    fun tunDnsServers(configJson: String): List<String> =
        if (answersAppDns(configJson)) listOf(TUN_DNS) else listOf("1.1.1.1", "8.8.8.8")

    // -------------------------------------------------------------- inbounds

    private fun buildInbounds(socksPort: Int, httpPort: Int, batterySaver: Boolean): JsonArray {
        val arr = JsonArray()
        arr.add(JsonObject().apply {
            addProperty("tag", "socks")
            addProperty("port", socksPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("udp", true)
                addProperty("auth", "noauth")
            })
            // Everything from the phone arrives here (the TUN bridge speaks SOCKS), and the
            // app-wide filters depend on this sniffing staying on, for TCP and UDP alike:
            // it reads the site name out of HTTP, TLS and QUIC, so the ad list matches a
            // connection made straight to an IP (an app with its own DNS), and it is the
            // only way the core recognises BitTorrent (the `protocol` rule). The protocol
            // is noted whatever destOverride says.
            add("sniffing", JsonObject().apply {
                addProperty("enabled", true)
                add("destOverride", jsonArray("http", "tls", "quic"))
                // routeOnly keeps the original destination for the outbound, which is
                // what REALITY/SNI-pinned nodes need; only the routing table sees the host.
                addProperty("routeOnly", true)
            })
        })
        arr.add(JsonObject().apply {
            addProperty("tag", EXIT_CHECK_TAG)
            addProperty("port", EXIT_CHECK_PORT)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "socks")
            add("settings", JsonObject().apply {
                addProperty("udp", false)
                addProperty("auth", "noauth")
            })
        })
        arr.add(JsonObject().apply {
            addProperty("tag", "http")
            addProperty("port", httpPort)
            addProperty("listen", "127.0.0.1")
            addProperty("protocol", "http")
            add("settings", JsonObject().apply {
                addProperty("allowTransparent", false)
            })
        })
        return arr
    }

    // ------------------------------------------------------------- outbounds

    private fun directOutbound() = JsonObject().apply {
        addProperty("tag", "direct")
        addProperty("protocol", "freedom")
        add("settings", JsonObject().apply { addProperty("domainStrategy", "UseIP") })
    }

    private fun blockOutbound() = JsonObject().apply {
        addProperty("tag", "block")
        addProperty("protocol", "blackhole")
        add("settings", JsonObject().apply {
            add("response", JsonObject().apply { addProperty("type", "http") })
        })
    }

    private fun dnsOutbound() = JsonObject().apply {
        addProperty("tag", DNS_OUT_TAG)
        addProperty("protocol", "dns")
        add("settings", JsonObject().apply {
            // Address lookups are answered by the core's resolver; anything else is
            // passed on as is. Passed on to a real resolver, not to where it was sent:
            // that is now TUN_DNS, where nothing would ever answer.
            addProperty("nonIPQuery", "skip")
            addProperty("address", PASS_THROUGH_DNS)
            addProperty("port", 53)
        })
    }

    /**
     * One proxy outbound for [server]. A verbatim JSON import wins over the
     * field-by-field build so exotic provider configs survive untouched.
     */
    fun outboundFor(server: ServerEntity, tag: String): JsonObject {
        rawOutbound(server)?.let { raw ->
            raw.addProperty("tag", tag)
            return raw
        }
        val ob = JsonObject()
        ob.addProperty("tag", tag)
        when (server.protocol) {
            ProtocolType.VLESS -> {
                ob.addProperty("protocol", "vless")
                ob.add("settings", vnextSettings(server) { user ->
                    user.addProperty("encryption", server.encryption.ifBlank { "none" })
                    flowFor(server)?.let { user.addProperty("flow", it) }
                })
            }

            ProtocolType.VMESS -> {
                ob.addProperty("protocol", "vmess")
                ob.add("settings", vnextSettings(server) { user ->
                    user.addProperty("security", server.encryption.ifBlank { "auto" })
                    val alterId = ConfigParser.extra(server.extraJson, "alterId", "aid")
                        .toIntOrNull() ?: 0
                    user.addProperty("alterId", alterId)
                })
            }

            ProtocolType.TROJAN -> {
                ob.addProperty("protocol", "trojan")
                ob.add("settings", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("address", server.address)
                            addProperty("port", server.port)
                            addProperty("password", server.uuid)
                            flowFor(server)?.let { addProperty("flow", it) }
                        })
                    })
                })
            }

            ProtocolType.SHADOWSOCKS -> {
                if (server.encryption.isBlank()) {
                    throw IncompleteServerException(R.string.routing_dial_link_incomplete)
                }
                ob.addProperty("protocol", "shadowsocks")
                ob.add("settings", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("address", server.address)
                            addProperty("port", server.port)
                            addProperty("method", server.encryption)
                            addProperty("password", server.uuid)
                        })
                    })
                })
            }

            ProtocolType.SOCKS -> {
                ob.addProperty("protocol", "socks")
                val user = ConfigParser.extra(server.extraJson, "user", "username")
                    .ifBlank { server.host }
                ob.add("settings", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("address", server.address)
                            addProperty("port", server.port)
                            if (user.isNotBlank()) {
                                add("users", JsonArray().apply {
                                    add(JsonObject().apply {
                                        addProperty("user", user)
                                        addProperty("pass", server.uuid)
                                        addProperty("level", 0)
                                    })
                                })
                            }
                        })
                    })
                })
            }

            ProtocolType.HTTP -> {
                ob.addProperty("protocol", "http")
                val user = ConfigParser.extra(server.extraJson, "user", "username")
                    .ifBlank { server.host }
                ob.add("settings", JsonObject().apply {
                    add("servers", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("address", server.address)
                            addProperty("port", server.port)
                            if (user.isNotBlank()) {
                                add("users", JsonArray().apply {
                                    add(JsonObject().apply {
                                        addProperty("user", user)
                                        addProperty("pass", server.uuid)
                                    })
                                })
                            }
                        })
                    })
                })
            }

            ProtocolType.WIREGUARD -> {
                ob.addProperty("protocol", "wireguard")
                ob.add("settings", wireGuardSettings(server))
                // WireGuard carries its own transport; no streamSettings.
                return ob
            }

            ProtocolType.HYSTERIA2 -> {
                ob.addProperty("protocol", "hysteria")
                ob.add("settings", JsonObject().apply {
                    addProperty("version", 2)
                    addProperty("address", server.address)
                    addProperty("port", server.port)
                })
            }

            ProtocolType.OPENFLUX -> {
                if (!OpenFluxRunner.available) {
                    throw UnsupportedProtocolException(R.string.routing_dial_not_in_build, "OpenFlux")
                }
                // The OpenFlux helper carries the traffic; the core only hands it over
                // to the helper's local SOCKS5 port (see OpenFluxRunner).
                return localSocks(ob, OpenFluxRunner.PORT)
            }

            ProtocolType.AMNEZIAWG, ProtocolType.HYSTERIA -> {
                val spec = helperSpec(server)
                    ?: throw UnsupportedProtocolException(R.string.routing_dial_no_settings, server.remark)
                if (!ProxyHelper.available) {
                    throw UnsupportedProtocolException(
                        R.string.routing_dial_not_in_build, server.protocol.label
                    )
                }
                if (spec.kind == ProxyHelper.Spec.HYSTERIA) hysteriaV1Problem(server)?.let { throw it }
                // The voltaproxy helper carries the traffic (see ProxyHelper).
                return localSocks(ob, ProxyHelper.PORT)
            }

            ProtocolType.RAW_JSON, ProtocolType.UNKNOWN ->
                throw UnsupportedProtocolException(R.string.routing_dial_unknown_type, server.remark)
        }
        ob.add("streamSettings", buildStreamSettings(server))
        val mux = muxSettings(server)
        if (mux != null) ob.add("mux", mux)
        return ob
    }

    /** A SOCKS5 outbound to a helper process on this phone. */
    private fun localSocks(ob: JsonObject, port: Int): JsonObject {
        ob.addProperty("protocol", "socks")
        ob.add("settings", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", "127.0.0.1")
                    addProperty("port", port)
                })
            })
        })
        return ob
    }

    /**
     * What the voltaproxy helper must run for [server], or null when the core dials
     * it by itself: AmneziaWG (its .conf) and Hysteria v1 (its link).
     */
    fun helperSpec(server: ServerEntity): ProxyHelper.Spec? = when (server.protocol) {
        ProtocolType.AMNEZIAWG -> server.rawLink.takeIf { it.isNotBlank() }
            ?.let { ProxyHelper.Spec(ProxyHelper.Spec.AWG, it) }

        ProtocolType.HYSTERIA -> server.rawLink.takeIf { it.isNotBlank() }
            ?.let { ProxyHelper.Spec(ProxyHelper.Spec.HYSTERIA, it) }

        else -> null
    }

    /** Hysteria v1 over its fake-TCP or "wechat-video" modes: not in the helper. */
    private fun hysteriaV1Problem(server: ServerEntity): UnsupportedProtocolException? {
        val mode = ConfigParser.extra(server.extraJson, "protocol").lowercase()
        return if (mode.isNotBlank() && mode != "udp") {
            UnsupportedProtocolException(R.string.routing_dial_variant_unsupported, server.remark)
        } else null
    }

    /**
     * XTLS flow only exists over TLS/REALITY on the raw TCP transport. Copying it onto a
     * ws/grpc/xhttp node produces a config the core accepts and then fails to dial, which
     * looks to the user like a dead server, so it is dropped instead.
     */
    private fun flowFor(server: ServerEntity): String? {
        val flow = server.flow.trim()
        if (flow.isBlank()) return null
        val security = server.security.lowercase()
        val network = server.network.lowercase()
        val tlsLike = security == "tls" || security == "reality"
        val rawTransport = network.isBlank() || network == "tcp" || network == "raw"
        return if (tlsLike && rawTransport) flow else null
    }

    private fun vnextSettings(
        server: ServerEntity,
        userBlock: (JsonObject) -> Unit
    ): JsonObject {
        if (server.uuid.isBlank()) throw IncompleteServerException(R.string.routing_dial_no_key, server.remark)
        return JsonObject().apply {
            add("vnext", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("address", server.address)
                    addProperty("port", server.port)
                    add("users", JsonArray().apply {
                        add(JsonObject().apply {
                            addProperty("id", server.uuid)
                            addProperty("level", 0)
                            userBlock(this)
                        })
                    })
                })
            })
        }
    }

    private fun wireGuardSettings(server: ServerEntity): JsonObject {
        val extras = server.extraJson
        val secret = ConfigParser.extra(extras, "privateKey", "privatekey", "secretKey")
            .ifBlank { server.uuid }
        val publicKey = ConfigParser.extra(extras, "publicKey", "publickey", "peerPublicKey")
            .ifBlank { server.pbk }
        if (secret.isBlank() || publicKey.isBlank()) {
            throw IncompleteServerException(R.string.routing_dial_no_keys, server.remark)
        }
        val localAddresses = ConfigParser.extra(extras, "address", "localAddress", "ip")
            .ifBlank { "172.16.0.2/32" }
        val allowed = ConfigParser.extra(extras, "allowedIPs", "allowedips")
            .ifBlank { "0.0.0.0/0,::/0" }
        val preShared = ConfigParser.extra(extras, "preSharedKey", "presharedkey")
        val reserved = ConfigParser.extra(extras, "reserved")
        val mtu = ConfigParser.extra(extras, "mtu").toIntOrNull() ?: 1420

        return JsonObject().apply {
            addProperty("secretKey", secret)
            add("address", jsonArray(*splitList(localAddresses).toTypedArray()))
            addProperty("mtu", mtu)
            // Userspace stack: a kernel TUN is not reachable from an unprivileged app.
            addProperty("noKernelTun", true)
            add("peers", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("publicKey", publicKey)
                    addProperty("endpoint", "${server.address}:${server.port}")
                    add("allowedIPs", jsonArray(*splitList(allowed).toTypedArray()))
                    if (preShared.isNotBlank()) addProperty("preSharedKey", preShared)
                    if (reserved.isNotBlank()) {
                        add("reserved", JsonArray().apply {
                            splitList(reserved).mapNotNull { it.toIntOrNull() }.forEach { add(it) }
                        })
                    }
                })
            })
        }
    }

    /** Outbound protocols this build of Xray can actually dial. */
    private val DIALABLE_PROTOCOLS = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "hysteria"
    )

    /**
     * Full config imported as JSON — reuse the provider's own outbound verbatim.
     * The protocol is still checked, otherwise `canDial` would happily approve a node
     * the core rejects and one bad entry would make the whole config unloadable.
     */
    private fun rawOutbound(server: ServerEntity): JsonObject? {
        if (server.rawJson.isBlank() || !server.rawJson.contains("\"protocol\"")) return null
        val outbound = runCatching {
            val raw = JsonParser.parseString(server.rawJson).asJsonObject
            raw.getAsJsonArray("outbounds")
                ?.firstOrNull { it.isJsonObject && isProxyOutbound(it.asJsonObject) }
                ?.asJsonObject
                ?.deepCopy()
                ?: raw.takeIf { isProxyOutbound(it) }?.deepCopy()
        }.getOrNull() ?: return null

        val protocol = outbound.get("protocol")?.takeIf { it.isJsonPrimitive }
            ?.asString?.lowercase().orEmpty()
        if (protocol !in DIALABLE_PROTOCOLS) {
            throw UnsupportedProtocolException(R.string.routing_dial_type_unsupported, server.remark)
        }
        return dropRetired(outbound)
    }

    /**
     * Fields this core refuses outright. A provider's JSON may still carry them; without
     * them the certificate is simply verified, while with them nothing would start.
     */
    private fun dropRetired(outbound: JsonObject): JsonObject {
        runCatching {
            outbound.getAsJsonObject("streamSettings")?.getAsJsonObject("tlsSettings")
                ?.remove("allowInsecure")
        }
        return outbound
    }

    /**
     * Outbounds the imported one delegates to (`proxySettings.tag`, `sockopt.dialerProxy`).
     * Without them the core cannot resolve the chain and refuses to start.
     */
    private fun chainedOutbounds(server: ServerEntity, primaryTag: String): List<JsonObject> {
        if (server.rawJson.isBlank()) return emptyList()
        return runCatching {
            val raw = JsonParser.parseString(server.rawJson).asJsonObject
            val all = raw.getAsJsonArray("outbounds") ?: return emptyList()
            val byTag = all.mapNotNull { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject
            }.associateBy { it.get("tag")?.takeIf { t -> t.isJsonPrimitive }?.asString.orEmpty() }

            val wanted = LinkedHashSet<String>()
            fun collect(from: JsonObject, depth: Int) {
                if (depth > 4) return
                from.getAsJsonObject("proxySettings")
                    ?.get("tag")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.let { if (wanted.add(it)) byTag[it]?.let { next -> collect(next, depth + 1) } }
                from.getAsJsonObject("streamSettings")
                    ?.getAsJsonObject("sockopt")
                    ?.get("dialerProxy")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.let { if (wanted.add(it)) byTag[it]?.let { next -> collect(next, depth + 1) } }
            }
            rawOutbound(server)?.let { collect(it, 0) }

            wanted.mapNotNull { tag -> byTag[tag]?.deepCopy()?.let(::dropRetired) }
                .filter { it.get("tag")?.asString != primaryTag }
        }.getOrDefault(emptyList())
    }

    private fun isProxyOutbound(outbound: JsonObject): Boolean {
        val protocol = outbound.get("protocol")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        val tag = outbound.get("tag")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
        return protocol.isNotBlank() &&
            protocol !in setOf("freedom", "blackhole", "dns", "loopback") &&
            tag !in setOf("direct", "block", "dns-out", "api")
    }

    // ------------------------------------------------------- streamSettings

    private fun buildStreamSettings(server: ServerEntity): JsonObject {
        val stream = JsonObject()
        val extras = server.extraJson
        val network = server.network.ifBlank { "tcp" }.lowercase()
        val security = server.security.ifBlank { "none" }.lowercase()

        // Xray v26 dropped the standalone h2 and quic transports.
        val effectiveNetwork = when (network) {
            "raw" -> "tcp"
            "h2", "http" -> "xhttp"
            "splithttp" -> "xhttp"
            "mkcp" -> "kcp"
            "quic" -> throw UnsupportedProtocolException(R.string.routing_dial_outdated, server.remark)
            else -> network
        }
        // An unrecognised security value makes the core reject the whole config, taking
        // every other node down with it — refuse this one node loudly instead.
        if (security !in setOf("none", "tls", "reality")) {
            throw UnsupportedProtocolException(R.string.routing_dial_encryption, server.remark)
        }
        stream.addProperty("network", effectiveNetwork)
        stream.addProperty("security", security)

        when (security) {
            "reality" -> {
                if (server.pbk.isBlank()) {
                    throw IncompleteServerException(R.string.routing_dial_missing_key, server.remark)
                }
                stream.add("realitySettings", JsonObject().apply {
                    addProperty(
                        "serverName",
                        server.sni.ifBlank { server.host.ifBlank { server.address } }
                    )
                    addProperty("fingerprint", server.fp.ifBlank { "chrome" })
                    addProperty("publicKey", server.pbk)
                    addProperty("shortId", server.sid)
                    if (server.spx.isNotBlank()) addProperty("spiderX", server.spx)
                    ConfigParser.extra(extras, "pqv", "mldsa65Verify").takeIf { it.isNotBlank() }
                        ?.let { addProperty("mldsa65Verify", it) }
                    addProperty("show", false)
                })
            }

            "tls" -> {
                stream.add("tlsSettings", JsonObject().apply {
                    addProperty("serverName", tlsServerName(server))
                    if (server.fp.isNotBlank()) addProperty("fingerprint", server.fp)
                    if (server.alpn.isNotBlank()) {
                        add("alpn", jsonArray(*splitList(server.alpn).toTypedArray()))
                    }
                    // The core dropped allowInsecure and refuses a config that sets it. A
                    // self-signed server is trusted by pinning its certificate instead;
                    // CertPins fills the pin in for links that still only say "insecure".
                    // Without a pin the certificate is verified as usual.
                    pinsOf(server).takeIf { it.isNotEmpty() }
                        ?.let { addProperty("pinnedPeerCertSha256", it.joinToString(",")) }
                    ConfigParser.extra(extras, "vcn", "verifyPeerCertByName").takeIf { it.isNotBlank() }
                        ?.let { addProperty("verifyPeerCertByName", it) }
                })
            }
        }

        when (effectiveNetwork) {
            "ws" -> stream.add("wsSettings", JsonObject().apply {
                addProperty("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) {
                    addProperty("host", server.host)
                    add("headers", JsonObject().apply { addProperty("Host", server.host) })
                }
            })

            "httpupgrade" -> stream.add("httpupgradeSettings", JsonObject().apply {
                addProperty("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) addProperty("host", server.host)
            })

            "xhttp" -> stream.add("xhttpSettings", JsonObject().apply {
                addProperty("path", server.path.ifBlank { "/" })
                if (server.host.isNotBlank()) addProperty("host", server.host)
                val mode = ConfigParser.extra(extras, "mode").ifBlank {
                    if (server.network.equals("h2", true) ||
                        server.network.equals("http", true)
                    ) "stream-one" else "auto"
                }
                addProperty("mode", mode)
                ConfigParser.extra(extras, "extra").takeIf { it.isNotBlank() }?.let { raw ->
                    runCatching { JsonParser.parseString(raw) }
                        .getOrNull()?.takeIf { it.isJsonObject }
                        ?.let { add("extra", it.asJsonObject) }
                }
            })

            "grpc" -> stream.add("grpcSettings", JsonObject().apply {
                addProperty(
                    "serviceName",
                    server.serviceName.ifBlank { ConfigParser.extra(extras, "serviceName", "path") }
                )
                val mode = ConfigParser.extra(extras, "mode")
                addProperty("multiMode", mode.equals("multi", ignoreCase = true))
                ConfigParser.extra(extras, "authority").takeIf { it.isNotBlank() }
                    ?.let { addProperty("authority", it) }
            })

            "kcp" -> stream.add("kcpSettings", JsonObject().apply {
                addProperty("mtu", ConfigParser.extra(extras, "mtu").toIntOrNull() ?: 1350)
                addProperty("tti", ConfigParser.extra(extras, "tti").toIntOrNull() ?: 50)
                addProperty("uplinkCapacity", 12)
                addProperty("downlinkCapacity", 100)
                addProperty("congestion", ConfigParser.extra(extras, "congestion") == "true")
                ConfigParser.extra(extras, "seed", "serviceName").takeIf { it.isNotBlank() }
                    ?.let { addProperty("seed", it) }
                add("header", JsonObject().apply {
                    addProperty("type", server.headerType.ifBlank { "none" })
                })
            })

            "hysteria" -> stream.add("hysteriaSettings", hysteriaSettings(server))

            "tcp" -> {
                if (server.headerType.equals("http", ignoreCase = true)) {
                    stream.add("tcpSettings", JsonObject().apply {
                        add("header", JsonObject().apply {
                            addProperty("type", "http")
                            add("request", JsonObject().apply {
                                addProperty("version", "1.1")
                                addProperty("method", "GET")
                                add("path", jsonArray(server.path.ifBlank { "/" }))
                                add("headers", JsonObject().apply {
                                    if (server.host.isNotBlank()) {
                                        add("Host", jsonArray(*splitList(server.host).toTypedArray()))
                                    }
                                })
                            })
                        })
                    })
                }
            }
        }

        // Salamander obfuscation ships as a final-mask layer in this core.
        val obfs = ConfigParser.extra(extras, "obfs")
        val obfsPassword = ConfigParser.extra(extras, "obfs-password", "obfsPassword", "obfsParam")
        if (obfs.equals("salamander", ignoreCase = true) && obfsPassword.isNotBlank()) {
            // finalmask is an object of per-transport mask lists; salamander masks UDP.
            stream.add("finalmask", JsonObject().apply {
                add("udp", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("type", "salamander")
                        add("settings", JsonObject().apply {
                            addProperty("password", obfsPassword)
                        })
                    })
                })
            })
        }

        return stream
    }

    private fun hysteriaSettings(server: ServerEntity): JsonObject {
        val extras = server.extraJson
        val auth = server.uuid.ifBlank { ConfigParser.extra(extras, "auth", "password") }
        if (auth.isBlank()) throw IncompleteServerException(R.string.routing_dial_no_password, server.remark)
        return JsonObject().apply {
            addProperty("version", 2)
            addProperty("auth", auth)
            val up = ConfigParser.extra(extras, "upmbps", "up", "upMbps")
            val down = ConfigParser.extra(extras, "downmbps", "down", "downMbps")
            if (up.isNotBlank() && down.isNotBlank()) {
                addProperty("congestion", "brutal")
                addProperty("brutalUp", "${up.filter { it.isDigit() }.ifBlank { "50" }}mbps")
                addProperty("brutalDown", "${down.filter { it.isDigit() }.ifBlank { "50" }}mbps")
            }
            ConfigParser.extra(extras, "udpIdleTimeout").toIntOrNull()
                ?.let { addProperty("udpIdleTimeout", it) }
            val hopPorts = ConfigParser.extra(extras, "mport", "ports", "hopPorts")
            if (hopPorts.isNotBlank()) {
                add("udphop", JsonObject().apply {
                    addProperty("port", hopPorts)
                    addProperty(
                        "interval",
                        ConfigParser.extra(extras, "hopInterval").toIntOrNull() ?: 30
                    )
                })
            }
        }
    }

    /** Mux never helps XTLS-flow nodes and hurts UDP-heavy ones; keep it opt-in. */
    private fun muxSettings(server: ServerEntity): JsonObject? {
        if (flowFor(server) != null) return null
        val concurrency = ConfigParser.extra(server.extraJson, "mux", "concurrency")
            .toIntOrNull() ?: return null
        if (concurrency <= 0) return null
        return JsonObject().apply {
            addProperty("enabled", true)
            addProperty("concurrency", concurrency)
            addProperty("xudpConcurrency", 8)
            addProperty("xudpProxyUDP443", "reject")
        }
    }

    /** The share link asked to skip certificate checks (allowInsecure / insecure=1). */
    fun asksInsecure(server: ServerEntity): Boolean {
        if (!server.security.equals("tls", ignoreCase = true)) return false
        val raw = ConfigParser.extra(server.extraJson, "allowInsecure", "insecure", "skip-cert-verify")
        return raw == "1" || raw.equals("true", ignoreCase = true)
    }

    /** Certificate SHA-256 pins from the link (pcs); malformed ones would sink the config. */
    fun pinsOf(server: ServerEntity): List<String> =
        ConfigParser.extra(server.extraJson, "pcs", "pinnedPeerCertSha256")
            .split(',')
            .map { it.trim().replace(":", "").lowercase() }
            .filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

    fun tlsServerName(server: ServerEntity): String =
        server.sni.ifBlank { server.host.ifBlank { server.address } }

    // ------------------------------------------------------------- routing

    private fun resolveRules(
        server: ServerEntity,
        profile: RoutingProfileEntity?,
        providerRoutingJson: String
    ) = RoutingEngine.mergeRules(
        profileRules = profile?.let { RoutingEngine.parseRules(it.rulesJson) }.orEmpty(),
        useProviderRules = profile?.useProviderRules ?: true,
        providerRoutingJson = providerRoutingJson,
        serverJsonRouting = if (server.rawJson.contains("\"routing\"")) {
            runCatching {
                JsonParser.parseString(server.rawJson).asJsonObject
                    .getAsJsonObject("routing")?.toString().orEmpty()
            }.getOrDefault("")
        } else ""
    )

    private fun serverRoutingOf(server: ServerEntity): JsonObject? {
        if (!server.rawJson.contains("\"routing\"")) return null
        return runCatching {
            JsonParser.parseString(server.rawJson).asJsonObject.getAsJsonObject("routing")
        }.getOrNull()
    }

    /**
     * App-wide filters, ahead of every profile rule so no profile can undo them. All of
     * them send to the `block` (blackhole) outbound, which every config has.
     *
     * Ads: AdGuard DNS alone is not enough. Whatever finds an address without asking
     * the core (a browser's own secure DNS, Private DNS set to a named provider, an app
     * with a built-in resolver, an address cached from before) went straight to the ad
     * server. The same category list in the routing catches those connections by the
     * site name sniffing reads out of them, so ads stay blocked either way; AdGuard's
     * bigger list still applies to every lookup the core answers.
     *
     * Torrents: the core can only tell BitTorrent by sniffing, from the plain TCP
     * handshake (the "BitTorrent protocol" greeting) and from µTP over UDP. A client that
     * encrypts its peer connections (MSE/PE, offered by default in most clients) looks
     * like random bytes over TCP and slips through, as do DHT and UDP tracker packets,
     * which the core has no sniffer for. Trackers reached by name over HTTP(S) are
     * caught by the public-tracker list, which takes away most ways to find peers.
     * This is a strong hint, not a guarantee.
     *
     * The geosite names come from the bundled geosite.dat; CI runs every built config
     * through `xray run -test` against that file, so a list it lacks fails the build.
     */
    private fun filterRules(adBlock: AdBlockMode, blockTorrents: Boolean): List<RoutingRuleDto> = buildList {
        if (adBlock != AdBlockMode.OFF) {
            add(filterRule("Реклама и трекеры", domains = listOf("geosite:category-ads-all")))
        }
        if (adBlock == AdBlockMode.FAMILY) {
            // Family DNS hides adult sites only from lookups the core answers.
            add(filterRule("Сайты для взрослых", domains = listOf("geosite:category-porn")))
        }
        if (blockTorrents) {
            add(filterRule("Торренты", protocol = listOf("bittorrent")))
            add(filterRule("Торрент-трекеры", domains = listOf("geosite:category-public-tracker")))
        }
    }

    private fun filterRule(
        name: String,
        domains: List<String> = emptyList(),
        protocol: List<String> = emptyList()
    ) = RoutingRuleDto(
        name = name,
        outbound = RouteOutbound.BLOCK,
        domains = domains,
        protocol = protocol,
        source = "builtin",
        // Rules are laid out by sortOrder; this keeps the filters first for certain.
        sortOrder = Int.MIN_VALUE
    )

    private fun buildDns(profile: RoutingProfileEntity?, adBlock: AdBlockMode): JsonObject {
        val dns = JsonObject()
        // Happ DnsHosts: fixed answers the provider wants for its own domains.
        profile?.dnsHostsJson?.takeIf { it.isNotBlank() }?.let { hosts ->
            runCatching { JsonParser.parseString(hosts).asJsonObject }
                .getOrNull()
                ?.takeIf { it.size() > 0 }
                ?.let { dns.add("hosts", it) }
        }
        dns.addProperty("queryStrategy", "UseIP")
        dns.addProperty("disableCache", false)
        val servers = JsonArray()
        // With AdGuard on it answers everything, Russian domains included: their ads are
        // just as annoying. The system resolver stays as a last resort.
        if (adBlock != AdBlockMode.OFF) {
            servers.add(adBlock.dohUrl)
            servers.add("localhost")
            dns.add("servers", servers)
            return dns
        }
        val remote = profile?.remoteDns?.ifBlank { null } ?: "https://1.1.1.1/dns-query"
        val domestic = profile?.domesticDns?.ifBlank { null } ?: "https://dns.google/dns-query"
        servers.add(remote)
        servers.add(JsonObject().apply {
            addProperty("address", domestic)
            add("domains", jsonArray("geosite:category-ru", "domain:ru", "domain:su", "domain:рф"))
            add("expectIPs", jsonArray("geoip:ru"))
        })
        servers.add("localhost")
        dns.add("servers", servers)
        return dns
    }

    // --------------------------------------------------------------- helpers

    private fun jsonArray(vararg items: String): JsonArray =
        JsonArray().also { a -> items.forEach { a.add(it) } }

    private fun splitList(value: String): List<String> =
        value.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
}
