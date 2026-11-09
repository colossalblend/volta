package com.ars3nb.volta.core.stats

import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

/** What the traffic card shows: totals for today and two months, and the recent days. */
data class TrafficSummary(
    val today: Long = 0,
    val thisMonth: Long = 0,
    val lastMonth: Long = 0,
    /** Bytes per day for the last [TrafficTally.RECENT_DAYS] days, oldest first, today last. */
    val recent: List<Long> = emptyList(),
    /** The same days split into what came in and what went out, for the day the user taps. */
    val recentDown: List<Long> = emptyList(),
    val recentUp: List<Long> = emptyList(),
    /** The first bar's day as `LocalDate.toEpochDay()`; bar i is that day plus i. */
    val firstDay: Long = 0,
    /** The months the two totals are for; the card names them in the app's language. */
    val thisMonthOf: Month = Month.JANUARY,
    val lastMonthOf: Month = Month.DECEMBER
) {
    /** Nothing has gone through the VPN in the time the card covers. */
    val isEmpty: Boolean get() = thisMonth <= 0 && lastMonth <= 0 && recent.all { it <= 0 }
}

/**
 * Bytes that went through the VPN server on one calendar day of the computer's clock, as
 * the Android app's TrafficDayEntity: only traffic of the proxy outbound counts.
 */
data class TrafficDayEntity(
    /** The day as LocalDate.toEpochDay(). */
    val day: Long,
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0
) {
    val totalBytes: Long get() = uplinkBytes + downlinkBytes
}

/** Turns the stored days into the card's numbers (the Android app's TrafficTally). */
object TrafficTally {

    /** Bars on the card: two weeks. */
    const val RECENT_DAYS = 14

    /** How far back the card reads: enough for all of last month, whatever today is. */
    const val WINDOW_DAYS = 70L

    /** The calendar day of a moment, on the phone's clock. */
    fun dayOf(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    fun summarize(days: List<TrafficDayEntity>, today: LocalDate): TrafficSummary {
        val todayKey = today.toEpochDay()
        val monthStart = today.withDayOfMonth(1)
        val lastMonthStart = monthStart.minusMonths(1)
        val byDay = HashMap<Long, Long>()
        val downByDay = HashMap<Long, Long>()
        val upByDay = HashMap<Long, Long>()
        var thisMonth = 0L
        var lastMonth = 0L
        for (d in days) {
            val bytes = d.totalBytes.coerceAtLeast(0)
            // A day after today means the clock was set back; it belongs to no total.
            if (d.day > todayKey) continue
            byDay[d.day] = (byDay[d.day] ?: 0L) + bytes
            downByDay[d.day] = (downByDay[d.day] ?: 0L) + d.downlinkBytes.coerceAtLeast(0)
            upByDay[d.day] = (upByDay[d.day] ?: 0L) + d.uplinkBytes.coerceAtLeast(0)
            val date = LocalDate.ofEpochDay(d.day)
            if (!date.isBefore(monthStart)) {
                thisMonth += bytes
            } else if (!date.isBefore(lastMonthStart)) {
                lastMonth += bytes
            }
        }
        return TrafficSummary(
            today = byDay[todayKey] ?: 0L,
            thisMonth = thisMonth,
            lastMonth = lastMonth,
            recent = (RECENT_DAYS - 1 downTo 0).map { back -> byDay[todayKey - back] ?: 0L },
            recentDown = (RECENT_DAYS - 1 downTo 0).map { back -> downByDay[todayKey - back] ?: 0L },
            recentUp = (RECENT_DAYS - 1 downTo 0).map { back -> upByDay[todayKey - back] ?: 0L },
            firstDay = todayKey - (RECENT_DAYS - 1),
            thisMonthOf = today.month,
            lastMonthOf = lastMonthStart.month
        )
    }
}

/**
 * Bytes counted but not written yet, kept apart per day so a session that runs past
 * midnight puts each part on its own day. Thread-safe: the tunnel adds, a writer drains.
 */
class PendingTraffic {

    data class Bytes(val up: Long, val down: Long)

    private val days = LinkedHashMap<Long, Bytes>()

    @Synchronized
    fun add(day: Long, up: Long, down: Long) {
        val u = up.coerceAtLeast(0)
        val d = down.coerceAtLeast(0)
        if (u == 0L && d == 0L) return
        val old = days[day]
        days[day] = Bytes((old?.up ?: 0L) + u, (old?.down ?: 0L) + d)
    }

    /** Everything counted so far, oldest day first; the counter starts empty again. */
    @Synchronized
    fun drain(): Map<Long, Bytes> {
        val out = LinkedHashMap(days)
        days.clear()
        return out
    }

    @Synchronized
    fun isEmpty(): Boolean = days.isEmpty()
}
