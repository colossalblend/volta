package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.R
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
import org.junit.Assert.assertNotEquals
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
        val config = root(ConfigBuilder.build(vless(), profile, blockTorrents = true))
        val rules = rules(config)
        val torrent = rules.indexOfFirst {
            it.getAsJsonArray("protocol")?.toString()?.contains("bittorrent") == true
        }
        val trackers = rules.indexOfFirst { it.domains().contains("geosite:category-public-tracker") }
        val direct = rules.indexOfFirst { it.get("port")?.asString == "0-65535" }
        assertTrue(torrent in 0 until direct)
        assertTrue(trackers in 0 until direct)
        assertEquals("block", rules[torrent].get("outboundTag").asString)
        assertEquals("block", rules[trackers].get("outboundTag").asString)
        assertBlockOutbound(config)
        // The core only recognises BitTorrent by sniffing the traffic of the phone.
        assertSniffs(config)
    }

    @Test
    fun adBlockAlsoBlocksTheAdListInTheRouting() {
        // AdGuard only sees lookups the core answers; a browser with its own secure DNS
        // goes around it, so the ad list must also stop the connections themselves.
        val profile = RoutingProfileEntity(
            name = "Everything direct",
            rulesJson = RoutingEngine.serializeRules(
                listOf(
                    RoutingRuleDto(
                        name = "All",
                        outbound = RouteOutbound.DIRECT,
                        domains = listOf("regexp:.*"),
                        source = "user"
                    )
                )
            )
        )
        AdBlockMode.entries.filter { it != AdBlockMode.OFF }.forEach { mode ->
            val config = root(ConfigBuilder.build(vless(), profile, adBlock = mode))
            val rules = rules(config)
            val ads = rules.indexOfFirst { it.domains().contains("geosite:category-ads-all") }
            val user = rules.indexOfFirst { it.domains().contains("regexp:.*") }
            assertTrue("$mode", ads in 0 until user)
            assertEquals("block", rules[ads].get("outboundTag").asString)
            assertBlockOutbound(config)
            assertSniffs(config)
            // The DNS side stays as it was: AdGuard answers first.
            assertEquals(
                mode.dohUrl,
                config.getAsJsonObject("dns").getAsJsonArray("servers")[0].asString
            )
        }
    }

    @Test
    fun voltasTrackerListIsTieredByMode() {
        fun adRule(mode: AdBlockMode) = rules(root(ConfigBuilder.build(vless(), null, adBlock = mode)))
            .single { it.domains().contains("geosite:category-ads-all") }
        fun hosts(mode: AdBlockMode) =
            root(ConfigBuilder.build(vless(), null, adBlock = mode)).getAsJsonObject("dns")
                .getAsJsonObject("hosts")

        listOf(AdBlockMode.STANDARD, AdBlockMode.FAMILY).forEach { mode ->
            val domains = adRule(mode).domains()
            assertTrue("$mode", domains.containsAll(AdBlockLists.STANDARD))
            assertTrue("$mode", AdBlockLists.STRICT.none { it in domains })
            assertTrue("$mode", hosts(mode).keySet().containsAll(AdBlockLists.STANDARD))
            assertTrue("$mode", AdBlockLists.STRICT.none { hosts(mode).has(it) })
        }
        val strict = adRule(AdBlockMode.STRICT)
        assertEquals("block", strict.get("outboundTag").asString)
        assertTrue(strict.domains().containsAll(AdBlockLists.STANDARD + AdBlockLists.STRICT))
        val strictHosts = hosts(AdBlockMode.STRICT)
        (AdBlockLists.STANDARD + AdBlockLists.STRICT).forEach { name ->
            assertEquals(name, listOf("0.0.0.0", "::"), strictHosts.getAsJsonArray(name).map { it.asString })
        }
        // Strict keeps Standard's AdGuard server; it is not a DNS mode of its own.
        assertEquals(AdBlockMode.STANDARD.dohUrl, AdBlockMode.STRICT.dohUrl)
        assertFalse(ConfigBuilder.build(vless(), null, adBlock = AdBlockMode.STRICT).contains("category-porn"))

        // The hosts the owner's test found open, by tier.
        listOf("metrika.yandex.ru", "appmetrica.yandex.ru", "advertising.yandex.ru", "ads.vk.com",
            "ads.x.com", "ads.tiktok.com", "ads-api.tiktok.com", "ads.microsoft.com", "o0.ingest.sentry.io",
            "browser.sentry-cdn.com", "us.i.posthog.com", "r.lr-ingest.com", "api.bugsnag.com",
            "tr.facebook.com", "fls-na.amazon.com", "adtech.yahooinc.com", "www.tkqlhce.com"
        ).forEach { host ->
            assertTrue(host, AdBlockLists.STANDARD.any { matches(it, host) })
        }
        listOf("privacyportal.onetrust.com", "cdn.cookielaw.org", "consent.trustarc.com",
            "sdk.privacy-center.org", "cdn.privacy-mgmt.com", "cmp.osano.com",
            "fundingchoicesmessages.google.com", "cdn.dynamicyield.com",
            "clientstream.launchdarkly.com", "xp.apple.com", "s.youtube.com"
        ).forEach { host ->
            assertTrue(host, AdBlockLists.STRICT.any { matches(it, host) })
            assertFalse(host, AdBlockLists.STANDARD.any { matches(it, host) })
        }
        // One rule for geosite and Volta's list together, not one per tier.
        assertEquals(
            1,
            rules(root(ConfigBuilder.build(vless(), null, adBlock = AdBlockMode.STRICT)))
                .count { r -> r.get("outboundTag")?.asString == "block" && r.domains().isNotEmpty() }
        )
    }

    @Test
    fun theNeverBlockHostsAreNeverBlocked() {
        // Logins, YouTube playback, links in mail, support chats: whatever the mode, no
        // block rule and no DNS answer of Volta's own may catch them.
        assertTrue(AdBlockLists.NEVER_BLOCK.containsAll(listOf(
            "graph.instagram.com", "i.instagram.com", "graph.facebook.com", "connect.facebook.net",
            "redirector.googlevideo.com", "bnc.lt", "click.mailchimp.com", "widget.intercom.io",
            "widgets.pinterest.com", "tagmanager.google.com"
        )))
        AdBlockMode.entries.forEach { mode ->
            val config = root(ConfigBuilder.build(vless(), null, adBlock = mode, blockTorrents = true))
            val blocked = rules(config).filter { it.get("outboundTag")?.asString == "block" }
                .flatMap { it.domains() }
            val hosts = config.getAsJsonObject("dns")?.getAsJsonObject("hosts")?.keySet().orEmpty()
            AdBlockLists.NEVER_BLOCK.forEach { host ->
                assertTrue("$mode blocks $host", blocked.none { matches(it, host) })
                assertTrue("$mode answers $host", hosts.none { matches(it, host) })
            }
        }
    }

    @Test
    fun adFilterComesBeforeEveryProfileAndProviderRule() {
        // «Российские сайты напрямую» sends geosite:category-ru and geoip:ru direct; the
        // ad rule must still be met first, or metrika.yandex.ru would go direct.
        val ru = RoutingEngine.builtInProfiles().single { it.name == RoutingEngine.BUILTIN_RU_NAME }
        val providerJson =
            """{"rules":[{"type":"field","domain":["domain:yandex.ru","regexp:.*"],"outboundTag":"direct"}]}"""
        val shipped = vless().copy(
            rawJson = """{"routing":{"domainStrategy":"AsIs","rules":[""" +
                """{"type":"field","domain":["geosite:category-ru","domain:shipped.example"],"outboundTag":"direct"},""" +
                """{"type":"field","ip":["0.0.0.0/0"],"outboundTag":"direct"}]}}"""
        )
        val configs = mapOf(
            "ru profile" to ConfigBuilder.build(vless(), ru, adBlock = AdBlockMode.STANDARD),
            "provider json" to ConfigBuilder.build(
                vless(),
                ru.copy(useProviderRules = true),
                providerJson,
                adBlock = AdBlockMode.STRICT
            ),
            "server routing" to ConfigBuilder.build(
                shipped,
                null,
                preferServerRouting = true,
                adBlock = AdBlockMode.FAMILY
            )
        )
        configs.forEach { (name, json) ->
            val rules = rules(root(json))
            val ads = rules.indexOfFirst { it.domains().contains("geosite:category-ads-all") }
            assertTrue(name, ads >= 0)
            assertTrue(name, rules[ads].domains().contains("domain:metrika.yandex.ru"))
            // Every rule a profile or provider sent (anything not the app's own plumbing)
            // comes after it.
            val firstForeign = rules.indexOfFirst { r ->
                r.domains().any {
                    it == "geosite:category-ru" || it == "regexp:.*" || it == "domain:shipped.example"
                } || r.ips().contains("geoip:ru") || r.ips().contains("0.0.0.0/0")
            }
            assertTrue(name, firstForeign > ads)
            // Before the ad rule only DNS plumbing, the exit check and local addresses.
            rules.take(ads).forEach { r ->
                assertTrue("$name: $r", r.domains().isEmpty())
                assertTrue(
                    "$name: $r",
                    r.ips().all { it == ConfigBuilder.TUN_DNS || it == "geoip:private" }
                )
            }
        }
    }

    @Test
    fun providersDnsHostsWinOverVoltasList() {
        val profile = RoutingProfileEntity(
            name = "Hosts",
            dnsHostsJson = """{"domain:ads.vk.com":"10.0.0.7","provider.example":"10.0.0.8"}"""
        )
        val hosts = root(ConfigBuilder.build(vless(), profile, adBlock = AdBlockMode.STANDARD))
            .getAsJsonObject("dns").getAsJsonObject("hosts")
        assertEquals("10.0.0.7", hosts.get("domain:ads.vk.com").asString)
        assertEquals("10.0.0.8", hosts.get("provider.example").asString)
        assertTrue(hosts.has("domain:metrika.yandex.ru"))
        // Off: the provider's hosts alone.
        val off = root(ConfigBuilder.build(vless(), profile)).getAsJsonObject("dns").getAsJsonObject("hosts")
        assertEquals(setOf("domain:ads.vk.com", "provider.example"), off.keySet())
    }

    @Test
    fun familyModeAlsoBlocksAdultSitesInTheRouting() {
        val family = ConfigBuilder.build(vless(), null, adBlock = AdBlockMode.FAMILY)
        val standard = ConfigBuilder.build(vless(), null, adBlock = AdBlockMode.STANDARD)
        val adult = rules(root(family)).firstOrNull { it.domains().contains("geosite:category-porn") }
        assertNotNull(adult)
        assertEquals("block", adult!!.get("outboundTag").asString)
        assertFalse(standard.contains("geosite:category-porn"))
    }

    @Test
    fun filtersStayOnTheBundledListsWithASubscriptionsOwnGeodata() {
        // A provider's geosite file need not carry the ad or tracker lists.
        val text = ConfigBuilder.build(
            vless(),
            null,
            adBlock = AdBlockMode.STANDARD,
            blockTorrents = true,
            geo = com.ars3nb.volta.core.routing.GeoNames("geoip_s1.dat", "geosite_s1.dat")
        )
        assertTrue(text.contains("\"geosite:category-ads-all\""))
        assertTrue(text.contains("\"geosite:category-public-tracker\""))
        assertFalse(text.contains("ext:geosite_s1.dat:category-ads-all"))
    }

    @Test
    fun filtersOffLeaveTheConfigAlone() {
        val text = ConfigBuilder.build(vless(), null)
        assertFalse(text.contains("bittorrent"))
        assertFalse(text.contains("adguard"))
        assertFalse(text.contains("category-ads-all"))
        assertFalse(text.contains("category-porn"))
        assertFalse(text.contains("category-public-tracker"))
    }

    @Test
    fun androidIsPointedAtTheCoreWheneverTheCoreAnswersDns() {
        // A public resolver as the tunnel's DNS invites Android and Chrome to switch to
        // encrypted DNS that goes around the core (and AdGuard) altogether.
        listOf(
            ConfigBuilder.build(vless(), null, localDns = true),
            ConfigBuilder.build(vless(), null, localDns = false, adBlock = AdBlockMode.STANDARD)
        ).forEach { json ->
            assertTrue(ConfigBuilder.answersAppDns(json))
            assertEquals(listOf(ConfigBuilder.TUN_DNS), ConfigBuilder.tunDnsServers(json))

            val config = root(json)
            val rules = rules(config)
            // Lookups sent there, over UDP and TCP, reach the core's resolver...
            assertEquals("dns-out", rules[0].get("outboundTag").asString)
            val tcp = rules.indexOfFirst {
                it.ips().contains(ConfigBuilder.TUN_DNS) && it.get("port")?.asString == "53"
            }
            assertTrue(tcp >= 0)
            assertEquals("dns-out", rules[tcp].get("outboundTag").asString)
            assertTrue(rules[tcp].toString().contains("tcp"))
            // ...the rest (Android trying DNS-over-TLS there) is refused, and both come
            // before local addresses are sent direct.
            val refuse = rules.indexOfFirst {
                it.ips() == listOf(ConfigBuilder.TUN_DNS) && it.get("port") == null
            }
            val local = rules.indexOfFirst { it.ips().contains("geoip:private") }
            assertEquals("block", rules[refuse].get("outboundTag").asString)
            assertTrue(tcp < refuse && refuse < local)

            // Lookups the core does not answer go to a real resolver, not to TUN_DNS.
            val dnsOut = config.getAsJsonArray("outbounds").map { it.asJsonObject }
                .first { it.get("tag").asString == "dns-out" }
            val forward = dnsOut.getAsJsonObject("settings").get("address").asString
            assertNotEquals(ConfigBuilder.TUN_DNS, forward)
        }
    }

    @Test
    fun withoutTheCoresDnsAndroidKeepsARealResolver() {
        val json = ConfigBuilder.build(vless(), null, localDns = false)
        assertFalse(ConfigBuilder.answersAppDns(json))
        assertFalse(ConfigBuilder.tunDnsServers(json).contains(ConfigBuilder.TUN_DNS))
        assertTrue(ConfigBuilder.tunDnsServers(json).isNotEmpty())
        assertFalse(json.contains(ConfigBuilder.TUN_DNS))
        assertFalse(ConfigBuilder.answersAppDns("not json"))
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
    fun exitCheckPortAlwaysLeavesThroughTheExit() {
        // A profile that sends everything direct must not catch the exit-address lookup.
        val profile = RoutingProfileEntity(
            name = "Everything direct",
            rulesJson = RoutingEngine.serializeRules(
                listOf(RoutingRuleDto(name = "all", outbound = RouteOutbound.DIRECT, domains = listOf("regexp:.*")))
            )
        )
        val config = root(ConfigBuilder.build(vless(), profile))
        val inbound = config.getAsJsonArray("inbounds").map { it.asJsonObject }
            .first { it.get("tag").asString == ConfigBuilder.EXIT_CHECK_TAG }
        assertEquals(ConfigBuilder.EXIT_CHECK_PORT, inbound.get("port").asInt)
        assertEquals("127.0.0.1", inbound.get("listen").asString)

        val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val exitRule = rules.indexOfFirst {
            it.getAsJsonArray("inboundTag")?.toString()?.contains(ConfigBuilder.EXIT_CHECK_TAG) == true
        }
        val directRule = rules.indexOfFirst { it.toString().contains("regexp:.*") }
        assertTrue(exitRule in 0 until directRule)
        assertEquals(ConfigBuilder.PROXY_TAG, rules[exitRule].get("outboundTag").asString)
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
        // Said in plain words: what the user should do, not the transport's name.
        assertEquals(R.string.routing_dial_outdated, error.reason)
        assertEquals(listOf("Test"), error.args.toList())
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

    private fun rules(config: JsonObject): List<JsonObject> =
        config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }

    private fun JsonObject.domains(): List<String> =
        getAsJsonArray("domain")?.map { it.asString }.orEmpty()

    private fun JsonObject.ips(): List<String> =
        getAsJsonArray("ip")?.map { it.asString }.orEmpty()

    /** Whether the core's domain matcher [entry] catches [host] (geosite: is not opened). */
    private fun matches(entry: String, host: String): Boolean = when {
        entry.startsWith("domain:") -> entry.removePrefix("domain:").let { host == it || host.endsWith(".$it") }
        entry.startsWith("full:") -> host == entry.removePrefix("full:")
        entry.startsWith("keyword:") -> host.contains(entry.removePrefix("keyword:"))
        entry.startsWith("regexp:") -> Regex(entry.removePrefix("regexp:")).containsMatchIn(host)
        entry.startsWith("geosite:") || entry.startsWith("ext:") -> false
        else -> host == entry || host.endsWith(".$entry")
    }

    /** The filters' target: a blackhole tagged `block`. */
    private fun assertBlockOutbound(config: JsonObject) {
        val block = config.getAsJsonArray("outbounds").map { it.asJsonObject }
            .firstOrNull { it.get("tag").asString == "block" }
        assertNotNull(block)
        assertEquals("blackhole", block!!.get("protocol").asString)
    }

    /** The inbound the phone's traffic arrives on sniffs it, so filters can match. */
    private fun assertSniffs(config: JsonObject) {
        val socks = config.getAsJsonArray("inbounds").map { it.asJsonObject }
            .first { it.get("tag").asString == "socks" }
        assertTrue(socks.getAsJsonObject("settings").get("udp").asBoolean)
        val sniffing = socks.getAsJsonObject("sniffing")
        assertTrue(sniffing.get("enabled").asBoolean)
        val overrides = sniffing.getAsJsonArray("destOverride").map { it.asString }
        assertTrue(overrides.containsAll(listOf("http", "tls", "quic")))
    }

    private fun proxyOutbound(json: String): JsonObject =
        root(json).getAsJsonArray("outbounds")
            .map { it.asJsonObject }
            .first { it.get("tag").asString == "proxy" }
}
