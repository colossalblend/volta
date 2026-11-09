package com.ars3nb.volta.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.IntOffset
import com.ars3nb.volta.desktop.ui.LocalLang
import com.ars3nb.volta.desktop.ui.theme.rememberReducedMotion
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallDesktop
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.stats.TrafficSummary
import com.ars3nb.volta.core.vpn.InternetCheck
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.stringArrayResource
import com.ars3nb.volta.desktop.i18n.stringResource
import com.ars3nb.volta.desktop.ui.theme.Motion
import com.ars3nb.volta.desktop.ui.theme.TABULAR
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme
import com.ars3nb.volta.desktop.update.UpdateInfo
import com.ars3nb.volta.desktop.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The Android app's home and settings cards that Windows lacked: the traffic card, the
 * no-internet note, the update banner and panel, and the OpenFlux captcha note. The same
 * layout and the same words (ui/components/TrafficCard.kt, InternetBanner.kt, Update.kt,
 * OpenFluxCard.kt).
 */

// ------------------------------------------------------------------ traffic

/**
 * How much went through the VPN: today, this month and last month as three numbers,
 * and the last two weeks as bars, as on Android (ui/components/TrafficCard.kt). Click a
 * bar, or drag the mouse along them, and the numbers make way for that day: its date,
 * its total and what came in and went out; click the same bar again (or Esc) to go
 * back. The bar under the pointer brightens; with the card focused, ← and → walk the
 * days. The bars grow in when the card appears and the chosen one lifts and turns
 * copper; with «Lite-оформление» all of it is instant.
 */
@Composable
fun TrafficCard(summary: TrafficSummary, modifier: Modifier = Modifier) {
    val months = stringArrayResource(R.array.home_months)
    val reduced = rememberReducedMotion()
    var picked by remember { mutableStateOf<Int?>(null) }
    // New data with fewer days (it never happens today, but stays safe) ignores a stale pick.
    val selected = picked?.takeIf { it in summary.recent.indices }

    fun pick(index: Int?) {
        if (index != selected) picked = index
    }

    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_traffic_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Hint(stringResource(R.string.home_traffic_period))
        }
        Spacer(Modifier.height(12.dp))

        // The totals, or the chosen day in their place. Moving between days slides the
        // way the pointer went; opening and closing a day rises and falls.
        AnimatedContent(
            targetState = selected,
            transitionSpec = { dayTransition(initialState, targetState, reduced) },
            label = "trafficHeadline"
        ) { index ->
            if (index == null) {
                Row(Modifier.fillMaxWidth()) {
                    TrafficFigure(stringResource(R.string.home_traffic_today), summary.today, Modifier.weight(1f))
                    TrafficFigure(months.getOrElse(summary.thisMonthOf.value - 1) { "" }, summary.thisMonth, Modifier.weight(1f))
                    TrafficFigure(months.getOrElse(summary.lastMonthOf.value - 1) { "" }, summary.lastMonth, Modifier.weight(1f))
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
            onClose = { pick(null) },
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
            Hint(stringResource(if (idle) R.string.desktop_traffic_tap_hint else R.string.home_traffic_note))
        }
    }
}

private fun AnimatedContentTransitionScope<Int?>.dayTransition(
    from: Int?,
    to: Int?,
    reduced: Boolean
): ContentTransform {
    if (reduced) return fadeIn(tween(0)) togetherWith fadeOut(tween(0))
    val spec = Motion.slide<IntOffset>()
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
            formatBytes(bytes),
            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = TABULAR),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1
        )
    }
}

