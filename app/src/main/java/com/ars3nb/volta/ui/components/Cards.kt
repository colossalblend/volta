package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.rotate
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.parser.AmneziaConfigs
import com.ars3nb.volta.core.ping.LiveCheck
import com.ars3nb.volta.core.ping.SweepBoard
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.ProtocolType
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ars3nb.volta.R
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import com.ars3nb.volta.ui.tv.focusRing

/**
 * One server. On its own it is a card with the country flag; inside a country card
 * ([inGroup]) it is a flat row whose badge shows the protocol instead, since the flag
 * is already in the country header.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerRow(
    server: ServerEntity,
    selected: Boolean,
    subscriptionName: String,
    supported: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    inGroup: Boolean = false,
    /** This row's answer in a running "check all" (see SweepBoard); null: the stored one. */
    live: LiveCheck? = null,
    /** The clock the stored figure's age is judged by; 0 never dims it. */
    now: Long = 0
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when {
            selected -> scheme.primary.copy(alpha = if (inGroup) 0.14f else 0.12f)
            inGroup -> Color.Transparent
            else -> scheme.surface
        },
        label = "serverRow"
    )
    // The last measurement, the same figure the auto pick compares; during a sweep the
    // fresh answer, until the stored one catches up with it.
    val fresh = live?.takeIf { it.shownOver(server.lastPingAt) }
    val shown = when {
        fresh == null -> server.latencyMs.takeIf { it >= 0 } ?: server.ewmaMs
        fresh.pending -> PENDING_FIGURE
        else -> fresh.latencyMs
    }
    // A figure from long ago is faint: the network it was measured on may be gone.
    val stale = fresh == null && now > 0 && SweepBoard.isStale(server.lastPingAt, now)
    val shape = if (inGroup) RoundedCornerShape(14.dp) else CardShape
    // Read from the name once, not on every redraw of the row (a ping redraws it).
    val flag = remember(server.remark) { flagOf(server.remark) }

    Surface(
        shape = shape,
        color = container,
        // Only the chosen server is outlined, so an outline always means "this one".
        border = if (selected && !inGroup) androidx.compose.foundation.BorderStroke(1.5.dp, scheme.primary) else null,
        modifier = modifier
            .fillMaxWidth()
            .focusRing(shape)
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.servers_row_actions),
                onLongClick = onLongClick
            )
    ) {
        Row(
            Modifier.padding(horizontal = if (inGroup) 10.dp else 14.dp, vertical = if (inGroup) 10.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(if (inGroup) 38.dp else 40.dp)
                    .clip(if (inGroup) RoundedCornerShape(11.dp) else CircleShape)
                    .background(
                        when {
                            !supported -> palette.poor.copy(alpha = 0.16f)
                            selected -> scheme.primary.copy(alpha = 0.18f)
                            else -> scheme.surfaceVariant
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when {
                    !supported -> Icon(
                        Icons.Rounded.Warning,
                        null,
                        tint = palette.poor,
                        modifier = Modifier.size(18.dp)
                    )

                    else -> Text(flag, fontSize = if (inGroup) 18.sp else 20.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (server.isFavorite) {
                        Icon(
                            Icons.Rounded.Star,
                            null,
                            tint = scheme.primary,
                            modifier = Modifier.padding(end = 4.dp).size(15.dp)
                        )
                    }
                    Text(
                        shownServerName(server.remark),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Where it came from, in words; the connection details stay out of sight.
                Text(
                    subscriptionName.ifBlank { stringResource(R.string.servers_added_manually) },
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supported) {
                    Text(
                        stringResource(R.string.servers_unsupported),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.poor
                    )
                } else if (server.failStreak > 0) {
                    Text(
                        stringResource(R.string.servers_recently_failed),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.fair
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                LatencyChip(shown, stale, animate = live != null)
                Spacer(Modifier.height(3.dp))
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = stringResource(R.string.servers_selected),
                        tint = scheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    val (label, home) = groupLabel(server.role)
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = groupColor(home)
                    )
                }
            }
        }
    }
}

/** What a row shows while its server waits its turn in a sweep. */
private const val PENDING_FIGURE = -2L

/**
 * A row's figure: signal bars and the time. In a sweep it waits as a faint dot and the
 * answer pops in (a short settle, nothing with reduced motion); a stale figure is faint.
 */
@Composable
private fun LatencyChip(shown: Long, stale: Boolean, animate: Boolean) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val still = rememberReducedMotion()
    val fade by animateFloatAsState(if (stale) 0.45f else 1f, Motion.settle(), label = "stale")
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            if (still || !animate) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (scaleIn(Motion.press(), initialScale = 0.6f) + fadeIn(Motion.settle())) togetherWith
                    fadeOut(Motion.settle()) using SizeTransform(clip = false)
            }
        },
        modifier = Modifier.graphicsLayer { alpha = fade },
        label = "latency"
    ) { figure ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (figure == PENDING_FIGURE) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp, horizontal = 6.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(scheme.onSurfaceVariant.copy(alpha = 0.4f))
                )
            } else {
                if (figure >= 0) {
                    SignalBars(figure)
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    formatLatency(figure),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (figure >= 0) palette.latency(figure) else scheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * A country with its servers from every subscription. The header says how many there
 * are and how fast the best one answered; tapping it folds the list open or shut.
 */
@Composable
fun CountryCard(
    country: Countries.Country,
    /** The country's name in the app's language (see [countryName]). */
    name: String,
    count: Int,
    bestMs: Long,
    expanded: Boolean,
    current: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val border by animateColorAsState(
        if (current) scheme.primary.copy(alpha = 0.7f) else Color.Transparent,
        label = "countryBorder"
    )

    Surface(
        shape = CardShape,
        color = scheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(CardShape)
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(country.flag, fontSize = 25.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (current) {
                            Spacer(Modifier.width(8.dp))
                            StatusPill(stringResource(R.string.servers_now), scheme.primary)
                        }
                    }
                    Text(
                        serversCount(count),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                if (bestMs >= 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        SignalBars(bestMs)
                        Text(
                            stringResource(R.string.servers_from_ms, bestMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.latency(bestMs)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.servers_collapse else R.string.servers_expand
                    ),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.rotate(turn)
                )
            }
            if (expanded) {
                HorizontalDivider(color = palette.cardBorder)
                Column(
                    Modifier.padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/** "1 server", "3 servers", in the app's language. */
@Composable
fun serversCount(n: Int): String = pluralStringResource(R.plurals.servers_count, n, n)

/**
 * A country's name in the app's language: Volta's short name for the few with long
 * official ones, the system's name for the rest.
 */
@Composable
fun countryName(country: Countries.Country): String {
    LocalConfiguration.current
    return Countries.name(LocalContext.current.resources, country.code)
}

/** An OpenFlux carrier as people know it ("Yandex Docs", "direct"), in the app's language. */
@Composable
fun carrierName(type: String): String {
    LocalConfiguration.current
    return OpenFluxLink.carrierName(LocalContext.current, type)
}

/** Short protocol name for a badge. */
fun protocolBadge(server: ServerEntity): String = when (server.protocol) {
    ProtocolType.VLESS -> "VLESS"
    ProtocolType.VMESS -> "VMess"
    ProtocolType.TROJAN -> "Trojan"
    ProtocolType.SHADOWSOCKS -> "SS"
    ProtocolType.SOCKS -> "SOCKS"
    ProtocolType.HTTP -> "HTTP"
    ProtocolType.WIREGUARD -> "WG"
    ProtocolType.HYSTERIA2 -> "HY2"
    ProtocolType.AMNEZIAWG -> if (server.network == "wg") "WG" else "AWG"
    ProtocolType.HYSTERIA -> "HY"
    ProtocolType.OPENFLUX -> "OF"
    ProtocolType.RAW_JSON -> "JSON"
    ProtocolType.UNKNOWN -> "?"
}

/**
 * "VLESS · Reality · XHTTP": what a row inside a country card says under the name.
 * An OpenFlux server names its carriers: "OpenFlux · Yandex Docs + direct".
 */
@Composable
fun transportLabel(server: ServerEntity): String {
    if (server.protocol == ProtocolType.OPENFLUX) {
        val context = LocalContext.current
        LocalConfiguration.current
        val carriers = server.network.split('+').filter { it.isNotBlank() }
            .joinToString(" + ") { OpenFluxLink.carrierName(context, it) }
        return listOf(server.protocol.label, carriers).filter { it.isNotBlank() }.joinToString(" · ")
    }
    if (server.protocol == ProtocolType.AMNEZIAWG) {
        // network holds the generation read from the .conf: awg3, awg2, awg1.5, awg1, wg.
        return AmneziaConfigs.generationLabel(server.network)
    }
    val transport = when (server.network.lowercase()) {
        "ws" -> "WS"
        "grpc" -> "gRPC"
        "xhttp", "splithttp" -> "XHTTP"
        "httpupgrade" -> "HTTPUpgrade"
        "kcp", "mkcp" -> "mKCP"
        else -> ""
    }
    val security = when (server.security.lowercase()) {
        "reality" -> "Reality"
        "tls" -> "TLS"
        else -> ""
    }
    return listOf(server.protocol.label, security, transport)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

@Composable
fun roleLabel(role: ServerRole): String = when (role) {
    ServerRole.NORMAL -> "Home"
    ServerRole.BYPASS_WHITELIST -> "LTE"
    ServerRole.AUTO -> stringResource(R.string.servers_role_balancer)
}

/** Group badge text and whether it is Home (true), LTE (false) or a balancer (null). */
@Composable
fun groupLabel(role: ServerRole): Pair<String, Boolean?> = when (role) {
    ServerRole.NORMAL -> "Home" to true
    ServerRole.BYPASS_WHITELIST -> "LTE" to false
    ServerRole.AUTO -> stringResource(R.string.servers_role_balancer) to null
}

/** The server's country flag (from its name), or a globe when it names none. */
fun flagOf(remark: String): String = Countries.of(remark).flag

/**
 * One subscription: who it is (a coin in its colour with its initial), how its servers
 * split into Home, LTE and provider balancers, and how much traffic is left. The whole
 * card opens the editor; the only button on it refreshes.
 */
@Composable
fun SubscriptionCard(
    sub: SubscriptionEntity,
    homeCount: Int,
    lteCount: Int,
    autoCount: Int,
    routingName: String,
    active: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val tint = subscriptionColor(sub)
    val total = homeCount + lteCount + autoCount

    VoltaCard(modifier = modifier, selected = active, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscriptionCoin(sub, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    shownSubscriptionName(sub),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    updatedLabel(sub.lastUpdated),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.servers_sub_refresh), Modifier.size(20.dp))
            }
        }

        if (total > 0) {
            Spacer(Modifier.height(14.dp))
            GroupSplit(homeCount, lteCount, autoCount)
        }

        if (sub.trafficTotal > 0 || sub.expireAt > 0) {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    if (sub.trafficTotal > 0) {
                        stringResource(
                            R.string.servers_sub_traffic,
                            formatBytes(sub.trafficUsed),
                            formatBytes(sub.trafficTotal)
                        )
                    } else {
                        stringResource(R.string.servers_sub_unlimited)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f)
                )
                if (sub.expireAt > 0) {
                    val days = ((sub.expireAt - System.currentTimeMillis()) / 86_400_000L).toInt()
                    Text(
                        formatExpiry(sub.expireAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            days < 3 -> palette.poor
                            days < 7 -> palette.fair
                            else -> scheme.onSurfaceVariant
                        }
                    )
                }
            }
            if (sub.trafficTotal > 0) {
                Spacer(Modifier.height(6.dp))
                UsageBar(sub.trafficUsed, sub.trafficTotal)
            }
        }

        if (sub.urlHidden || routingName.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sub.urlHidden) {
                    StatusPill(
                        stringResource(R.string.servers_sub_link_hidden),
                        scheme.onSurfaceVariant,
                        icon = Icons.Rounded.Lock
                    )
                }
                if (routingName.isNotBlank()) {
                    StatusPill(
                        routingName,
                        palette.good,
                        icon = Icons.Rounded.Bolt,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }

        if (sub.announce.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            // The provider's own words, set apart from the app's.
            Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                Box(
                    Modifier
                        .width(3.dp)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.6f))
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    sub.announce,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** The subscription's own colour, as picked when it was added. */
@Composable
fun subscriptionColor(sub: SubscriptionEntity): Color = remember(sub.colorHex) {
    runCatching { Color(android.graphics.Color.parseColor(sub.colorHex)) }
        .getOrDefault(Color(0xFFC4784A))
}

/** A coin in the subscription's colour with the first letter of its name. */
@Composable
fun SubscriptionCoin(sub: SubscriptionEntity, tint: Color = subscriptionColor(sub), size: Int = 42) {
    val name = shownSubscriptionName(sub)
    val initial = remember(name) {
        name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "V"
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (VoltaTheme.palette.isDark) 0.22f else 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            initial,
            style = MaterialTheme.typography.titleLarge,
            fontSize = (size * 0.42f).sp,
            color = tint
        )
    }
}

/**
 * How a subscription's servers split between the groups: one bar in the group colours,
 * each part as long as its share, and the counts under it in the same colours.
 */
@Composable
private fun GroupSplit(home: Int, lte: Int, auto: Int) {
    val palette = VoltaTheme.palette
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val parts = listOf(
        Triple(home, palette.home, "Home"),
        Triple(lte, palette.lte, "LTE"),
        Triple(auto, neutral, stringResource(R.string.servers_pool_balancers))
    ).filter { it.first > 0 }
    Row(
        Modifier.fillMaxWidth().height(6.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        parts.forEach { (n, color, _) ->
            Box(
                Modifier
                    .weight(n.toFloat())
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { (n, color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    n.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (color == neutral) MaterialTheme.colorScheme.onSurface else color
                )
            }
        }
    }
}

/** When a subscription was last refreshed: "updated 5 minutes ago". */
@Composable
fun updatedLabel(at: Long): String =
    if (at <= 0) stringResource(R.string.servers_sub_never_updated)
    else stringResource(
        R.string.servers_sub_updated,
        android.text.format.DateUtils.getRelativeTimeSpanString(
            at, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString().replaceFirstChar { it.lowercase() }
    )

/**
 * What the six probe hosts said: Home hosts on the left, LTE hosts on the right, one
 * dot per host. This is the evidence behind the Home / LTE verdict.
 */
@Composable
fun NetworkStatusCard(
    regime: NetworkRegime,
    probes: List<SiteProbeResult>,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val (label, color) = when (regime) {
        NetworkRegime.OPEN -> stringResource(R.string.servers_network_home) to palette.home
        NetworkRegime.WHITELIST_ONLY -> stringResource(R.string.servers_network_lte) to palette.lte
        NetworkRegime.BLOCKED -> stringResource(R.string.servers_network_offline) to palette.poor
        NetworkRegime.UNKNOWN ->
            stringResource(R.string.servers_network_unchecked) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = when (regime) {
        NetworkRegime.OPEN -> Icons.Rounded.Public
        else -> Icons.Rounded.Language
    }

    VoltaCard(modifier = modifier, contentPadding = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = color,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onRecheck, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Refresh, stringResource(R.string.servers_network_recheck), Modifier.size(17.dp))
            }
        }
    }
}
