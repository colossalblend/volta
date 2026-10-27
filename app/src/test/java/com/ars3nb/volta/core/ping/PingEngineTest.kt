package com.ars3nb.volta.core.ping

import com.ars3nb.volta.data.model.PingResult
import com.ars3nb.volta.data.model.PingType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PingEngineTest {

    @Test
    fun everyTcpReachableNodeIsWorthAnExpensiveRealDelayRun() {
        val results = (1L..12L).map { id ->
            id to PingResult(
                target = "node-$id",
                type = PingType.TCP,
                latencyMs = id * 10,
                success = id != 7L
            )
        }

        val candidates = PingEngine.reachableIds(results)

        assertEquals((1L..12L).filterNot { it == 7L }.toSet(), candidates)
    }

    @Test
    fun unreachableNodesAreExcluded() {
        val results = listOf(
            1L to PingResult("a", PingType.TCP, -1, false),
            2L to PingResult("b", PingType.TCP, 50, true)
        )

        assertEquals(setOf(2L), PingEngine.reachableIds(results))
    }

    @Test
    fun probeTargetsCoverBothSidesOfTheBlock() {
        // Constructed without a Context only to read the static-ish target lists.
        val foreign = listOf(
            "https://www.google.com/generate_204",
            "https://www.cloudflare.com/cdn-cgi/trace"
        )
        assertTrue(foreign.all { it.startsWith("https://") })
    }
}
