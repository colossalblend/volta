package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.VpnConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelRequestsTest {

    private val plan = VoltaVpnService.ConnectionPlan(
        serverId = 7,
        serverName = "NL",
        configJson = """{"outbounds":[]}""",
        perAppMode = PerAppMode.EXCLUDE,
        perAppPackages = setOf("com.bank")
    )

    @Test
    fun theSamePlanWhileUpIsARepeat() {
        assertTrue(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, plan, plan.copy()))
        assertTrue(TunnelRequests.isRepeat(VpnConnectionState.CONNECTING, plan, plan.copy()))
    }

    @Test
    fun aRepeatOnlyCountsWhileATunnelIsWanted() {
        // Off, failed or still going down: the same plan raises the tunnel again.
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.DISCONNECTED, plan, plan))
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.ERROR, plan, plan))
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.DISCONNECTING, plan, plan))
        // After "off" nothing is wanted: a quick off-and-on is a new connect.
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, plan, null))
    }

    @Test
    fun aChangedAppListOrMtuOnTheSameNodeIsNotDropped() {
        // "Apply now" after editing split tunnelling sends the same config with a new list.
        val newList = plan.copy(perAppPackages = setOf("com.bank", "com.shop"))
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, newList, plan))
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, plan.copy(mtu = 1280), plan))
        // Every voltaproxy server has the same config: the helper tells them apart.
        val awg = plan.copy(helper = ProxyHelper.Spec(ProxyHelper.Spec.AWG, "[Interface]"))
        assertFalse(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, awg, plan))
    }

    @Test
    fun onlyTheCoreIsSwappedWhenTheInterfaceFits() {
        val running = TunnelRequests.tunOf(plan)
        val otherNode = plan.copy(serverId = 8, configJson = """{"outbounds":[{"tag":"proxy"}]}""")
        assertTrue(TunnelRequests.canSwapCore(running, tunUp = true, next = TunnelRequests.tunOf(otherNode)))
    }

    @Test
    fun aDifferentInterfaceOrNoTunnelRebuilds() {
        val running = TunnelRequests.tunOf(plan)
        val newList = TunnelRequests.tunOf(plan.copy(perAppPackages = emptySet()))
        assertFalse(TunnelRequests.canSwapCore(running, tunUp = true, next = newList))
        assertFalse(TunnelRequests.canSwapCore(running, tunUp = false, next = running))
        assertFalse(TunnelRequests.canSwapCore(null, tunUp = true, next = running))
    }

    @Test
    fun theMtuIsComparedAsTheInterfaceGetsIt() {
        // Both out of range end up at 9000: the same interface.
        val a = TunnelRequests.tunOf(plan.copy(mtu = 12_000))
        val b = TunnelRequests.tunOf(plan.copy(mtu = 20_000))
        assertTrue(TunnelRequests.canSwapCore(a, tunUp = true, next = b))
    }

    @Test
    fun aJobAloneMayEndTheServiceOneWithAnotherBehindItMayNot() {
        val jobs = PendingJobs()
        jobs.enter()
        assertTrue(jobs.alone())
        jobs.enter()
        assertFalse(jobs.alone())
        jobs.leave()
        assertTrue(jobs.alone())
    }

    // ------------------------------------------------------------------ switching

    @Test
    fun whoAskedChangesNothingAboutWhetherItIsARepeat() {
        val byHand = plan.copy(origin = SwitchOrigin.MANUAL)
        assertTrue(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, byHand, plan))
        assertTrue(TunnelRequests.isRepeat(VpnConnectionState.CONNECTED, plan, byHand))
    }

    @Test
    fun aFailedPickByHandGoesBackToTheNodeThatWorked() {
        assertEquals(Fallback.BACK, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = 7))
    }

    @Test
    fun anythingElseMovesOnToTheNextBest() {
        // The auto pick and the guard choose by themselves: the next best node.
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.AUTO, failedId = 8, lastGoodId = 7))
        // The way back failed too: no ping-pong between two dead nodes.
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.FALLBACK, failedId = 7, lastGoodId = 7))
        // Nothing worked before it, or what worked is the very node that failed.
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = null))
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = 8))
        assertEquals(Fallback.NEXT, TunnelRequests.fallbackFor(SwitchOrigin.MANUAL, failedId = 8, lastGoodId = 0))
    }

    @Test
    fun anotherNodeOnALiveTunnelShowsAsSwitchingFromTheFirstMoment() {
        val next = plan.copy(serverId = 8, serverName = "DE")
        val moving = TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = null, next = next)
        assertEquals(VoltaVpnService.Switching(7, "NL", 8, "DE"), moving)
    }

    @Test
    fun theLiveNodeAgainIsNoSwitchUnlessAMoveWasUnderWay() {
        // Asked for what already runs (new settings): nothing moves.
        assertNull(TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = null, next = plan))
        // NL → DE was on its way and NL is tapped again: moving back.
        val away = VoltaVpnService.Switching(7, "NL", 8, "DE")
        assertEquals(
            VoltaVpnService.Switching(8, "DE", 7, "NL"),
            TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = away, next = plan)
        )
    }

    @Test
    fun theLatestTapNamesTheTarget() {
        // NL live, DE on its way, then FR: the screen says FR.
        val away = VoltaVpnService.Switching(7, "NL", 8, "DE")
        val fr = plan.copy(serverId = 9, serverName = "FR")
        assertEquals(
            VoltaVpnService.Switching(7, "NL", 9, "FR"),
            TunnelRequests.switchingFor(liveId = 7, liveName = "NL", moving = away, next = fr)
        )
        // The same target again keeps the mark as it is.
        assertEquals(away, TunnelRequests.switchingFor(7, "NL", away, plan.copy(serverId = 8, serverName = "DE")))
    }

    @Test
    fun aSwapNeedsOnlyTheInterfaceNotTheCore() {
        // The core of a failed swap is gone, the interface is up: the next node still swaps in.
        val running = TunnelRequests.tunOf(plan)
        assertTrue(TunnelRequests.canSwapCore(running, tunUp = true, next = running))
    }
}
