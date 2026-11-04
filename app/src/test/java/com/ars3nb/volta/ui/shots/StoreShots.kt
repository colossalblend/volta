package com.ars3nb.volta.ui.shots

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import com.ars3nb.volta.R
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.stats.TrafficSummary
import com.ars3nb.volta.core.stats.TrafficTally
import com.ars3nb.volta.core.update.UpdateState
import com.ars3nb.volta.data.db.VoltaDatabase
import com.ars3nb.volta.data.model.AdBlockMode
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.IpInfo
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.TrafficDayEntity
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.prefs.AppPreferences
import com.ars3nb.volta.data.prefs.DesignTheme
import com.ars3nb.volta.data.repository.VoltaRepository
import com.ars3nb.volta.ui.ConnectPhase
import com.ars3nb.volta.ui.PickRow
import com.ars3nb.volta.ui.PickSummary
import com.ars3nb.volta.ui.SpeedHistory
import com.ars3nb.volta.ui.VoltaUiState
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.FormatText
import com.ars3nb.volta.ui.components.PowerButton
import com.ars3nb.volta.ui.navigation.Routes
import com.ars3nb.volta.ui.navigation.VoltaBottomBar
import com.ars3nb.volta.ui.navigation.VoltaRail
import com.ars3nb.volta.ui.screens.HomeContent
import com.ars3nb.volta.ui.screens.ImportScreen
import com.ars3nb.volta.ui.screens.PerAppScreen
import com.ars3nb.volta.ui.screens.RoutingScreen
import com.ars3nb.volta.ui.screens.ServersScreen
import com.ars3nb.volta.ui.screens.SettingsScreen
import com.ars3nb.volta.ui.screens.SubscriptionsScreen
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.ProvideTv
import java.io.File
import java.time.LocalDate
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runners.MethodSorters
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The raw screens for the Google Play listing (store/play): real composables with made-up
 * but realistic data, drawn at the size they take inside the listing pictures, which
 * tools/store/compose.py then builds. Runs only when VOLTA_STORE is set (store.yml); the
 * PNGs go to `<volta.shots>/raw/<lang>/`.
 *
 * Screens that need the ViewModel get a real one over a real (Robolectric) database and
 * preferences, filled with sample servers; the app's own startup work (WorkManager, the
 * network watcher, the updater) never runs, and the network is shut off, so nothing in the
 * pictures comes from the machine that drew them.
 *
 * One subclass per listing language, each with its locale in the qualifiers.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
abstract class StoreShots(private val lang: String) {

    @get:Rule(order = 0)
    val log = object : TestWatcher() {
        override fun starting(description: Description) = say("start ${description.methodName}")
        override fun finished(description: Description) = say("end ${description.methodName}")
    }

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out: File by lazy {
        File(System.getProperty("volta.shots") ?: "build/shots", "raw/$lang").apply { mkdirs() }
    }

    @Before
    fun onlyForTheStore() {
        assumeTrue("store pictures are drawn only by store.yml", !System.getenv("VOLTA_STORE").isNullOrBlank())
        // No lookups of this machine's address, no update checks: every request fails fast.
        System.setProperty("http.proxyHost", "127.0.0.1")
        System.setProperty("http.proxyPort", "9")
        System.setProperty("https.proxyHost", "127.0.0.1")
        System.setProperty("https.proxyPort", "9")
        // What MainActivity does: the units drawn outside a composable ("48 мс" in a
        // server row) read the app's language from here.
        FormatText.resources = compose.activity.resources
    }

    // ------------------------------------------------------------------ home (stateless)

    /** 1: on, Home group, Wi-Fi. */
    @Test
    fun homeOn() = home("home-on", connectedHome())

    /** 2: on, the LTE group, after the network turned out to be a whitelisted mobile one. */
    @Test
    fun homeLte() = home("home-lte", connectedLte(), pick = pickLte())

    /** 7: what is under the disc: speed, traffic per day, why this server. */
    @Test
    fun homeDetails() = home("home-details", connectedHome(), traffic = traffic(), scrollTo = 1)

    @Test
    fun homeOff() = home("home-off", base())

