package com.ars3nb.volta.core.vpn

import com.ars3nb.volta.core.vpn.InternetCheck.Generation
import com.ars3nb.volta.core.vpn.InternetCheck.Signals
import com.ars3nb.volta.core.vpn.InternetCheck.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetCheckTest {

    private fun decide(
        airplane: Boolean = false,
        wifi: Boolean = false,
        cellular: Boolean = false,
        kbps: Int = 0,
        generation: Generation = Generation.UNKNOWN,
        level: Int = -1
    ) = InternetCheck.decide(Signals(airplane, wifi, cellular, kbps, generation, level))

    @Test
    fun airplaneModeWithoutWifiMeansNoInternet() {
        assertEquals(Status.AIRPLANE, decide(airplane = true))
    }

    @Test
    fun airplaneModeWithWifiIsFine() {
        assertEquals(Status.OK, decide(airplane = true, wifi = true))
    }

    @Test
    fun nothingAtAllMeansNoNetwork() {
        assertEquals(Status.NO_NETWORK, decide())
    }

    @Test
    fun onlyAirplaneAndNoNetworkStopConnecting() {
        assertTrue(Status.AIRPLANE.blocksConnect)
        assertTrue(Status.NO_NETWORK.blocksConnect)
        assertFalse(Status.WEAK_MOBILE.blocksConnect)
        assertFalse(Status.OK.blocksConnect)
    }

    @Test
    fun twoGeeMobileIsWeak() {
        assertEquals(Status.WEAK_MOBILE, decide(cellular = true, generation = Generation.SLOW_2G, level = 3))
    }

    @Test
    fun noSignalBarsIsWeak() {
        assertEquals(Status.WEAK_MOBILE, decide(cellular = true, generation = Generation.FASTER, level = 0))
    }

    @Test
    fun lteWithBarsIsFine() {
        assertEquals(Status.OK, decide(cellular = true, kbps = 30_000, generation = Generation.FASTER, level = 2))
    }

    @Test
    fun slowEstimateCountsOnlyWhenTheGenerationIsUnknown() {
        assertEquals(Status.WEAK_MOBILE, decide(cellular = true, kbps = 70))
        assertEquals(Status.OK, decide(cellular = true, kbps = 4_300))
        // No estimate at all is not a warning.
        assertEquals(Status.OK, decide(cellular = true, kbps = 0))
        // The generation, when known, wins over a pessimistic estimate.
        assertEquals(Status.OK, decide(cellular = true, kbps = 70, generation = Generation.FASTER, level = 3))
    }

    @Test
    fun wifiAlongsideWeakMobileIsFine() {
        assertEquals(Status.OK, decide(wifi = true, cellular = true, generation = Generation.SLOW_2G, level = 0))
    }
}
