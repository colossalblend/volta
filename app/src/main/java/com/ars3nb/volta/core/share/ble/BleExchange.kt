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
import com.ars3nb.volta.core.share.ShareHandshake
import com.ars3nb.volta.core.share.SharePeer
import com.ars3nb.volta.core.share.ShareProtocol
import java.util.UUID

/**
 * The exchange of [ShareProtocol] on top of the radio of [BleSession], the same for both
 * layouts: when this side serves it is the [ShareHandshake.Responder], when it connects
 * the [ShareHandshake.Initiator]. Once the exchange is done ([onExchanged]) the sender and
 * the receiver only [send] and [sealAndSend] messages and get them in [onMessage],
 * whichever way round the radio is.
 */
@SuppressLint("MissingPermission")
abstract class BleExchange(context: Context) : BleSession(context) {

    /** The session id of the exchange: the one the serving side advertised. */
    protected var sessionId: ByteArray = ByteArray(0)
        private set

    /** The other device, from the moment it is chosen or bound. */
    protected var peer: SharePeer? = null
        private set

    /** The exchange's outcome: the code and the session key. */
    protected var result: ShareHandshake.Result? = null
        private set

    /** This side served (the peer connected to it); false: it connected to the peer. */
    private var serving = false

    // ---------------------------------------------------------------- hooks

    /** A HELLO may be taken now: this side is waiting to be found. */
    protected abstract fun acceptsHello(): Boolean

    /** The exchange is done: show [result]'s code. */
    protected abstract fun onExchanged(peer: SharePeer, result: ShareHandshake.Result)

    /** A message from the peer after the exchange. */
    protected abstract fun onMessage(type: Byte, body: ByteArray)

    /** Frames of a message from the bound peer are coming in. */
    protected open fun onIncoming(received: Int, total: Int) = Unit

    /** The bound peer sent more than any message can be. */
    protected open fun onTooLarge() = fail(ShareFailure.TOO_LARGE)

    /** The bound or chosen peer went away. */
    protected open fun onPeerLost() = fail(ShareFailure.CONNECT)

    /** The server or its advertising could not start. */
    protected open fun onServeFailed() = fail(ShareFailure.ADVERTISE)

    // ---------------------------------------------------- serving: responder

    private var responder: ShareHandshake.Responder? = null
    private var serveRole: Byte = 0
    private var bound: BluetoothDevice? = null

    /** Before anyone is bound: the one device whose frames are being collected. */
    private var writer: String? = null
    private val uplink = ShareFraming.Assembler(ShareProtocol.MAX_MESSAGE)

    protected val isServing: Boolean get() = responder != null

