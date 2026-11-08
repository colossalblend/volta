package com.ars3nb.volta.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.ars3nb.volta.desktop.ui.theme.ProvideLiteMotion
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import com.ars3nb.volta.desktop.ui.screens.RoutingScreen
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.rounded.AltRoute
import com.ars3nb.volta.desktop.VoltaController
import com.ars3nb.volta.desktop.VoltaUiState
import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.Strings
import com.ars3nb.volta.desktop.ui.screens.HomeActions
import com.ars3nb.volta.desktop.ui.screens.HomeScreen
import com.ars3nb.volta.desktop.ui.screens.ImportScreen
import com.ars3nb.volta.desktop.ui.screens.ImportViewState
import com.ars3nb.volta.desktop.ui.screens.ServersScreen
import com.ars3nb.volta.desktop.ui.screens.ServersViewState
import com.ars3nb.volta.desktop.ui.screens.SettingsScreen
import com.ars3nb.volta.desktop.ui.screens.SubscriptionsScreen
import com.ars3nb.volta.desktop.ui.theme.Motion
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme

enum class Page { HOME, SERVERS, SUBSCRIPTIONS, ROUTING, ADD, SETTINGS }

/** What the app needs from Windows itself, so the screens stay testable. */
interface DesktopBridge {
    fun readClipboard(): String?
    fun writeClipboard(text: String)
    fun pickFile(): String?
    fun openUrl(url: String)
    fun openFolder(dir: java.io.File)

    /** Windows' "Save as" dialog for a backup file; null when cancelled. */
    fun chooseSaveFile(suggestedName: String): java.io.File? = null

    /** Windows' "Open" dialog for a backup file; null when cancelled. */
    fun chooseOpenFile(): java.io.File? = null
}

/** Page and per-page state, kept while the window lives (and when it hides to the tray). */
class AppViewState {
    var page by mutableStateOf(Page.HOME)
    val servers = ServersViewState()
    val import = ImportViewState()
}

/**
 * The whole window: Volta's look in the theme the user picked, a navigation rail where
 * the Android app has its tab bar, and the page in a centred column.
 */
@Composable
fun VoltaDesktopApp(
    controller: VoltaController,
    state: VoltaUiState,
    view: AppViewState,
    bridge: DesktopBridge
) {
    val lang = Lang.resolve(state.settings.language)
    val strings = lang.strings
    val snackbar = remember { SnackbarHostState() }
    val update by controller.updater.state.collectAsState()
    val openFlux by OpenFluxRunner.status.collectAsState()
    // «Lite-оформление» first, so every animation below it already runs on its clock.
    ProvideLiteMotion(lite = state.settings.liteMode) {
    LaunchedEffect(controller) {
        controller.messages.collect { snackbar.showSnackbar(it) }
    }

    VoltaTheme(design = state.settings.designTheme) {
        val scheme = MaterialTheme.colorScheme
        CompositionLocalProvider(
            LocalStrings provides strings,
            LocalLang provides lang,
            LocalLayoutDirection provides if (lang.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            LocalScrollbarStyle provides ScrollbarStyle(
                minimalHeight = 32.dp,
                thickness = 6.dp,
                shape = RoundedCornerShape(3.dp),
                hoverDurationMillis = 250,
                unhoverColor = scheme.onSurfaceVariant.copy(alpha = 0.18f),
                hoverColor = scheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
        ) {
            // The page colour as a Surface, so text without a colour of its own takes the
            // page's ink (Scaffold does this on Android).
            Surface(color = scheme.background, contentColor = scheme.onBackground, modifier = Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                NavRail(
                    current = view.page,
                    vpnState = state.vpnState,
                    strings = strings,
                    onSelect = { view.page = it }
                )
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = view.page,
                        transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.98f)) togetherWith fadeOut(tween(140)) },
                        label = "page"
                    ) { page ->
                        when (page) {
                            Page.HOME -> HomeScreen(
                                state,
                                HomeActions(
                                    onToggle = controller::toggleConnect,
                                    onSelectMode = controller::setConnectMode,
                                    onOpenServers = { view.page = Page.SERVERS },
                                    onOpenImport = { view.page = Page.ADD },
                                    onRecheckNetwork = controller::recheckNetwork,
                                    onLteMissing = { controller.message(strings.lteMissing) },
                                    onInstallUpdate = controller::installUpdate,
                                    onOpenCaptcha = bridge::openUrl,
                                    onCaptchaDone = OpenFluxRunner::cancelCaptcha,
                                    onAutoSubscription = controller::setAutoSubscription
                                ),
                                update = update,
                                openFlux = openFlux,
                                gemini = controller.gemini
                            )
                            Page.SERVERS -> ServersScreen(
                                state, controller, view.servers,
                                onOpenImport = { view.page = Page.ADD },
                                copyToClipboard = bridge::writeClipboard
                            )
                            Page.SUBSCRIPTIONS -> SubscriptionsScreen(
                                state, controller,
                                onOpenImport = { view.page = Page.ADD },
                                copyToClipboard = bridge::writeClipboard,
                                openUrl = bridge::openUrl
                            )
                            Page.ROUTING -> RoutingScreen(state, controller)
                            Page.ADD -> ImportScreen(
                                state, controller, view.import,
                                readClipboard = bridge::readClipboard,
                                pickFile = bridge::pickFile,
                                onDone = { view.page = Page.HOME }
                            )
                            Page.SETTINGS -> SettingsScreen(
                                state, controller,
                                copyToClipboard = bridge::writeClipboard,
                                openUrl = bridge::openUrl,
                                openFolder = bridge::openFolder,
                                bridge = bridge,
                                update = update
                            )
                        }
                    }
                    SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
                }
            }
            }
        }
    }
}
}

