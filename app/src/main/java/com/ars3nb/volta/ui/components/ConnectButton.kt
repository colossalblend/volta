package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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
 * The home screen centrepiece: a disc that fills up like a glass.
 *
 *  - idle: an empty disc with the Volta "V" in it and a faint orbit around it;
 *  - working: the disc fills with the accent colour, a soft wave on top, while a
 *    spark runs round the orbit trailing a tail; the "V" flips colour where the
 *    liquid covers it;
 *  - connected: the disc floods solid, one ripple leaves it and the spark slows
 *    to a lazy circle;
 *  - disconnecting drains it, an error leaves a red puddle and a short shake.
 *
 * Tapping sloshes the liquid a little, so the button feels like a physical thing.
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
    val draining = state == VpnConnectionState.DISCONNECTING
    val busy = working || state == VpnConnectionState.CONNECTING || draining
    val failed = state == VpnConnectionState.ERROR && !busy
    val up = connected && !busy
    val haptics = LocalHapticFeedback.current

    val liquidColor by animateColorAsState(
        targetValue = when {
            up -> palette.good
            failed -> palette.poor
            else -> scheme.primary
        },
        animationSpec = tween(520),
        label = "liquid"
    )
    val onLiquid by animateColorAsState(
        targetValue = if (up) palette.onAccent else scheme.onPrimary,
        animationSpec = tween(520),
        label = "onLiquid"
    )

    // How full the disc is: below 0 hides the wave entirely, above 1 floods it.
    val level = remember { Animatable(if (up) FULL else EMPTY) }
    LaunchedEffect(up, busy, draining, failed) {
        when {
            up -> level.animateTo(FULL, tween(750, easing = FastOutSlowInEasing))
            draining -> level.animateTo(EMPTY, tween(700, easing = FastOutSlowInEasing))
            // Rises fast, then creeps: the pick can take a while and must never look done.
            busy -> level.animateTo(0.74f, tween(6_000, easing = CubicBezierEasing(0.1f, 0.7f, 0.3f, 1f)))
            failed -> level.animateTo(0.16f, tween(600, easing = FastOutSlowInEasing))
            else -> level.animateTo(EMPTY, tween(650, easing = FastOutSlowInEasing))
        }
    }

    // A tap or a state change sloshes the liquid; it settles by itself.
    val slosh = remember { Animatable(0f) }
    LaunchedEffect(up, busy) {
        slosh.snapTo(1f)
        slosh.animateTo(0f, tween(1_400, easing = LinearEasing))
    }

    // One ripple and a haptic tick when the tunnel comes up.
    val burst = remember { Animatable(1f) }
    LaunchedEffect(up) {
        if (up) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            burst.snapTo(0f)
            burst.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing))
        }
    }

    // A failure gets a short "no" shake.
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failed) {
        if (failed) {
            for (x in listOf(10f, -8f, 6f, -4f, 2f, 0f)) shake.animateTo(x, tween(55))
        }
    }

    // The spark and the wave move at speeds that ease between states, so nothing jumps.
    val sparkSpeed by animateFloatAsState(
        targetValue = when {
            busy -> 330f
            up -> 40f
            else -> 18f
        },
        animationSpec = tween(1_000, easing = FastOutSlowInEasing),
        label = "sparkSpeed"
    )
    val waveSpeed by animateFloatAsState(if (busy) 4.2f else 1.6f, tween(800), label = "waveSpeed")
    val sparkAlpha by animateFloatAsState(if (busy || up) 1f else 0f, tween(600), label = "sparkAlpha")
    val tail by animateFloatAsState(if (busy) 1f else 0f, tween(700), label = "tail")
    val speeds by rememberUpdatedState(sparkSpeed to waveSpeed)
    var spark by remember { mutableFloatStateOf(-90f) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    spark = (spark + speeds.first * dt) % 360f
                    phase = (phase + speeds.second * dt) % (2f * PI.toFloat())
                }
                last = now
            }
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    LaunchedEffect(pressed) {
        if (pressed) slosh.animateTo(1f, tween(120))
        else slosh.animateTo(0f, tween(1_400, easing = LinearEasing))
    }
    val press by animateFloatAsState(
        targetValue = if (pressed) 0.93f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 420f),
        label = "press"
    )

    val description = when {
        busy -> "Подключение, подождите"
        connected -> "Отключить VPN"
        else -> "Подключить VPN"
    }

    val ringColor = scheme.outlineVariant
    val muted = scheme.onSurfaceVariant
    val discColor = palette.elevated

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer { translationX = shake.value.dp.toPx() }
    ) {
        // Orbit, spark and the connect ripple live outside the disc.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2, this.size.height / 2)
            val outer = this.size.minDimension / 2
            val orbit = outer * 0.86f
            val disc = outer * DISC

            drawCircle(ringColor.copy(alpha = 0.7f), orbit, center, style = Stroke(1.dp.toPx()))

            if (burst.value < 1f) {
                val t = burst.value
                drawCircle(
                    color = liquidColor.copy(alpha = (1f - t) * 0.5f),
                    radius = disc + (outer - disc) * t,
                    center = center,
                    style = Stroke(width = (3f * (1f - t) + 1f).dp.toPx())
                )
            }

            if (sparkAlpha > 0f) {
                if (tail > 0f) {
                    // The tail: short arcs fading out behind the spark.
                    val steps = 36
                    val span = 110f * tail
                    for (i in 0 until steps) {
                        val a = spark - 90f - span * (i + 1) / steps
                        drawArc(
                            color = liquidColor.copy(alpha = sparkAlpha * 0.9f * (1f - i / steps.toFloat())),
                            startAngle = a,
                            sweepAngle = span / steps + 0.5f,
                            useCenter = false,
                            topLeft = Offset(center.x - orbit, center.y - orbit),
                            size = Size(orbit * 2, orbit * 2),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                val rad = ((spark - 90f) * PI / 180f).toFloat()
                val dot = Offset(center.x + cos(rad) * orbit, center.y + sin(rad) * orbit)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(liquidColor.copy(alpha = 0.35f * sparkAlpha), Color.Transparent),
                        center = dot,
                        radius = 12.dp.toPx()
                    ),
                    radius = 12.dp.toPx(),
                    center = dot
                )
                drawCircle(liquidColor.copy(alpha = sparkAlpha), 4.dp.toPx(), dot)
            }
        }

        // The disc: background, liquid, then the "V" in two inks.
        Canvas(
            Modifier
                .size(size * DISC)
                .scale(press)
                .clip(CircleShape)
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
            val r = this.size.minDimension / 2
            val center = Offset(r, r)
            drawCircle(discColor, r, center)

            val amp = (1.5f + 2.5f * tail + 4f * slosh.value).dp.toPx()
            val surface = this.size.height * (1f - level.value)
            val front = wavePath(this.size, surface, amp, phase, r * 1.35f)
            val back = wavePath(this.size, surface - 3.dp.toPx(), amp, phase + PI.toFloat(), r * 1.1f)
            drawPath(back, liquidColor.copy(alpha = 0.45f))
            drawPath(front, liquidColor)
            // A faint highlight so the full disc doesn't read as flat paint.
            drawCircle(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                    startY = 0f,
                    endY = this.size.height * 0.6f
                ),
                radius = r,
                center = center
            )

            clipPath(front, ClipOp.Difference) { drawMark(center, r, muted) }
            clipPath(front) { drawMark(center, r, onLiquid) }

            drawCircle(ringColor, r - 0.5.dp.toPx(), center, style = Stroke(1.dp.toPx()))
        }
    }
}

