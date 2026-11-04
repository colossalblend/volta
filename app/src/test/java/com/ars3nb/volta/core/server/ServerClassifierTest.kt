package com.ars3nb.volta.core.server

import com.ars3nb.volta.core.openflux.OpenFluxLinkTest.Companion.DIRECT
import com.ars3nb.volta.core.openflux.OpenFluxLinkTest.Companion.DOCS_A
import com.ars3nb.volta.core.openflux.OpenFluxLinkTest.Companion.DOCS_B
import com.ars3nb.volta.core.openflux.OpenFluxLinkTest.Companion.NODE
import com.ars3nb.volta.core.parser.ConfigParser
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
    fun providerBalancersAreTheirOwnGroupEvenWithLteMarkers() {
        listOf(
            "🇷🇺 Автовыбор",
            "Авто",
            "Автобалансер LTE",
            "Балансер | обход белых списков",
            "Load Balance 4G",
            "URL-Test",
            "Fallback"
        ).forEach { name ->
            assertEquals(name, ServerRole.AUTO, ServerClassifier.classify(name))
        }
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("🇩🇪 Германия LTE"))
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify("🇳🇱 Нидерланды"))
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

    // ------------------------------------------------------------------ OpenFlux

    private fun openFlux(link: String, name: String = "") =
        ConfigParser.parseShareLink(if (name.isBlank()) link else "$link#$name")!!

    @Test
    fun openFluxTakesProviderMarkupLikeAnyServer() {
        // A [L]/[H] prefix, a name and server-type positions work as for every protocol.
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify(openFlux(DIRECT, "[L] Нидерланды")).role)
        assertEquals("Нидерланды", ServerClassifier.classify(openFlux(DIRECT, "[L] Нидерланды")).remark)
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify(openFlux(DOCS_A, "[H] Документы")).role)
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify(openFlux(NODE, "Германия LTE")).role)
        assertEquals(ServerRole.AUTO, ServerClassifier.classify(openFlux(NODE, "Автовыбор")).role)

        val types = ServerMarkup.parseServerTypes("lte:1;home:2")
        assertEquals(
            listOf(ServerRole.BYPASS_WHITELIST, ServerRole.NORMAL),
            listOf(openFlux(DIRECT), openFlux(DOCS_A)).mapIndexed { i, s ->
                ServerClassifier.classify(s, types, i).role
            }
        )
    }

    @Test
    fun unmarkedOpenFluxGoesWhereItsCarriersWork() {
        // Only documents (they pass mobile white lists): LTE. A direct road to the exit,
        // alone or as a backup: Home, like any VPN server.
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify(openFlux(DOCS_A)).role)
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify(openFlux(DIRECT)).role)
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify(openFlux(NODE)).role)
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.autoRole(openFlux(DOCS_B)))

        // A provider that listed its LTE servers keeps the group as it made it.
        val merged = ServerClassifier.mergeProviderUpdate(
            emptyList(),
            listOf(openFlux(DIRECT, "[L] Нидерланды"), openFlux(DOCS_A)),
            1
        )
        assertEquals(listOf(ServerRole.BYPASS_WHITELIST, ServerRole.NORMAL), merged.map { it.role })
    }

    @Test
    fun openFluxExitsKeepTheirStateAcrossRefreshes() {
        val stored = openFlux(DOCS_A, "Москва").copy(
            id = 7, subscriptionId = 1, isSelected = true, ewmaMs = 900, latencyMs = 950,
            role = ServerRole.NORMAL, roleManual = true
        )
        // Same exit renamed, plus another exit over the same document service.
        val merged = ServerClassifier.mergeProviderUpdate(
            listOf(stored),
            listOf(openFlux(DOCS_B, "Питер"), openFlux(DOCS_A, "Москва, новое имя")),
            1
        )
        assertEquals(0L, merged[0].id)
        assertFalse(merged[0].isSelected)
        assertEquals(7L, merged[1].id)
        assertTrue(merged[1].isSelected)
        assertEquals(900L, merged[1].ewmaMs)
        assertEquals(ServerRole.NORMAL, merged[1].role)
        assertEquals("Москва, новое имя", merged[1].remark)
    }

    @Test
    fun lteMarkedNodesGoToTheLteGroup() {
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("🇷🇺 LTE обход моб. интернет"))
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("Москва | LTE"))
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("Для мобильного интернета"))
        // Letters inside a word are not a marker.
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify("Deltexa 01"))
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify("🇩🇪 Germany"))
    }
}
