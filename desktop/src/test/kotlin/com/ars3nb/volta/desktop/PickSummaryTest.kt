package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "why this server" card, as on Android (PickSummaryTest there). */
class PickSummaryTest {

    private val node = ServerEntity(id = 3, remark = "NL", address = "203.0.113.5", port = 443)

    private fun decision(quick: Boolean, selected: ServerEntity?) = AutoBalancer.Decision(
        scope = BalancerScope.NORMAL,
        regime = NetworkRegime.OPEN,
        selected = selected,
        previous = null,
        switched = true,
        ranked = emptyList(),
        probes = emptyList(),
        reason = UiText.Raw("NL answered first of 4, in 48 ms"),
        quick = quick
    )

    @Test
    fun aQuickPickSaysWhichNodeAndWhyBeforeThereAreNumbers() {
        val summary = PickSummary.of(decision(quick = true, selected = node))
        assertNotNull(summary)
        assertEquals(UiText.Raw("NL answered first of 4, in 48 ms"), summary!!.reason)
        assertTrue(summary.rows.isEmpty())
    }

    @Test
    fun aFullRunThatMeasuredNothingHasNoCard() {
        assertNull(PickSummary.of(decision(quick = false, selected = node)))
        assertNull(PickSummary.of(decision(quick = true, selected = null)))
    }
}
