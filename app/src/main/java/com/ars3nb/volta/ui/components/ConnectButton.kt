package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.theme.VoltaTheme

/**
 * The home screen centrepiece: a single tappable ring that shows connection state,
 * uptime and live throughput without the user having to read a status line.
 */
@Composable
fun ConnectHero(
    state: VpnConnectionState,
    serverName: String,
    subtitle: String,
    uptimeText: String,
    traffic: TrafficStats,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val connected = state == VpnConnectionState.CONNECTED
    val transitioning = state == VpnConnectionState.CONNECTING ||
        state == VpnConnectionState.DISCONNECTING
    val failed = state == VpnConnectionState.ERROR

    val accent by animateColorAsState(
        targetValue = when {
            connected -> palette.good
            transitioning -> MaterialTheme.colorScheme.primary
            failed -> palette.poor
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
        animationSpec = tween(450),
        label = "accent"
    )

    val infinite = rememberInfiniteTransition(label = "hero")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "sweep"
    )
    val breathe by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            tween(2600, easing = FastOutSlowInEasing),
            RepeatMode.Reverse
        ),
        label = "breathe"
    )
    val press by animateFloatAsState(
        targetValue = if (connected) 1f else 0.97f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 260f),
        label = "press"
    )

    val label = when (state) {
        VpnConnectionState.CONNECTED -> "Отключить"
        VpnConnectionState.CONNECTING -> "Подключение…"
        VpnConnectionState.DISCONNECTING -> "Отключение…"
        VpnConnectionState.ERROR -> "Повторить"
        VpnConnectionState.DISCONNECTED -> "Подключить"
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(228.dp)) {
            // Soft halo — only while the tunnel is actually up.
            if (connected) {
                Box(
                    Modifier
                        .size(212.dp)
                        .scale(breathe)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(accent.copy(alpha = 0.20f), Color.Transparent)
                            )
                        )
                )
            }

            androidx.compose.foundation.Canvas(Modifier.size(184.dp)) {
                val stroke = 8.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(
                    color = accent.copy(alpha = 0.16f),
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (transitioning) {
                    drawArc(
                        color = accent,
                        startAngle = sweep,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                } else if (connected) {
                    drawArc(
                        color = accent,
                        startAngle = -90f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(148.dp)
                    .scale(press)
                    .clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            if (connected) {
                                listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0.10f))
                            } else {
                                listOf(palette.elevated, MaterialTheme.colorScheme.surface)
                            }
                        )
                    )
                    .semantics { contentDescription = label }
                    .clickable(
                        enabled = enabled && !transitioning,
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, radius = 74.dp),
                        onClick = onClick
                    )
            ) {
                Icon(
                    Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(56.dp)
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            serverName.ifBlank { "Сервер не выбран" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (subtitle.isNotBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        AnimatedContent(
            targetState = connected,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "heroMeta"
        ) { isUp ->
            if (isUp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text(
                        uptimeText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SpeedLabel(
                        icon = Icons.Rounded.ArrowDownward,
                        value = formatSpeed(traffic.downlinkSpeed),
                        color = palette.good
                    )
                    SpeedLabel(
                        icon = Icons.Rounded.ArrowUpward,
                        value = formatSpeed(traffic.uplinkSpeed),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            } else {
                Spacer(Modifier.height(28.dp))
            }
        }
    }
}

@Composable
private fun SpeedLabel(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
