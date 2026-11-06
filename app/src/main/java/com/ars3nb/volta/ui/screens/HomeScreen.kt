package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.balancer.AutoBalancer
import com.ars3nb.volta.core.balancer.SubscriptionScope
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.core.locale.asString
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.stats.TrafficSummary
import com.ars3nb.volta.core.update.UpdateState
import com.ars3nb.volta.core.vpn.InternetCheck
import com.ars3nb.volta.data.model.BalancerScope
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.data.model.SwitchTarget
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.ConnectPhase
import com.ars3nb.volta.ui.PickSummary
import com.ars3nb.volta.ui.SpeedHistory
import com.ars3nb.volta.ui.VoltaUiState
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.AutoPickCard
import com.ars3nb.volta.ui.components.CardShape
import com.ars3nb.volta.ui.components.ConnectionHeadline
import com.ars3nb.volta.ui.components.LiveMeter
import com.ars3nb.volta.ui.components.ManualModeStrip
import com.ars3nb.volta.ui.components.ModeSelector
import com.ars3nb.volta.ui.components.NetworkStatusCard
import com.ars3nb.volta.ui.components.OpenFluxCaptchaBanner
import com.ars3nb.volta.ui.components.PowerButton
import com.ars3nb.volta.ui.components.SignalBars
import com.ars3nb.volta.ui.components.SpeedGraph
import com.ars3nb.volta.ui.components.TrafficCard
import com.ars3nb.volta.ui.components.UpdateBanner
import com.ars3nb.volta.ui.components.VoltaChip
import com.ars3nb.volta.ui.components.subscriptionColor
import com.ars3nb.volta.ui.components.InternetBanner
import com.ars3nb.volta.ui.components.flagOf
import com.ars3nb.volta.ui.components.formatDuration
import com.ars3nb.volta.ui.components.formatLatency
import com.ars3nb.volta.ui.components.screenResources
import com.ars3nb.volta.ui.components.groupColor
import com.ars3nb.volta.ui.components.groupLabel
import com.ars3nb.volta.ui.components.offersInstall
import com.ars3nb.volta.ui.components.rememberInternetStatus
import com.ars3nb.volta.ui.components.trafficActivity
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.TABULAR
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import com.ars3nb.volta.ui.share.BleReceiveSheet
import com.ars3nb.volta.ui.share.rememberBleShareAvailable
import com.ars3nb.volta.ui.tv.LocalTv
import com.ars3nb.volta.ui.tv.StartFocus
import com.ars3nb.volta.ui.tv.focusRing
import kotlinx.coroutines.delay
import com.ars3nb.volta.ui.components.shownServerName
import com.ars3nb.volta.ui.components.shownProfileName
import com.ars3nb.volta.ui.components.shownSubscriptionName
import com.ars3nb.volta.ui.components.shownIpCountry

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
    val update by vm.updateState.collectAsStateWithLifecycle()
    val openFlux by vm.openFluxStatus.collectAsStateWithLifecycle()
    val traffic by vm.trafficSummary.collectAsStateWithLifecycle()
    val switching by vm.switching.collectAsStateWithLifecycle()
    val bySubscription by vm.autoBySubscription.collectAsStateWithLifecycle()
    val autoSubscription by vm.autoSubscription.collectAsStateWithLifecycle()
    val internet by rememberInternetStatus()
    // On a TV with nothing set up yet, a phone nearby can send the subscription.
    val bluetooth = rememberBleShareAvailable()
    val receiveOnTv = LocalTv.current && bluetooth
    var receiving by remember { mutableStateOf(false) }

    HomeContent(
        state = state,
        mode = mode,
        phase = phase,
        speed = speed,
        pick = pick,
        update = update,
        openFlux = openFlux,
        traffic = traffic,
        internet = internet,
        switching = switching,
        autoBySubscription = bySubscription,
        autoSubscription = autoSubscription,
        onAutoSubscription = vm::setAutoSubscription,
        onToggle = vm::toggleConnect,
        onSelectMode = vm::setConnectMode,
        onOpenServers = onOpenServers,
        onOpenImport = onOpenImport,
        onOpenRouting = onOpenRouting,
        onRecheckNetwork = vm::runStartupProbesForce,
        onInstallUpdate = vm::installUpdate,
        onLteMissing = {
            vm.showMessage(UiText.of(R.string.home_lte_missing))
        },
        onReceiveBluetooth = if (receiveOnTv) {
            { receiving = true }
        } else null
    )
    if (receiving) {
        BleReceiveSheet(
            onAdd = { text -> vm.importLink(text) },
            onDismiss = { receiving = false }
        )
    }
}

/**
 * The home screen as a function of its state, so it can be drawn without a view model.
 *
 * The first screenful is the disc and what it needs: the state in a word, the server it
 * goes through and the auto mode. Everything else (speed, why this server, the network
 * check, routing) waits below; its top edge peeks out so it's clear there is more.
 */
