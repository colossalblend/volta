package com.ars3nb.volta.core.share

import com.ars3nb.volta.core.link.SubCrypto
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The crypto of Bluetooth sharing (see [ShareProtocol]), built from the same parts as
 * [SubCrypto]: P-256 keys and ECDH, HKDF-SHA256, AES-256-GCM. The key agreement and the
 * code are in [ShareHandshake]; this seals the messages that follow it with the session
 * key it derives.
 *
 * Sealed message (the body of [ShareProtocol.MSG_LINK] and [ShareProtocol.MSG_STATUS]):
 * ```
 * [0]      version, 0x02
 * [1..12]  AES-GCM nonce, 12 random bytes
 * [13..]   AES-256-GCM ciphertext ‖ 16-byte tag
 * AAD    = version ‖ message type ‖ session id
 * ```
 * The type in the AAD keeps a sealed status from passing for a link and back. Keys are
 * made per session and never leave memory.
 */
object ShareCrypto {

    const val KEY_LEN = 65
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val HEADER_LEN = 1 + NONCE_LEN

    /** What sealing adds to a plaintext. */
    const val OVERHEAD = HEADER_LEN + TAG_BITS / 8

    /** Anything wrong with a message or a key: damaged, wrong key, wrong session. */
    open class ShareCryptoException(message: String) : Exception(message)

    /** A fresh P-256 key pair, for one session. */
    fun newKeyPair(random: SecureRandom = SecureRandom()): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
            .generateKeyPair()

    /** The 65-byte form that goes over the air. */
    fun encodePublic(key: KeyPair): ByteArray = SubCrypto.encodePoint(key.public as ECPublicKey)

    fun newSessionId(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(ShareProtocol.SESSION_ID_LEN).also(random::nextBytes)

    /** Seals [plaintext] with the 32-byte session [key] as a message of [type]. */
    fun seal(
        plaintext: ByteArray,
        key: ByteArray,
        sessionId: ByteArray,
        type: Byte,
        random: SecureRandom = SecureRandom()
    ): ByteArray {
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(type, sessionId))
        return byteArrayOf(ShareProtocol.VERSION) + nonce + cipher.doFinal(plaintext)
    }

    /** Opens what [seal] made; throws when anything is off (key, session, type, a byte). */
    fun open(message: ByteArray, key: ByteArray, sessionId: ByteArray, type: Byte): ByteArray {
        if (message.size < OVERHEAD) throw ShareCryptoException("truncated")
        if (message[0] != ShareProtocol.VERSION) throw ShareCryptoException("version")
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                GCMParameterSpec(TAG_BITS, message, 1, NONCE_LEN)
            )
            cipher.updateAAD(aad(type, sessionId))
            cipher.doFinal(message, HEADER_LEN, message.size - HEADER_LEN)
        } catch (e: Exception) {
            throw ShareCryptoException("cannot open")
        }
    }

    private fun aad(type: Byte, sessionId: ByteArray) = byteArrayOf(ShareProtocol.VERSION, type) + sessionId

    /** True for 65 bytes that are a point on P-256: what a peer's key must be. */
    fun isValidKey(bytes: ByteArray): Boolean = runCatching { decodePublic(bytes) }.isSuccess

    private val p256: ECParameterSpec by lazy { (newKeyPair().public as ECPublicKey).params }

    /** A peer's key, checked to lie on the curve (an invalid point would leak key bits). */
    fun decodePublic(bytes: ByteArray): ECPublicKey {
        val params = p256
        if (bytes.size != KEY_LEN || bytes[0] != 0x04.toByte()) throw ShareCryptoException("key format")
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        val p = (params.curve.field as ECFieldFp).p
        if (x >= p || y >= p) throw ShareCryptoException("key range")
        val lhs = y.multiply(y).mod(p)
        val rhs = x.multiply(x).multiply(x).add(params.curve.a.multiply(x)).add(params.curve.b).mod(p)
        if (lhs != rhs) throw ShareCryptoException("key not on curve")
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params)) as ECPublicKey
    }
}
