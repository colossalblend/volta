package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
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
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SubscriptionCard

/**
 * Every subscription the app holds. Their servers are merged into one list (Servers tab)
 * and split by group; routing and geodata stay per subscription, exactly as in Happ.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    vm: VoltaViewModel,
    onOpenImport: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val selected = state.selectedServer

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Подписки", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (state.subscriptions.isNotEmpty()) {
                        IconButton(onClick = vm::refreshAllSubscriptions, enabled = !state.busy) {
                            Icon(Icons.Rounded.Refresh, "Обновить все подписки")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenImport,
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("Добавить") }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.subscriptions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.CloudDownload,
                        title = "Подписок пока нет",
                        subtitle = "Добавьте одну или несколько — Volta объединит их серверы в " +
                            "общий список и сама разложит по группам Home и LTE.",
                        action = { Button(onClick = onOpenImport) { Text("Добавить подписку") } }
                    )
                }
            } else {
                item {
                    Hint(
                        "Серверы всех подписок собраны в один список. Правила маршрутизации и " +
                            "файлы geoip/geosite у каждой подписки свои и обновляются вместе с ней."
                    )
                }
                items(state.subscriptions, key = { "sub-${it.id}" }) { sub ->
                    val geo = remember(sub.id, sub.lastUpdated) { vm.geoStatus(sub.id) }
                    SubscriptionCard(
                        sub = sub,
                        serverCount = state.servers.count { it.subscriptionId == sub.id },
                        routingName = RoutingResolver.resolve(sub, state.routingProfiles)?.name.orEmpty(),
                        active = selected?.subscriptionId == sub.id,
                        onClick = { editing = sub },
                        onRefresh = { vm.refreshSubscription(sub.id) },
                        onEdit = { editing = sub },
                        geoText = when {
                            geo == null -> ""
                            geo.geoip != null && geo.geosite != null -> "свои geoip + geosite"
                            geo.geoip != null -> "свой geoip"
                            else -> "свой geosite"
                        }
                    )
                }
                item { Spacer(Modifier.size(8.dp)) }
            }
        }
    }

    editing?.let { sub ->
        SubscriptionEditorDialog(
            subscription = sub,
            busy = state.busy,
            onDismiss = { editing = null },
            onSave = { name, url ->
                vm.updateSubscription(sub.id, name, url) { ok -> if (ok) editing = null }
            },
            onDelete = {
                vm.deleteSubscription(sub.id)
                editing = null
            }
        )
    }
}