private const val DISC = 0.62f
private const val EMPTY = -0.08f
private const val FULL = 1.12f

/** Liquid filling [size] up to [surface] (px from the top), its top a sine wave. */
private fun wavePath(size: Size, surface: Float, amp: Float, phase: Float, wavelength: Float): Path =
    Path().apply {
        moveTo(0f, size.height)
        var x = 0f
        while (x <= size.width) {
            lineTo(x, surface + amp * sin(2f * PI.toFloat() * x / wavelength + phase))
            x += 3f
        }
        lineTo(size.width, surface + amp * sin(2f * PI.toFloat() * size.width / wavelength + phase))
        lineTo(size.width, size.height)
        close()
    }

/** The Volta "V", same geometry as the launcher icon: a soft left arm, a solid right one. */
private fun DrawScope.drawMark(center: Offset, radius: Float, color: Color) {
    val s = radius * 0.80f / 46f
    val left = Offset(center.x - 17f * s, center.y - 17.5f * s)
    val bottom = Offset(center.x, center.y + 17.5f * s)
    val right = Offset(center.x + 17f * s, center.y - 17.5f * s)
    drawLine(color.copy(alpha = color.alpha * 0.6f), left, bottom, 11f * s, StrokeCap.Round)
    drawLine(color, bottom, right, 11f * s, StrokeCap.Round)
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
