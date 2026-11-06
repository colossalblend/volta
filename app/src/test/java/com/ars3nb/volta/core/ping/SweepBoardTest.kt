package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepBoardTest {

    private fun ok(ms: Long, at: Long = 1_000) = PingResult("n", PingType.TCP, ms, true, timestamp = at)
    private fun silent(at: Long = 1_000) = PingResult("n", PingType.TCP, -1, false, timestamp = at)

    @Test
    fun aNewSweepWaitsOnEveryServer() {
        val board = SweepBoard.IDLE.start(listOf(1L, 2L, 3L))
        assertTrue(board.running)
        assertEquals(3, board.total)
        assertEquals(0, board.done)
        assertEquals(0f, board.fraction)
        assertTrue(listOf(1L, 2L, 3L).all { board.cell(it)?.pending == true })
    }

    @Test
    fun eachAnswerCountsOnceAndSilenceCountsAsChecked() {
        var board = SweepBoard.IDLE.start(listOf(1L, 2L, 3L, 4L))
        board = board.record(1, ok(40))
        board = board.record(2, silent())
        assertEquals(2, board.done)
        assertEquals(1, board.ok)
        assertEquals(0.5f, board.fraction)
        // A retry for the same server replaces its answer, it isn't a third check.
        board = board.record(2, ok(90))
        assertEquals(2, board.done)
        assertEquals(2, board.ok)
        // And a good answer that turns silent takes its point back.
        board = board.record(1, silent())
        assertEquals(1, board.ok)
        assertEquals(90L, board.cell(2)?.latencyMs)
    }

    @Test
    fun serversOutsideTheSweepOrAfterItAreIgnored() {
        val board = SweepBoard.IDLE.start(listOf(1L, 2L))
        assertSame(board, board.record(99, ok(10)))
        val over = board.finish()
        assertSame(over, over.record(1, ok(10)))
        assertSame(SweepBoard.IDLE, SweepBoard.IDLE.record(1, ok(10)))
    }

    @Test
    fun doneNeverPassesTheTotal() {
        var board = SweepBoard.IDLE.start(listOf(1L, 2L))
        repeat(5) { board = board.record(1, ok(10L + it)).record(2, silent()) }
        assertEquals(board.total, board.done)
        assertEquals(1f, board.fraction)
    }

    @Test
    fun aStoppedSweepKeepsItsAnswersAndDropsTheWaiting() {
        val board = SweepBoard.IDLE.start(listOf(1L, 2L, 3L)).record(1, ok(40)).finish()
        assertFalse(board.running)
        assertEquals(40L, board.cell(1)?.latencyMs)
        // Never measured in this sweep: the row shows its stored figure, not a waiting dot.
        assertNull(board.cell(2))
        assertNull(board.cell(3))
    }

    @Test
    fun theFreshAnswerShowsUntilTheDatabaseHasANewerOne() {
        val cell = LiveCheck.of(ok(40, at = 5_000))
        assertTrue(cell.shownOver(storedAt = 4_000))
        assertTrue(cell.shownOver(storedAt = 5_000))
        assertFalse(cell.shownOver(storedAt = 6_000))
        // Waiting always shows as waiting.
        assertTrue(LiveCheck.PENDING.shownOver(storedAt = Long.MAX_VALUE))
    }

    @Test
    fun aSilentAnswerHasNoFigure() {
        val cell = LiveCheck.of(PingResult("n", PingType.TCP, 120, success = false))
        assertFalse(cell.ok)
        assertEquals(-1L, cell.latencyMs)
    }

    @Test
    fun oldFiguresAreStaleNeverMeasuredOnesAreNot() {
        val now = 10 * 60 * 60_000L
        assertFalse(SweepBoard.isStale(lastPingAt = 0, now = now))
        assertFalse(SweepBoard.isStale(lastPingAt = now - 60_000, now = now))
        assertTrue(SweepBoard.isStale(lastPingAt = now - SweepBoard.STALE_AFTER_MS - 1, now = now))
    }
}
