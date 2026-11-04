package com.ars3nb.volta.ui.navigation

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import com.ars3nb.volta.R
import com.ars3nb.volta.ui.tv.ProvideTv
import com.ars3nb.volta.ui.tv.Tv
import com.ars3nb.volta.ui.tv.focusRing
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import kotlin.math.absoluteValue
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.collectMessage
import com.ars3nb.volta.ui.screens.HomeScreen
import com.ars3nb.volta.ui.screens.ImportScreen
import com.ars3nb.volta.ui.screens.PerAppScreen
import com.ars3nb.volta.ui.screens.RoutingScreen
import com.ars3nb.volta.ui.screens.ServersScreen
import com.ars3nb.volta.ui.screens.SettingsScreen
import com.ars3nb.volta.ui.screens.SimpleHomeScreen
import com.ars3nb.volta.ui.screens.SubscriptionsScreen
import com.ars3nb.volta.ui.theme.VoltaTheme

object Routes {
    const val HOME = "home"
    const val SERVERS = "servers"
    const val ROUTING = "routing"
    const val SETTINGS = "settings"
    const val IMPORT = "import"
    const val PER_APP = "per_app"
    const val SUBSCRIPTIONS = "subscriptions"
}

private data class Tab(
    val route: String,
    @StringRes val label: Int,
    val icon: ImageVector,
    val chosenIcon: ImageVector
)

private val TABS = listOf(
    Tab(Routes.HOME, R.string.home_tab_home, Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.SERVERS, R.string.home_tab_servers, Icons.Outlined.Storage, Icons.Rounded.Storage),
    Tab(Routes.SUBSCRIPTIONS, R.string.home_tab_subscriptions, Icons.Outlined.CloudSync, Icons.Rounded.CloudSync),
    Tab(Routes.ROUTING, R.string.home_tab_routing, Icons.Outlined.AltRoute, Icons.Rounded.AltRoute),
    Tab(Routes.SETTINGS, R.string.home_tab_settings, Icons.Outlined.Settings, Icons.Rounded.Settings)
)

@Composable
fun VoltaAppRoot(vm: VoltaViewModel = viewModel()) {
    val design by vm.designTheme.collectAsStateWithLifecycle()
    // Only the message: the whole state changes every two seconds while connected.
    val message by vm.collectMessage()
    val simple by vm.simpleMode.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val tv = remember(context) { Tv.isTv(context) }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBottomBar = current in TABS.map { it.route }

    // One place for user-facing messages, so no screen loses or repeats them.
    // Read in this screen's language when it is shown, not when it was made.
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it.resolve(context))
            vm.clearMessage()
        }
    }

    VoltaTheme(design = design) {
        ProvideTv(tv) {
            Crossfade(targetState = simple, animationSpec = tween(320), label = "simpleMode") { simpleMode ->
                when (simpleMode) {
                    // The setting is still being read: an empty screen, not the wrong one.
                    null -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                    true -> Scaffold(
                        contentWindowInsets = WindowInsets(0, 0, 0, 0),
                        snackbarHost = { SnackbarHost(snackbar) }
                    ) { padding ->
                        SimpleHomeScreen(vm = vm, modifier = Modifier.padding(padding))
                    }
                    false -> if (tv) {
                        TvApp(vm, nav, snackbar, current, showBottomBar)
                    } else {
                        FullApp(vm, nav, snackbar, current, showBottomBar)
                    }
                }
            }
        }
    }
}

/** Switches to a tab, keeping each tab's own back stack and scroll. */
private fun NavHostController.openTab(current: String?, route: String) {
    if (current == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
private fun FullApp(
    vm: VoltaViewModel,
    nav: NavHostController,
    snackbar: SnackbarHostState,
    current: String?,
    showBottomBar: Boolean
) {
    Scaffold(
        // Each screen handles the status bar itself (the home screen draws under it).
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (showBottomBar) {
                VoltaBottomBar(current) { route -> nav.openTab(current, route) }
            }
        }
    ) { padding ->
        VoltaNavHost(vm, nav, Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding))
    }
}

/**
 * The full app on a TV: the tabs as a rail down the left edge, where the remote reaches
 * them with one press to the left, and the screens in a column of readable width instead
 * of stretched across the whole television. A margin keeps everything off the edges a
 * TV may crop.
 */
@Composable
private fun TvApp(
    vm: VoltaViewModel,
    nav: NavHostController,
    snackbar: SnackbarHostState,
    current: String?,
    showRail: Boolean
) {
    Row(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        if (showRail) VoltaRail(current) { route -> nav.openTab(current, route) }
        Scaffold(
            modifier = Modifier.weight(1f),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) }
        ) { padding ->
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                VoltaNavHost(vm, nav, Modifier.widthIn(max = TV_CONTENT_WIDTH).fillMaxSize())
            }
        }
    }
}

/** How wide the screens get on a TV. */
private val TV_CONTENT_WIDTH = 720.dp

