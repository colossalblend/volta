package com.ars3nb.volta.core.share.ble

import android.annotation.SuppressLint
import android.content.Context
import com.ars3nb.volta.core.share.ShareCrypto
import com.ars3nb.volta.core.share.ShareFailure
import com.ars3nb.volta.core.share.ShareHandshake
import com.ars3nb.volta.core.share.SharePeer
import com.ars3nb.volta.core.share.ShareProtocol
import com.ars3nb.volta.core.share.ShareState

/**
 * «Получить по Bluetooth»: waits for a link, opens it and hands it to the sheet as
 * [ShareState.Received]. Serves and advertises when the radio can advertise, otherwise
 * scans for a sender and connects to it (see [ShareProtocol]); the exchange and the code
 * are the same either way.
 */
@SuppressLint("MissingPermission")
class BleReceiver(context: Context) : BleExchange(context) {

    override fun begin() {
        if (BleSupport.canAdvertise(app)) {
            set(ShareState.Waiting(code = null, peer = null))
            serve(ShareProtocol.ROLE_RECEIVER, ShareCrypto.newSessionId())
        } else {
            startScan(ShareProtocol.ROLE_SENDER)
        }
    }

    /** Serving and not yet bound: waiting, with no code on screen. */
    override fun acceptsHello(): Boolean {
        val waiting = current as? ShareState.Waiting ?: return false
        return waiting.code == null && isServing
    }

    override fun onExchanged(peer: SharePeer, result: ShareHandshake.Result) {
        set(ShareState.Waiting(result.code, peer))
    }

    // ------------------------------------- the receiver cannot advertise: scan

    override fun onPeersChanged(finished: Boolean) {
        if (current is ShareState.Starting || current is ShareState.Searching) {
            set(ShareState.Searching(peers.values.toList(), finished, asReceiver = true))
        }
    }

    /** Scan again (the sheet's «Искать снова»). */
    fun rescan() = post { if (current is ShareState.Searching) startScan(ShareProtocol.ROLE_SENDER) }

    /** Connect to the sender the user picked. */
    fun select(peer: SharePeer) = post {
        if (current !is ShareState.Searching) return@post
        stopScan()
        set(ShareState.Connecting(peer))
        connectTo(peer)
    }

    // ------------------------------------------------------------- the link

    override fun onIncoming(received: Int, total: Int) {
        if (current is ShareState.Waiting || current is ShareState.Transferring) {
            set(ShareState.Transferring(peer, received.toFloat() / total, sending = false))
        }
    }

    override fun onMessage(type: Byte, body: ByteArray) {
        when (type) {
            ShareProtocol.MSG_LINK -> link(body)
            // The sender's user saw another code.
            ShareProtocol.MSG_CANCEL -> fail(ShareFailure.MISMATCH)
            else -> Unit
        }
    }

    private fun link(body: ByteArray) {
        val from = peer ?: return fail(ShareFailure.CONNECT)
        val plain = opened(ShareProtocol.MSG_LINK, body)
        if (plain == null) {
            answer(ShareProtocol.STATUS_DAMAGED)
            return fail(ShareFailure.DAMAGED, flushMs = FLUSH_MS)
        }
        if (plain.size > ShareProtocol.MAX_PLAINTEXT) {
            answer(ShareProtocol.STATUS_TOO_LARGE)
            return fail(ShareFailure.TOO_LARGE, flushMs = FLUSH_MS)
        }
        answer(ShareProtocol.STATUS_OK)
        finish(ShareState.Received(from, String(plain, Charsets.UTF_8)), flushMs = FLUSH_MS)
    }

    override fun onTooLarge() {
        answer(ShareProtocol.STATUS_TOO_LARGE)
        fail(ShareFailure.TOO_LARGE, flushMs = FLUSH_MS)
    }

    private fun answer(status: Byte) = sealAndSend(ShareProtocol.MSG_STATUS, byteArrayOf(status))

    private companion object {
        /** Long enough for the answer to get out before the connection closes. */
        const val FLUSH_MS = 1_000L
    }
}
