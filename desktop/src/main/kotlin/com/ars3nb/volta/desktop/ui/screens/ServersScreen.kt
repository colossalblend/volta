package com.ars3nb.volta.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.ping.SweepBoard
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.ServerSort
import com.ars3nb.volta.data.model.matches
import com.ars3nb.volta.desktop.VoltaController
import com.ars3nb.volta.desktop.VoltaUiState
import com.ars3nb.volta.desktop.core.WindowsCompat
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.Res
import com.ars3nb.volta.desktop.i18n.Strings
import com.ars3nb.volta.desktop.i18n.stringResource
import com.ars3nb.volta.desktop.ui.LocalLang
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.components.CheckMenuRow
import com.ars3nb.volta.desktop.ui.components.CountryCard
import com.ars3nb.volta.desktop.ui.components.GeminiMenuRow
import com.ars3nb.volta.desktop.ui.components.EmptyState
import com.ars3nb.volta.desktop.ui.components.PageHeader
import com.ars3nb.volta.desktop.ui.components.ScrollPage
import com.ars3nb.volta.desktop.ui.components.SectionHeader
import com.ars3nb.volta.desktop.ui.components.ServerAction
import com.ars3nb.volta.desktop.ui.components.ServerRow
import com.ars3nb.volta.desktop.ui.components.SlidingChoice
import com.ars3nb.volta.desktop.ui.components.SwitchRow
import com.ars3nb.volta.desktop.ui.components.VoltaCard
import com.ars3nb.volta.desktop.ui.components.VoltaChip
import com.ars3nb.volta.desktop.ui.components.VoltaSearchField
import com.ars3nb.volta.desktop.ui.components.countryName
import com.ars3nb.volta.desktop.ui.components.subscriptionColor
import com.ars3nb.volta.desktop.ui.theme.Motion
import com.ars3nb.volta.desktop.ui.theme.TABULAR
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme
import com.ars3nb.volta.desktop.ui.theme.rememberReducedMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Where the server list was scrolled to, one per layout (by country, flat), kept while
 * the window lives: back from another page, the list is where it was left. Its filters,
 * order, grouping, search and open countries are kept in the settings
 * (AppSettings.serversView), so they come back after a restart too.
 */
class ServersViewState {
    val byCountryList = LazyListState()
    val flatList = LazyListState()
}

/**
 * Every server of every subscription, as on Android: the three "best in group" pads,
 * search, group and subscription chips, and the list by country or flat. A click picks
 * a server; a right click (or ⋮) opens its actions.
 */
