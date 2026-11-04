package com.ars3nb.volta.core.share.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
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
 * «Получить по Bluetooth»: waits for a link, decrypts it and hands it to the sheet as
 * [ShareState.Received]. Serves and advertises when the radio can advertise, otherwise
 * scans for a sender and connects to it (see [ShareProtocol]).
 */
@SuppressLint("MissingPermission")
class BleReceiver(context: Context) : BleSession(context) {

    private val keys = ShareCrypto.newKeyPair()
    private val publicKey = ShareCrypto.encodePublic(keys)

    /** Ours when serving; the sender's (from its advert) in the fallback. */
    private var sessionId = ShareCrypto.newSessionId()
    private val assembler = ShareFraming.Assembler(MAX_MESSAGE)

    /** Serving: the one device whose frames are being collected. */
    private var writer: BluetoothDevice? = null

    /** Fallback: the sender we connected to, and its characteristics. */
    private var sender: SharePeer? = null
    private var keyChar: BluetoothGattCharacteristic? = null
    private var dataChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null

    private fun code() = ShareCrypto.confirmationCode(publicKey, sessionId)

    override fun begin() {
        if (BleSupport.canAdvertise(app)) serve() else startScan(ShareProtocol.ROLE_SENDER)
    }

    // ------------------------------------------------------ normal: we serve

    private fun serve() {
        val service = BluetoothGattService(ShareProtocol.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.KEY,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.DATA,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.STATUS,
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
        }
        if (!openServer(service)) return fail(ShareFailure.ADVERTISE)
        set(ShareState.Waiting(code(), null))
    }

    override fun onServerReady(ok: Boolean) {
        if (!ok) return fail(ShareFailure.ADVERTISE)
        startAdvertising(ShareProtocol.ROLE_RECEIVER, sessionId)
    }

    override fun onServerRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: UUID) {
        if (uuid != ShareProtocol.KEY) return respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
        if (offset > publicKey.size) return respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, publicKey.copyOfRange(offset, publicKey.size))
    }

    override fun onServerWrite(device: BluetoothDevice, uuid: UUID, value: ByteArray): (() -> Unit)? {
        if (uuid != ShareProtocol.DATA || current.terminal) return null
        val busy = writer
        if (busy != null && busy.address != device.address) return null
        writer = device
        return { serverFrame(device, value) }
    }

    private fun serverFrame(device: BluetoothDevice, value: ByteArray) {
        val from = peerOf(device)
        when (val step = assembler.add(value)) {
            is ShareFraming.Step.Partial ->
                set(ShareState.Transferring(from, step.received.toFloat() / step.total, sending = false))
            is ShareFraming.Step.Complete -> {
                val ok = deliver(step.message, from) { notify(device, ShareProtocol.STATUS, byteArrayOf(it)) }
                // Garbage from someone nearby must not end the session: keep waiting.
                if (!ok) waitAgain()
            }
            is ShareFraming.Step.Broken -> waitAgain()
            ShareFraming.Step.TooLarge -> {
                notify(device, ShareProtocol.STATUS, byteArrayOf(ShareProtocol.STATUS_TOO_LARGE))
                waitAgain()
            }
        }
    }

    private fun waitAgain() {
        writer = null
        assembler.reset()
        if (!current.terminal) set(ShareState.Waiting(code(), null))
    }

    override fun onServerDisconnected(device: BluetoothDevice) {
        if (device.address == writer?.address && !current.terminal) waitAgain()
    }

    // ------------------------------------------- fallback: we scan for a sender

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
        sender = peer
        sessionId = peer.sessionId
        set(ShareState.Connecting(peer))
        connect(peer)
    }

    override fun onClientReady(service: BluetoothGattService) {
        keyChar = service.getCharacteristic(ShareProtocol.KEY)
        dataChar = service.getCharacteristic(ShareProtocol.DATA)
        statusChar = service.getCharacteristic(ShareProtocol.STATUS)
        val data = dataChar
        if (keyChar == null || data == null || statusChar == null) return fail(ShareFailure.NOT_VOLTA)
        // Listen first, so no frame of the link can be missed.
        if (!enableNotifications(data)) fail(ShareFailure.CONNECT)
    }

    override fun onClientDescriptorWritten(ok: Boolean) {
        val key = keyChar ?: return
        if (!ok) return fail(ShareFailure.CONNECT)
        writeFrames(key, ShareFraming.split(publicKey, clientMtu)) {
            connected()
            set(ShareState.Waiting(code(), sender))
        }
    }

    override fun onClientNotified(uuid: UUID, value: ByteArray) {
        if (uuid != ShareProtocol.DATA || current.terminal) return
        when (val step = assembler.add(value)) {
            is ShareFraming.Step.Partial ->
                set(ShareState.Transferring(sender, step.received.toFloat() / step.total, sending = false))
            is ShareFraming.Step.Complete -> {
                val from = sender ?: return fail(ShareFailure.CONNECT)
                if (!deliver(step.message, from) { answer(it) }) fail(ShareFailure.DAMAGED)
            }
            is ShareFraming.Step.Broken -> {
                answer(ShareProtocol.STATUS_DAMAGED)
                fail(ShareFailure.DAMAGED)
            }
            ShareFraming.Step.TooLarge -> {
                answer(ShareProtocol.STATUS_TOO_LARGE)
                fail(ShareFailure.TOO_LARGE)
            }
        }
    }

    private fun answer(status: Byte) {
        val ch = statusChar ?: return
        writeFrames(ch, listOf(byteArrayOf(status))) {}
    }

    override fun onClientDisconnected() {
        if (!current.terminal) fail(ShareFailure.CONNECT)
    }

    // ------------------------------------------------------------------ both

    /**
     * Opens [message]; on success answers [ShareProtocol.STATUS_OK] and ends the session
     * with the link in memory. False (after answering why) when it cannot be opened.
     */
    private fun deliver(message: ByteArray, from: SharePeer, answer: (Byte) -> Unit): Boolean {
        val plain = try {
            ShareCrypto.open(message, keys, sessionId)
        } catch (e: ShareCrypto.ShareCryptoException) {
            answer(ShareProtocol.STATUS_DAMAGED)
            return false
        }
        if (plain.size > ShareProtocol.MAX_PLAINTEXT) {
            answer(ShareProtocol.STATUS_TOO_LARGE)
            return false
        }
        answer(ShareProtocol.STATUS_OK)
        finish(ShareState.Received(from, String(plain, Charsets.UTF_8)), flushMs = 1_000)
        return true
    }

    private companion object {
        /** The largest plaintext, encrypted: header, nonce and tag on top. */
        const val MAX_MESSAGE = ShareProtocol.MAX_PLAINTEXT + 128
    }
}
