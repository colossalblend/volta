package com.ars3nb.volta.ui.screens

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.key
import com.ars3nb.volta.core.ping.SweepBoard
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.TABULAR
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import com.ars3nb.volta.ui.tv.focusRing
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.ars3nb.volta.R
import com.ars3nb.volta.core.balancer.GuardPolicy
import com.ars3nb.volta.core.locale.UiText
import java.text.Collator
import java.util.Locale
import com.ars3nb.volta.ui.components.GeminiSheetRow
import com.ars3nb.volta.ui.components.SlidingChoice
import com.ars3nb.volta.ui.components.SwitchRow
import com.ars3nb.volta.ui.components.VoltaChip
import com.ars3nb.volta.ui.components.VoltaSearchField
import com.ars3nb.volta.ui.components.subscriptionColor
import com.ars3nb.volta.ui.theme.VoltaTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.ui.components.barColors
import com.ars3nb.volta.ui.components.CountryCard
import com.ars3nb.volta.ui.components.countryName
import com.ars3nb.volta.ui.components.serversCount
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ars3nb.volta.core.share.SharePayload
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.ServerSort
import com.ars3nb.volta.data.model.matches
import com.ars3nb.volta.ui.GeminiViewModel
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.collectQuietState
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.ServerRow
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.components.shownSubscriptionName
import com.ars3nb.volta.ui.share.BleSendSheet
import com.ars3nb.volta.ui.share.rememberBleShareAvailable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    vm: VoltaViewModel,
    onOpenImport: () -> Unit
) {
    val state by vm.collectQuietState()
    // The list as it was left: read from the settings before anything is drawn, so it
    // never shows unfiltered first and then jumps to the saved filters.
    val savedView by vm.serversView.collectAsStateWithLifecycle()
    val view = savedView ?: run {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
        return
    }
    val pool = view.pool
    val query = view.query
    val sort = view.sort
    val byCountry = view.byCountry
    val favoritesOnly = view.favoritesOnly
    // A subscription filter outlives its subscription only until the list knows it's gone.
    val subscriptionFilter = view.subscriptionId?.takeIf { id ->
        state.subscriptions.isEmpty() || (state.subscriptions.size > 1 && state.subscriptions.any { it.id == id })
    }
    // null until the user folds something: then only the current server's country is open.
    val openCountries = view.openCountries
    val runningScope by vm.runningScope.collectAsStateWithLifecycle()
    val hideBalancers by vm.hideBalancers.collectAsStateWithLifecycle()
    // Only whether a sweep runs: its progress is read by the strip and the rows alone, so
    // an answer redraws one row and the counter, not this whole screen.
    val sweeping by vm.sweepRunning.collectAsStateWithLifecycle()
    val gemini = viewModel<GeminiViewModel>()
    var sortMenu by remember { mutableStateOf(false) }
    var sheetServer by remember { mutableStateOf<ServerEntity?>(null) }
    val bluetooth = rememberBleShareAvailable()
    // The link being sent over Bluetooth, if any: remembered, never saved state.
    var bleSend by remember { mutableStateOf<String?>(null) }

    val subsById = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }

    // "Ping all" writes every result as it lands, and each write is a new list. What a
    // server can dial and which country it is in don't change with its numbers, so those
    // are worked out again only when something else about the servers changed.
    val shapes = remember(state.servers) { state.servers.map(::withoutMeasurements) }

    // canDial builds JSON, so cache the verdict for the whole list.
    val supported = remember(shapes) {
        state.servers.associate { it.id to ConfigBuilder.canDial(it) }
    }

    val balancerCount = remember(state.servers) { state.servers.count { it.role == ServerRole.AUTO } }
    // No LTE server anywhere: the LTE choices fold away, as on the home screen.
    val hasLte = remember(state.servers) { GuardPolicy.hasLte(state.servers) }

    val countryOf = remember(shapes) {
        state.servers.associate { it.id to Countries.of(it.remark) }
    }
    // Shown, searched and sorted in the app's language.
    val resources = LocalContext.current.resources
    val locale: Locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val countryNames = remember(countryOf, resources, locale) {
        countryOf.values.map { it.code }.distinct().associateWith { Countries.name(resources, it) }
    }

    val visible = remember(
        state.servers, pool, query, sort, favoritesOnly, supported, subscriptionFilter, hideBalancers,
        countryNames
    ) {
        state.servers
            .filter { pool.matches(it.role) }
            // Provider balancers stay out of the list unless their own chip is open.
            .filter { !(hideBalancers && pool != ServerPool.AUTO && it.role == ServerRole.AUTO) }
            .filter { subscriptionFilter == null || it.subscriptionId == subscriptionFilter }
            .filter { !favoritesOnly || it.isFavorite }
            .filter {
                matchesQuery(
                    it,
                    query,
                    subsById[it.subscriptionId]?.name.orEmpty(),
                    countryOf[it.id]?.let { c -> countryNames[c.code] }.orEmpty()
                )
            }
            .sortedWith(
                when (sort) {
                    ServerSort.BEST -> compareBy<ServerEntity> { supported[it.id] == false }
                        .thenBy { it.failStreak > 0 }
                        .thenBy { measuredMs(it).let { ms -> if (ms < 0) Long.MAX_VALUE else ms } }

                    ServerSort.NAME -> compareBy { it.remark.lowercase() }
                    ServerSort.SUBSCRIPTION -> compareBy<ServerEntity> { it.subscriptionId ?: 0 }
                        .thenBy { it.sortOrder }
                }
            )
    }

    // All subscriptions together, one card per country, alphabetical; unnamed last.
    val countries = remember(visible, countryNames, locale) {
        val collator = Collator.getInstance(locale)
        visible.groupBy { countryOf[it.id] ?: Countries.UNKNOWN }
            .toList()
            .sortedWith(
                compareBy<Pair<Countries.Country, List<ServerEntity>>> {
                    it.first.code == Countries.UNKNOWN_CODE
                }.thenBy(collator) { countryNames[it.first.code] ?: it.first.code }
            )
    }
    val currentCountry = state.selectedServer?.let { countryOf[it.id]?.code }
    val open = openCountries ?: setOfNotNull(currentCountry)
    // How old the stored figures are is judged against the time the list last changed.
    val now = remember(state.servers) { System.currentTimeMillis() }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    colors = barColors(),
                    title = { Text(stringResource(R.string.servers_title), fontWeight = FontWeight.SemiBold) },
                    actions = {
                        // One button: starts the check, and stops it while it runs.
                        IconButton(
                            onClick = { if (sweeping) vm.cancelPingAll() else vm.pingAll(pool = pool) },
                            enabled = sweeping || (!state.busy && runningScope == null && state.servers.isNotEmpty())
                        ) {
                            AnimatedContent(
                                targetState = sweeping,
                                transitionSpec = {
                                    (scaleIn(Motion.press(), initialScale = 0.7f) + fadeIn()) togetherWith fadeOut()
                                },
                                label = "pingAll"
                            ) { running ->
                                if (running) {
                                    Icon(Icons.Rounded.Close, stringResource(R.string.servers_check_stop))
                                } else {
                                    Icon(Icons.Rounded.Speed, stringResource(R.string.servers_ping_all))
                                }
                            }
                        }
                        Box {
                            IconButton(onClick = { sortMenu = true }) {
                                Icon(Icons.Rounded.SwapVert, stringResource(R.string.servers_sort))
                            }
                            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                                ServerSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(option)) },
                                        onClick = {
                                            vm.updateServersView { it.copy(sort = option) }
                                            sortMenu = false
                                        },
                                        trailingIcon = {
                                            if (sort == option) Icon(Icons.Rounded.Check, null)
                                        }
                                    )
                                }
                            }
                        }
                    }
                )
                AnimatedVisibility(
                    visible = sweeping,
                    enter = fadeIn() + expandVertically(Motion.settle()),
                    exit = fadeOut() + shrinkVertically(Motion.settle())
                ) {
                    SweepStrip(vm.sweep, onStop = vm::cancelPingAll)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            // Kept in the view model: back from another tab, the list is where it was.
            state = if (byCountry) vm.serversByCountryList else vm.serversFlatList,
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionHeader(stringResource(R.string.servers_best_in_group)) }
            item {
                BestPickPads(
                    running = runningScope,
                    enabled = state.servers.isNotEmpty() && !state.busy && !sweeping,
                    hasLte = hasLte,
                    onRun = vm::runBalancer
                )
            }

            item {
                VoltaSearchField(
                    value = query,
                    onValueChange = vm::setQuery,
                    placeholder = stringResource(R.string.servers_search_hint)
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ServerPool.entries.forEach { option ->
                        if (option == ServerPool.AUTO && balancerCount == 0) return@forEach
                        if (option == ServerPool.BYPASS_WHITELIST && !hasLte) return@forEach
                        VoltaChip(
                            text = if (option == ServerPool.AUTO) "${poolLabel(option)} $balancerCount"
                            else poolLabel(option),
                            selected = pool == option,
                            onClick = { vm.setServerPool(option) },
                            accent = poolAccent(option)
                        )
                    }
                    VoltaChip(
                        text = stringResource(R.string.servers_favorites),
                        selected = favoritesOnly,
                        onClick = { vm.updateServersView { it.copy(favoritesOnly = !favoritesOnly) } },
                        icon = Icons.Rounded.Star
                    )
                }
            }

            if (state.subscriptions.size > 1) {
                item {
                    // All subscriptions form one list; these chips only narrow it down.
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        VoltaChip(
                            text = stringResource(R.string.servers_all_subscriptions),
                            selected = subscriptionFilter == null,
                            onClick = { vm.updateServersView { it.copy(subscriptionId = null) } }
                        )
                        state.subscriptions.forEach { sub ->
                            VoltaChip(
                                text = shownSubscriptionName(sub),
                                selected = subscriptionFilter == sub.id,
                                onClick = {
                                    val next = if (subscriptionFilter == sub.id) null else sub.id
                                    vm.updateServersView { it.copy(subscriptionId = next) }
                                },
                                accent = subscriptionColor(sub)
                            )
                        }
                    }
                }
            }

            if (pool == ServerPool.AUTO && balancerCount > 0) {
                item {
                    VoltaCard(contentPadding = 10) {
                        SwitchRow(
                            icon = Icons.Rounded.VisibilityOff,
                            tint = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.servers_hide_balancers),
                            subtitle = stringResource(R.string.servers_hide_balancers_text),
                            checked = hideBalancers,
                            onChecked = vm::setHideBalancers
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (byCountry) {
                            pluralStringResource(
                                R.plurals.servers_count_in_countries,
                                countries.size,
                                serversCount(visible.size),
                                countries.size
                            )
                        } else {
                            serversCount(visible.size)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    val byCountryLabel = stringResource(R.string.servers_view_countries)
                    val listLabel = stringResource(R.string.servers_view_list)
                    SlidingChoice(
                        options = listOf(true, false),
                        selected = byCountry,
                        onSelect = { choice -> vm.updateServersView { it.copy(byCountry = choice) } },
                        label = { if (it) byCountryLabel else listLabel },
                        height = 36.dp,
                        modifier = Modifier.width(170.dp)
                    )
                }
            }

            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Storage,
                        title = stringResource(
                            if (state.servers.isEmpty()) R.string.servers_empty_title
                            else R.string.servers_nothing_found
                        ),
                        subtitle = stringResource(
                            if (state.servers.isEmpty()) R.string.servers_empty_text
                            else R.string.servers_nothing_found_text
                        ),
                        action = emptyStateAction(state.servers.isEmpty(), onOpenImport)
                    )
                }
            }

            if (byCountry) {
                items(countries, key = { "country-${it.first.code}" }) { (country, servers) ->
                    // While searching every match is shown; otherwise only the open ones.
                    val expanded = query.isNotBlank() || country.code in open
                    CountryCard(
                        country = country,
                        name = countryNames[country.code] ?: countryName(country),
                        count = servers.size,
                        bestMs = servers.map(::measuredMs).filter { it >= 0 }.minOrNull() ?: -1,
                        expanded = expanded,
                        current = country.code == currentCountry,
                        onToggle = {
                            val next = if (country.code in open) open - country.code else open + country.code
                            vm.updateServersView { it.copy(openCountries = next) }
                        }
                    ) {
                        servers.forEach { server ->
                            key(server.id) {
                                LiveServerRow(
                                    sweep = vm.sweep,
                                    server = server,
                                    selected = state.selectedServer?.id == server.id,
                                    subscriptionName = subsById[server.subscriptionId]?.let { shownSubscriptionName(it) }.orEmpty(),
                                    supported = supported[server.id] != false,
                                    onClick = { vm.selectServer(server.id) },
                                    onLongClick = { sheetServer = server },
                                    now = now,
                                    inGroup = true
                                )
                            }
                        }
                    }
                }
            } else {
                items(visible, key = { it.id }) { server ->
                    LiveServerRow(
                        sweep = vm.sweep,
                        server = server,
                        selected = state.selectedServer?.id == server.id,
                        subscriptionName = subsById[server.subscriptionId]?.let { shownSubscriptionName(it) }.orEmpty(),
                        supported = supported[server.id] != false,
                        onClick = { vm.selectServer(server.id) },
                        onLongClick = { sheetServer = server },
                        now = now,
                        // The order changes once, when a sweep's results are in: rows glide there.
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    sheetServer?.let { server ->
        val payload = SharePayload.server(server.rawLink, server.rawJson)
        val check by vm.serverCheck.collectAsStateWithLifecycle()
        ServerActionsSheet(
            server = server,
            onDismiss = {
                sheetServer = null
                vm.clearServerCheck()
            },
            onPing = { vm.pingServer(server.id) },
            check = check?.takeIf { it.serverId == server.id },
            onToggleFavorite = { vm.toggleFavorite(server.id) },
            onSetRole = { role -> vm.setServerRole(server.id, role) },
            onDelete = if (server.subscriptionId == null) {
                { vm.deleteServer(server.id) }
            } else null,
            onCopied = { vm.showMessage(UiText.of(R.string.servers_link_copied)) },
            onSendBluetooth = if (bluetooth && payload != null) {
                { bleSend = payload }
            } else null,
            geminiRow = { GeminiSheetRow(server, gemini) }
        )
    }
    bleSend?.let { payload -> BleSendSheet(payload, onDismiss = { bleSend = null }) }
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
    onLongClick: () -> Unit,
    now: Long,
    modifier: Modifier = Modifier,
    inGroup: Boolean = false
) {
    val cell = remember(sweep, server.id) { sweep.map { it.cell(server.id) }.distinctUntilChanged() }
    val live by cell.collectAsStateWithLifecycle(initialValue = sweep.value.cell(server.id))
    ServerRow(
        server = server,
        selected = selected,
        subscriptionName = subscriptionName,
        supported = supported,
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        inGroup = inGroup,
        live = live,
        now = now
    )
}

/** «Проверено N из M», a bar, and a way to stop: under the top bar while a sweep runs. */
@Composable
private fun SweepStrip(sweep: StateFlow<SweepBoard>, onStop: () -> Unit) {
    val board by sweep.collectAsStateWithLifecycle()
    val still = rememberReducedMotion()
    val progress by animateFloatAsState(
        board.fraction,
        if (still) snap() else Motion.slide(),
        label = "sweepProgress"
    )
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.servers_check_progress, board.done, board.total),
                style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onStop, modifier = Modifier.focusRing(RoundedCornerShape(20.dp))) {
                Text(stringResource(R.string.servers_check_stop))
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(4.dp).clip(CircleShape)
        )
    }
}

