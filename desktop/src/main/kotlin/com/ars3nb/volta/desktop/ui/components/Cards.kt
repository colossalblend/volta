package com.ars3nb.volta.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ars3nb.volta.core.ping.LiveCheck
import com.ars3nb.volta.core.ping.SweepBoard
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ProtocolType
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.desktop.i18n.Lang
import com.ars3nb.volta.desktop.i18n.Strings
import com.ars3nb.volta.desktop.ui.LocalLang
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.theme.Motion
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme
import com.ars3nb.volta.desktop.ui.theme.rememberReducedMotion
import java.util.Locale

/**
 * A country, shown by its two letters in a round badge. Windows draws no flag emoji
 * (its emoji font has none), so where the Android app shows 🇩🇪 this shows DE, set in
 * the app's own face; a server with no country gets the globe.
 */
@Composable
fun CountryBadge(code: String, modifier: Modifier = Modifier, size: Dp = 44.dp, fill: Color? = null) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.size(size).clip(CircleShape).background(fill ?: scheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (code == Countries.UNKNOWN_CODE || code.length != 2) {
            Icon(Icons.Rounded.Public, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                code,
                style = MaterialTheme.typography.titleLarge,
                fontSize = (size.value * 0.34f).sp,
                fontWeight = FontWeight.SemiBold,
                color = scheme.onSurface,
                maxLines = 1
            )
        }
    }
}

/** A country's name in the language in use. */
fun countryName(code: String, lang: Lang, s: Strings): String {
    if (code == Countries.UNKNOWN_CODE) return s.otherCountries
    if (lang == Lang.RU) return Countries.byCode(code).name
    val name = Locale("", code).getDisplayCountry(lang.locale)
    return name.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code
}

/** Group badge text and whether it is Home (true), LTE (false) or a balancer (null). */
fun groupLabel(role: ServerRole, s: Strings): Pair<String, Boolean?> = when (role) {
    ServerRole.NORMAL -> s.modeHome to true
    ServerRole.BYPASS_WHITELIST -> s.modeLte to false
    ServerRole.AUTO -> s.balancer to null
}

/** "VLESS · Reality · XHTTP": what a row says about how it connects. */
fun transportLabel(server: ServerEntity): String {
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
    if (server.protocol == ProtocolType.AMNEZIAWG) {
        return com.ars3nb.volta.core.parser.AmneziaConfigs.generationLabel(server.network)
    }
    return listOf(server.protocol.label, security, transport).filter { it.isNotBlank() }.joinToString(" · ")
}

/**
 * One action in a server's menu. [content], when given, is drawn in its place instead of
 * a plain item and keeps the menu open (a check that shows its answer right there);
 * [close] shuts the menu from inside it.
 */
data class ServerAction(
    val label: String,
    val icon: ImageVector? = null,
    val danger: Boolean = false,
    val content: (@Composable (close: () -> Unit) -> Unit)? = null,
    val run: () -> Unit = {}
)

/**
 * One server. On its own it is a card with the country badge; inside a country card
 * ([inGroup]) it is a flat row. A right click (or the ⋮ button) opens its actions.
 */
@Composable
fun ServerRow(
    server: ServerEntity,
    selected: Boolean,
    subscriptionName: String,
    supported: Boolean,
    onClick: () -> Unit,
    actions: () -> List<ServerAction>,
    modifier: Modifier = Modifier,
    inGroup: Boolean = false,
    /** This row's answer in a running "check all" (see SweepBoard); null: the stored one. */
    live: LiveCheck? = null,
    /** The clock the stored figure's age is judged by; 0 never dims it. */
    now: Long = 0,
    /** The menu closed (to forget an answer shown in it). */
    onMenuClosed: () -> Unit = {}
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val s = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    fun closeMenu() {
        menu = false
        onMenuClosed()
    }
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

    Box(modifier) {
        Surface(
            shape = shape,
            color = container,
            border = if (selected && !inGroup) BorderStroke(1.5.dp, scheme.primary) else null,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .pointerInput(server.id) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                                menu = true
                            }
                        }
                    }
                }
                .clickable(onClick = onClick)
        ) {
            Row(
                Modifier.padding(
                    start = if (inGroup) 10.dp else 14.dp,
                    end = 4.dp,
                    top = if (inGroup) 8.dp else 12.dp,
                    bottom = if (inGroup) 8.dp else 12.dp
                ),
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
                    if (!supported) {
                        Icon(Icons.Rounded.Warning, null, tint = palette.poor, modifier = Modifier.size(18.dp))
                    } else {
                        val code = Countries.of(server.remark).code
                        if (inGroup || code == Countries.UNKNOWN_CODE) {
                            Text(
                                protocolBadge(server),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        } else {
                            Text(
                                code,
                                style = MaterialTheme.typography.titleSmall,
                                fontFamily = MaterialTheme.typography.titleLarge.fontFamily,
                                color = scheme.onSurface
                            )
                        }
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
                            Countries.withoutLeadingFlag(server.remark),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        if (inGroup) {
                            listOf(transportLabel(server), subscriptionName.ifBlank { s.addedManually })
                                .joinToString(" · ")
                        } else {
                            subscriptionName.ifBlank { s.addedManually }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!supported) {
                        Text(s.notOnWindows, style = MaterialTheme.typography.labelSmall, color = palette.poor)
                    } else if (server.failStreak > 0) {
                        Text(s.recentlyFailed, style = MaterialTheme.typography.labelSmall, color = palette.fair)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    LatencyChip(shown, stale, animate = live != null)
                    Spacer(Modifier.height(3.dp))
                    if (selected) {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = s.chosen,
                            tint = scheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        val (label, home) = groupLabel(server.role, s)
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = groupColor(home)
                        )
                    }
                }
                IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Rounded.MoreVert,
                        contentDescription = s.actionsHint,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        if (menu) {
            DropdownMenu(expanded = true, onDismissRequest = { closeMenu() }) {
                actions().forEach { action ->
                    val custom = action.content
                    if (custom != null) {
                        custom { closeMenu() }
                    } else {
                        val ink = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        val icon = action.icon
                        // Typed slot: a lambda built inside ?.let would not be @Composable.
                        val leading: (@Composable () -> Unit)? = if (icon == null) null else ({ Icon(icon, null, tint = ink) })
                        DropdownMenuItem(
                            text = {
                                Text(
                                    action.label,
                                    color = if (action.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            leadingIcon = leading,
                            onClick = {
                                closeMenu()
                                action.run()
                            }
                        )
                    }
                }
            }
        }
    }
}

/** What a row shows while its server waits its turn in a sweep. */
private const val PENDING_FIGURE = -2L

/**
 * A row's figure: signal bars and the time. In a sweep it waits as a faint dot and the
 * answer pops in (a short settle, nothing with «Lite»); a stale figure is faint.
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
 * A country with its servers from every subscription. The header says how many there
 * are and how fast the best one answered; clicking it folds the list open or shut.
 */
@Composable
fun CountryCard(
    code: String,
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
    val s = LocalStrings.current
    val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
    val border by animateColorAsState(
        if (current) scheme.primary.copy(alpha = 0.7f) else Color.Transparent,
        label = "countryBorder"
    )

    Surface(
        shape = CardShape,
        color = scheme.surface,
        border = BorderStroke(1.5.dp, border),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CountryBadge(code, size = 46.dp)
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
                            StatusPill(s.currentCountry, scheme.primary)
                        }
                    }
                    Text(s.servers(count), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
                if (bestMs >= 0) {
                    Column(horizontalAlignment = Alignment.End) {
                        SignalBars(bestMs)
                        Text(s.fromMs(bestMs), style = MaterialTheme.typography.labelSmall, color = palette.latency(bestMs))
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) s.collapse else s.expand,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.rotate(turn)
                )
            }
            if (expanded) {
                HorizontalDivider(color = palette.cardBorder)
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    content()
                }
            }
        }
    }
}