@Composable
fun ServersScreen(
    state: VoltaUiState,
    controller: VoltaController,
    view: ServersViewState,
    onOpenImport: () -> Unit,
    copyToClipboard: (String) -> Unit
) {
    val s = LocalStrings.current
    val lang = LocalLang.current
    val saved = state.settings.serversView
    val pool = saved.pool
    // The search box is the only one to change the query: it keeps its own text, so typing
    // never waits for the settings to come back round (they are saved as it goes).
    var typed by remember { mutableStateOf(saved.query) }
    val query = typed
    val sort = saved.sort
    val byCountry = saved.byCountry
    val favoritesOnly = saved.favoritesOnly
    // A subscription filter outlives its subscription only until the list knows it's gone.
    val subscriptionFilter = saved.subscriptionId?.takeIf { id ->
        state.subscriptions.isEmpty() || (state.subscriptions.size > 1 && state.subscriptions.any { it.id == id })
    }
    val subsById = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }
    val supported = remember(state.servers) {
        state.servers.associate { it.id to ConfigBuilder.canDial(WindowsCompat.adapt(it)) }
    }
    val balancerCount = remember(state.servers) { state.servers.count { it.role == ServerRole.AUTO } }
    val hasLte = remember(state.servers) { state.servers.any { it.role == ServerRole.BYPASS_WHITELIST } }
    val codeOf = remember(state.servers) { state.servers.associate { it.id to Countries.of(it.remark).code } }
    val hideBalancers = state.settings.hideBalancers
    var sortMenu by remember { mutableStateOf(false) }
    // Only whether a sweep runs: its progress is read by the strip and the rows alone, so
    // an answer redraws one row and the counter, not this whole page.
    val sweepRunning = remember(controller) { controller.sweep.map { it.running }.distinctUntilChanged() }
    val sweeping by sweepRunning.collectAsState(initial = controller.sweep.value.running)
    // How old the stored figures are is judged against the time the list last changed.
    val now = remember(state.servers) { System.currentTimeMillis() }

    val visible = remember(state.servers, pool, query, sort, favoritesOnly, supported, subscriptionFilter, hideBalancers, lang) {
        state.servers
            .filter { pool.matches(it.role) }
            .filter { !(hideBalancers && pool != ServerPool.AUTO && it.role == ServerRole.AUTO) }
            .filter { subscriptionFilter == null || it.subscriptionId == subscriptionFilter }
            .filter { !favoritesOnly || it.isFavorite }
            .filter {
                matchesQuery(it, query, subsById[it.subscriptionId]?.name.orEmpty(), countryName(codeOf[it.id] ?: Countries.UNKNOWN_CODE, lang, s))
            }
            .sortedWith(
                when (sort) {
                    ServerSort.BEST -> compareBy<ServerEntity> { supported[it.id] == false }
                        .thenBy { it.failStreak > 0 }
                        .thenBy { measuredMs(it).let { ms -> if (ms < 0) Long.MAX_VALUE else ms } }
                    ServerSort.NAME -> compareBy { it.remark.lowercase() }
                    ServerSort.SUBSCRIPTION -> compareBy<ServerEntity> { it.subscriptionId ?: 0 }.thenBy { it.sortOrder }
                }
            )
    }
    val countries = remember(visible, lang) {
        visible.groupBy { codeOf[it.id] ?: Countries.UNKNOWN_CODE }
            .toList()
            .map { (code, list) -> Triple(code, countryName(code, lang, s), list) }
            .sortedWith(compareBy<Triple<String, String, List<ServerEntity>>> { it.first == Countries.UNKNOWN_CODE }.thenBy { it.second })
    }
    val currentCode = state.selectedServer?.let { codeOf[it.id] }
    // null until the user folds something: then only the current server's country is open.
    val open = saved.openCountries ?: setOfNotNull(currentCode)
    val running = state.runtime.runningScope
    val liveId = state.runtime.connectedServerId.takeIf { state.isConnected }

    fun actionsFor(server: ServerEntity): List<ServerAction> = buildList {
        add(ServerAction(s.actionConnect, Icons.Rounded.Bolt) { controller.connectTo(server.id) })
        add(
            ServerAction(s.actionPing, Icons.Rounded.Speed, content = {
                CheckMenuRow(state.runtime.serverCheck?.takeIf { it.serverId == server.id }) { controller.pingServer(server.id) }
            })
        )
        add(
            if (server.isFavorite) ServerAction(s.actionUnfavorite, Icons.Rounded.StarBorder) { controller.toggleFavorite(server.id) }
            else ServerAction(s.actionFavorite, Icons.Rounded.Star) { controller.toggleFavorite(server.id) }
        )
        if (server.role != ServerRole.NORMAL) add(ServerAction(s.actionGroupHome, Icons.Rounded.Wifi) { controller.setServerRole(server.id, ServerRole.NORMAL) })
        if (server.role != ServerRole.BYPASS_WHITELIST) add(ServerAction(s.actionGroupLte, Icons.Rounded.SignalCellularAlt) { controller.setServerRole(server.id, ServerRole.BYPASS_WHITELIST) })
        if (server.role != ServerRole.AUTO) add(ServerAction(s.actionGroupBalancer, Icons.Rounded.Tune) { controller.setServerRole(server.id, ServerRole.AUTO) })
        val sub = server.subscriptionId?.let { subsById[it] }
        if (server.rawLink.isNotBlank() && sub?.urlHidden != true) {
            add(ServerAction(s.actionCopyLink, Icons.Rounded.ContentCopy) { copyToClipboard(server.rawLink); controller.message(s.linkCopied) })
        }
        // «Проверить Gemini», its verdict shown in the menu (as in Android's sheet).
        add(
            ServerAction(Res.string(R.string.gemini_check_action), content = {
                val route = remember(server, liveId) { controller.geminiRoute(server) }
                GeminiMenuRow(server, controller.gemini, route)
            })
        )
        if (server.subscriptionId == null) add(ServerAction(s.actionDelete, Icons.Rounded.Delete, danger = true) { controller.deleteServer(server.id) })
    }

    @Composable
    fun row(server: ServerEntity, modifier: Modifier, inGroup: Boolean) {
        LiveServerRow(
            sweep = controller.sweep,
            server = server,
            selected = state.selectedServer?.id == server.id,
            subscriptionName = subsById[server.subscriptionId]?.name.orEmpty(),
            supported = supported[server.id] != false,
            onClick = { controller.selectServer(server.id) },
            actions = { actionsFor(server) },
            onMenuClosed = {
                if (state.runtime.serverCheck?.serverId == server.id) controller.clearServerCheck()
            },
            now = now,
            modifier = modifier,
            inGroup = inGroup
        )
    }

    // Kept in the page state: back from another page, the list is where it was.
    ScrollPage(state = if (byCountry) view.byCountryList else view.flatList) {
        item(key = "header") {
            PageHeader(s.navServers) {
                // One button: starts the check, and stops it while it runs.
                IconButton(
                    onClick = { if (sweeping) controller.cancelPingAll() else controller.pingAll(pool) },
                    enabled = sweeping || (!state.busy && running == null && state.servers.isNotEmpty())
                ) {
                    AnimatedContent(
                        targetState = sweeping,
                        transitionSpec = { (scaleIn(Motion.press(), initialScale = 0.7f) + fadeIn()) togetherWith fadeOut() },
                        label = "pingAll"
                    ) { busyNow ->
                        if (busyNow) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.servers_check_stop))
                        } else {
                            Icon(Icons.Rounded.Speed, s.pingAll)
                        }
                    }
                }
                Box {
                    IconButton(onClick = { sortMenu = true }) { Icon(Icons.Rounded.SwapVert, s.sort) }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        ServerSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(sortLabel(option, s)) },
                                onClick = {
                                    controller.updateServersView { it.copy(sort = option) }
                                    sortMenu = false
                                },
                                trailingIcon = { if (sort == option) Icon(Icons.Rounded.Check, null) }
                            )
                        }
                    }
                }
            }
        }
        item(key = "sweep") {
            AnimatedVisibility(
                visible = sweeping,
                enter = fadeIn() + expandVertically(Motion.settle()),
                exit = fadeOut() + shrinkVertically(Motion.settle())
            ) {
                SweepStrip(controller.sweep, onStop = controller::cancelPingAll)
            }
        }
        item(key = "best") { SectionHeader(s.bestInGroup) }
        item(key = "pads") {
            BestPickPads(
                running = running,
                enabled = state.servers.isNotEmpty() && !state.busy && !sweeping,
                hasLte = hasLte,
                onRun = controller::runBalancer
            )
        }
        item(key = "search") {
            VoltaSearchField(
                value = query,
                onValueChange = { text ->
                    typed = text
                    controller.updateServersView { it.copy(query = text) }
                },
                placeholder = s.searchPlaceholder
            )
        }
        item(key = "chips") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ServerPool.entries.forEach { option ->
                    if (option == ServerPool.AUTO && balancerCount == 0) return@forEach
                    if (option == ServerPool.BYPASS_WHITELIST && !hasLte) return@forEach
                    VoltaChip(
                        text = if (option == ServerPool.AUTO) "${poolLabel(option, s)} $balancerCount" else poolLabel(option, s),
                        selected = pool == option,
                        onClick = { controller.updateServersView { it.copy(pool = option) } },
                        accent = poolAccent(option)
                    )
                }
                VoltaChip(
                    text = s.favorites,
                    selected = favoritesOnly,
                    onClick = { controller.updateServersView { it.copy(favoritesOnly = !favoritesOnly) } },
                    icon = Icons.Rounded.Star
                )
            }
        }
        if (state.subscriptions.size > 1) {
            item(key = "subs") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VoltaChip(
                        text = s.allSubscriptions,
                        selected = subscriptionFilter == null,
                        onClick = { controller.updateServersView { it.copy(subscriptionId = null) } }
                    )
                    state.subscriptions.forEach { sub ->
                        VoltaChip(
                            text = sub.name,
                            selected = subscriptionFilter == sub.id,
                            onClick = {
                                val next = if (subscriptionFilter == sub.id) null else sub.id
                                controller.updateServersView { it.copy(subscriptionId = next) }
                            },
                            accent = subscriptionColor(sub)
                        )
                    }
                }
            }
        }
        if (pool == ServerPool.AUTO && balancerCount > 0) {
            item(key = "hide") {
                VoltaCard(contentPadding = 10) {
                    SwitchRow(
                        icon = Icons.Rounded.VisibilityOff,
                        tint = MaterialTheme.colorScheme.primary,
                        title = s.hideBalancers,
                        subtitle = s.hideBalancersHint,
                        checked = hideBalancers,
                        onChecked = controller::setHideBalancers
                    )
                }
            }
        }
        item(key = "count") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (byCountry) s.serversAndCountries(visible.size, countries.size) else s.servers(visible.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (visible.isNotEmpty()) {
                        Text(s.actionsHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SlidingChoice(
                    options = listOf(true, false),
                    selected = byCountry,
                    onSelect = { choice -> controller.updateServersView { it.copy(byCountry = choice) } },
                    label = { if (it) s.viewCountries else s.viewList },
                    height = 36.dp,
                    modifier = Modifier.width(190.dp)
                )
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                // Typed on purpose: an if/else producing a composable lambda confuses inference.
                val emptyAction: (@Composable () -> Unit)? = if (state.servers.isEmpty()) {
                    { FilledTonalButton(onClick = onOpenImport, modifier = Modifier.padding(top = 8.dp)) { Text(s.add) } }
                } else {
                    null
                }
                EmptyState(
                    icon = Icons.Rounded.Storage,
                    title = if (state.servers.isEmpty()) s.noServersYet else s.nothingFound,
                    subtitle = if (state.servers.isEmpty()) s.addSubOrLink else s.changeFilter,
                    action = emptyAction
                )
            }
        }
        if (byCountry) {
            items(countries, key = { "country-${it.first}" }) { (code, name, servers) ->
                val expanded = query.isNotBlank() || code in open
                CountryCard(
                    code = code,
                    name = name,
                    count = servers.size,
                    bestMs = servers.map(::measuredMs).filter { it >= 0 }.minOrNull() ?: -1,
                    expanded = expanded,
                    current = code == currentCode,
                    onToggle = {
                        val next = if (code in open) open - code else open + code
                        controller.updateServersView { it.copy(openCountries = next) }
                    }
                ) {
                    servers.forEach { server ->
                        key(server.id) { row(server, Modifier, inGroup = true) }
                    }
                }
            }
        } else {
            items(visible, key = { it.id }) { server ->
                // The order changes once, when a sweep's results are in: rows glide there.
                row(server, Modifier.animateItem(), inGroup = false)
            }
        }
    }
}

