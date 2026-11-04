package com.ars3nb.volta.core.share

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareCryptoTest {

    private val key = ByteArray(32).also(SecureRandom()::nextBytes)
    private val session = byteArrayOf(1, 2, 3, 4)
    private val link = ShareProtocol.MSG_LINK

    @Test
    fun roundTrip() {
        val text = "volta://import?url=https%3A%2F%2Fsub.example%2Fabc&name=%D0%94%D0%BE%D0%BC&visible_subs=false"
        val sealed = ShareCrypto.seal(text.toByteArray(), key, session, link)
        assertEquals(text, String(ShareCrypto.open(sealed, key, session, link)))
    }

    @Test
    fun roundTripOfEmptyAndLongPayloads() {
        for (size in listOf(0, 1, 500, ShareProtocol.MAX_PLAINTEXT)) {
            val plain = ByteArray(size).also(SecureRandom()::nextBytes)
            val sealed = ShareCrypto.seal(plain, key, session, link)
            assertEquals(size + ShareCrypto.OVERHEAD, sealed.size)
            assertTrue(1 + sealed.size <= ShareProtocol.MAX_MESSAGE)
            assertArrayEquals(plain, ShareCrypto.open(sealed, key, session, link))
        }
    }

    @Test
    fun theSameTextSealsDifferentlyEveryTime() {
        val a = ShareCrypto.seal("x".toByteArray(), key, session, link)
        val b = ShareCrypto.seal("x".toByteArray(), key, session, link)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun anotherKeyCannotOpenIt() {
        val sealed = ShareCrypto.seal("secret".toByteArray(), key, session, link)
        val other = key.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        expectFailure { ShareCrypto.open(sealed, other, session, link) }
    }

    @Test
    fun theSessionIdIsBoundIn() {
        val sealed = ShareCrypto.seal("secret".toByteArray(), key, session, link)
        expectFailure { ShareCrypto.open(sealed, key, byteArrayOf(1, 2, 3, 5), link) }
    }

    @Test
    fun theMessageTypeIsBoundIn() {
        // A sealed status cannot be passed off as a link, nor the other way round.
        val status = ShareCrypto.seal(byteArrayOf(ShareProtocol.STATUS_OK), key, session, ShareProtocol.MSG_STATUS)
        expectFailure { ShareCrypto.open(status, key, session, link) }
        val sealed = ShareCrypto.seal("x".toByteArray(), key, session, link)
        expectFailure { ShareCrypto.open(sealed, key, session, ShareProtocol.MSG_STATUS) }
    }

    @Test
    fun anyChangedByteIsRefused() {
        val sealed = ShareCrypto.seal("secret".toByteArray(), key, session, link)
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            expectFailure { ShareCrypto.open(bad, key, session, link) }
        }
        expectFailure { ShareCrypto.open(sealed.copyOf(sealed.size - 1), key, session, link) }
        expectFailure { ShareCrypto.open(ByteArray(10), key, session, link) }
    }

    @Test
    fun onlyPointsOnTheCurveAreKeys() {
        val pk = ShareCrypto.encodePublic(ShareCrypto.newKeyPair())
        assertTrue(ShareCrypto.isValidKey(pk))
        assertFalse(ShareCrypto.isValidKey(pk.copyOf(64)))
        assertFalse(ShareCrypto.isValidKey(ByteArray(65)))
        val offCurve = pk.copyOf().also { it[64] = (it[64].toInt() xor 0x01).toByte() }
        assertFalse(ShareCrypto.isValidKey(offCurve))
    }

    private fun expectFailure(block: () -> Unit) {
        try {
            block()
            fail("expected ShareCryptoException")
        } catch (e: ShareCrypto.ShareCryptoException) {
            // expected
        }
    }
}