/** The subscription's own colour, as picked when it was added. */
fun subscriptionColor(sub: SubscriptionEntity): Color = parseHex(sub.colorHex) ?: Color(0xFFC4784A)

private fun parseHex(hex: String): Color? = runCatching {
    val v = hex.removePrefix("#")
    when (v.length) {
        6 -> Color(0xFF000000 or v.toLong(16))
        8 -> Color(v.toLong(16))
        else -> null
    }
}.getOrNull()

/** A coin in the subscription's colour with the first letter of its name. */
@Composable
fun SubscriptionCoin(sub: SubscriptionEntity, tint: Color = subscriptionColor(sub), size: Int = 42) {
    val initial = remember(sub.name) {
        sub.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "V"
    }
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = if (VoltaTheme.palette.isDark) 0.22f else 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        Text(initial, style = MaterialTheme.typography.titleLarge, fontSize = (size * 0.42f).sp, color = tint)
    }
}

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
    val s = LocalStrings.current
    val tint = subscriptionColor(sub)
    val total = homeCount + lteCount + autoCount

    VoltaCard(modifier = modifier, selected = active, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SubscriptionCoin(sub, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(updatedLabel(sub.lastUpdated), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onRefresh, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Rounded.Refresh, s.refreshSubscription, Modifier.size(20.dp))
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
                    if (sub.trafficTotal > 0) s.trafficOf(formatBytes(sub.trafficUsed), formatBytes(sub.trafficTotal))
                    else s.noTrafficLimit,
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
                if (sub.urlHidden) StatusPill(s.linkHidden, scheme.onSurfaceVariant, icon = Icons.Rounded.Lock)
                if (routingName.isNotBlank()) {
                    StatusPill(routingName, palette.good, icon = Icons.Rounded.Bolt, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }

        if (sub.announce.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().clip(CircleShape).background(tint.copy(alpha = 0.6f)))
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

/** How a subscription's servers split between the groups, in the group colours. */
@Composable
private fun GroupSplit(home: Int, lte: Int, auto: Int) {
    val palette = VoltaTheme.palette
    val s = LocalStrings.current
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val parts = listOf(
        Triple(home, palette.home, s.modeHome),
        Triple(lte, palette.lte, s.modeLte),
        Triple(auto, neutral, s.poolBalancers)
    ).filter { it.first > 0 }
    Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        parts.forEach { (n, color, _) ->
            Box(Modifier.weight(n.toFloat()).fillMaxHeight().clip(CircleShape).background(color))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEach { (n, color, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

/** What the network check found: Home, LTE or nothing, with a button to check again. */
@Composable
fun NetworkStatusCard(regime: NetworkRegime, onRecheck: () -> Unit, modifier: Modifier = Modifier) {
    val palette = VoltaTheme.palette
    val s = LocalStrings.current
    val (label, color) = when (regime) {
        NetworkRegime.OPEN -> s.networkHome to palette.home
        NetworkRegime.WHITELIST_ONLY -> s.networkLte to palette.lte
        NetworkRegime.BLOCKED -> s.networkNone to palette.poor
        NetworkRegime.UNKNOWN -> s.networkUnknown to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val icon = if (regime == NetworkRegime.OPEN) Icons.Rounded.Public else Icons.Rounded.Language
    VoltaCard(modifier = modifier, contentPadding = 12) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = color, modifier = Modifier.weight(1f))
            IconButton(onClick = onRecheck, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Refresh, s.recheckNetwork, Modifier.size(17.dp))
            }
        }
    }
}
