package com.ars3nb.volta.core.openflux

import com.ars3nb.volta.R
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.server.ServerClassifier
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerRole
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Links made by the OpenFlux core itself (`openflux --make-link`). */
class OpenFluxLinkTest {

    @Test
    fun readsASessionWithTwoCarriers() {
        val link = OpenFluxLink.parse(SESSION)!!
        assertEquals("Финляндия · Яндекс", link.name)
        assertTrue(link.encrypted)
        assertEquals(listOf("direct", "yandex"), link.transports.map { it.type })
        assertEquals("203.0.113.7" to 8445, link.endpoint)
        assertEquals("напрямую + Яндекс Документы", link.carriersLabel)
    }

    @Test
    fun readsPlainAndStreamLinks() {
        val plain = OpenFluxLink.parse(PLAIN)!!
        assertFalse(plain.encrypted)
        assertEquals("disk.yandex.ru" to 443, plain.endpoint)
        val stream = OpenFluxLink.parse(STREAM)!!
        assertEquals("stream", stream.mode)
        assertEquals("mailru", stream.transports.single().type)
    }

    @Test
    fun toleratesWhatChatsDoToALink() {
        val body = SESSION.removePrefix(OpenFluxLink.PREFIX)
        val mangled = OpenFluxLink.PREFIX + body.chunked(40).joinToString("\n ")
            .replace('-', '+').replace('_', '/') + "=="
        assertEquals("Финляндия · Яндекс", OpenFluxLink.parse(mangled)?.name)
    }

    @Test
    fun rejectsWhatIsNotALink() {
        assertNull(OpenFluxLink.parse("openflux://v2/abc"))
        assertNull(OpenFluxLink.parse("OPENFLUX://v1/" + SESSION.removePrefix(OpenFluxLink.PREFIX)))
        assertNull(OpenFluxLink.parse(OpenFluxLink.PREFIX + "not-deflate"))
        assertNull(OpenFluxLink.parse("vless://x@y:1"))
    }

    @Test
    fun unwrapsVoltaLinks() {
        val body = SESSION.removePrefix(OpenFluxLink.PREFIX)
        assertEquals(SESSION, OpenFluxLink.unwrap("volta://$SESSION"))
        assertEquals(SESSION, OpenFluxLink.unwrap("volta://of/$body"))
        assertEquals(SESSION, OpenFluxLink.unwrap("volta://of/v1/$body"))
        assertNull(OpenFluxLink.unwrap("volta://import?url=https://example.com"))
    }

    @Test
    fun importsAsAnOpenFluxServer() {
        val server = ConfigParser.parseShareLink(SESSION)!!
        assertEquals(ProtocolType.OPENFLUX, server.protocol)
        assertEquals("Финляндия · Яндекс", server.remark)
        assertEquals("203.0.113.7", server.address)
        assertEquals(8445, server.port)
        assertEquals("direct+yandex", server.network)
        assertEquals(SESSION, server.rawLink)

        val short = ConfigParser.parseShareLink("volta://of/" + PLAIN.removePrefix(OpenFluxLink.PREFIX))!!
        assertEquals("OpenFlux · Яндекс Документы", short.remark)
    }

    @Test
    fun findsLinksInASubscription() {
        val body = "#profile-title: Test\n$SESSION\nvolta://$PLAIN\nvless://11111111-2222-4333-8444-555555555555@h:443#V\n"
        val links = ConfigParser.extractShareLinks(body)
        assertTrue(SESSION in links)
        assertTrue(PLAIN in links)
        val servers = ConfigParser.parseSubscriptionBody(body).servers
        assertEquals(2, servers.count { it.protocol == ProtocolType.OPENFLUX })
    }

    @Test
    fun theCoreHandsTrafficToTheHelper() {
        val server = ConfigParser.parseShareLink(SESSION)!!
        val outbound = ConfigBuilder.outboundFor(server, ConfigBuilder.PROXY_TAG)
        assertEquals("socks", outbound.get("protocol").asString)
        val target = outbound.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("127.0.0.1", target.get("address").asString)
        assertEquals(OpenFluxRunner.PORT, target.get("port").asInt)
        assertNull(outbound.get("streamSettings"))
        val config = JsonParser.parseString(ConfigBuilder.build(server, null)).asJsonObject
        assertNotNull(config.getAsJsonArray("outbounds"))
    }