/**
 * A server row that follows its own entry of a running sweep: an answer redraws this
 * row only. Outside a sweep it is the plain row with the stored figure.
 */
@Composable
private fun LiveServerRow(
    sweep: StateFlow<SweepBoard>,
    server: ServerEntity,
    selected: Boolean,
    subscriptionName: String,
    supported: Boolean,
    onClick: () -> Unit,
    actions: () -> List<ServerAction>,
    onMenuClosed: () -> Unit,
    now: Long,
    modifier: Modifier = Modifier,
    inGroup: Boolean = false
) {
    val cell = remember(sweep, server.id) { sweep.map { it.cell(server.id) }.distinctUntilChanged() }
    val live by cell.collectAsState(initial = sweep.value.cell(server.id))
    ServerRow(
        server = server,
        selected = selected,
        subscriptionName = subscriptionName,
        supported = supported,
        onClick = onClick,
        actions = actions,
        modifier = modifier,
        inGroup = inGroup,
        live = live,
        now = now,
        onMenuClosed = onMenuClosed
    )
}

/** «Проверено N из M», a bar, and a way to stop: at the top of the list while a sweep runs. */
@Composable
private fun SweepStrip(sweep: StateFlow<SweepBoard>, onStop: () -> Unit) {
    val board by sweep.collectAsState()
    val still = rememberReducedMotion()
    val progress by animateFloatAsState(board.fraction, if (still) snap() else Motion.slide(), label = "sweepProgress")
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.servers_check_progress, board.done, board.total),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop) { Text(stringResource(R.string.servers_check_stop)) }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape)
        )
    }
}

