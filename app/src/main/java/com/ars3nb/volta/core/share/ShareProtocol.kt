package com.ars3nb.volta.core.share

import java.util.UUID

/**
 * Sending a subscription or server link to a device nearby over Bluetooth Low Energy:
 * phone → Android TV (where typing a link with the remote is painful), or phone → a
 * friend's phone. Everything about the protocol that is not Android lives here and in
 * [ShareHandshake] / [ShareCrypto] / [ShareFraming], so it is tested on the JVM; the radio
 * work is in core/share/ble and stays thin. docs/ble-share.md tells the same story for
 * people.
 *
 * ## Two layouts on the radio, one exchange
 *
 * Whoever is found serves: it opens a GATT server with [SERVICE] and advertises it with
 * service data [advert] (version, role, session id); its name goes in the scan response.
 * Normally that is the **receiver** («Получить по Bluetooth», role [ROLE_RECEIVER]). A
 * receiver that cannot advertise (`isMultipleAdvertisementSupported == false`, common on
 * TV boxes) scans instead, and the **sender** («Отправить по Bluetooth») serves with role
 * [ROLE_SENDER] while its search is open. The other side scans, connects, asks for MTU
 * [MTU_WANTED] and talks to three characteristics:
 *
 * | characteristic | the server (responder)           | the client (initiator)            |
 * |----------------|----------------------------------|-----------------------------------|
 * | [KEY]          | read: its key ‖ commitment       | reads it after subscribing        |
 * | [UPLINK]       | write: messages from the client  | writes framed messages, in order  |
 * | [DOWNLINK]     | notify: messages to the client   | subscribes (CCCD) first           |
 *
 * A message is one [type][MSG_HELLO] byte and a body, cut into [ShareFraming] frames.
 *
 * ## The exchange (both layouts)
 *
 * The server is the responder, the client the initiator, whichever of them sends the
 * link ([ShareHandshake]):
 * 1. the responder publishes `PKb ‖ Cb`, `Cb = SHA-256(PKb ‖ Nb)`, on [KEY];
 * 2. the initiator reads it and writes [MSG_HELLO] `PKa ‖ Na`;
 * 3. the responder answers [MSG_REVEAL] `Nb`, once per session; the initiator checks Cb;
 * 4. both show the 6-digit code from `SHA-256(PKa ‖ PKb ‖ Na ‖ Nb ‖ sid)`, where sid is the
 *    session id the server advertised;
 * 5. after «Совпадает» on the sender, the link goes as [MSG_LINK], sealed with the session
 *    key (ECDH of the two keys, HKDF with both nonces), and the receiver answers
 *    [MSG_STATUS], sealed the same way. «Не совпадает» sends [MSG_CANCEL].
 *
 * Neither side can pick its key or nonce after seeing the other's, so a man in the middle
 * gets one blind guess at the code per session (1 in 10⁶). The receiver offers to add the
 * link through the ordinary import path (a hidden subscription stays hidden).
 *
 * ## Lifetime
 *
 * A session ends after [SESSION_MS]; keys and the link live only in memory, nothing is
 * stored, and the GATT connection, server, advertising and scanning are all closed when
 * the sheet closes or the session ends.
 */
object ShareProtocol {

    /**
     * Protocol version: the first byte of the advert and of a sealed message. 0x02 is the
     * exchange with a commitment; 0x01 (code from the receiver's key alone) was never
     * released, and a v1 device neither finds nor is found by this one.
     */
    const val VERSION: Byte = 0x02

    val SERVICE: UUID = UUID.fromString("06ba13c2-685e-4ebd-9c68-0549e59b33a2")
    val KEY: UUID = UUID.fromString("06ba13c3-685e-4ebd-9c68-0549e59b33a2")
    val UPLINK: UUID = UUID.fromString("06ba13c4-685e-4ebd-9c68-0549e59b33a2")
    val DOWNLINK: UUID = UUID.fromString("06ba13c5-685e-4ebd-9c68-0549e59b33a2")

    /** Client Characteristic Configuration descriptor (Bluetooth SIG). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Who is serving: a receiver waiting (normal) or a sender (receiver can't advertise). */
    const val ROLE_RECEIVER: Byte = 'R'.code.toByte()
    const val ROLE_SENDER: Byte = 'S'.code.toByte()

    const val SESSION_ID_LEN = 4

    /** Message types, the first byte of every message on [UPLINK] / [DOWNLINK]. */
    const val MSG_HELLO: Byte = 0x01
    const val MSG_REVEAL: Byte = 0x02
    const val MSG_LINK: Byte = 0x03
    const val MSG_STATUS: Byte = 0x04
    const val MSG_CANCEL: Byte = 0x05

    /** A session, from opening the sheet, lasts this long. */
    const val SESSION_MS = 120_000L

    /** One scan for devices nearby. */
    const val SCAN_MS = 15_000L

    /** Connecting, MTU, discovery, the key and the reveal, all together. */
    const val CONNECT_MS = 15_000L

    /** From the last frame written to the receiver's answer. */
    const val ACK_MS = 10_000L

    /** Asked for after connecting; the largest ATT allows. */
    const val MTU_WANTED = 517

    /** A link, a server list or a JSON config; anything bigger is not for Bluetooth. */
    const val MAX_PLAINTEXT = 24 * 1024

    /** The largest message: a sealed [MAX_PLAINTEXT] with its type, header and tag. */
    const val MAX_MESSAGE = MAX_PLAINTEXT + 128

    /** Status bytes inside [MSG_STATUS]. */
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

    /** One message: its type byte, then [body]. */
    fun message(type: Byte, body: ByteArray = ByteArray(0)): ByteArray = byteArrayOf(type) + body

    /** A short tag for a device that sent no name: the session id in hex, 4 characters. */
    fun shortTag(sessionId: ByteArray): String =
        sessionId.take(2).joinToString("") {
            Integer.toHexString(it.toInt() and 0xFF).padStart(2, '0').uppercase(java.util.Locale.ROOT)
        }
}