    @Test
    fun autoModesPickOpenFluxInItsGroup() {
        val openFlux = ConfigParser.parseShareLink(SESSION)!!.copy(id = 1)
        val vless = ConfigParser.parseShareLink(
            "vless://11111111-2222-4333-8444-555555555555@h:443?security=tls&type=tcp#V"
        )!!.copy(id = 2)
        val both = listOf(openFlux, vless)
        assertTrue(AutoBalancer.poolFor(both, BalancerScope.ALL).any { it.id == 1L })
        assertTrue(AutoBalancer.poolFor(both, BalancerScope.NORMAL).any { it.id == 1L })
        assertFalse(AutoBalancer.poolFor(both, BalancerScope.BYPASS).any { it.id == 1L })
        val lte = openFlux.copy(role = ServerRole.BYPASS_WHITELIST)
        assertEquals(listOf(1L), AutoBalancer.poolFor(listOf(lte, vless), BalancerScope.BYPASS).map { it.id })
    }

    // ------------------------------------------------------------ several servers

    @Test
    fun aLinkWithSeveralCarriersIsOneServer() {
        // The node wizard's link: a Volga document first, direct to the exit as backup.
        // Both lead to the same exit; the core runs them together and fails over.
        val servers = ConfigParser.parseSubscriptionBody(NODE).servers
        val node = servers.single()
        assertEquals("Германия", node.remark)
        assertEquals("vyandex+direct", node.network)
        assertEquals("198.51.100.20", node.address)
        assertEquals(8445, node.port)
        val link = OpenFluxLink.parse(node.rawLink)!!
        assertEquals(
            listOf(
                OpenFluxLink.Endpoint("vyandex", "docs.yandex.ru", 443, 100),
                OpenFluxLink.Endpoint("direct", "198.51.100.20", 8445, 50)
            ),
            link.endpoints
        )
        assertFalse(link.onlyThroughServices)
    }

    @Test
    fun severalLinksAreSeveralServers() {
        val body = listOf(
            "$DOCS_A#%5BL%5D%20Москва%20документы",
            "$DOCS_B#[L] Питер документы",
            NODE,
            "$DIRECT#[H] Нидерланды"
        ).joinToString("\n")
        val servers = ConfigParser.parseSubscriptionBody(body).servers
        assertEquals(4, servers.size)
        assertTrue(servers.all { it.protocol == ProtocolType.OPENFLUX })
        assertEquals(
            listOf("[L] Москва документы", "[L] Питер документы", "Германия", "[H] Нидерланды"),
            servers.map { it.remark }
        )
        // The name after '#' is Volta's: the core gets the bare link.
        assertEquals(DOCS_A, servers[0].rawLink)
        assertEquals(DIRECT, servers[3].rawLink)
        // Two exits over the same service are still two servers.
        assertEquals(servers[0].address, servers[1].address)
        assertFalse(
            ServerClassifier.stableKey(servers[0]) == ServerClassifier.stableKey(servers[1])
        )

        val merged = ServerClassifier.mergeProviderUpdate(emptyList(), servers, 3)
        assertEquals(
            listOf(
                ServerRole.BYPASS_WHITELIST, ServerRole.BYPASS_WHITELIST,
                ServerRole.NORMAL, ServerRole.NORMAL
            ),
            merged.map { it.role }
        )
        assertEquals(listOf("Москва документы", "Питер документы"), merged.take(2).map { it.remark })
    }

    @Test
    fun pastedLinksWithoutLineBreaksAllCount() {
        val pasted = "$DOCS_A $DOCS_B\t$DIRECT"
        assertEquals(3, ConfigParser.extractShareLinks(pasted).size)
        assertEquals(3, ConfigParser.parseSubscriptionBody(pasted).servers.size)
        // One link wrapped by a chat is still one link.
        val wrapped = DOCS_A.chunked(30).joinToString("\n")
        assertEquals(1, ConfigParser.extractShareLinks(wrapped).size)
    }

