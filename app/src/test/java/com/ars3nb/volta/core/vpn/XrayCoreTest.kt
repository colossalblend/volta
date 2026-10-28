package com.ars3nb.volta.core.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class XrayCoreTest {

    @Test
    fun trafficSumsTheProxyAndItsBalancerMembersOnly() {
        val raw = "proxy,uplink,100;proxy,downlink,2000;proxy-0,downlink,50;" +
            "direct,downlink,999;proxyfoo,uplink,7;block,uplink,1;"
        assertEquals(100L to 2050L, XrayCore.parseTraffic(raw, "proxy"))
    }

    @Test
    fun emptyOrBrokenReportsCountAsNothing() {
        assertEquals(0L to 0L, XrayCore.parseTraffic("", "proxy"))
        assertEquals(0L to 0L, XrayCore.parseTraffic("proxy,uplink;garbage", "proxy"))
    }
}
