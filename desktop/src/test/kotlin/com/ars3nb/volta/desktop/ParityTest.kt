package com.ars3nb.volta.desktop

import com.ars3nb.volta.core.backup.BackupCodec
import com.ars3nb.volta.core.routing.Presets
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.routing.RoutingNames
import com.ars3nb.volta.core.stats.TrafficDayEntity
import com.ars3nb.volta.core.stats.TrafficTally
import com.ars3nb.volta.core.vpn.AdBlockLists
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.core.vpn.InternetCheck
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.PingType
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RouteOutbound
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.RoutingRuleDto
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.desktop.data.AppSettings
import com.ars3nb.volta.desktop.data.DesignTheme
import com.ars3nb.volta.desktop.data.SettingsBackup
import com.ars3nb.volta.desktop.i18n.I18n
import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.PluralRules
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import com.ars3nb.volta.desktop.platform.SystemProxy
import com.ars3nb.volta.desktop.update.AppUpdater
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.time.LocalDate
import java.time.Month
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Volta for Windows shares with the Android app, checked against the Android app's own data. */
class ParityTest {

    // ------------------------------------------------------------------ words

    @Test
    fun everyLanguageReadsTheAndroidTexts() {
        assertEquals(15, Lang.entries.size)
        Lang.entries.forEach { lang ->
            val title = Res.string(R.string.network_none_title, tag = lang.tag)
            assertTrue("${lang.tag}: $title", title.isNotBlank() && title != R.string.network_none_title)
            assertEquals(12, Res.array(R.array.home_months, tag = lang.tag).size)
            listOf(0, 1, 2, 3, 5, 11, 21, 101).forEach { n ->
                val text = Res.plural(R.plurals.settings_backup_count_servers, n, n, tag = lang.tag)
                assertFalse("${lang.tag} $n", text.isBlank() || text == R.plurals.settings_backup_count_servers)
            }
        }
        // The very words of the Android app, formatting included.
        assertEquals("Нет интернета", Res.string(R.string.network_none_title, tag = "ru"))
        assertEquals("Файл сохранён 4 ноября 2026.", Res.string(R.string.settings_backup_saved_on, "4 ноября 2026", tag = "ru"))
        assertTrue(Res.plural(R.plurals.settings_backup_count_servers, 5, 5, tag = "ru").contains("5"))
        assertEquals(Res.plural(R.plurals.settings_backup_count_servers, 5, 5, tag = "ru"), Res.plural(R.plurals.settings_backup_count_servers, 11, 5, tag = "ru"))
        // The typed desktop words follow Android where the Russian text is the same.
        assertEquals(Res.string(R.string.home_state_protected, tag = "fr"), Lang.FR.strings.stateConnected)
        assertEquals(Res.string(R.string.home_state_protected, tag = "ar"), Lang.AR.strings.stateConnected)
    }

    @Test
    fun androidEscapesAndPluralRules() {
        assertEquals("It's \"here\"\nnext", Res.unescape("It\\'s \\\"here\\\"\\nnext"))
        assertEquals("a b", Res.unescape("  a \n   b  "))
        assertEquals("  kept  ", Res.unescape("\"  kept  \""))
        assertEquals("…", Res.unescape("\\u2026"))
        assertEquals("few", PluralRules.select("ru", 3))
        assertEquals("many", PluralRules.select("ru", 11))
        assertEquals("one", PluralRules.select("uk", 21))
        assertEquals("two", PluralRules.select("ar", 2))
        assertEquals("many", PluralRules.select("ar", 11))
        assertEquals("one", PluralRules.select("fr", 0))
        assertEquals("other", PluralRules.select("en", 0))
        assertEquals("other", PluralRules.select("zh-CN", 1))
        assertEquals("values-zh-rCN", Res.folderOf("zh-CN"))
        assertEquals("values-pt-rBR", Res.folderOf("pt-BR"))
        assertEquals("values", Res.folderOf("en"))
    }

    // ------------------------------------------------------------------ routing