    @Test
    fun jsonConfigurationsAreOpenFluxServers() {
        val one = """
            {"name": "Финляндия", "negotiate": true, "secret": "json-secret-0123456789abcdef",
             "transports": [{"type": "direct", "dial": "203.0.113.9:8445", "priority": 100},
                            {"type": "yandex", "url": "https://disk.yandex.ru/i/json", "priority": 50}]}
        """.trimIndent()
        val single = ConfigParser.parseSubscriptionBody(one).servers.single()
        assertEquals(ProtocolType.OPENFLUX, single.protocol)
        assertEquals("Финляндия", single.remark)
        assertEquals("direct+yandex", single.network)
        // Made into the link the core would make: it reads back the same configuration.
        val back = OpenFluxLink.parse(single.rawLink)!!
        assertTrue(back.encrypted)
        assertEquals(listOf("direct", "yandex"), back.transports.map { it.type })
        assertEquals("203.0.113.9" to 8445, back.endpoint)

        val several = """
            [
              {"name": "[L] Документы", "transports": [{"type": "mailru", "url": "https://cloud.mail.ru/public/x/y"}]},
              $one,
              {"remarks": "not openflux", "outbounds": []}
            ]
        """.trimIndent()
        val servers = ConfigParser.parseSubscriptionBody(several).servers
        assertEquals(listOf("[L] Документы", "Финляндия"), servers.map { it.remark })

        // Not a configuration: no transports, or a transport without a type.
        assertNull(OpenFluxLink.encode(JsonParser.parseString("{\"name\": \"x\"}").asJsonObject))
        assertNull(
            OpenFluxLink.encode(JsonParser.parseString("{\"transports\": [{\"url\": \"u\"}]}").asJsonObject)
        )
    }

    @Test
    fun jsonGroupsHoldOpenFluxLinksAndConfigurations() {
        val body = """
            {
              "wifi": ["$DIRECT"],
              "lte": [
                {"link": "$DOCS_A", "name": "Москва"},
                {"name": "Казань", "negotiate": true, "secret": "kazan-secret-0123456789abcdef",
                 "transports": [{"type": "yandex", "url": "https://disk.yandex.ru/i/kazan"}]}
              ]
            }
        """.trimIndent()
        val servers = ConfigParser.parseSubscriptionBody(body, subscriptionId = 5).servers
        assertEquals(listOf("Нидерланды", "Москва", "Казань"), servers.map { it.remark })
        assertTrue(servers.all { it.protocol == ProtocolType.OPENFLUX && it.roleFromProvider })
        val merged = ServerClassifier.mergeProviderUpdate(emptyList(), servers, 5)
        assertEquals(
            listOf(ServerRole.NORMAL, ServerRole.BYPASS_WHITELIST, ServerRole.BYPASS_WHITELIST),
            merged.map { it.role }
        )
    }

    @Test
    fun anExitKeepsItsIdentityWhenRenamed() {
        val a = OpenFluxLink.parse(DOCS_A)!!
        val renamed = OpenFluxLink.encode(
            JsonParser.parseString(
                """{"name": "Другое имя", "negotiate": true, "secret": "secret-A-0123456789abcdef",
                   "context": "https://disk.yandex.ru/i/exitA",
                   "transports": [{"type": "yandex", "url": "https://disk.yandex.ru/i/exitA", "priority": 100},
                                  {"type": "mailru", "url": "https://cloud.mail.ru/public/AbCd/exitA", "priority": 50}]}"""
            ).asJsonObject
        )!!
        assertEquals(a.identity, OpenFluxLink.parse(renamed)!!.identity)
        assertFalse(a.identity == OpenFluxLink.parse(DOCS_B)!!.identity)
        assertTrue(a.onlyThroughServices)
        assertEquals(listOf("disk.yandex.ru", "cloud.mail.ru"), a.endpoints.map { it.host })

        // A Mail.ru weblink or a Cups room list is no address: the service's host is used.
        val bare = OpenFluxLink.encode(
            JsonParser.parseString(
                """{"transports": [{"type": "mailru", "url": "AbCdEfGh1/IjKlMnOp2"},
                                   {"type": "cupsonline", "url": "eyJyb29tcyI6W119"}]}"""
            ).asJsonObject
        )!!
        assertEquals(listOf("cloud.mail.ru", "cups.online"), OpenFluxLink.parse(bare)!!.endpoints.map { it.host })
    }

    @Test
    fun linkProblemsReadPlainly() {
        assertEquals(R.string.servers_openflux_link_damaged, OpenFluxRunner.problemRes("damaged"))
        assertEquals(R.string.servers_openflux_short_key, OpenFluxRunner.problemRes("short_secret"))
        // Anything else is told in the helper's own words.
        assertNull(OpenFluxRunner.problemRes(""))
    }

