package com.ars3nb.volta.core.balancer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaceJudgeTest {

    @Test
    fun theFavouriteAnsweringFirstEndsTheRaceAtOnce() {
        val judge = RaceJudge(size = 4, graceMs = 250)
        judge.record(rank = 0, ok = true, latencyMs = 60, at = 60)
        // Nobody ranked above it: no reason to wait.
        assertTrue(judge.decided(now = 60))
        assertEquals(0, judge.leader())
        assertEquals(60L, judge.latencyOf(0))
    }

    @Test
    fun aBetterRankedNodeWithinTheGraceStillWins() {
        val judge = RaceJudge(size = 3, graceMs = 250)
        judge.record(rank = 2, ok = true, latencyMs = 40, at = 40)
        assertFalse(judge.decided(now = 40))
        judge.record(rank = 0, ok = true, latencyMs = 180, at = 180)
        assertTrue(judge.decided(now = 180))
        assertEquals(0, judge.leader())
    }

    @Test
    fun afterTheGraceTheFirstAnswerTakesIt() {
        val judge = RaceJudge(size = 3, graceMs = 250)
        judge.record(rank = 1, ok = true, latencyMs = 50, at = 50)
        assertFalse(judge.decided(now = 299))
        assertEquals(1L, judge.waitLimit(now = 299))
        assertTrue(judge.decided(now = 300))
        assertEquals(1, judge.leader())
        // The favourite answering after that changes nothing: the race was over.
    }

    @Test
    fun failedFavouritesStopTheWaitEarly() {
        val judge = RaceJudge(size = 3, graceMs = 250)
        judge.record(rank = 2, ok = true, latencyMs = 30, at = 30)
        judge.record(rank = 0, ok = false, latencyMs = -1, at = 35)
        assertFalse(judge.decided(now = 35))
        judge.record(rank = 1, ok = false, latencyMs = -1, at = 50)
        assertTrue(judge.decided(now = 50))
        assertEquals(2, judge.leader())
        assertEquals(1, judge.answers)
    }

    @Test
    fun nobodyAnsweringIsNoWinnerOnceAllAreOut() {
        val judge = RaceJudge(size = 2, graceMs = 250)
        assertNull(judge.waitLimit(now = 0))
        judge.record(rank = 0, ok = false, latencyMs = -1, at = 10)
        assertFalse(judge.decided(now = 10))
        judge.record(rank = 1, ok = false, latencyMs = -1, at = 20)
        assertTrue(judge.decided(now = 20))
        assertNull(judge.leader())
        assertEquals(0, judge.answers)
    }

    @Test
    fun aSecondReportForTheSameContenderIsIgnored() {
        val judge = RaceJudge(size = 2, graceMs = 250)
        judge.record(rank = 1, ok = false, latencyMs = -1, at = 10)
        judge.record(rank = 1, ok = true, latencyMs = 20, at = 20)
        assertNull(judge.leader())
        judge.record(rank = 7, ok = true, latencyMs = 5, at = 30)
        assertNull(judge.leader())
    }
}
