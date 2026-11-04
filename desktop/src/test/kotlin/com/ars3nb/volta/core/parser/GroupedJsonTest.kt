package com.ars3nb.volta.core.parser

import com.ars3nb.volta.core.server.ServerClassifier
import com.ars3nb.volta.data.model.ServerRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupedJsonTest {

    private val vless = "vless://11111111-1111-1111-1111-111111111111@%s:443?security=tls&type=tcp#%s"

    @Test
    fun groupsGiveEveryServerItsRole() {
        val body = """
            {
              "title": "Grouped VPN",
              "wifi": ["${vless.format("de.example", "Germany")}", "${vless.format("nl.example", "LTE-named Home")}"],
              "lte": [
                {"link": "${vless.format("ru.example", "x")}", "name": "Moscow"},
                {
                  "remarks": "SPB",
                  "outbounds": [{
                    "tag": "proxy", "protocol": "vless",
                    "settings": {"vnext": [{"address": "spb.example", "port": 443,
                      "users": [{"id": "22222222-2222-2222-2222-222222222222", "encryption": "none"}]}]}
                  }]
                }
              ],
              "balancers": [{"tag": "bal", "protocol": "vless",
                "settings": {"vnext": [{"address": "bal.example", "port": 443,
                  "users": [{"id": "33333333-3333-3333-3333-333333333333"}]}]}}]
            }
        """.trimIndent()

        val result = ConfigParser.parseSubscriptionBody(body, subscriptionId = 4)
        assertEquals("Grouped VPN", result.title)
        assertEquals(
            listOf("de.example", "nl.example", "ru.example", "spb.example", "bal.example"),
            result.servers.map { it.address }
        )
        assertEquals(listOf("Germany", "LTE-named Home", "Moscow", "SPB", "bal"), result.servers.map { it.remark })
        assertTrue(result.servers.all { it.roleFromProvider })

        // The group survives the merge, whatever the names say.
        val merged = ServerClassifier.mergeProviderUpdate(emptyList(), result.servers, 4)
        assertEquals(
            listOf(
                ServerRole.NORMAL, ServerRole.NORMAL,
                ServerRole.BYPASS_WHITELIST, ServerRole.BYPASS_WHITELIST,
                ServerRole.AUTO
            ),
            merged.map { it.role }
        )
    }

    @Test
    fun groupsMayBeNestedAndBase64Wrapped() {
        val json = """{"groups": {"home": ["${vless.format("a.example", "A")}"], "mobile": ["${vless.format("b.example", "B")}"]}}"""
        val body = java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        val result = ConfigParser.parseSubscriptionBody("#profile-title: Wrapped\n$body")
        assertEquals("Wrapped", result.title)
        assertEquals(listOf(ServerRole.NORMAL, ServerRole.BYPASS_WHITELIST), result.servers.map { it.role })
    }

    @Test
    fun ordinaryXrayJsonIsNotMistakenForGroups() {
        val body = """
            {"remarks": "Single", "outbounds": [{"tag": "proxy", "protocol": "vless",
              "settings": {"vnext": [{"address": "one.example", "port": 443,
                "users": [{"id": "11111111-1111-1111-1111-111111111111"}]}]}}]}
        """.trimIndent()
        val result = ConfigParser.parseSubscriptionBody(body)
        assertEquals(1, result.servers.size)
        assertEquals("Single", result.servers[0].remark)
        assertTrue(result.servers.none { it.roleFromProvider })
    }
}
