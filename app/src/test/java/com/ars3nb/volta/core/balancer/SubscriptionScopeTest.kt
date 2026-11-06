package com.ars3nb.volta.core.balancer

import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SubscriptionScopeTest {

    private fun server(id: Long, sub: Long?, role: ServerRole = ServerRole.NORMAL) = ServerEntity(
        id = id, subscriptionId = sub, remark = "N$id", address = "n$id.example", port = 443, role = role
    )

    private val library = listOf(
        server(1, sub = 10),
        server(2, sub = 10, role = ServerRole.BYPASS_WHITELIST),
        server(3, sub = 20),
        server(4, sub = 20),
        server(5, sub = null),
        server(6, sub = 30, role = ServerRole.AUTO)
    )

    @Test
    fun aLimitKeepsTheAutoPickInsideItsSubscription() {
        assertEquals(listOf(3L, 4L), SubscriptionScope.pool(library, 20, BalancerScope.ALL).map { it.id })
        assertEquals(listOf(1L, 2L), SubscriptionScope.pool(library, 10, BalancerScope.BYPASS).map { it.id })
        // The mode's own group is chosen from it later (poolFor): Home within 10 is node 1.
        assertEquals(
            listOf(1L),
            AutoBalancer.poolFor(SubscriptionScope.pool(library, 10, BalancerScope.NORMAL), BalancerScope.NORMAL).map { it.id }
        )
    }

    @Test
    fun noLimitIsTheWholeLibrary() {
        assertEquals(library, SubscriptionScope.pool(library, null, BalancerScope.ALL))
    }

    @Test
    fun aSubscriptionWithNothingForTheGroupDoesNotLeaveTheTunnelWithNothing() {
        // Auto LTE limited to a subscription without LTE servers: the whole library.
        assertEquals(library, SubscriptionScope.pool(library, 20, BalancerScope.BYPASS))
        // Only provider balancers, which no auto group uses: the same.
        assertEquals(library, SubscriptionScope.pool(library, 30, BalancerScope.ALL))
        // Emptied by a refresh: the same.
        assertEquals(library, SubscriptionScope.pool(library, 99, BalancerScope.ALL))
    }

    @Test
    fun theChoiceAppliesOnlyWhereItIsOffered() {
        val subs = listOf(10L, 20L, 30L)
        assertEquals(20L, SubscriptionScope.effective(20, true, ConnectMode.AUTO_HOME_LTE, false, subs))
        assertEquals(20L, SubscriptionScope.effective(20, true, ConnectMode.AUTO_LTE, false, subs))
        // Switched off in Settings, a manual mode, the simple screen, nothing chosen.
        assertNull(SubscriptionScope.effective(20, false, ConnectMode.AUTO_HOME_LTE, false, subs))
        assertNull(SubscriptionScope.effective(20, true, ConnectMode.MANUAL, false, subs))
        assertNull(SubscriptionScope.effective(20, true, ConnectMode.AUTO_HOME_LTE, true, subs))
        assertNull(SubscriptionScope.effective(0, true, ConnectMode.AUTO_HOME_LTE, false, subs))
    }

    @Test
    fun aGoneSubscriptionLiftsTheLimit() {
        // A tile or the widget then connects in the plain mode.
        assertNull(SubscriptionScope.effective(40, true, ConnectMode.AUTO_HOME_LTE, false, listOf(10L, 20L)))
        // One subscription left: the choice isn't offered any more, so it doesn't apply.
        assertNull(SubscriptionScope.effective(10, true, ConnectMode.AUTO_HOME_LTE, false, listOf(10L)))
    }
}
