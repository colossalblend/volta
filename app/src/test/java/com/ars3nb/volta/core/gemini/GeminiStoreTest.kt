package com.ars3nb.volta.core.gemini

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cache of verdicts between launches, and the config of the check's own core. */
class GeminiStoreTest {

    @Test
    fun verdictsSurviveARoundTrip() {
        val now = 1_700_000_000_000L
        val results = mapOf(
            1L to GeminiResult(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON, "USA", 200, now - 1_000),
            2L to GeminiResult(GeminiStatus.UNSUPPORTED_REGION, GeminiReason.FLAG_OFF, "RUS", 200, now - 2_000),
            3L to GeminiResult(GeminiStatus.BLOCKED_OR_ERROR, GeminiReason.TIMEOUT, null, 0, now - 3_000)
        )
        assertEquals(results, GeminiChecks.decode(GeminiChecks.encode(results, now)))
    }

    @Test
    fun oldVerdictsAreDropped() {
        val now = 1_700_000_000_000L
        val month = 31L * 24 * 60 * 60_000L
        val results = mapOf(
            1L to GeminiResult(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON, checkedAt = now),
            2L to GeminiResult(GeminiStatus.AVAILABLE, GeminiReason.FLAG_ON, checkedAt = now - month)
        )
        assertEquals(setOf(1L), GeminiChecks.decode(GeminiChecks.encode(results, now)).keys)
    }

    @Test
    fun unreadableStoreIsEmptyAndBadEntriesAreSkipped() {
        assertTrue(GeminiChecks.decode("").isEmpty())
        assertTrue(GeminiChecks.decode("not json").isEmpty())
        val mixed = """[{"id":5,"status":"AVAILABLE","reason":"FLAG_ON","code":200,"at":10},""" +
            """{"id":6,"status":"SOMETHING_NEW","reason":"FLAG_ON","at":10},{"oops":1}]"""
        assertEquals(setOf(5L), GeminiChecks.decode(mixed).keys)
    }

    @Test
    fun ownCoreServesTheProbeOnALocalSocksPort() {
        val probe = """{"log":{"loglevel":"none"},"outbounds":[{"tag":"proxy","protocol":"vless"},""" +
            """{"tag":"direct","protocol":"freedom"}]}"""
        val config = JsonParser.parseString(GeminiProbe.ownCoreConfig(probe, 23456)).asJsonObject
        val inbound = config.getAsJsonArray("inbounds").single().asJsonObject
        assertEquals("socks", inbound.get("protocol").asString)
        assertEquals("127.0.0.1", inbound.get("listen").asString)
        assertEquals(23456, inbound.get("port").asInt)
        // The proxy stays first: with no routing, the first outbound takes everything.
        assertEquals("proxy", config.getAsJsonArray("outbounds")[0].asJsonObject.get("tag").asString)
    }
}
