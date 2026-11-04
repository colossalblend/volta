package com.ars3nb.volta.core.parser

import com.ars3nb.volta.data.model.ProtocolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ConfigParserTest {

    @Test
    fun parsesBase64SubscriptionWithoutLineBreaks() {
        val links = listOf(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443?security=tls#One",
            "vless://22222222-2222-2222-2222-222222222222@example.org:443?security=tls#Two"
        ).joinToString("\n")
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(links.toByteArray())

        val result = ConfigParser.parseSubscriptionBody(encoded, 7)

        assertEquals(2, result.servers.size)
        assertTrue(result.servers.all { it.subscriptionId == 7L })
    }

    @Test
    fun extractsLinksFromHtmlAndOneLineResponses() {
        val body = "<p>vless://id@one.example:443#One</p> vmess://invalid " +
            "vless://id@two.example:443#Two"

        val result = ConfigParser.parseSubscriptionBody(body)

        assertEquals(listOf("one.example", "two.example"), result.servers.map { it.address })
    }

    @Test
    fun parsesProviderBase64BodyWithHttpMetadataHeaders() {
        val links = listOf(
            "vless://11111111-1111-1111-1111-111111111111@nl.example:443?security=reality&sni=google.com&pbk=KEY#NL",
            "vless://22222222-2222-2222-2222-222222222222@de.example:443?security=tls#DE",
            "ss://YWVzLTI1Ni1nY206cGFzcw@fi.example:8388#FI"
        ).joinToString("\n")
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(links.toByteArray())
        val body = buildString {
            appendLine("#profile-title: base64:TmVidWxhR3VhcmQ=")
            appendLine("#subscription-userinfo: upload=0; download=10; total=0; expire=1792690181")
            appendLine("#support-url: https://t.me/example")
            appendLine("#profile-web-page-url: https://sub.example.com/token")
            append(encoded)
        }

        val result = ConfigParser.parseSubscriptionBody(body, 3)

        assertEquals("NebulaGuard", result.title)
        assertEquals(3, result.servers.size)
        assertEquals(
            listOf("nl.example", "de.example", "fi.example"),
            result.servers.map { it.address }
        )
        assertTrue(result.servers.all { it.subscriptionId == 3L })
    }

    @Test
    fun parsesXrayJsonArrayOfConfigs() {
        val body = """
            [
              {
                "remarks": "One",
                "outbounds": [
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{
                        "address": "one.example",
                        "port": 443,
                        "users": [{"id": "11111111-1111-1111-1111-111111111111", "encryption": "none"}]
                      }]
                    },
                    "streamSettings": {"network": "tcp", "security": "tls"}
                  },
                  {"tag": "direct", "protocol": "freedom"}
                ]
              },
              {
                "outbounds": [
                  {
                    "tag": "proxy",
                    "protocol": "vless",
                    "settings": {
                      "vnext": [{
                        "address": "two.example",
                        "port": 8443,
                        "users": [{"id": "22222222-2222-2222-2222-222222222222", "encryption": "none"}]
                      }]
                    }
                  }
                ]
              }
            ]
        """.trimIndent()

        val result = ConfigParser.parseSubscriptionBody(body)

        assertEquals(listOf("one.example", "two.example"), result.servers.map { it.address })
        assertEquals(listOf(443, 8443), result.servers.map { it.port })
    }

    @Test
    fun extractsNestedRoutingProfileWithoutRegex() {
        val body = """
            #routing-profile: {"Name":"Mix","rules":[{"type":"field","domain":["geosite:category-ads"]}]}
            vless://11111111-1111-1111-1111-111111111111@nl.example:443?security=tls#NL
        """.trimIndent()

        val result = ConfigParser.parseSubscriptionBody(body)

        assertEquals(1, result.servers.size)
        assertTrue(result.routingJson.contains("geosite:category-ads"))
        assertTrue(result.routingJson.contains("\"Name\":\"Mix\""))
    }

    @Test
    fun parsesFullyEncodedShadowsocksSip002Link() {
        val value = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-256-gcm:password@ss.example:8388".toByteArray())

        val server = ConfigParser.parseShareLink("ss://$value#Fast")

        requireNotNull(server)
        assertEquals("ss.example", server.address)
        assertEquals(8388, server.port)
        assertEquals("aes-256-gcm", server.encryption)
        assertEquals("password", server.uuid)
    }

    @Test
    fun keepsShadowsocks2022KeyIntact() {
        // The ss2022 password is itself base64 and must not be decoded a second time.
        val link = "ss://MjAyMi1ibGFrZTMtYWVzLTI1Ni1nY206MkIzUlRSSlN2SzZQTjhkPQ==@ss.example:443#SS2022"

        val server = ConfigParser.parseShareLink(link)

        requireNotNull(server)
        assertEquals("2022-blake3-aes-256-gcm", server.encryption)
        assertEquals("2B3RTRJSvK6PN8d=", server.uuid)
    }

    @Test
    fun parsesHysteria2WithObfsAndBandwidth() {
        val link = "hysteria2://s3cret@hy.example:8443/?sni=cdn.example&obfs=salamander" +
            "&obfs-password=abc&upmbps=50&downmbps=200&insecure=1#HY"

        val server = ConfigParser.parseShareLink(link)

        requireNotNull(server)
        assertEquals(ProtocolType.HYSTERIA2, server.protocol)
        assertEquals("hy.example", server.address)
        assertEquals(8443, server.port)
        assertEquals("s3cret", server.uuid)
        assertEquals("cdn.example", server.sni)
        assertEquals("salamander", ConfigParser.extra(server.extraJson, "obfs"))
        assertEquals("abc", ConfigParser.extra(server.extraJson, "obfs-password"))
        assertEquals("50", ConfigParser.extra(server.extraJson, "upmbps"))
    }

    @Test
    fun parsesSocksAndWireGuardLinks() {
        val socks = ConfigParser.parseShareLink("socks://dXNlcjpwYXNz@sox.example:1080#SOCKS")
        requireNotNull(socks)
        assertEquals(ProtocolType.SOCKS, socks.protocol)
        assertEquals(1080, socks.port)
        assertEquals("user", ConfigParser.extra(socks.extraJson, "user"))
        assertEquals("pass", socks.uuid)

        val wg = ConfigParser.parseShareLink(
            "wireguard://PRIVKEY%3D@wg.example:51820?publickey=PUBKEY%3D" +
                "&address=10.7.0.2/32&mtu=1420&reserved=1,2,3#WG"
        )
        requireNotNull(wg)
        assertEquals(ProtocolType.WIREGUARD, wg.protocol)
        assertEquals("wg.example", wg.address)
        assertEquals(51820, wg.port)
        assertEquals("PRIVKEY=", wg.uuid)
        assertEquals("PUBKEY=", wg.pbk)
        assertEquals("10.7.0.2/32", ConfigParser.extra(wg.extraJson, "address"))
    }

    @Test
    fun parsesVlessWithModernTransportParameters() {
        val server = ConfigParser.parseShareLink(
            "vless://11111111-1111-1111-1111-111111111111@node.example:443" +
                "?type=xhttp&mode=stream-one&path=%2Fdl&host=cdn.example&security=reality" +
                "&pbk=PUB&sid=ab&fp=chrome&flow=xtls-rprx-vision#Node"
        )

        requireNotNull(server)
        assertEquals("xhttp", server.network)
        assertEquals("reality", server.security)
        assertEquals("/dl", server.path)
        assertEquals("cdn.example", server.host)
        assertEquals("PUB", server.pbk)
        assertEquals("xtls-rprx-vision", server.flow)
        assertEquals("stream-one", ConfigParser.extra(server.extraJson, "mode"))
    }

    @Test
    fun normalisesLegacyTransportSpellings() {
        val raw = ConfigParser.parseShareLink(
            "vless://id@a.example:443?type=raw&security=none#A"
        )
        requireNotNull(raw)
        assertEquals("tcp", raw.network)
        assertEquals("none", raw.security)

        val split = ConfigParser.parseShareLink(
            "vless://id@b.example:443?type=splithttp&security=1#B"
        )
        requireNotNull(split)
        assertEquals("xhttp", split.network)
        assertEquals("tls", split.security)
    }

    @Test
    fun keepsIpv6LiteralHostsIntact() {
        val server = ConfigParser.parseShareLink(
            "vless://id@[2001:db8::1]:8443?security=tls#v6"
        )

        requireNotNull(server)
        assertEquals("2001:db8::1", server.address)
        assertEquals(8443, server.port)
    }

    @Test
    fun reportsProtocolsTheCoreCannotDial() {
        val body = """
            tuic://uuid:pass@tuic.example:443#TUIC
            ssr://abcdef
            vless://id@ok.example:443#OK
        """.trimIndent()

        val result = ConfigParser.parseSubscriptionBody(body)

        assertEquals(1, result.servers.size)
        assertTrue(result.skipped.contains("TUIC"))
        assertTrue(result.skipped.contains("ShadowsocksR"))
        // hysteria2:// must not be reported as unsupported Hysteria v1
        assertFalse(
            ConfigParser.unsupportedProtocols("hysteria2://a@b:443").contains("Hysteria v1")
        )
    }

    @Test
    fun rejectsLinksWithoutCredentialsOrHost() {
        assertNull(ConfigParser.parseShareLink("vless://@:443#broken"))
        assertNull(ConfigParser.parseShareLink("nonsense://whatever"))
    }
}
