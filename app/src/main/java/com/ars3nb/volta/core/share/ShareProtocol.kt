package com.ars3nb.volta.core.share

import java.util.UUID

/**
 * Sending a subscription or server link to a device nearby over Bluetooth Low Energy:
 * phone → Android TV (where typing a link with the remote is painful), or phone → a
 * friend's phone. Everything about the protocol that is not Android lives here and in
 * [ShareCrypto] / [ShareFraming], so it is tested on the JVM; the radio work is in
 * core/share/ble and stays thin. docs/ble-share.md tells the same story for people.
 *
 * ## Roles
 *
 * The **receiver** («Получить по Bluetooth», usually the TV) makes a fresh P-256 key pair
 * and a random 4-byte session id for every session, and shows a 4-digit code,
 * [ShareCrypto.confirmationCode] = SHA-256(receiver public key ‖ session id) → 0000…9999.
 * The **sender** («Отправить по Bluetooth») finds it, reads its public key, shows the
 * same code for the user to compare (a key swapped in by someone else gives another
 * code; docs/ble-share.md says where this check ends), and only after «Совпадает»
 * encrypts the link to that key
 * ([ShareCrypto.seal]: ECIES, ephemeral P-256, HKDF-SHA256, AES-256-GCM, session id in the
 * AAD) and writes it. The receiver decrypts, answers with [STATUS_OK] and offers to add
 * the link through the ordinary import path (a hidden subscription stays hidden).
 *
 * ## Normal mode: the receiver serves
 *
 * The receiver opens a GATT server with [SERVICE] and advertises it with service data
 * [advert] (version, [ROLE_RECEIVER], session id); its name goes in the scan response.
 * Plain advertising cannot carry an encrypted link (31 bytes), so it is only for
 * discovery; the link goes over GATT:
 *
 * | characteristic | receiver serves              | sender does                           |
 * |----------------|------------------------------|---------------------------------------|
 * | [KEY]          | read: 65-byte public key     | reads it after MTU 517 is asked       |
 * | [DATA]         | write: framed ciphertext     | writes [ShareFraming] frames in order |
 * | [STATUS]       | notify: one status byte      | subscribes (CCCD) before writing      |
 *
 * ## Fallback: the receiver cannot advertise
 *
 * Cheap TV boxes often report `isMultipleAdvertisementSupported == false`. Then the
 * roles on the radio swap and the crypto stays: the sender advertises [ROLE_SENDER] with
 * its own session id and serves; the receiver scans, connects and
 *
 * | characteristic | sender serves                | receiver does                         |
 * |----------------|------------------------------|---------------------------------------|
 * | [KEY]          | write: framed public key     | writes its 65-byte public key         |
 * | [DATA]         | notify: framed ciphertext    | subscribes (CCCD) first               |
 * | [STATUS]       | write: one status byte       | writes [STATUS_OK] after decrypting   |
 *
 * Both sides show the code from (receiver key ‖ the sender's session id). The sender
 * advertises only while its own «Отправить» sheet is open, and only if it can.
 *
 * ## Lifetime
 *
 * A session ends after [SESSION_MS]; keys and the link live only in memory, nothing is
 * stored, and the GATT connection, server, advertising and scanning are all closed when
 * the sheet closes or the session ends.
 */
object ShareProtocol {

    /** Protocol version, first byte of the advert and of an encrypted message. */
    const val VERSION: Byte = 0x01

    val SERVICE: UUID = UUID.fromString("06ba13c2-685e-4ebd-9c68-0549e59b33a2")
    val KEY: UUID = UUID.fromString("06ba13c3-685e-4ebd-9c68-0549e59b33a2")
    val DATA: UUID = UUID.fromString("06ba13c4-685e-4ebd-9c68-0549e59b33a2")
    val STATUS: UUID = UUID.fromString("06ba13c5-685e-4ebd-9c68-0549e59b33a2")

    /** Client Characteristic Configuration descriptor (Bluetooth SIG). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Who is advertising: a receiver waiting (normal) or a sender serving (fallback). */
    const val ROLE_RECEIVER: Byte = 'R'.code.toByte()
    const val ROLE_SENDER: Byte = 'S'.code.toByte()

    const val SESSION_ID_LEN = 4

    /** A session, from opening the sheet, lasts this long. */
    const val SESSION_MS = 120_000L

    /** One scan for devices nearby. */
    const val SCAN_MS = 15_000L

    /** Connecting, MTU, discovery and reading the key, all together. */
    const val CONNECT_MS = 15_000L

    /** From the last frame written to the receiver's answer. */
    const val ACK_MS = 10_000L

    /** Asked for after connecting; the largest ATT allows. */
    const val MTU_WANTED = 517

    /** A link, a server list or a JSON config; anything bigger is not for Bluetooth. */
    const val MAX_PLAINTEXT = 24 * 1024

    /** Status bytes on [STATUS]. */
    const val STATUS_OK: Byte = 0x01
    const val STATUS_DAMAGED: Byte = 0x02
    const val STATUS_TOO_LARGE: Byte = 0x03

    /** The service data in an advert: version, role, session id (6 bytes). */
    fun advert(role: Byte, sessionId: ByteArray): ByteArray {
        require(sessionId.size == SESSION_ID_LEN) { "session id is $SESSION_ID_LEN bytes" }
        return byteArrayOf(VERSION, role) + sessionId
    }

    /** What a scan filter matches on: the first two bytes of [advert]. */
    fun advertPrefix(role: Byte): ByteArray = byteArrayOf(VERSION, role)

    data class Advert(val role: Byte, val sessionId: ByteArray) {
        override fun equals(other: Any?) =
            other is Advert && other.role == role && other.sessionId.contentEquals(sessionId)

        override fun hashCode() = 31 * role + sessionId.contentHashCode()
    }

    /** Reads [advert] back; null for anything else (another version, a stray device). */
    fun parseAdvert(data: ByteArray?): Advert? {
        if (data == null || data.size < 2 + SESSION_ID_LEN || data[0] != VERSION) return null
        val role = data[1]
        if (role != ROLE_RECEIVER && role != ROLE_SENDER) return null
        return Advert(role, data.copyOfRange(2, 2 + SESSION_ID_LEN))
    }

    /** A short tag for a device that sent no name: the session id in hex, 4 characters. */
    fun shortTag(sessionId: ByteArray): String =
        sessionId.take(2).joinToString("") {
            Integer.toHexString(it.toInt() and 0xFF).padStart(2, '0').uppercase(java.util.Locale.ROOT)
        }
}
