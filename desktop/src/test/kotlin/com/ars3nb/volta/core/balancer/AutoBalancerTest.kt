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
        assertEquals(listOf(2L), filtered.map { it.id })

        // With no bypass nodes tagged at all, the user still gets something to connect to,
        // but never a provider balancer.
        val noBypass = listOf(server(1, ServerRole.NORMAL), server(3, ServerRole.AUTO))
        assertEquals(
            listOf(1L),
            AutoBalancer.filterByRegime(noBypass, NetworkRegime.WHITELIST_ONLY).map { it.id }
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
        // 500 -> 460 clears 30ms but is only an 8% win.
        assertFalse(AutoBalancer.beats(ranked(2, 460), ranked(1, 500)))
        // 120 -> 100 is 20ms: below the absolute margin.
        assertFalse(AutoBalancer.beats(ranked(2, 100), ranked(1, 120)))
        // 200 -> 130 clears both 30ms and the 0.85 ratio.
        assertTrue(AutoBalancer.beats(ranked(2, 130), ranked(1, 200)))
        // 1000 -> 900 clears the 30ms margin but is only a 10% win.
        assertFalse(AutoBalancer.beats(ranked(2, 900), ranked(1, 1000)))
        // The case from the field: 44 ms waiting behind a 119 ms node in use.
        assertTrue(AutoBalancer.beats(ranked(2, 44), ranked(1, 119)))
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
    fun eachScopePicksItsOwnPoolAndProviderBalancersAreInNone() {
        val pool = listOf(
            server(1, ServerRole.NORMAL),
            server(2, ServerRole.BYPASS_WHITELIST),
            server(3, ServerRole.AUTO)
        )

        assertEquals(
            listOf(1L, 2L),
            AutoBalancer.poolFor(pool, BalancerScope.ALL).map { it.id }
        )
        assertEquals(
            listOf(1L),
            AutoBalancer.poolFor(pool, BalancerScope.NORMAL).map { it.id }
        )
        assertEquals(
            listOf(2L),
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

    @Test
    fun medianAndSpreadIgnoreLostSamples() {
        val q = AutoBalancer.quality(listOf(120, -1, 80, 100))
        assertEquals(100L, q.medianMs)
        assertEquals(40L, q.jitterMs)
        assertEquals(0.25, q.loss, 1e-9)
        assertEquals(-1L, AutoBalancer.quality(listOf(-1, -1)).medianMs)
    }

    @Test
    fun steadyNodeBeatsAJitteryOneWithTheSameMedian() {
        val steady = AutoBalancer.effectiveMs(AutoBalancer.quality(listOf(150, 155, 160)), 0)
        val jittery = AutoBalancer.effectiveMs(AutoBalancer.quality(listOf(60, 150, 600)), 0)
        assertTrue("steady=$steady jittery=$jittery", steady < jittery)
    }

    @Test
    fun lostSamplesAndRecentFailuresOutweighAFewMilliseconds() {
        val clean = AutoBalancer.effectiveMs(AutoBalancer.quality(listOf(200, 210, 205)), 0)
        val lossy = AutoBalancer.effectiveMs(AutoBalancer.quality(listOf(90, -1, 95)), 0)
        assertTrue("clean=$clean lossy=$lossy", clean < lossy)

        val once = AutoBalancer.quality(listOf(100))
        assertTrue(AutoBalancer.effectiveMs(once, 2) > AutoBalancer.effectiveMs(once, 0))
        assertEquals(-1L, AutoBalancer.effectiveMs(AutoBalancer.quality(listOf(-1, -1)), 0))
    }

    @Test
    fun homeLteRunRemembersTheGroupTheNetworkNeeds() {
        assertEquals(BalancerScope.NORMAL, AutoBalancer.groupOf(BalancerScope.ALL, NetworkRegime.OPEN))
        assertEquals(
            BalancerScope.BYPASS,
            AutoBalancer.groupOf(BalancerScope.ALL, NetworkRegime.WHITELIST_ONLY)
        )
        assertEquals(null, AutoBalancer.groupOf(BalancerScope.ALL, NetworkRegime.BLOCKED))
        assertEquals(BalancerScope.BYPASS, AutoBalancer.groupOf(BalancerScope.BYPASS, NetworkRegime.UNKNOWN))
    }

    // ------------------------------------------------------------------ connect-time race

    @Test
    fun theRaceStartsWithTheRememberedNodeThenTheBestRecentOnes() {
        val now = 100 * 60 * 60_000L
        val recentFast = server(1, ewma = 40).copy(latencyMs = 40, lastPingAt = now - 60_000)
        val recentSlow = server(2, ewma = 90).copy(latencyMs = 90, lastPingAt = now - 60_000)
        val staleFastest = server(3, ewma = 20).copy(latencyMs = 20, lastPingAt = now - AutoBalancer.RECENT_MS - 1)
        val holder = server(4, ewma = 150).copy(latencyMs = 150, lastPingAt = now - 60_000)
        val never = server(5)
        val pool = listOf(recentFast, recentSlow, staleFastest, holder, never)

        val contenders = AutoBalancer.raceContenders(pool, holder, now)

        // The node that worked here last, then recent before stale, each by its record;
        // a node never measured has nothing to race on.
        assertEquals(listOf(4L, 1L, 2L, 3L), contenders.map { it.id })
    }

    @Test
    fun theRaceIsSmallAndLeavesOutFailingAndGuessedNodes() {
        val now = 100 * 60 * 60_000L
        fun measured(id: Long, ms: Long) = server(id, ewma = ms).copy(latencyMs = ms, lastPingAt = now)
        val pool = (1L..8L).map { measured(it, 10 * it) } +
            measured(20, 5).copy(failStreak = 1) +
            measured(21, 5).copy(protocol = ProtocolType.OPENFLUX)
        val failingHolder = measured(30, 50).copy(failStreak = AutoBalancer.INSTANT_MAX_FAILS)

        val contenders = AutoBalancer.raceContenders(pool + failingHolder, failingHolder, now)

        assertEquals(AutoBalancer.RACE_SIZE, contenders.size)
        assertEquals(listOf(1L, 2L, 3L, 4L), contenders.map { it.id })
    }

    @Test
    fun anOpenFluxHolderStillRacesAndAHolderOutsideThePoolDoesNot() {
        val now = 1_000L
        val pool = listOf(server(1, ewma = 40).copy(latencyMs = 40, lastPingAt = now))
        val openFlux = server(2).copy(protocol = ProtocolType.OPENFLUX)

        assertEquals(listOf(2L, 1L), AutoBalancer.raceContenders(pool + openFlux, openFlux, now).map { it.id })
        assertEquals(listOf(1L), AutoBalancer.raceContenders(pool, openFlux, now).map { it.id })
        assertTrue(AutoBalancer.raceContenders(listOf(server(9)), null, now).isEmpty())
    }
}
