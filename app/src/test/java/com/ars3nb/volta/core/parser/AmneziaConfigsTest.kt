package com.ars3nb.volta.core.parser

import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.ProtocolType
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmneziaConfigsTest {

    private val awg3Conf = """
        # exported by AmneziaVPN
        [Interface]
        PrivateKey = $PRIVATE
        Address = 10.8.1.3/32
        DNS = 1.1.1.1
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 15
        S2 = 18
        S3 = 20
        S4 = 23
        H1 = 1000-2000
        H2 = 3000-4000
        H3 = 5000-6000
        H4 = 7000-8000
        I1 = <r 32><b 0x0102>
        HeaderProtectionKey = $PRIVATE

        [Peer]
        PublicKey = $PUBLIC
        Endpoint = 203.0.113.7:51820
        AllowedIPs = 0.0.0.0/0, ::/0
        PersistentKeepalive = 25
    """.trimIndent()

    @Test
    fun confBecomesOneAmneziaServer() {
        val result = ConfigParser.parseSubscriptionBody(awg3Conf)

        assertEquals(1, result.servers.size)
        val server = result.servers.single()
        assertEquals(ProtocolType.AMNEZIAWG, server.protocol)
        assertEquals("203.0.113.7", server.address)
        assertEquals(51820, server.port)
        assertEquals("awg3", server.network)
        assertEquals(PUBLIC, server.pbk)
        // The helper gets the file as it is, comments and all.
        assertTrue(server.rawLink.contains("HeaderProtectionKey"))
        assertEquals(ProxyHelper.Spec.AWG, ConfigBuilder.helperSpec(server)?.kind)
    }

    @Test
    fun generationsAreToldApart() {
        fun generationOf(extra: String): String? = AmneziaConfigs.parseConf(
            "[Interface]\nPrivateKey = $PRIVATE\nAddress = 10.0.0.2/32\n$extra\n" +
                "[Peer]\nPublicKey = $PUBLIC\nEndpoint = [2001:db8::1]:443\n"
        )?.network

        assertEquals("wg", generationOf(""))
        assertEquals("awg1", generationOf("Jc = 3\nH1 = 5"))
        assertEquals("awg1.5", generationOf("Jc = 3\nI1 = <b 0x01>"))
        assertEquals("awg2", generationOf("Jc = 3\nH1 = 10-20"))
        assertEquals("awg2", generationOf("S3 = 10"))
        assertEquals("awg3", generationOf("RandomTrailers = on"))
        assertEquals(
            "2001:db8::1",
            AmneziaConfigs.parseConf(
                "[Interface]\nPrivateKey = $PRIVATE\n[Peer]\nPublicKey = $PUBLIC\nEndpoint = [2001:db8::1]:443\n"
            )?.address
        )
    }

    @Test
    fun confWithoutEndpointOrKeysIsRefused() {
        assertNull(AmneziaConfigs.parseConf("[Interface]\nPrivateKey = $PRIVATE\n[Peer]\nPublicKey = $PUBLIC\n"))
        assertNull(AmneziaConfigs.parseConf("[Interface]\n[Peer]\nEndpoint = 1.2.3.4:1\n"))
    }

    @Test
    fun amneziaVpnKeyUnpacksItsAwgContainer() {
        val conf = awg3Conf.replace("DNS = 1.1.1.1", "DNS = \$PRIMARY_DNS, \$SECONDARY_DNS")
        val lastConfig = JsonObject().apply {
            addProperty("config", conf)
            addProperty("hostName", "203.0.113.7")
        }
        val root = JsonObject().apply {
            add("containers", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("container", "amnezia-awg")
                    add("awg", JsonObject().apply {
                        addProperty("last_config", lastConfig.toString())
                        addProperty("port", "51820")
                    })
                })
            })
            addProperty("defaultContainer", "amnezia-awg")
            addProperty("description", "Мой сервер")
            addProperty("dns1", "9.9.9.9")
            addProperty("dns2", "149.112.112.112")
            addProperty("hostName", "203.0.113.7")
        }
        val key = "vpn://" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(qCompress(root.toString().toByteArray()))

        val server = ConfigParser.parseShareLink(key)
        assertNotNull(server)
        server!!
        assertEquals(ProtocolType.AMNEZIAWG, server.protocol)
        assertEquals("Мой сервер", server.remark)
        assertTrue(server.rawLink.contains("DNS = 9.9.9.9, 149.112.112.112"))

        // An uncompressed key, inside a subscription body.
        val plain = "vpn://" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(root.toString().toByteArray())
        assertEquals(1, ConfigParser.parseSubscriptionBody("#profile-title: x\n$plain").servers.size)
    }

    @Test
    fun hysteriaV1LinkGoesToTheHelper() {
        val server = ConfigParser.parseShareLink(
            "hysteria://hy.example:20000-30000?protocol=udp&auth=pw&peer=cdn.example" +
                "&insecure=1&upmbps=20&downmbps=100&obfs=xplus&obfsParam=salt#Хи"
        )
        assertNotNull(server)
        server!!
        assertEquals(ProtocolType.HYSTERIA, server.protocol)
        assertEquals("hy.example", server.address)
        assertEquals(20000, server.port)
        assertEquals("pw", server.uuid)
        assertEquals("cdn.example", server.sni)
        assertEquals("Хи", server.remark)
        assertEquals("20000-30000", ConfigParser.extra(server.extraJson, "mport"))
        assertEquals(ProxyHelper.Spec.HYSTERIA, ConfigBuilder.helperSpec(server)?.kind)

        val outbound = proxyOutbound(ConfigBuilder.build(server, null))
        assertEquals("socks", outbound.get("protocol").asString)
        val target = outbound.getAsJsonObject("settings").getAsJsonArray("servers")[0].asJsonObject
        assertEquals("127.0.0.1", target.get("address").asString)
        assertEquals(ProxyHelper.PORT, target.get("port").asInt)

        val faketcp = ConfigParser.parseShareLink("hysteria://hy.example:443?protocol=faketcp&auth=pw#F")
        assertNotNull(ConfigBuilder.dialProblem(faketcp!!))
    }

    @Test
    fun hysteria2PortRangeStaysInTheCore() {
        val server = ConfigParser.parseShareLink("hysteria2://pw@hy.example:20000-30000/?sni=a.example#H")
        assertNotNull(server)
        server!!
        assertEquals(ProtocolType.HYSTERIA2, server.protocol)
        assertEquals("hy.example", server.address)
        assertEquals(20000, server.port)
        assertNull(ConfigBuilder.helperSpec(server))
        val hop = proxyOutbound(ConfigBuilder.build(server, null))
            .getAsJsonObject("streamSettings").getAsJsonObject("hysteriaSettings")
            .getAsJsonObject("udphop")
        assertEquals("20000-30000", hop.get("port").asString)
    }

    @Test
    fun wireGuardLinkWithAmneziaFieldsBecomesAmneziaWg() {
        val server = ConfigParser.parseShareLink(
            "wireguard://${PRIVATE.replace("=", "%3D")}@wg.example:51820?publickey=${PUBLIC.replace("=", "%3D")}" +
                "&address=10.7.0.2%2F32&jc=4&jmin=40&jmax=70&s1=15&s2=18&h1=1&h2=2&h3=3&h4=4#AWG"
        )
        assertNotNull(server)
        server!!
        assertEquals(ProtocolType.AMNEZIAWG, server.protocol)
        assertEquals("AWG", server.remark)
        assertEquals("awg1", server.network)
        assertTrue(server.rawLink.contains("Jc = 4"))
        assertTrue(server.rawLink.contains("Endpoint = wg.example:51820"))
        assertTrue(server.rawLink.contains("PublicKey = $PUBLIC"))

        // Without them it stays the core's own WireGuard.
        val plain = ConfigParser.parseShareLink(
            "wireguard://${PRIVATE.replace("=", "%3D")}@wg.example:51820?publickey=${PUBLIC.replace("=", "%3D")}" +
                "&address=10.7.0.2%2F32#WG"
        )
        assertEquals(ProtocolType.WIREGUARD, plain?.protocol)
    }

    private fun proxyOutbound(json: String): JsonObject =
        JsonParser.parseString(json).asJsonObject.getAsJsonArray("outbounds")
            .map { it.asJsonObject }
            .first { it.get("tag").asString == ConfigBuilder.PROXY_TAG }

    /** Qt's qCompress, as AmneziaVPN writes its keys. */
    private fun qCompress(data: ByteArray): ByteArray {
        val deflater = Deflater(8)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(data.size).array())
        val buf = ByteArray(4096)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        return out.toByteArray()
    }

    private companion object {
        const val PRIVATE = "KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QEFCQ0RFRkc="
        const val PUBLIC = "UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8="
    }
}
