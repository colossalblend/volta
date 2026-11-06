package com.ars3nb.volta.ui.components

import com.ars3nb.volta.ui.theme.rememberReducedMotion

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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
import com.ars3nb.volta.core.locale.asString
import com.ars3nb.volta.ui.PickSummary
import com.ars3nb.volta.ui.SpeedHistory
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.ui.theme.TABULAR
import com.ars3nb.volta.ui.theme.VoltaTheme

/**
 * Live speed, FLClash-style: download as a filled curve, upload as a thin line. Each
 * new sample slides in from the right over the 2 s until the next one, so the graph
 * moves continuously instead of jumping.
 */
@Composable
fun SpeedGraph(history: SpeedHistory, modifier: Modifier = Modifier, totals: TrafficStats? = null) {
    val palette = VoltaTheme.palette
    val downColor = palette.good
    val upColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    val peak = maxOf(history.down.maxOrNull() ?: 0L, history.up.maxOrNull() ?: 0L, 32L * 1024)
    // The scale eases to a new peak instead of snapping, so a burst doesn't jolt the curve.
    val scale by animateFloatAsState(peak * 1.15f, tween(700, easing = FastOutSlowInEasing), label = "scale")

    val still = rememberReducedMotion()
    val slide = remember { Animatable(0f) }
    LaunchedEffect(history.version) {
        if (still) return@LaunchedEffect
        slide.snapTo(1f)
        slide.animateTo(0f, tween(1_900, easing = LinearEasing))
    }

    // The live dot pulses; with «Lite» or "remove animations" it simply stays lit.
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulsing by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "dot"
    )
    val dot = if (still) 1f else pulsing

    val res = screenResources()
    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_speed),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            LegendDot(downColor, "↓ " + formatSpeed(res, history.down.lastOrNull() ?: 0))
            Spacer(Modifier.width(12.dp))
            LegendDot(upColor, "↑ " + formatSpeed(res, history.up.lastOrNull() ?: 0))
        }
        Spacer(Modifier.height(10.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(92.dp)
        ) {
            val slots = SpeedHistory.CAPACITY - 1
            val step = size.width / slots
            val h = size.height
            val top = 4.dp.toPx()

            for (i in 1..2) {
                val y = h * i / 3f
                drawLine(
                    gridColor,
                    Offset(0f, y),
                    Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                )
            }

            fun points(values: List<Long>): List<Offset> {
                val start = SpeedHistory.CAPACITY - values.size
                return values.mapIndexed { i, v ->
                    val y = h - (v / scale).coerceIn(0f, 1f) * (h - top)
                    Offset((start + i) * step, y)
                }
            }

            fun curve(pts: List<Offset>): Path = Path().apply {
                if (pts.isEmpty()) return@apply
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) {
                    val a = pts[i - 1]
                    val b = pts[i]
                    val mid = (a.x + b.x) / 2
                    cubicTo(mid, a.y, mid, b.y, b.x, b.y)
                }
            }

            val down = points(history.down)
            val up = points(history.up)
            clipRect {
                translate(left = slide.value * step) {
                    if (down.size >= 2) {
                        val line = curve(down)
                        val fill = Path().apply {
                            addPath(line)
                            lineTo(down.last().x, h)
                            lineTo(down.first().x, h)
                            close()
                        }
                        drawPath(
                            fill,
                            Brush.verticalGradient(
                                listOf(downColor.copy(alpha = 0.32f), downColor.copy(alpha = 0f))
                            )
                        )
                        drawPath(line, downColor, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round))
                    }
                    if (up.size >= 2) {
                        drawPath(
                            curve(up),
                            upColor.copy(alpha = 0.85f),
                            style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    down.lastOrNull()?.let { last ->
                        drawCircle(downColor.copy(alpha = 0.25f * dot), 9.dp.toPx(), last)
                        drawCircle(downColor, 3.5.dp.toPx(), last)
                    }
                }
            }
        }
        if (totals != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(
                    R.string.home_session_totals,
                    formatBytes(res, totals.downlinkBytes),
                    formatBytes(res, totals.uplinkBytes)
                ),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = TABULAR),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LegendDot(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(5.dp))
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (slideInVertically { it / 2 } + fadeIn()) togetherWith
                    (slideOutVertically { -it / 2 } + fadeOut())
            },
            label = "legend"
        ) { value ->
            Text(value, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

/**
 * Signal-style quality bars for a latency, like the ones next to a Wi-Fi network:
 * four bars under 120 ms, fewer as it climbs, none when the node did not answer.
 */
@Composable
fun SignalBars(ms: Long, modifier: Modifier = Modifier) {
    val palette = VoltaTheme.palette
    val level = when {
        ms < 0 -> 0
        ms < 120 -> 4
        ms < 250 -> 3
        ms < 500 -> 2
        else -> 1
    }
    val color by animateColorAsState(palette.latency(ms), label = "bars")
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = modifier.height(14.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..4) {
            val on = i <= level
            val fill by animateFloatAsState(if (on) 1f else 0f, tween(300, delayMillis = i * 60), label = "bar$i")
            Box(
                Modifier
                    .width(3.5.dp)
                    .fillMaxHeight(0.25f * i)
                    .clip(RoundedCornerShape(2.dp))
                    .background(lerp(off, color, fill))
            )
        }
    }
}

/**
 * "Why this node": the finalists of the last auto pick with their median and spread.
 * Bars grow in one after another, the chosen one is marked, so the automatic choice
 * is something you can see rather than take on faith.
 */
@Composable
fun AutoPickCard(summary: PickSummary, modifier: Modifier = Modifier) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val worst = (summary.rows.maxOfOrNull { it.medianMs } ?: 1L).coerceAtLeast(1L)

    VoltaCard(modifier = modifier) {
        Text(stringResource(R.string.home_pick_title), style = MaterialTheme.typography.titleSmall)
        // Which node and why, in a sentence; a quick pick has only this, no table yet.
        val reason = summary.reason.asString()
        if (reason.isNotBlank()) {
            Text(reason, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
        }
        if (summary.rows.isNotEmpty()) {
            Text(
                stringResource(R.string.home_pick_counts, summary.measured, summary.alive),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                summary.rows.forEachIndexed { index, row ->
                    val grow = remember(summary.at, row.serverId) { Animatable(0f) }
                    LaunchedEffect(summary.at, row.serverId) {
                        grow.animateTo(1f, tween(650, delayMillis = 120 * index, easing = FastOutSlowInEasing))
                    }
                    val share = (row.medianMs.toFloat() / worst).coerceIn(0.08f, 1f)
                    val barColor = if (row.chosen) palette.latency(row.medianMs) else scheme.outline
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(flagOf(row.name), modifier = Modifier.padding(end = 6.dp))
                            Text(
                                shownServerName(row.name),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (row.chosen) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                formatLatency(screenResources(), row.medianMs),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (row.chosen) palette.latency(row.medianMs) else scheme.onSurfaceVariant
                            )
                            if (row.chosen) {
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Rounded.CheckCircle,
                                    contentDescription = stringResource(R.string.home_pick_chosen),
                                    tint = palette.good,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape)
                                .background(scheme.surfaceVariant)
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(share * grow.value)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(barColor)
                            )
                        }
                    }
                }
            }
        }
    }
}
