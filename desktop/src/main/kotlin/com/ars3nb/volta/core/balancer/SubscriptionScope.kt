package com.ars3nb.volta.core.balancer

import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.ServerEntity

/**
 * «Авто по подписке»: the auto modes (Home+LTE, Home, LTE) limited to one subscription's
 * servers. The connect-time pick, the background comparison and the guard all choose
 * from that pool, so the tunnel never leaves the subscription by itself.
 */
object SubscriptionScope {

    /**
     * The subscription the auto pick is limited to right now, or null for all of them.
     * Null as well when the choice no longer applies: the feature is off, the mode is
     * manual or the simple screen's (which can't show the choice), the subscription is
     * gone (a tile or the widget then simply connects in the plain mode), or there is
     * only one subscription left, where the choice isn't offered.
     */
    fun effective(
        chosen: Long,
        enabled: Boolean,
        mode: ConnectMode,
        simpleMode: Boolean,
        subscriptionIds: Collection<Long>
    ): Long? = chosen.takeIf {
        enabled && !simpleMode && mode.scope != null && it > 0 &&
            it in subscriptionIds && subscriptionIds.toSet().size >= 2
    }

    /**
     * The servers an auto pick may use. With a limit: that subscription's, unless it has
     * none for the group the mode asks for ([scope]; say Auto LTE and a subscription
     * without LTE servers), where limiting would only leave nothing to connect to; the
     * whole library is used then rather than failing. Without a limit: all of them.
     */
    fun pool(servers: List<ServerEntity>, subscriptionId: Long?, scope: BalancerScope?): List<ServerEntity> {
        if (subscriptionId == null) return servers
        val mine = servers.filter { it.subscriptionId == subscriptionId }
        val usable = if (scope == null) mine else AutoBalancer.poolFor(mine, scope)
        return if (usable.isEmpty()) servers else mine
    }
}
