package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.theme.Metals
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.TABULAR
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * The home screen's one bold thing: a copper disc, like the plates of Volta's pile.
 *
 *  - off: bare copper, turned like a coin, with the power mark cut into it;
 *  - connecting: a spark runs round the rim and patina starts creeping out from where
 *    the finger touched;
 *  - on: the patina, the green that protects copper, has covered the disc, and faint
 *    rings leave it in time with the traffic going through the tunnel;
 *  - turning off scrapes the patina back; a failure flashes oxide red and shakes.
 *
 * [activity] is 0..1, how busy the tunnel is right now.
 */
@Composable
fun PowerButton(
    state: VpnConnectionState,
    working: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 260.dp,
    activity: Float = 0f
) {
    val connected = state == VpnConnectionState.CONNECTED
    val draining = state == VpnConnectionState.DISCONNECTING
    val busy = working || state == VpnConnectionState.CONNECTING || draining
    val failed = state == VpnConnectionState.ERROR && !busy
    val up = connected && !busy
    val haptics = LocalHapticFeedback.current
    val still = rememberReducedMotion()

    // Where the patina starts: the last touch, in the disc's own coordinates (0..1).
    var origin by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    // How much of the disc the patina covers, 0..1.
    val patina = remember { Animatable(if (up) 1f else 0f) }
    LaunchedEffect(up, busy, draining, failed) {
        when {
            still -> patina.snapTo(if (up) 1f else 0f)
            up -> patina.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
            draining -> patina.animateTo(0f, tween(650, easing = FastOutSlowInEasing))
            // Creeps and never quite arrives: picking a server can take a while.
            busy -> patina.animateTo(0.42f, tween(7_000, easing = CubicBezierEasing(0.1f, 0.6f, 0.3f, 1f)))
            else -> patina.animateTo(0f, tween(600, easing = FastOutSlowInEasing))
        }
    }

    val oxide = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(failed) {
        if (failed) {
            oxide.snapTo(0.85f)
            if (!still) for (x in listOf(10f, -8f, 6f, -4f, 2f, 0f)) shake.animateTo(x, tween(55))
            oxide.animateTo(0.3f, tween(700))
        } else {
            oxide.animateTo(0f, tween(300))
        }
    }

    // One ring and a tick on the hand when the tunnel comes up.
    val burst = remember { Animatable(1f) }
    LaunchedEffect(up) {
        if (up) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            if (!still) {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing))
            }
        }
    }

    val sparkAlpha by animateFloatAsState(if (busy) 1f else 0f, tween(450), label = "spark")
    val fieldAlpha by animateFloatAsState(
        targetValue = when {
            up -> 1f
            busy -> 0.45f
            else -> 0f
        },
        animationSpec = tween(800),
        label = "field"
    )
    val ringColor by animateColorAsState(
        if (up) Metals.patina else Metals.copper,
        tween(700),
        label = "ringColor"
    )

    // Two clocks: the spark's angle and the field rings' phase. Their speeds ease, so
    // nothing jumps when the state changes.
    val sparkSpeed by animateFloatAsState(if (busy) 300f else 60f, tween(900), label = "sparkSpeed")
    val fieldSpeed = 0.22f + 0.55f * activity.coerceIn(0f, 1f)
    val speeds by rememberUpdatedState(sparkSpeed to fieldSpeed)
    var spark by remember { mutableFloatStateOf(-90f) }
    var field by remember { mutableFloatStateOf(0f) }
    val animating = !still && (busy || up || sparkAlpha > 0f || fieldAlpha > 0f)
    LaunchedEffect(animating) {
        if (!animating) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    spark = (spark + speeds.first * dt) % 360f
                    field = (field + speeds.second * dt) % 1f
                }
                last = now
            }
        }
    }

    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.94f else 1f, Motion.press(), label = "press")

    val description = when {
        busy -> "Подключение, нажмите, чтобы отменить"
        connected -> "Отключить VPN"
        else -> "Подключить VPN"
    }
    val specks = remember { speckles() }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .graphicsLayer { translationX = shake.value.dp.toPx() }
    ) {
        // Around the disc: its shadow, the field rings, the connect ring and the spark.
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val outer = this.size.minDimension / 2
            val disc = outer * DISC

            drawCircle(
                brush = Brush.radialGradient(
                    listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent),
                    center = c + Offset(0f, disc * 0.12f),
                    radius = disc * 1.25f
                ),
                radius = disc * 1.25f,
                center = c + Offset(0f, disc * 0.12f)
            )

            if (fieldAlpha > 0f) {
                for (i in 0 until RINGS) {
                    val t = (field + i / RINGS.toFloat()) % 1f
                    val fade = (1f - t) * (1f - t)
                    drawCircle(
                        color = ringColor.copy(alpha = 0.32f * fade * fieldAlpha),
                        radius = disc + (outer - disc) * t,
                        center = c,
                        style = Stroke(width = 1.2.dp.toPx())
                    )
                }
            }

            if (burst.value < 1f) {
                val t = burst.value
                drawCircle(
                    color = Metals.patinaLight.copy(alpha = (1f - t) * 0.55f),
                    radius = disc + (outer - disc) * t,
                    center = c,
                    style = Stroke(width = (3f * (1f - t) + 1f).dp.toPx())
                )
            }

            if (sparkAlpha > 0f) drawSpark(c, disc + 7.dp.toPx(), spark, sparkAlpha)
        }

        // The disc itself.
        Canvas(
            Modifier
                .size(size * DISC)
                .scale(press)
                .clip(CircleShape)
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.firstOrNull { it.pressed && !it.previousPressed }?.let {
                                origin = Offset(
                                    (it.position.x / this.size.width).coerceIn(0f, 1f),
                                    (it.position.y / this.size.height).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                }
                .clickable(
                    // Tappable while connecting too: a second tap cancels the attempt.
                    enabled = enabled && !draining,
                    interactionSource = interaction,
                    indication = null,
                    role = Role.Button
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                }
        ) {
            val r = this.size.minDimension / 2
            val c = Offset(r, r)
            val from = Offset(origin.x * this.size.width, origin.y * this.size.height)
            val front = patina.value * (hypot(from.x - c.x, from.y - c.y) + r) * 1.04f

            drawCopper(c, r)
            if (front > 1f) drawPatina(c, r, from, front)
            drawSpecks(specks, c, r, from, front)

            // The mark turns patina-dark once the green has passed the middle.
            val passed = ((front - hypot(from.x - c.x, from.y - c.y)) / (r * 0.35f)).coerceIn(0f, 1f)
            drawPowerMark(c, r, lerp(Metals.copperInk, Metals.patinaInk, passed))

            if (oxide.value > 0f) {
                drawCircle(Metals.oxide.copy(alpha = oxide.value * 0.55f), r, c, blendMode = BlendMode.SrcAtop)
            }
            drawRim(c, r)
        }
    }
}

