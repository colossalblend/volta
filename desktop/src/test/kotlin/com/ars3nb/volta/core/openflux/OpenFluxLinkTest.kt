package com.ars3nb.volta.core.openflux

import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ProtocolType
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
    fun windowsSaysOpenFluxIsNotAvailable() {
        val server = ConfigParser.parseShareLink(SESSION)!!
        assertNotNull(ConfigBuilder.dialProblem(server))
        assertFalse(ConfigBuilder.canDial(server))
    }

    @Test
    fun autoModesLeaveOpenFluxAlone() {
        val openFlux = ConfigParser.parseShareLink(SESSION)!!.copy(id = 1)
        val vless = ConfigParser.parseShareLink(
            "vless://11111111-2222-4333-8444-555555555555@h:443?security=tls&type=tcp#V"
        )!!.copy(id = 2)
        BalancerScope.entries.forEach { scope ->
            assertFalse(AutoBalancer.poolFor(listOf(openFlux, vless), scope).any { it.id == 1L })
        }
    }

    companion object {
        const val SESSION = "openflux://v1/jI5BTsMwEEWvUv21mzhNQouvgliYeACL4kT2RGpUZcEJegn2bEBUoG44weRGqFQq2y6_9L7e2yLYZ4KBvMpeDvI97eQg77KfdrOfz5m8_a0P-ZpeoBDooWVvmWA49qSQqInEMGBKPD-tuS4WZVVfLVfX9q5xdA-Fpg1MmyP4yNwlk-fOp6dssMHRJot97vMzy9GG1LWRE8zNFjx0xz7nIzUMhS76NnoeYAqtFZy3axgsdJnprCjKbGlWVVVjVOfryQKFPq4vKvhX1Hq8HX8DAAD__w"
        const val PLAIN = "openflux://v1/JMkxCsUgDAbgu_zzw-y5yqODoFCppCGJUBHv3qHztxCWxfW2cPB_IaZWMGaWUh_8MKyDcUaoM1FpfqXPkg1qpD03wT72GwAA__8"
        const val STREAM = "openflux://v1/FMm9CoMwFAbQd_nm4N2zlS4d3UuHaIQKibncH6iVvLtkPJwLR6obIubXjIDa8oCabKkiwCQdyk1MEd8X7OTRNe1FHAEuBRFfM9ZItJbmeRo5iRP7UvaVHssz0-_8o3_6HQAA__8"
    }
}
