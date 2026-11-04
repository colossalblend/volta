package com.ars3nb.volta.core.server

import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMarkupTest {

    private fun server(remark: String, n: Int = 0) = ServerEntity(
        remark = remark,
        protocol = ProtocolType.VLESS,
        address = "node$n.example",
        port = 443,
        uuid = "u$n"
    )

    @Test
    fun prefixesNameTheGroupAndAreCutFromTheName() {
        assertEquals(ServerMarkup.Prefixed(ServerRole.BYPASS_WHITELIST, "🇷🇺 Москва"), ServerMarkup.prefixRole("[L] 🇷🇺 Москва"))
        assertEquals(ServerMarkup.Prefixed(ServerRole.NORMAL, "Germany"), ServerMarkup.prefixRole("[H]Germany"))
        assertEquals(ServerMarkup.Prefixed(ServerRole.AUTO, "Best"), ServerMarkup.prefixRole(" [a] - Best"))
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerMarkup.prefixRole("[LTE] Riga")?.role)
        assertEquals(ServerRole.NORMAL, ServerMarkup.prefixRole("[WIFI] Riga")?.role)
        assertEquals(ServerRole.NORMAL, ServerMarkup.prefixRole("【H】 Riga")?.role)
        // Only at the start, and only known tags.
        assertNull(ServerMarkup.prefixRole("Riga [L]"))
        assertNull(ServerMarkup.prefixRole("[NL] Amsterdam"))
    }

    @Test
    fun prefixBeatsTheNameCheck() {
        // "LTE" in the name, but the provider says Home.
        assertEquals(ServerRole.NORMAL, ServerClassifier.classify("[H] Германия LTE-ready"))
        // A balancer word, but the provider says LTE.
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerClassifier.classify("[L] Авто Москва"))
    }

    @Test
    fun serverTypesAreParsedLeniently() {
        val map = ServerMarkup.parseServerTypes("lte:1,2,5-7;home:3|auto:9")
        assertEquals(ServerRole.BYPASS_WHITELIST, map.roles[1])
        assertEquals(ServerRole.BYPASS_WHITELIST, map.roles[6])
        assertEquals(ServerRole.NORMAL, map.roles[3])
        assertEquals(ServerRole.AUTO, map.roles[9])
        assertNull(map.roles[4])
        assertEquals(setOf(ServerRole.BYPASS_WHITELIST, ServerRole.NORMAL, ServerRole.AUTO), map.explicit)

        // '+' arrives as a space from a query string; junk groups are skipped.
        val loose = ServerMarkup.parseServerTypes("lte:1 2 3 wat:4 home:5")
        assertEquals(listOf(1, 2, 3, 5), loose.roles.keys.sorted())
        assertEquals("lte:1,2,3;home:5", ServerMarkup.normalize("lte:1 2 3 wat:4 home:5"))
        assertEquals("", ServerMarkup.normalize("nonsense"))
        assertTrue(ServerMarkup.parseServerTypes(null).isEmpty)
    }

    @Test
    fun firstMentionOfAPositionWins() {
        assertEquals(ServerRole.BYPASS_WHITELIST, ServerMarkup.parseServerTypes("lte:1;home:1").roles[1])
    }

    @Test
    fun serverTypeMarksByPositionAndCompletesItsGroup() {
        val incoming = listOf(
            server("🇩🇪 Germany", 1),
            server("🇷🇺 Moscow", 2),
            server("🇫🇮 Finland LTE", 3), // name says LTE, provider's LTE list doesn't
            server("Автовыбор", 4)
        )
        val merged = ServerClassifier.mergeProviderUpdate(emptyList(), incoming, 7, "lte:2")
        assertEquals(ServerRole.NORMAL, merged[0].role)
        assertEquals(ServerRole.BYPASS_WHITELIST, merged[1].role)
        assertTrue(merged[1].roleFromProvider)
        assertEquals(ServerRole.NORMAL, merged[2].role)
        assertFalse(merged[2].roleFromProvider)
        // Balancers were not listed, so the name check still finds them.
        assertEquals(ServerRole.AUTO, merged[3].role)
    }

    @Test
    fun withoutMarkupTheOldNameCheckStays() {
        val merged = ServerClassifier.mergeProviderUpdate(
            emptyList(),
            listOf(server("Germany", 1), server("Finland LTE", 2), server("Auto", 3)),
            1
        )
        assertEquals(
            listOf(ServerRole.NORMAL, ServerRole.BYPASS_WHITELIST, ServerRole.AUTO),
            merged.map { it.role }
        )
        assertTrue(merged.none { it.roleFromProvider })
    }

    @Test
    fun prefixedListStripsTagsAndKeepsUserChoiceOnRefresh() {
        val first = ServerClassifier.mergeProviderUpdate(
            emptyList(), listOf(server("[L] Moscow", 1), server("[H] Berlin", 2)), 1
        )
        assertEquals(listOf("Moscow", "Berlin"), first.map { it.remark })
        assertEquals(listOf(ServerRole.BYPASS_WHITELIST, ServerRole.NORMAL), first.map { it.role })

        // The user moved Berlin to LTE by hand: a refresh keeps that.
        val stored = first.mapIndexed { i, s -> s.copy(id = i + 1L) }
            .map { if (it.remark == "Berlin") it.copy(role = ServerRole.BYPASS_WHITELIST, roleManual = true) else it }
        val second = ServerClassifier.mergeProviderUpdate(
            stored, listOf(server("[L] Moscow", 1), server("[H] Berlin", 2)), 1
        )
        assertEquals(ServerRole.BYPASS_WHITELIST, second[1].role)
        assertTrue(second[1].roleManual)
    }

    @Test
    fun roleFromJsonGroupIsKept() {
        val fromJson = server("Moscow LTE", 1).copy(role = ServerRole.NORMAL, roleFromProvider = true)
        val merged = ServerClassifier.mergeProviderUpdate(emptyList(), listOf(fromJson), 1)
        assertEquals(ServerRole.NORMAL, merged[0].role)
    }
}
