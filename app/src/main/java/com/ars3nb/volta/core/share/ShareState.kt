package com.ars3nb.volta.core.share

/** A Volta device nearby, as a scan found it or as it connected to us. */
data class SharePeer(
    /** Bluetooth address: identifies it for this session only. */
    val address: String,
    /** Its Bluetooth name, when it gave one. */
    val name: String?,
    /** A short tag to tell unnamed devices apart (from its session id or address). */
    val tag: String,
    /** The session id it advertised; empty for a peer that connected to us. */
    val sessionId: ByteArray = ByteArray(0),
    val rssi: Int = 0
) {
    override fun equals(other: Any?) = other is SharePeer && other.address == address &&
        other.name == name && other.rssi == rssi && other.sessionId.contentEquals(sessionId)

    override fun hashCode() = address.hashCode()
}

/** Why a session ended without delivering. */
enum class ShareFailure {
    BLUETOOTH_OFF,
    PERMISSION,
    ADVERTISE,
    SCAN,
    CONNECT,
    NOT_VOLTA,
    MISMATCH,
    DAMAGED,
    TOO_LARGE,
    REJECTED,
    TIMEOUT,
    EXPIRED
}

/** Where a Bluetooth sharing session is; the sheet draws exactly this. */
sealed interface ShareState {

    data object Starting : ShareState

    /**
     * Looking for the other device. A sender looks for receivers; a receiver that cannot
     * advertise ([asReceiver]) looks for senders. [finished]: the scan ended.
     */
    data class Searching(
        val peers: List<SharePeer>,
        val finished: Boolean,
        val asReceiver: Boolean
    ) : ShareState

    /**
     * The receiver shows its code: waiting to be found ([peer] null), or connected to a
     * sender in the fallback and waiting for it to confirm.
     */
    data class Waiting(val code: String, val peer: SharePeer?) : ShareState

    data class Connecting(val peer: SharePeer) : ShareState

    /** The sender compares the code with the receiver's screen. */
    data class Confirm(val peer: SharePeer, val code: String) : ShareState

    /** Frames on the way; [progress] 0..1. */
    data class Transferring(val peer: SharePeer?, val progress: Float, val sending: Boolean) : ShareState

    data class Sent(val peer: SharePeer) : ShareState

    /** The receiver has the link, in memory, and asks whether to add it. */
    class Received(val peer: SharePeer, val text: String) : ShareState {
        // Never log the link: it may be a hidden subscription.
        override fun toString() = "Received(peer=$peer)"
    }

    data class Failed(val reason: ShareFailure) : ShareState

    val terminal: Boolean get() = this is Sent || this is Received || this is Failed
}
