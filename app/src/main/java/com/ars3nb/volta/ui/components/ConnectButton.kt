package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
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
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.theme.VoltaTheme
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The home screen centrepiece: one big "power dial".
 *
 *  - idle: a quiet dial with a ring of 72 ticks;
 *  - working (probing / measuring / connecting): the ticks charge up in a sweeping
 *    wave and sonar rings leave the button — the user sees *progress*, not a spinner;
 *  - connected: every tick lit, a one-off shock ring bursts out, then a slow breath;
 *  - error: the dial goes red so a failure is obvious from across the room.
 */
@Composable
fun PowerButton(
    state: VpnConnectionState,
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 264.dp
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val connected = state == VpnConnectionState.CONNECTED
    val busy = working || state == VpnConnectionState.CONNECTING ||
        state == VpnConnectionState.DISCONNECTING
    val failed = state == VpnConnectionState.ERROR && !busy
    val haptics = LocalHapticFeedback.current

    val accent by animateColorAsState(
        targetValue = when {
            connected && !busy -> palette.good
            busy -> scheme.primary
            failed -> palette.poor
            else -> scheme.onSurfaceVariant
        },
        animationSpec = tween(520),
        label = "accent"
    )

    val infinite = rememberInfiniteTransition(label = "dial")
    val sweep by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
        label = "sweep"
    )
    val breathe by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val sonar by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2100, easing = LinearEasing)),
        label = "sonar"
    )

    // How much of the tick ring is lit: 0 idle, 1 connected; eased between states.
    val charge by animateFloatAsState(
        targetValue = if (connected && !busy) 1f else 0f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "charge"
    )

    // One shock ring when the tunnel comes up, plus a confirming haptic tick.
    val burst = remember { Animatable(1f) }
    LaunchedEffect(connected) {
        if (connected) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            burst.snapTo(0f)
            burst.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        targetValue = when {
            pressed -> 0.92f
            busy -> 0.96f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 380f),
        label = "press"
    )

    val description = when {
        busy -> "Подключение, подождите"
        connected -> "Отключить VPN"
        else -> "Подключить VPN"
    }

    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2

            // Ambient glow: brighter while connected, pulsing with the breath.
            val glowAlpha = when {
                connected && !busy -> 0.20f + 0.10f * breathe
                busy -> 0.16f
                failed -> 0.14f
                else -> 0.06f
            }
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                    center = center,
                    radius = outer
                ),
                radius = outer,
                center = center
            )

            // Sonar rings while working: three, a third of a cycle apart.
            if (busy) {
                repeat(3) { i ->
                    val t = (sonar + i / 3f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - t) * 0.35f),
                        radius = outer * (0.60f + 0.40f * t),
                        center = center,
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // Shock ring right after connecting.
            if (burst.value < 1f) {
                val t = burst.value
                drawCircle(
                    color = accent.copy(alpha = (1f - t) * 0.6f),
                    radius = outer * (0.62f + 0.38f * t),
                    center = center,
                    style = Stroke(width = (6f * (1f - t) + 1f).dp.toPx())
                )
            }

            // Tick ring: the "voltage scale" of the dial.
            val ticks = 72
            val tickOuter = outer * 0.86f
            val tickInner = outer * 0.78f
            for (i in 0 until ticks) {
                val f = i / ticks.toFloat()
                val lit = when {
                    busy -> {
                        // A charging wave travels around the dial.
                        val d = ((f - sweep + 1f) % 1f)
                        (1f - d * 3f).coerceIn(0f, 1f)
                    }

                    else -> if (f <= charge) 1f else 0f
                }
                val major = i % 6 == 0
                val angle = (f * 2f * PI - PI / 2).toFloat()
                val inner = if (major) tickInner - 4.dp.toPx() else tickInner
                val start = Offset(center.x + cos(angle) * inner, center.y + sin(angle) * inner)
                val end = Offset(center.x + cos(angle) * tickOuter, center.y + sin(angle) * tickOuter)
                drawLine(
                    color = if (lit > 0f) {
                        accent.copy(alpha = 0.25f + 0.75f * lit)
                    } else {
                        scheme.outlineVariant.copy(alpha = 0.55f)
                    },
                    start = start,
                    end = end,
                    strokeWidth = if (major) 3.dp.toPx() else 2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Thin progress arc just inside the ticks while working.
            if (busy) {
                val r = outer * 0.70f
                rotate(degrees = sweep * 360f, pivot = center) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            listOf(Color.Transparent, accent, Color.Transparent),
                            center = center
                        ),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(r * 2, r * 2),
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }

        // The disc itself.
        val discColors = when {
            connected && !busy -> listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.70f))
            failed -> listOf(palette.poor.copy(alpha = 0.30f), palette.poor.copy(alpha = 0.12f))
            else -> listOf(palette.elevated, scheme.surface)
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size * 0.58f)
                .scale(press)
                .clip(CircleShape)
                .background(Brush.linearGradient(discColors))
                .semantics { contentDescription = description }
                .clickable(
                    enabled = enabled && !busy,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
        ) {
            AnimatedContent(
                targetState = connected && !busy,
                transitionSpec = {
                    (scaleIn(spring(dampingRatio = 0.5f), initialScale = 0.6f) + fadeIn()) togetherWith
                        (scaleOut(tween(150), targetScale = 0.6f) + fadeOut(tween(150)))
                },
                label = "dialIcon"
            ) { up ->
                Icon(
                    if (up) Icons.Rounded.Bolt else Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = if (up) palette.onAccent else accent,
                    modifier = Modifier.size(if (up) 72.dp else 64.dp)
                )
            }
        }
    }
}

/** Big state word under the dial, with a caption that animates between phases. */
@Composable
fun ConnectionHeadline(
    state: VpnConnectionState,
    phaseCaption: String,
    serverName: String,
    modifier: Modifier = Modifier
) {
    val title = when {
        phaseCaption.isNotBlank() -> "Подключаемся"
        state == VpnConnectionState.CONNECTED -> "Защищено"
        state == VpnConnectionState.CONNECTING -> "Подключаемся"
        state == VpnConnectionState.DISCONNECTING -> "Отключаемся"
        state == VpnConnectionState.ERROR -> "Не вышло"
        else -> "Не подключено"
    }
    val caption = when {
        phaseCaption.isNotBlank() -> phaseCaption
        state == VpnConnectionState.CONNECTED -> serverName
        state == VpnConnectionState.CONNECTING -> serverName.ifBlank { "Поднимаю туннель…" }
        state == VpnConnectionState.ERROR -> "Нажмите, чтобы попробовать снова"
        else -> "Нажмите на кнопку, чтобы включить VPN"
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "title"
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "caption"
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Uptime and live speeds, shown only while the tunnel is up. */
@Composable
fun LiveMeter(
    uptimeText: String,
    traffic: TrafficStats,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    Row(
        modifier = modifier.height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            uptimeText,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        SpeedLabel(Icons.Rounded.ArrowDownward, formatSpeed(traffic.downlinkSpeed), palette.good)
        SpeedLabel(Icons.Rounded.ArrowUpward, formatSpeed(traffic.uplinkSpeed), MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SpeedLabel(icon: ImageVector, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        Text(value, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
