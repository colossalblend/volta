package com.ars3nb.volta.core.balancer

import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardPolicyTest {

    private fun node(role: ServerRole, protocol: ProtocolType = ProtocolType.VLESS) =
        ServerEntity(remark = "n", address = "a", port = 1, role = role, protocol = protocol)

    @Test
    fun withoutLteServersEveryAutoModeRunsAsHome() {
        assertFalse(GuardPolicy.hasLte(listOf(node(ServerRole.NORMAL), node(ServerRole.AUTO))))
        // An OpenFlux LTE node counts like any other: auto modes pick it.
        assertTrue(GuardPolicy.hasLte(listOf(node(ServerRole.BYPASS_WHITELIST, ProtocolType.OPENFLUX))))
        assertFalse(GuardPolicy.hasLte(listOf(node(ServerRole.NORMAL, ProtocolType.OPENFLUX))))
        assertTrue(GuardPolicy.hasLte(listOf(node(ServerRole.BYPASS_WHITELIST))))

        assertEquals(ConnectMode.AUTO_HOME, GuardPolicy.effectiveMode(ConnectMode.AUTO_HOME_LTE, false))
        assertEquals(ConnectMode.AUTO_HOME, GuardPolicy.effectiveMode(ConnectMode.AUTO_LTE, false))
        assertEquals(ConnectMode.MANUAL, GuardPolicy.effectiveMode(ConnectMode.MANUAL, false))
        assertEquals(ConnectMode.AUTO_HOME_LTE, GuardPolicy.effectiveMode(ConnectMode.AUTO_HOME_LTE, true))
    }

    @Test
    fun aDocumentCarrierIsNotSlowJustForBeingADocument() {
        val docs = node(ServerRole.BYPASS_WHITELIST, ProtocolType.OPENFLUX).copy(network = "yandex+mailru")
        val direct = node(ServerRole.NORMAL, ProtocolType.OPENFLUX).copy(network = "direct")
        val mixed = node(ServerRole.NORMAL, ProtocolType.OPENFLUX).copy(network = "direct+vyandex")
        assertEquals(GuardPolicy.SLOW_ABS_OPENFLUX_MS, GuardPolicy.slowBarMs(docs))
        assertEquals(GuardPolicy.SLOW_ABS_OPENFLUX_MS, GuardPolicy.slowBarMs(mixed))
        assertEquals(GuardPolicy.SLOW_ABS_MS, GuardPolicy.slowBarMs(direct))
        assertEquals(GuardPolicy.SLOW_ABS_MS, GuardPolicy.slowBarMs(node(ServerRole.NORMAL)))

        assertFalse(GuardPolicy.isSlow(1_400, 1_200, GuardPolicy.slowBarMs(docs)))  // its usual pace
        assertTrue(GuardPolicy.isSlow(3_500, 1_200, GuardPolicy.slowBarMs(docs)))   // slow even for it
        assertTrue(GuardPolicy.isSlow(1_400, 400, GuardPolicy.slowBarMs(docs)))     // 3.5× its usual
        assertTrue(GuardPolicy.isSlow(1_400, 1_200, GuardPolicy.slowBarMs(direct)))
    }

    @Test
    fun slowMeansSlowForThisNode() {
        assertTrue(GuardPolicy.isSlow(950, 900))      // slow in absolute terms
        assertTrue(GuardPolicy.isSlow(400, 120))      // 3× its usual
        assertFalse(GuardPolicy.isSlow(200, 60))      // 3×, but still fast
        assertFalse(GuardPolicy.isSlow(400, 300))     // a little worse than usual
        assertFalse(GuardPolicy.isSlow(-1, 100))      // no answer is not "slow"
        assertFalse(GuardPolicy.isSlow(500, -1))      // never measured: only the absolute bar
    }

    @Test
    fun trafficAndTunnelChecks() {
        assertTrue(GuardPolicy.trafficProvesAlive(64 * 1024))
        assertFalse(GuardPolicy.trafficProvesAlive(2_000))
        assertTrue(GuardPolicy.tunnelConfirmsSlow(-1))
        assertTrue(GuardPolicy.tunnelConfirmsSlow(2_000))
        assertFalse(GuardPolicy.tunnelConfirmsSlow(300))
    }

    @Test
    fun scheduleStretchesOnLowBattery() {
        assertEquals(GuardPolicy.BASE_INTERVAL_MS, GuardPolicy.interval(false, false, false))
        assertEquals(GuardPolicy.BASE_INTERVAL_MS * 2, GuardPolicy.interval(true, false, false))
        assertEquals(GuardPolicy.BASE_INTERVAL_MS * 2, GuardPolicy.interval(false, true, false))
        assertEquals(GuardPolicy.BASE_INTERVAL_MS, GuardPolicy.interval(true, true, true))
        assertFalse(GuardPolicy.fullAllowed(batteryLow = true, charging = false))
        assertTrue(GuardPolicy.fullAllowed(batteryLow = true, charging = true))
    }

    @Test
    fun limiterHoldsStillAfterFlapping() {
        val limiter = GuardPolicy.SwitchLimiter(max = 3, windowMs = 10_000, cooldownMs = 20_000)
        assertTrue(limiter.allow(0))
        limiter.record(0)
        limiter.record(1_000)
        assertTrue(limiter.allow(2_000))
        limiter.record(2_000)          // third switch in the window: cool down
        assertFalse(limiter.allow(3_000))
        assertFalse(limiter.allow(21_000))
        assertTrue(limiter.allow(23_000))
        // Old switches age out of the window.
        val calm = GuardPolicy.SwitchLimiter(max = 3, windowMs = 10_000, cooldownMs = 20_000)
        calm.record(0)
        calm.record(1_000)
        assertTrue(calm.allow(15_000))
    }
}
