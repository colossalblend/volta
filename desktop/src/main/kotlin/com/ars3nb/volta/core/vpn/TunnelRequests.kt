package com.ars3nb.volta.core.vpn

/**
 * The parts of the Android app's TunnelRequests (core/vpn/TunnelRequests.kt) that are
 * about moving a live tunnel, not about Android's VPN interface: where a failed switch
 * goes, and what the screen names while one is under way. The same rules, so both apps
 * behave the same when a node picked by hand doesn't answer.
 */
internal object TunnelRequests {

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

    /** A live tunnel on its way from one node to another, as the screen names it. */
    data class Switching(val fromId: Long, val fromName: String, val toId: Long, val toName: String)

    /**
     * What the screen says while a move to [nextId] waits its turn on a live tunnel (on
     * [liveId]): moving there, or, when [nextId] is the live node itself while a move
     * elsewhere was under way ([moving]), moving back. Null: nothing moves.
     */
    fun switchingFor(
        liveId: Long,
        liveName: String,
        moving: Switching?,
        nextId: Long,
        nextName: String
    ): Switching? = when {
        liveId != 0L && nextId != liveId -> Switching(liveId, liveName, nextId, nextName)
        moving != null && moving.toId != nextId -> Switching(moving.toId, moving.toName, nextId, nextName)
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
