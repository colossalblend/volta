package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBuilderTest {

    // ------------------------------------------------------------------ routing

    @Test
    fun adGuardAnswersEveryLookupEvenWithTheTunnelDnsOff() {
        val root = root(
            ConfigBuilder.build(vless(), null, localDns = false, adBlock = AdBlockMode.STANDARD)
        )
        val servers = root.getAsJsonObject("dns").getAsJsonArray("servers")
        assertEquals(AdBlockMode.STANDARD.dohUrl, servers[0].asString)
        assertFalse(servers.toString().contains("1.1.1.1"))
        // Plain DNS from apps is handed to the core so AdGuard actually sees it.
        val rules = root.getAsJsonObject("routing").getAsJsonArray("rules")
        assertEquals("dns-out", rules[0].asJsonObject.get("outboundTag").asString)
    }

    @Test
    fun torrentFilterComesBeforeProfileRules() {
        val profile = RoutingProfileEntity(
            name = "Everything direct",
            rulesJson = RoutingEngine.serializeRules(
                listOf(
                    RoutingRuleDto(
                        name = "All",
                        outbound = RouteOutbound.DIRECT,
                        ports = "0-65535",
                        source = "user"
                    )
                )
            )
        )
        val rules = root(ConfigBuilder.build(vless(), profile, blockTorrents = true))
            .getAsJsonObject("routing").getAsJsonArray("rules")
            .map { it.asJsonObject }
        val torrent = rules.indexOfFirst { it.toString().contains("bittorrent") }
        val direct = rules.indexOfFirst { it.get("port")?.asString == "0-65535" }
        assertTrue(torrent in 0 until direct)
        assertEquals("block", rules[torrent].get("outboundTag").asString)
    }

    @Test
    fun filtersOffLeaveTheConfigAlone() {
        val text = ConfigBuilder.build(vless(), null)
        assertFalse(text.contains("bittorrent"))
        assertFalse(text.contains("adguard"))
    }

    @Test
    fun usesOnlyPassedSubscriptionProfileRules() {
        val profile = RoutingProfileEntity(
            name = "Subscription A",
            sourceSubscriptionId = 1,
            // What applySubscriptionRouting actually writes for a provider profile.
            useProviderRules = true,
            rulesJson = RoutingEngine.serializeRules(
                listOf(
                    RoutingRuleDto(
                        name = "Only A",
                        outbound = RouteOutbound.DIRECT,
                        domains = listOf("domain:subscription-a.example"),
                        source = "provider"
                    )
                )
            )
        )

        val routing = root(ConfigBuilder.build(vless(), profile)).getAsJsonObject("routing")
        val text = routing.toString()

        assertTrue(text.contains("subscription-a.example"))
        assertFalse(text.contains("subscription-b.example"))
    }

    @Test
    fun turningOffProviderRulesDropsTheStoredProviderRules() {
        // The switch means "ignore what the provider sent", so its own rules go too.
        val profile = RoutingProfileEntity(
            name = "Subscription A",
            sourceSubscriptionId = 1,
            useProviderRules = false,
            rulesJson = RoutingEngine.serializeRules(
                listOf(
                    RoutingRuleDto(
                        name = "Only A",
                        outbound = RouteOutbound.DIRECT,
                        domains = listOf("domain:subscription-a.example"),
                        source = "provider"
                    )
                )
            )
        )

        val text = root(ConfigBuilder.build(vless(), profile)).getAsJsonObject("routing").toString()

        assertFalse(text.contains("subscription-a.example"))
    }

    @Test
    fun profileWithProviderRulesOffIgnoresProviderJson() {
        val profile = RoutingProfileEntity(
            name = "User override",
            useProviderRules = false,
            rulesJson = RoutingEngine.serializeRules(
                listOf(
                    RoutingRuleDto(
                        name = "Mine",
                        outbound = RouteOutbound.DIRECT,
                        domains = listOf("domain:mine.example"),
                        source = "user"
                    )
                )
            )
        )
        val providerJson =
            """{"rules":[{"type":"field","domain":["domain:provider.example"],"outboundTag":"direct"}]}"""

        val text = root(ConfigBuilder.build(vless(), profile, providerJson))
            .getAsJsonObject("routing").toString()

        assertTrue(text.contains("mine.example"))
        assertFalse(text.contains("provider.example"))
    }

    @Test
    fun routesPlainDnsToTheCoreResolver() {
        val routing = root(ConfigBuilder.build(vless(), null, localDns = true))
            .getAsJsonObject("routing")
        val first = routing.getAsJsonArray("rules")[0].asJsonObject

        assertEquals("dns-out", first.get("outboundTag").asString)
        assertEquals("53", first.get("port").asString)

        val outboundTags = root(ConfigBuilder.build(vless(), null, localDns = true))
            .getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertTrue(outboundTags.contains("dns-out"))
    }

    @Test
    fun localDnsOffDropsTheDnsPlumbing() {
        val config = root(ConfigBuilder.build(vless(), null, localDns = false))
        val tags = config.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }

        assertFalse(tags.contains("dns-out"))
        assertNull(config.get("dns"))
    }

    // ---------------------------------------------------------------- protocols

    @Test
    fun buildsVlessRealityOutbound() {
        val server = vless().copy(
            security = "reality",
            pbk = "PUBKEY",
            sid = "ab12",
            fp = "chrome",
            sni = "www.example.com",
            flow = "xtls-rprx-vision"
        )
        val proxy = proxyOutbound(ConfigBuilder.build(server, null))

        assertEquals("vless", proxy.get("protocol").asString)
        val user = proxy.getAsJsonObject("settings")
            .getAsJsonArray("vnext")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertEquals("xtls-rprx-vision", user.get("flow").asString)

        val reality = proxy.getAsJsonObject("streamSettings").getAsJsonObject("realitySettings")
        assertEquals("PUBKEY", reality.get("publicKey").asString)
        assertEquals("www.example.com", reality.get("serverName").asString)
    }

    @Test
    fun buildsHysteria2OutboundWithTransportSettings() {
        val server = ServerEntity(
            remark = "HY",
            protocol = ProtocolType.HYSTERIA2,
            address = "hy.example",
            port = 8443,
            uuid = "s3cret",
            network = "hysteria",
            security = "tls",
            sni = "cdn.example",
            alpn = "h3",
            extraJson = """{"obfs":"salamander","obfs-password":"pw","upmbps":"50","downmbps":"200"}"""
        )
        val proxy = proxyOutbound(ConfigBuilder.build(server, null))

        assertEquals("hysteria", proxy.get("protocol").asString)
        val settings = proxy.getAsJsonObject("settings")
        assertEquals(2, settings.get("version").asInt)
        assertEquals("hy.example", settings.get("address").asString)
        assertEquals(8443, settings.get("port").asInt)

        val stream = proxy.getAsJsonObject("streamSettings")
        assertEquals("hysteria", stream.get("network").asString)
        assertEquals("tls", stream.get("security").asString)
        val hy = stream.getAsJsonObject("hysteriaSettings")
        assertEquals(2, hy.get("version").asInt)
        assertEquals("s3cret", hy.get("auth").asString)
        assertEquals("brutal", hy.get("congestion").asString)
        // Salamander travels as a final-mask layer, not inside hysteriaSettings.
        assertNotNull(stream.getAsJsonObject("finalmask").getAsJsonArray("udp"))
    }

    @Test
    fun buildsWireGuardOutboundWithoutStreamSettings() {
        val server = ServerEntity(
            remark = "WG",
            protocol = ProtocolType.WIREGUARD,
            address = "wg.example",
            port = 51820,
            uuid = "PRIV=",
            pbk = "PUB=",
            extraJson = """{"address":"10.7.0.2/32","mtu":"1420","reserved":"1,2,3"}"""
        )
        val proxy = proxyOutbound(ConfigBuilder.build(server, null))

        assertEquals("wireguard", proxy.get("protocol").asString)
        assertNull(proxy.get("streamSettings"))
        val settings = proxy.getAsJsonObject("settings")
        assertEquals("PRIV=", settings.get("secretKey").asString)
        assertEquals(1420, settings.get("mtu").asInt)
        val peer = settings.getAsJsonArray("peers")[0].asJsonObject
        assertEquals("wg.example:51820", peer.get("endpoint").asString)
        assertEquals("PUB=", peer.get("publicKey").asString)
    }

    @Test
    fun buildsSocksAndShadowsocksOutbounds() {
        val socks = proxyOutbound(
            ConfigBuilder.build(
                ServerEntity(
                    remark = "S",
                    protocol = ProtocolType.SOCKS,
                    address = "s.example",
                    port = 1080,
                    uuid = "pass",
                    extraJson = """{"user":"bob"}"""
                ),
                null
            )
        )
        assertEquals("socks", socks.get("protocol").asString)
        val user = socks.getAsJsonObject("settings")
            .getAsJsonArray("servers")[0].asJsonObject
            .getAsJsonArray("users")[0].asJsonObject
        assertEquals("bob", user.get("user").asString)

        val ss = proxyOutbound(
            ConfigBuilder.build(
                ServerEntity(
                    remark = "SS",
                    protocol = ProtocolType.SHADOWSOCKS,
                    address = "ss.example",
                    port = 8388,
                    uuid = "pw",
                    encryption = "aes-256-gcm"
                ),
                null
            )
        )
        assertEquals("shadowsocks", ss.get("protocol").asString)
        assertEquals(
            "aes-256-gcm",
            ss.getAsJsonObject("settings").getAsJsonArray("servers")[0]
                .asJsonObject.get("method").asString
        )
    }

    @Test
    fun mapsRetiredTransportsOntoSupportedOnes() {
        val h2 = proxyOutbound(ConfigBuilder.build(vless().copy(network = "h2", path = "/p"), null))
        val stream = h2.getAsJsonObject("streamSettings")
        assertEquals("xhttp", stream.get("network").asString)
        assertEquals("stream-one", stream.getAsJsonObject("xhttpSettings").get("mode").asString)
    }

    @Test
    fun refusesTransportsTheCoreDropped() {
        val quic = vless().copy(network = "quic")
        val error = assertThrows(ConfigBuilder.UnsupportedProtocolException::class.java) {
            ConfigBuilder.build(quic, null)
        }
        assertTrue(error.message!!.contains("QUIC"))
        assertFalse(ConfigBuilder.canDial(quic))
        assertNotNull(ConfigBuilder.dialProblem(quic))
    }

    @Test
    fun unknownProtocolIsNeverSilentlyDialledAsVless() {
        val unknown = vless().copy(protocol = ProtocolType.UNKNOWN)
        assertThrows(ConfigBuilder.UnsupportedProtocolException::class.java) {
            ConfigBuilder.build(unknown, null)
        }
        assertFalse(ConfigBuilder.canDial(unknown))
    }

    // ----------------------------------------------------------------- balancer

    @Test
    fun poolOfManyNodesBuildsAnObservatoryBackedBalancer() {
        val pool = (1..3).map {
            vless().copy(id = it.toLong(), remark = "N$it", address = "n$it.example")
        }
        val config = root(ConfigBuilder.build(pool.first(), null, balancerPool = pool))

        val tags = config.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertTrue(tags.containsAll(listOf("proxy-0", "proxy-1", "proxy-2")))
        assertFalse(tags.contains("proxy"))

        val observatory = config.getAsJsonObject("observatory")
        assertNotNull(observatory)
        assertEquals("proxy-", observatory.getAsJsonArray("subjectSelector")[0].asString)

        val routing = config.getAsJsonObject("routing")
        val balancer = routing.getAsJsonArray("balancers")[0].asJsonObject
        assertEquals(ConfigBuilder.BALANCER_TAG, balancer.get("tag").asString)
        assertEquals("leastPing", balancer.getAsJsonObject("strategy").get("type").asString)

        // The catch-all rule must target the balancer, not a now-missing "proxy" outbound.
        val last = routing.getAsJsonArray("rules").last().asJsonObject
        assertEquals(ConfigBuilder.BALANCER_TAG, last.get("balancerTag").asString)
        assertNull(last.get("outboundTag"))
    }

    @Test
    fun singleNodePoolStaysOnThePlainProxyOutbound() {
        val server = vless()
        val config = root(ConfigBuilder.build(server, null, balancerPool = listOf(server)))

        val tags = config.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertTrue(tags.contains("proxy"))
        assertNull(config.get("observatory"))
        assertNull(config.getAsJsonObject("routing").get("balancers"))
    }

    @Test
    fun undiallableNodesAreDroppedFromThePool() {
        val good = vless().copy(id = 1)
        val broken = vless().copy(id = 2, network = "quic")
        val config = root(ConfigBuilder.build(good, null, balancerPool = listOf(good, broken)))

        // One usable node left => plain proxy outbound, no half-broken balancer.
        val tags = config.getAsJsonArray("outbounds").map { it.asJsonObject.get("tag").asString }
        assertTrue(tags.contains("proxy"))
        assertFalse(tags.any { it.startsWith("proxy-") })
    }

    @Test
    fun probeConfigCarriesOnlyTheOutbound() {
        val probe = root(ConfigBuilder.buildProbe(vless()))
        assertNull(probe.get("inbounds"))
        assertNull(probe.get("routing"))
        assertEquals("proxy", probe.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
    }

    @Test
    fun neverWritesTheRemovedAllowInsecureFlag() {
        val insecure = vless().copy(extraJson = """{"allowInsecure":"1"}""")
        val tls = proxyOutbound(ConfigBuilder.build(insecure, null))
            .getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")

        assertNull(tls.get("allowInsecure"))
        assertTrue(ConfigBuilder.asksInsecure(insecure))
    }

    @Test
    fun pinsTheCertificateFromTheLinkAndDropsMalformedPins() {
        val hash = "AB:".repeat(31) + "AB"
        val pinned = vless().copy(extraJson = """{"allowInsecure":"1","pcs":"$hash,nonsense"}""")
        val tls = proxyOutbound(ConfigBuilder.build(pinned, null))
            .getAsJsonObject("streamSettings").getAsJsonObject("tlsSettings")

        assertEquals("ab".repeat(32), tls.get("pinnedPeerCertSha256").asString)
    }

    // ------------------------------------------------------------------ helpers

    private fun vless() = ServerEntity(
        remark = "Test",
        protocol = ProtocolType.VLESS,
        address = "node.example",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        security = "tls"
    )

    private fun root(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    private fun proxyOutbound(json: String): JsonObject =
        root(json).getAsJsonArray("outbounds")
            .map { it.asJsonObject }
            .first { it.get("tag").asString == "proxy" }
}
