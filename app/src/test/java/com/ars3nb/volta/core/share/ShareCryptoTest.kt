package com.ars3nb.volta.core.share

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareCryptoTest {

    private val receiver = ShareCrypto.newKeyPair()
    private val receiverKey = ShareCrypto.encodePublic(receiver)
    private val session = byteArrayOf(1, 2, 3, 4)

    @Test
    fun roundTrip() {
        val text = "volta://import?url=https%3A%2F%2Fsub.example%2Fabc&name=%D0%94%D0%BE%D0%BC&visible_subs=false"
        val sealed = ShareCrypto.seal(text.toByteArray(), receiverKey, session)
        assertEquals(text, String(ShareCrypto.open(sealed, receiver, session)))
    }

    @Test
    fun roundTripOfEmptyAndLongPayloads() {
        for (size in listOf(0, 1, 500, ShareProtocol.MAX_PLAINTEXT)) {
            val plain = ByteArray(size).also(SecureRandom()::nextBytes)
            val sealed = ShareCrypto.seal(plain, receiverKey, session)
            assertEquals(size + 1 + 65 + 12 + 16, sealed.size)
            assertArrayEquals(plain, ShareCrypto.open(sealed, receiver, session))
        }
    }

    @Test
    fun theSameTextSealsDifferentlyEveryTime() {
        val a = ShareCrypto.seal("x".toByteArray(), receiverKey, session)
        val b = ShareCrypto.seal("x".toByteArray(), receiverKey, session)
        assertFalse(a.contentEquals(b))
    }

    @Test
    fun anotherKeyCannotOpenIt() {
        val sealed = ShareCrypto.seal("secret".toByteArray(), receiverKey, session)
        expectFailure { ShareCrypto.open(sealed, ShareCrypto.newKeyPair(), session) }
    }

    @Test
    fun theSessionIdIsBoundIn() {
        val sealed = ShareCrypto.seal("secret".toByteArray(), receiverKey, session)
        expectFailure { ShareCrypto.open(sealed, receiver, byteArrayOf(1, 2, 3, 5)) }
    }

    @Test
    fun anyChangedByteIsRefused() {
        val sealed = ShareCrypto.seal("secret".toByteArray(), receiverKey, session)
        for (i in sealed.indices) {
            val bad = sealed.copyOf().also { it[i] = (it[i].toInt() xor 0x01).toByte() }
            expectFailure { ShareCrypto.open(bad, receiver, session) }
        }
        expectFailure { ShareCrypto.open(sealed.copyOf(sealed.size - 1), receiver, session) }
        expectFailure { ShareCrypto.open(ByteArray(10), receiver, session) }
    }

    @Test
    fun confirmationCodeIsStableAndFourDigits() {
        val key = byteArrayOf(4) + ByteArray(64) { (it + 1).toByte() }
        // SHA-256(key ‖ DEADBEEF)[0..3] as an unsigned number, mod 10 000.
        assertEquals("2599", ShareCrypto.confirmationCode(key, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())))
        assertEquals("3393", ShareCrypto.confirmationCode(key, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEE.toByte())))
        repeat(50) {
            val code = ShareCrypto.confirmationCode(ShareCrypto.encodePublic(ShareCrypto.newKeyPair()), ShareCrypto.newSessionId())
            assertTrue(code, code.length == 4 && code.all { it in '0'..'9' })
        }
    }

    @Test
    fun bothSidesDeriveTheSameCode() {
        val onReceiver = ShareCrypto.confirmationCode(receiverKey, session)
        val onSender = ShareCrypto.confirmationCode(receiverKey.copyOf(), session.copyOf())
        assertEquals(onReceiver, onSender)
        // A key swapped in by someone in the middle gives another code (almost always).
        val other = ShareCrypto.encodePublic(ShareCrypto.newKeyPair())
        assertNotEquals(ShareCrypto.confirmationCode(other, session), onReceiver)
    }

    @Test
    fun onlyPointsOnTheCurveAreKeys() {
        assertTrue(ShareCrypto.isValidKey(receiverKey))
        assertFalse(ShareCrypto.isValidKey(receiverKey.copyOf(64)))
        assertFalse(ShareCrypto.isValidKey(ByteArray(65)))
        val offCurve = receiverKey.copyOf().also { it[64] = (it[64].toInt() xor 0x01).toByte() }
        assertFalse(ShareCrypto.isValidKey(offCurve))
        expectFailure { ShareCrypto.seal("x".toByteArray(), offCurve, session) }
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
