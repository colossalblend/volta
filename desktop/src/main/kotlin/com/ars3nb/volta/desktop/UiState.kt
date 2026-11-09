package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.stats.TrafficDayEntity
import com.ars3nb.volta.core.vpn.InternetCheck
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.IpInfo
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SiteProbeResult
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.SwitchTarget
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.desktop.core.LocalPorts
import com.ars3nb.volta.desktop.data.AppSettings
import com.ars3nb.volta.desktop.i18n.Strings

/** What the connect button is doing before the tunnel exists. */
enum class ConnectPhase {
    IDLE, PREPARING, PROBING, MEASURING, REFINING;

    fun caption(s: Strings): String = when (this) {
        IDLE -> ""
        PREPARING -> s.phasePreparing
        PROBING -> s.phaseProbing
        MEASURING -> s.phaseMeasuring
        REFINING -> s.phaseRefining
    }

    companion object {
        fun of(phase: AutoBalancer.Phase): ConnectPhase = when (phase) {
            AutoBalancer.Phase.PROBING_NETWORK -> PROBING
            AutoBalancer.Phase.MEASURING -> MEASURING
            AutoBalancer.Phase.REFINING -> REFINING
        }
    }
}

data class SpeedHistory(
    val down: List<Long> = emptyList(),
    val up: List<Long> = emptyList(),
    val version: Long = 0
) {
    fun push(downSpeed: Long, upSpeed: Long) = SpeedHistory(
        down = (down + downSpeed.coerceAtLeast(0)).takeLast(CAPACITY),
        up = (up + upSpeed.coerceAtLeast(0)).takeLast(CAPACITY),
        version = version + 1
    )

    companion object {
        const val CAPACITY = 40
    }
}

data class PickRow(
    val serverId: Long,
    val name: String,
    val medianMs: Long,
    val jitterMs: Long,
    val samples: Int,
    val chosen: Boolean
)

/** The finalists of the last auto pick, for the "why this server" card. */
data class PickSummary(
    /** Which node and why, in a sentence; a quick pick has only this, no table yet. */
    val reason: UiText,
    val rows: List<PickRow>,
    val measured: Int,
    val alive: Int,
    val at: Long
) {
    companion object {
        fun of(decision: AutoBalancer.Decision): PickSummary? {
            val alive = decision.ranked.filter { it.ok }
            if (alive.isEmpty()) {
                // A quick pick has no table yet, only its reason: which node and why.
                if (!decision.quick || decision.selected == null) return null
                return PickSummary(
                    reason = decision.reason,
                    rows = emptyList(),
                    measured = 0,
                    alive = 0,
                    at = System.currentTimeMillis()
                )
            }
            val chosenId = decision.selected?.id
            val top = alive.take(3).toMutableList()
            alive.firstOrNull { it.server.id == chosenId }
                ?.takeIf { c -> top.none { it.server.id == c.server.id } }
                ?.let { top[top.lastIndex] = it }
            return PickSummary(
                reason = decision.reason,
                rows = top.map {
                    PickRow(
                        serverId = it.server.id,
                        name = it.server.remark,
                        medianMs = it.latencyMs,
                        jitterMs = it.jitterMs,
                        samples = it.samples,
                        chosen = it.server.id == chosenId
                    )
                },
                measured = decision.ranked.size,
                alive = alive.size,
                at = System.currentTimeMillis()
            )
        }
    }
}

/** Everything that changes while Volta runs and isn't saved. */
data class RuntimeState(
    val vpnState: VpnConnectionState = VpnConnectionState.DISCONNECTED,
    val connectedServerId: Long? = null,
    val connectedSince: Long = 0,
    val traffic: TrafficStats = TrafficStats(),
    val speed: SpeedHistory = SpeedHistory(),
    val error: String? = null,
    val busy: Int = 0,
    val runningScope: BalancerScope? = null,
    val phase: ConnectPhase = ConnectPhase.IDLE,
    val ipInfo: IpInfo? = null,
    val probes: List<SiteProbeResult> = emptyList(),
    val pick: PickSummary? = null,
    val ports: LocalPorts? = null,
    val proxyActive: Boolean = false,
    val coreVersion: String = "",
    val autostart: Boolean = false,
    /** Routing or filters changed while connected: they reach the tunnel with a reconnect. */
    val routingDirty: Boolean = false,
    /** Is there a network at all (see InternetCheck). */
    val internet: InternetCheck.Status = InternetCheck.Status.OK,
    /**
     * A live tunnel on its way to another node: from the click (or the guard's decision)
     * until the new node carried a page. The tunnel stays up meanwhile.
     */
    val switching: SwitchTarget? = null,
    /** The one-server check started from its menu, while the menu shows it. */
    val serverCheck: ServerCheck? = null
)

/** A one-server check from its menu: running, then its answer in words. */
data class ServerCheck(
    val serverId: Long,
    val running: Boolean,
    /** In words, read in the window's language when shown. */
    val text: UiText? = null,
    val ok: Boolean = false,
    val latencyMs: Long = -1
)

data class VoltaUiState(
    val subscriptions: List<SubscriptionEntity> = emptyList(),
    val servers: List<ServerEntity> = emptyList(),
    val routingProfiles: List<RoutingProfileEntity> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val networkRegime: NetworkRegime = NetworkRegime.UNKNOWN,
    val trafficDays: List<TrafficDayEntity> = emptyList(),
    val runtime: RuntimeState = RuntimeState()
) {
    val selectedServer: ServerEntity? get() = servers.firstOrNull { it.isSelected }
    val vpnState: VpnConnectionState get() = runtime.vpnState
    val isConnected: Boolean get() = runtime.vpnState == VpnConnectionState.CONNECTED
    val busy: Boolean get() = runtime.busy > 0
    val phase: ConnectPhase get() = runtime.phase
}
