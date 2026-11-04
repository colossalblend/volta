package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.link.ImportDetector
import com.ars3nb.volta.core.link.VoltaLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharePayloadTest {

    private val url = "https://sub.example/api/sub?token=a+b/c&x=1#frag"

    @Test
    fun aHiddenSubscriptionArrivesHidden() {
        val text = SharePayload.subscription(url, "Мой VPN & co", hidden = true, serverTypes = "lte:1,2;home:3")
        val a = VoltaLinks.parse(text) as VoltaLinks.AddSubscription
        assertEquals(url, a.url)
        assertEquals("Мой VPN & co", a.name)
        assertTrue(a.hidden)
        assertEquals("lte:1,2;home:3", a.serverTypes)
        assertEquals(ImportDetector.Kind.Subscription(hidden = true), ImportDetector.detect(text))
    }

    @Test
    fun aVisibleSubscriptionStaysVisible() {
        val text = SharePayload.subscription("https://s.example/x", "", hidden = false)
        val a = VoltaLinks.parse(text) as VoltaLinks.AddSubscription
        assertEquals("https://s.example/x", a.url)
        assertFalse(a.hidden)
        assertEquals("", a.name)
        assertEquals("", a.serverTypes)
    }

    @Test
    fun theReceiverNeverSeesAHiddenAddress() {
        val p = SharePayload.preview(SharePayload.subscription(url, "Home", hidden = true))
        assertTrue(p.hidden)
        assertNull(p.excerpt)
        assertEquals("Home", p.name)

        val encrypted = SharePayload.preview("volta://crypt/AQEabc")
        assertTrue(encrypted.hidden)
        assertNull(encrypted.excerpt)
    }

    @Test
    fun aVisibleOneShowsItsAddress() {
        val p = SharePayload.preview(SharePayload.subscription("https://s.example/x", "Home", hidden = false))
        assertFalse(p.hidden)
        assertEquals("https://s.example/x", p.excerpt)
    }

    @Test
    fun aServerTravelsAsItsLink() {
        val link = "vless://uuid@host.example:443?security=reality#Germany"
        assertEquals(link, SharePayload.server(link, ""))
        assertEquals("{\"outbounds\":[]}", SharePayload.server("", "{\"outbounds\":[]}"))
        assertNull(SharePayload.server(" ", ""))
        val p = SharePayload.preview(link)
        assertTrue(p.kind is ImportDetector.Kind.Server)
        assertEquals(link, p.excerpt)
    }

    @Test
    fun theAddFieldIsSentAsTyped() {
        // A named plain address keeps its name on the way.
        val named = VoltaLinks.parse(SharePayload.fromField(" https://s.example/x ", "Home")) as VoltaLinks.AddSubscription
        assertEquals("https://s.example/x", named.url)
        assertEquals("Home", named.name)
        assertFalse(named.hidden)
        assertEquals("https://s.example/x", SharePayload.fromField("https://s.example/x", " "))
        // A volta:// link (here a hidden one) is never rewritten.
        val hidden = "volta://import/https://s.example/x?visible_subs=false"
        assertEquals(hidden, SharePayload.fromField(hidden, "Home"))
        assertEquals("vless://u@h:1#A", SharePayload.fromField("vless://u@h:1#A\n", ""))
    }

    @Test
    fun aLongExcerptIsCut() {
        val p = SharePayload.preview("vless://" + "a".repeat(500) + "@h:1")
        assertEquals(120, p.excerpt!!.length)
    }
}