    @Test
    fun carriersAreShownInTheAppsLanguageButStoredAsBefore() {
        assertEquals(R.string.servers_carrier_yandex, OpenFluxLink.carrierNameRes("yandex"))
        assertEquals(R.string.servers_carrier_direct, OpenFluxLink.carrierNameRes("direct"))
        // Brand names are the same in every language.
        assertNull(OpenFluxLink.carrierNameRes("mailru"))
        // The stored name of a nameless link is unchanged.
        assertEquals("напрямую + Яндекс Документы", OpenFluxLink.parse(SESSION)!!.carriersLabel)
    }

    companion object {
        const val SESSION = "openflux://v1/jI5BTsMwEEWvUv21mzhNQouvgliYeACL4kT2RGpUZcEJegn2bEBUoG44weRGqFQq2y6_9L7e2yLYZ4KBvMpeDvI97eQg77KfdrOfz5m8_a0P-ZpeoBDooWVvmWA49qSQqInEMGBKPD-tuS4WZVVfLVfX9q5xdA-Fpg1MmyP4yNwlk-fOp6dssMHRJot97vMzy9GG1LWRE8zNFjx0xz7nIzUMhS76NnoeYAqtFZy3axgsdJnprCjKbGlWVVVjVOfryQKFPq4vKvhX1Hq8HX8DAAD__w"
        const val PLAIN = "openflux://v1/JMkxCsUgDAbgu_zzw-y5yqODoFCppCGJUBHv3qHztxCWxfW2cPB_IaZWMGaWUh_8MKyDcUaoM1FpfqXPkg1qpD03wT72GwAA__8"
        const val STREAM = "openflux://v1/FMm9CoMwFAbQd_nm4N2zlS4d3UuHaIQKibncH6iVvLtkPJwLR6obIubXjIDa8oCabKkiwCQdyk1MEd8X7OTRNe1FHAEuBRFfM9ZItJbmeRo5iRP7UvaVHssz0-_8o3_6HQAA__8"

        /** Two exits over Yandex and Mail.ru documents only, no names. */
        const val DOCS_A = "openflux://v1/jc1NDoIwEIbhu8waKKj40x3xGMZFaUdtxLaZThMI4e5CNG504W6S780zIzi8eraKESRTwgwiakIG-T7yJi-r1XpTb3f7g2q1wQtkoL1j7JfqxhyiFMLYeC8G5Qz2BSVhBfaWmzllUi4GTxxBnkbgIcyv4FXOc6LuHyWQ9WR5AFmV5ZR9nIeyHaUvR3c-mWIZFyaktrNaNO3R_ADrcjpPTw"
        const val DOCS_B = "openflux://v1/jc1NDoIwEIbhu8waKaj40514DOOitKM2VtpMpwmEcHchGje6cDfJ9-aZAVq8eraKESRTwgwiakIG-T4W9aIol6t1tdnu9qrRBi-QgfYtYzdXN-YQpRDGxnveq9Zgl1MSVmBnuZ5SJtXG4IkjyNMA3IfpFbzKaU7k_lECWU-We5BlUYzZx3ko6yh9Odr5ZPJ5nJmQGme1ODRH8wOsivE8PgE"

        /** The node wizard's shape (share.NodeConfig): Volga document first, direct as backup. */
        const val NODE = "openflux://v1/jY5NTsMwEIXvMuvUPyWB1FdBLIw9UAuwo_EENaoicYMuepFuKrGAO7g3qiMkJHZs3_vep7eHaN8QDJRjOV8-ylc5le_yeTlAAxGfEwfLtWYasYGMjpArHJPH1QtOK6XXN213e9dv7KPz-FRXLkXG3UJtmYdspPTJZTHZ6HEnaJToA0svF4euPJONeUjEGcz9HngaljvvP3ztR3r9t2ugkCjwBEYrNTe_Nh8IHf8BOtXU2C5uvelFp0WdiLUyfdt2MD_MVw"

        /** Direct to the exit only. */
        const val DIRECT = "openflux://v1/PYxBDoIwEEXvMmsggEWhVyEuahlNIxbSjomEkLj3DN7BBSYmxjsMN7JuXL789_4IVp0QJPCdXzzzc7nymx_84Xm5QQQWDx0ZRUEhd8YIPGqHFILGONQUH3GI0yxfiWK9KSu10w3uQ6c7S3j5ebYNSE5Z33eOPMh6BBp6_F-EuTGqDZxVeZImeSKELIUoYNpOXw"
    }
}
