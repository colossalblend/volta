package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.PerAppMode
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.ConnectHero
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.NetworkStatusCard
import com.ars3nb.volta.ui.components.SectionHeader
import com.ars3nb.volta.ui.components.StatTile
import com.ars3nb.volta.ui.components.SubscriptionCard
import com.ars3nb.volta.ui.components.formatBytes
import com.ars3nb.volta.ui.components.formatDuration
import com.ars3nb.volta.ui.theme.VoltaTheme
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: VoltaViewModel,
    onOpenServers: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenRouting: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val palette = VoltaTheme.palette
    var editing by remember { mutableStateOf<SubscriptionEntity?>(null) }

    // Ticks only while the tunnel is up, so an idle screen costs nothing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isConnected) {
        while (state.isConnected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val selected = state.selectedServer
    val subscriptionOf = remember(state.subscriptions) {
        state.subscriptions.associateBy { it.id }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("Volta", fontWeight = FontWeight.Bold)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // Ambient wash behind the hero.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(420.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(palette.heroTop, palette.heroBottom)
                        )
                    )
            )

            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ConnectHero(
                        state = state.vpnState,
                        serverName = selected?.remark.orEmpty(),
                        subtitle = heroSubtitle(state, subscriptionOf),
                        uptimeText = formatDuration(
                            if (state.connectedSince > 0) now - state.connectedSince else 0
                        ),
                        traffic = state.traffic,
                        // Must stay tappable while connected, otherwise a server that
                        // vanished under the tunnel leaves no way to turn the VPN off.
                        enabled = (selected != null || state.isConnected) && !state.busy,
                        onClick = vm::toggleConnect,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }

                if (state.error != null && !state.isConnected) {
                    item { ErrorBanner(state.error!!) }
                }

                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(
                            onClick = onOpenServers,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("Сервер")
                        }
                        Button(
                            onClick = { vm.runBalancer(BalancerScope.ALL) },
                            enabled = state.servers.isNotEmpty() && !state.busy,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (state.busy) {
                                CircularProgressIndicator(
                                    Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(18.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text("Подобрать")
                        }
                    }
                }

                if (state.isConnected) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(
                                label = "Скачано",
                                value = formatBytes(state.traffic.downlinkBytes),
                                icon = Icons.Rounded.CloudDownload,
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Отправлено",
                                value = formatBytes(state.traffic.uplinkBytes),
                                icon = Icons.Rounded.Bolt,
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Отклик",
                                value = selected?.let {
                                    val ms = it.ewmaMs.takeIf { v -> v >= 0 } ?: it.latencyMs
                                    if (ms >= 0) "$ms мс" else "—"
                                } ?: "—",
                                icon = Icons.Rounded.Speed,
                                valueColor = palette.latency(
                                    selected?.ewmaMs ?: -1
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                } else {
                    item {
                        NetworkStatusCard(
                            regime = state.networkRegime,
                            probes = state.probes,
                            ip = state.ipInfo?.ip,
                            country = state.ipInfo?.country,
                            onRecheck = vm::runStartupProbesForce
                        )
                    }
                }

                item {
                    ModeCard(
                        perAppMode = state.perAppMode,
                        onOpenRouting = onOpenRouting,
                        routingName = selected?.subscriptionId
                            ?.let { subscriptionOf[it] }
                            ?.let { sub -> routingNameFor(sub, state.routingProfiles) }
                            ?: state.routingProfiles
                                .firstOrNull { it.isActive && it.sourceSubscriptionId == null }
                                ?.name.orEmpty()
                    )
                }

                item {
                    SectionHeader("Подписки") {
                        androidx.compose.material3.TextButton(onClick = onOpenImport) {
                            Icon(Icons.Rounded.Add, null, Modifier.size(17.dp))
                            Spacer(Modifier.size(4.dp))
                            Text("Добавить")
                        }
                    }
                }

                if (state.subscriptions.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Rounded.CloudDownload,
                            title = "Пока пусто",
                            subtitle = "Вставьте ссылку от VPN-сервиса — серверы и правила " +
                                "подтянутся автоматически.",
                            action = {
                                Button(onClick = onOpenImport) { Text("Добавить подписку") }
                            }
                        )
                    }
                } else {
                    items(state.subscriptions, key = { "sub-${it.id}" }) { sub ->
                        SubscriptionCard(
                            sub = sub,
                            serverCount = state.servers.count { it.subscriptionId == sub.id },
                            routingName = routingNameFor(sub, state.routingProfiles),
                            active = selected?.subscriptionId == sub.id,
                            onClick = {
                                state.servers.firstOrNull { it.subscriptionId == sub.id }
                                    ?.let { vm.selectServer(it.id) }
                            },
                            onRefresh = { vm.refreshSubscription(sub.id) },
                            onEdit = { editing = sub }
                        )
                    }
                }
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

@Composable
private fun ErrorBanner(text: String) {
    val palette = VoltaTheme.palette
    androidx.compose.material3.Surface(
        shape = com.ars3nb.volta.ui.components.SmallShape,
        color = palette.poor.copy(alpha = 0.14f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = palette.poor,
            modifier = Modifier.padding(12.dp)
        )
    }
}

@Composable
private fun ModeCard(
    perAppMode: PerAppMode,
    routingName: String,
    onOpenRouting: () -> Unit
) {
    com.ars3nb.volta.ui.components.VoltaCard(onClick = onOpenRouting) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Dns,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Правила: ${routingName.ifBlank { "встроенные" }}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    when (perAppMode) {
                        PerAppMode.OFF -> "Туннель охватывает все приложения"
                        PerAppMode.INCLUDE -> "Через VPN идут только выбранные приложения"
                        PerAppMode.EXCLUDE -> "Выбранные приложения идут мимо VPN"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun heroSubtitle(
    state: com.ars3nb.volta.ui.VoltaUiState,
    subs: Map<Long, SubscriptionEntity>
): String {
    val server = state.selectedServer ?: return "Добавьте подписку, чтобы начать"
    val subName = server.subscriptionId?.let { subs[it]?.name }.orEmpty()
    return buildString {
        append("${server.address}:${server.port}")
        if (subName.isNotBlank()) append(" · $subName")
    }
}

private fun routingNameFor(
    sub: SubscriptionEntity,
    profiles: List<com.ars3nb.volta.data.model.RoutingProfileEntity>
): String = com.ars3nb.volta.core.routing.RoutingResolver.resolve(sub, profiles)?.name.orEmpty()
