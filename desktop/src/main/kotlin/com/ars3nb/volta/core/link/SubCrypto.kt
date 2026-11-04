package com.ars3nb.volta.core.link

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted subscription links, in the spirit of Happ's `happ://crypt…`: the provider
 * encrypts the subscription address with Volta's public key and hands out
 * `volta://crypt/<payload>`. Only the app can open it, and the address is then kept
 * hidden (see SubscriptionEntity.urlHidden).
 *
 * Payload, base64url without padding:
 *
 * ```
 * [0]      version, 0x01
 * [1]      key id (which app key encrypted it; lets a future build rotate keys)
 * [2..66]  ephemeral P-256 public key, uncompressed (0x04 ‖ X ‖ Y)
 * [67..78] AES-GCM nonce, 12 bytes
 * [79..]   AES-256-GCM ciphertext ‖ 16-byte tag; AAD = bytes [0..66]
 * ```
 *
 * AES key = HKDF-SHA256(ikm = ECDH(app key, ephemeral key), salt = ephemeral key,
 * info = "volta-crypt-v1"). ECIES over P-256 keeps a link short (≈ 130 characters plus
 * the address) and needs nothing beyond the JDK, on every Android version Volta runs on.
 *
 * Honest limits: like Happ's, the private key ships inside the app. This stops the link
 * from being read, forwarded as a plain URL or pasted into another client; it does not
 * stop someone who takes the APK apart. Providers encrypt with tools/volta_crypt.py.
 */
object SubCrypto {

    const val VERSION: Byte = 0x01
    const val CURRENT_KEY_ID = 1
    private const val INFO = "volta-crypt-v1"
    private const val EPK_LEN = 65
    private const val NONCE_LEN = 12
    private const val TAG_BITS = 128
    private const val HEADER_LEN = 2 + EPK_LEN

    /** Volta's public key (X.509 SubjectPublicKeyInfo, base64). Safe to publish. */
    const val PUBLIC_KEY_B64 =
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEUWxsXxcqdWIU7N6R2yqKdHwBzCgpx9jOwsb8pNV29/W96Nm6I+WD5nq5e4/7l7tsSaOK8zGtnCw1DTw17uAeNg=="

    class CryptException(message: String) : Exception(message)

    /** Opens a payload; throws [CryptException] with a user-facing message on failure. */
    fun decrypt(payload: String, keys: Map<Int, PrivateKey> = appKeys): String {
        val bytes = decodeBase64Url(payload)
            ?: throw CryptException("Зашифрованная ссылка повреждена")
        if (bytes.size < HEADER_LEN + NONCE_LEN + TAG_BITS / 8 + 1) {
            throw CryptException("Зашифрованная ссылка обрезана")
        }
        if (bytes[0] != VERSION) {
            throw CryptException("Ссылка зашифрована для более новой версии Volta — обновите приложение")
        }
        val keyId = bytes[1].toInt() and 0xFF
        val key = keys[keyId] as? ECPrivateKey
            ?: throw CryptException("Ссылка зашифрована ключом, которого нет в этой версии Volta")
        val epkBytes = bytes.copyOfRange(2, HEADER_LEN)
        val nonce = bytes.copyOfRange(HEADER_LEN, HEADER_LEN + NONCE_LEN)
        val body = bytes.copyOfRange(HEADER_LEN + NONCE_LEN, bytes.size)
        return try {
            val epk = decodePoint(epkBytes, key)
            val aes = deriveKey(ecdh(key, epk), epkBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(bytes, 0, HEADER_LEN)
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: CryptException) {
            throw e
        } catch (e: Exception) {
            throw CryptException("Не удалось расшифровать ссылку: она повреждена или не для Volta")
        }
    }

    /** What a provider tool does; here for tests and for the app's own key checks. */
    fun encrypt(
        plaintext: String,
        publicKey: PublicKey = appPublicKey,
        keyId: Int = CURRENT_KEY_ID,
        random: SecureRandom = SecureRandom()
    ): String {
        val pub = publicKey as ECPublicKey
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), random) }
        val eph = gen.generateKeyPair()
        val epkBytes = encodePoint(eph.public as ECPublicKey)
        val header = byteArrayOf(VERSION, keyId.toByte()) + epkBytes
        val aes = deriveKey(ecdh(eph.private, pub), epkBytes)
        val nonce = ByteArray(NONCE_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(header)
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(header + nonce + body)
    }

    // ------------------------------------------------------------------ keys

    val appPublicKey: PublicKey by lazy {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY_B64)))
    }

    private val appKeys: Map<Int, PrivateKey> by lazy {
        mapOf(CURRENT_KEY_ID to unmask(K1))
    }

    /** Masked so the key is not a greppable string in the APK; not a secret from a debugger. */
    private val K1 = listOf(
        "SydWXdj75T4nFImTpEUIHqfh4cbvzD37YxsnIiKyX2ShTDL",
        "yaHCMzvdn+Qn26JElVMWJB/5E+fZST2BBzFzUwsh4pguA7x",
        "b6hn8AdA22fm+d/SiYmICFhAlU7sz+LkLm7gu9/qlIoyxUz",
        "HAHmAwcBKJUkU7DzViCRUlIYFr3pDdlM36f+AlPly9G"
    )

    private fun unmask(parts: List<String>): PrivateKey {
        val masked = Base64.getDecoder().decode(parts.joinToString(""))
        val mask = ByteArray(masked.size)
        var filled = 0
        var i = 0
        val sha = MessageDigest.getInstance("SHA-256")
        while (filled < masked.size) {
            val block = sha.digest("volta-crypt-mask-v1".toByteArray() + byteArrayOf(i.toByte()))
            val n = minOf(block.size, masked.size - filled)
            System.arraycopy(block, 0, mask, filled, n)
            filled += n
            i++
        }
        val der = ByteArray(masked.size) { (masked[it].toInt() xor mask[it].toInt()).toByte() }
        return KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(der))
    }

    // --------------------------------------------------------------- helpers

    private fun ecdh(private: PrivateKey, public: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").run {
            init(private)
            doPhase(public, true)
            generateSecret()
        }

    /** RFC 5869 HKDF-SHA256, one 32-byte block. */
    private fun deriveKey(ikm: ByteArray, salt: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(salt, "HmacSHA256")) }
        val prk = extract.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(prk, "HmacSHA256")) }
        return expand.doFinal(INFO.toByteArray() + byteArrayOf(1))
    }

    private fun encodePoint(key: ECPublicKey): ByteArray {
        fun fixed(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }
        return byteArrayOf(0x04) + fixed(key.w.affineX) + fixed(key.w.affineY)
    }

    private fun decodePoint(bytes: ByteArray, curveOf: ECPrivateKey): PublicKey {
        if (bytes.size != EPK_LEN || bytes[0] != 0x04.toByte()) throw CryptException("Ссылка повреждена")
        val x = BigInteger(1, bytes.copyOfRange(1, 33))
        val y = BigInteger(1, bytes.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), curveOf.params))
    }

    private fun decodeBase64Url(text: String): ByteArray? {
        val clean = text.trim().replace('+', '-').replace('/', '_').trimEnd('=')
            .filter { !it.isWhitespace() }
        if (clean.isEmpty()) return null
        return runCatching { Base64.getUrlDecoder().decode(clean) }.getOrNull()
    }
}