    /** Opens the GATT server with a fresh responder for [sid] and advertises [role]. */
    protected fun serve(role: Byte, sid: ByteArray) {
        stopServing()
        sessionId = sid
        serveRole = role
        responder = ShareHandshake.Responder(sid)
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
                    ShareProtocol.UPLINK,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
            )
            addCharacteristic(
                BluetoothGattCharacteristic(
                    ShareProtocol.DOWNLINK,
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
        if (!openServer(service)) {
            responder = null
            onServeFailed()
        }
    }

    /** Stops advertising and closes the server; the responder's keys go with it. */
    protected fun stopServing() {
        stopAdvertising()
        closeServer()
        responder = null
        writer = null
        uplink.reset()
    }

    override fun onServerReady(ok: Boolean) {
        if (!ok) {
            responder = null
            return onServeFailed()
        }
        if (acceptsHello()) startAdvertising(serveRole, sessionId)
    }

    override fun onAdvertiseFailed() = onServeFailed()

    override fun onServerRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: UUID) {
        val published = responder?.published
        if (uuid != ShareProtocol.KEY || published == null) {
            return respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)
        }
        if (offset > published.size) return respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
        respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, published.copyOfRange(offset, published.size))
    }

    override fun onServerWrite(device: BluetoothDevice, uuid: UUID, value: ByteArray): (() -> Unit)? {
        if (uuid != ShareProtocol.UPLINK || current.terminal) return null
        val b = bound
        if (b != null) {
            if (b.address != device.address) return null
        } else {
            if (!acceptsHello() || responder?.spent != false) return null
            val w = writer
            if (w != null && w != device.address) return null
            writer = device.address
        }
        return { serverFrame(device, value) }
    }

    private fun serverFrame(device: BluetoothDevice, value: ByteArray) {
        val isBound = bound != null
        when (val step = uplink.add(value)) {
            is ShareFraming.Step.Partial -> if (isBound) onIncoming(step.received, step.total)
            is ShareFraming.Step.Complete -> {
                writer = null
                serverMessage(device, step.message)
            }
            // Garbage from a stranger before the exchange is just dropped.
            is ShareFraming.Step.Broken -> {
                writer = null
                if (isBound) fail(ShareFailure.DAMAGED)
            }
            ShareFraming.Step.TooLarge -> {
                writer = null
                if (isBound) onTooLarge()
            }
        }
    }

    private fun serverMessage(device: BluetoothDevice, message: ByteArray) {
        if (message.isEmpty()) return
        val type = message[0]
        val body = message.copyOfRange(1, message.size)
        if (bound != null) return onMessage(type, body)
        if (type != ShareProtocol.MSG_HELLO || !acceptsHello()) return
        val r = responder ?: return
        val (reveal, res) = try {
            r.answer(body)
        } catch (e: ShareCrypto.ShareCryptoException) {
            // Not a valid HELLO: nothing was revealed, keep waiting for a real one.
            return
        }
        // From here this session belongs to [device]: one exchange per session.
        bound = device
        serving = true
        val p = peerOf(device)
        peer = p
        result = res
        stopScan()
        stopAdvertising()
        notifyFrames(
            device, ShareProtocol.DOWNLINK,
            ShareFraming.split(ShareProtocol.message(ShareProtocol.MSG_REVEAL, reveal), mtuOf(device))
        ) {}
        onExchanged(p, res)
    }

    override fun onServerDisconnected(device: BluetoothDevice) {
        if (device.address == bound?.address) {
            if (!current.terminal) onPeerLost()
        } else if (device.address == writer) {
            writer = null
            uplink.reset()
        }
    }

    // ------------------------------------------------- connecting: initiator

    private var initiator: ShareHandshake.Initiator? = null
    private var keyChar: BluetoothGattCharacteristic? = null
    private var upChar: BluetoothGattCharacteristic? = null
    private var downChar: BluetoothGattCharacteristic? = null
    private val downlink = ShareFraming.Assembler(ShareProtocol.MAX_MESSAGE)

    /** Connects to [p], whose advert gave the session id; the exchange follows by itself. */
    protected fun connectTo(p: SharePeer) {
        sessionId = p.sessionId
        peer = p
        serving = false
        initiator = null
        result = null
        downlink.reset()
        connect(p)
    }

    override fun onClientReady(service: BluetoothGattService) {
        keyChar = service.getCharacteristic(ShareProtocol.KEY)
        upChar = service.getCharacteristic(ShareProtocol.UPLINK)
        val down = service.getCharacteristic(ShareProtocol.DOWNLINK)
        downChar = down
        if (keyChar == null || upChar == null || down == null) return fail(ShareFailure.NOT_VOLTA)
        // Listen first, so the reveal cannot be missed.
        if (!enableNotifications(down)) fail(ShareFailure.CONNECT)
    }

    override fun onClientDescriptorWritten(ok: Boolean) {
        val key = keyChar ?: return
        if (initiator != null) return
        if (!ok || !read(key)) fail(ShareFailure.CONNECT)
    }

    override fun onClientRead(uuid: UUID, value: ByteArray, ok: Boolean) {
        if (uuid != ShareProtocol.KEY || initiator != null || result != null) return
        if (!ok) return fail(ShareFailure.NOT_VOLTA)
        val i = try {
            ShareHandshake.Initiator(value, sessionId)
        } catch (e: ShareCrypto.ShareCryptoException) {
            return fail(ShareFailure.NOT_VOLTA)
        }
        initiator = i
        val up = upChar ?: return fail(ShareFailure.CONNECT)
        writeFrames(up, ShareFraming.split(ShareProtocol.message(ShareProtocol.MSG_HELLO, i.hello), clientMtu)) {}
    }

    override fun onClientNotified(uuid: UUID, value: ByteArray) {
        if (uuid != ShareProtocol.DOWNLINK || current.terminal) return
        when (val step = downlink.add(value)) {
            is ShareFraming.Step.Partial -> if (result != null) onIncoming(step.received, step.total)
            is ShareFraming.Step.Complete -> clientMessage(step.message)
            is ShareFraming.Step.Broken -> fail(if (result != null) ShareFailure.DAMAGED else ShareFailure.NOT_VOLTA)
            ShareFraming.Step.TooLarge -> if (result != null) onTooLarge() else fail(ShareFailure.NOT_VOLTA)
        }
    }

    private fun clientMessage(message: ByteArray) {
        if (message.isEmpty()) return
        val type = message[0]
        val body = message.copyOfRange(1, message.size)
        if (result != null) return onMessage(type, body)
        if (type != ShareProtocol.MSG_REVEAL) return fail(ShareFailure.NOT_VOLTA)
        val i = initiator ?: return fail(ShareFailure.NOT_VOLTA)
        val res = try {
            i.finish(body)
        } catch (e: ShareCrypto.ShareCryptoException) {
            // The nonce does not open the commitment: someone answered in between.
            return fail(ShareFailure.COMMITMENT)
        }
        result = res
        connected()
        onExchanged(peer ?: return fail(ShareFailure.CONNECT), res)
    }

    override fun onClientDisconnected() {
        if (!current.terminal) onPeerLost()
    }

    // ------------------------------------------------------------ messages

    /** One message to the peer, over whichever link the exchange used. */
    protected fun send(type: Byte, body: ByteArray = ByteArray(0), done: () -> Unit = {}) {
        val message = ShareProtocol.message(type, body)
        if (serving) {
            val d = bound ?: return fail(ShareFailure.CONNECT)
            notifyFrames(d, ShareProtocol.DOWNLINK, ShareFraming.split(message, mtuOf(d)), done)
        } else {
            val up = upChar ?: return fail(ShareFailure.CONNECT)
            writeFrames(up, ShareFraming.split(message, clientMtu), done)
        }
    }

    /** [plaintext] sealed with the session key as a message of [type]. */
    protected fun sealAndSend(type: Byte, plaintext: ByteArray, done: () -> Unit = {}) {
        val key = result?.key ?: return fail(ShareFailure.CONNECT)
        send(type, ShareCrypto.seal(plaintext, key, sessionId, type), done)
    }

    /** The plaintext of a sealed [body] of [type]; null when it does not open. */
    protected fun opened(type: Byte, body: ByteArray): ByteArray? {
        val key = result?.key ?: return null
        return try {
            ShareCrypto.open(body, key, sessionId, type)
        } catch (e: ShareCrypto.ShareCryptoException) {
            null
        }
    }
}
