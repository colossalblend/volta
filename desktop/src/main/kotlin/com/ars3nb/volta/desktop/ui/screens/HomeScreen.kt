package com.ars3nb.volta.desktop.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.SettingsEthernet
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.desktop.ConnectPhase
import com.ars3nb.volta.desktop.VoltaUiState
import com.ars3nb.volta.desktop.ui.LocalLang
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.components.AutoPickCard
import com.ars3nb.volta.desktop.ui.components.ContentMaxWidth
import com.ars3nb.volta.desktop.ui.components.ConnectionHeadline
import com.ars3nb.volta.desktop.ui.components.CountryBadge
import com.ars3nb.volta.desktop.ui.components.LiveMeter
import com.ars3nb.volta.desktop.ui.components.ManualModeStrip
import com.ars3nb.volta.desktop.ui.components.ModeSelector
import com.ars3nb.volta.desktop.ui.components.NetworkStatusCard
import com.ars3nb.volta.desktop.ui.components.PowerButton
import com.ars3nb.volta.desktop.ui.components.ScrollPage
import com.ars3nb.volta.desktop.ui.components.SignalBars
import com.ars3nb.volta.desktop.ui.components.SpeedGraph
import com.ars3nb.volta.desktop.ui.components.VoltaCard
import com.ars3nb.volta.desktop.ui.components.countryName
import com.ars3nb.volta.desktop.ui.components.formatDuration
import com.ars3nb.volta.desktop.ui.components.groupColor
import com.ars3nb.volta.desktop.ui.components.groupLabel
import com.ars3nb.volta.desktop.ui.components.trafficActivity
import com.ars3nb.volta.desktop.ui.theme.TABULAR
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme
import kotlinx.coroutines.delay

/** What the home screen can ask for. */
class HomeActions(
    val onToggle: () -> Unit,
    val onSelectMode: (ConnectMode) -> Unit,
    val onOpenServers: () -> Unit,
    val onOpenImport: () -> Unit,
    val onRecheckNetwork: () -> Unit,
    val onLteMissing: () -> Unit
)

/**
 * The home screen, as on Android: the disc and what it needs first (the state in a word,
 * the server it goes through, the auto mode), then the speed, why this server, the
 * network and, new on Windows, what the system proxy is doing.
 */
@Composable
fun HomeScreen(state: VoltaUiState, actions: HomeActions) {
    val s = LocalStrings.current
    val rt = state.runtime
    val mode = state.settings.connectMode
    val phase = rt.phase
    val selected = state.selectedServer
    val subscriptionOf = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }
    val homeCount = remember(state.servers) { AutoBalancer.poolFor(state.servers, BalancerScope.NORMAL).size }
    val lteCount = remember(state.servers) { AutoBalancer.poolFor(state.servers, BalancerScope.BYPASS).size }
    val autoCount = remember(state.servers) { AutoBalancer.poolFor(state.servers, BalancerScope.ALL).size }
    val working = phase != ConnectPhase.IDLE
    val hasServers = state.servers.isNotEmpty()

    // Ticks only while the tunnel is up, so an idle screen costs nothing.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isConnected) {
        while (state.isConnected) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val viewport = maxHeight
        val discSize = min(min(min(maxWidth, ContentMaxWidth) * 0.92f, viewport * 0.47f), 360.dp)

        ScrollPage(spacing = 12.dp, contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            item(key = "hero") {
                Column(
                    Modifier.fillMaxWidth().heightIn(min = viewport - PEEK),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    TopBar(
                        regime = state.networkRegime,
                        ip = rt.ipInfo?.ip,
                        country = rt.ipInfo?.let { info ->
                            if (info.countryCode.isNotBlank()) countryName(info.countryCode, LocalLang.current, s)
                            else info.country
                        },
                        onAdd = actions.onOpenImport.takeIf { hasServers }
                    )

                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
                        PowerButton(
                            state = state.vpnState,
                            working = working,
                            enabled = hasServers || state.isConnected,
                            onClick = actions.onToggle,
                            size = discSize,
                            activity = trafficActivity(rt.traffic)
                        )
                        ConnectionHeadline(
                            state = state.vpnState,
                            phaseCaption = phase.caption(s),
                            serverName = (state.servers.firstOrNull { it.id == rt.connectedServerId } ?: selected)
                                ?.let { Countries.withoutLeadingFlag(it.remark) }.orEmpty(),
                            idleCaption = if (hasServers) s.tapToConnect else s.addSubscriptionFirst
                        )
                        AnimatedVisibility(
                            visible = state.isConnected && !working,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            LiveMeter(
                                uptimeText = formatDuration(if (rt.connectedSince > 0) now - rt.connectedSince else 0),
                                traffic = rt.traffic,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = rt.error != null && !state.isConnected && !working,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                rt.error.orEmpty(),
                                style = MaterialTheme.typography.bodySmall,
                                color = VoltaTheme.palette.poor,
                                textAlign = TextAlign.Center,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 10.dp, start = 12.dp, end = 12.dp)
                            )
                        }
                    }

                    if (hasServers) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ServerCapsule(
                                server = selected,
                                subscription = selected?.subscriptionId?.let { subscriptionOf[it] },
                                manual = mode == ConnectMode.MANUAL,
                                onClick = actions.onOpenServers
                            )
                            ModeSelector(
                                mode = mode,
                                homeCount = homeCount,
                                lteCount = lteCount,
                                totalCount = autoCount,
                                onSelect = actions.onSelectMode,
                                onLteMissing = actions.onLteMissing
                            )
                            AnimatedVisibility(visible = mode == ConnectMode.MANUAL) {
                                ManualModeStrip(serverName = "", onBackToAuto = { actions.onSelectMode(ConnectMode.AUTO_HOME_LTE) })
                            }
                        }
                    } else {
                        FirstSubscription(actions.onOpenImport)
                    }
                }
            }

            if (hasServers) {
                item(key = "speed") {
                    AnimatedVisibility(
                        visible = state.isConnected,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        SpeedGraph(rt.speed, totals = rt.traffic)
                    }
                }
                item(key = "proxy") {
                    AnimatedVisibility(
                        visible = state.isConnected && rt.ports != null,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        rt.ports?.let { ports -> ProxyCard(rt.proxyActive, ports.http, ports.socks) }
                    }
                }
                item(key = "pick") {
                    val pick = rt.pick
                    AnimatedVisibility(
                        visible = pick != null && mode != ConnectMode.MANUAL && !working,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        pick?.let { AutoPickCard(it) }
                    }
                }
                if (lteCount > 0) {
                    item(key = "network") {
                        NetworkStatusCard(regime = state.networkRegime, onRecheck = actions.onRecheckNetwork)
                    }
                }
            }
        }
    }
}

