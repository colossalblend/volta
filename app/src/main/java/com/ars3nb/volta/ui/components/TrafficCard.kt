package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
import com.ars3nb.volta.core.stats.TrafficSummary
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.TABULAR
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import com.ars3nb.volta.ui.tv.focusRing
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * How much went through the VPN: today, this month and last month as three numbers,
 * and the last two weeks as bars. Tap a bar, or slide a finger along them, and the
 * numbers make way for that day: its date, its total and what came in and went out.
 * Tap the same bar again to go back. The bars grow in when the card appears and the
 * chosen one lifts and turns copper; with "remove animations" on, all of it is instant.
 * On a TV the bars take focus and the arrows walk the days.
 */
@Composable
fun TrafficCard(summary: TrafficSummary, modifier: Modifier = Modifier) {
    val months = stringArrayResource(R.array.home_months)
    val reduced = rememberReducedMotion()
    val haptics = LocalHapticFeedback.current
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    // New data with fewer days (it never happens today, but stays safe) ignores a stale pick.
    val selected = picked?.takeIf { it in summary.recent.indices }

    fun pick(index: Int?) {
        if (index != selected) {
            picked = index
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.home_traffic_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Hint(stringResource(R.string.home_traffic_period))
        }
        Spacer(Modifier.height(12.dp))

        // The totals, or the chosen day in their place. Moving between days slides the
        // way the finger went; opening and closing a day rises and falls.
        AnimatedContent(
            targetState = selected,
            transitionSpec = { dayTransition(initialState, targetState, reduced) },
            label = "trafficHeadline"
        ) { index ->
            if (index == null) {
                Row(Modifier.fillMaxWidth()) {
                    TrafficFigure(stringResource(R.string.home_traffic_today), summary.today, Modifier.weight(1f))
                    TrafficFigure(months[summary.thisMonthOf.value - 1], summary.thisMonth, Modifier.weight(1f))
                    TrafficFigure(months[summary.lastMonthOf.value - 1], summary.lastMonth, Modifier.weight(1f))
                }
            } else {
                DayDetail(summary, index)
            }
        }

        Spacer(Modifier.height(14.dp))
        DayBars(
            values = summary.recent,
            selected = selected,
            reduced = reduced,
            onPick = { i -> pick(if (i == selected) null else i) },
            onScrub = { i -> pick(i) },
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
        )
        Spacer(Modifier.height(6.dp))
        BarDates(summary)
        Spacer(Modifier.height(10.dp))
        AnimatedContent(
            targetState = selected == null,
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 220)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) },
            label = "trafficHint"
        ) { idle ->
            Hint(stringResource(if (idle) R.string.home_traffic_tap_hint else R.string.home_traffic_note))
        }
    }
}

private fun AnimatedContentTransitionScope<Int?>.dayTransition(
    from: Int?,
    to: Int?,
    reduced: Boolean
): ContentTransform {
    if (reduced) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val spec = Motion.slide<androidx.compose.ui.unit.IntOffset>()
    return when {
        from != null && to != null -> {
            // Day to day: the newer day comes in from the right.
            val dir = if (to > from) 1 else -1
            (slideInHorizontally(spec) { it / 3 * dir } + fadeIn(tween(180))) togetherWith
                (slideOutHorizontally(spec) { -it / 3 * dir } + fadeOut(tween(120)))
        }
        to != null -> (slideInVertically(spec) { it / 2 } + fadeIn(tween(200))) togetherWith
            (slideOutVertically(spec) { -it / 2 } + fadeOut(tween(120)))
        else -> (slideInVertically(spec) { -it / 2 } + fadeIn(tween(200))) togetherWith
            (slideOutVertically(spec) { it / 2 } + fadeOut(tween(120)))
    }.using(SizeTransform(clip = false))
}