private class RailItem(val page: Page, val label: (Strings) -> String, val icon: ImageVector, val chosenIcon: ImageVector)

private val RAIL = listOf(
    RailItem(Page.HOME, { it.navHome }, Icons.Outlined.Home, Icons.Rounded.Home),
    RailItem(Page.SERVERS, { it.navServers }, Icons.Outlined.Storage, Icons.Rounded.Storage),
    RailItem(Page.SUBSCRIPTIONS, { it.navSubscriptions }, Icons.Outlined.CloudSync, Icons.Rounded.CloudSync),
    RailItem(Page.ROUTING, { Res.string(R.string.home_tab_routing) }, Icons.Outlined.AltRoute, Icons.Rounded.AltRoute),
    RailItem(Page.ADD, { it.navAdd }, Icons.Outlined.AddCircleOutline, Icons.Rounded.AddCircle),
    RailItem(Page.SETTINGS, { it.navSettings }, Icons.Outlined.Settings, Icons.Rounded.Settings)
)

private val RAIL_WIDTH = 92.dp
private val ITEM_HEIGHT = 68.dp
private val PILL_WIDTH = 58.dp

/**
 * The Android tab bar turned on its side: one copper pill sits behind the chosen page's
 * icon and slides to the next one, stretching a little on the way; the icon fills in as
 * the pill arrives. At the foot, the connection state as a dot of patina or copper.
 */
@Composable
private fun NavRail(current: Page, vpnState: VpnConnectionState, strings: Strings, onSelect: (Page) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val palette = VoltaTheme.palette
    val index = RAIL.indexOfFirst { it.page == current }.coerceAtLeast(0)
    Surface(color = scheme.surface, modifier = Modifier.width(RAIL_WIDTH).fillMaxHeight()) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(18.dp))
            VoltaMark(Modifier.size(40.dp))
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().height(ITEM_HEIGHT * RAIL.size)) {
                val target = ITEM_HEIGHT * index
                val y by animateDpAsState(target, Motion.slide(), label = "railPill")
                val stretch = ((target - y).value.let { if (it < 0) -it else it } * 0.35f).coerceAtMost(22f).dp
                Box(
                    Modifier
                        .offset(x = (RAIL_WIDTH - PILL_WIDTH) / 2, y = y + 8.dp - stretch / 2)
                        .width(PILL_WIDTH)
                        .height(32.dp + stretch)
                        .clip(CircleShape)
                        .background(scheme.primary.copy(alpha = 0.16f))
                )
                Column {
                    RAIL.forEachIndexed { i, item ->
                        val chosen = i == index
                        val tint by animateColorAsState(if (chosen) scheme.primary else scheme.onSurfaceVariant, label = "railTint")
                        val pop by animateFloatAsState(if (chosen) 1f else 0f, Motion.press(), label = "railPop")
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .width(RAIL_WIDTH)
                                .height(ITEM_HEIGHT)
                                .selectable(
                                    selected = chosen,
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    role = Role.Tab
                                ) { onSelect(item.page) }
                        ) {
                            Spacer(Modifier.height(12.dp))
                            Icon(
                                if (chosen) item.chosenIcon else item.icon,
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(24.dp).graphicsLayer {
                                    val sc = 1f + 0.1f * pop * (1f - pop) * 4f
                                    scaleX = sc
                                    scaleY = sc
                                }
                            )
                            Spacer(Modifier.height(9.dp))
                            Text(
                                item.label(strings),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (chosen) scheme.onSurface else scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 4.dp)
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val (dot, label) = when (vpnState) {
                VpnConnectionState.CONNECTED -> palette.good to strings.stateConnected
                VpnConnectionState.CONNECTING -> palette.fair to strings.stateConnecting
                VpnConnectionState.DISCONNECTING -> palette.fair to strings.stateDisconnecting
                VpnConnectionState.ERROR -> palette.poor to strings.stateError
                VpnConnectionState.DISCONNECTED -> scheme.onSurfaceVariant.copy(alpha = 0.5f) to strings.stateOff
            }
            val dotColor by animateColorAsState(dot, label = "railDot")
            Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor))
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp)
            )
            Spacer(Modifier.height(18.dp))
        }
    }
}

/** Volta's mark, the folded V of the app icon: cream arm under, copper arm over. */
@Composable
fun VoltaMark(modifier: Modifier = Modifier) {
    // The cream arm would vanish on the light theme: there it takes the ink colour.
    val under = if (VoltaTheme.palette.isDark) Color(0xFFF3EFE9) else Color(0xFF2A2D33)
    Canvas(modifier) {
        val k = size.minDimension / 54f
        fun p(x: Float, y: Float) = Offset((x - 27f) * k + size.width / 2, (y - 27f) * k + size.height / 2)
        val w = 9.5f * k
        drawLine(under, p(10f, 10f), p(27f, 45f), strokeWidth = w, cap = StrokeCap.Round)
        drawLine(
            Brush.linearGradient(listOf(Color(0xFFC96F32), Color(0xFFF2B183)), start = p(27f, 45f), end = p(44f, 10f)),
            p(27f, 45f),
            p(44f, 10f),
            strokeWidth = w,
            cap = StrokeCap.Round
        )
    }
}