/**
 * The app's autobalancers as three pads, one per group, in the group's colour.
 * Home+LTE asks the sites first and then measures whichever group fits.
 */
@Composable
private fun BestPickPads(running: BalancerScope?, enabled: Boolean, hasLte: Boolean, onRun: (BalancerScope) -> Unit) {
    val palette = VoltaTheme.palette
    val s = LocalStrings.current
    val scopes = if (hasLte) BalancerScope.entries else listOf(BalancerScope.NORMAL)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        scopes.forEach { scope ->
            val tint = when (scope) {
                BalancerScope.ALL -> MaterialTheme.colorScheme.primary
                BalancerScope.NORMAL -> palette.home
                BalancerScope.BYPASS -> palette.lte
            }
            val busyHere = running == scope
            Surface(
                onClick = { onRun(scope) },
                enabled = enabled && running == null,
                shape = RoundedCornerShape(18.dp),
                color = if (busyHere) tint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                modifier = Modifier.weight(1f)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (enabled || busyHere) tint else tint.copy(alpha = 0.4f)))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            when (scope) {
                                BalancerScope.ALL -> s.modeHomeLte
                                BalancerScope.NORMAL -> s.modeHome
                                BalancerScope.BYPASS -> s.modeLte
                            },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (busyHere) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = tint)
                            Spacer(Modifier.width(6.dp))
                            Text(s.searching, style = MaterialTheme.typography.labelMedium, color = tint)
                        }
                    } else {
                        Text(
                            if (hasLte) s.pickFind else s.pickBest,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun poolAccent(pool: ServerPool): Color = when (pool) {
    ServerPool.NORMAL -> VoltaTheme.palette.home
    ServerPool.BYPASS_WHITELIST -> VoltaTheme.palette.lte
    ServerPool.AUTO -> MaterialTheme.colorScheme.onSurfaceVariant
    ServerPool.ALL -> MaterialTheme.colorScheme.primary
}

private fun poolLabel(pool: ServerPool, s: Strings): String = when (pool) {
    ServerPool.ALL -> s.poolAll
    ServerPool.NORMAL -> s.modeHome
    ServerPool.BYPASS_WHITELIST -> s.modeLte
    ServerPool.AUTO -> s.poolBalancers
}

private fun sortLabel(sort: ServerSort, s: Strings): String = when (sort) {
    ServerSort.BEST -> s.sortBest
    ServerSort.NAME -> s.sortName
    ServerSort.SUBSCRIPTION -> s.sortSubscription
}

/** The last measurement, falling back to the smoothed one; -1 when never measured. */
private fun measuredMs(server: ServerEntity): Long = server.latencyMs.takeIf { it >= 0 } ?: server.ewmaMs

private fun matchesQuery(server: ServerEntity, query: String, subName: String, countryName: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return server.remark.lowercase().contains(q) ||
        countryName.lowercase().contains(q) ||
        server.address.lowercase().contains(q) ||
        server.protocol.label.lowercase().contains(q) ||
        subName.lowercase().contains(q) ||
        server.tags.lowercase().contains(q)
}
