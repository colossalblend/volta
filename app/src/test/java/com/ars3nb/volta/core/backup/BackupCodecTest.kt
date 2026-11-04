package com.ars3nb.volta.core.backup

import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCodecTest {

    private val plainSub = SubscriptionEntity(
        id = 7,
        name = "Мой VPN",
        nameLocked = true,
        url = "https://panel.example/sub/abc",
        lastUpdated = 123,
        rawBody = "cached",
        trafficUsed = 5,
        trafficTotal = 10,
        colorHex = "#4A8FC4",
        routingProfileId = 3,
        serverTypes = "lte:1"
    )
    private val hiddenSub = SubscriptionEntity(
        id = 8,
        name = "Секретная",
        url = "https://hidden.example/s/xyz",
        urlHidden = true
    )
    private val manual = ServerEntity(
        id = 42,
        remark = "Мой сервер",
        protocol = ProtocolType.VLESS,
        address = "203.0.113.5",
        port = 443,
        uuid = "u-1",
        security = "reality",
        rawLink = "vless://u-1@203.0.113.5:443",
        latencyMs = 80,
        ewmaMs = 90,
        failStreak = 2,
        isSelected = true,
        isFavorite = true,
        role = ServerRole.BYPASS_WHITELIST,
        roleManual = true
    )
    private val ownRules = RoutingProfileEntity(
        id = 3,
        name = "Мои правила",
        isActive = true,
        rulesJson = RoutingEngine.serializeRules(
            listOf(RoutingRuleDto(name = "Банк", outbound = RouteOutbound.DIRECT, domains = listOf("bank.ru")))
        ),
        remoteDns = "https://9.9.9.9/dns-query"
    )

    /** Stands in for the app key: reversible, and visibly not the address. */
    private val seal: (String) -> String = { "sealed:" + it.reversed() }
    private val unseal: (String) -> String = { it.removePrefix("sealed:").reversed() }

    private fun snapshot(settings: Map<String, Any> = mapOf("design_theme" to "OCEAN")) = BackupCodec.Snapshot(
        subscriptions = listOf(plainSub, hiddenSub),
        subscriptionRouting = mapOf(7L to "Мои правила"),
        servers = listOf(manual),
        routing = listOf(ownRules),
        activeRouting = "Мои правила",
        settings = settings,
        appVersion = "1.1.200",
        createdAt = 1_793_000_000_000
    )

    @Test
    fun roundTripKeepsWhatMattersAndDropsTheRest() {
        val text = BackupCodec.encode(snapshot(), seal)
        val back = BackupCodec.decode(text, unseal)

        assertEquals(0, back.skipped)
        assertEquals(1_793_000_000_000, back.createdAt)
        assertEquals(2, back.subscriptions.size)

        val plain = back.subscriptions[0]
        assertEquals("Мой VPN", plain.entity.name)
        assertTrue(plain.entity.nameLocked)
        assertEquals("https://panel.example/sub/abc", plain.entity.url)
        assertFalse(plain.entity.urlHidden)
        assertEquals("#4A8FC4", plain.entity.colorHex)
        assertEquals("lte:1", plain.entity.serverTypes)
        assertEquals("Мои правила", plain.routingName)
        // This phone's rows and the provider's numbers do not travel.
        assertEquals(0L, plain.entity.id)
        assertEquals(0L, plain.entity.routingProfileId)
        assertEquals(0L, plain.entity.lastUpdated)
        assertEquals("", plain.entity.rawBody)
        assertEquals(0L, plain.entity.trafficTotal)

        val hidden = back.subscriptions[1]
        assertTrue(hidden.entity.urlHidden)
        assertEquals("https://hidden.example/s/xyz", hidden.entity.url)
        assertNull(hidden.routingName)

        val server = back.servers.single()
        assertEquals(0L, server.id)
        assertNull(server.subscriptionId)
        assertEquals("Мой сервер", server.remark)
        assertEquals("reality", server.security)
        assertEquals(ServerRole.BYPASS_WHITELIST, server.role)
        assertTrue(server.roleManual)
        assertTrue(server.isFavorite)
        assertFalse(server.isSelected)
        assertEquals(-1L, server.latencyMs)
        assertEquals(0, server.failStreak)

        val rules = back.routing.single()
        assertEquals("Мои правила", rules.name)
        assertEquals(0L, rules.id)
        assertFalse(rules.isActive)
        assertEquals("https://9.9.9.9/dns-query", rules.remoteDns)
        assertEquals("bank.ru", RoutingEngine.parseRules(rules.rulesJson).single().domains.single())
        assertEquals("Мои правила", back.activeRouting)

        assertEquals(mapOf<String, Any?>("design_theme" to "OCEAN"), back.settings)
    }

    @Test
    fun aHiddenLinkIsNeverWrittenInPlainText() {
        val text = BackupCodec.encode(snapshot(), seal)
        assertFalse(text.contains("hidden.example"))
        assertTrue(text.contains("panel.example"))
        // And with the real app key it still comes back.
        val real = BackupCodec.decode(BackupCodec.encode(snapshot()))
        assertEquals("https://hidden.example/s/xyz", real.subscriptions[1].entity.url)
    }

    @Test
    fun settingsKeepTheirTypes() {
        val settings = mapOf(
            "auto_failover" to false,
            "mtu" to 1380,
            "per_app_packages" to listOf("org.telegram.messenger", "com.whatsapp"),
            "connect_mode" to "AUTO_HOME"
        )
        val back = BackupCodec.decode(BackupCodec.encode(snapshot(settings), seal), unseal)
        assertEquals(false, back.settings["auto_failover"])
        assertEquals(1380, back.settings["mtu"])
        assertEquals(listOf("org.telegram.messenger", "com.whatsapp"), back.settings["per_app_packages"])
        assertEquals("AUTO_HOME", back.settings["connect_mode"])
    }

    @Test
    fun brokenEntriesAreSkippedNotFatal() {
        val text = """
            {
              "format": "volta-backup", "version": 1,
              "subscriptions": [
                {"name": "Без адреса"},
                {"name": "Без защиты", "url": "http://plain.example/sub"},
                {"name": null, "url": "https://ok.example/sub", "sortOrder": null, "futureField": 1},
                {"name": "Не открыть", "hiddenUrl": "garbage"},
                "not an object"
              ],
              "servers": [
                {"protocol": "TELEPORT", "address": "a", "port": 1},
                {"protocol": "TROJAN", "address": "b.example", "port": 443, "role": "FUTURE_ROLE", "remark": "T"}
              ],
              "routing": [{"description": "без имени"}],
              "settings": {"mtu": 1400, "nested": {"x": 1}}
            }
        """.trimIndent()
        val back = BackupCodec.decode(text) { throw IllegalStateException("bad key") }

        val sub = back.subscriptions.single()
        assertEquals("https://ok.example/sub", sub.entity.url)
        assertEquals("Подписка", sub.entity.name)
        assertEquals(0, sub.entity.sortOrder)
        assertTrue(sub.entity.isEnabled)

        val server = back.servers.single()
        assertEquals(ProtocolType.TROJAN, server.protocol)
        assertEquals(ServerRole.NORMAL, server.role)
        assertEquals("none", server.encryption)

        assertTrue(back.routing.isEmpty())
        assertEquals(mapOf<String, Any?>("mtu" to 1400), back.settings)
        // Four bad subscriptions (one not even an object), one server, one rule set.
        assertEquals(6, back.skipped)
    }

    @Test
    fun foreignOrNewerFilesAreRefusedWithAPlainReason() {
        fun refused(text: String): String = try {
            BackupCodec.decode(text)
            fail("accepted: $text")
            ""
        } catch (e: BackupCodec.BackupException) {
            e.message.orEmpty()
        }
        assertTrue(refused("not json at all").contains("не файл Volta"))
        assertTrue(refused("""{"subscriptions": []}""").contains("не файл Volta"))
        assertTrue(refused("""{"format": "volta-backup", "version": 99}""").contains("новой версией"))
        // A byte-order mark from a text editor is fine.
        assertTrue(BackupCodec.decode("﻿{\"format\": \"volta-backup\", \"version\": 1}").isEmpty)
    }

    @Test
    fun onlyChangedBuiltInsAreWorthKeeping() {
        val stock = RoutingEngine.builtInProfiles()
        // A fresh copy has new rule ids but the same rules: nothing to keep.
        val untouched = RoutingEngine.builtInProfiles().first().copy(id = 1)
        assertFalse(BackupCodec.worthKeeping(untouched, stock))
        val edited = untouched.copy(remoteDns = "https://8.8.8.8/dns-query")
        assertTrue(BackupCodec.worthKeeping(edited, stock))
        val moreRules = untouched.copy(
            rulesJson = RoutingEngine.serializeRules(
                RoutingEngine.parseRules(untouched.rulesJson) + RoutingRuleDto(name = "ещё", domains = listOf("x.ru"))
            )
        )
        assertTrue(BackupCodec.worthKeeping(moreRules, stock))
        assertTrue(BackupCodec.worthKeeping(ownRules, stock))
        // A provider's set belongs to its subscription, which brings it back itself.
        assertFalse(BackupCodec.worthKeeping(ownRules.copy(sourceSubscriptionId = 7), stock))
    }

    @Test
    fun theDialogSaysWhatIsInTheFile() {
        val back = BackupCodec.decode(BackupCodec.encode(snapshot(), seal), unseal)
        val text = BackupCodec.describe(back, ZoneOffset.UTC)
        assertTrue(text, text.startsWith("Файл сохранён 26 октября 2026."))
        assertTrue(text.contains("подписки: 2"))
        assertTrue(text.contains("свои серверы: 1"))
        assertTrue(text.contains("наборы правил: 1"))
        assertTrue(text.contains("настройки"))
        assertEquals("volta-2026-11-04.json", BackupCodec.fileName(LocalDate.of(2026, 11, 4)))
    }

    @Test
    fun theSameServerIsRecognisedWhateverItsName() {
        assertEquals(
            BackupCodec.sameServerKey(manual),
            BackupCodec.sameServerKey(manual.copy(id = 1, remark = "Другое имя", latencyMs = 5, address = "203.0.113.5"))
        )
        assertFalse(BackupCodec.sameServerKey(manual) == BackupCodec.sameServerKey(manual.copy(port = 8443)))
    }
}

class RestoreReportTest {

    @Test
    fun theMessageNamesWhatCameBackAndWhatDidNot() {
        assertEquals(
            "Готово. Восстановлено — подписки: 2, свои серверы: 1, настройки. " +
                "Серверы не загрузились у подписок: 1. Обновите их позже на вкладке «Подписки»",
            RestoreReport(subscriptionsAdded = 2, subscriptionsNotLoaded = 1, serversAdded = 1, settingsRestored = 5).message()
        )
        assertEquals("Всё из файла уже есть в Volta", RestoreReport(subscriptionsAlreadyThere = 3).message())
        assertEquals("Нечего восстанавливать. Не прочитано записей: 2", RestoreReport(skipped = 2).message())
    }
}