/**
 * The app-side autobalancers as three pads, one per group, in the group's colour.
 * Home+LTE asks the sites first and then measures whichever group fits; Home and LTE
 * go straight to their own servers. Without LTE servers only Home is left.
 */
@Composable
private fun BestPickPads(
    running: BalancerScope?,
    enabled: Boolean,
    hasLte: Boolean,
    onRun: (BalancerScope) -> Unit
) {
    val palette = VoltaTheme.palette
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
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (enabled || busyHere) tint else tint.copy(alpha = 0.4f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            scopeLabel(scope),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    if (busyHere) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                Modifier.size(12.dp),
                                strokeWidth = 1.5.dp,
                                color = tint
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                stringResource(R.string.servers_searching),
                                style = MaterialTheme.typography.labelMedium,
                                color = tint
                            )
                        }
                    } else {
                        Text(
                            stringResource(if (hasLte) R.string.servers_pick else R.string.servers_pick_best),
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

private fun scopeLabel(scope: BalancerScope): String = when (scope) {
    BalancerScope.ALL -> "Home+LTE"
    BalancerScope.NORMAL -> "Home"
    BalancerScope.BYPASS -> "LTE"
}

/** Explicit helper: an `if/else null` producing a composable lambda confuses inference. */
private fun emptyStateAction(
    show: Boolean,
    onOpenImport: () -> Unit
): (@Composable () -> Unit)? = if (!show) null else {
    {
        FilledTonalButton(onClick = onOpenImport, modifier = Modifier.padding(top = 8.dp)) {
            Text(stringResource(R.string.servers_add))
        }
    }
}

/** [server] without what a measurement or a tap changes (equal before and after a ping). */
internal fun withoutMeasurements(server: ServerEntity): ServerEntity = server.copy(
    latencyMs = -1,
    ewmaMs = -1,
    failStreak = 0,
    lastPingAt = 0,
    isFavorite = false,
    isSelected = false
)

/** The last measurement, falling back to the smoothed one; -1 when never measured. */
private fun measuredMs(server: ServerEntity): Long =
    server.latencyMs.takeIf { it >= 0 } ?: server.ewmaMs

private fun matchesQuery(
    server: ServerEntity,
    query: String,
    subName: String,
    countryName: String
): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return server.remark.lowercase().contains(q) ||
        countryName.lowercase().contains(q) ||
        server.address.lowercase().contains(q) ||
        server.protocol.label.lowercase().contains(q) ||
        subName.lowercase().contains(q) ||
        server.tags.lowercase().contains(q)
}

@Composable
private fun poolLabel(pool: ServerPool): String = when (pool) {
    ServerPool.ALL -> stringResource(R.string.servers_pool_all)
    ServerPool.NORMAL -> "Home"
    ServerPool.BYPASS_WHITELIST -> "LTE"
    ServerPool.AUTO -> stringResource(R.string.servers_pool_balancers)
}

@Composable
private fun sortLabel(sort: ServerSort): String = when (sort) {
    ServerSort.BEST -> stringResource(R.string.servers_sort_fastest)
    ServerSort.NAME -> stringResource(R.string.servers_sort_name)
    ServerSort.SUBSCRIPTION -> stringResource(R.string.servers_sort_subscription)
}
