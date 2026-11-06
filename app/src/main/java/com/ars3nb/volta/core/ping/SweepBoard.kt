package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.PingResult

/** One server's result in a running sweep, as the list shows it. */
data class LiveCheck(
    /** Still waiting its turn or its answer. */
    val pending: Boolean,
    val latencyMs: Long = -1,
    val ok: Boolean = false,
    /** A judgement from outside (an OpenFlux node), not a round trip through it. */
    val estimate: Boolean = false,
    /** When it was measured; a newer figure in the database wins over it. */
    val at: Long = 0
) {
    /** Shown in place of the stored figure, which was measured at [storedAt]. */
    fun shownOver(storedAt: Long): Boolean = pending || at >= storedAt

    companion object {
        val PENDING = LiveCheck(pending = true)

        fun of(result: PingResult): LiveCheck {
            val ok = result.success && result.latencyMs >= 0
            return LiveCheck(
                pending = false,
                latencyMs = if (ok) result.latencyMs else -1,
                ok = ok,
                estimate = result.estimate,
                at = result.timestamp
            )
        }
    }
}

/**
 * "Check all" as it goes: which servers are in it, which have answered, how far along it
 * is. Plain values, so the counting can be tested; the list reads each row's own entry,
 * so a result redraws one row and the progress line, not the whole list.
 */
data class SweepBoard(
    val running: Boolean = false,
    val ids: Set<Long> = emptySet(),
    val results: Map<Long, LiveCheck> = emptyMap(),
    val ok: Int = 0
) {
    val total: Int get() = ids.size

    /** Servers with an answer (or a definite silence) so far. */
    val done: Int get() = results.count { !it.value.pending }

    /** 0..1 for the progress bar. */
    val fraction: Float get() = if (total == 0) 0f else done.toFloat() / total

    /** What the row of [id] shows: its result in this sweep, else null (the stored one). */
    fun cell(id: Long): LiveCheck? = results[id]


    /** A new sweep over [servers]: every one of them waits for its turn. */
    fun start(servers: Collection<Long>): SweepBoard {
        val set = servers.toSet()
        return SweepBoard(
            running = true,
            ids = set,
            results = set.associateWith { LiveCheck.PENDING },
            ok = 0
        )
    }

    /**
     * One answer landed. Counted once per server: a second answer for the same one (a
     * retry) replaces the first, and an id that isn't in this sweep is ignored.
     */
    fun record(id: Long, result: PingResult): SweepBoard {
        if (!running || id !in ids) return this
        val before = results[id]
        val now = LiveCheck.of(result)
        val okDelta = (if (now.ok) 1 else 0) - (if (before?.ok == true) 1 else 0)
        return copy(results = results + (id to now), ok = ok + okDelta)
    }

    /**
     * The sweep ended, done or stopped. The answers stay on the rows until the database,
     * which they are written to, shows them itself ([LiveCheck.shownOver]): no flash of
     * the old figure in between. Servers that never got their turn show their stored one.
     */
    fun finish(): SweepBoard = copy(running = false, results = results.filterValues { !it.pending })

    companion object {
        val IDLE = SweepBoard()

        /** A figure older than this is shown dimmed: the network may have changed since. */
        const val STALE_AFTER_MS = 30 * 60_000L

        /** A stored figure measured at [lastPingAt] is old news at [now]. */
        fun isStale(lastPingAt: Long, now: Long, staleAfterMs: Long = STALE_AFTER_MS): Boolean =
            lastPingAt > 0 && now - lastPingAt > staleAfterMs
    }
}
