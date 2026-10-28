package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.ui.components.CountryCard
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
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
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.matches
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.ServerRow
import com.ars3nb.volta.ui.components.VoltaCard

private enum class ServerSort { BEST, NAME, SUBSCRIPTION }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    vm: VoltaViewModel,
    onOpenImport: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val pool by vm.serverPool.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val runningScope by vm.runningScope.collectAsStateWithLifecycle()
    val hideBalancers by vm.hideBalancers.collectAsStateWithLifecycle()
    var sort by remember { mutableStateOf(ServerSort.BEST) }
    var sortMenu by remember { mutableStateOf(false) }
    var byCountry by remember { mutableStateOf(true) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sheetServer by remember { mutableStateOf<ServerEntity?>(null) }
    var subscriptionFilter by remember { mutableStateOf<Long?>(null) }
    // null until the user folds something: then only the current server's country is open.
    var openCountries by remember { mutableStateOf<Set<String>?>(null) }

    val subsById = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }

    // canDial builds JSON, so cache the verdict for the whole list.
    val supported = remember(state.servers) {
        state.servers.associate { it.id to ConfigBuilder.canDial(it) }
    }

    val balancerCount = remember(state.servers) { state.servers.count { it.role == ServerRole.AUTO } }

    val countryOf = remember(state.servers) {
        state.servers.associate { it.id to Countries.of(it.remark) }
    }

    val visible = remember(
        state.servers, pool, query, sort, favoritesOnly, supported, subscriptionFilter, hideBalancers
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
                    countryOf[it.id]?.name.orEmpty()
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
    val countries = remember(visible) {
        visible.groupBy { countryOf[it.id] ?: Countries.UNKNOWN }
            .toList()
            .sortedWith(
                compareBy<Pair<Countries.Country, List<ServerEntity>>> {
                    it.first.code == Countries.UNKNOWN_CODE
                }.thenBy { it.first.name }
            )
    }
    val currentCountry = state.selectedServer?.let { countryOf[it.id]?.code }
    val open = openCountries ?: setOfNotNull(currentCountry)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Серверы", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = { vm.pingAll(pool = pool) },
                        enabled = !state.busy && state.servers.isNotEmpty()
                    ) {
                        if (state.busy && runningScope == null) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Rounded.Speed, "Проверить отклик всех")
                        }
                    }
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Rounded.SwapVert, "Сортировка")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            ServerSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(sortLabel(option)) },
                                    onClick = {
                                        sort = option
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
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionHeader("Лучший сервер в группе") }
            item {
                AutobalancerBar(
                    running = runningScope,
                    enabled = state.servers.isNotEmpty() && !state.busy,
                    onRun = vm::runBalancer
                )
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = vm::setQuery,
                    placeholder = { Text("Поиск по названию, адресу, стране") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.setQuery("") }) {
                                Icon(Icons.Rounded.Close, "Очистить")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ServerPool.entries.forEach { option ->
                        if (option == ServerPool.AUTO && balancerCount == 0) return@forEach
                        FilterChip(
                            selected = pool == option,
                            onClick = { vm.setServerPool(option) },
                            label = {
                                Text(
                                    if (option == ServerPool.AUTO) "${poolLabel(option)} · $balancerCount"
                                    else poolLabel(option)
                                )
                            }
                        )
                    }
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text("Избранные") }
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
                        FilterChip(
                            selected = subscriptionFilter == null,
                            onClick = { subscriptionFilter = null },
                            label = { Text("Все подписки") }
                        )
                        state.subscriptions.forEach { sub ->
                            FilterChip(
                                selected = subscriptionFilter == sub.id,
                                onClick = {
                                    subscriptionFilter = if (subscriptionFilter == sub.id) null else sub.id
                                },
                                label = { Text(sub.name, maxLines = 1) }
                            )
                        }
                    }
                }
            }

            if (pool == ServerPool.AUTO && balancerCount > 0) {
                item {
                    VoltaCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Скрывать балансеры", style = MaterialTheme.typography.titleSmall)
                                Hint("Серверы «Авто» и «Балансер» не показываются в списке")
                            }
                            Switch(checked = hideBalancers, onCheckedChange = vm::setHideBalancers)
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (byCountry) "${serversCount(visible.size)} · стран: ${countries.size}"
                        else serversCount(visible.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    SingleChoiceSegmentedButtonRow {
                        listOf(true to "Страны", false to "Список").forEachIndexed { index, (value, label) ->
                            SegmentedButton(
                                selected = byCountry == value,
                                onClick = { byCountry = value },
                                shape = SegmentedButtonDefaults.itemShape(index = index, count = 2),
                                icon = {},
                                label = { Text(label, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }
                }
            }

            if (visible.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.Storage,
                        title = if (state.servers.isEmpty()) "Серверов пока нет"
                        else "Ничего не нашлось",
                        subtitle = if (state.servers.isEmpty()) {
                            "Добавьте подписку или вставьте ссылку на сервер."
                        } else {
                            "Измените фильтр или поисковый запрос."
                        },
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
                        count = servers.size,
                        bestMs = servers.map(::measuredMs).filter { it >= 0 }.minOrNull() ?: -1,
                        expanded = expanded,
                        current = country.code == currentCountry,
                        onToggle = {
                            openCountries = if (country.code in open) open - country.code
                            else open + country.code
                        }
                    ) {
                        servers.forEach { server ->
                            ServerRow(
                                server = server,
                                selected = state.selectedServer?.id == server.id,
                                subscriptionName = subsById[server.subscriptionId]?.name.orEmpty(),
                                supported = supported[server.id] != false,
                                onClick = { vm.selectServer(server.id) },
                                onLongClick = { sheetServer = server },
                                inGroup = true
                            )
                        }
                    }
                }
            } else {
                items(visible, key = { it.id }) { server ->
                    ServerRow(
                        server = server,
                        selected = state.selectedServer?.id == server.id,
                        subscriptionName = subsById[server.subscriptionId]?.name.orEmpty(),
                        supported = supported[server.id] != false,
                        onClick = { vm.selectServer(server.id) },
                        onLongClick = { sheetServer = server }
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    sheetServer?.let { server ->
        ServerActionsSheet(
            server = server,
            onDismiss = { sheetServer = null },
            onPing = { vm.pingServer(server.id) },
            onToggleFavorite = { vm.toggleFavorite(server.id) },
            onSetRole = { role -> vm.setServerRole(server.id, role) },
            onDelete = if (server.subscriptionId == null) {
                { vm.deleteServer(server.id) }
            } else null,
            onCopied = { vm.showMessage("Ссылка скопирована") }
        )
    }
}

/**
 * The three app-side autobalancers.
 *
 * "Все" probes the network first and then measures whichever pool fits the answer;
 * the other two go straight to their own pool without touching any websites.
 */
@Composable
private fun AutobalancerBar(
    running: BalancerScope?,
    enabled: Boolean,
    onRun: (BalancerScope) -> Unit
) {
    VoltaCard {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            BalancerScope.entries.forEachIndexed { index, scope ->
                SegmentedButton(
                    selected = running == scope,
                    onClick = { onRun(scope) },
                    enabled = enabled && running == null,
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = BalancerScope.entries.size
                    ),
                    icon = {
                        if (running == scope) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        } else {
                            SegmentedButtonDefaults.Icon(active = false)
                        }
                    },
                    label = { Text(scopeLabel(scope)) }
                )
            }
        }
    }
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
    { OutlinedButton(onClick = onOpenImport) { Text("Добавить") } }
}

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

private fun poolLabel(pool: ServerPool): String = when (pool) {
    ServerPool.ALL -> "Все"
    ServerPool.NORMAL -> "Home"
    ServerPool.BYPASS_WHITELIST -> "LTE"
    ServerPool.AUTO -> "Балансеры"
}

private fun sortLabel(sort: ServerSort): String = when (sort) {
    ServerSort.BEST -> "Сначала быстрые"
    ServerSort.NAME -> "По названию"
    ServerSort.SUBSCRIPTION -> "По подписке"
}
