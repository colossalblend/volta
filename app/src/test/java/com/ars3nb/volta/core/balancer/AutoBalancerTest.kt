package com.ars3nb.volta.core.balancer

import com.ars3nb.volta.core.ping.NetworkProbe
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBalancerTest {

    private fun probe(category: SiteCategory, reachable: Boolean) =
        SiteProbeResult("https://x", "x", category, if (reachable) 40 else -1, reachable)

    private fun server(
        id: Long,
        role: ServerRole = ServerRole.NORMAL,
        ewma: Long = -1,
        fails: Int = 0,
        favorite: Boolean = false
    ) = ServerEntity(
        id = id,
        remark = "N$id",
        protocol = ProtocolType.VLESS,
        address = "n$id.example",
        port = 443,
        uuid = "id",
        ewmaMs = ewma,
        failStreak = fails,
        isFavorite = favorite,
        role = role
    )

    private fun ranked(id: Long, ewma: Long, ok: Boolean = true, favorite: Boolean = false) =
        AutoBalancer.Ranked(server(id, favorite = favorite), ewma, ewma, ok)

    // ------------------------------------------------------------------ regime

    @Test
    fun foreignReachabilityWinsOverWhitelist() {
        val regime = AutoBalancer.detectRegime(
            listOf(
                probe(SiteCategory.FOREIGN, true),
                probe(SiteCategory.RU_WHITELIST, true)
            )
        )
        assertEquals(NetworkRegime.OPEN, regime)
    }

    @Test
    fun onlyRussianSitesMeansWhitelistRegime() {
        val regime = AutoBalancer.detectRegime(
            listOf(
                probe(SiteCategory.FOREIGN, false),
                probe(SiteCategory.RU_WHITELIST, true)
            )
        )
        assertEquals(NetworkRegime.WHITELIST_ONLY, regime)
    }

    @Test
    fun nothingReachableMeansBlocked() {
        assertEquals(
            NetworkRegime.BLOCKED,
            AutoBalancer.detectRegime(listOf(probe(SiteCategory.FOREIGN, false)))
        )
    }

    @Test
    fun whitelistRegimePrefersBypassNodesButNeverEmptiesThePool() {
        val pool = listOf(
            server(1, ServerRole.NORMAL),
            server(2, ServerRole.BYPASS_WHITELIST),
            server(3, ServerRole.AUTO)
        )
        val filtered = AutoBalancer.filterByRegime(pool, NetworkRegime.WHITELIST_ONLY)
        assertEquals(listOf(2L, 3L), filtered.map { it.id })

        // With no bypass nodes tagged at all, the user still gets something to connect to.
        val onlyNormal = listOf(server(1, ServerRole.NORMAL))
        assertEquals(
            onlyNormal,
            AutoBalancer.filterByRegime(onlyNormal, NetworkRegime.WHITELIST_ONLY)
        )
    }

    // -------------------------------------------------------------------- ewma

    @Test
    fun firstSampleSeedsTheAverageAndLaterOnesSmoothIt() {
        assertEquals(100L, AutoBalancer.blendEwma(-1, 100))
        // 100 -> 200 moves only part of the way, not all of it.
        val blended = AutoBalancer.blendEwma(100, 200)
        assertTrue(blended in 101..199)
        assertEquals(140L, blended)
    }

    @Test
    fun failedMeasurementLeavesTheAverageAlone() {
        assertEquals(120L, AutoBalancer.blendEwma(120, -1))
    }

    // -------------------------------------------------------------- hysteresis

    @Test
    fun marginallyFasterNodeDoesNotStealTheConnection() {
        // 500 -> 460 is 40ms: below the absolute margin.
        assertFalse(AutoBalancer.beats(ranked(2, 460), ranked(1, 500)))
        // 200 -> 130 clears 60ms but not the 0.8 ratio... 130 <= 160, so it does.
        assertTrue(AutoBalancer.beats(ranked(2, 130), ranked(1, 200)))
        // 1000 -> 900 clears the 60ms margin but is only a 10% win.
        assertFalse(AutoBalancer.beats(ranked(2, 900), ranked(1, 1000)))
    }

    @Test
    fun clearlyFasterNodeTakesOver() {
        assertTrue(AutoBalancer.beats(ranked(2, 80), ranked(1, 400)))
    }

    @Test
    fun unmeasuredChallengerNeverWinsAndUnmeasuredHolderAlwaysLoses() {
        assertFalse(AutoBalancer.beats(ranked(2, -1), ranked(1, 300)))
        assertTrue(AutoBalancer.beats(ranked(2, 300), ranked(1, -1)))
    }

    // ------------------------------------------------------------------- score

    @Test
    fun rankingPrefersLowLatencyHealthyFavourites() {
        val fast = ranked(1, 100)
        val fastFavourite = ranked(2, 100, favorite = true)
        val slow = ranked(3, 400)
        val dead = ranked(4, 100, ok = false)

        val order = listOf(slow, dead, fast, fastFavourite).sortedBy { it.score }.map { it.server.id }
        assertEquals(listOf(2L, 1L, 3L, 4L), order)
    }

    // ------------------------------------------------------------------ scopes

    @Test
    fun eachScopePicksItsOwnPoolAndAutoNodesServeBoth() {
        val pool = listOf(
            server(1, ServerRole.NORMAL),
            server(2, ServerRole.BYPASS_WHITELIST),
            server(3, ServerRole.AUTO)
        )

        assertEquals(
            listOf(1L, 2L, 3L),
            AutoBalancer.poolFor(pool, BalancerScope.ALL).map { it.id }
        )
        assertEquals(
            listOf(1L, 3L),
            AutoBalancer.poolFor(pool, BalancerScope.NORMAL).map { it.id }
        )
        assertEquals(
            listOf(2L, 3L),
            AutoBalancer.poolFor(pool, BalancerScope.BYPASS).map { it.id }
        )
    }

    @Test
    fun openNetworkUsesNormalNodesAndWhitelistUsesBypassNodes() {
        val pool = listOf(
            server(1, ServerRole.NORMAL),
            server(2, ServerRole.BYPASS_WHITELIST)
        )

        assertEquals(
            listOf(1L),
            AutoBalancer.filterByRegime(pool, NetworkRegime.OPEN).map { it.id }
        )
        assertEquals(
            listOf(2L),
            AutoBalancer.filterByRegime(pool, NetworkRegime.WHITELIST_ONLY).map { it.id }
        )
    }

    @Test
    fun halfTheEndpointsAnsweringCountsAsAnOpenInternet() {
        val probe = NetworkProbe()
        // Matches the SpeedTester rule: success * 2 >= total.
        assertTrue(probe.isMajority(2, 4))
        assertTrue(probe.isMajority(3, 4))
        assertFalse(probe.isMajority(1, 4))
        assertFalse(probe.isMajority(0, 0))
    }

    // ------------------------------------------------------------------- score

    @Test
    fun serverScoreSinksFlakyAndUnmeasuredNodes() {
        val healthy = server(1, ewma = 200)
        val flaky = server(2, ewma = 200, fails = 3)
        val unmeasured = server(3)

        assertTrue(healthy.score < flaky.score)
        assertTrue(flaky.score < unmeasured.score)
        assertTrue(healthy.isHealthy)
        assertFalse(flaky.isHealthy)
        assertFalse(unmeasured.isHealthy)
    }
}
