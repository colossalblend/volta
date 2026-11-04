package com.ars3nb.volta.core.share.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.ars3nb.volta.core.share.ShareFailure
import com.ars3nb.volta.core.share.SharePeer
import com.ars3nb.volta.core.share.ShareProtocol
import com.ars3nb.volta.core.share.ShareState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The radio plumbing both sides of [ShareProtocol] share: scanning, advertising, a GATT
 * server and a GATT client, each sending frames in order.
 *
 * Threading: everything runs on one background thread of the session's own. GATT client
 * callbacks are delivered there directly; GATT server, scan and advertise callbacks
 * (which Android calls on binder threads or the main looper) only post to it. No state
 * is touched anywhere else, so there are no locks.
 *
 * Lifetime: [close] (the sheet's onDispose) stops scanning and advertising, closes the
 * GATT client and server, forgets the keys with the session and ends the thread. The
 * session also stops the radio on its own when it ends, fails or reaches
 * [ShareProtocol.SESSION_MS]. Permissions are checked by the sheet before a session is
 * made; a SecurityException (permission taken away meanwhile) ends the session.
 */
@SuppressLint("MissingPermission")
abstract class BleSession(context: Context) {

    protected val app: Context = context.applicationContext
    private val thread = HandlerThread("volta-ble").apply { start() }
    protected val handler = Handler(thread.looper)
    private val manager: BluetoothManager? = app.getSystemService(BluetoothManager::class.java)
    protected val adapter: BluetoothAdapter? = manager?.adapter

    private val _state = MutableStateFlow<ShareState>(ShareState.Starting)
    val state: StateFlow<ShareState> = _state.asStateFlow()
    protected val current: ShareState get() = _state.value

    @Volatile
    private var closed = false

    // ------------------------------------------------------------ lifecycle

    fun start() = post {
        ContextCompat.registerReceiver(
            app, adapterState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            null, handler, ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
        handler.postDelayed(expire, ShareProtocol.SESSION_MS)
        if (adapter?.isEnabled != true) fail(ShareFailure.BLUETOOTH_OFF) else begin()
    }

    /** Stops everything. Safe to call more than once, from any thread. */
    fun close() {
        if (closed) return
        closed = true
        handler.post {
            stopRadio()
            handler.removeCallbacksAndMessages(null)
            if (receiverRegistered) runCatching { app.unregisterReceiver(adapterState) }
            receiverRegistered = false
        }
        thread.quitSafely()
    }

    /** Starts the role's work; Bluetooth is on. Runs on the session thread. */
    protected abstract fun begin()

    private var receiverRegistered = false

    private val adapterState = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val s = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_ON)
            if (s == BluetoothAdapter.STATE_TURNING_OFF || s == BluetoothAdapter.STATE_OFF) {
                guard { fail(ShareFailure.BLUETOOTH_OFF) }
            }
        }
    }

    private val expire = Runnable { guard { if (!current.terminal) fail(ShareFailure.EXPIRED) } }

    protected fun set(state: ShareState) {
        _state.value = state
    }

    /** Ends the session with [state]; the connections close after [flushMs] so a last answer gets out. */
    protected fun finish(state: ShareState, flushMs: Long = 600) {
        set(state)
        stopScan()
        stopAdvertising()
        handler.removeCallbacks(expire)
        handler.removeCallbacks(connectTimeout)
        onFinished()
        handler.postDelayed({ closeClient(); closeServer() }, flushMs)
    }

    protected open fun onFinished() = Unit

    protected fun fail(reason: ShareFailure) {
        if (current.terminal) return
        finish(ShareState.Failed(reason), flushMs = 300)
    }

    private fun stopRadio() {
        stopScan()
        stopAdvertising()
        closeClient()
        closeServer()
    }

    /** Runs [block] on the session thread, unless the session is closed. */
    protected fun post(block: () -> Unit) {
        if (!closed) handler.post { guard(block) }
    }

    protected fun guard(block: () -> Unit) {
        if (closed) return
        try {
            block()
        } catch (e: SecurityException) {
            failQuietly(ShareFailure.PERMISSION)
        } catch (e: Exception) {
            failQuietly(ShareFailure.CONNECT)
        }
    }

    private fun failQuietly(reason: ShareFailure) {
        runCatching { fail(reason) }.onFailure { set(ShareState.Failed(reason)); runCatching { stopRadio() } }
    }

    protected fun peerOf(device: BluetoothDevice): SharePeer = SharePeer(
        address = device.address,
        name = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() },
        tag = device.address.replace(":", "").takeLast(4)
    )

    // ------------------------------------------------------------- scanning

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private var scanRole: Byte = 0
    protected val peers = LinkedHashMap<String, SharePeer>()

    /** Called when the list of found devices changed, or the scan ended ([finished]). */
    protected abstract fun onPeersChanged(finished: Boolean)

    /** Filtered, low-latency scan for [role] adverts, for [ShareProtocol.SCAN_MS]. */
    protected fun startScan(role: Byte) {
        stopScan()
        val s = adapter?.bluetoothLeScanner ?: return fail(ShareFailure.SCAN)
        peers.clear()
        scanRole = role
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = post { found(this, result) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                val copy = results.toList()
                post { copy.forEach { found(this, it) } }
            }
            override fun onScanFailed(errorCode: Int) = post {
                if (scanCallback === this && errorCode != SCAN_FAILED_ALREADY_STARTED) fail(ShareFailure.SCAN)
            }
        }
        val mask = byteArrayOf(-1, -1)
        val filter = ScanFilter.Builder()
            .setServiceData(ParcelUuid(ShareProtocol.SERVICE), ShareProtocol.advertPrefix(role), mask)
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        scanner = s
        scanCallback = cb
        s.startScan(listOf(filter), settings, cb)
        onPeersChanged(finished = false)
        handler.removeCallbacks(scanTimeout)
        handler.postDelayed(scanTimeout, ShareProtocol.SCAN_MS)
    }

    private val scanTimeout = Runnable {
        guard {
            if (scanCallback != null) {
                stopScan()
                onPeersChanged(finished = true)
            }
        }
    }

    private fun found(from: ScanCallback, r: ScanResult) {
        if (scanCallback !== from) return
        val record = r.scanRecord
        val ad = ShareProtocol.parseAdvert(record?.getServiceData(ParcelUuid(ShareProtocol.SERVICE))) ?: return
        if (ad.role != scanRole) return
        val address = r.device.address
        val old = peers[address]
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: runCatching { r.device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: old?.name
        val peer = SharePeer(address, name, ShareProtocol.shortTag(ad.sessionId), ad.sessionId, r.rssi)
        peers[address] = peer
        // A new device, a name that came with the scan response or a restarted session;
        // not every RSSI change, which would redraw the list several times a second.
        if (old == null || old.name != peer.name || !old.sessionId.contentEquals(peer.sessionId)) {
            onPeersChanged(finished = false)
        }
    }

    protected fun stopScan() {
        val s = scanner
        val cb = scanCallback
        scanner = null
        scanCallback = null
        handler.removeCallbacks(scanTimeout)
        if (s != null && cb != null) runCatching { s.stopScan(cb) }
    }

    // ---------------------------------------------------------- advertising

    private var advertiser: BluetoothLeAdvertiser? = null
    private var advertiseCallback: AdvertiseCallback? = null

    /**
     * Advertises [ShareProtocol.advert] as service data (27 of the 31 bytes, flags
     * included) and the device name in the scan response; a name too long for the scan
     * response is left out rather than failing.
     */
    protected fun startAdvertising(role: Byte, sessionId: ByteArray, withName: Boolean = true) {
        stopAdvertising()
        val adv = adapter?.bluetoothLeAdvertiser ?: return onAdvertiseFailed()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(ParcelUuid(ShareProtocol.SERVICE), ShareProtocol.advert(role, sessionId))
            .build()
        val response = AdvertiseData.Builder()
            .setIncludeDeviceName(withName)
            .setIncludeTxPowerLevel(false)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) = Unit
            override fun onStartFailure(errorCode: Int) {
                val self = this
                post {
                    if (advertiseCallback !== self) return@post
                    advertiseCallback = null
                    advertiser = null
                    when (errorCode) {
                        ADVERTISE_FAILED_DATA_TOO_LARGE ->
                            if (withName) startAdvertising(role, sessionId, withName = false) else onAdvertiseFailed()
                        ADVERTISE_FAILED_ALREADY_STARTED -> Unit
                        else -> onAdvertiseFailed()
                    }
                }
            }
        }
        advertiser = adv
        advertiseCallback = cb
        adv.startAdvertising(settings, data, response, cb)
    }

    protected open fun onAdvertiseFailed() = fail(ShareFailure.ADVERTISE)

    protected fun stopAdvertising() {
        val a = advertiser
        val cb = advertiseCallback
        advertiser = null
        advertiseCallback = null
        if (a != null && cb != null) runCatching { a.stopAdvertising(cb) }
    }

    // ---------------------------------------------------------- GATT server

    protected var server: BluetoothGattServer? = null
        private set
    private val serverMtu = HashMap<String, Int>()
    private val subscribed = HashSet<String>()

    /** Opens the server with [service]; [onServerReady] follows once Android has added it. */
    protected fun openServer(service: BluetoothGattService): Boolean {
        closeServer()
        val s = manager?.openGattServer(app, serverCallbacks) ?: return false
        server = s
        if (!s.addService(service)) {
            closeServer()
            return false
        }
        return true
    }

    protected fun closeServer() {
        val s = server ?: return
        server = null
        runCatching { s.clearServices() }
        runCatching { s.close() }
        serverMtu.clear()
        subscribed.clear()
        notifyQueue.clear()
    }

    protected fun mtuOf(device: BluetoothDevice): Int = serverMtu[device.address] ?: DEFAULT_MTU

    protected fun isSubscribed(device: BluetoothDevice): Boolean = device.address in subscribed

    protected open fun onServerReady(ok: Boolean) = Unit
    protected open fun onServerDisconnected(device: BluetoothDevice) = Unit

    /** A read of [uuid]: answer with [respond]. */
    protected open fun onServerRead(device: BluetoothDevice, requestId: Int, offset: Int, uuid: java.util.UUID) =
        respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED)

    /**
     * A write of [value] to [uuid]. Return what to do with it (run after the write is
     * acknowledged), or null to refuse it.
     */
    protected open fun onServerWrite(device: BluetoothDevice, uuid: java.util.UUID, value: ByteArray): (() -> Unit)? = null

    protected fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int = 0, value: ByteArray? = null) {
        runCatching { server?.sendResponse(device, requestId, status, offset, value) }
    }

    private val serverCallbacks = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService?) = post {
            if (server != null) onServerReady(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) = post {
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                serverMtu.remove(device.address)
                subscribed.remove(device.address)
                onServerDisconnected(device)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) = post { serverMtu[device.address] = mtu }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic
        ) = post { onServerRead(device, requestId, offset, characteristic.uuid) }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) = post {
            // Frames always fit one packet, so a long (prepared) write is not ours.
            val action = if (preparedWrite || offset != 0) null
            else onServerWrite(device, characteristic.uuid, value ?: ByteArray(0))
            if (responseNeeded) {
                respond(device, requestId, if (action != null) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED)
            }
            action?.invoke()
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) = post {
            respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?
        ) = post {
            if (descriptor.uuid != ShareProtocol.CCCD || preparedWrite) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED)
                return@post
            }
            if (value != null && value.isNotEmpty() && value[0].toInt() and 0x01 != 0) {
                subscribed += device.address
            } else {
                subscribed -= device.address
            }
            if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, descriptor: BluetoothGattDescriptor
        ) = post {
            val v = if (device.address in subscribed) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, v)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) = post { notified(device, status) }
    }

    /** One notification of [value] on [uuid] to [device]. */
    protected fun notify(device: BluetoothDevice, uuid: java.util.UUID, value: ByteArray): Boolean {
        val s = server ?: return false
        val ch = s.getService(ShareProtocol.SERVICE)?.getCharacteristic(uuid) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.notifyCharacteristicChanged(device, ch, false, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            s.notifyCharacteristicChanged(device, ch, false)
        }
    }

    private val notifyQueue = ArrayDeque<ByteArray>()
    private var notifyTarget: BluetoothDevice? = null
    private var notifyUuid: java.util.UUID? = null
    private var notifyTotal = 0
    private var notifyDone: (() -> Unit)? = null

    /** Sends [frames] as notifications one at a time, each after the last went out. */
    protected fun notifyFrames(device: BluetoothDevice, uuid: java.util.UUID, frames: List<ByteArray>, done: () -> Unit) {
        notifyQueue.clear()
        notifyQueue.addAll(frames)
        notifyTarget = device
        notifyUuid = uuid
        notifyTotal = frames.size
        notifyDone = done
        notifyNext(0)
    }

    private fun notifyNext(attempt: Int) {
        val device = notifyTarget ?: return
        val uuid = notifyUuid ?: return
        val frame = notifyQueue.firstOrNull() ?: return
        if (!notify(device, uuid, frame)) {
            if (attempt < RETRIES) handler.postDelayed({ guard { notifyNext(attempt + 1) } }, RETRY_MS)
            else fail(ShareFailure.CONNECT)
        }
    }

    private fun notified(device: BluetoothDevice, status: Int) {
        if (device.address != notifyTarget?.address || notifyQueue.isEmpty()) return
        if (status != BluetoothGatt.GATT_SUCCESS) return fail(ShareFailure.CONNECT)
        notifyQueue.removeFirst()
        onFrameProgress(notifyTotal - notifyQueue.size, notifyTotal)
        if (notifyQueue.isEmpty()) {
            val done = notifyDone
            notifyDone = null
            done?.invoke()
        } else {
            notifyNext(0)
        }
    }

    /** [sent] of [total] frames are out (as writes or notifications). */
    protected open fun onFrameProgress(sent: Int, total: Int) = Unit

    // ---------------------------------------------------------- GATT client

    protected var gatt: BluetoothGatt? = null
        private set
    protected var clientMtu = DEFAULT_MTU
        private set
    private var connectAttempts = 0
    private var discovering = false
    private var connectingTo: SharePeer? = null

    /** Connects to [peer], asks for MTU 517, then discovers; [onClientReady] follows. */
    protected fun connect(peer: SharePeer) {
        connectingTo = peer
        connectAttempts = 0
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, ShareProtocol.CONNECT_MS)
        openClient(peer)
    }

    private fun openClient(peer: SharePeer) {
        closeClient()
        connectAttempts++
        discovering = false
        clientMtu = DEFAULT_MTU
        val device = adapter?.getRemoteDevice(peer.address) ?: return fail(ShareFailure.CONNECT)
        gatt = device.connectGatt(
            app, false, clientCallbacks, BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, handler
        ) ?: return fail(ShareFailure.CONNECT)
    }

    /** The connection got as far as the role needed; the connect timeout no longer applies. */
    protected fun connected() = handler.removeCallbacks(connectTimeout)

    private val connectTimeout = Runnable { guard { if (!current.terminal) fail(ShareFailure.CONNECT) } }

    protected fun closeClient() {
        val g = gatt ?: return
        gatt = null
        runCatching { g.disconnect() }
        runCatching { g.close() }
        writeQueue.clear()
    }

    protected open fun onClientReady(service: BluetoothGattService) = Unit
    protected open fun onClientDisconnected() = Unit
    protected open fun onClientDescriptorWritten(ok: Boolean) = Unit
    protected open fun onClientRead(uuid: java.util.UUID, value: ByteArray, ok: Boolean) = Unit
    protected open fun onClientNotified(uuid: java.util.UUID, value: ByteArray) = Unit

    private fun discover(g: BluetoothGatt) {
        if (discovering || g !== gatt) return
        discovering = true
        if (!g.discoverServices()) fail(ShareFailure.CONNECT)
    }

    private val clientCallbacks = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = guard {
            if (g !== gatt) return@guard
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                // Some stacks never answer an MTU request; discover anyway after a moment.
                if (g.requestMtu(ShareProtocol.MTU_WANTED)) {
                    handler.postDelayed({ guard { discover(g) } }, MTU_GRACE_MS)
                } else {
                    discover(g)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val peer = connectingTo
                // The infamous status 133 on a first connect: one more try usually works.
                if (!discovering && connectAttempts < 2 && peer != null && !current.terminal) {
                    handler.postDelayed({ guard { openClient(peer) } }, RECONNECT_MS)
                } else {
                    closeClient()
                    onClientDisconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) = guard {
            if (g !== gatt) return@guard
            if (status == BluetoothGatt.GATT_SUCCESS) clientMtu = mtu
            discover(g)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = guard {
            if (g !== gatt) return@guard
            val service = g.getService(ShareProtocol.SERVICE)
            if (status != BluetoothGatt.GATT_SUCCESS || service == null) fail(ShareFailure.NOT_VOLTA)
            else onClientReady(service)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) = guard {
            if (g === gatt) onClientDescriptorWritten(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicRead(
            g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int
        ) = guard {
            if (g === gatt) onClientRead(characteristic.uuid, value, status == BluetoothGatt.GATT_SUCCESS)
        }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicRead(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = guard {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: ByteArray(0)
            if (g === gatt) onClientRead(characteristic.uuid, value, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) = guard {
            if (g === gatt) written(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) =
            guard { if (g === gatt) onClientNotified(characteristic.uuid, value.copyOf()) }

        @Deprecated("Android 12 and older")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) = guard {
            @Suppress("DEPRECATION")
            val value = characteristic.value?.copyOf() ?: ByteArray(0)
            if (g === gatt) onClientNotified(characteristic.uuid, value)
        }
    }

    protected fun enableNotifications(ch: BluetoothGattCharacteristic): Boolean {
        val g = gatt ?: return false
        if (!g.setCharacteristicNotification(ch, true)) return false
        val d = ch.getDescriptor(ShareProtocol.CCCD) ?: return false
        val v = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, v) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            d.value = v
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    protected fun read(ch: BluetoothGattCharacteristic): Boolean = gatt?.readCharacteristic(ch) == true

    private fun write(ch: BluetoothGattCharacteristic, value: ByteArray): Boolean {
        val g = gatt ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ch, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            ch.value = value
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ch)
        }
    }

    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeTotal = 0
    private var writeDone: (() -> Unit)? = null

    /** Writes [frames] (with response) one after another; [done] after the last is acknowledged. */
    protected fun writeFrames(ch: BluetoothGattCharacteristic, frames: List<ByteArray>, done: () -> Unit) {
        writeQueue.clear()
        writeQueue.addAll(frames)
        writeChar = ch
        writeTotal = frames.size
        writeDone = done
        writeNext(0)
    }

    private fun writeNext(attempt: Int) {
        val ch = writeChar ?: return
        val frame = writeQueue.firstOrNull() ?: return
        if (!write(ch, frame)) {
            // Busy with the previous operation for a moment: try again shortly.
            if (attempt < RETRIES) handler.postDelayed({ guard { writeNext(attempt + 1) } }, RETRY_MS)
            else fail(ShareFailure.CONNECT)
        }
    }

    private fun written(uuid: java.util.UUID, ok: Boolean) {
        if (uuid != writeChar?.uuid || writeQueue.isEmpty()) return
        if (!ok) return fail(ShareFailure.CONNECT)
        writeQueue.removeFirst()
        onFrameProgress(writeTotal - writeQueue.size, writeTotal)
        if (writeQueue.isEmpty()) {
            val done = writeDone
            writeDone = null
            done?.invoke()
        } else {
            writeNext(0)
        }
    }

    protected companion object {
        const val DEFAULT_MTU = 23
        const val RETRIES = 5
        const val RETRY_MS = 40L
        const val MTU_GRACE_MS = 1_500L
        const val RECONNECT_MS = 400L
    }
}
