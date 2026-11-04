package com.ars3nb.volta.ui.shots

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.routing.RoutingEngine
import com.ars3nb.volta.data.db.VoltaDatabase
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.RoutingProfileEntity
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.TrafficDayEntity
import com.ars3nb.volta.data.prefs.AppPreferences
import com.ars3nb.volta.data.prefs.DesignTheme
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.navigation.Routes
import com.ars3nb.volta.ui.navigation.VoltaBottomBar
import com.ars3nb.volta.ui.screens.HomeScreen
import com.ars3nb.volta.ui.screens.ImportScreen
import com.ars3nb.volta.ui.screens.PerAppScreen
import com.ars3nb.volta.ui.screens.RoutingScreen
import com.ars3nb.volta.ui.screens.ServersScreen
import com.ars3nb.volta.ui.screens.SettingsScreen
import com.ars3nb.volta.ui.screens.SubscriptionsScreen
import com.ars3nb.volta.ui.theme.VoltaTheme
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper

/**
 * Every main screen, drawn from the real composables with the real view model and
 * database, in English and in German: a picture of each ([LANG]/<screen>.png in the
 * `volta.shots` folder) and the text it shows (<screen>.txt). The sample subscription
 * data is in English, as a provider's would be; what the database holds in Russian is
 * only what Volta itself stored (the built-in sets, older builds' default names), so any
 * Cyrillic on screen is Volta's own words left untranslated, and the test fails on it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = VoltaApp::class, qualifiers = "en-w392dp-h2000dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
open class LocalizedScreensTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val app: VoltaApp get() = RuntimeEnvironment.getApplication() as VoltaApp

    private val lang: String get() = app.resources.configuration.locales[0].language

    private val out: File get() = File(System.getProperty("volta.shots") ?: "build/shots", "l10n/$lang")

    private lateinit var vm: VoltaViewModel

    @Before
    fun seed(): Unit = runBlocking {
        val prefs = AppPreferences(app)
        prefs.setRefreshOnLaunch(false)
        prefs.setPingOnStart(false)
        prefs.setAutoConnect(false)
        prefs.setSimpleMode(false)
        app.repository.ensureSeeded()

        val db = VoltaDatabase.get(app)
        if (db.subscriptionDao().getAll().isEmpty()) {
            val now = System.currentTimeMillis()
            val day = 24L * 60 * 60 * 1000
            // Named by an older build: the default name, in Russian.
            val legacy = db.subscriptionDao().insert(
                SubscriptionEntity(
                    name = "Подписка",
                    url = "https://127.0.0.1:9/a",
                    expireAt = now + 186 * day + day / 2,
                    trafficUsed = 273L * 1024 * 1024,
                    trafficTotal = 50L * 1024 * 1024 * 1024
                )
            )
            val ownRules = db.routingDao().insert(
                RoutingProfileEntity(
                    name = "Подписка · правила",
                    description = "Из подписки «Подписка»",
                    sourceSubscriptionId = legacy,
                    useProviderRules = true
                )
            )
            db.subscriptionDao().getById(legacy)?.let { db.subscriptionDao().update(it.copy(routingProfileId = ownRules)) }
            // A provider's, with the app's default rules: "Russian sites direct".
            val provider = db.subscriptionDao().insert(
                SubscriptionEntity(name = "Nebula VPN", url = "https://127.0.0.1:9/b", expireAt = now + 12 * day)
            )
            db.routingDao().getAll().firstOrNull { it.isBuiltIn && it.name == RoutingEngine.BUILTIN_RU_NAME }
                ?.let { db.routingDao().setActive(it.id) }

            db.serverDao().insertAll(
                listOf(
                    server(provider, "🇳🇱 Netherlands, Amsterdam", 48),
                    server(provider, "🇩🇪 Germany, Frankfurt", 61),
                    server(provider, "🇫🇮 Finland, Helsinki", 74),
                    server(provider, "🇷🇺 Moscow, LTE bypass", 22, ServerRole.BYPASS_WHITELIST),
                    server(legacy, "🇹🇷 Turkey, Istanbul", 96),
                    // A nameless OpenFlux link as older builds stored it.
                    server(legacy, "OpenFlux · Яндекс Документы + напрямую", 130).copy(
                        protocol = ProtocolType.OPENFLUX,
                        network = "yandex+direct"
                    )
                )
            )
            db.serverDao().getAll().firstOrNull()?.let { db.serverDao().selectServer(it.id) }

            val today = LocalDate.now()
            (0L until 40L).forEach { back ->
                db.trafficDao().insertIfMissing(
                    TrafficDayEntity(
                        day = today.minusDays(back).toEpochDay(),
                        uplinkBytes = (3 + back % 5) * 1024L * 1024,
                        downlinkBytes = (40 + back * 7 % 90) * 1024L * 1024
                    )
                )
            }
        }
        vm = VoltaViewModel(app)
    }

    @Test
    fun home() = screen("home", Routes.HOME) { HomeScreen(it, {}, {}, {}) }

    @Test
    fun servers() = screen("servers", Routes.SERVERS) { ServersScreen(it) {} }

    @Test
    fun subscriptions() = screen("subscriptions", Routes.SUBSCRIPTIONS) { SubscriptionsScreen(it) {} }

    @Test
    fun routing() = screen("routing", Routes.ROUTING) { RoutingScreen(it) {} }

    @Test
    fun settings() = screen("settings", Routes.SETTINGS) { SettingsScreen(it) }

    @Test
    fun importScreen() = screen("import", null) { ImportScreen(it) {} }

    @Test
    fun perApp() = screen("per-app", null) { PerAppScreen(it) {} }

    // ------------------------------------------------------------------ helpers

    private fun screen(name: String, tab: String?, content: @Composable (VoltaViewModel) -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                Scaffold(bottomBar = { if (tab != null) VoltaBottomBar(tab) {} }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) { content(vm) }
                }
            }
        }
        settle { vm.uiState.value.let { it.servers.size >= 6 && it.subscriptions.size >= 2 && it.routingProfiles.size >= 3 } }
        out.mkdirs()
        capture(File(out, "$name.png"))
        val texts = texts()
        File(out, "$name.txt").writeText(texts.joinToString("\n"))
        val russian = texts.filter { CYRILLIC.containsMatchIn(it) }
        assertTrue("$lang/$name shows Russian:\n" + russian.joinToString("\n"), russian.isEmpty())
    }

    /** Lets the database, the flows and the first animations run, real time and frames both. */
    private fun settle(ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            step()
            if (ready()) break
        }
        repeat(16) { step() }
    }

    private fun step() {
        compose.mainClock.advanceTimeBy(200)
        ShadowLooper.idleMainLooper()
        Thread.sleep(30)
    }

    /** Everything the screen says: texts, field contents and icon descriptions, every window. */
    private fun texts(): List<String> {
        val found = mutableListOf<String>()
        fun walk(node: SemanticsNode) {
            node.config.getOrNull(SemanticsProperties.Text)?.forEach { found += it.text }
            node.config.getOrNull(SemanticsProperties.EditableText)?.let { found += it.text }
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.let { found += it }
            node.children.forEach(::walk)
        }
        compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        return found.map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun capture(file: File) {
        val bitmap = runCatching {
            compose.onAllNodes(isRoot())[0].captureToImage().asAndroidBitmap()
        }.getOrElse {
            val view = compose.activity.window.decorView
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private companion object {
        val CYRILLIC = Regex("\\p{IsCyrillic}")

        fun server(sub: Long, remark: String, ms: Long, role: ServerRole = ServerRole.NORMAL) = ServerEntity(
            subscriptionId = sub,
            remark = remark,
            address = "node${remark.length}.example.com",
            port = 443,
            uuid = "00000000-0000-0000-0000-000000000000",
            security = "reality",
            network = "tcp",
            latencyMs = ms,
            ewmaMs = ms,
            role = role,
            roleFromProvider = true
        )
    }
}

/** The same screens in German. */
@Config(qualifiers = "de-w392dp-h2000dp-xhdpi")
class LocalizedScreensGermanTest : LocalizedScreensTest()
