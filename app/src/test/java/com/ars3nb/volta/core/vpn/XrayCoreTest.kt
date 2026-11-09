package com.ars3nb.volta.core.vpn

import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * The core reads xray.xudp.basekey with Go's base64.RawURLEncoding and refuses to start
     * unless it is 32 bytes. Java's URL decoder is as strict ("+", "/" and "=" are errors).
     */
    @Test
    fun xudpBaseKeyIsWhatTheCoreAccepts() {
        val hwids = (0 until 100).map { "device-$it" } + "7f3a9c0e5b1d4e2f8a6c3b9d0e1f2a3b"
        var hadStdOnlyChars = false
        hwids.forEach { hwid ->
            val key = XrayCore.xudpBaseKeyFor(hwid)
            assertEquals(43, key.length)
            assertFalse(key, key.any { it == '+' || it == '/' || it == '=' })
            assertEquals(32, Base64.getUrlDecoder().decode(key).size)
            // Same bytes as the key Volta always used, only spelled for the core.
            val digest = MessageDigest.getInstance("SHA-256").digest(hwid.toByteArray())
            val old = Base64.getEncoder().withoutPadding().encodeToString(digest)
            if (old.any { it == '+' || it == '/' }) hadStdOnlyChars = true
            assertEquals(old.replace('+', '-').replace('/', '_'), key)
            assertEquals(key, XrayCore.xudpBaseKeyFor(hwid))
        }
        // The keys that used to break (most devices') are among those checked.
        assertTrue(hadStdOnlyChars)
    }
}
