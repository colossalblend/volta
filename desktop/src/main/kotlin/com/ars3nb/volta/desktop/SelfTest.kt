package com.ars3nb.volta.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.ars3nb.volta.core.parser.ConfigParser
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.desktop.core.DelayTester
import com.ars3nb.volta.desktop.core.LocalPorts
import com.ars3nb.volta.desktop.core.Ports
import com.ars3nb.volta.desktop.core.TrafficMeter
import com.ars3nb.volta.desktop.core.XrayCore
import com.ars3nb.volta.desktop.data.AppSettings
import com.ars3nb.volta.desktop.data.DesignTheme
import com.ars3nb.volta.desktop.data.StoreData
import com.ars3nb.volta.desktop.platform.SystemProxy
import com.ars3nb.volta.desktop.platform.WinApi
import com.ars3nb.volta.desktop.platform.WindowsApi
import com.ars3nb.volta.desktop.ui.AppViewState
import com.ars3nb.volta.desktop.ui.DesktopBridge
import com.ars3nb.volta.desktop.ui.Page
import com.ars3nb.volta.desktop.ui.VoltaDesktopApp
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.nio.file.Files
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat

/**
 * `Volta.exe --self-test <log> [--shots <dir>] [--proxy]`: the packaged app checks itself
 * the way a user would find out, without a user. Exit code 0 only when everything holds:
 *
 *  1. the bundled core runs and accepts every kind of config Volta builds;
 *  2. a real tunnel: a local VLESS server (the same xray.exe) and Volta's own connect,
 *     with traffic through its HTTP and SOCKS ports, the traffic counters and the
 *     real-delay measurement, in the manual and in an auto mode;
 *  3. with --proxy, the Windows proxy setting switched on and given back, including after
 *     a simulated crash;
 *  4. every screen draws, in several themes and languages (PNGs to --shots);
 *  5. what the Android app has too: the voltaproxy and OpenFlux helpers run, ad
 *     blocking and the user's own rules block by site name through the proxy, the
 *     traffic is counted per day, the update path finds, checks and starts a build, and
 *     a backup made on Android comes in and goes back out.
 */
object SelfTest {

    private val log = StringBuilder()
    private var failures = 0

    private fun say(line: String) {
        log.appendLine(line)
        println(line)
    }

    private fun check(name: String, block: () -> String?) {
        val started = System.currentTimeMillis()
        val result = runCatching(block)
        val ms = System.currentTimeMillis() - started
        val error = result.exceptionOrNull()
        if (error != null) {
            failures++
            say("FAIL $name (${ms} ms): ${error::class.simpleName}: ${error.message}")
            error.stackTrace.take(6).forEach { say("       at $it") }
        } else {
            say("ok   $name (${ms} ms)${result.getOrNull()?.let { " — $it" }.orEmpty()}")
        }
    }