@Composable
fun HomeContent(
    state: VoltaUiState,
    mode: ConnectMode,
    phase: ConnectPhase,
    speed: SpeedHistory,
    pick: PickSummary?,
    update: UpdateState,
    openFlux: OpenFluxRunner.Status,
    traffic: TrafficSummary = TrafficSummary(),
    /** A fixed clock for screenshots; null ticks live, inside the uptime meter only. */
    now: Long? = null,
    internet: InternetCheck.Status = InternetCheck.Status.OK,
    onToggle: () -> Unit,
    onSelectMode: (ConnectMode) -> Unit,
    onOpenServers: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenRouting: () -> Unit,
    onRecheckNetwork: () -> Unit,
    onInstallUpdate: () -> Unit,
    onLteMissing: () -> Unit = {},
    /** «Получить по Bluetooth» under «add a subscription» (on a TV); null hides it. */
    onReceiveBluetooth: (() -> Unit)? = null,
    /** A live tunnel on its way to another node (see VoltaViewModel.switching). */
    switching: SwitchTarget? = null,
    /** «Авто по подпискам» is on: the auto modes may be limited to one subscription. */
    autoBySubscription: Boolean = false,
    /** The subscription the auto modes pick from; 0: all of them. */
    autoSubscription: Long = 0,
    onAutoSubscription: (Long) -> Unit = {}
) {
    val selected = state.selectedServer
    val subscriptionOf = remember(state.subscriptions) { state.subscriptions.associateBy { it.id } }
    // The choice is offered with two subscriptions or more, in an auto mode.
    val limitShown = autoBySubscription && state.subscriptions.size >= 2 && mode != ConnectMode.MANUAL
    val limit = SubscriptionScope.effective(
        chosen = autoSubscription,
        enabled = autoBySubscription,
        mode = mode,
        simpleMode = false,
        subscriptionIds = state.subscriptions.map { it.id }
    )
    // The counts on the mode selector are of the servers the auto pick may use.
    val scoped = remember(state.servers, limit) { SubscriptionScope.pool(state.servers, limit, scope = null) }
    val homeCount = remember(scoped) {
        AutoBalancer.poolFor(scoped, BalancerScope.NORMAL).size
    }
    val lteCount = remember(scoped) {
        AutoBalancer.poolFor(scoped, BalancerScope.BYPASS).size
    }
    // Provider balancers are in no auto group, so they don't count here either.
    val autoCount = remember(scoped) {
        AutoBalancer.poolFor(scoped, BalancerScope.ALL).size
    }
    val working = phase != ConnectPhase.IDLE
    // A live tunnel never looks off while it moves: a pick by hand, the guard, or the
    // auto pick choosing again after a mode change all show as "switching".
    val moving = state.isConnected && (switching != null || working) ||
        (switching != null && state.vpnState == VpnConnectionState.CONNECTING)
    val hasServers = state.servers.isNotEmpty()
    // Must stay tappable while connected, otherwise a server that vanished under the
    // tunnel leaves no way to turn the VPN off.
    val discEnabled = hasServers || state.isConnected

    // On a TV the remote starts on the disc, or on "add a subscription" while there is
    // nothing to connect to. The servers load a moment after the screen opens.
    val startFocus = remember { FocusRequester() }
    StartFocus(startFocus, target = discEnabled, settled = discEnabled)

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val viewport = maxHeight
        val discSize = min(min(maxWidth * 0.92f, viewport * 0.47f), 380.dp)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "hero") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = viewport - PEEK)
                        .statusBarsPadding(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        TopBar(
                            regime = state.networkRegime,
                            ip = state.ipInfo?.ip,
                            country = shownIpCountry(state.ipInfo),
                            onAdd = onOpenImport.takeIf { hasServers }
                        )
                        InternetBanner(internet)
                        (openFlux as? OpenFluxRunner.Status.Captcha)?.let { OpenFluxCaptchaBanner(it) }
                        if (update.offersInstall) UpdateBanner(update, onInstall = onInstallUpdate)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        PowerButton(
                            state = state.vpnState,
                            working = working && !moving,
                            enabled = discEnabled,
                            onClick = onToggle,
                            size = discSize,
                            activity = trafficActivity(state.traffic),
                            switching = moving,
                            modifier = if (discEnabled) Modifier.focusRequester(startFocus) else Modifier
                        )
                        ConnectionHeadline(
                            state = state.vpnState,
                            phaseCaption = if (phase.caption != 0) stringResource(phase.caption) else "",
                            serverName = selected?.let { shownServerName(it.remark) }.orEmpty(),
                            idleCaption = if (hasServers) {
                                stringResource(R.string.home_tap_disc)
                            } else {
                                stringResource(R.string.home_add_subscription_first)
                            },
                            switchingTo = if (moving) switching?.let { shownServerName(it.name) }.orEmpty() else null
                        )
                        AnimatedVisibility(
                            // Up while it switches: the tunnel and its clock carry on.
                            visible = state.isConnected && (!working || moving),
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            UptimeMeter(
                                connectedSince = state.connectedSince,
                                fixedNow = now,
                                traffic = state.traffic,
                                modifier = Modifier.padding(top = 12.dp)
                            )
                        }
                        AnimatedVisibility(
                            visible = state.error != null && !state.isConnected && !working,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically()
                        ) {
                            Text(
                                state.error?.asString().orEmpty(),
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
                                onClick = onOpenServers,
                                switching = moving
                            )
                            ModeSelector(
                                mode = mode,
                                homeCount = homeCount,
                                lteCount = lteCount,
                                totalCount = autoCount,
                                onSelect = onSelectMode,
                                onLteMissing = onLteMissing
                            )
                            AnimatedVisibility(
                                visible = limitShown,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                SubscriptionLimitRow(
                                    subscriptions = state.subscriptions,
                                    chosen = limit ?: 0L,
                                    onChoose = onAutoSubscription
                                )
                            }
                            AnimatedVisibility(visible = mode == ConnectMode.MANUAL) {
                                // The capsule above already names the server.
                                ManualModeStrip(
                                    serverName = "",
                                    onBackToAuto = { onSelectMode(ConnectMode.AUTO_HOME_LTE) }
                                )
                            }
                        }
                    } else {
                        FirstSubscription(
                            onOpenImport,
                            if (discEnabled) Modifier else Modifier.focusRequester(startFocus),
                            onReceiveBluetooth
                        )
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
                        SpeedGraph(speed, totals = state.traffic)
                    }
                }

                // Shown once anything has gone through the VPN, connected or not.
                if (!traffic.isEmpty) {
                    item(key = "traffic") { TrafficCard(traffic) }
                }

                item(key = "pick") {
                    AnimatedVisibility(
                        visible = pick != null && mode != ConnectMode.MANUAL && !working,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically()
                    ) {
                        pick?.let { AutoPickCard(it) }
                    }
                }

                // The site check only chooses between Home and LTE; with no LTE server
                // there is nothing to choose, so neither the check nor its card.
                if (lteCount > 0) {
                    item(key = "network") {
                        NetworkStatusCard(
                            regime = state.networkRegime,
                            probes = state.probes,
                            onRecheck = onRecheckNetwork
                        )
                    }
                }

                item(key = "routing") {
                    RoutingRow(
                        name = selected?.subscriptionId
                            ?.let { subscriptionOf[it] }
                            ?.let { sub -> routingFor(sub, state.routingProfiles) }
                            ?.let { shownProfileName(it) }
                            ?: state.routingProfiles
                                .firstOrNull { it.isActive && it.sourceSubscriptionId == null }
                                ?.let { shownProfileName(it) }.orEmpty(),
                        onClick = onOpenRouting
                    )
                }
            }
        }
    }
}