private const val DISC = 0.62f
private const val RINGS = 3

/** Bare copper: lit from the top left, with the faint rings of a turned blank. */
private fun DrawScope.drawCopper(c: Offset, r: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            0f to Metals.copperLight,
            0.55f to Metals.copper,
            1f to Metals.copperDark,
            center = c + Offset(-r * 0.35f, -r * 0.42f),
            radius = r * 1.7f
        ),
        radius = r,
        center = c
    )
    val turns = 16
    for (k in 1..turns) {
        drawCircle(
            color = if (k % 2 == 0) Color.White.copy(alpha = 0.035f) else Color.Black.copy(alpha = 0.04f),
            radius = r * k / turns,
            center = c,
            style = Stroke(width = 0.8.dp.toPx())
        )
    }
    // A brushed sheen that sits across the disc like light on a coin.
    drawCircle(
        brush = Brush.sweepGradient(
            0f to Color.Transparent,
            0.12f to Color.White.copy(alpha = 0.10f),
            0.25f to Color.Transparent,
            0.62f to Color.Transparent,
            0.75f to Color.White.copy(alpha = 0.07f),
            0.88f to Color.Transparent,
            1f to Color.Transparent,
            center = c
        ),
        radius = r,
        center = c
    )
}

/** Patina out to [front] px from [from], with a soft, uneven edge. */
private fun DrawScope.drawPatina(c: Offset, r: Float, from: Offset, front: Float) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        drawCircle(
            brush = Brush.radialGradient(
                0f to Metals.patinaLight,
                0.5f to Metals.patina,
                1f to Metals.patinaDark,
                center = c + Offset(-r * 0.35f, -r * 0.42f),
                radius = r * 1.7f
            ),
            radius = r,
            center = c
        )
        val soft = (r * 0.22f / front).coerceIn(0f, 0.9f)
        drawRect(
            brush = Brush.radialGradient(
                0f to Color.White,
                (1f - soft) to Color.White,
                1f to Color.Transparent,
                center = from,
                radius = front
            ),
            blendMode = BlendMode.DstIn
        )
        canvas.restore()
    }
}

