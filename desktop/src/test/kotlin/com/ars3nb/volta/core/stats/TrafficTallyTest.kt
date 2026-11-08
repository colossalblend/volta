package com.ars3nb.volta.core.stats


import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrafficTallyTest {

    private fun day(date: LocalDate, up: Long, down: Long) =
        TrafficDayEntity(day = date.toEpochDay(), uplinkBytes = up, downlinkBytes = down)

    @Test
    fun totalsSplitByCalendarMonth() {
        val today = LocalDate.of(2026, 11, 4)
        val days = listOf(
            day(LocalDate.of(2026, 9, 30), 1_000, 9_000), // two months back: in no total
            day(LocalDate.of(2026, 10, 1), 100, 900),
            day(LocalDate.of(2026, 10, 31), 50, 450),
            day(LocalDate.of(2026, 11, 1), 10, 90),
            day(today, 5, 45)
        )
        val s = TrafficTally.summarize(days, today)
        assertEquals(50, s.today)
        assertEquals(150, s.thisMonth)
        assertEquals(1_500, s.lastMonth)
        assertEquals(Month.NOVEMBER, s.thisMonthOf)
        assertEquals(Month.OCTOBER, s.lastMonthOf)
        assertFalse(s.isEmpty)
    }

    @Test
    fun recentDaysEndWithTodayAndFillGaps() {
        val today = LocalDate.of(2026, 3, 2)
        val s = TrafficTally.summarize(
            listOf(day(today, 1, 1), day(today.minusDays(2), 0, 7), day(today.minusDays(13), 3, 0)),
            today
        )
        assertEquals(TrafficTally.RECENT_DAYS, s.recent.size)
        assertEquals(2L, s.recent.last())
        assertEquals(0L, s.recent[s.recent.size - 2])
        assertEquals(7L, s.recent[s.recent.size - 3])
        assertEquals(3L, s.recent.first())
        // Across the new year: January is the month before February… and December before January.
        assertEquals(Month.FEBRUARY, TrafficTally.summarize(emptyList(), today).lastMonthOf)
        assertEquals(Month.DECEMBER, TrafficTally.summarize(emptyList(), LocalDate.of(2027, 1, 15)).lastMonthOf)
    }

    @Test
    fun eachBarKnowsItsDayAndBothDirections() {
        val today = LocalDate.of(2026, 11, 6)
        val s = TrafficTally.summarize(listOf(day(today, 30, 270), day(today.minusDays(13), 4, 6)), today)
        assertEquals(today.minusDays(13).toEpochDay(), s.firstDay)
        assertEquals(270L, s.recentDown.last())
        assertEquals(30L, s.recentUp.last())
        assertEquals(6L, s.recentDown.first())
        assertEquals(4L, s.recentUp.first())
        assertEquals(TrafficTally.RECENT_DAYS, s.recentDown.size)
        assertEquals(TrafficTally.RECENT_DAYS, s.recentUp.size)
        s.recent.indices.forEach { assertEquals(s.recent[it], s.recentDown[it] + s.recentUp[it]) }
    }

    @Test
    fun aDayAfterTodayCountsNowhere() {
        val today = LocalDate.of(2026, 11, 4)
        val s = TrafficTally.summarize(listOf(day(today.plusDays(1), 10, 10)), today)
        assertEquals(0, s.thisMonth)
        assertTrue(s.isEmpty)
    }

    @Test
    fun theWindowReachesTheStartOfLastMonth() {
        // The latest "today" relative to the first of last month: 31 August → 1 July.
        val today = LocalDate.of(2026, 8, 31)
        val firstOfLast = today.withDayOfMonth(1).minusMonths(1)
        assertTrue(today.minusDays(TrafficTally.WINDOW_DAYS) <= firstOfLast)
    }

    @Test
    fun dayOfFollowsTheGivenClock() {
        val moscow = ZoneId.of("Europe/Moscow")
        val lateEvening = LocalDateTime.of(2026, 11, 4, 23, 59).atZone(moscow).toInstant().toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 4).toEpochDay(), TrafficTally.dayOf(lateEvening, moscow))
        // The same moment is already the 5th further east.
        assertEquals(LocalDate.of(2026, 11, 5).toEpochDay(), TrafficTally.dayOf(lateEvening, ZoneOffset.ofHours(9)))
    }

    @Test
    fun pendingKeepsDaysApartAndEmptiesOnDrain() {
        val pending = PendingTraffic()
        assertTrue(pending.isEmpty())
        pending.add(10, 1, 2)
        pending.add(10, 3, 4)
        pending.add(11, 5, 0)
        pending.add(11, 0, 0)   // nothing: ignored
        pending.add(12, -5, -1) // a counter that went backwards is not traffic
        val batch = pending.drain()
        assertEquals(listOf(10L, 11L), batch.keys.toList())
        assertEquals(PendingTraffic.Bytes(4, 6), batch[10L])
        assertEquals(PendingTraffic.Bytes(5, 0), batch[11L])
        assertTrue(pending.isEmpty())
    }
}
