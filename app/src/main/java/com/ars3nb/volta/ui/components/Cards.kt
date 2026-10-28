package com.ars3nb.volta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.theme.VoltaTheme

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
    inGroup: Boolean = false
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
    // The last measurement, the same figure the auto pick compares.
    val shown = server.latencyMs.takeIf { it >= 0 } ?: server.ewmaMs
    val latencyColor = palette.latency(shown)
    val shape = if (inGroup) RoundedCornerShape(14.dp) else CardShape

    Surface(
        shape = shape,
        color = container,
        border = when {
            inGroup -> null
            else -> androidx.compose.foundation.BorderStroke(
                if (selected) 1.5.dp else 1.dp,
                if (selected) scheme.primary else palette.cardBorder
            )
        },
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Действия сервера",
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

                    inGroup -> Text(
                        protocolBadge(server),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                        maxLines = 1
                    )

                    else -> Text(flagOf(server.remark), fontSize = 20.sp)
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
                    listOf(
                        subscriptionName.ifBlank { "Свой сервер" },
                        if (inGroup) transportLabel(server) else protocolBadge(server)
                    ).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supported) {
                    Text(
                        "Не поддерживается",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.poor
                    )
                } else if (server.failStreak > 0) {
                    Text(
                        "Не отвечал ${server.failStreak} раз(а)",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.fair
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shown >= 0) {
                        SignalBars(shown)
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        formatLatency(shown),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (shown >= 0) latencyColor else scheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(3.dp))
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Выбран",
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

/**
 * A country with its servers from every subscription. The header says how many there
 * are and how fast the best one answered; tapping it folds the list open or shut.
 */
@Composable
fun CountryCard(
    country: Countries.Country,
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
        if (current) scheme.primary.copy(alpha = 0.7f) else palette.cardBorder,
        label = "countryBorder"
    )

    Surface(
        shape = CardShape,
        color = scheme.surface,
        border = androidx.compose.foundation.BorderStroke(if (current) 1.5.dp else 1.dp, border),
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
                            country.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (current) {
                            Spacer(Modifier.width(8.dp))
                            StatusPill("Сейчас", scheme.primary)
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
                            "от $bestMs мс",
                            style = MaterialTheme.typography.labelSmall,
                            color = palette.latency(bestMs)
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Свернуть" else "Развернуть",
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

/** "1 сервер", "3 сервера", "12 серверов". */
fun serversCount(n: Int): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> "серверов"
        mod10 == 1 -> "сервер"
        mod10 in 2..4 -> "сервера"
        else -> "серверов"
    }
    return "$n $word"
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
    ProtocolType.RAW_JSON -> "JSON"
    ProtocolType.UNKNOWN -> "?"
}

/** "VLESS · Reality · XHTTP": what a row inside a country card says under the name. */
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
    return listOf(server.protocol.label, security, transport)
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

fun roleLabel(role: ServerRole): String = when (role) {
    ServerRole.NORMAL -> "Home"
    ServerRole.BYPASS_WHITELIST -> "LTE"
    ServerRole.AUTO -> "Балансер"
}

/** Group badge text and whether it is Home (true), LTE (false) or a balancer (null). */
fun groupLabel(role: ServerRole): Pair<String, Boolean?> = when (role) {
    ServerRole.NORMAL -> "HOME" to true
    ServerRole.BYPASS_WHITELIST -> "LTE" to false
    ServerRole.AUTO -> "БАЛАНСЕР" to null
}

/** The server's country flag (from its name), or a globe when it names none. */
fun flagOf(remark: String): String = Countries.of(remark).flag

@Composable
fun SubscriptionCard(
    sub: SubscriptionEntity,
    serverCount: Int,
    routingName: String,
    active: Boolean,
    onClick: () -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val dot = remember(sub.colorHex) {
        runCatching { Color(android.graphics.Color.parseColor(sub.colorHex)) }
            .getOrDefault(Color(0xFFC4784A))
    }

    VoltaCard(modifier = modifier, selected = active, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(9.dp))
            Text(
                sub.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onRefresh, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Refresh, "Обновить подписку", Modifier.size(18.dp))
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Edit, "Изменить подписку", Modifier.size(18.dp))
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusPill("$serverCount серв.", MaterialTheme.colorScheme.primary)
            if (routingName.isNotBlank()) {
                StatusPill(
                    routingName,
                    palette.good,
                    icon = Icons.Rounded.Bolt,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
        }

        if (sub.trafficTotal > 0) {
            Spacer(Modifier.height(10.dp))
            UsageBar(sub.trafficUsed, sub.trafficTotal)
            Spacer(Modifier.height(5.dp))
            Text(
                "${formatBytes(sub.trafficUsed)} из ${formatBytes(sub.trafficTotal)}" +
                    formatExpiry(sub.expireAt).let { if (it.isBlank()) "" else " · $it" },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (sub.expireAt > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                formatExpiry(sub.expireAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (sub.announce.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                sub.announce,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

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
        NetworkRegime.OPEN -> "Сеть Home" to palette.home
        NetworkRegime.WHITELIST_ONLY -> "Сеть LTE" to palette.lte
        NetworkRegime.BLOCKED -> "Нет интернета" to palette.poor
        NetworkRegime.UNKNOWN -> "Сеть не проверена" to MaterialTheme.colorScheme.onSurfaceVariant
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
                Icon(Icons.Rounded.Refresh, "Проверить сеть", Modifier.size(17.dp))
            }
        }
    }
}
