package com.ars3nb.volta.ui.components

import androidx.compose.animation.animateColorAsState
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
import com.ars3nb.volta.data.model.NetworkRegime
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SiteCategory
import com.ars3nb.volta.data.model.SiteProbeResult
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.theme.VoltaTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ServerRow(
    server: ServerEntity,
    selected: Boolean,
    subscriptionName: String,
    supported: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val container by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.surface,
        label = "serverRow"
    )
    val shown = server.ewmaMs.takeIf { it >= 0 } ?: server.latencyMs
    val latencyColor = palette.latency(shown)

    Surface(
        shape = CardShape,
        color = container,
        border = androidx.compose.foundation.BorderStroke(
            if (selected) 1.5.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else palette.cardBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .clip(CardShape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Действия сервера",
                onLongClick = onLongClick
            )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (supported) MaterialTheme.colorScheme.surfaceVariant
                        else palette.poor.copy(alpha = 0.16f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (supported) {
                    Text(
                        server.protocol.label.take(2).uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        Icons.Rounded.Warning,
                        null,
                        tint = palette.poor,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (server.isFavorite) {
                        Icon(
                            Icons.Rounded.Star,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 4.dp).size(15.dp)
                        )
                    }
                    Text(
                        server.remark,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    buildString {
                        append(server.protocol.label)
                        if (server.security != "none") append(" · ${server.security}")
                        if (subscriptionName.isNotBlank()) append(" · $subscriptionName")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!supported) {
                    Text(
                        "Не поддерживается ядром",
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
                        Icon(
                            Icons.Rounded.Speed,
                            null,
                            modifier = Modifier.size(13.dp),
                            tint = latencyColor
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        formatLatency(shown),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (shown >= 0) latencyColor
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (selected) {
                    Icon(
                        Icons.Rounded.CheckCircle,
                        contentDescription = "Выбран",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp).size(16.dp)
                    )
                } else if (server.role != ServerRole.NORMAL) {
                    Text(
                        roleLabel(server.role),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

fun roleLabel(role: ServerRole): String = when (role) {
    ServerRole.NORMAL -> "Обычный"
    ServerRole.BYPASS_WHITELIST -> "Для обхода"
    ServerRole.AUTO -> "Универсальный"
}

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
                StatusPill(routingName, palette.good, icon = Icons.Rounded.Bolt)
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

@Composable
fun NetworkStatusCard(
    regime: NetworkRegime,
    probes: List<SiteProbeResult>,
    ip: String?,
    country: String?,
    onRecheck: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val (label, color) = when (regime) {
        NetworkRegime.OPEN -> "Интернет открыт" to palette.good
        NetworkRegime.WHITELIST_ONLY -> "Только «белый список»" to palette.fair
        NetworkRegime.BLOCKED -> "Сеть сильно ограничена" to palette.poor
        NetworkRegime.UNKNOWN -> "Сеть не проверена" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(label, color)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRecheck, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Refresh, "Проверить сеть", Modifier.size(17.dp))
            }
        }
        if (!ip.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Ваш IP: $ip" + if (!country.isNullOrBlank()) " · $country" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ProbeTile(
                title = "Зарубежные",
                items = probes.filter { it.category == SiteCategory.FOREIGN },
                icon = Icons.Rounded.Public,
                modifier = Modifier.weight(1f)
            )
            ProbeTile(
                title = "Российские",
                items = probes.filter { it.category == SiteCategory.RU_WHITELIST },
                icon = Icons.Rounded.Language,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProbeTile(
    title: String,
    items: List<SiteProbeResult>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val ok = items.count { it.reachable }
    val total = items.size
    StatTile(
        label = title,
        value = if (total == 0) "…" else "$ok из $total",
        icon = icon,
        valueColor = when {
            total == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
            ok == 0 -> palette.poor
            ok < total -> palette.fair
            else -> palette.good
        },
        modifier = modifier
    )
}
