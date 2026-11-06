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
            wanted != null &&
            // Who asked (a tap, the guard) changes nothing about the tunnel itself.
            incoming.copy(origin = wanted.origin) == wanted

    /**
     * The running interface fits [next]: only the core (and its helpers) is replaced.
     * [tunUp]: the VPN interface and its bridge are up. The core itself may be missing
     * (a node that just failed to start in a swap): the next one takes its place all the
     * same, and apps never see the interface go.
     */
    fun canSwapCore(running: TunSpec?, tunUp: Boolean, next: TunSpec): Boolean =
        tunUp && running != null && running == next

    /**
     * What happens when the node a live tunnel was moved to doesn't carry traffic. A pick
     * by hand goes back to the node that worked before it ([lastGoodId]): the user chose,
     * and silently ending up somewhere else would be a surprise. Anything else (the auto
     * pick, the guard, a way back that failed too) moves on to the next best node.
     */
    fun fallbackFor(origin: SwitchOrigin, failedId: Long, lastGoodId: Long?): Fallback =
        if (origin == SwitchOrigin.MANUAL && lastGoodId != null && lastGoodId > 0 && lastGoodId != failedId) {
            Fallback.BACK
        } else {
            Fallback.NEXT
        }

    /**
     * What the screen says while a connect for [next] waits its turn on a live tunnel
     * (on [liveId]): moving there, or, when [next] is the live node itself while a move
     * elsewhere was under way ([moving]), moving back. Null: nothing moves.
     */
    fun switchingFor(
        liveId: Long,
        liveName: String,
        moving: VoltaVpnService.Switching?,
        next: VoltaVpnService.ConnectionPlan
    ): VoltaVpnService.Switching? = when {
        liveId != 0L && next.serverId != liveId ->
            VoltaVpnService.Switching(liveId, liveName, next.serverId, next.serverName)
        moving != null && moving.toId != next.serverId ->
            VoltaVpnService.Switching(moving.toId, moving.toName, next.serverId, next.serverName)
        else -> moving
    }
}

/** Who moved the tunnel to a node: decides where it goes when that node fails. */
enum class SwitchOrigin {
    /** The user picked the node. */
    MANUAL,

    /** The auto pick, the guard, a failover or new settings. */
    AUTO,

    /** The way back after a manual pick failed; failing again moves on. */
    FALLBACK
}

enum class Fallback {
    /** Back to the node that worked before. */
    BACK,

    /** On to the next best node (or the error, when there is none). */
    NEXT
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
