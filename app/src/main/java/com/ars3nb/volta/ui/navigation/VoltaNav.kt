package com.ars3nb.volta.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.HOME, "Главная", Icons.Rounded.Home),
    Tab(Routes.SERVERS, "Серверы", Icons.Rounded.Storage),
    Tab(Routes.SUBSCRIPTIONS, "Подписки", Icons.Rounded.CloudSync),
    Tab(Routes.ROUTING, "Правила", Icons.Rounded.Tune),
    Tab(Routes.SETTINGS, "Настройки", Icons.Rounded.Settings)
)

@Composable
fun VoltaAppRoot(vm: VoltaViewModel = viewModel()) {
    val design by vm.designTheme.collectAsStateWithLifecycle()
    val state by vm.uiState.collectAsStateWithLifecycle()
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
        Scaffold(
            // Each screen handles the status bar itself (the home screen draws under it).
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (showBottomBar) {
                    NavigationBar {
                        TABS.forEach { tab ->
                            NavigationBarItem(
                                selected = current == tab.route,
                                onClick = {
                                    if (current != tab.route) {
                                        nav.navigate(tab.route) {
                                            popUpTo(nav.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) }
                            )
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
                composable(Routes.ROUTING) { RoutingScreen(vm = vm) }
                composable(Routes.SETTINGS) {
                    SettingsScreen(vm = vm, onOpenPerApp = { nav.navigate(Routes.PER_APP) })
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
}
