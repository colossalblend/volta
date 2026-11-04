package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RouteOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetsTest {

    @Test
    fun typedEntriesBecomeDomainsOrIps() {
        assertEquals(Presets.Entry.Domain("domain:youtube.com"), Presets.parseEntry("YouTube.com"))
        assertEquals(
            Presets.Entry.Domain("domain:www.example.org"),
            Presets.parseEntry("https://www.example.org/watch?v=1")
        )
        assertEquals(Presets.Entry.Domain("domain:example.org"), Presets.parseEntry("*.example.org"))
        assertEquals(Presets.Entry.Domain("domain:example.org"), Presets.parseEntry("example.org:8443"))
        assertEquals(Presets.Entry.Ip("8.8.8.0/24"), Presets.parseEntry(" 8.8.8.0/24 "))
        assertEquals(Presets.Entry.Ip("1.2.3.4"), Presets.parseEntry("1.2.3.4:80"))
        assertEquals(Presets.Entry.Ip("2001:db8::/32"), Presets.parseEntry("2001:db8::/32"))
        assertEquals(Presets.Entry.Ip("geoip:ru"), Presets.parseEntry("geoip:ru"))
        assertEquals(Presets.Entry.Domain("geosite:netflix"), Presets.parseEntry("geosite:netflix"))
        assertNull(Presets.parseEntry("   "))
        assertNull(Presets.parseEntry("localhost"))
    }

    @Test
    fun sitePresetsAreSelfContained() {
        // No geo names: a subscription's own geodata might not carry the category.
        Presets.sites.forEach { preset ->
            val rule = preset.toRule()
            assertTrue(preset.id, rule.domains.isNotEmpty())
            assertTrue(preset.id, rule.domains.all { it.startsWith("domain:") && ' ' !in it })
            assertTrue(preset.id, rule.ips.none { it.startsWith("geoip:") })
            assertEquals("user", rule.source)
        }
        assertEquals(Presets.sites.size, Presets.sites.map { it.id }.toSet().size)
        assertEquals(RouteOutbound.DIRECT, Presets.sites.first { it.id == "ru" }.suggested)
    }
}
