package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.parser.AmneziaConfigs
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.desktop.core.TrafficMeter
import com.ars3nb.volta.desktop.core.WindowsCompat
import com.ars3nb.volta.desktop.data.AppSettings
import com.ars3nb.volta.desktop.data.Store
import com.ars3nb.volta.desktop.data.StoreData
import com.ars3nb.volta.desktop.data.SubscriptionFetcher
import com.ars3nb.volta.desktop.i18n.I18n
import com.ars3nb.volta.desktop.i18n.Lang
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Base64
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsCoreTest {

    @Test
    fun trafficCountersAreSummedOverEveryProxyOutbound() {
        val json = """
            {"cmdline":["xray"],"stats":{"inbound":{"socks":{"downlink":5,"uplink":6}},
             "outbound":{"proxy-0":{"downlink":1000,"uplink":200},"proxy-1":{"downlink":24,"uplink":0},
             "direct":{"downlink":999999,"uplink":999999}},"user":{}},"observatory":null}
        """.trimIndent()
        val totals = TrafficMeter.parse(json)!!
        assertEquals(1024L, totals.downlink)
        assertEquals(200L, totals.uplink)
    }

    @Test
    fun aPlainWireGuardConfOpensInTheCoreOnWindows() {
        val conf = "[Interface]\nPrivateKey = KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QEFCQ0RFRkc=\nAddress = 10.7.0.2/32\n" +
            "MTU = 1280\n\n[Peer]\nPublicKey = UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8=\n" +
            "AllowedIPs = 0.0.0.0/0\nEndpoint = wg.example:51820\n"
        val parsed = AmneziaConfigs.parseConf(conf)!!
        val server = WindowsCompat.adapt(parsed)
        assertEquals(ProtocolType.WIREGUARD, server.protocol)
        assertTrue(ConfigBuilder.canDial(server))
        val out = JsonParser.parseString(ConfigBuilder.build(server, null)).asJsonObject
            .getAsJsonArray("outbounds")[0].asJsonObject
        assertEquals("wireguard", out.get("protocol").asString)
        assertEquals(1280, out.getAsJsonObject("settings").get("mtu").asInt)
    }

    @Test
    fun amneziaProperStaysRefusedWithAPlainReason() {
        I18n.use("ru")
        val conf = "[Interface]\nPrivateKey = KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QEFCQ0RFRkc=\nJc = 4\nJmin = 40\nJmax = 70\n" +
            "[Peer]\nPublicKey = UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8=\nEndpoint = wg.example:51820\n"
        val server = WindowsCompat.adapt(AmneziaConfigs.parseConf(conf)!!)
        assertEquals(ProtocolType.AMNEZIAWG, server.protocol)
        assertTrue(WindowsCompat.needsAndroidHelper(server))
        assertTrue(ConfigBuilder.dialProblem(server)!!.contains("Windows"))
    }

    @Test
    fun theStoreSurvivesARestartAndAnOldFile() {
        val dir = Files.createTempDirectory("volta-store").toFile()
        val file = File(dir, "volta.json")
        val store = Store(file)
        store.update {
            it.copy(
                servers = listOf(ServerEntity(id = 5, remark = "DE", address = "de.example", port = 443)),
                settings = AppSettings(language = "en")
            )
        }
        store.flush()
        val again = Store(file)
        assertEquals("de.example", again.value.servers.single().address)
        assertEquals("en", again.value.settings.language)
        assertTrue(again.value.nextId > 5)

        // A file from an older Volta, missing most fields, still loads with defaults.
        file.writeText("""{"servers":[{"id":7,"remark":"X","address":"x.example","port":1}],"settings":{"language":"ru"}}""")
        val old = Store(file)
        val server = old.value.servers.single()
        assertEquals("", server.uuid)
        assertEquals(-1L, server.latencyMs)
        assertEquals(true, old.value.settings.systemProxy)
        assertEquals(StoreData().settings.connectMode, old.value.settings.connectMode)
    }

    @Test
    fun theProvidersHeadersReachTheParser() {
        val links = "vless://11111111-2222-4333-8444-555555555555@nl.example:443?security=tls&type=tcp#NL\n" +
            "trojan://pw@de.example:443?security=tls#DE"
        val body = Base64.getEncoder().encodeToString(links.toByteArray())
        var seenAgent = ""
        var seenHwid = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/sub") { ex ->
            seenAgent = ex.requestHeaders.getFirst("User-Agent").orEmpty()
            seenHwid = ex.requestHeaders.getFirst("x-hwid").orEmpty()
            ex.responseHeaders.add("Profile-Title", "base64:" + Base64.getEncoder().encodeToString("Мой VPN".toByteArray()))
            ex.responseHeaders.add("Subscription-Userinfo", "upload=10; download=20; total=1000; expire=1893456000")
            ex.responseHeaders.add("Support-Url", "https://t.me/support")
            ex.responseHeaders.add("Profile-Update-Interval", "6")
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/sub"
            val fetched = SubscriptionFetcher.fetch(url, "", "1.1.0", "abc123")
            val parsed = ConfigParser.parseSubscriptionBody(fetched.body, 1)
            assertEquals(SubscriptionFetcher.DEFAULT_USER_AGENT, seenAgent)
            assertEquals("abc123", seenHwid)
            assertEquals("Мой VPN", parsed.title)
            assertEquals(30L, parsed.trafficUsed)
            assertEquals(1000L, parsed.trafficTotal)
            assertEquals(1893456000000L, parsed.expireAt)
            assertEquals("https://t.me/support", parsed.supportUrl)
            assertEquals(6, parsed.updateIntervalHours)
            assertEquals(listOf("nl.example", "de.example"), parsed.servers.map { it.address })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aMissingSubscriptionSaysSoInPlainWords() {
        I18n.use("ru")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex -> ex.sendResponseHeaders(404, -1); ex.close() }
        server.start()
        try {
            val e = runCatching {
                SubscriptionFetcher.fetch("http://127.0.0.1:${server.address.port}/x", "", "1", null)
            }.exceptionOrNull()
            assertNotNull(e)
            assertTrue(e!!.message!!.contains("404"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun theSystemLanguageIsFollowed() {
        assertEquals(Lang.RU, Lang.fromLocale(Locale.forLanguageTag("ru-RU")))
        assertEquals(Lang.UK, Lang.fromLocale(Locale.forLanguageTag("uk-UA")))
        assertEquals(Lang.RU, Lang.fromLocale(Locale.forLanguageTag("be-BY")))
        assertEquals(Lang.EN, Lang.fromLocale(Locale.forLanguageTag("fr-FR")))
        assertEquals(Lang.DE, Lang.resolve("de"))
        assertFalse(Lang.FA.strings.navHome.isBlank())
        // Every language answers every plural without crashing.
        Lang.entries.forEach { lang ->
            listOf(0, 1, 2, 5, 11, 21, 101).forEach { n ->
                assertTrue(lang.strings.servers(n).contains(n.toString()))
                assertTrue(lang.strings.daysLeft(n).isNotBlank())
            }
        }
    }
}