    @Test
    fun routingProfilesAndRulesAsOnAndroid() {
        val builtIns = RoutingEngine.builtInProfiles()
        assertEquals(2, builtIns.size)
        I18n.use("en")
        assertEquals(Res.string(R.string.routing_builtin_all_name), RoutingNames.shownProfileName(builtIns[0]))
        I18n.use("ru")
        assertEquals("Весь трафик через VPN", RoutingNames.shownProfileName(builtIns[0]))

        // A user's set: a site direct, a geosite blocked, an IP through the VPN, in order.
        val rules = listOf(
            RoutingRuleDto(name = "Банк", outbound = RouteOutbound.DIRECT, domains = listOf("domain:bank.ru"), sortOrder = 0),
            RoutingRuleDto(name = "Блок", outbound = RouteOutbound.BLOCK, domains = listOf("geosite:category-ads-all"), sortOrder = 1),
            RoutingRuleDto(name = "Сеть", outbound = RouteOutbound.PROXY, ips = listOf("geoip:telegram", "91.108.4.0/22"), sortOrder = 2)
        )
        val json = RoutingEngine.serializeRules(rules)
        assertEquals(rules.map { it.name }, RoutingEngine.parseRules(json).map { it.name })
        val profile = RoutingProfileEntity(id = 9, name = "Мои", rulesJson = json)
        val config = root(ConfigBuilder.build(vless(), profile))
        val routing = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
        val bank = routing.indexOfFirst { it.has("domain") && it.getAsJsonArray("domain").any { d -> d.asString == "domain:bank.ru" } }
        val ads = routing.indexOfFirst { it.has("domain") && it.getAsJsonArray("domain").any { d -> d.asString == "geosite:category-ads-all" } }
        assertTrue(bank >= 0 && ads > bank)
        assertEquals("direct", routing[bank].get("outboundTag").asString)
        assertEquals("block", routing[ads].get("outboundTag").asString)

        // Pasted JSON that has no rules is refused with the Android app's words.
        I18n.use("ru")
        val error = runCatching { RoutingEngine.importRules("""{"foo": 1}""") }.exceptionOrNull()
        assertTrue(error is RoutingEngine.RulesFormatException)
        assertEquals(Res.string(R.string.routing_import_no_rules), error!!.message)
        // Presets carry the Android labels.
        assertTrue(Presets.sites.isNotEmpty())
        Presets.sites.forEach { assertTrue(Res.has(it.subtitle)) }
    }

    // ------------------------------------------------------------------ ad blocking

    @Test
    fun adBlockingModesBuildTheAndroidFilters() {
        AdBlockMode.entries.forEach { mode ->
            val config = root(ConfigBuilder.build(vless(), null, adBlock = mode, blockTorrents = true))
            val rules = config.getAsJsonObject("routing").getAsJsonArray("rules").map { it.asJsonObject }
            val blocked = rules.filter { it.get("outboundTag")?.asString == "block" }
                .flatMap { r -> r.getAsJsonArray("domain")?.map { it.asString }.orEmpty() }
            if (mode == AdBlockMode.OFF) {
                assertFalse(blocked.contains("geosite:category-ads-all"))
                assertFalse(config.has("dns") && config.getAsJsonObject("dns").getAsJsonArray("servers").any {
                    it.isJsonPrimitive && it.asString.contains("adguard")
                })
            } else {
                assertTrue(blocked.contains("geosite:category-ads-all"))
                assertTrue(blocked.containsAll(AdBlockLists.domainsFor(mode)))
                val servers = config.getAsJsonObject("dns").getAsJsonArray("servers")
                assertEquals(mode.dohUrl, servers[0].asString)
                // The never-block list stays reachable.
                AdBlockLists.domainsFor(mode).forEach { assertFalse(it in AdBlockLists.NEVER_BLOCK) }
            }
            assertEquals(mode == AdBlockMode.FAMILY, blocked.contains("geosite:category-porn"))
            // Torrents: by protocol and by tracker list.
            assertTrue(rules.any { r -> r.getAsJsonArray("protocol")?.any { it.asString == "bittorrent" } == true })
            assertTrue(blocked.contains("geosite:category-public-tracker"))
            // The system proxy's own inbound reads site names for the filters.
            val http = config.getAsJsonArray("inbounds").map { it.asJsonObject }.first { it.get("tag").asString == "http" }
            assertTrue(http.getAsJsonObject("sniffing").get("enabled").asBoolean)
            dump("adblock-${mode.name.lowercase()}", config)
        }
    }

    // ------------------------------------------------------------------ updates