@Composable
private fun VoltaNavHost(vm: VoltaViewModel, nav: NavHostController, modifier: Modifier) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        modifier = modifier,
        enterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f) },
        exitTransition = { fadeOut(tween(140)) },
        popEnterTransition = { fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.97f) },
        popExitTransition = { fadeOut(tween(140)) }
    ) {
        composable(Routes.HOME) {
            HomeScreen(
                vm = vm,
                onOpenServers = {
                    nav.navigate(Routes.SERVERS) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onOpenImport = { nav.navigate(Routes.IMPORT) },
                onOpenRouting = {
                    nav.navigate(Routes.ROUTING) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        }
        composable(Routes.SERVERS) {
            ServersScreen(vm = vm, onOpenImport = { nav.navigate(Routes.IMPORT) })
        }
        composable(Routes.SUBSCRIPTIONS) {
            SubscriptionsScreen(vm = vm, onOpenImport = { nav.navigate(Routes.IMPORT) })
        }
        composable(Routes.ROUTING) {
            RoutingScreen(vm = vm, onOpenPerApp = { nav.navigate(Routes.PER_APP) })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(vm = vm)
        }
        composable(
            Routes.PER_APP,
            enterTransition = { slideInVertically(tween(260)) { it / 3 } + fadeIn() },
            popExitTransition = { slideOutVertically(tween(220)) { it / 3 } + fadeOut() }
        ) {
            PerAppScreen(vm = vm, onBack = { nav.popBackStack() })
        }
        composable(
            Routes.IMPORT,
            enterTransition = { slideInVertically(tween(260)) { it / 3 } + fadeIn() },
            popExitTransition = { slideOutVertically(tween(220)) { it / 3 } + fadeOut() }
        ) {
            ImportScreen(vm = vm, onBack = { nav.popBackStack() })
        }
    }
}

/**
 * The tab bar. One copper pill sits behind the chosen tab's icon and slides to the next
 * one, stretching a little on the way like a drop of something heavy; the icon fills in
 * as the pill arrives.
 */
@Composable
internal fun VoltaBottomBar(current: String?, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val still = rememberReducedMotion()
    val index = TABS.indexOfFirst { it.route == current }.coerceAtLeast(0)

    Surface(color = scheme.surface) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(68.dp)
        ) {
            val slot = maxWidth / TABS.size
            val target = slot * index
            val x by animateDpAsState(target, if (still) snap() else Motion.slide(), label = "tabPill")
            val stretch = ((target - x).value.absoluteValue * 0.35f).coerceAtMost(26f).dp
            Box(
                Modifier
                    .offset(x = x + (slot - PILL_WIDTH - stretch) / 2, y = 10.dp)
                    .width(PILL_WIDTH + stretch)
                    .height(32.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.16f))
            )
            Row(Modifier.fillMaxSize()) {
                TABS.forEachIndexed { i, tab ->
                    val chosen = i == index
                    val tint by animateColorAsState(
                        if (chosen) scheme.primary else scheme.onSurfaceVariant,
                        label = "tabTint"
                    )
                    val pop by animateFloatAsState(
                        if (chosen) 1f else 0f,
                        if (still) snap() else Motion.press(),
                        label = "tabPop"
                    )
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            // Only with a keyboard or remote: a tap never gives a tab focus.
                            .focusRing(TabFocusShape)
                            .selectable(
                                selected = chosen,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab
                            ) {
                                if (!chosen) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(tab.route)
                            }
                    ) {
                        Spacer(Modifier.height(14.dp))
                        Icon(
                            if (chosen) tab.chosenIcon else tab.icon,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    val s = 1f + 0.1f * pop * (1f - pop) * 4f
                                    scaleX = s
                                    scaleY = s
                                }
                        )
                        Spacer(Modifier.height(9.dp))
                        Text(
                            stringResource(tab.label),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (chosen) scheme.onSurface else scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

private val PILL_WIDTH = 58.dp
private val TabFocusShape = RoundedCornerShape(18.dp)

/**
 * The tabs on a TV: a rail down the left edge, in the same language as the tab bar
 * (the copper pill behind the chosen icon). Pressing left from a screen lands on the
 * tab that was last used, and the remote's focus is a copper outline round the tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun VoltaRail(current: String?, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val index = TABS.indexOfFirst { it.route == current }.coerceAtLeast(0)
    val chosenTab = remember { FocusRequester() }
    Column(
        Modifier
            .fillMaxHeight()
            .width(RAIL_WIDTH)
            .focusGroup()
            .focusRestorer(chosenTab)
            .selectableGroup(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)
    ) {
        TABS.forEachIndexed { i, tab ->
            val chosen = i == index
            val tint by animateColorAsState(
                if (chosen) scheme.primary else scheme.onSurfaceVariant,
                label = "railTint"
            )
            val pill by animateColorAsState(
                if (chosen) scheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                label = "railPill"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .then(if (chosen) Modifier.focusRequester(chosenTab) else Modifier)
                    .fillMaxWidth()
                    .focusRing(TabFocusShape)
                    .clip(TabFocusShape)
                    .selectable(selected = chosen, role = Role.Tab) { onSelect(tab.route) }
                    .padding(vertical = 10.dp)
            ) {
                Box(
                    Modifier
                        .width(PILL_WIDTH)
                        .height(32.dp)
                        .clip(CircleShape)
                        .background(pill),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (chosen) tab.chosenIcon else tab.icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(tab.label),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (chosen) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

private val RAIL_WIDTH = 96.dp
