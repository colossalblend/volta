package com.ars3nb.volta.core.share.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.content.Context
import com.ars3nb.volta.core.share.ShareCrypto
import com.ars3nb.volta.core.share.ShareFailure
import com.ars3nb.volta.core.share.ShareFraming
import com.ars3nb.volta.core.share.SharePeer
import com.ars3nb.volta.core.share.ShareProtocol
import com.ars3nb.volta.core.share.ShareState
import java.util.UUID

/**
 * «Отправить по Bluetooth»: finds receivers, reads the chosen one's key, waits for the
 * user to compare the code, then sends [payload] encrypted to it. While searching it
 * also serves and advertises (if the radio can), so a receiver that cannot advertise
 * finds it instead (see [ShareProtocol]). [payload] lives only in this object.
 */
@SuppressLint("MissingPermission")
class BleSender(context: Context, payload: String) : BleSession(context) {

    private val plaintext = payload.toByteArray(Charsets.UTF_8)

    /** Ours, advertised for the fallback. */
    private val ownSessionId = ShareCrypto.newSessionId()

    private var target: SharePeer? = null
    private var receiverKey: ByteArray? = null
    private var sessionId: ByteArray? = null

    // Normal: we connected to the receiver.
    private var keyChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null

    // Fallback: the receiver connected to us.
    private var viaServer: BluetoothDevice? = null
    private var keyFrom: String? = null
    private val keyAssembler = ShareFraming.Assembler(ShareCrypto.KEY_LEN)

    override fun begin() {
        if (plaintext.size > ShareProtocol.MAX_PLAINTEXT) return fail(ShareFailure.TOO_LARGE)
        startScan(ShareProtocol.ROLE_RECEIVER)
        if (BleSupport.canAdvertise(app)) serveFallback()
    }

    override fun onPeersChanged(finished: Boolean) {
        if (current is ShareState.Starting || current is ShareState.Searching) {
            set(ShareState.Searching(peers.values.toList(), finished, asReceiver = false))
        }
    }

    fun rescan() = post {
        if (current !is ShareState.Searching) return@post
        startScan(ShareProtocol.ROLE_RECEIVER)
        if (server == null && BleSupport.canAdvertise(app)) serveFallback()
    }

    /** Connect to the receiver the user picked. */
    fun select(peer: SharePeer) = post {
        if (current !is ShareState.Searching) return@post
        stopScan()
        stopAdvertising()
        target = peer
        sessionId = peer.sessionId
        set(ShareState.Connecting(peer))
        connect(peer)
    }

