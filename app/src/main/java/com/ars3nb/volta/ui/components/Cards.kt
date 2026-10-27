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
import androidx.compose.ui.unit.sp
import com.ars3nb.volta.core.ping.NetworkProbe
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
                    Text(flagOf(server.remark), fontSize = 20.sp)
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

fun roleLabel(role: ServerRole): String = when (role) {
    ServerRole.NORMAL -> "Home"
    ServerRole.BYPASS_WHITELIST -> "LTE"
    ServerRole.AUTO -> "Home + LTE"
}

/** Group badge text and whether it is the Home (true) or LTE (false) colour. */
fun groupLabel(role: ServerRole): Pair<String, Boolean> = when (role) {
    ServerRole.NORMAL -> "HOME" to true
    ServerRole.BYPASS_WHITELIST -> "LTE" to false
    ServerRole.AUTO -> "HOME+LTE" to true
}

/**
 * The country flag a provider put in the remark (🇩🇪 Germany), or a globe. Flags are
 * pairs of regional-indicator symbols, which live outside the BMP.
 */
fun flagOf(remark: String): String {
    var i = 0
    while (i < remark.length) {
        val cp = remark.codePointAt(i)
        val next = i + Character.charCount(cp)
        if (cp in 0x1F1E6..0x1F1FF && next < remark.length) {
            val cp2 = remark.codePointAt(next)
            if (cp2 in 0x1F1E6..0x1F1FF) {
                return String(Character.toChars(cp)) + String(Character.toChars(cp2))
            }
        }
        i = next
    }
    return "🌐"
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
    modifier: Modifier = Modifier,
    /** Happ geodata status, e.g. "свои geoip/geosite". Empty = bundled lists. */
    geoText: String = ""
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
            if (geoText.isNotBlank()) {
                StatusPill(geoText, palette.home, icon = Icons.Rounded.Public)
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
        NetworkRegime.OPEN -> "Сеть Home — открытый интернет" to palette.home
        NetworkRegime.WHITELIST_ONLY -> "Сеть LTE — работает только белый список" to palette.lte
        NetworkRegime.BLOCKED -> "Сеть не отвечает" to palette.poor
        NetworkRegime.UNKNOWN -> "Сеть ещё не проверена" to MaterialTheme.colorScheme.onSurfaceVariant
    }

    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(label, color, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onRecheck, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Rounded.Refresh, "Проверить сеть", Modifier.size(17.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProbeColumn(
                title = "Home",
                icon = Icons.Rounded.Public,
                hosts = NetworkProbe.HOME_HOSTS.map { it.first },
                probes = probes,
                tint = palette.home,
                modifier = Modifier.weight(1f)
            )
            ProbeColumn(
                title = "LTE",
                icon = Icons.Rounded.Language,
                hosts = NetworkProbe.LTE_HOSTS.map { it.first },
                probes = probes,
                tint = palette.lte,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ProbeColumn(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    hosts: List<String>,
    probes: List<SiteProbeResult>,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    Surface(
        modifier = modifier,
        shape = SmallShape,
        color = tint.copy(alpha = 0.07f)
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = tint, fontWeight = FontWeight.Bold)
            }
            hosts.forEach { host ->
                val probe = probes.firstOrNull { it.host == host }
                val dot = when {
                    probe == null -> MaterialTheme.colorScheme.outline
                    probe.reachable -> palette.good
                    else -> palette.poor
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        host,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (probe?.reachable == true) {
                        Text(
                            "${probe.latencyMs}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