    @Test
    @Config(qualifiers = "+w1280dp-h720dp-land-television-hdpi")
    fun tvHome() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                ProvideTv(true) {
                    TvFrame(Routes.HOME) {
                        Home(connectedHome(), ConnectMode.AUTO_HOME_LTE, pickHome(), TrafficSummary())
                    }
                }
            }
        }
        settle(2_500)
        capture("tv-home")
    }

    /** The disc alone, for the feature graphic. */
    @Test
    @Config(qualifiers = "+w400dp-h400dp-xhdpi")
    fun disc() {
        var state by mutableStateOf(VpnConnectionState.CONNECTED)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    PowerButton(
                        state = state,
                        working = false,
                        enabled = true,
                        onClick = {},
                        size = 340.dp,
                        activity = if (state == VpnConnectionState.CONNECTED) 0.6f else 0f
                    )
                }
            }
        }
        settle(2_500)
        capture("disc-on")
        state = VpnConnectionState.DISCONNECTED
        settle(2_500)
        capture("disc-off")
    }

    // ------------------------------------------------------------------ screens with the ViewModel

    @Test
    fun appScreens() {
        val vm = viewModel()
        var screen by mutableStateOf("servers")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                ProvideTv(false) {
                    AppSurface {
                        when (screen) {
                            "servers" -> Tabbed(Routes.SERVERS) { ServersScreen(vm, onOpenImport = {}) }
                            "subscriptions" -> Tabbed(Routes.SUBSCRIPTIONS) { SubscriptionsScreen(vm, onOpenImport = {}) }
                            // Without the tab bar: the ad filter's card with the torrent
                            // switch under it is taller than what is left above the bar.
                            "routing" -> RoutingScreen(vm, onOpenPerApp = {})
                            "perapp" -> PerAppScreen(vm, onBack = {})
                            "import" -> ImportScreen(vm, onBack = {})
                        }
                    }
                }
            }
        }
        settle(3_000) { vm.uiState.value.servers.size == SERVERS.size }
        capture("servers")

        screen = "subscriptions"
        settle(2_000)
        capture("subscriptions")

        screen = "routing"
        settle(2_000) { vm.uiState.value.routingProfiles.isNotEmpty() }
        // The filters' card from its top edge down to the torrent switch. Where the edges
        // fall depends on the language's text: in Russian the header's descenders must go
        // fully under the bar, in English the next card must stay below the screen.
        scrollToTop(text(R.string.routing_filters))
        scrollNodeToTop(text(R.string.routing_adblock_title), gap = if (lang == "ru") 46f else 64f)
        scrollIntoView(text(R.string.routing_torrents_title), margin = 24f)
        capture("routing")

        screen = "perapp"
        settle(3_000)
        capture("perapp")

        screen = "import"
        settle(1_000)
        say("import: link")
        compose.onNode(hasSetTextAction()).performTextInput(SUBSCRIPTION_LINK)
        settle(1_200)
        say("import: name")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput(SUBSCRIPTION.name)
        settle(1_200)
        capture("import")

        vm.viewModelScope.cancel()
    }

    /** Last of all (see the method order): Settings, scrolled to its looks, and the languages. */
    @Test
    fun zSettings() {
        val vm = viewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                ProvideTv(false) {
                    Tabbed(Routes.SETTINGS) { SettingsScreen(vm) }
                }
            }
        }
        settle(1_500)
        say("settings: scroll")
        scrollToTop(text(R.string.settings_section_look))
        capture("settings-look")
        say("settings: language")
        compose.onAllNodesWithText(text(R.string.language_title))[0].performClick()
        settle(1_500)
        capture("settings-language")
        vm.viewModelScope.cancel()
    }

    @Test
    @Config(qualifiers = "+w1280dp-h720dp-land-television-hdpi")
    fun tvServers() {
        val vm = viewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                ProvideTv(true) {
                    TvFrame(Routes.SERVERS) { ServersScreen(vm, onOpenImport = {}) }
                }
            }
        }
        settle(3_000) { vm.uiState.value.servers.size == SERVERS.size }
        runCatching {
            compose.onAllNodesWithText(name(SERVERS[1]), substring = true)[0].requestFocus()
        }
        settle(800)
        capture("tv-servers")
        vm.viewModelScope.cancel()
    }

    /**
     * The TV's Add screen on a box with Bluetooth LE: a phone nearby sends the link, so
     * nothing is typed with the remote. Without a tab, so without the rail, as in TvApp.
     */
    @Test
    @Config(qualifiers = "+w1280dp-h720dp-land-television-hdpi")
    fun tvImport() {
        shadowOf(compose.activity.packageManager).setSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE, true)
        val vm = viewModel()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                ProvideTv(true) {
                    TvFrame(Routes.IMPORT, rail = false) { ImportScreen(vm, onBack = {}) }
                }
            }
        }
        settle(2_000)
        val row = hasText(text(R.string.share_receive_action), substring = true) and isFocusable()
        runCatching { compose.onAllNodes(row)[0].requestFocus() }
            .onFailure { say("focus: ${it.message?.lineSequence()?.firstOrNull()}") }
        settle(800)
        capture("tv-import")
        vm.viewModelScope.cancel()
    }

    // ------------------------------------------------------------------ frames

    /** What MainActivity puts under every screen: the background and its content colour. */
    @Composable
    private fun AppSurface(content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
    }

    @Composable
    private fun Tabbed(route: String, content: @Composable () -> Unit) {
        Scaffold(bottomBar = { VoltaBottomBar(route) {} }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) { content() }
        }
    }

    /**
     * The TV layout of VoltaNav's TvApp: the rail (on the tabs), then the screen at reading
     * width, under a Surface for the content colour as TvApp's Scaffold gives it. The
     * remote's keys put a real TV in keyboard mode, where rows take focus and show it.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun TvFrame(route: String, rail: Boolean = true, content: @Composable () -> Unit) {
        val input = LocalInputModeManager.current
        LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
                if (rail) VoltaRail(route) {}
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) { content() }
                }
            }
        }
    }

    private fun home(
        name: String,
        state: VoltaUiState,
        mode: ConnectMode = ConnectMode.AUTO_HOME_LTE,
        pick: PickSummary = pickHome(),
        traffic: TrafficSummary = TrafficSummary(),
        scrollTo: Int? = null
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                Tabbed(Routes.HOME) { Home(state, mode, pick, traffic) }
            }
        }
        settle(2_500)
        if (scrollTo != null) {
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(scrollTo)
            settle(1_200)
        }
        capture(name)
    }

    @Composable
    private fun Home(state: VoltaUiState, mode: ConnectMode, pick: PickSummary, traffic: TrafficSummary) {
        HomeContent(
            state = state,
            mode = mode,
            phase = ConnectPhase.IDLE,
            speed = speed(),
            pick = pick,
            update = UpdateState.Idle,
            openFlux = OpenFluxRunner.Status.Idle,
            traffic = traffic,
            now = state.connectedSince + 23 * 60_000L + 41_000L,
            onToggle = {},
            onSelectMode = {},
            onOpenServers = {},
            onOpenImport = {},
            onOpenRouting = {},
            onRecheckNetwork = {},
            onInstallUpdate = {}
        )
    }

    // ------------------------------------------------------------------ plumbing

    /** Lets the clock, the main thread and the database threads run for [ms], or until [done]. */
    private fun settle(ms: Long, done: () -> Boolean = { false }) {
        var t = 0L
        while (t < ms) {
            Thread.sleep(20)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(50)
            t += 50
            if (t >= 400 && done()) break
        }
        shadowOf(Looper.getMainLooper()).idle()
        compose.mainClock.advanceTimeBy(100)
    }

    /**
     * Scrolls the screen's list item by item until the one with [label] is at its top.
     * performScrollToNode would animate, and with the clock held still that never ends.
     */
    private fun scrollToTop(label: String) {
        val list = compose.onNode(hasScrollToIndexAction())
        val top = list.fetchSemanticsNode().boundsInRoot.top
        for (i in 0 until 40) {
            list.performScrollToIndex(i)
            settle(300)
            val node = compose.onAllNodesWithText(label).fetchSemanticsNodes().firstOrNull()
            if (node != null && node.boundsInRoot.top - top < 160f) {
                settle(900)
                return
            }
        }
        error("no \"$label\" in the list")
    }

    /** Scrolls the list by pixels so that [label] (inside an item) sits [gap] below its top. */
    private fun scrollNodeToTop(label: String, gap: Float = 36f) {
        val list = compose.onNode(hasScrollToIndexAction())
        val top = list.fetchSemanticsNode().positionInRoot.y
        for (i in 0 until 3) {
            val node = compose.onAllNodesWithText(label).fetchSemanticsNodes().first()
            val delta = node.positionInRoot.y - (top + gap)
            say("scroll: \"$label\" is $delta px below where it should be")
            if (kotlin.math.abs(delta) < 4f) break
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            settle(900)
        }
    }

    /** Scrolls the list down a little at a time until [label] sits above its bottom edge. */
    private fun scrollIntoView(label: String, margin: Float = 120f) {
        val list = compose.onNode(hasScrollToIndexAction())
        val bottom = list.fetchSemanticsNode().boundsInRoot.bottom
        for (i in 0 until 60) {
            // Its own bounds, not boundsInRoot: those are cut to what is on screen.
            val node = compose.onAllNodesWithText(label).fetchSemanticsNodes().firstOrNull()
            val end = node?.let { it.positionInRoot.y + it.size.height }
            if (i % 5 == 0) say("scroll: \"$label\" ends at $end, the list at $bottom")
            if (end != null && end <= bottom - margin) {
                settle(900)
                return
            }
            list.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
            settle(250)
        }
        error("could not bring \"$label\" into view")
    }

    /**
     * Runs [work] on a thread of its own and keeps the main looper going meanwhile, so
     * nothing the work waits for (Room, DataStore) can stall behind a blocked main thread.
     */
    private fun offMain(work: suspend () -> Unit) {
        var error: Throwable? = null
        val thread = Thread {
            try {
                runBlocking(Dispatchers.IO) { work() }
            } catch (t: Throwable) {
                error = t
            }
        }
        thread.start()
        val deadline = System.currentTimeMillis() + 120_000
        while (thread.isAlive) {
            check(System.currentTimeMillis() < deadline) { "sample setup took over two minutes" }
            shadowOf(Looper.getMainLooper()).idle()
            thread.join(20)
        }
        error?.let { throw it }
    }

    private fun say(what: String) {
        System.err.println("store[$lang] $what")
        File(out, "progress.txt").appendText("${System.currentTimeMillis()} $what\n")
    }

    private fun capture(name: String) {
        say("capture $name")
        val bitmap = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }
            .getOrElse {
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    view.draw(Canvas(it))
                }
            }
        val file = File(out, "$name.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun text(res: Int): String = compose.activity.getString(res)

    /**
     * A VoltaApp that never ran onCreate (no WorkManager, network watcher or guard), with
     * its repository over the test's database, filled with the sample library.
     */
    private fun viewModel(): VoltaViewModel {
        val base = ApplicationProvider.getApplicationContext<Application>()
        val app = VoltaApp()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
            invoke(app, base)
        }
        VoltaApp::class.java.getDeclaredField("instance").apply {
            isAccessible = true
            set(null, app)
        }
        installApps(base)
        offMain {
            val prefs = AppPreferences(base)
            prefs.setPingOnStart(false)
            prefs.setRefreshOnLaunch(false)
            prefs.setUpdateAutoCheck(false)
            prefs.setAutoConnect(false)
            prefs.setConnectMode(ConnectMode.AUTO_HOME_LTE)
            prefs.setNetworkRegime(NetworkRegime.OPEN.name)
            prefs.setAdBlock(AdBlockMode.STRICT)
            prefs.setBlockTorrents(true)
            prefs.setPerAppMode(PerAppMode.EXCLUDE)
            prefs.setPerAppPackages(BYPASS_APPS.map { it.first }.toSet())
            prefs.setDesignTheme(DesignTheme.VOLTA)

            val db = VoltaDatabase.get(base)
            db.clearAllTables()
            db.subscriptionDao().insert(SUBSCRIPTION)
            db.serverDao().insertAll(SERVERS)
            val today = LocalDate.now().toEpochDay()
            DAILY_MB.forEachIndexed { i, mb ->
                val bytes = mb * 1024L * 1024L
                db.trafficDao().add(today - (DAILY_MB.size - 1 - i), bytes / 12, bytes - bytes / 12)
            }
        }
        say("sample library in place")
        val repo = VoltaRepository(app)
        VoltaApp::class.java.getDeclaredField("repository").apply {
            isAccessible = true
            set(app, repo)
        }
        return VoltaViewModel(app)
    }

    /** Apps for split tunnelling: some of each ready-made "past the VPN" set, and a few others. */
    private fun installApps(context: Context) {
        val pm = shadowOf(context.packageManager)
        (BYPASS_APPS + OTHER_APPS).forEachIndexed { i, (pkg, label) ->
            val info = PackageInfo().apply {
                packageName = pkg
                requestedPermissions = arrayOf(Manifest.permission.INTERNET)
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                    flags = 0
                }
            }
            pm.installPackage(info)
            pm.setApplicationIcon(pkg, BitmapDrawable(context.resources, appIcon(label, ICON_COLORS[i % ICON_COLORS.size])))
        }
    }

    /** A plain round icon with the app's first letter: no real app's artwork. */
    private fun appIcon(label: String, color: Int): Bitmap {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRoundRect(RectF(4f, 4f, size - 4f, size - 4f), 26f, 26f, paint)
        paint.color = 0xF2FFFFFF.toInt()
        paint.textSize = 44f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        val y = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(label.take(1).uppercase(), size / 2f, y, paint)
        return bitmap
    }

    // ------------------------------------------------------------------ sample state

    private fun base() = VoltaUiState(
        servers = SERVERS,
        subscriptions = listOf(SUBSCRIPTION),
        selectedServer = SERVERS[0],
        networkRegime = NetworkRegime.OPEN,
        ipInfo = IpInfo(ip = "198.51.100.7", country = country("RU"))
    )

    private fun connectedHome() = base().copy(
        vpnState = VpnConnectionState.CONNECTED,
        connectedSince = 1_000_000L,
        traffic = TrafficStats(
            uplinkBytes = 48L * 1024 * 1024,
            downlinkBytes = 1_310L * 1024 * 1024,
            uplinkSpeed = 96L * 1024,
            downlinkSpeed = 2_400L * 1024
        ),
        ipInfo = IpInfo(ip = "203.0.113.10", country = country("NL"))
    )

    private fun connectedLte() = connectedHome().copy(
        selectedServer = SERVERS.first { it.id == 9L },
        networkRegime = NetworkRegime.WHITELIST_ONLY,
        traffic = TrafficStats(
            uplinkBytes = 21L * 1024 * 1024,
            downlinkBytes = 386L * 1024 * 1024,
            uplinkSpeed = 54L * 1024,
            downlinkSpeed = 1_150L * 1024
        ),
        ipInfo = IpInfo(ip = "198.51.100.14", country = country("RU"))
    )

    private fun country(code: String) = com.ars3nb.volta.core.server.Countries
        .name(compose.activity.resources, code)

    private fun speed(): SpeedHistory {
        var history = SpeedHistory()
        for (i in 0 until SpeedHistory.CAPACITY) {
            val down = 1_700_000 + 1_000_000 * sin(i / 3.2) + 450_000 * sin(i / 1.3)
            val up = 90_000 + 50_000 * sin(i / 2.1)
            history = history.push(down.toLong(), up.toLong())
        }
        return history
    }

    private fun pickHome() = PickSummary(
        reason = UiText.Raw(""),
        rows = listOf(
            PickRow(1, SERVERS[0].remark, 48, 4, 5, chosen = true),
            PickRow(8, SERVERS[7].remark, 57, 6, 5, chosen = false),
            PickRow(2, SERVERS[1].remark, 61, 7, 5, chosen = false)
        ),
        measured = 9,
        alive = 9,
        at = 0
    )

    private fun pickLte() = PickSummary(
        reason = UiText.Raw(""),
        rows = listOf(
            PickRow(9, SERVERS[8].remark, 22, 3, 5, chosen = true),
            PickRow(10, SERVERS[9].remark, 31, 4, 5, chosen = false),
            PickRow(11, SERVERS[10].remark, 38, 5, 5, chosen = false)
        ),
        measured = 3,
        alive = 3,
        at = 0
    )

    private fun traffic(): TrafficSummary {
        val today = LocalDate.now()
        val days = DAILY_MB.mapIndexed { i, mb ->
            val bytes = mb * 1024L * 1024L
            TrafficDayEntity(today.toEpochDay() - (DAILY_MB.size - 1 - i), bytes / 12, bytes - bytes / 12)
        }
        return TrafficTally.summarize(days, today)
    }

    private fun name(server: ServerEntity) =
        com.ars3nb.volta.core.server.Countries.withoutLeadingFlag(server.remark)

    // The sample library in the listing's language: the servers' names, the subscription's,
    // the apps'. Nothing in it is real.
    private val SERVERS: List<ServerEntity> = sampleServers(lang)
    private val SUBSCRIPTION: SubscriptionEntity = sampleSubscription(lang)
    private val BYPASS_APPS: List<Pair<String, String>> = bypassApps(lang)
    private val OTHER_APPS: List<Pair<String, String>> = otherApps(lang)

    companion object {
        const val SUBSCRIPTION_LINK = "https://sub.example.com/s/k7Qm2xVb9TfR"

        fun sampleSubscription(lang: String) = SubscriptionEntity(
            id = 1,
            name = if (lang == "ru") "Мой VPN" else "My VPN",
            url = SUBSCRIPTION_LINK,
            lastUpdated = System.currentTimeMillis() - 2 * 3_600_000L,
            expireAt = System.currentTimeMillis() + 86L * 86_400_000L,
            trafficUsed = 63L * 1024 * 1024 * 1024,
            trafficTotal = 200L * 1024 * 1024 * 1024
        )

        /** Made-up nodes on documentation addresses (RFC 5737), named as a provider would. */
        fun sampleServers(lang: String): List<ServerEntity> {
            val ru = lang == "ru"
            fun n(r: String, e: String) = if (ru) r else e
            return listOf(
                node(1, n("🇳🇱 Нидерланды, Амстердам", "🇳🇱 Netherlands, Amsterdam"), "203.0.113.10", 48, selected = true),
                node(2, n("🇩🇪 Германия, Франкфурт", "🇩🇪 Germany, Frankfurt"), "203.0.113.21", 61),
                node(3, n("🇫🇮 Финляндия, Хельсинки", "🇫🇮 Finland, Helsinki"), "203.0.113.33", 74, protocol = ProtocolType.TROJAN),
                node(4, n("🇸🇪 Швеция, Стокгольм", "🇸🇪 Sweden, Stockholm"), "203.0.113.45", 83, protocol = ProtocolType.HYSTERIA2),
                node(5, n("🇵🇱 Польша, Варшава", "🇵🇱 Poland, Warsaw"), "203.0.113.52", 69),
                node(6, n("🇹🇷 Турция, Стамбул", "🇹🇷 Turkey, Istanbul"), "203.0.113.67", 96),
                node(7, n("🇺🇸 США, Нью-Йорк", "🇺🇸 USA, New York"), "203.0.113.78", 142, protocol = ProtocolType.TROJAN),
                node(8, n("🇳🇱 Нидерланды, Роттердам", "🇳🇱 Netherlands, Rotterdam"), "203.0.113.11", 57),
                node(9, n("🇷🇺 Россия, Москва", "🇷🇺 Russia, Moscow"), "198.51.100.14", 22, role = ServerRole.BYPASS_WHITELIST),
                node(10, n("🇷🇺 Россия, Санкт-Петербург", "🇷🇺 Russia, St Petersburg"), "198.51.100.27", 31, role = ServerRole.BYPASS_WHITELIST),
                node(11, n("🇷🇺 Россия, Казань", "🇷🇺 Russia, Kazan"), "198.51.100.39", 38, role = ServerRole.BYPASS_WHITELIST),
                node(12, n("🇰🇿 Казахстан, Алматы", "🇰🇿 Kazakhstan, Almaty"), "203.0.113.90", 88)
            )
        }

        private fun node(
            id: Long,
            remark: String,
            address: String,
            ms: Long,
            role: ServerRole = ServerRole.NORMAL,
            protocol: ProtocolType = ProtocolType.VLESS,
            selected: Boolean = false
        ) = ServerEntity(
            id = id,
            subscriptionId = 1,
            remark = remark,
            protocol = protocol,
            address = address,
            port = 443,
            uuid = if (protocol == ProtocolType.VLESS) "3f1c9a52-7d4e-4b8a-9c21-5e6f7a8b9c0d" else "s4mpleP4ssw0rd",
            flow = if (protocol == ProtocolType.VLESS) "xtls-rprx-vision" else "",
            network = "tcp",
            security = if (protocol == ProtocolType.VLESS) "reality" else "tls",
            sni = "www.example.com",
            fp = "chrome",
            pbk = if (protocol == ProtocolType.VLESS) "mD3qY0xQ7rT1uV9wZ2aB4cE6fH8jK0lN2pR4sU6vX8y" else "",
            sid = if (protocol == ProtocolType.VLESS) "6ba85179e30d4fc2" else "",
            latencyMs = ms,
            ewmaMs = ms,
            lastPingAt = System.currentTimeMillis() - 4 * 60_000L,
            isSelected = selected,
            role = role,
            roleFromProvider = true,
            sortOrder = id.toInt()
        )

        /**
         * Package names from the ready-made sets (so the sets find them), with plain made-up
         * labels and letter icons: no real app's name or artwork.
         */
        fun bypassApps(lang: String): List<Pair<String, String>> {
            val ru = lang == "ru"
            fun n(r: String, e: String) = if (ru) r else e
            return listOf(
                "ru.sberbankmobile" to n("Банк Онлайн", "Online Bank"),
                "com.idamob.tinkoff.android" to n("Мой банк", "My Bank"),
                "ru.vtb24.mobilebanking.android" to n("Банк24", "Bank24"),
                "ru.rostel" to n("Портал услуг", "Public Services"),
                "ru.fns.lkfl" to n("Налоги", "Taxes"),
                "ru.ozon.app.android" to n("Маркетплейс", "Marketplace"),
                "com.avito.android" to n("Объявления", "Classifieds"),
                "ru.vkusvill" to n("Доставка продуктов", "Groceries"),
                "ru.mos.app" to n("Мой город", "My City")
            )
        }

        fun otherApps(lang: String): List<Pair<String, String>> {
            val ru = lang == "ru"
            fun n(r: String, e: String) = if (ru) r else e
            return listOf(
                "org.example.browser" to n("Браузер", "Browser"),
                "org.example.notes" to n("Заметки", "Notes"),
                "org.example.music" to n("Музыка", "Music"),
                "org.example.mail" to n("Почта", "Mail"),
                "org.example.weather" to n("Погода", "Weather"),
                "org.example.photos" to n("Фото", "Photos")
            )
        }

        val ICON_COLORS = listOf(
            0xFF3E7D5E.toInt(), 0xFF5C6B7A.toInt(), 0xFF2F6FA3.toInt(), 0xFFA0603A.toInt(),
            0xFF6B5A8E.toInt(), 0xFF8C7A3C.toInt(), 0xFF4F7F86.toInt(), 0xFF9A4E4E.toInt()
        )

        /** Megabytes through the VPN per day, oldest first, today last. */
        val DAILY_MB = listOf(
            820, 1_340, 960, 410, 2_250, 3_120, 1_180, 870, 1_420, 1_060, 520, 2_480, 2_940, 1_310,
            900, 1_280, 1_010, 450, 2_030, 2_870, 1_240, 780, 1_510, 1_190, 610, 2_360, 3_050, 1_420,
            860, 1_300, 1_120, 380, 2_210, 2_760, 1_160, 920, 1_380, 1_050, 560, 2_190, 2_640, 1_340,
            790, 1_210, 1_090, 470, 1_980, 2_910, 1_080, 840, 1_460, 990, 640, 2_300, 2_820, 1_270,
            880, 1_350, 1_040, 520, 2_120, 2_700, 1_150, 940, 1_390, 1_110, 610, 1_870
        )
    }
}

/** The listing in Russian. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "ru-rRU-w392dp-h665dp-xhdpi")
class StoreShotsRuTest : StoreShots("ru")

/** The listing in English: the app's default strings. */
@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS-w392dp-h665dp-xhdpi")
class StoreShotsEnTest : StoreShots("en")