/** How much of the details shows under the first screenful. */
private val PEEK = 44.dp

/**
 * The live meter with its own once-a-second clock, so the tick redraws only this line and
 * not the whole home screen. It runs only while the meter is shown (the tunnel is up).
 */
@Composable
private fun UptimeMeter(connectedSince: Long, fixedNow: Long?, traffic: TrafficStats, modifier: Modifier) {
    var ticking by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (fixedNow == null) {
        LaunchedEffect(Unit) {
            while (true) {
                ticking = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    val now = fixedNow ?: ticking
    LiveMeter(
        uptimeText = formatDuration(if (connectedSince > 0) now - connectedSince else 0),
        traffic = traffic,
        modifier = modifier
    )
}

@Composable
private fun TopBar(regime: NetworkRegime, ip: String?, country: String?, onAdd: (() -> Unit)?) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Volta", style = MaterialTheme.typography.headlineSmall, color = scheme.onBackground)
                val (label, color) = when (regime) {
                    NetworkRegime.OPEN -> "Home" to palette.home
                    NetworkRegime.WHITELIST_ONLY -> "LTE" to palette.lte
                    NetworkRegime.BLOCKED -> stringResource(R.string.home_regime_no_network) to palette.poor
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
                targetState = listOfNotNull(country?.takeIf { it.isNotBlank() }, ip?.takeIf { it.isNotBlank() })
                    .joinToString(", "),
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
            Surface(
                onClick = onAdd,
                shape = CircleShape,
                color = scheme.surface,
                modifier = Modifier.size(44.dp).focusRing(CircleShape)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.home_add_description))
                }
            }
        }
    }
}