/** How much of the details shows under the first screenful. */
private val PEEK = 44.dp

@Composable
private fun TopBar(regime: NetworkRegime, ip: String?, country: String?, onAdd: (() -> Unit)?) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val s = LocalStrings.current
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volta", style = MaterialTheme.typography.headlineSmall, color = scheme.onBackground)
                val (label, color) = when (regime) {
                    NetworkRegime.OPEN -> s.modeHome to palette.home
                    NetworkRegime.WHITELIST_ONLY -> s.modeLte to palette.lte
                    NetworkRegime.BLOCKED -> s.regimeNoNetwork to palette.poor
                    NetworkRegime.UNKNOWN -> "" to Color.Transparent
                }
                AnimatedVisibility(visible = label.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .clip(CircleShape)
                            .background(color.copy(alpha = 0.15f))
                            .padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            // The address the internet sees right now: the server's while connected.
            AnimatedContent(
                targetState = listOfNotNull(country?.takeIf { it.isNotBlank() }, ip?.takeIf { it.isNotBlank() }).joinToString(", "),
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "exit"
            ) { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = TABULAR),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (onAdd != null) {
            Surface(onClick = onAdd, shape = CircleShape, color = scheme.surface, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = s.addSubscriptionOrServer)
                }
            }
        }
    }
}

/** The server the tunnel goes through. Clicking it opens the list. */
@Composable
private fun ServerCapsule(server: ServerEntity?, subscription: SubscriptionEntity?, manual: Boolean, onClick: () -> Unit) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val s = LocalStrings.current
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = scheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = server,
                contentKey = { it?.id },
                transitionSpec = {
                    (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "server"
            ) { shown ->
                ServerIdentity(shown, subscription, manual)
            }
            val ms = server?.let { it.ewmaMs.takeIf { v -> v >= 0 } ?: it.latencyMs } ?: -1
            if (ms >= 0) {
                Column(horizontalAlignment = Alignment.End) {
                    SignalBars(ms)
                    Text(
                        s.ms(ms),
                        style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
                        color = palette.latency(ms)
                    )
                }
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ServerIdentity(server: ServerEntity?, subscription: SubscriptionEntity?, manual: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        CountryBadge(server?.let { Countries.of(it.remark).code } ?: Countries.UNKNOWN_CODE, size = 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    server != null -> Countries.withoutLeadingFlag(server.remark)
                    manual -> s.serverNotChosen
                    else -> s.fastestServer
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (server != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, home) = groupLabel(server.role, s)
                    Text(label, style = MaterialTheme.typography.labelMedium, color = groupColor(home))
                    subscription?.name?.takeIf { it.isNotBlank() }?.let { name ->
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Text(
                    if (manual) s.openListToChoose else s.willFindOnConnect,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/** No servers yet: the one thing to do. */
@Composable
private fun FirstSubscription(onOpenImport: () -> Unit) {
    val s = LocalStrings.current
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            s.firstSubscriptionText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(14.dp))
        Button(onClick = onOpenImport, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(s.addSubscription, style = MaterialTheme.typography.titleSmall)
        }
    }
}

/** Windows only: where the traffic goes in, for apps that don't follow the system proxy. */
@Composable
private fun ProxyCard(systemProxy: Boolean, http: Int, socks: Int) {
    val s = LocalStrings.current
    val palette = VoltaTheme.palette
    VoltaCard(contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.SettingsEthernet,
                null,
                tint = if (systemProxy) palette.good else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (systemProxy) s.proxyOnTitle else s.proxyLocalTitle, style = MaterialTheme.typography.titleSmall)
                Text(
                    s.proxyPorts(http, socks),
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
