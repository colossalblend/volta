package com.ars3nb.volta.core.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Is there a network under the tunnel at all? The Android app's InternetCheck on
 * Windows: read from the computer's own network adapters in a moment, no request goes
 * out, so the answer is there before the user presses the disc.
 *
 * Windows tells no airplane mode or mobile signal to a plain program, so only two of
 * Android's answers exist here: [Status.OK] and [Status.NO_NETWORK]. An adapter counts
 * when it is up and has an address that leads outside this computer; loopback,
 * link-local addresses (169.254.x.x, what Windows gives an adapter it got nothing for)
 * and Windows' own tunnel stubs don't. Anything unreadable counts as fine: never a false
 * alarm.
 */
object InternetCheck {

    enum class Status {
        OK,

        /** No network at all. */
        NO_NETWORK;

        /** Connecting can only spin: say so instead. */
        val blocksConnect: Boolean get() = this == NO_NETWORK
    }

    /** One adapter as Windows reports it, as plain values, so [decide] can be tested. */
    data class Adapter(
        val name: String,
        val displayName: String,
        val up: Boolean,
        val loopback: Boolean,
        val virtual: Boolean,
        val addresses: List<InetAddress>
    )

    /**
     * Adapters Windows keeps that never carry the internet themselves. Hyper-V's and
     * other virtual switches are not on the list: with an external switch the host's
     * internet goes through one, and a false "no internet" would stop a connect that works.
     */
    private val VIRTUAL = listOf(
        "loopback", "pseudo-interface", "teredo", "isatap", "6to4", "wan miniport", "kernel debug"
    )

    fun decide(adapters: List<Adapter>): Status {
        if (adapters.isEmpty()) return Status.OK
        val usable = adapters.any { a ->
            a.up && !a.loopback && !a.virtual &&
                VIRTUAL.none { a.displayName.lowercase().contains(it) || a.name.lowercase().contains(it) } &&
                a.addresses.any(::leadsOutside)
        }
        return if (usable) Status.OK else Status.NO_NETWORK
    }

    private fun leadsOutside(address: InetAddress): Boolean = when {
        address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress -> false
        address is Inet4Address -> true
        address is Inet6Address -> !address.isSiteLocalAddress
        else -> false
    }

    fun read(): List<Adapter> = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { ni ->
        Adapter(
            name = ni.name.orEmpty(),
            displayName = ni.displayName.orEmpty(),
            up = runCatching { ni.isUp }.getOrDefault(false),
            loopback = runCatching { ni.isLoopback }.getOrDefault(false),
            virtual = runCatching { ni.isVirtual }.getOrDefault(false),
            addresses = ni.inetAddresses.toList()
        )
    }

    /** The status right now. */
    fun status(): Status = runCatching { decide(read()) }.getOrDefault(Status.OK)

    /** The status, live: read every few seconds, a change confirmed once before it shows. */
    fun watch(everyMs: Long = 4_000L): Flow<Status> = flow {
        var shown = status()
        emit(shown)
        while (true) {
            delay(everyMs)
            val now = status()
            if (now != shown) {
                // The hand-over between Wi-Fi and a cable flickers: read again first.
                delay(SETTLE_MS)
                if (status() == now) {
                    shown = now
                    emit(now)
                }
            }
        }
    }.distinctUntilChanged()

    private const val SETTLE_MS = 1_200L
}
