package com.ars3nb.volta.core.link

import com.ars3nb.volta.core.link.ImportDetector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDetectorTest {

    @Test
    fun recognisesWhatWasPasted() {
        assertEquals(Kind.Empty, ImportDetector.detect("   "))
        assertEquals(Kind.Subscription(false), ImportDetector.detect("https://sub.example/abc"))
        assertEquals(Kind.Subscription(true), ImportDetector.detect("volta://import/https://s.example/a?visible_subs=false"))
        assertEquals(Kind.Encrypted, ImportDetector.detect("volta://crypt/AQEEabc"))
        assertEquals(Kind.Server("VLESS"), ImportDetector.detect("vless://id@host:443?type=tcp#Germany"))
        assertEquals(Kind.Server("Hysteria2"), ImportDetector.detect("hy2://pass@host:443#x"))
        assertEquals(Kind.Servers(2), ImportDetector.detect("vless://a@h:1#a\ntrojan://p@h:2#b"))
        assertEquals(Kind.AmneziaKey, ImportDetector.detect("vpn://AAAA"))
        assertEquals(Kind.Json, ImportDetector.detect("{\"outbounds\": []}"))
        assertEquals(Kind.Conf, ImportDetector.detect("[Interface]\nPrivateKey = x\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = y\nEndpoint = 1.2.3.4:51820"))
        assertEquals(Kind.Control, ImportDetector.detect("volta://connect"))
        assertEquals(Kind.Unknown, ImportDetector.detect("hello world"))
    }

    @Test
    fun subscriptionKindsGoToTheSubscriptionPath() {
        assertTrue(Kind.Encrypted.isSubscription)
        assertTrue(Kind.Subscription(false).isSubscription)
        assertTrue(!Kind.Server("VLESS").isSubscription)
    }
}