/** The chosen day: its name, its total, and in/out with arrows. Read out when it changes. */
@Composable
private fun DayDetail(summary: TrafficSummary, index: Int) {
    val total = summary.recent.getOrElse(index) { 0L }
    val down = summary.recentDown.getOrElse(index) { 0L }
    val up = summary.recentUp.getOrElse(index) { 0L }
    val name = dayName(summary, index)
    val totalText = if (total > 0) formatBytes(total) else stringResource(R.string.home_traffic_no_traffic)
    val spoken = stringResource(R.string.home_traffic_day_description, name, totalText, formatBytes(down), formatBytes(up))
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
            Direction(Icons.Rounded.ArrowDownward, formatBytes(down), VoltaTheme.palette.good)
            Spacer(Modifier.width(14.dp))
            Direction(Icons.Rounded.ArrowUpward, formatBytes(up), MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Direction(icon: ImageVector, text: String, tint: Color) {
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

/** "Today", "Yesterday", or the weekday and date in the window's language. */
@Composable
private fun dayName(summary: TrafficSummary, index: Int): String {
    val last = summary.recent.lastIndex
    return when (index) {
        last -> stringResource(R.string.home_traffic_today)
        last - 1 -> stringResource(R.string.home_traffic_yesterday)
        else -> {
            val locale = LocalLang.current.locale
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
    val locale = LocalLang.current.locale
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
 * step back. The bar under the mouse brightens, so it is clear which day a click opens.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DayBars(
    values: List<Long>,
    selected: Int?,
    reduced: Boolean,
    onPick: (Int) -> Unit,
    onScrub: (Int) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val copper = scheme.primary
    val idle = scheme.surfaceVariant
    val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    val description = stringResource(R.string.home_traffic_bars_description)
    val count = values.size
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Int?>(null) }

    // Grow-in, once per card: 0 → 1 over the whole row, each bar starting a step later.
    val appear = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!reduced) appear.animateTo(1f, tween(durationMillis = 700))
    }

    // Per bar: its height share, how "chosen" it is and how lit by the pointer.
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
    val glow = List(count) { i ->
        animateFloatAsState(if (hovered == i && selected != i) 1f else 0f, if (reduced) tween(0) else tween(140), label = "barHover")
    }
    val todayColor by animateColorAsState(
        if (selected == null || selected == count - 1) copper else copper.copy(alpha = 0.45f),
        label = "todayBar"
    )
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(if (reduced) 0 else 160), label = "barsFocus")

    val onPickNow by rememberUpdatedState(onPick)
    val onScrubNow by rememberUpdatedState(onScrub)
    val onCloseNow by rememberUpdatedState(onClose)
    fun indexAt(x: Float, width: Int): Int =
        if (count == 0 || width <= 0) 0 else (x / width * count).toInt().coerceIn(0, count - 1)

    Canvas(
        modifier
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || count == 0) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionLeft -> { onScrubNow(((selected ?: count) - 1).coerceAtLeast(0)); true }
                    Key.DirectionRight -> {
                        if (selected == null) false else { onScrubNow((selected + 1).coerceAtMost(count - 1)); true }
                    }
                    Key.MoveHome -> { onScrubNow(0); true }
                    Key.MoveEnd -> { onScrubNow(count - 1); true }
                    Key.Enter, Key.NumPadEnter, Key.Spacebar -> { onPickNow(selected ?: (count - 1)); true }
                    Key.Escape -> if (selected != null) { onCloseNow(); true } else false
                    else -> false
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .onPointerEvent(PointerEventType.Move) { e ->
                hovered = e.changes.firstOrNull()?.position?.x?.let { indexAt(it, size.width) }
            }
            .onPointerEvent(PointerEventType.Exit) { hovered = null }
            .pointerInput(count) {
                detectTapGestures { offset ->
                    focus.requestFocus()
                    onPickNow(indexAt(offset.x, size.width))
                }
            }
            .pointerInput(count) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    onScrubNow(indexAt(change.position.x, size.width))
                    hovered = indexAt(change.position.x, size.width)
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
            val g = glow[i].value
            val share = heights[i].value
            val barH = maxOf(stub, room * share) * grow + if (l > 0f) 6.dp.toPx() * l else 0f
            val base = when {
                bytes <= 0L -> idle
                i == count - 1 -> todayColor
                else -> copper.copy(alpha = 0.38f)
            }
            val lit = when {
                l > 0f && bytes > 0L -> lerpColor(base, copper, l)
                l < 0f -> base.copy(alpha = base.alpha * (1f + 0.45f * l))
                else -> base
            }
            val color = if (g > 0f) lerpColor(lit, if (bytes > 0L) copper else scheme.outline, 0.45f * g) else lit
            val w = barW * (1f + 0.12f * l.coerceAtLeast(0f))
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barW + gap) - (w - barW) / 2f, size.height - barH),
                size = Size(w, barH),
                cornerRadius = CornerRadius(w / 3f, w / 3f)
            )
        }
        if (ring > 0f) {
            // The keyboard's place: a thin copper line under the row while it has focus.
            drawRoundRect(
                color = copper.copy(alpha = 0.5f * ring),
                topLeft = Offset(0f, size.height + 2.dp.toPx()),
                size = Size(size.width, 2.dp.toPx()),
                cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx())
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

// ------------------------------------------------------------------ no internet

/** A calm note when the VPN has nothing to go over. Nothing while the internet is fine. */
@Composable
fun InternetBanner(status: InternetCheck.Status, modifier: Modifier = Modifier) {
    if (status == InternetCheck.Status.OK) return
    val palette = VoltaTheme.palette
    VoltaCard(modifier = modifier, contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.WifiOff, palette.poor)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.network_none_title), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.network_none_text))
            }
        }
    }
}

