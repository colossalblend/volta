package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.core.vpn.ConfigBuilder
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerPool
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
    var sort by remember { mutableStateOf(ServerSort.BEST) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var sheetServer by remember { mutableStateOf<ServerEntity?>(null) }

    val subsById = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }

    // canDial builds JSON, so cache the verdict for the whole list.
    val supported = remember(state.servers) {
        state.servers.associate { it.id to ConfigBuilder.canDial(it) }
    }

    val visible = remember(state.servers, pool, query, sort, favoritesOnly, supported) {
        state.servers
            .filter { pool.matches(it.role) }
            .filter { !favoritesOnly || it.isFavorite }
            .filter { matchesQuery(it, query, subsById[it.subscriptionId]?.name.orEmpty()) }
            .sortedWith(
                when (sort) {
                    ServerSort.BEST -> compareBy<ServerEntity> { supported[it.id] == false }
                        .thenBy { it.score }

                    ServerSort.NAME -> compareBy { it.remark.lowercase() }
                    ServerSort.SUBSCRIPTION -> compareBy<ServerEntity> { it.subscriptionId ?: 0 }
                        .thenBy { it.sortOrder }
                }
            )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Серверы", fontWeight = FontWeight.SemiBold) }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionHeader("Автоподбор") }
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
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ServerPool.entries.forEach { option ->
                        FilterChip(
                            selected = pool == option,
                            onClick = { vm.setServerPool(option) },
                            label = { Text(poolLabel(option)) }
                        )
                    }
                    FilterChip(
                        selected = favoritesOnly,
                        onClick = { favoritesOnly = !favoritesOnly },
                        label = { Text("Избранные") }
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ServerSort.entries.forEach { option ->
                        FilterChip(
                            selected = sort == option,
                            onClick = { sort = option },
                            label = { Text(sortLabel(option)) }
                        )
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = { vm.pingAll(pool = pool) },
                    enabled = !state.busy && state.servers.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.busy && runningScope == null) {
                        CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Rounded.Speed, null, Modifier.size(17.dp))
                    }
                    Spacer(Modifier.size(8.dp))
                    Text("Проверить отклик всех")
                }
            }

            item {
                SectionHeader("Найдено: ${visible.size}") { Hint("всего ${state.servers.size}") }
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
        Spacer(Modifier.height(8.dp))
        Hint(
            "«Все» — сначала проверяет сайты: открыт интернет — берёт обычные серверы, " +
                "работает только «белый список» — берёт серверы обхода, сети нет — ничего " +
                "не пингует и повторит попытку позже. «Обычные» и «Обход» сразу проверяют " +
                "свою группу."
        )
    }
}

private fun scopeLabel(scope: BalancerScope): String = when (scope) {
    BalancerScope.ALL -> "Все"
    BalancerScope.NORMAL -> "Обычные"
    BalancerScope.BYPASS -> "Обход"
}

/** Explicit helper: an `if/else null` producing a composable lambda confuses inference. */
private fun emptyStateAction(
    show: Boolean,
    onOpenImport: () -> Unit
): (@Composable () -> Unit)? = if (!show) null else {
    { OutlinedButton(onClick = onOpenImport) { Text("Добавить") } }
}

private fun matchesQuery(server: ServerEntity, query: String, subName: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return server.remark.lowercase().contains(q) ||
        server.address.lowercase().contains(q) ||
        server.protocol.label.lowercase().contains(q) ||
        subName.lowercase().contains(q) ||
        server.tags.lowercase().contains(q)
}

private fun poolLabel(pool: ServerPool): String = when (pool) {
    ServerPool.ALL -> "Все"
    ServerPool.NORMAL -> "Обычные"
    ServerPool.BYPASS_WHITELIST -> "Обход"
    ServerPool.AUTO -> "Универсальные"
}

private fun sortLabel(sort: ServerSort): String = when (sort) {
    ServerSort.BEST -> "Сначала быстрые"
    ServerSort.NAME -> "По названию"
    ServerSort.SUBSCRIPTION -> "По подписке"
}
