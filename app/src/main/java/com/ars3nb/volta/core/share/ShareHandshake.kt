package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.link.SubCrypto
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The key agreement and the code both screens show, in the manner of Bluetooth's own
 * Numeric Comparison: the side that is read first commits to its nonce before it sees
 * anything of the other side, so nobody in the middle can choose a key or a nonce that
 * makes the codes agree.
 *
 * ```
 * responder (B, the GATT server)            initiator (A, the GATT client)
 *   keys PKb, nonce Nb (16 bytes)
 *   publishes PKb ‖ Cb, Cb = SHA-256(PKb ‖ Nb)
 *                                   ──────►  reads it; keys PKa, nonce Na
 *                                   ◄──────  HELLO  PKa ‖ Na
 *   REVEAL  Nb                      ──────►  checks SHA-256(PKb ‖ Nb) == Cb
 *
 * code = uint32_be(SHA-256(PKa ‖ PKb ‖ Na ‖ Nb ‖ sid)[0..3]) mod 10⁶, six digits
 * key  = HKDF-SHA256(ikm = ECDH(PKa, PKb), salt = Na ‖ Nb,
 *                    info = "volta-ble-v2" ‖ PKa ‖ PKb ‖ sid)
 * ```
 * sid is the session id the responder advertised. A [Responder] answers one HELLO only:
 * after it has revealed Nb its session is spent, so a man in the middle gets a single
 * guess at the code (one in a million) and then must start over where people can see.
 */
object ShareHandshake {

    const val NONCE_LEN = 16
    const val COMMIT_LEN = 32
    const val CODE_DIGITS = 6

    /** What the responder publishes: its key ‖ commitment. */
    const val PUBLISHED_LEN = ShareCrypto.KEY_LEN + COMMIT_LEN

    /** The body of HELLO: the initiator's key ‖ nonce. */
    const val HELLO_LEN = ShareCrypto.KEY_LEN + NONCE_LEN

    private const val INFO = "volta-ble-v2"

    /** The responder's nonce does not match its commitment: someone is in between. */
    class CommitmentException : ShareCrypto.ShareCryptoException("commitment does not match")

    /** The outcome on either side. [key] is the session key; never logged. */
    class Result(val code: String, internal val key: ByteArray) {
        override fun toString() = "Result(code=$code)"
    }

    fun commitment(publicKey: ByteArray, nonce: ByteArray): ByteArray = sha256(publicKey + nonce)

    fun code(pka: ByteArray, pkb: ByteArray, na: ByteArray, nb: ByteArray, sessionId: ByteArray): String {
        val h = sha256(pka + pkb + na + nb + sessionId)
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (n % 1_000_000).toString().padStart(CODE_DIGITS, '0')
    }

    fun sessionKey(
        secret: ByteArray,
        pka: ByteArray,
        pkb: ByteArray,
        na: ByteArray,
        nb: ByteArray,
        sessionId: ByteArray
    ): ByteArray = SubCrypto.deriveKey(secret, na + nb, INFO.toByteArray() + pka + pkb + sessionId)

    /** "123456" → "123 456", as the screens show it. */
    fun grouped(code: String): String =
        if (code.length == CODE_DIGITS) code.take(3) + " " + code.drop(3) else code

    /** The side that serves: publishes first, answers one HELLO. */
    class Responder(private val sessionId: ByteArray, random: SecureRandom = SecureRandom()) {
        private val keys = ShareCrypto.newKeyPair(random)
        private val publicKey = ShareCrypto.encodePublic(keys)
        private val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)

        /** For [ShareProtocol.KEY]: PKb ‖ Cb. */
        val published: ByteArray = publicKey + commitment(publicKey, nonce)

        /** A HELLO was answered: this session's nonce is out. */
        var spent = false
            private set

        /**
         * Takes the initiator's HELLO body and gives back what to reveal (Nb) and the
         * result. Throws for a malformed HELLO (and stays unspent), or once [spent].
         */
        fun answer(hello: ByteArray): Pair<ByteArray, Result> {
            if (spent) throw ShareCrypto.ShareCryptoException("already answered")
            if (hello.size != HELLO_LEN) throw ShareCrypto.ShareCryptoException("hello length")
            val pka = hello.copyOfRange(0, ShareCrypto.KEY_LEN)
            val na = hello.copyOfRange(ShareCrypto.KEY_LEN, HELLO_LEN)
            val peer = ShareCrypto.decodePublic(pka)
            val secret = SubCrypto.ecdh(keys.private, peer)
            spent = true
            val key = sessionKey(secret, pka, publicKey, na, nonce, sessionId)
            return nonce.copyOf() to Result(code(pka, publicKey, na, nonce, sessionId), key)
        }
    }

    /**
     * The side that connects, once it has read [published] (throws if that is not a
     * valid key ‖ commitment). Sends [hello], then [finish]es with the reveal.
     */
    class Initiator(
        published: ByteArray,
        private val sessionId: ByteArray,
        random: SecureRandom = SecureRandom()
    ) {
        private val peerKey: ByteArray
        private val promised: ByteArray

        init {
            if (published.size != PUBLISHED_LEN) throw ShareCrypto.ShareCryptoException("published length")
            peerKey = published.copyOfRange(0, ShareCrypto.KEY_LEN)
            promised = published.copyOfRange(ShareCrypto.KEY_LEN, PUBLISHED_LEN)
            ShareCrypto.decodePublic(peerKey)
        }

        private val keys = ShareCrypto.newKeyPair(random)
        private val publicKey = ShareCrypto.encodePublic(keys)
        private val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        private var done = false

        /** The body of HELLO: PKa ‖ Na. */
        val hello: ByteArray get() = publicKey + nonce

        /** Checks the reveal against the commitment; throws [CommitmentException] if it lies. */
        fun finish(reveal: ByteArray): Result {
            if (done) throw ShareCrypto.ShareCryptoException("already finished")
            done = true
            if (reveal.size != NONCE_LEN) throw CommitmentException()
            if (!MessageDigest.isEqual(commitment(peerKey, reveal), promised)) throw CommitmentException()
            val secret = SubCrypto.ecdh(keys.private, ShareCrypto.decodePublic(peerKey))
            val key = sessionKey(secret, publicKey, peerKey, nonce, reveal, sessionId)
            return Result(code(publicKey, peerKey, nonce, reveal, sessionId), key)
        }
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