    /** The user compared the codes: «Совпадает» ([match]) or «Не совпадает». */
    fun confirm(match: Boolean) = post {
        val confirming = current as? ShareState.Confirm ?: return@post
        if (!match) return@post fail(ShareFailure.MISMATCH)
        val key = receiverKey ?: return@post fail(ShareFailure.CONNECT)
        val sid = sessionId ?: return@post fail(ShareFailure.CONNECT)
        val message = ShareCrypto.seal(plaintext, key, sid)
        set(ShareState.Transferring(confirming.peer, 0f, sending = true))
        val via = viaServer
        if (via != null) {
            if (!isSubscribed(via)) return@post fail(ShareFailure.CONNECT)
            notifyFrames(via, ShareProtocol.DATA, ShareFraming.split(message, mtuOf(via))) { awaitAnswer() }
        } else {
            val data = dataChar ?: return@post fail(ShareFailure.CONNECT)
            writeFrames(data, ShareFraming.split(message, clientMtu)) { awaitAnswer() }
        }
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

    private fun answered(value: ByteArray) {
        if (current !is ShareState.Transferring) return
        handler.removeCallbacks(answerTimeout)
        when (value.firstOrNull()) {
            ShareProtocol.STATUS_OK -> finish(ShareState.Sent(target ?: return))
            ShareProtocol.STATUS_TOO_LARGE -> fail(ShareFailure.TOO_LARGE)
            else -> fail(ShareFailure.REJECTED)
        }
    }

    override fun onFinished() {
        handler.removeCallbacks(answerTimeout)
    }

    // ------------------------------------------- normal: we are the client

    override fun onClientReady(service: BluetoothGattService) {
        keyChar = service.getCharacteristic(ShareProtocol.KEY)
        dataChar = service.getCharacteristic(ShareProtocol.DATA)
        val status = service.getCharacteristic(ShareProtocol.STATUS)
        if (keyChar == null || dataChar == null || status == null) return fail(ShareFailure.NOT_VOLTA)
        // Subscribe to the answer before anything is sent.
        if (!enableNotifications(status)) fail(ShareFailure.CONNECT)
    }

    override fun onClientDescriptorWritten(ok: Boolean) {
        val key = keyChar ?: return
        if (!ok || !read(key)) fail(ShareFailure.CONNECT)
    }

    override fun onClientRead(uuid: UUID, value: ByteArray, ok: Boolean) {
        if (uuid != ShareProtocol.KEY || current !is ShareState.Connecting) return
        val peer = target ?: return
        val sid = sessionId ?: return
        if (!ok || !ShareCrypto.isValidKey(value)) return fail(ShareFailure.NOT_VOLTA)
        connected()
        receiverKey = value
        set(ShareState.Confirm(peer, ShareCrypto.confirmationCode(value, sid)))
    }

    override fun onClientNotified(uuid: UUID, value: ByteArray) {
        if (uuid == ShareProtocol.STATUS) answered(value)
    }

    override fun onClientDisconnected() {
        if (viaServer == null && !current.terminal && current !is ShareState.Searching) fail(ShareFailure.CONNECT)
    }

    // --------------------------------------- fallback: we serve, it connects

    private fun serveFallback() {
        val service = BluetoothGattService(ShareProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.KEY,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.DATA,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ
                ).apply {
                    addDescriptor(
                        BluetoothGattDescriptor(
                            ShareProtocol.CCCD,
                            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
                        )
                    )
                }
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.STATUS,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
        }
        // The fallback is a bonus: if it cannot start, the normal search still runs.
        openServer(service)
    }

    override fun onServerReady(ok: Boolean) {
        if (!ok) return closeServer()
        if (current is ShareState.Starting || current is ShareState.Searching) {
            startAdvertising(ShareProtocol.ROLE_SENDER, ownSessionId)
        }
    }

    override fun onAdvertiseFailed() = closeServer()

    override fun onServerWrite(device: BluetoothDevice, uuid: UUID, value: ByteArray): (() -> Unit)? = when (uuid) {
        ShareProtocol.KEY ->
            if (current is ShareState.Searching && (keyFrom == null || keyFrom == device.address)) {
                keyFrom = device.address
                ({ keyFrame(device, value) })
            } else null
        ShareProtocol.STATUS ->
            if (viaServer?.address == device.address) ({ answered(value) }) else null
        else -> null
    }

    private fun keyFrame(device: BluetoothDevice, value: ByteArray) {
        when (val step = keyAssembler.add(value)) {
            is ShareFraming.Step.Partial -> Unit
            is ShareFraming.Step.Complete -> {
                keyFrom = null
                if (current !is ShareState.Searching || !ShareCrypto.isValidKey(step.message)) return
                stopScan()
                stopAdvertising()
                val peer = peerOf(device)
                viaServer = device
                target = peer
                receiverKey = step.message
                sessionId = ownSessionId
                set(ShareState.Confirm(peer, ShareCrypto.confirmationCode(step.message, ownSessionId)))
            }
            is ShareFraming.Step.Broken, ShareFraming.Step.TooLarge -> keyFrom = null
        }
    }

    override fun onServerDisconnected(device: BluetoothDevice) {
        if (device.address == viaServer?.address) {
            if (!current.terminal) fail(ShareFailure.CONNECT)
        } else if (device.address == keyFrom) {
            keyFrom = null
            keyAssembler.reset()
        }
    }
}
