package com.ars3nb.volta.core.routing

import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class HappGeoDataTest {

    private val happJson = """
        {
          "Name": "Provider rules",
          "GlobalProxy": "true",
          "RemoteDNSType": "DoH",
          "RemoteDNSDomain": "https://cloudflare-dns.com/dns-query",
          "RemoteDNSIP": "1.1.1.1",
          "DomesticDNSType": "DoU",
          "DomesticDNSDomain": "",
          "DomesticDNSIP": "77.88.8.8",
          "Geoipurl": "https://cdn.example/geoip.dat",
          "Geositeurl": "https://cdn.example/geosite.dat",
          "LastUpdated": "1730000000",
          "DnsHosts": {"api.provider.example": "10.0.0.1"},
          "DirectSites": ["geosite:category-ru"],
          "DirectIp": ["geoip:ru", "geoip:private"],
          "BlockSites": ["geosite:category-ads"]
        }
    """.trimIndent()

    @Test
    fun happProfileCarriesGeodataUrlsVersionAndDnsHosts() {
        val parsed = HappRoutingParser.parseHappJson(happJson)
        assertNotNull(parsed)
        parsed!!
        assertEquals("https://cdn.example/geoip.dat", parsed.geoipUrl)
        assertEquals("https://cdn.example/geosite.dat", parsed.geositeUrl)
        assertEquals("1730000000", parsed.geoVersion)
        assertTrue(parsed.dnsHostsJson.contains("api.provider.example"))
        assertEquals("https://cloudflare-dns.com/dns-query", parsed.remoteDns)
        assertEquals("77.88.8.8", parsed.domesticDns)
    }

    @Test
    fun dnsAddressFollowsTheHappType() {
        assertEquals("https://dns.example/dns-query", HappRoutingParser.dnsAddress("DoH", "dns.example", ""))
        assertEquals("https://1.1.1.1/dns-query", HappRoutingParser.dnsAddress("DoH", "", "1.1.1.1"))
        assertEquals("8.8.8.8", HappRoutingParser.dnsAddress("DoU", "", "8.8.8.8"))
        assertEquals("tls://dns.example", HappRoutingParser.dnsAddress("DoT", "dns.example", ""))
    }

    @Test
    fun geoReferencesPointAtTheSubscriptionsOwnFiles() {
        val names = GeoNames(geoip = "geoip_s7.dat", geosite = "geosite_s7.dat")
        assertEquals("ext:geoip_s7.dat:ru", names.rewrite("geoip:ru"))
        assertEquals("ext:geosite_s7.dat:category-ads", names.rewrite("geosite:category-ads"))
        // Bundled ranges and plain domains stay untouched.
        assertEquals("geoip:private", names.rewrite("geoip:private"))
        assertEquals("domain:example.com", names.rewrite("domain:example.com"))
        // Only the half that was downloaded is redirected.
        assertEquals("geosite:ru", GeoNames(geoip = "geoip_s7.dat", geosite = null).rewrite("geosite:ru"))
    }

    @Test
    fun configUsesTheSubscriptionGeodataAndDnsHosts() {
        val parsed = HappRoutingParser.parseHappJson(happJson)!!
        val profile = RoutingProfileEntity(
            name = parsed.name,
            sourceSubscriptionId = 7,
            useProviderRules = true,
            rulesJson = RoutingEngine.serializeRules(parsed.rules),
            dnsHostsJson = parsed.dnsHostsJson
        )
        val config = ConfigBuilder.build(
            server = vless(),
            profile = profile,
            geo = GeoNames("geoip_s7.dat", "geosite_s7.dat")
        )
        assertTrue(config.contains("ext:geoip_s7.dat:ru"))
        assertTrue(config.contains("ext:geosite_s7.dat:category-ads"))
        val routing = JsonParser.parseString(config).asJsonObject.getAsJsonObject("routing").toString()
        assertFalse(routing.contains("\"geoip:ru\""))
        val hosts = JsonParser.parseString(config).asJsonObject
            .getAsJsonObject("dns").getAsJsonObject("hosts")
        assertEquals("10.0.0.1", hosts.get("api.provider.example").asString)
    }

    @Test
    fun jsonSubscriptionRoutingIsUsedAsShipped() {
        val server = vless().copy(
            rawJson = """{"outbounds":[],"routing":{"domainStrategy":"AsIs","rules":[
                {"type":"field","domain":["domain:only-in-json.example"],"outboundTag":"direct"}]}}"""
        )
        val appProfile = RoutingProfileEntity(
            name = "App default",
            rulesJson = RoutingEngine.serializeRules(
                listOf(RoutingRuleDto(name = "app", outbound = RouteOutbound.BLOCK, domains = listOf("domain:app-rule.example")))
            )
        )
        val shipped = ConfigBuilder.build(server, appProfile, preferServerRouting = true)
        assertTrue(shipped.contains("only-in-json.example"))
        assertFalse(shipped.contains("app-rule.example"))

        // An explicit user choice still wins over the JSON's own routing.
        val overridden = ConfigBuilder.build(server, appProfile, preferServerRouting = false)
        assertTrue(overridden.contains("app-rule.example"))
    }

    @Test
    fun geodataIsStaleWhenTheProviderBumpsLastUpdated() {
        val dir = Files.createTempDirectory("geo").toFile()
        val manager = GeoDataManager(dir)
        val files = manager.filesFor(3)
        files.geoip.writeBytes(ByteArray(1024))
        files.geosite.writeBytes(ByteArray(1024))
        val previous = RoutingProfileEntity(
            name = "p",
            geoipUrl = "https://x/geoip.dat",
            geositeUrl = "https://x/geosite.dat",
            geoVersion = "1",
            geoUpdatedAt = System.currentTimeMillis()
        )
        assertFalse(manager.isStale(3, previous, previous))
        assertTrue(manager.isStale(3, previous.copy(geoVersion = "2"), previous))
        assertTrue(manager.isStale(3, previous.copy(geoipUrl = "https://y/geoip.dat"), previous))
        assertTrue(manager.isStale(3, previous, previous.copy(geoUpdatedAt = 1)))
        assertEquals(GeoNames("geoip_s3.dat", "geosite_s3.dat"), manager.installed(3))
        manager.delete(3)
        assertEquals(null, manager.installed(3))
        dir.deleteRecursively()
    }

    private fun vless() = ServerEntity(
        remark = "Test",
        protocol = ProtocolType.VLESS,
        address = "node.example",
        port = 443,
        uuid = "11111111-1111-1111-1111-111111111111",
        security = "tls"
    )
}