    @Test
    fun theUpdateManifestIsRead() {
        val m = AppUpdater.parseManifest(
            """{"versionCode": 512, "versionName": "1.1.412", "tag": "apk-main", "xray": "v26.7.28",
               "installer": {"name": "volta-abc1234-windows-x64-setup.exe", "size": 91234567,
               "sha256": "${"ab".repeat(32)}"}}"""
        )!!
        assertEquals(512, m.versionCode)
        assertEquals("1.1.412", m.versionName)
        assertEquals("volta-abc1234-windows-x64-setup.exe", m.installer!!.name)
        assertEquals(
            "https://github.com/o/r/releases/download/apk-main/volta-abc1234-windows-x64-setup.exe",
            AppUpdater.assetUrl("o/r", m.tag, m.installer!!.name)
        )
        assertEquals("https://github.com/o/r/releases/latest/download/windows-update.json", AppUpdater.latestUrl("o/r", AppUpdater.MANIFEST))
        assertEquals("https://github.com/o/r/releases/download/apk-main/windows-update.json", AppUpdater.betaUrl("o/r", AppUpdater.MANIFEST))
        // No checksum, no installer: nothing to take.
        assertNull(AppUpdater.parseManifest("""{"versionCode": 3, "installer": {"name": "x.exe", "size": 5, "sha256": "zz"}}""")!!.installer)
        assertNull(AppUpdater.parseManifest("not json"))
        // The Android manifest (APKs only) gives Windows nothing to install.
        assertNull(AppUpdater.parseManifest("""{"versionCode": 9, "apks": []}""")!!.installer)
        val file = Files.createTempFile("volta", ".bin").toFile().apply { writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", AppUpdater.sha256(file))
    }

    // ------------------------------------------------------------------ backup

    @Test
    fun anAndroidBackupComesInAndGoesBackOut() = runBlocking {
        I18n.use("ru")
        val text = javaClass.getResourceAsStream("/backup/android-backup.json")!!.bufferedReader().readText()
        val contents = BackupCodec.decode(text)
        assertEquals(2, contents.subscriptions.size)
        assertEquals("https://hidden.invalid/s/xyz", contents.subscriptions[1].entity.url)
        assertTrue(contents.subscriptions[1].entity.urlHidden)
        assertEquals(1, contents.servers.size)
        assertEquals(1, contents.skipped)
        assertTrue(BackupCodec.describe(contents).contains("4 ноября 2026") || BackupCodec.describe(contents).contains("ноября"))

        val dir = Files.createTempDirectory("volta-restore").toFile()
        val controller = VoltaController(AppPaths(dir))
        val report = controller.restoreInternal(contents)
        assertEquals(2, report.subscriptionsAdded)
        assertEquals(2, report.subscriptionsNotLoaded)
        assertEquals(1, report.serversAdded)
        assertEquals(1, report.routingRestored)
        assertTrue(report.message().isNotBlank())

        val d = controller.store.value
        val st = d.settings
        assertFalse(st.autoFailover)
        assertTrue(st.blockTorrents)
        assertEquals(DesignTheme.OCEAN, st.designTheme)
        assertEquals(AdBlockMode.STRICT, st.adBlock)
        assertEquals(ConnectMode.AUTO_HOME, st.connectMode)
        assertEquals(PingType.REAL_DELAY, st.pingType)
        assertEquals("BYPASS", st.foreign["per_app_mode"])
        assertEquals(ServerRole.BYPASS_WHITELIST, d.servers.single().role)
        val mine = d.routingProfiles.first { it.name == "Мои правила" }
        assertTrue(mine.isActive)
        assertEquals(mine.id, d.subscriptions.first { it.url.startsWith("https://panel.invalid") }.routingProfileId)

        // The same file twice changes nothing.
        val again = controller.restoreInternal(contents)
        assertEquals(0, again.subscriptionsAdded)
        assertEquals(2, again.subscriptionsAlreadyThere)
        assertEquals(0, again.serversAdded)

        // Back out in the same format: the phone gets everything it gave, its own switches too.
        val out = BackupCodec.decode(controller.exportBackup())
        assertEquals(contents.subscriptions.map { it.entity.url }.toSet(), out.subscriptions.map { it.entity.url }.toSet())
        assertTrue(out.subscriptions.first { it.entity.urlHidden }.entity.url.startsWith("https://hidden.invalid"))
        assertFalse(controller.exportBackup().contains("hidden.invalid"))
        assertEquals("Мои правила", out.activeRouting)
        assertEquals(1400, out.settings["mtu"])
        assertEquals(listOf("ru.sberbankmobile", "ru.vtb24.mobilebanking.android"), out.settings["per_app_packages"])
        assertEquals("STRICT", out.settings["ad_block"])
        assertEquals(false, out.settings["auto_failover"])
        assertEquals(contents.servers.single().uuid, out.servers.single().uuid)
        controller.shutdown()
    }

    @Test
    fun settingsKeepTheirAndroidNames() {
        val (s, n) = SettingsBackup.fromBackup(AppSettings(), mapOf("default_ping_type" to "PROXY_GET", "ad_block" to "NOPE", "x" to 1))
        assertEquals(PingType.REAL_DELAY, s.pingType)
        assertEquals(AdBlockMode.OFF, s.adBlock)
        assertEquals(1, n)
        val out = SettingsBackup.toBackup(AppSettings(designTheme = DesignTheme.DYNAMIC, bypassSites = listOf("bank.ru")))
        assertEquals("DYNAMIC", out["design_theme"])
        assertEquals(listOf("bank.ru"), out["windows_bypass_sites"])
    }

    // ------------------------------------------------------------------ traffic, network, bypass

    @Test
    fun trafficIsTalliedPerDayAndMonth() {
        val today = LocalDate.of(2026, 11, 4)
        val days = listOf(
            TrafficDayEntity(today.toEpochDay(), 100, 900),
            TrafficDayEntity(today.minusDays(1).toEpochDay(), 0, 500),
            TrafficDayEntity(LocalDate.of(2026, 10, 20).toEpochDay(), 50, 50),
            TrafficDayEntity(today.plusDays(3).toEpochDay(), 7, 7)
        )
        val sum = TrafficTally.summarize(days, today)
        assertEquals(1000, sum.today)
        assertEquals(1500, sum.thisMonth)
        assertEquals(100, sum.lastMonth)
        assertEquals(TrafficTally.RECENT_DAYS, sum.recent.size)
        assertEquals(1000L, sum.recent.last())
        assertEquals(Month.OCTOBER, sum.lastMonthOf)
    }

    @Test
    fun noNetworkIsToldFromTheAdapters() {
        val lan = InetAddress.getByName("192.168.1.20")
        val linkLocal = InetAddress.getByName("169.254.10.1")
        fun adapter(name: String, vararg a: InetAddress, up: Boolean = true) =
            InternetCheck.Adapter(name, name, up, false, false, a.toList())
        assertEquals(InternetCheck.Status.OK, InternetCheck.decide(listOf(adapter("Wi-Fi", lan))))
        assertEquals(InternetCheck.Status.NO_NETWORK, InternetCheck.decide(listOf(adapter("Ethernet", linkLocal))))
        assertEquals(InternetCheck.Status.NO_NETWORK, InternetCheck.decide(listOf(adapter("Wi-Fi", lan, up = false))))
        assertEquals(InternetCheck.Status.NO_NETWORK, InternetCheck.decide(listOf(adapter("vEthernet (WSL)", lan))))
        // Nothing readable: never a false alarm.
        assertEquals(InternetCheck.Status.OK, InternetCheck.decide(emptyList()))
    }

    @Test
    fun sitesPastTheVpnGoIntoWindowsBypassList() {
        assertEquals("bank.ru", SystemProxy.normalizeSite("https://www.Bank.ru/login?x=1"))
        assertNull(SystemProxy.normalizeSite("not a site"))
        val list = SystemProxy.overrideFor(listOf("bank.ru", "gosuslugi.ru"))
        assertTrue(list.startsWith("bank.ru;*.bank.ru;gosuslugi.ru;*.gosuslugi.ru;"))
        assertTrue(list.endsWith("<local>"))
    }

    // ------------------------------------------------------------------ helpers

    @Test
    fun helperServersAreRecognisedAsOnAndroid() {
        val hy = ServerEntity(remark = "HY", protocol = ProtocolType.HYSTERIA, address = "h.example", port = 443, rawLink = "hysteria://h.example:443?auth=x")
        assertEquals("hysteria", ConfigBuilder.helperSpec(hy)?.kind)
        assertNull(ConfigBuilder.helperSpec(vless()))
        assertNotNull(ConfigBuilder.dialProblem(ServerEntity(remark = "OF", protocol = ProtocolType.OPENFLUX, address = "x", port = 1)))
        assertNotEquals(0, com.ars3nb.volta.core.openflux.OpenFluxRunner.PORT)
    }

    // ------------------------------------------------------------------ helpers

    private fun vless() = ServerEntity(
        id = 1, remark = "NL", protocol = ProtocolType.VLESS, address = "nl.example", port = 443,
        uuid = "6a9c3b1e-1f2d-4c3b-9a8e-7d6c5b4a3f21", security = "tls", network = "tcp", sni = "nl.example"
    )

    private fun root(json: String): JsonObject = JsonParser.parseString(json).asJsonObject

    /** CI runs each dumped config through the very xray.exe that ships (windows.yml). */
    private fun dump(name: String, config: JsonObject) {
        val dir = System.getenv("VOLTA_CONFIG_DUMP")?.takeIf { it.isNotBlank() } ?: return
        File(dir).apply { mkdirs() }.resolve("parity-$name.json").writeText(config.toString())
    }
}