/** The server the tunnel goes through. Tapping it opens the list. */
@Composable
private fun ServerCapsule(
    server: ServerEntity?,
    subscription: SubscriptionEntity?,
    manual: Boolean,
    onClick: () -> Unit,
    switching: Boolean = false
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val still = rememberReducedMotion()
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(28.dp),
        color = scheme.surface,
        modifier = Modifier.fillMaxWidth().focusRing(RoundedCornerShape(28.dp))
    ) {
        Row(
            Modifier.padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A new node rolls in from below, so an automatic switch is noticed.
            AnimatedContent(
                targetState = server,
                contentKey = { it?.id },
                transitionSpec = {
                    if (still) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (slideInVertically(Motion.settle()) { it } + fadeIn(Motion.settle())) togetherWith
                            (slideOutVertically(Motion.settle()) { -it } + fadeOut(Motion.settle()))
                    }
                },
                modifier = Modifier.weight(1f),
                label = "server"
            ) { shown ->
                ServerIdentity(shown, subscription, manual)
            }
            val ms = server?.let { it.ewmaMs.takeIf { v -> v >= 0 } ?: it.latencyMs } ?: -1
            // While the tunnel moves, a small ring stands where the figure will be.
            AnimatedContent(
                targetState = if (switching) -2L else ms,
                transitionSpec = {
                    if (still) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(Motion.settle()) togetherWith fadeOut(Motion.settle())
                },
                label = "capsuleFigure"
            ) { figure ->
                when {
                    figure == -2L -> CircularProgressIndicator(
                        Modifier.padding(horizontal = 6.dp).size(18.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary
                    )
                    figure >= 0 -> Column(horizontalAlignment = Alignment.End) {
                        SignalBars(figure)
                        Text(
                            formatLatency(screenResources(), figure),
                            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
                            color = palette.latency(figure)
                        )
                    }
                    else -> Spacer(Modifier.size(0.dp))
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = scheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ServerIdentity(server: ServerEntity?, subscription: SubscriptionEntity?, manual: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(scheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(server?.let { flagOf(it.remark) } ?: "🌐", fontSize = 22.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    server != null -> shownServerName(server.remark)
                    manual -> stringResource(R.string.home_server_none)
                    else -> stringResource(R.string.home_server_fastest)
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (server != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val (label, home) = groupLabel(server.role)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = groupColor(home)
                    )
                    subscription?.name?.takeIf { it.isNotBlank() }?.let { shownSubscriptionName(it) }?.let { name ->
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            } else {
                Text(
                    stringResource(if (manual) R.string.home_server_open_list else R.string.home_server_on_connect),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * «Авто по подписке»: which subscriptions the auto mode picks from. One compact row of
 * chips under the mode selector, all of them first; scrolls sideways when long.
 */
@Composable
private fun SubscriptionLimitRow(
    subscriptions: List<SubscriptionEntity>,
    chosen: Long,
    onChoose: (Long) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            stringResource(R.string.home_auto_scope_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp)
        )
        VoltaChip(
            text = stringResource(R.string.servers_all_subscriptions),
            selected = chosen == 0L,
            onClick = { onChoose(0L) }
        )
        subscriptions.forEach { sub ->
            VoltaChip(
                text = shownSubscriptionName(sub),
                selected = chosen == sub.id,
                onClick = { onChoose(if (chosen == sub.id) 0L else sub.id) },
                accent = subscriptionColor(sub)
            )
        }
    }
}

/** No servers yet: the one thing to do. */
@Composable
private fun FirstSubscription(
    onOpenImport: () -> Unit,
    buttonModifier: Modifier = Modifier,
    onReceiveBluetooth: (() -> Unit)? = null
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.home_first_subscription),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = onOpenImport,
            modifier = buttonModifier
                .fillMaxWidth()
                .height(52.dp)
                // Cream, not copper: the button itself is copper.
                .focusRing(CircleShape, color = MaterialTheme.colorScheme.onBackground)
        ) {
            Text(stringResource(R.string.home_add_subscription), style = MaterialTheme.typography.titleSmall)
        }
        if (onReceiveBluetooth != null) {
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(
                onClick = onReceiveBluetooth,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .focusRing(CircleShape)
            ) {
                Icon(Icons.Rounded.Bluetooth, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.share_receive_action), style = MaterialTheme.typography.titleSmall)
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
        modifier = Modifier.fillMaxWidth().focusRing(CardShape)
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
                stringResource(R.string.home_routing, name.ifBlank { stringResource(R.string.home_routing_builtin) }),
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

internal fun routingFor(
    sub: SubscriptionEntity,
    profiles: List<com.ars3nb.volta.data.model.RoutingProfileEntity>
): com.ars3nb.volta.data.model.RoutingProfileEntity? = com.ars3nb.volta.core.routing.RoutingResolver.resolve(sub, profiles)
