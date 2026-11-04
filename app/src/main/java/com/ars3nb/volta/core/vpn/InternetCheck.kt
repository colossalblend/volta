package com.ars3nb.volta.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Is there internet under the tunnel at all? Read from the phone itself in microseconds:
 * no request goes out, so the answer is there before the user taps the disc.
 *
 * Builds on what [DeviceState] and [NetworkWatcher] read: the physical networks (VPNs left
 * out; this app is outside its own tunnel). Mobile generation comes without
 * READ_PHONE_STATE, which Volta doesn't hold: from the kind of cell the modem reports its
 * signal for (GSM alone means 2G: GPRS «G» or EDGE «E»), and failing that from Android's
 * own bandwidth estimate for the mobile link.
 */
object InternetCheck {

    enum class Status {
        OK,

        /** Airplane mode with no Wi-Fi: nothing for the VPN to go over. */
        AIRPLANE,

        /** No network at all. */
        NO_NETWORK,

        /** Only mobile data, and it is 2G or has no signal: the VPN may not get through. */
        WEAK_MOBILE;

        /** Connecting can only spin: say so instead. */
        val blocksConnect: Boolean get() = this == AIRPLANE || this == NO_NETWORK
    }

    enum class Generation { UNKNOWN, SLOW_2G, FASTER }

    /** What the phone reports, as plain values, so [decide] can be tested on its own. */
    data class Signals(
        val airplaneMode: Boolean,
        /** Wi-Fi, ethernet or another non-mobile network (USB or Bluetooth tethering). */
        val hasNonCellular: Boolean,
        val hasCellular: Boolean,
        /** Android's estimate for the mobile link, kbit/s; 0 when it gives none. */
        val cellularDownKbps: Int = 0,
        val generation: Generation = Generation.UNKNOWN,
        /** Signal bars of the mobile-data SIM, 0..4; -1 when unknown. */
        val signalLevel: Int = -1
    )

    /**
     * Below this the mobile link counts as 2G-slow. Android's defaults per radio are
     * GPRS 24, EDGE 70, UMTS 115, HSPA 4300 and LTE 30000 kbit/s; a measured estimate this
     * low means the same in practice.
     */
    const val SLOW_KBPS = 200

    fun decide(s: Signals): Status = when {
        // Airplane mode with Wi-Fi on is fine: there is internet.
        s.hasNonCellular -> Status.OK
        s.airplaneMode && !s.hasCellular -> Status.AIRPLANE
        !s.hasCellular -> Status.NO_NETWORK
        s.generation == Generation.SLOW_2G -> Status.WEAK_MOBILE
        s.signalLevel == 0 -> Status.WEAK_MOBILE
        // The bandwidth estimate only when the generation can't be told.
        s.generation == Generation.UNKNOWN && s.cellularDownKbps in 1 until SLOW_KBPS -> Status.WEAK_MOBILE
        else -> Status.OK
    }

    /** The status right now. Anything unreadable counts as fine: never a false alarm. */
    fun status(context: Context): Status =
        runCatching { decide(read(context)) }.getOrDefault(Status.OK)

    fun read(context: Context): Signals {
        val cm = context.getSystemService(ConnectivityManager::class.java)
            ?: return Signals(airplaneMode = false, hasNonCellular = true, hasCellular = false)
        var nonCellular = false
        var cellular = false
        var kbps = 0
        @Suppress("DEPRECATION")
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                cellular = true
                kbps = maxOf(kbps, caps.linkDownstreamBandwidthKbps)
            } else {
                nonCellular = true
            }
        }
        val airplane = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        }.getOrDefault(false)
        val (generation, level) = if (cellular && !nonCellular) cell(context) else Generation.UNKNOWN to -1
        return Signals(airplane, nonCellular, cellular, kbps, generation, level)
    }

    /**
     * The serving cell of the mobile-data SIM. [TelephonyManager.getSignalStrength] needs no
     * permission (API 28+; the per-radio list API 29+).
     */
    private fun cell(context: Context): Pair<Generation, Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Generation.UNKNOWN to -1
        return runCatching {
            val base = context.getSystemService(TelephonyManager::class.java)
                ?: return Generation.UNKNOWN to -1
            val sub = SubscriptionManager.getDefaultDataSubscriptionId()
            val tm = if (sub != SubscriptionManager.INVALID_SUBSCRIPTION_ID) base.createForSubscriptionId(sub) else base
            val cells = tm.signalStrength?.cellSignalStrengths
                ?.filter { it.dbm != Int.MAX_VALUE }
                .orEmpty()
            if (cells.isEmpty()) return Generation.UNKNOWN to -1
            val generation = when {
                cells.any {
                    it is CellSignalStrengthLte || it is CellSignalStrengthNr ||
                        it is CellSignalStrengthWcdma || it is CellSignalStrengthTdscdma
                } -> Generation.FASTER
                cells.all { it is CellSignalStrengthGsm } -> Generation.SLOW_2G
                else -> Generation.UNKNOWN
            }
            generation to cells.maxOf { it.level }
        }.getOrDefault(Generation.UNKNOWN to -1)
    }

    /**
     * The status, live: networks coming and going, airplane mode, and a slow look at the
     * signal while only mobile data is up. A change is read again after a short settle,
     * so the hand-over between Wi-Fi and LTE doesn't flash a warning.
     */
    fun watch(context: Context): Flow<Status> = callbackFlow {
        val app = context.applicationContext
        trySend(status(app))
        var pending: Job? = null
        fun changed() {
            pending?.cancel()
            pending = launch {
                delay(SETTLE_MS)
                trySend(status(app))
            }
        }

        val cm = app.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val registered = runCatching { cm?.registerNetworkCallback(request, callback) }.isSuccess && cm != null

        val airplane = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) = changed()
        }
        runCatching {
            ContextCompat.registerReceiver(
                app, airplane, IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        // Signal bars change with no network event; a cached value, read rarely.
        val poll = launch {
            while (true) {
                delay(POLL_MS)
                trySend(status(app))
            }
        }

        awaitClose {
            poll.cancel()
            if (registered) runCatching { cm?.unregisterNetworkCallback(callback) }
            runCatching { app.unregisterReceiver(airplane) }
        }
    }.distinctUntilChanged()

    private const val SETTLE_MS = 1_200L
    private const val POLL_MS = 15_000L
}
