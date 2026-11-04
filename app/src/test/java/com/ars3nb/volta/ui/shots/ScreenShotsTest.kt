package com.ars3nb.volta.ui.shots

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ars3nb.volta.R
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.update.UpdateState
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.IpInfo
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.prefs.DesignTheme
import com.ars3nb.volta.ui.ConnectPhase
import com.ars3nb.volta.ui.PickRow
import com.ars3nb.volta.ui.PickSummary
import com.ars3nb.volta.ui.SpeedHistory
import com.ars3nb.volta.ui.VoltaUiState
import com.ars3nb.volta.ui.components.ConnectionHeadline
import com.ars3nb.volta.ui.components.PowerButton
import com.ars3nb.volta.ui.navigation.Routes
import com.ars3nb.volta.ui.navigation.VoltaBottomBar
import com.ars3nb.volta.ui.navigation.VoltaRail
import com.ars3nb.volta.ui.screens.HomeContent
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.ProvideTv
import java.io.File
import kotlin.math.sin
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of the home screen and frames of the disc's animation, drawn from the real
 * composables. Not a regression test: it only writes PNGs into the folder named by the
 * `volta.shots` property (VOLTA_SHOTS in CI), so a design can be looked at without a phone.
 */
@RunWith(AndroidJUnit4::class)
// In Russian, like the sample servers they show.
@Config(sdk = [35], application = Application::class, qualifiers = "ru-w392dp-h850dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenShotsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out: File = File(System.getProperty("volta.shots") ?: "build/shots").apply { mkdirs() }

    // ------------------------------------------------------------------ home screen

    @Test
    fun homeOff() = home("home-off", base())

    @Test
    fun homeConnecting() = home(
        "home-connecting",
        base().copy(vpnState = VpnConnectionState.CONNECTING),
        phase = ConnectPhase.MEASURING,
        settle = 1_400
    )

    @Test
    fun homeOn() = home("home-on", connected())

    @Test
    fun homeOnDetails() = home("home-on-details", connected(), scrollTo = 2)

    @Test
    fun homeOnLight() = home("home-on-light", connected(), design = DesignTheme.LIGHT)

    @Test
    fun homeError() = home(
        "home-error",
        base().copy(
            vpnState = VpnConnectionState.ERROR,
            error = UiText.Raw("Сервер не ответил за 10 секунд. Попробуйте другой или проверьте подписку.")
        )
    )

    @Test
    fun homeEmpty() = home(
        "home-empty",
        VoltaUiState(networkRegime = NetworkRegime.OPEN, ipInfo = IpInfo(ip = "95.31.18.7", country = "Россия"))
    )

    @Test
    fun homeManual() = home("home-manual", base(), mode = ConnectMode.MANUAL)

    /** The home screen on a 1080p TV: the tabs as a rail, the screen at reading width. */
    @Test
    @Config(qualifiers = "ru-w960dp-h540dp-land-television-xhdpi")
    fun homeTv() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                ProvideTv(true) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        VoltaRail(Routes.HOME) {}
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                                Home(connected(), ConnectPhase.IDLE, ConnectMode.AUTO_HOME_LTE)
                            }
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_500)
        capture("tv-home")
    }

    // ------------------------------------------------------------------ animations

    /** Off, a tap off-centre, connecting, connected with traffic, then off again. */
    @Test
    @Config(qualifiers = "ru-w360dp-h500dp-hdpi")
    fun discFrames() {
        var vpn by mutableStateOf(VpnConnectionState.DISCONNECTED)
        var working by mutableStateOf(false)
        var activity by mutableFloatStateOf(0f)
        var caption by mutableStateOf("")
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        PowerButton(
                            state = vpn,
                            working = working,
                            enabled = true,
                            onClick = {},
                            size = 300.dp,
                            activity = activity
                        )
                        ConnectionHeadline(
                            state = vpn,
                            phaseCaption = caption,
                            serverName = "Нидерланды, Амстердам"
                        )
                    }
                }
            }
        }
        var frame = 0
        fun film(ms: Long) {
            var t = 0L
            while (t < ms) {
                compose.mainClock.advanceTimeBy(FRAME_MS)
                capture("frames/disc-%03d".format(frame++))
                t += FRAME_MS
            }
        }

        compose.mainClock.advanceTimeBy(500)
        film(500)
        // The finger lands right of centre and a little high: the patina starts there.
        compose.onRoot().performTouchInput { click(Offset(width * 0.62f, height * 0.36f)) }
        working = true
        vpn = VpnConnectionState.CONNECTING
        caption = compose.activity.getString(ConnectPhase.MEASURING.caption)
        film(2_000)
        working = false
        caption = ""
        vpn = VpnConnectionState.CONNECTED
        activity = 0.7f
        film(2_400)
        vpn = VpnConnectionState.DISCONNECTING
        activity = 0f
        film(700)
        vpn = VpnConnectionState.DISCONNECTED
        film(700)
    }

    /** The tab bar's pill sliding from the first tab to the fourth. */
    @Test
    @Config(qualifiers = "ru-w392dp-h120dp-xxhdpi")
    fun navFrames() {
        var current by mutableStateOf(Routes.HOME)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = DesignTheme.VOLTA) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    VoltaBottomBar(current) { current = it }
                }
            }
        }
        compose.mainClock.advanceTimeBy(600)
        var frame = 0
        repeat(6) {
            compose.mainClock.advanceTimeBy(FRAME_MS)
            capture("frames/nav-%03d".format(frame++))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.home_tab_routing)).performClick()
        repeat(24) {
            compose.mainClock.advanceTimeBy(FRAME_MS / 2)
            capture("frames/nav-%03d".format(frame++))
        }
        compose.onNodeWithText(compose.activity.getString(R.string.home_tab_servers)).performClick()
        repeat(24) {
            compose.mainClock.advanceTimeBy(FRAME_MS / 2)
            capture("frames/nav-%03d".format(frame++))
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun home(
        name: String,
        state: VoltaUiState,
        phase: ConnectPhase = ConnectPhase.IDLE,
        mode: ConnectMode = ConnectMode.AUTO_HOME_LTE,
        design: DesignTheme = DesignTheme.VOLTA,
        settle: Long = 2_500,
        scrollTo: Int? = null
    ) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            VoltaTheme(design = design) {
                Scaffold(bottomBar = { VoltaBottomBar(Routes.HOME) {} }) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        Home(state, phase, mode)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(settle)
        if (scrollTo != null) {
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(scrollTo)
            compose.mainClock.advanceTimeBy(1_200)
        }
        capture(name)
    }

    @Composable
    private fun Home(state: VoltaUiState, phase: ConnectPhase, mode: ConnectMode) {
        HomeContent(
            state = state,
            mode = mode,
            phase = phase,
            speed = speed(),
            pick = pick(),
            update = UpdateState.Idle,
            openFlux = OpenFluxRunner.Status.Idle,
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

    private fun capture(name: String) {
        val bitmap = runCatching { compose.onRoot().captureToImage().asAndroidBitmap() }
            .getOrElse {
                // Older Robolectric can't copy pixels out of a window: draw the view instead.
                val view = compose.activity.window.decorView
                Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                    view.draw(Canvas(it))
                }
            }
        val file = File(out, "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun base() = VoltaUiState(
        servers = SERVERS,
        subscriptions = listOf(SUBSCRIPTION),
        selectedServer = SERVERS[0],
        networkRegime = NetworkRegime.OPEN,
        ipInfo = IpInfo(ip = "95.31.18.7", country = "Россия")
    )

    private fun connected() = base().copy(
        vpnState = VpnConnectionState.CONNECTED,
        connectedSince = 1_000_000L,
        traffic = TrafficStats(
            uplinkBytes = 48L * 1024 * 1024,
            downlinkBytes = 1_310L * 1024 * 1024,
            uplinkSpeed = 96L * 1024,
            downlinkSpeed = 2_400L * 1024
        ),
        ipInfo = IpInfo(ip = "185.107.56.14", country = "Нидерланды")
    )

    private fun speed(): SpeedHistory {
        var history = SpeedHistory()
        for (i in 0 until SpeedHistory.CAPACITY) {
            val down = 1_600_000 + 1_100_000 * sin(i / 3.2) + 500_000 * sin(i / 1.3)
            val up = 90_000 + 50_000 * sin(i / 2.1)
            history = history.push(down.toLong(), up.toLong())
        }
        return history
    }

    private fun pick() = PickSummary(
        reason = UiText.Raw(""),
        rows = listOf(
            PickRow(1, SERVERS[0].remark, 48, 4, 5, chosen = true),
            PickRow(2, SERVERS[1].remark, 61, 7, 5, chosen = false),
            PickRow(3, SERVERS[2].remark, 74, 12, 5, chosen = false)
        ),
        measured = 6,
        alive = 5,
        at = 0
    )

    private companion object {
        const val FRAME_MS = 50L

        val SUBSCRIPTION = SubscriptionEntity(id = 1, name = "Мой VPN", url = "https://example.com/sub")

        val SERVERS = listOf(
            server(1, "🇳🇱 Нидерланды, Амстердам", 48),
            server(2, "🇩🇪 Германия, Франкфурт", 61),
            server(3, "🇫🇮 Финляндия, Хельсинки", 74),
            server(4, "🇷🇺 Москва, обход белых списков", 22, ServerRole.BYPASS_WHITELIST),
            server(5, "🇷🇺 Санкт-Петербург, обход", 31, ServerRole.BYPASS_WHITELIST),
            server(6, "🇹🇷 Турция, Стамбул", 96)
        )

        fun server(id: Long, remark: String, ms: Long, role: ServerRole = ServerRole.NORMAL) = ServerEntity(
            id = id,
            subscriptionId = 1,
            remark = remark,
            address = "node$id.example.com",
            port = 443,
            latencyMs = ms,
            ewmaMs = ms,
            role = role
        )
    }
}
