package com.ars3nb.volta.core.link

import com.ars3nb.volta.data.model.ConnectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoltaLinksTest {

    private fun sub(link: String) = VoltaLinks.parse(link) as VoltaLinks.AddSubscription

    @Test
    fun pathImportKeepsTheProvidersOwnQuery() {
        val a = sub("volta://import/https://sub.example/api/sub?token=a+b&x=1&visible_subs=false&server-type=lte:1,2")
        assertEquals("https://sub.example/api/sub?token=a+b&x=1", a.url)
        assertTrue(a.hidden)
        assertEquals("lte:1,2", a.serverTypes)
    }

    @Test
    fun pathImportAcceptsOwnKeysAfterABareAmpersand() {
        val a = sub("volta://import/https://sub.example/abc&server-type=lte:3;home:1#My%20VPN")
        assertEquals("https://sub.example/abc", a.url)
        assertEquals("lte:3;home:1", a.serverTypes)
        assertEquals("My VPN", a.name)
        assertFalse(a.hidden)
    }

    @Test
    fun visibleByDefaultAndWhenTrue() {
        assertFalse(sub("volta://add/https://s.example/x").hidden)
        assertFalse(sub("volta://import/https://s.example/x?visible_subs=true").hidden)
        assertTrue(sub("volta://import/https://s.example/x?visible_subs=0").hidden)
    }

    @Test
    fun queryImportDecodesTheAddress() {
        val a = sub(
            "volta://import?url=https%3A%2F%2Fs.example%2Fsub%3Ft%3D1%26u%3D2&name=VPN&visible_subs=false&server-type=lte%3A1-3"
        )
        assertEquals("https://s.example/sub?t=1&u=2", a.url)
        assertEquals("VPN", a.name)
        assertTrue(a.hidden)
        assertEquals("lte:1,2,3", a.serverTypes)
    }

    @Test
    fun legacyFormsStillImportContent() {
        assertEquals(
            VoltaLinks.ImportContent("vless://id@host:443#x"),
            VoltaLinks.parse("volta://?config=vless%3A%2F%2Fid%40host%3A443%23x")
        )
        assertEquals(VoltaLinks.ImportContent("vless://id@h:1"), VoltaLinks.parse("vless://id@h:1"))
        assertTrue(VoltaLinks.parse("volta://?url=https://s.example/a") is VoltaLinks.AddSubscription)
    }

    @Test
    fun controlLinks() {
        assertEquals(VoltaLinks.Connect(null), VoltaLinks.parse("volta://connect"))
        assertEquals(VoltaLinks.Connect(ConnectMode.AUTO_LTE), VoltaLinks.parse("volta://connect?mode=lte"))
        assertEquals(VoltaLinks.Connect(ConnectMode.AUTO_HOME), VoltaLinks.parse("volta://on?mode=wifi"))
        assertEquals(VoltaLinks.Connect(ConnectMode.AUTO_HOME_LTE), VoltaLinks.parse("volta://connect?mode=home+lte"))
        assertEquals(VoltaLinks.Disconnect, VoltaLinks.parse("volta://disconnect"))
        assertEquals(VoltaLinks.Disconnect, VoltaLinks.parse("VOLTA://off"))
        assertEquals(VoltaLinks.Toggle, VoltaLinks.parse("volta://toggle"))
    }

    @Test
    fun encryptedLinkRoundTripsAndStaysHidden() {
        val payload = SubCrypto.encrypt("https://sub.example/secret?token=42")
        val parsed = VoltaLinks.parse("volta://crypt/$payload?name=Shop") as VoltaLinks.AddEncrypted
        val opened = VoltaLinks.openEncrypted(parsed) as VoltaLinks.AddSubscription
        assertEquals("https://sub.example/secret?token=42", opened.url)
        assertEquals("Shop", opened.name)
        assertTrue(opened.hidden)
    }

    @Test
    fun encryptedImportLinkCarriesItsOwnSettings() {
        val inner = "volta://import/https://sub.example/s?visible_subs=true&server-type=lte:2#Inner"
        val opened = VoltaLinks.openEncrypted(VoltaLinks.AddEncrypted(SubCrypto.encrypt(inner))) as VoltaLinks.AddSubscription
        assertFalse(opened.hidden)
        assertEquals("lte:2", opened.serverTypes)
        assertEquals("Inner", opened.name)

        val json = """{"url":"https://sub.example/j","server-type":"home:1"}"""
        val fromJson = VoltaLinks.openEncrypted(VoltaLinks.AddEncrypted(SubCrypto.encrypt(json))) as VoltaLinks.AddSubscription
        assertTrue(fromJson.hidden)
        assertEquals("home:1", fromJson.serverTypes)
    }

    @Test
    fun outerVisibleFlagCannotRevealAnEncryptedLink() {
        val payload = SubCrypto.encrypt("https://sub.example/secret")
        val parsed = VoltaLinks.parse("volta://crypt/$payload?visible_subs=true") as VoltaLinks.AddEncrypted
        assertTrue((VoltaLinks.openEncrypted(parsed) as VoltaLinks.AddSubscription).hidden)
    }

    @Test
    fun tamperedOrForeignPayloadIsRefused() {
        val payload = SubCrypto.encrypt("https://sub.example/secret")
        val chars = payload.toCharArray()
        val i = chars.size - 5
        chars[i] = if (chars[i] == 'A') 'B' else 'A'
        assertTrue(VoltaLinks.openEncrypted(VoltaLinks.AddEncrypted(String(chars))) is VoltaLinks.Invalid)
        assertTrue(VoltaLinks.openEncrypted(VoltaLinks.AddEncrypted("not-a-payload")) is VoltaLinks.Invalid)
    }

    /** Links made by tools/volta_crypt.py: the provider tool and the app must agree. */
    @Test
    fun linksFromTheProviderToolOpen() {
        val plain = VoltaLinks.parse(
            "volta://crypt/AQEEiz2QtC8zTkBrHcMezKfX1oeoqreqVx_lVRq7__VKm_48YOyj5zmGifePJKPBgSPZzU2p2w62uPK_H1S0_UAsSvN4pDYwZ042R2ZhFSPOi5WeYyPiViaNyottS2_rkzn6nEWxKMDMRz-2tickeHHtIUqaHy3l-82qjdA"
        ) as VoltaLinks.AddEncrypted
        val a = VoltaLinks.openEncrypted(plain) as VoltaLinks.AddSubscription
        assertEquals("https://sub.example/py?token=7", a.url)
        assertTrue(a.hidden)

        val withJson = VoltaLinks.parse(
            "volta://crypt/AQEExqoPL4ZwFmoQwnfo6UvidEQ4bfWF0Ke3yuGBgWphc7sbLRWpRypAfNUHiYQlkTPVeenqgBAp6kRitsBGKkkC-FoAzGV5RTjkEXFTU5AGZE_pNGf63e-4MMrKjCq-A1zmpbSYjvH_7uJZ9U8V4PvhEoR66Vr7AzklnGkCrguZXA-2cULRwQNdtK9P_KM6jTR536BnCK9-5kk-sPIaqrPF75xgGvSK"
        ) as VoltaLinks.AddEncrypted
        val b = VoltaLinks.openEncrypted(withJson) as VoltaLinks.AddSubscription
        assertEquals("https://sub.example/py2", b.url)
        assertEquals("Py VPN", b.name)
        assertEquals("lte:1,2", b.serverTypes)
        assertTrue(b.hidden)
    }

    @Test
    fun eachEncryptionIsDifferent() {
        assertNotEquals(SubCrypto.encrypt("https://a.example"), SubCrypto.encrypt("https://a.example"))
    }
}