    fun run(args: List<String>): Int {
        val logFile = args.firstOrNull { !it.startsWith("--") }?.let(::File) ?: File("volta-self-test.log")
        val shots = args.indexOf("--shots").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }?.let(::File)
        val testProxy = "--proxy" in args
        try {
            runAll(shots, testProxy)
        } catch (t: Throwable) {
            failures++
            say("FAIL unexpected: $t")
        }
        say(if (failures == 0) "ALL CHECKS PASSED" else "$failures CHECK(S) FAILED")
        runCatching { logFile.parentFile?.mkdirs(); logFile.writeText(log.toString()) }
        return if (failures == 0) 0 else 1
    }

    private fun runAll(shots: File?, testProxy: Boolean) {
        val work = Files.createTempDirectory("volta-self-test").toFile()
        val paths = AppPaths(File(work, "data")).apply { ensure() }
        val core = XrayCore.from(paths)
        say("Volta ${AppPaths.appVersion}, ${System.getProperty("os.name")} ${System.getProperty("os.version")}, Java ${System.getProperty("java.version")}")
        say("resources: ${AppPaths.resourcesDir().absolutePath}")

        check("core is bundled") {
            require(core.isPresent) { "no xray at ${core.exe}" }
            listOf("geoip.dat", "geosite.dat").forEach {
                require(File(AppPaths.resourcesDir(), it).length() > 1024) { "no $it" }
            }
            core.prepareAssets()
            "Xray ${core.version().ifBlank { error("xray version gave nothing") }}"
        }

        check("the network is seen (internet check)") {
            val status = com.ars3nb.volta.core.vpn.InternetCheck.status()
            require(status == com.ars3nb.volta.core.vpn.InternetCheck.Status.OK) { "$status" }
            "${com.ars3nb.volta.core.vpn.InternetCheck.read().count { it.up }} adapters up"
        }

        check("the helpers are bundled and run (voltaproxy, OpenFlux)") {
            val dir = AppPaths.resourcesDir()
            com.ars3nb.volta.core.helper.ProxyHelper.init(dir, File(paths.logDir, "voltaproxy.log"))
            com.ars3nb.volta.core.openflux.OpenFluxRunner.init(dir, File(work, "openflux"), File(paths.logDir, "openflux.log"))
            require(com.ars3nb.volta.core.helper.ProxyHelper.available) { "no voltaproxy.exe in $dir" }
            require(com.ars3nb.volta.core.openflux.OpenFluxRunner.available) { "no openflux.exe in $dir" }
            // A broken config: the helper must start, read it and say what is wrong.
            val awg = com.ars3nb.volta.core.helper.ProxyHelper.start(
                com.ars3nb.volta.core.helper.ProxyHelper.Spec(com.ars3nb.volta.core.helper.ProxyHelper.Spec.AWG, "[Interface]\nPrivateKey = broken\n")
            )
            val awgWhy = awg.exceptionOrNull()?.message.orEmpty()
            require(awg.isFailure && awgWhy.isNotBlank() &&
                !awgWhy.contains(com.ars3nb.volta.desktop.i18n.Res.string(com.ars3nb.volta.desktop.i18n.R.string.home_helper_no_answer))
            ) { "voltaproxy: ${awg.exceptionOrNull()?.message}" }
            val flux = runBlocking { com.ars3nb.volta.core.openflux.OpenFluxRunner.start("openflux://v1/broken") }
            val fluxWhy = flux.exceptionOrNull()?.message.orEmpty()
            require(flux.isFailure && fluxWhy.isNotBlank() &&
                fluxWhy != com.ars3nb.volta.desktop.i18n.Res.string(com.ars3nb.volta.desktop.i18n.R.string.servers_openflux_not_in_build)
            ) { "openflux: $fluxWhy" }
            com.ars3nb.volta.core.helper.ProxyHelper.stop()
            com.ars3nb.volta.core.openflux.OpenFluxRunner.stop()
            Thread.sleep(500)
            val left = ProcessHandle.allProcesses().filter { h ->
                val cmd = h.info().command().orElse("").lowercase()
                cmd.endsWith("voltaproxy.exe") || cmd.endsWith("openflux.exe")
            }.count()
            require(left == 0L) { "$left helper process(es) left running" }
            "voltaproxy: «${awgWhy.take(60)}», openflux: «${fluxWhy.take(60)}»"
        }

        check("the update path (find, download, SHA-256, start)") {
            com.ars3nb.volta.desktop.update.UpdateDrill.run(File(work, "update-drill"))
        }

        check("a backup made on Android comes in and goes back out") {
            backupRoundTrip(File(work, "backup"), core)
        }

        check("direct HTTPS from the bundled Java runtime") {
            "HTTP ${get("https://www.gstatic.com/generate_204", Proxy.NO_PROXY)}"
        }

        check("the core accepts every kind of config Volta builds") {
            val dir = File(work, "configs").apply { mkdirs() }
            val bad = mutableListOf<String>()
            sampleConfigs().forEach { (name, json) ->
                val f = File(dir, "$name.json").apply { writeText(json) }
                core.test(f)?.let { bad += "$name: $it" }
            }
            require(bad.isEmpty()) { bad.joinToString("; ") }
            "${sampleConfigs().size} configs"
        }

        // A VLESS server on this machine, run by the very same core.
        val uuid = UUID.randomUUID().toString()
        val serverPort = Ports.pick(0)
        val serverConfig = File(work, "server.json").apply {
            writeText(
                """{"log":{"loglevel":"warning"},"inbounds":[{"listen":"127.0.0.1","port":$serverPort,"protocol":"vless",""" +
                    """"settings":{"clients":[{"id":"$uuid"}],"decryption":"none"},"streamSettings":{"network":"tcp","security":"none"}}],""" +
                    """"outbounds":[{"protocol":"freedom"}]}"""
            )
        }
        val serverProcess = runCatching { core.start(serverConfig, File(work, "server.log")) }.getOrNull()
        try {
            check("local test server") {
                require(serverProcess != null) { "could not start" }
                require(Ports.awaitListening(listOf(serverPort), serverProcess, 8_000)) {
                    File(work, "server.log").readText().takeLast(400)
                }
                "127.0.0.1:$serverPort"
            }
            val link = "vless://$uuid@127.0.0.1:$serverPort?type=tcp&security=none&encryption=none#Local%20test"
            val parsed = ConfigParser.parseShareLink(link)!!.copy(id = 1)

            check("real delay through a server") {
                val ms = runBlocking { DelayTester(core, paths.runDir).measure(listOf(parsed), 10_000, rounds = 2) }[1L].orEmpty()
                require(ms.isNotEmpty() && ms.all { it > 0 }) { "samples $ms" }
                "${ms.joinToString()} ms"
            }

            val proxyBackup = File(work, "proxy-backup.json")
            val systemProxy = SystemProxy(proxyBackup)
            val before = if (testProxy) WindowsApi.read() else null
            val controller = VoltaController(paths, core = core, systemProxy = systemProxy)
            val sub = SubscriptionEntity(id = 2, name = "Self-test", url = "https://example.invalid/sub", lastUpdated = System.currentTimeMillis())
            VoltaController.seed(
                controller,
                StoreData(
                    subscriptions = listOf(sub),
                    servers = listOf(parsed.copy(id = 3, subscriptionId = 2, role = ServerRole.NORMAL, isSelected = true)),
                    routingProfiles = controller.store.value.routingProfiles,
                    settings = AppSettings(connectMode = ConnectMode.MANUAL, systemProxy = testProxy, localDns = true),
                    nextId = 10
                )
            )

            check("connect to the chosen server") {
                controller.connect()
                val ok = waitFor(30_000) { controller.ui.value.vpnState == VpnConnectionState.CONNECTED }
                require(ok) { "state ${controller.ui.value.vpnState}: ${controller.ui.value.runtime.error}" }
                val ports = controller.ui.value.runtime.ports!!
                "socks ${ports.socks}, http ${ports.http}, proxy ${controller.ui.value.runtime.proxyActive}"
            }

            check("traffic through the HTTP and SOCKS ports") {
                val ports = controller.tunnel.ports ?: error("no tunnel")
                val viaHttp = get("https://www.gstatic.com/generate_204", Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ports.http)))
                val viaSocks = get("https://cp.cloudflare.com/generate_204", Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", ports.socks)))
                require(viaHttp in 200..399 && viaSocks in 200..399) { "http $viaHttp, socks $viaSocks" }
                "http $viaHttp, socks $viaSocks"
            }

            check("traffic counters") {
                val ports = controller.tunnel.ports ?: error("no tunnel")
                val totals = TrafficMeter.read(ports.metrics) ?: error("metrics endpoint gave nothing")
                require(totals.downlink > 0 && totals.uplink > 0) { "$totals" }
                waitFor(6_000) { controller.ui.value.runtime.traffic.downlinkBytes > 0 }
                "${totals.downlink} B down, ${totals.uplink} B up, screen ${controller.ui.value.runtime.traffic.downlinkBytes}"
            }

            if (testProxy) {
                check("Windows proxy points at Volta while connected") {
                    val now = WindowsApi.read()
                    val http = controller.tunnel.ports!!.http
                    require(now.proxyEnable == 1 && now.proxyServer == "127.0.0.1:$http") { "$now" }
                    val flags = WinApi.queryPerConnectionFlags()
                    require(flags == null || flags and WinApi.PROXY_TYPE_PROXY != 0) { "WinINet flags $flags" }
                    "ProxyServer=${now.proxyServer}, flags=$flags"
                }
            }

            check("disconnect gives everything back") {
                val httpPort = controller.tunnel.ports?.http ?: error("no tunnel")
                controller.disconnect()
                require(waitFor(15_000) { controller.ui.value.vpnState == VpnConnectionState.DISCONNECTED }) {
                    "state ${controller.ui.value.vpnState}"
                }
                require(!Ports.isListening(httpPort)) { "port $httpPort still open" }
                if (before != null) {
                    val after = WindowsApi.read()
                    require(after.proxyEnable == before.proxyEnable && after.proxyServer == before.proxyServer) {
                        "before $before, after $after"
                    }
                    require(!proxyBackup.exists()) { "backup left behind" }
                }
                "restored"
            }

            check("traffic is counted per day") {
                val days = controller.store.value.trafficDays
                val today = com.ars3nb.volta.core.stats.TrafficTally.dayOf(System.currentTimeMillis())
                val bytes = days.firstOrNull { it.day == today }?.totalBytes ?: 0
                require(bytes > 0) { "days $days" }
                "$bytes B today"
            }

            check("ad blocking and own rules block by site name through the proxy") {
                controller.setAdBlock(com.ars3nb.volta.data.model.AdBlockMode.STANDARD)
                controller.setBlockTorrents(true)
                controller.createRoutingProfile("Self-test")
                val profile = controller.store.value.routingProfiles.first { it.name == "Self-test" }
                controller.saveRules(
                    profile.id,
                    listOf(
                        com.ars3nb.volta.data.model.RoutingRuleDto(
                            name = "Block",
                            outbound = com.ars3nb.volta.data.model.RouteOutbound.BLOCK,
                            domains = listOf("domain:example.org")
                        )
                    )
                )
                controller.setActiveRouting(profile.id)
                controller.connect()
                require(waitFor(30_000) { controller.ui.value.vpnState == VpnConnectionState.CONNECTED }) {
                    "state ${controller.ui.value.vpnState}: ${controller.ui.value.runtime.error}"
                }
                val ports = controller.tunnel.ports ?: error("no tunnel")
                val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", ports.http))
                fun blocked(url: String) = runCatching { get(url, proxy) !in 200..399 }.getOrDefault(true)
                require(!blocked("https://www.gstatic.com/generate_204")) { "ordinary site blocked" }
                // A tracker in geosite:category-ads-all, one from Volta's own list, the user's rule.
                require(blocked("https://mc.yandex.ru/watch/1")) { "mc.yandex.ru got through" }
                require(blocked("https://metrika.yandex.ru/")) { "metrika.yandex.ru got through" }
                require(blocked("https://example.org/")) { "example.org got through the user's rule" }
                controller.disconnect()
                waitFor(15_000) { controller.ui.value.vpnState == VpnConnectionState.DISCONNECTED }
                controller.setAdBlock(com.ars3nb.volta.data.model.AdBlockMode.OFF)
                controller.setActiveRouting(controller.store.value.routingProfiles.first { it.isBuiltIn }.id)
                "trackers and example.org blocked, gstatic through"
            }

            check("auto mode measures and connects") {
                controller.setConnectMode(ConnectMode.AUTO_HOME)
                controller.connect()
                val ok = waitFor(45_000) { controller.ui.value.vpnState == VpnConnectionState.CONNECTED }
                require(ok) { "state ${controller.ui.value.vpnState}: ${controller.ui.value.runtime.error}" }
                val server = controller.ui.value.servers.first()
                controller.disconnect()
                waitFor(15_000) { controller.ui.value.vpnState == VpnConnectionState.DISCONNECTED }
                "measured ${server.latencyMs} ms"
            }

            if (testProxy) {
                check("start with Windows") {
                    val exe = com.ars3nb.volta.desktop.platform.Autostart.launcher() ?: error("jpackage.app-path not set")
                    val autostart = com.ars3nb.volta.desktop.platform.Autostart
                    val was = autostart.isEnabled()
                    require(autostart.setEnabled(true) && autostart.isEnabled()) { "could not switch on" }
                    val value = WinApi.regGetString(WinApi.Root.CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Run", "Volta")
                    require(value != null && value.contains(exe.name) && value.contains("--autostart")) { "Run value $value" }
                    require(autostart.setEnabled(was) && autostart.isEnabled() == was) { "could not switch back" }
                    "Run → $value"
                }
                check("a crash while connected is repaired on the next start") {
                    val original = WindowsApi.read()
                    require(systemProxy.apply(Ports.pick(0))) { "apply refused" }
                    // Volta "dies" here; the next start runs:
                    val next = SystemProxy(proxyBackup)
                    require(next.recoverAfterCrash()) { "recover refused" }
                    val after = WindowsApi.read()
                    require(after.proxyEnable == original.proxyEnable && after.proxyServer == original.proxyServer) {
                        "before $original, after $after"
                    }
                    "restored ${after.proxyServer ?: "(none)"}"
                }
            }
            controller.shutdown()
        } finally {
            serverProcess?.destroy()
        }

        if (shots != null) drawScreens(shots, paths, core)
        runCatching { work.deleteRecursively() }
    }

    /** Every screen, drawn off-screen by the real composables, saved as PNG. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun drawScreens(dir: File, paths: AppPaths, core: XrayCore) {
        dir.mkdirs()
        val controller = VoltaController(AppPaths(File(paths.dataDir, "shots")).apply { ensure() }, core = core)
        VoltaController.seed(controller, sampleState(controller))
        val bridge = object : DesktopBridge {
            override fun readClipboard(): String? = null
            override fun writeClipboard(text: String) = Unit
            override fun pickFile(): String? = null
            override fun openUrl(url: String) = Unit
            override fun openFolder(dir: File) = Unit
        }
        com.ars3nb.volta.desktop.i18n.I18n.use("ru")
        val cases = listOf(
            Triple("home-off", Page.HOME, AppSettings()),
            Triple("home-on", Page.HOME, AppSettings()),
            Triple("servers", Page.SERVERS, AppSettings()),
            Triple("subscriptions", Page.SUBSCRIPTIONS, AppSettings()),
            Triple("add", Page.ADD, AppSettings()),
            Triple("settings", Page.SETTINGS, AppSettings()),
            Triple("home-on-light-en", Page.HOME, AppSettings(designTheme = DesignTheme.LIGHT, language = "en")),
            Triple("servers-ocean-de", Page.SERVERS, AppSettings(designTheme = DesignTheme.OCEAN, language = "de")),
            Triple("settings-ember-zh", Page.SETTINGS, AppSettings(designTheme = DesignTheme.EMBER, language = "zh")),
            Triple("home-off-fa", Page.HOME, AppSettings(language = "fa")),
            Triple("subscriptions-kk", Page.SUBSCRIPTIONS, AppSettings(language = "kk")),
            Triple("routing", Page.ROUTING, AppSettings(adBlock = com.ars3nb.volta.data.model.AdBlockMode.STANDARD, bypassSites = listOf("bank.ru", "gosuslugi.ru"))),
            Triple("routing-light-en", Page.ROUTING, AppSettings(designTheme = DesignTheme.LIGHT, language = "en")),
            Triple("routing-ar", Page.ROUTING, AppSettings(language = "ar")),
            Triple("settings-material-fr", Page.SETTINGS, AppSettings(designTheme = DesignTheme.DYNAMIC, language = "fr")),
            Triple("settings-be", Page.SETTINGS, AppSettings(language = "be")),
            Triple("home-on-update-pt", Page.HOME, AppSettings(language = "pt-BR")),
            Triple("home-off-nonet-az", Page.HOME, AppSettings(language = "az")),
            Triple("home-off-openflux", Page.HOME, AppSettings())
        )
        for ((name, page, settings) in cases) {
            check("draw $name") {
                controller.updateSettings { settings }
                com.ars3nb.volta.desktop.i18n.I18n.use(settings.language)
                val d = controller.store.value
                if (name.contains("update")) {
                    controller.updater.pretend(
                        com.ars3nb.volta.desktop.update.UpdateState.Available(
                            com.ars3nb.volta.desktop.update.UpdateInfo(
                                612, "1.1.512", "v26.7.28",
                                com.ars3nb.volta.desktop.update.UpdateAsset("volta-1.1.512-windows-x64-setup.exe", 98_000_000, "0".repeat(64)),
                                ""
                            )
                        )
                    )
                } else {
                    controller.updater.pretend(com.ars3nb.volta.desktop.update.UpdateState.UpToDate(System.currentTimeMillis()))
                }
                if (name.contains("openflux")) {
                    com.ars3nb.volta.core.openflux.OpenFluxRunner.onEvent(
                        com.google.gson.JsonParser.parseString(
                            """{"event":"captcha","url":"https://passport.yandex.ru/","transport":"yandex-1"}"""
                        ).asJsonObject
                    )
                } else {
                    com.ars3nb.volta.core.openflux.OpenFluxRunner.reset()
                }
                val base = if (name.startsWith("home-on")) connectedRuntime(controller) else RuntimeState(coreVersion = "26.7.28")
                val state = VoltaUiState(
                    subscriptions = d.subscriptions,
                    servers = d.servers,
                    routingProfiles = d.routingProfiles,
                    settings = d.settings,
                    networkRegime = d.networkRegime,
                    trafficDays = d.trafficDays,
                    runtime = base.copy(
                        internet = if (name.contains("nonet")) com.ars3nb.volta.core.vpn.InternetCheck.Status.NO_NETWORK
                        else com.ars3nb.volta.core.vpn.InternetCheck.Status.OK,
                        routingDirty = name.startsWith("routing")
                    )
                )
                val view = AppViewState().apply { this.page = page }
                val scene = ImageComposeScene(width = 1000, height = 720, density = Density(1f)) {
                    VoltaDesktopApp(controller, state, view, bridge)
                }
                try {
                    var t = 0L
                    repeat(40) {
                        scene.render(t)
                        t += 50_000_000L
                    }
                    val image = scene.render(t)
                    val png = image.encodeToData(EncodedImageFormat.PNG) ?: error("no PNG")
                    File(dir, "$name.png").writeBytes(png.bytes)
                    "${image.width}x${image.height}"
                } finally {
                    scene.close()
                }
            }
        }
        com.ars3nb.volta.core.openflux.OpenFluxRunner.reset()
        controller.shutdown()
    }

    /** The Android app's own backup format in, then out again (cf. ParityTest). */
    private fun backupRoundTrip(dir: File, core: XrayCore): String {
        val contents = com.ars3nb.volta.core.backup.BackupCodec.decode(ANDROID_BACKUP)
        require(contents.subscriptions.size == 1 && contents.servers.size == 1 && contents.routing.size == 1) { "$contents" }
        val controller = VoltaController(AppPaths(dir).apply { ensure() }, core = core)
        try {
            val report = runBlocking { controller.restoreInternal(contents) }
            require(report.serversAdded == 1 && report.routingRestored == 1 && report.subscriptionsAdded == 1) { "$report" }
            val st = controller.store.value.settings
            require(st.adBlock == com.ars3nb.volta.data.model.AdBlockMode.FAMILY && st.designTheme == DesignTheme.EMBER) { "$st" }
            val out = com.ars3nb.volta.core.backup.BackupCodec.decode(controller.exportBackup())
            require(out.subscriptions.single().entity.url == contents.subscriptions.single().entity.url)
            require(out.servers.single().uuid == contents.servers.single().uuid)
            require(out.settings["mtu"] == 1380 && out.settings["ad_block"] == "FAMILY") { "${out.settings}" }
            require(out.activeRouting == "Мои правила") { "${out.activeRouting}" }
            return report.message()
        } finally {
            controller.shutdown()
        }
    }

    /** What the Android app writes (BackupCodec.encode), trimmed to one of each. */
    private val ANDROID_BACKUP = """
        {"format":"volta-backup","version":1,"createdAt":1793836800000,"app":"1.1.412",
         "subscriptions":[{"name":"Мой VPN","nameLocked":true,"userAgent":"","updateIntervalHours":12,"isEnabled":true,
           "sortOrder":0,"hwidEnabled":true,"colorHex":"#4A8FC4","serverTypes":"lte:1","url":"https://panel.invalid/sub/abc",
           "routing":"Мои правила"}],
         "servers":[{"remark":"Мой сервер","protocol":"VLESS","address":"203.0.113.5","port":443,
           "uuid":"6a9c3b1e-1f2d-4c3b-9a8e-7d6c5b4a3f21","security":"tls","encryption":"none","network":"tcp",
           "sni":"example.com","rawLink":"vless://6a9c3b1e-1f2d-4c3b-9a8e-7d6c5b4a3f21@203.0.113.5:443?security=tls",
           "isFavorite":true,"role":"NORMAL"}],
         "routing":[{"name":"Мои правила","description":"","isBuiltIn":false,"useProviderRules":true,
           "rulesJson":"[{\"id\":\"r1\",\"name\":\"Банк\",\"enabled\":true,\"outbound\":\"DIRECT\",\"domains\":[\"domain:bank.ru\"]}]",
           "domainStrategy":"IPIfNonMatch","remoteDns":"https://9.9.9.9/dns-query","domesticDns":"https://dns.google/dns-query","dnsHostsJson":""}],
         "activeRouting":"Мои правила",
         "settings":{"auto_failover":true,"design_theme":"EMBER","ad_block":"FAMILY","mtu":1380,"per_app_mode":"EXCLUDE",
           "per_app_packages":["ru.sberbankmobile"]}}
    """.trimIndent()

    private fun connectedRuntime(controller: VoltaController): RuntimeState {
        val server = controller.store.value.servers.first { it.isSelected }
        var speed = SpeedHistory()
        listOf(120, 340, 900, 2400, 1800, 3100, 2600, 4200, 3900, 2200, 2800, 5200, 4700, 3600).forEach {
            speed = speed.push(it * 1024L, it * 160L)
        }
        return RuntimeState(
            vpnState = VpnConnectionState.CONNECTED,
            connectedServerId = server.id,
            connectedSince = System.currentTimeMillis() - 754_000,
            traffic = TrafficStats(18_400_000, 412_000_000, 540_000, 3_700_000),
            speed = speed,
            ipInfo = com.ars3nb.volta.data.model.IpInfo(ip = "185.23.108.12", country = "", countryCode = "NL"),
            ports = LocalPorts(10808, 10809, 50311, 50312),
            proxyActive = true,
            coreVersion = "26.7.28",
            pick = PickSummary(
                rows = listOf(
                    PickRow(server.id, server.remark, 38, 4, 3, true),
                    PickRow(-2, "🇩🇪 Germany · Frankfurt", 61, 9, 3, false),
                    PickRow(-3, "🇫🇮 Finland · Helsinki", 74, 12, 3, false)
                ),
                measured = 9,
                alive = 8,
                at = 1
            )
        )
    }

    private fun sampleState(controller: VoltaController): StoreData {
        val now = System.currentTimeMillis()
        val subs = listOf(
            SubscriptionEntity(
                id = 1, name = "Nebula VPN", url = "https://sub.example/a", lastUpdated = now - 7 * 60_000,
                trafficUsed = 38_000_000_000, trafficTotal = 100_000_000_000, expireAt = now + 23L * 86_400_000,
                colorHex = "#C4784A", announce = "Новые серверы в Казахстане и Турции"
            ),
            SubscriptionEntity(
                id = 2, name = "Белые списки", url = "https://sub.example/b", lastUpdated = now - 3 * 3_600_000,
                expireAt = now + 2L * 86_400_000, colorHex = "#4A8FC4", urlHidden = true
            )
        )
        val uuid = "11111111-2222-4333-8444-555555555555"
        val names = listOf(
            Triple("🇳🇱 Netherlands · Amsterdam", 38L, ServerRole.NORMAL),
            Triple("🇩🇪 Germany · Frankfurt", 61L, ServerRole.NORMAL),
            Triple("🇫🇮 Finland · Helsinki", 74L, ServerRole.NORMAL),
            Triple("🇩🇪 Germany · Berlin", 142L, ServerRole.NORMAL),
            Triple("🇺🇸 USA · New York", 168L, ServerRole.NORMAL),
            Triple("🇹🇷 Turkey · Istanbul", 95L, ServerRole.NORMAL),
            Triple("🇰🇿 Kazakhstan · Almaty", -1L, ServerRole.NORMAL),
            Triple("Авто · лучший", 55L, ServerRole.AUTO)
        )
        val lte = listOf(
            Triple("[L] Москва LTE 1", 48L, ServerRole.BYPASS_WHITELIST),
            Triple("[L] Москва LTE 2", 210L, ServerRole.BYPASS_WHITELIST),
            Triple("[L] Санкт-Петербург LTE", 380L, ServerRole.BYPASS_WHITELIST)
        )
        var id = 100L
        val servers = names.map { (remark, ms, role) ->
            ServerEntity(
                id = id++, subscriptionId = 1, remark = remark, address = "n$id.example", port = 443, uuid = uuid,
                security = "reality", pbk = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA", sni = "www.microsoft.com",
                network = if (id % 2 == 0L) "tcp" else "xhttp", flow = "xtls-rprx-vision",
                latencyMs = ms, ewmaMs = ms, role = role, isSelected = remark.contains("Amsterdam"),
                isFavorite = remark.contains("Helsinki"), failStreak = if (ms < 0) 2 else 0
            )
        } + lte.map { (remark, ms, role) ->
            ServerEntity(
                id = id++, subscriptionId = 2, remark = remark.removePrefix("[L] "), address = "l$id.example", port = 443,
                uuid = uuid, security = "tls", network = "ws", latencyMs = ms, ewmaMs = ms, role = role, roleFromProvider = true
            )
        } + ServerEntity(
            id = id, subscriptionId = null, remark = "AmneziaWG · home", protocol = com.ars3nb.volta.data.model.ProtocolType.AMNEZIAWG,
            address = "203.0.113.7", port = 51820, network = "awg2"
        )
        return StoreData(
            subscriptions = subs,
            servers = servers,
            routingProfiles = controller.store.value.routingProfiles,
            settings = AppSettings(),
            nextId = id + 1,
            networkRegime = com.ars3nb.volta.data.model.NetworkRegime.OPEN,
            trafficDays = (0L until 14L).map { back ->
                val day = java.time.LocalDate.now().minusDays(back).toEpochDay()
                com.ars3nb.volta.core.stats.TrafficDayEntity(day, 20_000_000L * (back % 3 + 1), 300_000_000L * ((back * 7) % 5 + 1))
            }
        )
    }

    /** One config per protocol and transport, as Volta builds them (cf. CoreConfigSamplesTest). */
    private fun sampleConfigs(): List<Pair<String, String>> {
        val uuid = "11111111-2222-4333-8444-555555555555"
        val key = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
        fun b64(t: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(t.toByteArray())
        val vmess = b64(
            """{"v":"2","ps":"vm","add":"node.example","port":"443","id":"$uuid","aid":"0","scy":"auto",""" +
                """"net":"ws","type":"none","host":"cdn.example","path":"/vm","tls":"tls","sni":"cdn.example","fp":"chrome"}"""
        )
        val links = linkedMapOf(
            "vless-reality-vision" to "vless://$uuid@node.example:443?security=reality&encryption=none&pbk=$key&fp=chrome&type=tcp&flow=xtls-rprx-vision&sni=www.microsoft.com&sid=ab12#R",
            "vless-tls-ws" to "vless://$uuid@node.example:443?security=tls&encryption=none&type=ws&path=%2Fws&host=cdn.example&sni=cdn.example&fp=chrome#WS",
            "vless-reality-xhttp" to "vless://$uuid@node.example:443?security=reality&encryption=none&pbk=$key&fp=chrome&type=xhttp&mode=auto&path=%2Fx&sni=www.microsoft.com&sid=ab12#X",
            "vless-grpc" to "vless://$uuid@node.example:443?security=tls&encryption=none&type=grpc&serviceName=svc&sni=node.example#G",
            "vmess-ws-tls" to "vmess://$vmess",
            "trojan" to "trojan://pass@node.example:443?security=tls&type=tcp&sni=node.example#T",
            "shadowsocks" to "ss://${b64("aes-256-gcm:password")}@ss.example:8388#SS",
            "shadowsocks-2022" to "ss://${b64("2022-blake3-aes-128-gcm:BQYHCAkKCwwNDg8QERITFA==")}@ss.example:443#SS22",
            "hysteria2" to "hysteria2://s3cret@hy.example:8443/?sni=cdn.example&obfs=salamander&obfs-password=abc&upmbps=50&downmbps=200#HY",
            "wireguard" to "wireguard://KCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4%2FQEFCQ0RFRkc%3D@wg.example:51820?publickey=UFFSU1RVVldYWVpbXF1eX2BhYmNkZWZnaGlqa2xtbm8%3D&address=10.7.0.2%2F32#WG",
            "socks" to "socks://${b64("user:pass")}@sox.example:1080#SOCKS"
        )
        val servers = links.mapValues { (name, link) -> requireNotNull(ConfigParser.parseShareLink(link)) { "$name did not parse" } }
        val out = servers.map { (name, s) ->
            name to ConfigBuilder.build(s, null, socksPort = 20808, httpPort = 20809, exitCheckPort = 20807, metricsPort = 20810)
        }.toMutableList()
        val ws = servers.getValue("vless-tls-ws")
        RoutingEngine.builtInProfiles().forEachIndexed { i, p -> out += "routing-$i" to ConfigBuilder.build(ws, p, metricsPort = 20810) }
        com.ars3nb.volta.data.model.AdBlockMode.entries.forEach { mode ->
            out += "adblock-${mode.name.lowercase()}" to ConfigBuilder.build(ws, null, adBlock = mode, blockTorrents = true, metricsPort = 20810)
        }
        val own = com.ars3nb.volta.data.model.RoutingProfileEntity(
            name = "Own",
            rulesJson = RoutingEngine.serializeRules(
                listOf(
                    com.ars3nb.volta.data.model.RoutingRuleDto(name = "a", outbound = com.ars3nb.volta.data.model.RouteOutbound.DIRECT, domains = listOf("domain:bank.ru", "geosite:category-ru")),
                    com.ars3nb.volta.data.model.RoutingRuleDto(name = "b", outbound = com.ars3nb.volta.data.model.RouteOutbound.BLOCK, ips = listOf("geoip:private", "203.0.113.0/24")),
                    com.ars3nb.volta.data.model.RoutingRuleDto(name = "c", outbound = com.ars3nb.volta.data.model.RouteOutbound.PROXY, domains = listOf("full:chat.openai.com"))
                )
            )
        )
        out += "routing-own" to ConfigBuilder.build(ws, own, adBlock = com.ars3nb.volta.data.model.AdBlockMode.STRICT, metricsPort = 20810)
        com.ars3nb.volta.core.routing.Presets.sites.forEach { preset ->
            val p = own.copy(rulesJson = RoutingEngine.serializeRules(listOf(preset.toRule())))
            out += "preset-${preset.id}" to ConfigBuilder.build(ws, p, metricsPort = 20810)
        }
        if (com.ars3nb.volta.core.helper.ProxyHelper.available) {
            val hy = ServerEntity(
                remark = "HY1", protocol = com.ars3nb.volta.data.model.ProtocolType.HYSTERIA,
                address = "hy.example", port = 443, rawLink = "hysteria://hy.example:443?auth=x&upmbps=10&downmbps=50"
            )
            out += "helper-hysteria" to ConfigBuilder.build(hy, null, metricsPort = 20810)
        }
        val batch = servers.values.mapIndexed { i, s -> s.copy(id = i + 1L) }
        out += "delay-batch" to DelayTester(XrayCore(File("x"), File("."), File(".")), File(".")).probeConfig(batch, batch.indices.map { 30000 + it })
        return out
    }

    private fun get(url: String, proxy: Proxy): Int {
        val c = URI(url).toURL().openConnection(proxy) as HttpURLConnection
        return try {
            c.connectTimeout = 10_000
            c.readTimeout = 10_000
            c.instanceFollowRedirects = false
            c.responseCode
        } finally {
            c.disconnect()
        }
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }
}
