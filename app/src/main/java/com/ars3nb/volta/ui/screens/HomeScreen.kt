package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.ConnectPhase
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.AutoPickCard
import com.ars3nb.volta.ui.components.CardShape
import com.ars3nb.volta.ui.components.ConnectionHeadline
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.LiveMeter
import com.ars3nb.volta.ui.components.ManualModeStrip
import com.ars3nb.volta.ui.components.ModeSelector
import com.ars3nb.volta.ui.components.NetworkStatusCard
import com.ars3nb.volta.ui.components.PowerButton
import com.ars3nb.volta.ui.components.SignalBars
import com.ars3nb.volta.ui.components.SmallShape
import com.ars3nb.volta.ui.components.SpeedGraph
import com.ars3nb.volta.ui.components.StatTile
import com.ars3nb.volta.ui.components.VoltaCard
import com.ars3nb.volta.ui.components.flagOf
import com.ars3nb.volta.ui.components.formatBytes
import com.ars3nb.volta.ui.components.formatDuration
import com.ars3nb.volta.ui.components.groupColor
import com.ars3nb.volta.ui.components.groupLabel
import com.ars3nb.volta.ui.theme.VoltaTheme
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    vm: VoltaViewModel,
    onOpenServers: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenRouting: () -> Unit
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val mode by vm.connectMode.collectAsStateWithLifecycle()
    val phase by vm.connectPhase.collectAsStateWithLifecycle()
    val speed by vm.speedHistory.collectAsStateWithLifecycle()
    val pick by vm.pickSummary.collectAsStateWithLifecycle()
    val palette = VoltaTheme.palette

    // Ticks only while the tunnel is up, so an idle screen costs nothing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isConnected) {
        while (state.isConnected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val selected = state.selectedServer
    val subscriptionOf = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }
    val homeCount = remember(state.servers) {
        AutoBalancer.poolFor(state.servers, BalancerScope.NORMAL).size
    }
    val lteCount = remember(state.servers) {
        AutoBalancer.poolFor(state.servers, BalancerScope.BYPASS).size
    }
    val working = phase != ConnectPhase.IDLE

    // The whole screen takes on the state's colour, softly.
    val wash by animateColorAsState(
        targetValue = when {
            state.isConnected && !working -> palette.good.copy(alpha = if (palette.isDark) 0.16f else 0.12f)
            working || state.vpnState == VpnConnectionState.CONNECTING ->
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)

            state.vpnState == VpnConnectionState.ERROR -> palette.poor.copy(alpha = 0.10f)
            else -> palette.heroTop
        },
        animationSpec = tween(700),
        label = "wash"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(560.dp)
                .background(Brush.verticalGradient(listOf(wash, Color.Transparent)))
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item { TopStrip(regime = state.networkRegime, ip = state.ipInfo?.ip, country = state.ipInfo?.country) }

            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PowerButton(
                        state = state.vpnState,
                        working = working,
                        // Must stay tappable while connected, otherwise a server that
                        // vanished under the tunnel leaves no way to turn the VPN off.
                        enabled = state.servers.isNotEmpty() || state.isConnected,
                        onClick = vm::toggleConnect
                    )
                    ConnectionHeadline(
                        state = state.vpnState,
                        phaseCaption = phase.caption,
                        serverName = selected?.remark.orEmpty()
                    )
                    AnimatedVisibility(
                        visible = state.isConnected && !working,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        LiveMeter(
                            uptimeText = formatDuration(
                                if (state.connectedSince > 0) now - state.connectedSince else 0
                            ),
                            traffic = state.traffic,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            if (state.error != null && !state.isConnected && !working) {
                item { ErrorBanner(state.error!!) }
            }

            if (state.servers.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.CloudDownload,
                        title = "Добавьте подписку",
                        subtitle = "Вставьте ссылку от VPN-сервиса: серверы разложатся по группам " +
                            "Home и LTE, правила маршрутизации подтянутся сами.",
                        action = { Button(onClick = onOpenImport) { Text("Добавить подписку") } }
                    )
                }
            } else {
                item {
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "АВТОВЫБОР СЕРВЕРА",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 1.4.sp,
                            modifier = Modifier.padding(start = 6.dp, bottom = 8.dp)
                        )
                        ModeSelector(
                            mode = mode,
                            homeCount = homeCount,
                            lteCount = lteCount,
                            totalCount = state.servers.size,
                            onSelect = vm::setConnectMode
                        )
                        AnimatedVisibility(visible = mode == ConnectMode.MANUAL) {
                            ManualModeStrip(
                                serverName = selected?.remark ?: "не выбран",
                                onBackToAuto = { vm.setConnectMode(ConnectMode.AUTO_HOME_LTE) },
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }

                item {
                    CurrentServerCard(
                        server = selected,
                        subscription = selected?.subscriptionId?.let { subscriptionOf[it] },
                        onClick = onOpenServers
                    )
                }

                item {
                    AnimatedVisibility(
                        visible = pick != null && mode != ConnectMode.MANUAL && !working,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        pick?.let { AutoPickCard(it) }
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = state.isConnected,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            StatTile(
                                label = "Скачано",
                                value = formatBytes(state.traffic.downlinkBytes),
                                icon = Icons.Rounded.Download,
                                modifier = Modifier.weight(1f)
                            )
                            StatTile(
                                label = "Отправлено",
                                value = formatBytes(state.traffic.uplinkBytes),
                                icon = Icons.Rounded.Upload,
                                modifier = Modifier.weight(1f)
                            )
                            val ms = selected?.let { it.ewmaMs.takeIf { v -> v >= 0 } ?: it.latencyMs } ?: -1
                            StatTile(
                                label = "Отклик",
                                value = if (ms >= 0) "$ms мс" else "—",
                                icon = Icons.Rounded.Speed,
                                valueColor = palette.latency(ms),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = state.isConnected,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SpeedGraph(speed)
                    }
                }

                item {
                    NetworkStatusCard(
                        regime = state.networkRegime,
                        probes = state.probes,
                        onRecheck = vm::runStartupProbesForce
                    )
                }

                item {
                    RoutingRow(
                        name = selected?.subscriptionId
                            ?.let { subscriptionOf[it] }
                            ?.let { sub -> routingNameFor(sub, state.routingProfiles) }
                            ?: state.routingProfiles
                                .firstOrNull { it.isActive && it.sourceSubscriptionId == null }
                                ?.name.orEmpty(),
                        onClick = onOpenRouting
                    )
                }
            }
        }

        if (state.servers.isNotEmpty()) {
            // Quick add, always at hand — like Happ's "+" in the corner.
            IconButton(
                onClick = onOpenImport,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(end = 8.dp, top = 4.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Добавить подписку или сервер")
            }
        }
    }
}

