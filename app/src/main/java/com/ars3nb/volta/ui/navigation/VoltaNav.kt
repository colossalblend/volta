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

private data class Tab(val route: String, val label: String, val icon: ImageVector, val chosenIcon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Главная", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.SERVERS, "Серверы", Icons.Outlined.Storage, Icons.Rounded.Storage),
    Tab(Routes.SUBSCRIPTIONS, "Подписки", Icons.Outlined.CloudSync, Icons.Rounded.CloudSync),
    Tab(Routes.ROUTING, "Маршруты", Icons.Outlined.AltRoute, Icons.Rounded.AltRoute),
    Tab(Routes.SETTINGS, "Настройки", Icons.Outlined.Settings, Icons.Rounded.Settings)
)

@Composable
fun VoltaAppRoot(vm: VoltaViewModel = viewModel()) {
    val design by vm.designTheme.collectAsStateWithLifecycle()
    val state by vm.uiState.collectAsStateWithLifecycle()
    val simple by vm.simpleMode.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }

    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    val showBottomBar = current in TABS.map { it.route }

    // One place for user-facing messages, so no screen loses or repeats them.
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    VoltaTheme(design = design) {
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
                false -> FullApp(vm, nav, snackbar, current, showBottomBar)
            }
        }
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
                VoltaBottomBar(current) { route ->
                    if (current != route) {
                        nav.navigate(route) {
                            popUpTo(nav.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
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
                            tab.label,
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
