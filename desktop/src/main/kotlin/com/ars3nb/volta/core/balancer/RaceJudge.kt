package com.ars3nb.volta.core.balancer

/**
 * Referee of the connect-time race between a few ranked contenders (rank 0 is the one
 * the record likes best: the node that worked here last, then the fastest measured).
 *
 * The first answer does not win outright: a better-ranked contender that answers within
 * [graceMs] of it still takes the race, since a handshake a few dozen ms slower says
 * nothing against a node with a good record. Once every better-ranked contender has
 * answered or failed, there is nothing left to wait for. No answer at all: no winner.
 *
 * Fed with events and asked for a verdict; it never reads a clock, so it can be tested.
 */
class RaceJudge(private val size: Int, private val graceMs: Long) {

    private val resolved = BooleanArray(size)
    private val answered = BooleanArray(size)
    private val latency = LongArray(size) { -1 }
    private var firstAnswerAt = -1L

    /** Contender [rank] answered ([ok]) or gave up, at [at] ms since the start. */
    fun record(rank: Int, ok: Boolean, latencyMs: Long, at: Long) {
        if (rank !in 0 until size || resolved[rank]) return
        resolved[rank] = true
        if (ok) {
            answered[rank] = true
            latency[rank] = latencyMs
            if (firstAnswerAt < 0) firstAnswerAt = at
        }
    }

    /** The best-ranked contender that answered so far, or null. */
    fun leader(): Int? = (0 until size).firstOrNull { answered[it] }

    fun latencyOf(rank: Int): Long = latency.getOrElse(rank) { -1 }

    val answers: Int get() = answered.count { it }

    /** Whether the race is decided at [now]: no point in waiting any longer. */
    fun decided(now: Long): Boolean {
        if (resolved.all { it }) return true
        val lead = leader() ?: return false
        // Everyone ranked above the leader is out: it cannot be beaten any more.
        if ((0 until lead).all { resolved[it] }) return true
        return now - firstAnswerAt >= graceMs
    }

    /** How long to wait for the next event at [now] before deciding anyway; null: no limit. */
    fun waitLimit(now: Long): Long? =
        if (firstAnswerAt < 0) null else (firstAnswerAt + graceMs - now).coerceAtLeast(0)
}
