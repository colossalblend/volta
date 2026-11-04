package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.link.SubCrypto
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto of Bluetooth sharing (see [ShareProtocol]): the same ECIES construction as
 * [SubCrypto] (P-256 ECDH with an ephemeral sender key, HKDF-SHA256, AES-256-GCM), with
 * its own HKDF info and the session id bound into the AAD.
 *
 * Encrypted message:
 * ```
 * [0]       version, 0x01
 * [1..65]   sender's ephemeral P-256 public key, uncompressed (0x04 ‖ X ‖ Y)
 * [66..77]  AES-GCM nonce, 12 bytes
 * [78..]    AES-256-GCM ciphertext ‖ 16-byte tag
 * AAD     = bytes [0..65] ‖ session id
 * AES key = HKDF-SHA256(ikm = ECDH(ephemeral, receiver), salt = ephemeral key, info = "volta-ble-v1")
 * ```
 * Keys are made per session and never leave memory.
 */
object ShareCrypto {

    const val KEY_LEN = 65
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val HEADER_LEN = 1 + KEY_LEN
    private const val INFO = "volta-ble-v1"

    /** Anything wrong with a message: damaged, wrong key, wrong session. */
    class ShareCryptoException(message: String) : Exception(message)

    /** A fresh P-256 key pair: the receiver's for a session, or a sender's ephemeral one. */
    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    /** The 65-byte form that goes over the air. */
    fun encodePublic(key: KeyPair): ByteArray = SubCrypto.encodePoint(key.public as ECPublicKey)

    fun newSessionId(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(ShareProtocol.SESSION_ID_LEN).also(random::nextBytes)

    /**
     * The code both screens show: the first 4 bytes of SHA-256(receiver key ‖ session id)
     * as an unsigned number, its last four decimal digits, always 4 ASCII digits.
     */
    fun confirmationCode(receiverKey: ByteArray, sessionId: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(receiverKey + sessionId)
        val n = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return (n % 10_000).toString().padStart(4, '0')
    }

    /** Encrypts [plaintext] to [receiverKey] (65 bytes) for this [sessionId]. */
    fun seal(
        plaintext: ByteArray,
        receiverKey: ByteArray,
        sessionId: ByteArray,
        random: SecureRandom = SecureRandom()
    ): ByteArray {
        val eph = newKeyPair(random)
        val receiver = decodePublic(receiverKey, (eph.public as ECPublicKey).params)
        val epk = encodePublic(eph)
        val header = byteArrayOf(ShareProtocol.VERSION) + epk
        val aes = SubCrypto.deriveKey(SubCrypto.ecdh(eph.private, receiver), epk, INFO)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header + sessionId)
        return header + nonce + cipher.doFinal(plaintext)
    }

    /** Opens what [seal] made, with the receiver's private key; throws when anything is off. */
    fun open(message: ByteArray, receiver: KeyPair, sessionId: ByteArray): ByteArray {
        if (message.size < HEADER_LEN + NONCE_LEN + TAG_BITS / 8) throw ShareCryptoException("truncated")
        if (message[0] != ShareProtocol.VERSION) throw ShareCryptoException("version")
        val epk = message.copyOfRange(1, HEADER_LEN)
        val nonce = message.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        return try {
            val sender = decodePublic(epk, (receiver.public as ECPublicKey).params)
            val aes = SubCrypto.deriveKey(SubCrypto.ecdh(receiver.private, sender), epk, INFO)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(message.copyOfRange(0, HEADER_LEN) + sessionId)
            cipher.doFinal(message, HEADER_LEN + NONCE_LEN, message.size - HEADER_LEN - NONCE_LEN)
        } catch (e: ShareCryptoException) {
            throw e
        } catch (e: Exception) {
            throw ShareCryptoException("cannot open")
        }
    }

    /** True for 65 bytes that are a point on P-256: what a peer's key must be. */
    fun isValidKey(bytes: ByteArray): Boolean = runCatching { decodePublic(bytes, p256) }.isSuccess

    private val p256: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    /** A peer's key, checked to lie on the curve (an invalid point would leak key bits). */
    private fun decodePublic(bytes: ByteArray, params: ECParameterSpec): ECPublicKey {
        if (bytes.size != KEY_LEN || bytes[0] != 0x04.toByte()) throw ShareCryptoException("key format")
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val field = params.curve.field as java.security.spec.ECFieldFp
        val p = field.p
        if (x >= p || y >= p) throw ShareCryptoException("key range")
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        if (lhs != rhs) throw ShareCryptoException("key not on curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }
}