private class Speck(val x: Float, val y: Float, val radius: Float, val alpha: Float, val light: Boolean)

/** Oxidation spots: a few run ahead of the patina's edge, the rest texture it. */
private fun speckles(): List<Speck> {
    val random = Random(7)
    return List(90) {
        val a = random.nextFloat() * 2f * PI.toFloat()
        val d = kotlin.math.sqrt(random.nextFloat()) * 0.94f
        Speck(
            x = cos(a) * d,
            y = sin(a) * d,
            radius = 0.006f + random.nextFloat() * 0.018f,
            alpha = 0.25f + random.nextFloat() * 0.45f,
            light = random.nextFloat() < 0.6f
        )
    }
}

private fun DrawScope.drawSpecks(specks: List<Speck>, c: Offset, r: Float, from: Offset, front: Float) {
    if (front <= 1f) return
    val reach = front + r * 0.16f
    specks.forEach { s ->
        val p = Offset(c.x + s.x * r, c.y + s.y * r)
        val d = hypot(p.x - from.x, p.y - from.y)
        val shown = ((reach - d) / (r * 0.16f)).coerceIn(0f, 1f)
        if (shown > 0f) {
            drawCircle(
                color = (if (s.light) Metals.patinaLight else Metals.patinaDark).copy(alpha = s.alpha * shown),
                radius = s.radius * r,
                center = p
            )
        }
    }
}

/** The power mark, cut into the metal: a dark stroke with a lit lower lip. */
private fun DrawScope.drawPowerMark(c: Offset, r: Float, ink: Color) {
    val radius = r * 0.30f
    val stroke = r * 0.085f
    val lip = Offset(0f, 1.2.dp.toPx())
    fun mark(offset: Offset, color: Color) {
        drawArc(
            color = color,
            startAngle = -58f,
            sweepAngle = 296f,
            useCenter = false,
            topLeft = Offset(c.x - radius, c.y - radius) + offset,
            size = Size(radius * 2, radius * 2),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        drawLine(
            color = color,
            start = Offset(c.x, c.y - radius * 1.18f) + offset,
            end = Offset(c.x, c.y - radius * 0.28f) + offset,
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
    }
    mark(lip, Color.White.copy(alpha = 0.22f))
    mark(Offset.Zero, ink.copy(alpha = 0.88f))
}

/** A thin bevel: light on the top left edge, shade on the bottom right. */
private fun DrawScope.drawRim(c: Offset, r: Float) {
    drawCircle(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.35f), Color.Transparent, Color.Black.copy(alpha = 0.35f)),
            start = Offset(c.x - r, c.y - r),
            end = Offset(c.x + r, c.y + r)
        ),
        radius = r - 0.75.dp.toPx(),
        center = c,
        style = Stroke(width = 1.5.dp.toPx())
    )
}

