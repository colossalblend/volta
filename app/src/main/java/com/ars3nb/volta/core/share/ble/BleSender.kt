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
 * «Отправить по Bluetooth»: finds receivers, does the exchange with the chosen one, waits
 * for the user to compare the code, then sends [payload] sealed with the session key.
 * While searching it also serves and advertises (if the radio can), so a receiver that
 * cannot advertise finds it instead (see [ShareProtocol]). [payload] lives only here.
 */
@SuppressLint("MissingPermission")
class BleSender(context: Context, payload: String) : BleExchange(context) {

    private val plaintext = payload.toByteArray(Charsets.UTF_8)

    override fun begin() {
        if (plaintext.size > ShareProtocol.MAX_PLAINTEXT) return fail(ShareFailure.TOO_LARGE)
        startScan(ShareProtocol.ROLE_RECEIVER)
        serveFallback()
    }

    /** Serving is a bonus for receivers that cannot advertise; without it the search still runs. */
    private fun serveFallback() {
        if (BleSupport.canAdvertise(app)) serve(ShareProtocol.ROLE_SENDER, ShareCrypto.newSessionId())
    }

    override fun onServeFailed() = stopServing()

    override fun acceptsHello(): Boolean = current is ShareState.Starting || current is ShareState.Searching

    override fun onPeersChanged(finished: Boolean) {
        if (current is ShareState.Starting || current is ShareState.Searching) {
            set(ShareState.Searching(peers.values.toList(), finished, asReceiver = false))
        }
    }

    fun rescan() = post {
        if (current !is ShareState.Searching) return@post
        startScan(ShareProtocol.ROLE_RECEIVER)
        if (!isServing) serveFallback()
    }

    /** Connect to the receiver the user picked. */
    fun select(peer: SharePeer) = post {
        if (current !is ShareState.Searching) return@post
        stopScan()
        stopServing()
        set(ShareState.Connecting(peer))
        connectTo(peer)
    }

    override fun onExchanged(peer: SharePeer, result: ShareHandshake.Result) {
        set(ShareState.Confirm(peer, result.code))
    }

    /** The user compared the codes: «Совпадает» ([match]) or «Не совпадает». */
    fun confirm(match: Boolean) = post {
        val confirming = current as? ShareState.Confirm ?: return@post
        if (!match) {
            // Tell the receiver, so it says the same; nothing about the link goes out.
            send(ShareProtocol.MSG_CANCEL)
            return@post fail(ShareFailure.MISMATCH, flushMs = 600)
        }
        set(ShareState.Transferring(confirming.peer, 0f, sending = true))
        sealAndSend(ShareProtocol.MSG_LINK, plaintext) { awaitAnswer() }
    }

    override fun onFrameProgress(sent: Int, total: Int) {
        val t = current as? ShareState.Transferring ?: return
        set(t.copy(progress = sent.toFloat() / total))
    }

    private fun awaitAnswer() {
        handler.removeCallbacks(answerTimeout)
        handler.postDelayed(answerTimeout, ShareProtocol.ACK_MS)
    }

    private val answerTimeout = Runnable { guard { if (!current.terminal) fail(ShareFailure.TIMEOUT) } }

    override fun onMessage(type: Byte, body: ByteArray) {
        if (type != ShareProtocol.MSG_STATUS || current !is ShareState.Transferring) return
        handler.removeCallbacks(answerTimeout)
        val status = opened(ShareProtocol.MSG_STATUS, body)?.firstOrNull()
        when (status) {
            ShareProtocol.STATUS_OK -> finish(ShareState.Sent(peer ?: return fail(ShareFailure.CONNECT)))
            ShareProtocol.STATUS_TOO_LARGE -> fail(ShareFailure.TOO_LARGE)
            else -> fail(ShareFailure.REJECTED)
        }
    }

    override fun onFinished() {
        handler.removeCallbacks(answerTimeout)
    }
}
