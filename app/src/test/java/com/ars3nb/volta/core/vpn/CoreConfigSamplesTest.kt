package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.routing.RoutingEngine
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Configs for every protocol and transport Volta dials, built from share links the way
 * the app builds them. CI hands them to the Xray core it is about to ship
 * (`xray run -test`), so a core update that no longer accepts Volta's configs is caught
 * at build time and the previous core is kept. VOLTA_CONFIG_DUMP names the directory
 * to write them to.
 */
class CoreConfigSamplesTest {

    @Test
    fun everySampleIsAConfigWithAnOutbound() {
        val dump = System.getenv("VOLTA_CONFIG_DUMP")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.apply { mkdirs() }
        samples().forEach { (name, json) ->
            val root = JsonParser.parseString(json).asJsonObject
            assertTrue(name, root.getAsJsonArray("outbounds").size() > 0)
            dump?.let { File(it, "$name.json").writeText(json) }
        }
    }

    private fun samples(): List<Pair<String, String>> {
        val servers = LINKS.mapValues { (name, link) ->
            requireNotNull(ConfigParser.parseShareLink(link)) { "$name did not parse" }
        }
        val ws = servers.getValue("vless-tls-ws")
        val out = servers.map { (name, server) -> name to ConfigBuilder.build(server, null) }
            .toMutableList()
        // The built-in routing sets, so the geosite/geoip names they use are checked too.
        RoutingEngine.builtInProfiles().forEachIndexed { i, profile ->
            out += "routing-$i" to ConfigBuilder.build(ws, profile)
        }
        out += "local-dns-off" to ConfigBuilder.build(ws, null, localDns = false)
        // App-wide filters on top of a profile made of every ready-made site list.
        val presets = com.ars3nb.volta.data.model.RoutingProfileEntity(
            name = "presets",
            rulesJson = RoutingEngine.serializeRules(
                com.ars3nb.volta.core.routing.Presets.sites.map { it.toRule() }
            )
        )
        out += "filters" to ConfigBuilder.build(
            ws,
            presets,
            localDns = false,
            adBlock = com.ars3nb.volta.data.model.AdBlockMode.FAMILY,
            blockTorrents = true
        )
        out += "ads-standard" to ConfigBuilder.build(
            ws,
            null,
            adBlock = com.ars3nb.volta.data.model.AdBlockMode.STANDARD
        )
        // Strict: both tiers of Volta's list in the routing and in the DNS hosts, over
        // the Russian set and a provider's own hosts, so the core reads all of it.
        out += "ads-strict" to ConfigBuilder.build(
            ws,
            RoutingEngine.builtInProfiles().last().copy(
                dnsHostsJson = """{"provider.example":"10.0.0.8","domain:ads.vk.com":"10.0.0.7"}"""
            ),
            adBlock = com.ars3nb.volta.data.model.AdBlockMode.STRICT
        )
        val pool = servers.values.take(3).mapIndexed { i, s -> s.copy(id = i + 1L) }
        out += "balancer" to ConfigBuilder.build(pool.first(), null, balancerPool = pool)
        out += "probe" to ConfigBuilder.buildProbe(servers.getValue("vless-reality-vision"))
        return out
    }

    private companion object {
        const val UUID = "11111111-2222-4333-8444-555555555555"

        // Well-formed keys: the core checks their length, not whose they are.
        const val REALITY_KEY = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
        const val WG_PRIVATE = "KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4%2FQEFCQ0RFRkc%3D"
        const val WG_PUBLIC = "UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8%3D"

        fun b64(text: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

        val VMESS = b64(
            """{"v":"2","ps":"vm","add":"node.example","port":"443","id":"$UUID","aid":"0",""" +
                """"scy":"auto","net":"ws","type":"none","host":"cdn.example","path":"/vm",""" +
                """"tls":"tls","sni":"cdn.example","fp":"chrome"}"""
        )

        val LINKS = linkedMapOf(
            "vless-reality-vision" to "vless://$UUID@node.example:443?security=reality&encryption=none" +
                "&pbk=$REALITY_KEY&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.microsoft.com&sid=ab12#R",
            "vless-tls-ws" to "vless://$UUID@node.example:443?security=tls&encryption=none&type=ws" +
                "&path=%2Fws&host=cdn.example&sni=cdn.example&fp=chrome&alpn=h2%2Chttp%2F1.1#WS",
            "vless-reality-xhttp" to "vless://$UUID@node.example:443?security=reality&encryption=none" +
                "&pbk=$REALITY_KEY&fp=chrome&type=xhttp&mode=auto&path=%2Fx&sni=www.microsoft.com&sid=ab12#X",
            "vless-tls-grpc" to "vless://$UUID@node.example:443?security=tls&encryption=none&type=grpc" +
                "&serviceName=svc&sni=node.example#G",
            "vless-httpupgrade" to "vless://$UUID@node.example:80?security=none&encryption=none" +
                "&type=httpupgrade&path=%2Fup&host=cdn.example#U",
            "vless-insecure-tls" to "vless://$UUID@node.example:443?security=tls&encryption=none" +
                "&type=tcp&sni=node.example&allowInsecure=1#I",
            "vless-pinned-cert" to "vless://$UUID@node.example:443?security=tls&encryption=none" +
                "&type=tcp&sni=node.example&allowInsecure=1&pcs=${"ab".repeat(32)}#P",
            "hysteria2-insecure" to "hysteria2://s3cret@hy.example:8443/?sni=cdn.example&insecure=1#HYI",
            "vmess-ws-tls" to "vmess://$VMESS",
            "trojan-tls" to "trojan://pass@node.example:443?security=tls&type=tcp&sni=node.example#T",
            "shadowsocks" to "ss://${b64("aes-256-gcm:password")}@ss.example:8388#SS",
            "shadowsocks-2022" to "ss://${b64("2022-blake3-aes-128-gcm:BQYHCAkKCwwNDg8QERITFA==")}" +
                "@ss.example:443#SS22",
            "hysteria2" to "hysteria2://s3cret@hy.example:8443/?sni=cdn.example&obfs=salamander" +
                "&obfs-password=abc&upmbps=50&downmbps=200#HY",
            "wireguard" to "wireguard://$WG_PRIVATE@wg.example:51820?publickey=$WG_PUBLIC" +
                "&address=10.7.0.2%2F32&mtu=1420&reserved=1,2,3#WG",
            "socks" to "socks://${b64("user:pass")}@sox.example:1080#SOCKS",
            "hysteria2-port-hopping" to "hysteria2://s3cret@hy.example:20000-30000/?sni=cdn.example#HOP",
            // Run by the voltaproxy helper: the core only hands traffic to its SOCKS port.
            "hysteria-v1" to "hysteria://hy.example:443?protocol=udp&auth=pw&peer=cdn.example#H1",
            "amneziawg-link" to "wireguard://$WG_PRIVATE@wg.example:51820?publickey=$WG_PUBLIC" +
                "&address=10.7.0.2%2F32&jc=4&jmin=40&jmax=70&s1=15&s2=18&h1=1&h2=2&h3=3&h4=4#AWG",
            "openflux" to com.ars3nb.volta.core.openflux.OpenFluxLinkTest.SESSION
        )
    }
}