/** The spark on the rim, trailing a tail that fades behind it. */
private fun DrawScope.drawSpark(c: Offset, orbit: Float, angle: Float, alpha: Float) {
    val steps = 28
    val span = 120f
    for (i in 0 until steps) {
        val a = angle - 90f - span * (i + 1) / steps
        drawArc(
            color = Metals.copperLight.copy(alpha = alpha * 0.85f * (1f - i / steps.toFloat())),
            startAngle = a,
            sweepAngle = span / steps + 0.6f,
            useCenter = false,
            topLeft = Offset(c.x - orbit, c.y - orbit),
            size = Size(orbit * 2, orbit * 2),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
    val rad = ((angle - 90f) * PI / 180f).toFloat()
    val dot = Offset(c.x + cos(rad) * orbit, c.y + sin(rad) * orbit)
    drawCircle(
        brush = Brush.radialGradient(
            listOf(Metals.spark.copy(alpha = 0.55f * alpha), Color.Transparent),
            center = dot,
            radius = 14.dp.toPx()
        ),
        radius = 14.dp.toPx(),
        center = dot
    )
    drawCircle(Metals.spark.copy(alpha = alpha), 3.5.dp.toPx(), dot)
}

/** How busy the tunnel is, 0..1, from the download speed on a log scale. */
fun trafficActivity(traffic: TrafficStats): Float {
    val bps = (traffic.downlinkSpeed + traffic.uplinkSpeed).coerceAtLeast(0L)
    if (bps < 1024) return 0f
    // 1 KB/s -> 0, ~10 MB/s -> 1.
    return (ln(bps / 1024.0) / ln(10_000.0)).toFloat().coerceIn(0f, 1f)
}

/** The state in one word, big, and a line under it saying what happens next. */
@Composable
fun ConnectionHeadline(
    state: VpnConnectionState,
    phaseCaption: String,
    serverName: String,
    modifier: Modifier = Modifier,
    idleCaption: String = "Нажмите на диск, чтобы включить VPN"
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val title = when {
        phaseCaption.isNotBlank() -> "Подключаю"
        state == VpnConnectionState.CONNECTED -> "Защищено"
        state == VpnConnectionState.CONNECTING -> "Подключаю"
        state == VpnConnectionState.DISCONNECTING -> "Отключаю"
        state == VpnConnectionState.ERROR -> "Не вышло"
        else -> "Выключено"
    }
    val caption = when {
        phaseCaption.isNotBlank() -> phaseCaption
        state == VpnConnectionState.CONNECTED -> serverName
        state == VpnConnectionState.CONNECTING -> serverName.ifBlank { "Поднимаю туннель…" }
        state == VpnConnectionState.ERROR -> "Нажмите на диск, чтобы попробовать снова"
        else -> idleCaption
    }
    val titleColor by animateColorAsState(
        when (state) {
            VpnConnectionState.CONNECTED -> palette.good
            VpnConnectionState.ERROR -> palette.poor
            else -> scheme.onBackground
        },
        tween(600),
        label = "titleColor"
    )
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = title,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut()) using SizeTransform(clip = false)
            },
            label = "title"
        ) { text ->
            Text(text, style = MaterialTheme.typography.headlineMedium, color = titleColor)
        }
        Spacer(Modifier.height(6.dp))
        AnimatedContent(
            targetState = caption,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "caption"
        ) { text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        AnimatedVisibility(
            visible = phaseCaption.isNotBlank() || state == VpnConnectionState.CONNECTING,
            enter = fadeIn(tween(400, delayMillis = 600)) + expandVertically(),
            exit = fadeOut(tween(160)) + shrinkVertically()
        ) {
            Text(
                "Ещё одно нажатие отменит",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** Time on and live speeds, with figures that roll instead of flicker. */
@Composable
fun LiveMeter(
    uptimeText: String,
    traffic: TrafficStats,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val figures = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = TABULAR)
    Row(
        modifier = modifier.height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        RollingText(uptimeText, figures, scheme.onBackground)
        SpeedLabel(Icons.Rounded.ArrowDownward, formatSpeed(traffic.downlinkSpeed), palette.good, figures)
        SpeedLabel(Icons.Rounded.ArrowUpward, formatSpeed(traffic.uplinkSpeed), scheme.primary, figures)
    }
}

@Composable
private fun SpeedLabel(icon: ImageVector, value: String, color: Color, style: TextStyle) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(3.dp))
        RollingText(value, style, MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Text whose digits roll like an odometer when they change; everything else just
 * swaps. Positions count from the end, so "59:59" -> "1:00:00" lines up.
 */
@Composable
fun RollingText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val still = rememberReducedMotion()
    Row(modifier) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(
                    targetState = char,
                    transitionSpec = {
                        if (!still && targetState.isDigit() && initialState.isDigit()) {
                            (slideInVertically(tween(260)) { it } + fadeIn(tween(260))) togetherWith
                                (slideOutVertically(tween(260)) { -it } + fadeOut(tween(200))) using
                                SizeTransform(clip = true)
                        } else {
                            fadeIn(tween(120)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = true)
                        }
                    },
                    label = "digit"
                ) { c ->
                    Text(c.toString(), style = style, color = color)
                }
            }
        }
    }
}