// ------------------------------------------------------------------ OpenFlux

/** While an OpenFlux carrier waits for a check to be passed in the browser. */
@Composable
fun OpenFluxCaptchaBanner(captcha: OpenFluxRunner.Status.Captcha, onOpen: () -> Unit, onDone: () -> Unit) {
    VoltaCard(selected = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(Icons.Rounded.VerifiedUser, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.servers_openflux_captcha_title), style = MaterialTheme.typography.titleSmall)
                val carrier = OpenFluxLink.carrierName(captcha.carrier.substringBefore('-'))
                Hint(
                    if (captcha.proxy.isNotBlank()) stringResource(R.string.servers_openflux_captcha_on_server, carrier)
                    else carrier
                )
                Hint(stringResource(R.string.desktop_captcha_browser, stringResource(R.string.servers_captcha_done)))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Spacer(Modifier.weight(1f))
            if (captcha.url.startsWith("http", ignoreCase = true)) {
                TextButton(onClick = onOpen) { Text(stringResource(R.string.desktop_captcha_open)) }
            }
            TextButton(onClick = onDone) { Text(stringResource(R.string.servers_captcha_done)) }
        }
    }
}

// ------------------------------------------------------------------ updates

/** A newer build is there to download or install. */
val UpdateState.offersInstall: Boolean
    get() = this is UpdateState.Available || this is UpdateState.Downloading || this is UpdateState.Ready

/** The newer build the state is about, if any. */
val UpdateState.newBuild: UpdateInfo?
    get() = when (this) {
        is UpdateState.Available -> info
        is UpdateState.Downloading -> info
        is UpdateState.Ready -> info
        is UpdateState.Failed -> info
        else -> null
    }

/** On the home screen, only while there is a newer build to get or install. */
@Composable
fun UpdateBanner(state: UpdateState, onInstall: () -> Unit) {
    val info = state.newBuild ?: return
    if (!state.offersInstall) return
    VoltaCard(contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(updateIcon(state), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_update_available, info.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Hint(bannerHint(state))
            }
            Spacer(Modifier.width(10.dp))
            UpdatePill(state, onCheck = {}, onInstall = onInstall)
        }
        UpdateTrack(state)
    }
}

/** The «О приложении» row: which version this is and what the update is doing. */
@Composable
fun UpdatePanel(state: UpdateState, currentVersion: String, onCheck: () -> Unit, onInstall: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val palette = VoltaTheme.palette
    val tint = when (state) {
        is UpdateState.UpToDate -> palette.good
        is UpdateState.Failed -> scheme.error
        else -> scheme.primary
    }
    Column {
        Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBubble(updateIcon(state), tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(panelTitle(state, currentVersion), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Hint(panelHint(state, currentVersion))
            }
            Spacer(Modifier.width(10.dp))
            UpdatePill(state, onCheck = onCheck, onInstall = onInstall)
        }
        UpdateTrack(state)
    }
}

