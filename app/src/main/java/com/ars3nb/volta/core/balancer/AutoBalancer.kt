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

        /** How many of the fastest nodes get extra samples before the final pick. */
        const val FINALISTS = 3

        /** Extra samples per finalist. */
        const val REFINE_ROUNDS = 2

        /** A lost sample costs as much as this much extra latency. */
        const val LOSS_PENALTY_MS = 1_200L

        /** Each recent failure in a row adds this to a node's figure. */
        const val FAIL_STREAK_PENALTY_MS = 120L

        /** Folds a node's samples (-1 = no answer) into median, spread and loss. */
        fun quality(samples: List<Long>): Quality {
            val good = samples.filter { it > 0 }.sorted()
            if (good.isEmpty()) return Quality(-1, 0, 1.0)
            val mid = good.size / 2
            val median = if (good.size % 2 == 1) good[mid] else (good[mid - 1] + good[mid]) / 2
            return Quality(
                medianMs = median,
                jitterMs = good.last() - good.first(),
                loss = 1.0 - good.size.toDouble() / samples.size
            )
        }

        /**
         * One comparable figure per node: the median, plus half the jitter (a node that
         * swings 80↔400 ms feels worse than a steady 150), plus lost samples and recent
         * failures. -1 when the node never answered.
         */
        fun effectiveMs(q: Quality, failStreak: Int): Long {
            if (q.medianMs < 0) return -1
            return q.medianMs + q.jitterMs / 2 +
                (q.loss * LOSS_PENALTY_MS).toLong() +
                failStreak.coerceIn(0, 5) * FAIL_STREAK_PENALTY_MS
        }

        /** Which group a run serves, to know whose remembered node to favour. */
        fun groupOf(scope: BalancerScope, regime: NetworkRegime): BalancerScope? = when (scope) {
            BalancerScope.ALL -> when (regime) {
                NetworkRegime.OPEN -> BalancerScope.NORMAL
                NetworkRegime.WHITELIST_ONLY -> BalancerScope.BYPASS
                else -> null
            }

            else -> scope
        }

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
    enum class Phase { PROBING_NETWORK, MEASURING, REFINING }

    data class Quality(val medianMs: Long, val jitterMs: Long, val loss: Double)

    data class Ranked(
        val server: ServerEntity,
        val latencyMs: Long,
        val ewmaMs: Long,
        val ok: Boolean,
        val jitterMs: Long = 0,
        val samples: Int = 1
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
        onPhase: (Phase) -> Unit = {},
        remembered: Map<BalancerScope, Long> = emptyMap()
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

        // The node to beat: the one in use if it belongs here, otherwise the one that
        // last carried traffic in this group (Wi-Fi → LTE → back to Wi-Fi resumes on
        // the Home node that worked, instead of a fresh lottery).
        val group = groupOf(scope, regime)
        val holder = current?.takeIf { c -> pool.any { it.id == c.id } }
            ?: remembered[group]?.let { id -> pool.firstOrNull { it.id == id } }

        onPhase(Phase.MEASURING)
        val first = pingEngine.pingServers(pool, pingType).toMap()
        val samples = pool.associate { server ->
            val r = first[server.id]
            server.id to mutableListOf(if (r?.success == true && r.latencyMs >= 0) r.latencyMs else -1L)
        }

        // Stage two: the few fastest (and the holder) are measured again, so one lucky
        // sample cannot win and a jittery or lossy node loses to a steady one.
        val alive = pool.filter { samples.getValue(it.id).first() > 0 }
            .sortedBy { samples.getValue(it.id).first() }
        val finalists = alive.take(FINALISTS).let { top ->
            if (holder != null && holder in alive && top.none { it.id == holder.id }) top + holder else top
        }
        if (finalists.size >= 2) {
            onPhase(Phase.REFINING)
            pingEngine.resample(finalists, pingType, REFINE_ROUNDS).forEach { (id, results) ->
                samples[id]?.addAll(
                    results.map { if (it.success && it.latencyMs >= 0) it.latencyMs else -1L }
                )
            }
        }

        val ranked = pool
            .map { server ->
                val list = samples.getValue(server.id)
                val q = quality(list)
                val figure = effectiveMs(q, server.failStreak)
                val ok = figure >= 0
                Ranked(
                    server = server,
                    latencyMs = if (ok) q.medianMs else -1,
                    ewmaMs = if (ok) blendEwma(server.ewmaMs, figure) else server.ewmaMs,
                    ok = ok,
                    jitterMs = q.jitterMs,
                    samples = list.size
                )
            }
            .sortedBy { it.score }

        val best = ranked.firstOrNull { it.ok }
        val holderRank = holder?.let { h -> ranked.firstOrNull { it.server.id == h.id } }

        val selected: ServerEntity? = when {
            // Nothing answered — keep what we had rather than jump to a dead node.
            best == null -> holder ?: pool.minByOrNull { it.score }
            holderRank == null || !holderRank.ok -> best.server
            best.server.id == holderRank.server.id -> holderRank.server
            beats(best, holderRank) -> best.server
            else -> holderRank.server
        }
        val switched = selected != null && selected.id != current?.id

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
