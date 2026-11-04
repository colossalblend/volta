package com.ars3nb.volta.ui.screens

import com.ars3nb.volta.data.model.ServerEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ServerShapeTest {

    private val server = ServerEntity(id = 3, remark = "🇳🇱 Amsterdam", address = "203.0.113.5", port = 443)

    @Test
    fun aPingLeavesTheShapeAsItWas() {
        val pinged = server.copy(latencyMs = 48, ewmaMs = 52, failStreak = 0, lastPingAt = 1_000)
        val failed = server.copy(latencyMs = -1, failStreak = 3, lastPingAt = 2_000)
        val starred = server.copy(isFavorite = true, isSelected = true)
        for (other in listOf(pinged, failed, starred)) {
            assertEquals(withoutMeasurements(server), withoutMeasurements(other))
        }
    }

    @Test
    fun whatDecidesDiallingOrTheCountryChangesIt() {
        assertNotEquals(withoutMeasurements(server), withoutMeasurements(server.copy(remark = "🇩🇪 Frankfurt")))
        assertNotEquals(withoutMeasurements(server), withoutMeasurements(server.copy(security = "reality")))
        assertNotEquals(withoutMeasurements(server), withoutMeasurements(server.copy(id = 4)))
    }
}
