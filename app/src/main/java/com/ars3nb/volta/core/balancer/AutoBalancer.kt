package com.ars3nb.volta.core.balancer

import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.ping.NetworkProbe
import com.ars3nb.volta.core.ping.PingEngine
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ProtocolType
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
        const val SWITCH_MARGIN_MS = 30L

        /** …and by this ratio, so 500ms→460ms does not trigger a reconnect. */
        const val SWITCH_MARGIN_RATIO = 0.85

        /** Weight of a fresh sample in the smoothed latency. */
        private const val EWMA_ALPHA = 0.4

        /** How many of the fastest nodes get extra samples before the final pick. */
        const val FINALISTS = 3

        /** Extra samples per finalist. */
        const val REFINE_ROUNDS = 2

        /**
         * The remembered node is taken unmeasured at connect time unless it failed this
         * many times in a row; one bad check (a network change) doesn't disqualify it.
         */
        const val INSTANT_MAX_FAILS = 2

        /** A lost sample costs as much as this much extra latency. */
        const val LOSS_PENALTY_MS = 1_200L

        /** Each recent failure in a row adds this to a node's figure. */
        const val FAIL_STREAK_PENALTY_MS = 40L

        /** How many of the best-known nodes the connect-time race starts with. */
        const val RACE_SIZE = 4

        /** A measurement younger than this counts as recent for the connect-time race. */
        const val RECENT_MS = 12 * 60 * 60_000L

        /**
         * Who races at connect time, best first: the node that worked last time in this
         * group (unless it keeps failing), then the healthy measured ones by their record,
         * recently measured before stale. OpenFlux nodes only as that remembered node:
         * their handshake reaches a carrier, not the exit ([raceTiers]). Empty when
         * nothing was ever measured: then the whole group races.
         */
        fun raceContenders(
            pool: List<ServerEntity>,
            holder: ServerEntity?,
            now: Long,
            size: Int = RACE_SIZE,
            recentMs: Long = RECENT_MS
        ): List<ServerEntity> {
            val first = holder?.takeIf { h -> h.failStreak < INSTANT_MAX_FAILS && pool.any { it.id == h.id } }
            val known = pool
                .filter { it.id != first?.id && it.protocol != ProtocolType.OPENFLUX }
                .filter { it.isHealthy && it.latencyMs > 0 }
                .sortedWith(compareBy<ServerEntity> { now - it.lastPingAt > recentMs }.thenBy { it.score })
            return (listOfNotNull(first) + known).take(size)
        }

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
         * One comparable figure per node, from this run's samples: the median the user
         * sees, nudged by a quarter of the spread (a node swinging 60↔600 ms is worse
         * than a steady 155), lost samples and recent failures. Ordinary nodes end up
         * within a few ms of their median. -1 when the node never answered.
         */
        fun effectiveMs(q: Quality, failStreak: Int): Long {
            if (q.medianMs < 0) return -1
            return q.medianMs + q.jitterMs / 4 +
                (q.loss * LOSS_PENALTY_MS).toLong() +
                failStreak.coerceIn(0, 3) * FAIL_STREAK_PENALTY_MS
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

        /**
         * Nodes a scope may pick from. Provider balancers (role AUTO) are in none of
         * them: Volta balances by itself, a balancer behind it only hides which exit
         * is actually in use. Every protocol takes part, OpenFlux included: its helper
         * is started (or swapped) by the tunnel like the core is.
         */
        fun poolFor(servers: List<ServerEntity>, scope: BalancerScope): List<ServerEntity> =
            when (scope) {
                BalancerScope.ALL -> servers.filter { it.role != ServerRole.AUTO }
                BalancerScope.NORMAL -> servers.filter { it.role == ServerRole.NORMAL }
                BalancerScope.BYPASS -> servers.filter { it.role == ServerRole.BYPASS_WHITELIST }
            }

        /**
         * The order of the connect-time race: regular nodes first, OpenFlux only when
         * none of them answered. A race of handshakes finds the nearest node, and an
         * OpenFlux node's handshake reaches its carrier (a big service next door), not
         * its exit: it would win every race while saying nothing about the exit, and
         * starting its helper costs seconds the race is meant to save.
         */
        fun raceTiers(pool: List<ServerEntity>): List<List<ServerEntity>> =
            pool.partition { it.protocol != ProtocolType.OPENFLUX }
                .toList()
                .map { tier -> tier.sortedBy { it.score } }
                .filter { it.isNotEmpty() }

        fun filterByRegime(
            servers: List<ServerEntity>,
            regime: NetworkRegime
        ): List<ServerEntity> {
            val all = poolFor(servers, BalancerScope.ALL)
            return when (regime) {
                NetworkRegime.OPEN -> poolFor(servers, BalancerScope.NORMAL).ifEmpty { all }
                NetworkRegime.WHITELIST_ONLY -> poolFor(servers, BalancerScope.BYPASS).ifEmpty { all }
                NetworkRegime.BLOCKED, NetworkRegime.UNKNOWN -> all
            }
        }

        /**
         * The node a run settles on, from this run's [ranked] figures. The node to beat
         * ([holder]) stays unless a challenger [beats] it; one that failed is replaced by
         * the best that answered; when nothing answered, the holder is kept rather than
         * a jump made to a dead node.
         *
         * A [guessed] node (an OpenFlux node never used: its carrier answered, its exit
         * was never timed) does not push out a holder that works: moving onto it costs a
         * helper start for a figure nobody measured. It is picked when the holder fails,
         * or when there is none.
         */
        fun select(
            ranked: List<Ranked>,
            holder: ServerEntity?,
            pool: List<ServerEntity>,
            guessed: Set<Long> = emptySet()
        ): ServerEntity? {
            val best = ranked.firstOrNull { it.ok }
                ?: return holder ?: pool.minByOrNull { it.score }
            val holderRank = holder?.let { h -> ranked.firstOrNull { it.server.id == h.id } }
            if (holderRank == null || !holderRank.ok) return best.server
            val challenger = ranked.firstOrNull {
                it.ok && (it.server.id == holderRank.server.id || it.server.id !in guessed)
            } ?: return holderRank.server
            return when {
                challenger.server.id == holderRank.server.id -> holderRank.server
                beats(challenger, holderRank) -> challenger.server
                else -> holderRank.server
            }
        }

        /**
         * Hysteresis: a challenger replaces the node in use only when it is both
         * absolutely and relatively faster in this very run, so ordinary jitter cannot
         * cause a reconnect while a clearly faster node always wins.
         */
        fun beats(challenger: Ranked, holder: Ranked): Boolean {
            val c = challenger.figureMs
            val h = holder.figureMs
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
        val samples: Int = 1,
        /** This run's figure ([effectiveMs]); the choice is made on it, not on history. */
        val figureMs: Long = latencyMs
    ) {
        val score: Long
            get() = when {
                !ok -> Long.MAX_VALUE / 4
                figureMs >= 0 -> figureMs - if (server.isFavorite) 40 else 0
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
        /** What the user is told; read in the screen's language when it is shown. */
        val reason: UiText,
        /** True when nothing could be measured, so the caller should retry later. */
        val retryLater: Boolean = false,
        /** Picked the fast way at connect time; a full comparison should follow. */
        val quick: Boolean = false
    ) {
        val measured: Boolean get() = ranked.isNotEmpty()
    }

    suspend fun choose(
        servers: List<ServerEntity>,
        scope: BalancerScope,
        current: ServerEntity? = null,
        pingType: PingType = PingType.REAL_DELAY,
        onPhase: (Phase) -> Unit = {},
        remembered: Map<BalancerScope, Long> = emptyMap(),
        /** Connect-time mode: answer in a second or two, refine after connecting. */
        quick: Boolean = false,
        /** A fresh network check for this same network, so it need not be repeated. */
        knownNetwork: NetworkProbe.Outcome? = null,
        /**
         * Quick mode may take the remembered node unmeasured. Off when the caller wants
         * numbers first, e.g. the background check after the group changed.
         */
        instant: Boolean = true
    ): Decision {
        val dialable = servers.filter { ConfigBuilder.canDial(it) }
        if (dialable.isEmpty()) {
            return empty(
                scope,
                NetworkRegime.UNKNOWN,
                current,
                text(
                    if (servers.isEmpty()) R.string.servers_balancer_no_servers
                    else R.string.servers_balancer_none_dialable
                )
            )
        }

        var regime = NetworkRegime.UNKNOWN
        var probes = emptyList<SiteProbeResult>()
        val pool: List<ServerEntity>

        if (scope == BalancerScope.ALL) {
            val outcome = knownNetwork ?: run {
                onPhase(Phase.PROBING_NETWORK)
                if (quick) networkProbe.runFast() else networkProbe.run()
            }
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
                    reason = text(R.string.servers_balancer_offline),
                    retryLater = true
                )
            }
            pool = filterByRegime(dialable, regime)
        } else {
            pool = poolFor(dialable, scope)
        }
        if (pool.isEmpty()) {
            // Not the current node: it belongs to another group (or is a provider
            // balancer), and connecting there would ignore the group the user chose.
            return empty(scope, regime, null, text(R.string.servers_balancer_group_empty))
        }

        // The node to beat: the one in use if it belongs here, otherwise the one that
        // last carried traffic in this group (Wi-Fi → LTE → back to Wi-Fi resumes on
        // the Home node that worked, instead of a fresh lottery).
        val group = groupOf(scope, regime)
        val holder = current?.takeIf { c -> pool.any { it.id == c.id } }
            ?: remembered[group]?.let { id -> pool.firstOrNull { it.id == id } }

        // Connect-time fast path: no full measuring before the tunnel is up. The node that
        // worked last time in this group and the best ones on record race each other
        // (a handshake each, all at once); the first to answer is taken, the remembered
        // one winning a near tie. Only when none of them answers does the whole group
        // race. The health check right after connecting vouches for the winner (and
        // fails over if it doesn't), and the full comparison runs once the tunnel is up
        // and moves it to the fastest node: the user doesn't sit through it.
        if (quick && instant) {
            val contenders = raceContenders(pool, holder, System.currentTimeMillis())
            if (contenders.isNotEmpty()) {
                onPhase(Phase.MEASURING)
                val race = pingEngine.raceRanked(contenders)
                race.winner?.let { pick ->
                    val why = if (pick.id == holder?.id) {
                        text(R.string.servers_pick_why_remembered, pick.remark, race.latencyMs)
                    } else {
                        text(R.string.servers_pick_why_race, pick.remark, race.asked, race.latencyMs)
                    }
                    return quickDecision(scope, regime, pick, current, probes, why)
                }
            }
            onPhase(Phase.MEASURING)
            for (tier in raceTiers(pool)) {
                val race = pingEngine.firstToAnswer(tier)
                val pick = race.winner ?: continue
                val why = if (race.latencyMs > 0) {
                    text(R.string.servers_pick_why_race, pick.remark, race.asked, race.latencyMs)
                } else {
                    text(R.string.servers_balancer_connecting, pick.remark)
                }
                return quickDecision(scope, regime, pick, current, probes, why)
            }
        }

        onPhase(Phase.MEASURING)
        val first = if (quick) {
            pingEngine.pingServersQuick(pool, pingType).toMap()
        } else {
            pingEngine.pingServers(pool, pingType, realDelayTimeoutMs = PingEngine.SWEEP_TIMEOUT_MS).toMap()
        }
        // The quick sweep only judges some nodes; the rest keep their stored health.
        val judged = if (quick) pool.filter { it.id in first } else pool
        val samples = judged.associate { server ->
            val r = first[server.id]
            server.id to mutableListOf(if (r?.success == true && r.latencyMs >= 0) r.latencyMs else -1L)
        }
        // Judged from outside rather than timed through (an OpenFlux node whose helper
        // isn't running): good for this run's choice, not for the node's long average.
        val estimated = first.filterValues { it.estimate }.keys.toMutableSet()

        // Stage two: the few fastest (and the holder) are measured again, so one lucky
        // sample cannot win and a jittery or lossy node loses to a steady one.
        val alive = judged.filter { samples.getValue(it.id).first() > 0 }
            .sortedBy { samples.getValue(it.id).first() }
        val finalists = alive.take(FINALISTS).let { top ->
            if (holder != null && holder in alive && top.none { it.id == holder.id }) top + holder else top
        }
        if (!quick && finalists.size >= 2) {
            onPhase(Phase.REFINING)
            pingEngine.resample(finalists, pingType, REFINE_ROUNDS).forEach { (id, results) ->
                samples[id]?.addAll(
                    results.map { if (it.success && it.latencyMs >= 0) it.latencyMs else -1L }
                )
                if (results.any { it.estimate }) estimated += id
            }
        }

        val ranked = judged
            .map { server ->
                val list = samples.getValue(server.id)
                val q = quality(list)
                val figure = effectiveMs(q, server.failStreak)
                val ok = figure >= 0
                Ranked(
                    server = server,
                    latencyMs = if (ok) q.medianMs else -1,
                    ewmaMs = if (ok && server.id !in estimated) {
                        blendEwma(server.ewmaMs, figure)
                    } else {
                        server.ewmaMs
                    },
                    ok = ok,
                    jitterMs = q.jitterMs,
                    samples = list.size,
                    figureMs = figure
                )
            }
            .sortedBy { it.score }

        val best = ranked.firstOrNull { it.ok }
        // Estimates with nothing real behind them: OpenFlux nodes never used, known only
        // by their carrier's handshake.
        val guessed = judged.filter { it.id in estimated && it.ewmaMs <= 0 }.map { it.id }.toSet()
        val selected = select(ranked, holder, pool, guessed)
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
            retryLater = best == null,
            quick = quick
        )
    }

    private fun quickDecision(
        scope: BalancerScope,
        regime: NetworkRegime,
        pick: ServerEntity,
        current: ServerEntity?,
        probes: List<SiteProbeResult>,
        why: UiText
    ) = Decision(
        scope = scope,
        regime = regime,
        selected = pick,
        previous = current,
        switched = pick.id != current?.id,
        ranked = emptyList(),
        probes = probes,
        reason = why,
        quick = true
    )

    private fun empty(
        scope: BalancerScope,
        regime: NetworkRegime,
        current: ServerEntity?,
        reason: UiText
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
    ): UiText {
        val alive = ranked.count { it.ok }
        val ms = ranked.firstOrNull { it.server.id == selected?.id }?.takeIf { it.ok }?.latencyMs
        val speed: UiText = ms?.let { UiText.Raw(" · ") + text(R.string.servers_ms, it) } ?: UiText.Raw("")
        val group = when (groupOf(scope, regime)) {
            BalancerScope.NORMAL -> "Home"
            BalancerScope.BYPASS -> "LTE"
            else -> null
        }
        val said = when {
            selected == null -> text(R.string.servers_balancer_none_suitable)
            alive == 0 -> text(R.string.servers_balancer_none_answered, selected.remark)
            switched -> text(R.string.servers_balancer_picked, selected.remark) + speed
            previous != null && previous.id == selected.id ->
                text(R.string.servers_balancer_kept, selected.remark) + speed
            else -> text(R.string.servers_balancer_picked, selected.remark) + speed
        }
        return if (group == null) said else text(R.string.servers_balancer_in_group, group, said)
    }

    /** What the user is told; the screen reads it in its own language. */
    private fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.of(id, *args)
}
