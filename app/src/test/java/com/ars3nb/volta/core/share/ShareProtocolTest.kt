package com.ars3nb.volta.core.share

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareProtocolTest {

    private val sid = byteArrayOf(0x12, 0x34, 0x56, 0x78)

    @Test
    fun advertRoundTrip() {
        for (role in listOf(ShareProtocol.ROLE_RECEIVER, ShareProtocol.ROLE_SENDER)) {
            val ad = ShareProtocol.advert(role, sid)
            assertEquals(6, ad.size)
            assertArrayEquals(ShareProtocol.advertPrefix(role), ad.copyOf(2))
            assertEquals(ShareProtocol.Advert(role, sid), ShareProtocol.parseAdvert(ad))
        }
    }

    @Test
    fun advertFitsLegacyAdvertising() {
        // Flags (3) + service data with a 128-bit UUID (2 + 16 + payload) must fit 31 bytes.
        assertTrue(3 + 2 + 16 + ShareProtocol.advert(ShareProtocol.ROLE_RECEIVER, sid).size <= 31)
    }

    @Test
    fun strayAdvertsAreIgnored() {
        assertNull(ShareProtocol.parseAdvert(null))
        assertNull(ShareProtocol.parseAdvert(byteArrayOf(2, 'R'.code.toByte(), 1, 2)))
        // Version 1 (never released, code from one key) is not this protocol.
        assertNull(ShareProtocol.parseAdvert(byteArrayOf(1, 'R'.code.toByte(), 1, 2, 3, 4)))
        assertNull(ShareProtocol.parseAdvert(byteArrayOf(3, 'R'.code.toByte(), 1, 2, 3, 4)))
        assertNull(ShareProtocol.parseAdvert(byteArrayOf(2, 'X'.code.toByte(), 1, 2, 3, 4)))
    }

    @Test
    fun theAdvertCarriesVersionTwo() {
        assertEquals(2, ShareProtocol.VERSION.toInt())
        assertEquals(2, ShareProtocol.advert(ShareProtocol.ROLE_SENDER, sid)[0].toInt())
    }

    @Test
    fun messagesFitTheirFrames() {
        val hello = ShareProtocol.message(ShareProtocol.MSG_HELLO, ByteArray(ShareHandshake.HELLO_LEN))
        assertEquals(1 + 65 + 16, hello.size)
        assertEquals(ShareProtocol.MSG_HELLO, hello[0])
        // At the default MTU the hello takes a few frames; at 517 one.
        assertEquals(6, ShareFraming.split(hello, 23).size)
        assertEquals(1, ShareFraming.split(hello, 517).size)
        assertEquals(97, ShareHandshake.PUBLISHED_LEN)
    }

    @Test
    fun shortTagIsHexOfTheSession() {
        assertEquals("1234", ShareProtocol.shortTag(sid))
        assertEquals("00FF", ShareProtocol.shortTag(byteArrayOf(0, -1, 0, 0)))
    }

    @Test
    fun characteristicsShareTheServiceBase() {
        val base = ShareProtocol.SERVICE.toString().substring(8)
        listOf(ShareProtocol.KEY, ShareProtocol.UPLINK, ShareProtocol.DOWNLINK).forEach {
            assertEquals(base, it.toString().substring(8))
        }
    }
}