@Composable
private fun TrafficFigure(label: String, bytes: Long, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            formatBytes(screenResources(), bytes),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** The chosen day: its name, its total, and in/out with arrows. Read out when it changes. */
@Composable
private fun DayDetail(summary: TrafficSummary, index: Int) {
    val res = screenResources()
    val total = summary.recent.getOrElse(index) { 0L }
    val down = summary.recentDown.getOrElse(index) { 0L }
    val up = summary.recentUp.getOrElse(index) { 0L }
    val name = dayName(summary, index)
    val totalText = if (total > 0) formatBytes(res, total) else stringResource(R.string.home_traffic_no_traffic)
    val spoken = stringResource(
        R.string.home_traffic_day_description,
        name, totalText, formatBytes(res, down), formatBytes(res, up)
    )
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = spoken
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                totalText,
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
        if (total > 0) {
            Direction(Icons.Rounded.ArrowDownward, formatBytes(res, down), VoltaPaletteGood())
            Spacer(Modifier.width(14.dp))
            Direction(Icons.Rounded.ArrowUpward, formatBytes(res, up), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun VoltaPaletteGood(): Color = com.ars3nb.volta.ui.theme.VoltaTheme.palette.good

@Composable
private fun Direction(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = TABULAR),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** "Today", "Yesterday", or the weekday and date in the app's language. */
@Composable
private fun dayName(summary: TrafficSummary, index: Int): String {
    val last = summary.recent.lastIndex
    return when (index) {
        last -> stringResource(R.string.home_traffic_today)
        last - 1 -> stringResource(R.string.home_traffic_yesterday)
        else -> {
            val locale = LocalConfiguration.current.locales[0]
            val pattern = remember(locale) { DateTimeFormatter.ofPattern("EEE, d MMMM", locale) }
            LocalDate.ofEpochDay(summary.firstDay + index).format(pattern)
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
        }
    }
}

/** The first bar's date on the left, "Today" on the right, so the bars read as a calendar. */
@Composable
private fun BarDates(summary: TrafficSummary) {
    if (summary.recent.isEmpty()) return
    val locale = LocalConfiguration.current.locales[0]
    val pattern = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    Row(Modifier.fillMaxWidth()) {
        Text(
            LocalDate.ofEpochDay(summary.firstDay).format(pattern),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            stringResource(R.string.home_traffic_today),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * One bar per day, oldest on the left; a day without traffic is a low stub. The bars
 * grow in one after another when the card first shows, ease to new heights when the
 * numbers change, and the chosen bar turns copper and rises a little while the rest
 * step back.
 */
@Composable
private fun DayBars(
    values: List<Long>,
    selected: Int?,
    reduced: Boolean,
    onPick: (Int) -> Unit,
    onScrub: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val copper = scheme.primary
    val idle = scheme.surfaceVariant
    val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    val description = stringResource(R.string.home_traffic_bars_description)
    val count = values.size

    // Grow-in, once per card: 0 → 1 over the whole row, each bar starting a step later.
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) appear.animateTo(1f, tween(durationMillis = 700))
    }

    // Per bar: its height share and how "chosen" it is. Same number of calls every time.
    val heights = values.map { bytes ->
        animateFloatAsState(
            if (bytes <= 0L) 0f else bytes / peak,
            if (reduced) tween(0) else Motion.settle(),
            label = "barHeight"
        )
    }
    val lift = List(count) { i ->
        animateFloatAsState(
            when (selected) {
                null -> 0f
                i -> 1f
                else -> -1f
            },
            if (reduced) tween(0) else Motion.press(),
            label = "barLift"
        )
    }
    val todayColor by animateColorAsState(
        if (selected == null || selected == count - 1) copper else copper.copy(alpha = 0.45f),
        label = "todayBar"
    )

    val onPickNow by rememberUpdatedState(onPick)
    val onScrubNow by rememberUpdatedState(onScrub)
    fun indexAt(x: Float, width: Int): Int =
        if (count == 0 || width <= 0) 0 else (x / width * count).toInt().coerceIn(0, count - 1)

    Canvas(
        modifier
            .focusRing(RoundedCornerShape(12.dp), outset = 4.dp)
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || count == 0) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onScrubNow(((selected ?: count) - 1).coerceAtLeast(0)); true }
                    Key.DirectionRight -> {
                        if (selected == null) false else { onScrubNow((selected + 1).coerceAtMost(count - 1)); true }
                    }
                    Key.DirectionCenter, Key.Enter -> { onPickNow(selected ?: (count - 1)); true }
                    else -> false
                }
            }
            .pointerInput(count) {
                detectTapGestures { offset -> onPickNow(indexAt(offset.x, size.width)) }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onScrubNow(indexAt(change.position.x, size.width))
                }
            }
            .semantics { contentDescription = description }
    ) {
        if (count == 0) return@Canvas
        val gap = 4.dp.toPx()
        val barW = (size.width - gap * (count - 1)) / count
        val stub = 3.dp.toPx()
        val room = size.height - 6.dp.toPx() // headroom for the chosen bar's lift
        values.forEachIndexed { i, bytes ->
            // Staggered grow-in: bar i starts when the sweep reaches it.
            val start = i.toFloat() / (count + 3)
            val grow = ((appear.value - start) * (count + 3) / 4f).coerceIn(0f, 1f)
            val l = lift[i].value
            val share = heights[i].value
            val barH = maxOf(stub, room * share) * grow + if (l > 0f) 6.dp.toPx() * l else 0f
            val base = when {
                bytes <= 0L -> idle
                i == count - 1 -> todayColor
                else -> copper.copy(alpha = 0.38f)
            }
            val color = when {
                l > 0f && bytes > 0L -> lerpColor(base, copper, l)
                l < 0f -> base.copy(alpha = base.alpha * (1f + 0.45f * l))
                else -> base
            }
            val w = barW * (1f + 0.12f * l.coerceAtLeast(0f))
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap) - (w - barW) / 2f, size.height - barH),
                size = Size(w, barH),
                cornerRadius = CornerRadius(w / 3f, w / 3f)
            )
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = a.alpha + (b.alpha - a.alpha) * t
)
