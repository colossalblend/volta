package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularOff
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.focusRing

private val AUTO_MODES = listOf(ConnectMode.AUTO_HOME_LTE, ConnectMode.AUTO_HOME, ConnectMode.AUTO_LTE)

fun modeIcon(mode: ConnectMode): ImageVector = when (mode) {
    ConnectMode.AUTO_HOME_LTE -> Icons.Rounded.Autorenew
    ConnectMode.AUTO_HOME -> Icons.Rounded.Wifi
    ConnectMode.AUTO_LTE -> Icons.Rounded.SignalCellularAlt
    ConnectMode.MANUAL -> Icons.Rounded.Tune
}

/** Short label for the selector; "Auto" is said once in the caption above it. */
@Composable
private fun modeShort(mode: ConnectMode): String = when (mode) {
    ConnectMode.AUTO_HOME_LTE -> "Home+LTE"
    ConnectMode.AUTO_HOME -> "Home"
    ConnectMode.AUTO_LTE -> "LTE"
    ConnectMode.MANUAL -> stringResource(R.string.home_mode_manual_short)
}

/** "12 servers" under a group, "empty" when it has none. */
@Composable
private fun groupCount(count: Int): String =
    if (count > 0) pluralStringResource(R.plurals.home_servers_count, count, count)
    else stringResource(R.string.home_group_empty)

/**
 * The three auto groups as one track with a sliding thumb. The thumb springs to the
 * new position and takes the group's colour, so changing mode feels physical rather
 * than a flat colour swap. When the user picked a server by hand the thumb hides and a
 * "Your server" strip shows under the track.
 */
@Composable
fun ModeSelector(
    mode: ConnectMode,
    homeCount: Int,
    lteCount: Int,
    totalCount: Int,
    onSelect: (ConnectMode) -> Unit,
    modifier: Modifier = Modifier,
    /** Tapped the LTE side while there are no LTE servers. */
    onLteMissing: () -> Unit = {}
) {
    // No LTE server anywhere: the three groups fold into one, Home, and the LTE side
    // says why instead of offering a mode that would have nothing to connect to.
    AnimatedContent(
        targetState = lteCount > 0,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
        label = "lteFold",
        modifier = modifier.fillMaxWidth()
    ) { hasLte ->
        if (hasLte) {
            ThreeGroupSelector(mode, homeCount, lteCount, totalCount, onSelect)
        } else {
            HomeOnlySelector(mode, homeCount, onSelect, onLteMissing)
        }
    }
}

@Composable
private fun ThreeGroupSelector(
    mode: ConnectMode,
    homeCount: Int,
    lteCount: Int,
    totalCount: Int,
    onSelect: (ConnectMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val index = AUTO_MODES.indexOf(mode)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surface
    ) {
        BoxWithConstraints(Modifier.padding(4.dp).height(50.dp)) {
            val slot = maxWidth / AUTO_MODES.size
            val thumbX by animateDpAsState(
                targetValue = slot * index.coerceAtLeast(0),
                animationSpec = Motion.slide(),
                label = "thumb"
            )
            val thumbColor by animateColorAsState(groupTint(mode), label = "thumbColor")

            if (index >= 0) {
                Box(
                    Modifier
                        .offset(x = thumbX)
                        .width(slot)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(16.dp))
                        .background(thumbColor.copy(alpha = if (palette.isDark) 0.17f else 0.14f))
                )
            }

            Row(Modifier.fillMaxSize()) {
                AUTO_MODES.forEach { option ->
                    val selected = option == mode
                    val tint by animateColorAsState(
                        if (selected) groupTint(option) else scheme.onSurfaceVariant,
                        label = "modeTint"
                    )
                    val count = when (option) {
                        ConnectMode.AUTO_HOME -> homeCount
                        ConnectMode.AUTO_LTE -> lteCount
                        else -> totalCount
                    }
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .focusRing(RoundedCornerShape(16.dp))
                            .clip(RoundedCornerShape(16.dp))
                            .semantics { this.selected = selected }
                            .clickable(role = Role.Tab) {
                                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(option)
                            }
                    ) {
                        Text(
                            modeShort(option),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (selected) tint else scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            groupCount(count),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * The selector when the subscriptions carry no LTE server: Home fills most of the
 * track (every auto mode runs as Home now, and no site is probed), and a quiet LTE stub
 * on the right says "no servers" rather than disappearing without a word.
 */
@Composable
private fun HomeOnlySelector(
    mode: ConnectMode,
    homeCount: Int,
    onSelect: (ConnectMode) -> Unit,
    onLteMissing: () -> Unit
) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val auto = mode != ConnectMode.MANUAL
    val homeTint by animateColorAsState(if (auto) palette.home else scheme.onSurface, label = "homeTint")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = scheme.surface
    ) {
        Row(
            Modifier.padding(4.dp).height(50.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(0.68f)
                    .fillMaxHeight()
                    .focusRing(RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (auto) palette.home.copy(alpha = if (palette.isDark) 0.17f else 0.14f)
                        else Color.Transparent
                    )
                    .semantics { this.selected = auto }
                    .clickable(role = Role.Tab) {
                        // Home+LTE is stored: it runs as Home now and picks LTE up by
                        // itself the day the provider adds LTE servers.
                        if (!auto) {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onSelect(ConnectMode.AUTO_HOME_LTE)
                        }
                    }
                    .padding(horizontal = 14.dp)
            ) {
                Icon(Icons.Rounded.Wifi, null, tint = homeTint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.home_mode_auto_home),
                        style = MaterialTheme.typography.titleSmall,
                        color = homeTint,
                        maxLines = 1
                    )
                    Text(
                        groupCount(homeCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            // The missing group: dashed in spirit — outlined, muted, struck-out signal.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .weight(0.32f)
                    .fillMaxHeight()
                    .focusRing(RoundedCornerShape(16.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .border(1.dp, scheme.outlineVariant.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
                    .clickable(onClick = onLteMissing)
                    .padding(horizontal = 8.dp)
            ) {
                Icon(
                    Icons.Rounded.SignalCellularOff,
                    null,
                    tint = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(
                        "LTE",
                        style = MaterialTheme.typography.titleSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1
                    )
                    Text(
                        stringResource(R.string.home_group_no_servers),
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun groupTint(mode: ConnectMode): Color = when (mode) {
    ConnectMode.AUTO_HOME -> VoltaTheme.palette.home
    ConnectMode.AUTO_LTE -> VoltaTheme.palette.lte
    else -> MaterialTheme.colorScheme.primary
}

/** Shown under the selector while a hand-picked server overrides the auto groups. */
@Composable
fun ManualModeStrip(
    serverName: String,
    onBackToAuto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.Tune,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (serverName.isBlank()) {
                    stringResource(R.string.home_manual_chosen)
                } else {
                    stringResource(R.string.home_manual_server, serverName)
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.home_manual_back_to_auto),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .focusRing(RoundedCornerShape(8.dp), width = 2.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBackToAuto)
                    .padding(6.dp)
            )
        }
    }
}

/** Colour for a group badge; provider balancers get a neutral one. */
@Composable
fun groupColor(home: Boolean?): Color = when (home) {
    true -> VoltaTheme.palette.home
    false -> VoltaTheme.palette.lte
    null -> MaterialTheme.colorScheme.onSurfaceVariant
}
