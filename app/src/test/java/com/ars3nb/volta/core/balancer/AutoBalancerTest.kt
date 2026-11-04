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
    fun openFluxNodesAreInTheirGroupAndRaceAfterTheRest() {
        val ofHome = server(1, ServerRole.NORMAL).copy(protocol = ProtocolType.OPENFLUX, network = "direct")
        val ofLte = server(2, ServerRole.BYPASS_WHITELIST).copy(protocol = ProtocolType.OPENFLUX, network = "yandex")
        val home = server(3, ServerRole.NORMAL, ewma = 300)
        val lte = server(4, ServerRole.BYPASS_WHITELIST, ewma = 200)
        val all = listOf(ofHome, ofLte, home, lte)

        assertEquals(listOf(1L, 3L), AutoBalancer.poolFor(all, BalancerScope.NORMAL).map { it.id })
        assertEquals(listOf(2L, 4L), AutoBalancer.poolFor(all, BalancerScope.BYPASS).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), AutoBalancer.poolFor(all, BalancerScope.ALL).map { it.id })
        assertEquals(
            listOf(2L, 4L),
            AutoBalancer.filterByRegime(all, NetworkRegime.WHITELIST_ONLY).map { it.id }
        )
        // A white-listed network with only an OpenFlux node for LTE still has one to use.
        assertEquals(
            listOf(2L),
            AutoBalancer.filterByRegime(listOf(ofHome, ofLte, home), NetworkRegime.WHITELIST_ONLY).map { it.id }
        )

        // The connect-time race: regular nodes (best known first), OpenFlux only after.
        assertEquals(
            listOf(listOf(4L, 3L), listOf(1L, 2L)),
            AutoBalancer.raceTiers(all).map { tier -> tier.map { it.id } }
        )
        assertEquals(listOf(listOf(2L)), AutoBalancer.raceTiers(listOf(ofLte)).map { tier -> tier.map { it.id } })
    }

    @Test
    fun aGuessNeverPushesOutANodeThatWorks() {
        val holder = server(1, ewma = 400)
        val openFlux = server(2).copy(protocol = ProtocolType.OPENFLUX, network = "yandex")
        val other = server(3, ewma = 380)
        val pool = listOf(holder, openFlux, other)
        fun rank(s: ServerEntity, ms: Long, ok: Boolean = true) = AutoBalancer.Ranked(s, ms, ms, ok)

        // The OpenFlux node only looks fast (its carrier is next door, its exit never
        // timed): the node in use stays.
        val ranked = listOf(rank(openFlux, 90), rank(other, 380), rank(holder, 400))
        assertEquals(1L, AutoBalancer.select(ranked, holder, pool, guessed = setOf(2L))?.id)
        // Once its exit has been timed, it competes like anyone.
        assertEquals(2L, AutoBalancer.select(ranked, holder, pool)?.id)
        // The node in use failed: the guess is better than nothing.
        val failing = listOf(rank(openFlux, 90), rank(holder, -1, ok = false))
        assertEquals(2L, AutoBalancer.select(failing, holder, pool, guessed = setOf(2L))?.id)
        // No node to beat (connect time, a node that just died): the best that answered.
        assertEquals(2L, AutoBalancer.select(ranked, null, pool, guessed = setOf(2L))?.id)
        // A clearly faster measured node still takes over past the guess.
        val faster = listOf(rank(openFlux, 90), rank(other, 150), rank(holder, 400))
        assertEquals(3L, AutoBalancer.select(faster, holder, pool, guessed = setOf(2L))?.id)
        // Nothing answered: keep the holder.
        val silent = listOf(rank(openFlux, -1, ok = false), rank(holder, -1, ok = false))
        assertEquals(1L, AutoBalancer.select(silent, holder, pool, guessed = setOf(2L))?.id)
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
}
