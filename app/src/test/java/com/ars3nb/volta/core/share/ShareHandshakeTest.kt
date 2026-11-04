package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.share.ShareHandshake.Initiator
import com.ars3nb.volta.core.share.ShareHandshake.Responder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ShareHandshakeTest {

    private val sid = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())

    // Fixed inputs; the expected values were worked out independently (Python hashlib/hmac).
    private val pka = byteArrayOf(4) + ByteArray(64) { (it + 1).toByte() }
    private val pkb = byteArrayOf(4) + ByteArray(64) { (it + 65).toByte() }
    private val na = ByteArray(16) { 0xA1.toByte() }
    private val nb = ByteArray(16) { 0xB2.toByte() }

    @Test
    fun codeVectors() {
        assertEquals("909037", ShareHandshake.code(pka, pkb, na, nb, sid))
        // Any input changes it: a nonce byte, the order of the keys.
        assertEquals("230916", ShareHandshake.code(pka, pkb, na, nb.copyOf().also { it[15] = 0xB3.toByte() }, sid))
        assertEquals("167861", ShareHandshake.code(pkb, pka, na, nb, sid))
        assertEquals("909 037", ShareHandshake.grouped("909037"))
    }

    @Test
    fun commitmentAndKeyVectors() {
        assertEquals(
            "06f03f1043f0fc2290ba5e17fe07a558694f7c946912e02d645c5a019e9e24b5",
            hex(ShareHandshake.commitment(pkb, nb))
        )
        assertEquals(
            "89da03425e37f4e4ee259aeb87a70a66853ca0ef4a1d2672906adee3c369d992",
            hex(ShareHandshake.sessionKey(ByteArray(32) { 0x11 }, pka, pkb, na, nb, sid))
        )
    }

    @Test
    fun bothSidesAgreeOnCodeAndKey() {
        repeat(20) {
            val b = Responder(sid)
            assertEquals(ShareHandshake.PUBLISHED_LEN, b.published.size)
            val a = Initiator(b.published, sid)
            assertEquals(ShareHandshake.HELLO_LEN, a.hello.size)
            val (reveal, onB) = b.answer(a.hello)
            val onA = a.finish(reveal)
            assertEquals(onB.code, onA.code)
            assertTrue(onA.code, onA.code.length == 6 && onA.code.all { it in '0'..'9' })
            assertArrayEquals(onB.key, onA.key)
            assertEquals(32, onA.key.size)
            // And the key does what it is for.
            val sealed = ShareCrypto.seal("hi".toByteArray(), onA.key, sid, ShareProtocol.MSG_LINK)
            assertEquals("hi", String(ShareCrypto.open(sealed, onB.key, sid, ShareProtocol.MSG_LINK)))
        }
    }

    @Test
    fun aRevealThatDoesNotOpenTheCommitmentIsRejected() {
        val b = Responder(sid)
        val (reveal, _) = b.answer(Initiator(b.published, sid).hello)
        // Someone in between answers with a nonce of their choosing.
        val a = Initiator(b.published, sid)
        for (bad in listOf(reveal.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, ByteArray(16), reveal.copyOf(15))) {
            expectCommitment { Initiator(b.published, sid).finish(bad) }
        }
        // A real nonce, but the commitment published was another one.
        val forged = b.published.copyOf().also { it[70] = (it[70].toInt() xor 1).toByte() }
        expectCommitment { Initiator(forged, sid).finish(reveal) }
        // finish works once only.
        try {
            a.finish(ByteArray(16))
        } catch (e: ShareHandshake.CommitmentException) {
            // expected
        }
        expectFailure { a.finish(reveal) }
    }

    @Test
    fun theResponderAnswersOnceOnly() {
        val b = Responder(sid)
        b.answer(Initiator(b.published, sid).hello)
        assertTrue(b.spent)
        expectFailure { b.answer(Initiator(b.published, sid).hello) }
    }

    @Test
    fun aBadHelloRevealsNothingAndLeavesTheResponderWaiting() {
        val b = Responder(sid)
        val offCurve = byteArrayOf(4) + ByteArray(64) { 7 } + ByteArray(16)
        expectFailure { b.answer(offCurve) }
        expectFailure { b.answer(ByteArray(10)) }
        assertFalse(b.spent)
        val a = Initiator(b.published, sid)
        val (reveal, onB) = b.answer(a.hello)
        assertEquals(onB.code, a.finish(reveal).code)
    }

    @Test
    fun anInvalidPublishedKeyIsRefused() {
        val b = Responder(sid)
        expectFailure { Initiator(b.published.copyOf(96), sid) }
        val badKey = b.published.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }
        expectFailure { Initiator(badKey, sid) }
    }

    @Test
    fun aManInTheMiddleSeesTwoDifferentCodes() {
        // M stands between A and B, running a responder towards A and an initiator towards B.
        // Neither side's nonce is known to M when it must commit to its own, so the codes
        // differ but for one chance in a million.
        val b = Responder(sid)
        val mToB = Initiator(b.published, sid)
        val (nb, onB) = b.answer(mToB.hello)
        val onMb = mToB.finish(nb)
        val mToA = Responder(sid)
        val a = Initiator(mToA.published, sid)
        val (nm, onMa) = mToA.answer(a.hello)
        val onA = a.finish(nm)
        assertEquals(onB.code, onMb.code)
        assertEquals(onA.code, onMa.code)
        assertNotEquals(onA.code, onB.code)
        assertFalse(onA.key.contentEquals(onB.key))
    }

    @Test
    fun theSessionIdIsInTheCode() {
        val b = Responder(sid)
        val a = Initiator(b.published, byteArrayOf(1, 2, 3, 4))
        val (reveal, onB) = b.answer(a.hello)
        val onA = a.finish(reveal)
        assertNotEquals(onB.code, onA.code)
        assertFalse(onA.key.contentEquals(onB.key))
    }

    @Test
    fun theResultNeverPrintsItsKey() {
        val b = Responder(sid)
        val (_, r) = b.answer(Initiator(b.published, sid).hello)
        assertEquals("Result(code=${r.code})", r.toString())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun expectCommitment(block: () -> Unit) {
        try {
            block()
            fail("expected CommitmentException")
        } catch (e: ShareHandshake.CommitmentException) {
            // expected
        }
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
