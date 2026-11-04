package com.ars3nb.volta.core.routing

import com.ars3nb.volta.data.model.RouteOutbound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Rules from a provider or a pasted file, as Gson reads them: nulls and repeats included. */
class RulesSanitizeTest {

    @Test
    fun repeatedOrMissingIdsBecomeUnique() {
        val rules = RoutingEngine.importRules(
            """[
              {"id":"a","outbound":"PROXY","domains":["x.com"]},
              {"id":"a","outbound":"DIRECT","domains":["y.ru"]},
              {"id":null,"outbound":"BLOCK"},
              {"outbound":"BLOCK","id":""}
            ]"""
        )
        assertEquals(4, rules.size)
        assertEquals("a", rules[0].id)
        assertEquals(4, rules.map { it.id }.toSet().size)
        assertTrue(rules.all { it.id.isNotBlank() })
    }

    @Test
    fun nullFieldsComeOutEmptyNotNull() {
        val rule = RoutingEngine.importRules(
            """[{"id":"r","outbound":"DIRECT","domains":null,"ips":["1.1.1.1",null],"name":null,"source":null}]"""
        ).single()
        assertEquals(emptyList<String>(), rule.domains)
        assertEquals(listOf("1.1.1.1"), rule.ips)
        assertEquals("", rule.name)
        assertEquals("user", rule.source)
        assertEquals(RouteOutbound.DIRECT, rule.outbound)
    }

    @Test
    fun aRuleWithAnOutboundThisBuildDoesNotKnowIsLeftOut() {
        val rules = RoutingEngine.importRules(
            """[{"id":"r1","outbound":"WARP","domains":["a.b"]},{"id":"r2","outbound":"PROXY","domains":["c.d"]}]"""
        )
        assertEquals(listOf("r2"), rules.map { it.id })
    }

    @Test
    fun storedRulesComeBackAsTheyWere() {
        val stored = RoutingEngine.importRules("""[{"id":"keep","outbound":"PROXY","domains":["e.f"]}]""")
        val again = RoutingEngine.parseRules(RoutingEngine.serializeRules(stored))
        assertEquals(stored, again)
        assertNotEquals(emptyList<Any>(), again)
    }
}
