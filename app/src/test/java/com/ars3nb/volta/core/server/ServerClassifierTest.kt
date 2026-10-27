package com.ars3nb.volta.core.server

import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerClassifierTest {
    @Test
    fun autoWordsHavePriorityOverBypassWords() {
        assertEquals(ServerRole.AUTO, ServerClassifier.classify("AUTO анти-глушилка"))
        assertEquals(ServerRole.AUTO, ServerClassifier.classify("Автоподбор · обход"))
    }

    @Test
    fun bypassAndNormalWordsAreClassified() {
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("Белые списки Москва"))
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("Анти-глушилка"))
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify("Germany 01"))
    }

    @Test
    fun providerRefreshPreservesLocalServerState() {
        val old = server(
            id = 42,
            remark = "Старое имя",
            role = ServerRole.NORMAL,
            roleManual = true,
            favorite = true,
            selected = true,
            latency = 73
        )
        val incoming = server(remark = "AUTO · новое имя")

        val merged = ServerClassifier.mergeProviderUpdate(listOf(old), listOf(incoming), 9).single()

        assertEquals(42, merged.id)
        assertEquals(9L, merged.subscriptionId)
        assertEquals(ServerRole.NORMAL, merged.role)
        assertTrue(merged.roleManual)
        assertTrue(merged.isFavorite)
        assertTrue(merged.isSelected)
        assertEquals(73, merged.latencyMs)
    }

    @Test
    fun automaticRoleIsReclassifiedWhenProviderRenamesServer() {
        val old = server(remark = "Обычный", role = ServerRole.NORMAL, roleManual = false)
        val incoming = server(remark = "AUTO обход")

        val merged = ServerClassifier.mergeProviderUpdate(listOf(old), listOf(incoming), 2).single()

        assertEquals(ServerRole.AUTO, merged.role)
        assertFalse(merged.roleManual)
    }

    @Test
    fun measuredHealthSurvivesARefresh() {
        val old = server(id = 7, remark = "NL").copy(ewmaMs = 132, failStreak = 2, lastPingAt = 99)
        val incoming = server(remark = "NL · updated")

        val merged = ServerClassifier.mergeProviderUpdate(listOf(old), listOf(incoming), 1).single()

        assertEquals(132L, merged.ewmaMs)
        assertEquals(2, merged.failStreak)
        assertEquals(99L, merged.lastPingAt)
    }

    @Test
    fun nodesSharingAnAddressButNotATransportStayDistinct() {
        val ws = server(id = 1, remark = "WS").copy(network = "ws", path = "/ws")
        val grpc = server(id = 2, remark = "gRPC").copy(network = "grpc", serviceName = "gun")

        // Same host, port and uuid — only the transport differs.
        assertTrue(ServerClassifier.stableKey(ws) != ServerClassifier.stableKey(grpc))

        val merged = ServerClassifier.mergeProviderUpdate(
            previous = listOf(ws, grpc),
            incoming = listOf(ws.copy(id = 0), grpc.copy(id = 0)),
            subscriptionId = 1
        )
        assertEquals(2, merged.size)
        assertEquals(listOf(1L, 2L), merged.map { it.id })
    }

    @Test
    fun twoIdenticalIncomingRowsNeverClaimTheSameStoredId() {
        val stored = server(id = 5, remark = "Dup")
        val duplicated = listOf(server(remark = "Dup"), server(remark = "Dup"))

        val merged = ServerClassifier.mergeProviderUpdate(listOf(stored), duplicated, 3)

        assertEquals(2, merged.size)
        // The second copy is inserted fresh instead of overwriting the first.
        assertEquals(listOf(5L, 0L), merged.map { it.id })
    }

    private fun server(
        id: Long = 0,
        remark: String,
        role: ServerRole = ServerRole.NORMAL,
        roleManual: Boolean = false,
        favorite: Boolean = false,
        selected: Boolean = false,
        latency: Long = -1
    ) = ServerEntity(
        id = id,
        remark = remark,
        protocol = ProtocolType.VLESS,
        address = "node.example",
        port = 443,
        uuid = "test-id",
        role = role,
        roleManual = roleManual,
        isFavorite = favorite,
        isSelected = selected,
        latencyMs = latency
    )
}
