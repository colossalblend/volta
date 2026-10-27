package com.ars3nb.volta.core.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Watches the *physical* network under the tunnel (VPN networks are filtered out) and
 * reports when the phone moves between Wi-Fi, mobile data and ethernet.
 *
 * Moving from home Wi-Fi to LTE is exactly when the right server group changes, so
 * "Авто Home+LTE" re-probes and re-picks on this signal instead of waiting for the
 * watchdog to notice a dead exit a minute later.
 */
class NetworkWatcher(
    context: Context,
    private val scope: CoroutineScope,
    private val onUnderlyingChanged: suspend (kind: String) -> Unit
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val networks = ConcurrentHashMap<Network, String>()
    private var lastKind: String? = null
    private var pending: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            networks[network] = kindOf(caps)
            schedule()
        }

        override fun onLost(network: Network) {
            networks.remove(network)
            schedule()
        }
    }

    fun start() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
    }

    /** Waits for the hand-over to settle: Wi-Fi and LTE overlap for a few seconds. */
    @Synchronized
    private fun schedule() {
        pending?.cancel()
        pending = scope.launch {
            delay(SETTLE_MS)
            val kind = current()
            val previous = lastKind
            lastKind = kind
            if (previous != null && kind != previous && kind != "none") {
                runCatching { onUnderlyingChanged(kind) }
            }
        }
    }

    /** Wi-Fi wins over cellular when both are up, as Android itself routes. */
    private fun current(): String {
        val kinds = networks.values.toSet()
        return when {
            "ethernet" in kinds -> "ethernet"
            "wifi" in kinds -> "wifi"
            "cellular" in kinds -> "cellular"
            kinds.isEmpty() -> "none"
            else -> kinds.first()
        }
    }

    private fun kindOf(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        else -> "other"
    }

    private companion object {
        const val SETTLE_MS = 4_000L
    }
}
