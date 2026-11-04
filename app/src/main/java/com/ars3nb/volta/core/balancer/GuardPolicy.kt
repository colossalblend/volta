package com.ars3nb.volta.core.balancer

import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole

/**
 * The decisions of the background check (ConnectionGuard → VoltaRepository.guardCheck),
 * kept free of Android so they can be tested. Everything here is arithmetic on numbers
 * the check already has: none of it costs a packet.
 */
object GuardPolicy {

    // --------------------------------------------------------------- groups

    /**
     * LTE servers that auto modes can actually use. Without any, "Авто LTE" and
     * "Авто Home+LTE" have nothing to switch to, so they run as plain Home and the site
     * probes (which only exist to choose between the groups) are skipped. OpenFlux
     * servers count like any other: auto modes pick them too.
     */
    fun hasLte(servers: List<ServerEntity>): Boolean = servers.any {
        it.role == ServerRole.BYPASS_WHITELIST
    }

    /** The mode that really runs: the chosen one, or Home when it needs LTE servers that aren't there. */
    fun effectiveMode(mode: ConnectMode, hasLte: Boolean): ConnectMode = when {
        hasLte -> mode
        mode == ConnectMode.AUTO_LTE || mode == ConnectMode.AUTO_HOME_LTE -> ConnectMode.AUTO_HOME
        else -> mode
    }

    // ---------------------------------------------------------------- speed

    /** Slower than this is slow whatever the node usually does. */
    const val SLOW_ABS_MS = 900L

    /**
     * The same bar for an OpenFlux server with a document or other service carrier:
     * relaying through a document is slow by nature, so only this is slow in itself
     * (being much slower than its own usual still counts, as for any node).
     */
    const val SLOW_ABS_OPENFLUX_MS = 3_000L

    /** …or this many times its own usual figure, */
    const val SLOW_RATIO = 2.5

    /** …as long as it is at least this slow (60 → 150 ms is not worth a comparison). */
    const val SLOW_MIN_MS = 350L

    /** A site through the tunnel taking longer than this confirms "slow". */
    const val SLOW_TUNNEL_MS = 1_500L

    /** A slow-triggered comparison runs at most this often. */
    const val SLOW_RECHECK_EVERY_MS = 6 * 60_000L

    fun isSlow(latencyMs: Long, usualMs: Long, absMs: Long = SLOW_ABS_MS): Boolean {
        if (latencyMs <= 0) return false
        if (latencyMs >= absMs) return true
        return usualMs > 0 && latencyMs >= SLOW_MIN_MS && latencyMs >= usualMs * SLOW_RATIO
    }

    /** The absolute "slow" bar for [server] (see [SLOW_ABS_OPENFLUX_MS]). */
    fun slowBarMs(server: ServerEntity): Long {
        if (server.protocol != ProtocolType.OPENFLUX) return SLOW_ABS_MS
        val carriers = server.network.split('+').map { it.trim() }
        return if (carriers.any { it.isNotEmpty() && it != "direct" }) SLOW_ABS_OPENFLUX_MS else SLOW_ABS_MS
    }

    /** The tunnel itself is slow (or silent) when a site through it takes this long. */
    fun tunnelConfirmsSlow(tunnelMs: Long): Boolean = tunnelMs < 0 || tunnelMs >= SLOW_TUNNEL_MS

    // -------------------------------------------------------------- traffic

    /** This much downloaded through the exit since the last check proves it carries data. */
    const val ALIVE_BYTES = 48 * 1024L

    fun trafficProvesAlive(downloadedSinceLastCheck: Long): Boolean =
        downloadedSinceLastCheck >= ALIVE_BYTES

    // ------------------------------------------------------------- schedule

    const val BASE_INTERVAL_MS = 2 * 60_000L

    /**
     * How long until the next check. Two minutes normally; four with Android's battery
     * saver on or the battery low and not charging — the check is cheap, but not free.
     */
    fun interval(powerSave: Boolean, batteryLow: Boolean, charging: Boolean): Long = when {
        charging -> BASE_INTERVAL_MS
        powerSave || batteryLow -> BASE_INTERVAL_MS * 2
        else -> BASE_INTERVAL_MS
    }

    /**
     * Whether the periodic full comparison (every node measured) may run now. It is the
     * one expensive step, so it waits for a better moment on a low battery.
     */
    fun fullAllowed(batteryLow: Boolean, charging: Boolean): Boolean = charging || !batteryLow

    // ------------------------------------------------------------- flapping

    /**
     * Stops optional switches (slow node, a faster one found) from flapping: after
     * [max] of them within [windowMs] the guard holds still for [cooldownMs]. A dead
     * node is still replaced — that is never optional.
     */
    class SwitchLimiter(
        private val max: Int = 3,
        private val windowMs: Long = 10 * 60_000L,
        private val cooldownMs: Long = 10 * 60_000L
    ) {
        private val times = ArrayDeque<Long>()
        private var quietUntil = 0L

        @Synchronized
        fun allow(now: Long): Boolean {
            if (now < quietUntil) return false
            while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
            return times.size < max
        }

        @Synchronized
        fun record(now: Long) {
            times.addLast(now)
            while (times.isNotEmpty() && now - times.first() > windowMs) times.removeFirst()
            if (times.size >= max) {
                quietUntil = now + cooldownMs
                times.clear()
            }
        }
    }
}