@Composable
private fun TopStrip(regime: NetworkRegime, ip: String?, country: String?) {
    val palette = VoltaTheme.palette
    Row(
        Modifier.fillMaxWidth().padding(end = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "VOLTA",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            letterSpacing = 4.sp
        )
        Spacer(Modifier.width(10.dp))
        val (label, color) = when (regime) {
            NetworkRegime.OPEN -> "Home" to palette.home
            NetworkRegime.WHITELIST_ONLY -> "LTE" to palette.lte
            NetworkRegime.BLOCKED -> "нет сети" to palette.poor
            NetworkRegime.UNKNOWN -> "" to Color.Transparent
        }
        if (label.isNotBlank()) {
            Surface(shape = CircleShape, color = color.copy(alpha = 0.16f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (!ip.isNullOrBlank()) {
            Text(
                listOfNotNull(country?.takeIf { it.isNotBlank() }, ip).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CurrentServerCard(
    server: ServerEntity?,
    subscription: SubscriptionEntity?,
    onClick: () -> Unit
) {
    val palette = VoltaTheme.palette
    VoltaCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A new node rolls in from below, so an automatic switch is noticed.
            AnimatedContent(
                targetState = server,
                contentKey = { it?.id },
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith
                        (slideOutVertically { -it } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "server"
            ) { shown ->
                ServerIdentity(shown, subscription)
            }
            val ms = server?.let { it.ewmaMs.takeIf { v -> v >= 0 } ?: it.latencyMs } ?: -1
            if (ms >= 0) {
                Column(horizontalAlignment = Alignment.End) {
                    SignalBars(ms)
                    Text(
                        "$ms мс",
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.latency(ms),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServerIdentity(server: ServerEntity?, subscription: SubscriptionEntity?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(server?.let { flagOf(it.remark) } ?: "🌐", fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                server?.remark ?: "Сервер не выбран",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (server != null) {
                    val (label, home) = groupLabel(server.role)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = groupColor(home)
                    )
                    Text(
                        " · " + listOfNotNull(
                            server.protocol.label,
                            subscription?.name
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        "Откройте список, чтобы выбрать",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingRow(name: String, onClick: () -> Unit) {
    Surface(
        shape = CardShape,
        color = Color.Transparent,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Dns,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "Маршрутизация: ${name.ifBlank { "встроенная" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorBanner(text: String) {
    val palette = VoltaTheme.palette
    Surface(
        shape = SmallShape,
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

internal fun routingNameFor(
    sub: SubscriptionEntity,
    profiles: List<com.ars3nb.volta.data.model.RoutingProfileEntity>
): String = com.ars3nb.volta.core.routing.RoutingResolver.resolve(sub, profiles)?.name.orEmpty()
