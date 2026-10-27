package com.ars3nb.volta.core.balancer

import com.ars3nb.volta.core.ping.NetworkProbe
import com.ars3nb.volta.core.ping.PingEngine
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult

/**
 * App-side autobalancer, exposed to the user as three explicit actions.
 *
 * [BalancerScope.ALL] first asks [NetworkProbe] what the local network allows and only
 * then measures the pool that suits the answer — regular exits on an open connection,
 * bypass exits under a white-list regime, and *nothing at all* when there is no network,
 * because pinging servers over a dead link only manufactures false failures.
 *
 * [BalancerScope.NORMAL] and [BalancerScope.BYPASS] skip the site probe entirely and
 * measure their own pool directly.
 *
 * Switching is sticky: the node in use keeps its place unless a challenger is both
 * meaningfully and proportionally faster, which stops the selection flapping.
 */
class AutoBalancer(
    private val pingEngine: PingEngine,
    private val networkProbe: NetworkProbe = NetworkProbe()
) {

    companion object {
        /** A challenger must beat the current node by at least this much… */
        const val SWITCH_MARGIN_MS = 60L

        /** …and by this ratio, so 500ms→460ms does not trigger a reconnect. */
        const val SWITCH_MARGIN_RATIO = 0.8

        /** Weight of a fresh sample in the smoothed latency. */
        private const val EWMA_ALPHA = 0.4

        fun blendEwma(previous: Long, sample: Long): Long {
            if (sample < 0) return previous
            if (previous < 0) return sample
            return (previous * (1 - EWMA_ALPHA) + sample * EWMA_ALPHA).toLong()
        }

        fun detectRegime(probes: List<SiteProbeResult>): NetworkRegime {
            val foreignOk = probes.any { it.category == SiteCategory.FOREIGN && it.reachable }
            val ruOk = probes.any { it.category == SiteCategory.RU_WHITELIST && it.reachable }
            return when {
                foreignOk -> NetworkRegime.OPEN
                ruOk -> NetworkRegime.WHITELIST_ONLY
                else -> NetworkRegime.BLOCKED
            }
        }

        /** Nodes a scope may pick from. AUTO nodes serve both roles. */
        fun poolFor(servers: List<ServerEntity>, scope: BalancerScope): List<ServerEntity> =
            when (scope) {
                BalancerScope.ALL -> servers
                BalancerScope.NORMAL -> servers.filter {
                    it.role == ServerRole.NORMAL || it.role == ServerRole.AUTO
                }

                BalancerScope.BYPASS -> servers.filter {
                    it.role == ServerRole.BYPASS_WHITELIST || it.role == ServerRole.AUTO
                }
            }

        fun filterByRegime(
            servers: List<ServerEntity>,
            regime: NetworkRegime
        ): List<ServerEntity> = when (regime) {
            NetworkRegime.OPEN -> poolFor(servers, BalancerScope.NORMAL).ifEmpty { servers }
            NetworkRegime.WHITELIST_ONLY -> poolFor(servers, BalancerScope.BYPASS).ifEmpty { servers }
            NetworkRegime.BLOCKED, NetworkRegime.UNKNOWN -> servers
        }

        /**
         * Hysteresis: a challenger replaces the node in use only when it is both
         * absolutely and relatively faster, so ordinary jitter cannot cause a reconnect.
         */
        fun beats(challenger: Ranked, holder: Ranked): Boolean {
            val c = challenger.ewmaMs
            val h = holder.ewmaMs
            if (c < 0) return false
            if (h < 0) return true
            return (h - c) >= SWITCH_MARGIN_MS && c <= h * SWITCH_MARGIN_RATIO
        }
    }

    /** What a run is doing right now, for the connect button's caption. */
    enum class Phase { PROBING_NETWORK, MEASURING }

    data class Ranked(
        val server: ServerEntity,
        val latencyMs: Long,
        val ewmaMs: Long,
        val ok: Boolean
    ) {
        val score: Long
            get() = when {
                !ok -> Long.MAX_VALUE / 4
                ewmaMs >= 0 -> ewmaMs - if (server.isFavorite) 40 else 0
                else -> Long.MAX_VALUE / 8
            }
    }

    data class Decision(
        val scope: BalancerScope,
        val regime: NetworkRegime,
        val selected: ServerEntity?,
        val previous: ServerEntity?,
        val switched: Boolean,
        val ranked: List<Ranked>,
        val probes: List<SiteProbeResult>,
        val reason: String,
        /** True when nothing could be measured, so the caller should retry later. */
        val retryLater: Boolean = false
    ) {
        val measured: Boolean get() = ranked.isNotEmpty()
    }

    suspend fun choose(
        servers: List<ServerEntity>,
        scope: BalancerScope,
        current: ServerEntity? = null,
        pingType: PingType = PingType.REAL_DELAY,
        onPhase: (Phase) -> Unit = {}
    ): Decision {
        val dialable = servers.filter { ConfigBuilder.canDial(it) }
        if (dialable.isEmpty()) {
            return empty(
                scope,
                NetworkRegime.UNKNOWN,
                current,
                if (servers.isEmpty()) "Нет серверов"
                else "Ни один сервер не поддерживается этим ядром"
            )
        }

        var regime = NetworkRegime.UNKNOWN
        var probes = emptyList<SiteProbeResult>()
        val pool: List<ServerEntity>

        if (scope == BalancerScope.ALL) {
            onPhase(Phase.PROBING_NETWORK)
            val outcome = networkProbe.run()
            regime = outcome.regime
            probes = outcome.probes
            if (!outcome.hasAnyNetwork) {
                // Measuring nodes over a dead link would mark every one of them failed.
                return Decision(
                    scope = scope,
                    regime = NetworkRegime.BLOCKED,
                    selected = current,
                    previous = current,
                    switched = false,
                    ranked = emptyList(),
                    probes = probes,
                    reason = "Сети нет — ни один сайт не отвечает. Серверы не проверялись, " +
                        "попробуем позже.",
                    retryLater = true
                )
            }
            pool = filterByRegime(dialable, regime)
        } else {
            pool = poolFor(dialable, scope)
            if (pool.isEmpty()) {
                // Not the current node: it belongs to the other group, and silently
                // connecting there would ignore the group the user just chose.
                return empty(scope, regime, null, "В этой группе нет серверов")
            }
        }

        onPhase(Phase.MEASURING)
        val measurements = pingEngine.pingServers(pool, pingType).toMap()

        val ranked = pool
            .map { server ->
                val result: PingResult? = measurements[server.id]
                val ok = result?.success == true && result.latencyMs >= 0
                Ranked(
                    server = server,
                    latencyMs = if (ok) result!!.latencyMs else -1,
                    ewmaMs = if (ok) blendEwma(server.ewmaMs, result!!.latencyMs) else server.ewmaMs,
                    ok = ok
                )
            }
            .sortedBy { it.score }

        val best = ranked.firstOrNull { it.ok }
        val currentRank = current?.let { c -> ranked.firstOrNull { it.server.id == c.id } }

        val selected: ServerEntity?
        val switched: Boolean
        when {
            best == null -> {
                // Nothing answered — keep what we had rather than jumping to a dead node,
                // as long as it is in the group asked for.
                selected = current?.takeIf { c -> pool.any { it.id == c.id } }
                    ?: pool.minByOrNull { it.score }
                switched = false
            }

            currentRank == null || !currentRank.ok -> {
                selected = best.server
                switched = current?.id != best.server.id
            }

            best.server.id == currentRank.server.id -> {
                selected = currentRank.server
                switched = false
            }

            beats(best, currentRank) -> {
                selected = best.server
                switched = true
            }

            else -> {
                selected = currentRank.server
                switched = false
            }
        }

        return Decision(
            scope = scope,
            regime = regime,
            selected = selected,
            previous = current,
            switched = switched,
            ranked = ranked,
            probes = probes,
            reason = describe(scope, regime, ranked, selected, switched, current),
            retryLater = best == null
        )
    }

    private fun empty(
        scope: BalancerScope,
        regime: NetworkRegime,
        current: ServerEntity?,
        reason: String
    ) = Decision(
        scope = scope,
        regime = regime,
        selected = current,
        previous = current,
        switched = false,
        ranked = emptyList(),
        probes = emptyList(),
        reason = reason
    )

    private fun describe(
        scope: BalancerScope,
        regime: NetworkRegime,
        ranked: List<Ranked>,
        selected: ServerEntity?,
        switched: Boolean,
        previous: ServerEntity?
    ): String {
        val alive = ranked.count { it.ok }
        val ms = ranked.firstOrNull { it.server.id == selected?.id }?.takeIf { it.ok }?.ewmaMs
        val speed = ms?.let { " · $it мс" }.orEmpty()
        val head = when (scope) {
            BalancerScope.ALL -> when (regime) {
                NetworkRegime.OPEN -> "Сеть Home"
                NetworkRegime.WHITELIST_ONLY -> "Сеть LTE (белый список)"
                NetworkRegime.BLOCKED -> "Сеть почти не отвечает"
                NetworkRegime.UNKNOWN -> "Сеть не проверена"
            }

            BalancerScope.NORMAL -> "Авто Home"
            BalancerScope.BYPASS -> "Авто LTE"
        }
        val tail = when {
            selected == null -> "подходящих серверов нет"
            alive == 0 -> "ни один не ответил, оставили «${selected.remark}»"
            switched -> "переключились на «${selected.remark}»$speed"
            previous != null && previous.id == selected.id -> "оставили «${selected.remark}»$speed"
            else -> "выбран «${selected.remark}»$speed"
        }
        return "$head · $tail · живых узлов: $alive из ${ranked.size}"
    }
}