/** The one thing to press: check, update, install or try again. A spinner while looking. */
@Composable
fun UpdatePill(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    when (state) {
        UpdateState.Checking -> Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }

        is UpdateState.Downloading -> Text(
            stringResource(R.string.settings_update_percent, (state.progress * 100).toInt()),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        is UpdateState.Available -> Pill(stringResource(R.string.settings_update_action_update), filled = true, onClick = onInstall)
        is UpdateState.Ready -> Pill(stringResource(R.string.settings_update_action_install), filled = true, onClick = onInstall)
        is UpdateState.Failed -> Pill(
            stringResource(R.string.settings_update_action_retry),
            filled = false,
            onClick = if (state.info != null) onInstall else onCheck
        )

        UpdateState.Idle, is UpdateState.UpToDate ->
            Pill(stringResource(R.string.settings_update_action_check), filled = false, onClick = onCheck)
    }
}

@Composable
private fun Pill(text: String, filled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = VoltaTheme.palette.isDark
    val fill by animateColorAsState(
        if (filled) scheme.primary else scheme.primary.copy(alpha = if (dark) 0.18f else 0.14f),
        label = "pillFill"
    )
    val ink: Color = if (filled) scheme.onPrimary else scheme.primary
    Surface(onClick = onClick, shape = CircleShape, color = fill) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = ink,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

/** A thin rounded track under the row while the file comes in. */
@Composable
fun UpdateTrack(state: UpdateState) {
    val downloading = state is UpdateState.Downloading
    val target = (state as? UpdateState.Downloading)?.progress ?: 1f
    val progress by animateFloatAsState(target, Motion.settle(), label = "updateTrack")
    AnimatedVisibility(visible = downloading, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)) {
                Box(
                    Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private fun updateIcon(state: UpdateState): ImageVector = when (state) {
    is UpdateState.UpToDate -> Icons.Rounded.CheckCircle
    is UpdateState.Available, is UpdateState.Downloading -> Icons.Rounded.Download
    is UpdateState.Ready -> Icons.Rounded.InstallDesktop
    is UpdateState.Failed -> Icons.Rounded.ErrorOutline
    UpdateState.Idle, UpdateState.Checking -> Icons.Rounded.SystemUpdate
}

@Composable
private fun panelTitle(state: UpdateState, current: String): String = when (state) {
    is UpdateState.UpToDate -> "Volta $current"
    is UpdateState.Available -> stringResource(R.string.settings_update_available, state.info.versionName)
    is UpdateState.Downloading -> stringResource(R.string.settings_update_downloading_version, state.info.versionName)
    is UpdateState.Ready -> stringResource(R.string.settings_update_downloaded, state.info.versionName)
    is UpdateState.Failed -> stringResource(R.string.settings_update_failed)
    UpdateState.Idle, UpdateState.Checking -> "Volta $current"
}

@Composable
private fun panelHint(state: UpdateState, current: String): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.settings_update_idle_hint)
    UpdateState.Checking -> stringResource(R.string.settings_update_checking)
    is UpdateState.UpToDate -> stringResource(R.string.settings_update_latest_at, clock(state.checkedAt))
    is UpdateState.Available -> stringResource(R.string.settings_update_current_size, current, formatBytes(state.info.asset.size))
    is UpdateState.Downloading -> stringResource(R.string.settings_update_downloading_hint)
    is UpdateState.Ready -> stringResource(R.string.desktop_update_quit)
    is UpdateState.Failed -> stringResource(
        if (state.message == R.string.settings_update_error_installer) R.string.desktop_update_error_installer else state.message
    )
}

@Composable
private fun bannerHint(state: UpdateState): String = when (state) {
    is UpdateState.Available -> stringResource(R.string.settings_update_download_size, formatBytes(state.info.asset.size))
    is UpdateState.Downloading -> stringResource(R.string.settings_update_downloading)
    is UpdateState.Ready -> stringResource(R.string.desktop_update_quit)
    else -> ""
}

private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
