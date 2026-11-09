package com.ars3nb.volta.core.balancer

import java.util.Collections
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LastWinsTest {

    @Test
    fun rapidTapsMoveOnceToTheLast() = runBlocking {
        val done = Collections.synchronizedList(mutableListOf<Int>())
        val wins = LastWins<Int>(this, quietMs = 60) { done += it }
        listOf(1, 2, 3, 4, 5).forEach { wins.submit(it) }
        delay(250)
        assertEquals(listOf(5), done.toList())
    }

    @Test
    fun tapsFarApartEachCount() = runBlocking {
        val done = Collections.synchronizedList(mutableListOf<Int>())
        val wins = LastWins<Int>(this, quietMs = 20) { done += it }
        wins.submit(1)
        delay(150)
        wins.submit(2)
        delay(150)
        assertEquals(listOf(1, 2), done.toList())
    }

    @Test
    fun aNewerTapCancelsOneStillAtWork() = runBlocking {
        val done = Collections.synchronizedList(mutableListOf<Int>())
        val wins = LastWins<Int>(this, quietMs = 10) { value ->
            delay(if (value == 1) 200 else 0)
            done += value
        }
        wins.submit(1)
        delay(60) // 1 is past its quiet time and working
        wins.submit(2)
        delay(300)
        assertEquals(listOf(2), done.toList())
    }

    @Test
    fun onlyTheNewestTicketIsCurrent() = runBlocking {
        val wins = LastWins<Int>(this, quietMs = 1_000) { }
        val first = wins.submit(1)
        val second = wins.submit(2)
        assertFalse(wins.isLatest(first))
        assertTrue(wins.isLatest(second))
        wins.cancel()
        assertFalse(wins.isLatest(second))
    }

    @Test
    fun cancelDropsWhatWaits() = runBlocking {
        val done = Collections.synchronizedList(mutableListOf<Int>())
        val wins = LastWins<Int>(this, quietMs = 30) { done += it }
        wins.submit(1)
        wins.cancel()
        delay(120)
        assertTrue(done.isEmpty())
    }
}
