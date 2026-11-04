package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.core.helper.ProxyHelper
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.VpnConnectionState
import org.junit.Assert.assertFalse
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
        assertTrue(TunnelRequests.canSwapCore(running, coreUp = true, next = TunnelRequests.tunOf(otherNode)))
    }

    @Test
    fun aDifferentInterfaceOrNoTunnelRebuilds() {
        val running = TunnelRequests.tunOf(plan)
        val newList = TunnelRequests.tunOf(plan.copy(perAppPackages = emptySet()))
        assertFalse(TunnelRequests.canSwapCore(running, coreUp = true, next = newList))
        assertFalse(TunnelRequests.canSwapCore(running, coreUp = false, next = running))
        assertFalse(TunnelRequests.canSwapCore(null, coreUp = true, next = running))
    }

    @Test
    fun theMtuIsComparedAsTheInterfaceGetsIt() {
        // Both out of range end up at 9000: the same interface.
        val a = TunnelRequests.tunOf(plan.copy(mtu = 12_000))
        val b = TunnelRequests.tunOf(plan.copy(mtu = 20_000))
        assertTrue(TunnelRequests.canSwapCore(a, coreUp = true, next = b))
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
}
