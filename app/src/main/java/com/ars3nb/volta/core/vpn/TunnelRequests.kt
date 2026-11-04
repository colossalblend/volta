package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.VpnConnectionState
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the VPN interface itself is built from. Two plans with the same one share the
 * interface, and moving between them only replaces the core: apps see no drop.
 */
internal data class TunSpec(
    val mtu: Int,
    val socksPort: Int,
    val perAppMode: PerAppMode,
    val perAppPackages: Set<String>,
    /** The DNS servers named to Android ([ConfigBuilder.tunDnsServers]). */
    val dns: List<String>
)

/** How [VoltaVpnService] treats a connect request; plain values, so it can be tested. */
internal object TunnelRequests {

    fun tunOf(plan: VoltaVpnService.ConnectionPlan): TunSpec = TunSpec(
        mtu = plan.mtu.coerceIn(1280, 9000),
        socksPort = plan.socksPort,
        perAppMode = plan.perAppMode,
        perAppPackages = plan.perAppPackages,
        dns = ConfigBuilder.tunDnsServers(plan.configJson)
    )

    /**
     * A connect for exactly what the tunnel already runs or is raising. Anything that
     * differs — the node, its config, the MTU, the app list — is a real change: a new
     * routing rule or split-tunnelling list must reach a live tunnel, not be dropped.
     */
    fun isRepeat(
        state: VpnConnectionState,
        incoming: VoltaVpnService.ConnectionPlan,
        wanted: VoltaVpnService.ConnectionPlan?
    ): Boolean =
        (state == VpnConnectionState.CONNECTING || state == VpnConnectionState.CONNECTED) &&
            incoming == wanted

    /** The running interface fits [next]: only the core (and its helpers) is replaced. */
    fun canSwapCore(running: TunSpec?, coreUp: Boolean, next: TunSpec): Boolean =
        coreUp && running != null && running == next
}

/**
 * Connect and disconnect jobs waiting their turn behind the connect lock. A job that
 * would end the service (a failure, a disconnect) leaves it running when another one
 * is queued behind it: stopping then would cancel the user's next connect.
 */
internal class PendingJobs {
    private val count = AtomicInteger(0)

    fun enter() {
        count.incrementAndGet()
    }

    fun leave() {
        count.decrementAndGet()
    }

    /** Called from inside a job: true when no other job waits behind it. */
    fun alone(): Boolean = count.get() <= 1
}
