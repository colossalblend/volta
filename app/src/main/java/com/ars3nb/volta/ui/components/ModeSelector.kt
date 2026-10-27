package com.ars3nb.volta.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.ui.theme.VoltaTheme

private val AUTO_MODES = listOf(ConnectMode.AUTO_HOME_LTE, ConnectMode.AUTO_HOME, ConnectMode.AUTO_LTE)

fun modeIcon(mode: ConnectMode): ImageVector = when (mode) {
    ConnectMode.AUTO_HOME_LTE -> Icons.Rounded.Autorenew
    ConnectMode.AUTO_HOME -> Icons.Rounded.Wifi
    ConnectMode.AUTO_LTE -> Icons.Rounded.SignalCellularAlt
    ConnectMode.MANUAL -> Icons.Rounded.Tune
}

/** Short label for the selector; "Авто" is said once in the caption above it. */
private fun modeShort(mode: ConnectMode): String = when (mode) {
    ConnectMode.AUTO_HOME_LTE -> "Home+LTE"
    ConnectMode.AUTO_HOME -> "Home"
    ConnectMode.AUTO_LTE -> "LTE"
    ConnectMode.MANUAL -> "Свой"
}

/**
 * The three auto groups as one track with a sliding thumb. The thumb springs to the
 * new position, so changing mode feels physical rather than a flat colour swap.
 * When the user picked a server by hand the thumb hides and a "Свой сервер" strip shows.
 */
@Composable
fun ModeSelector(
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
        shape = RoundedCornerShape(26.dp),
        color = scheme.surface,
        border = BorderStroke(1.dp, palette.cardBorder)
    ) {
        BoxWithConstraints(Modifier.padding(6.dp).height(78.dp)) {
            val slot = maxWidth / AUTO_MODES.size
            val thumbX by animateDpAsState(
                targetValue = slot * index.coerceAtLeast(0),
                animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
                label = "thumb"
            )
            val thumbColor = when (mode) {
                ConnectMode.AUTO_HOME -> palette.home
                ConnectMode.AUTO_LTE -> palette.lte
                else -> scheme.primary
            }
            val animatedThumb by animateColorAsState(thumbColor, label = "thumbColor")

            if (index >= 0) {
                Box(
                    Modifier
                        .offset(x = thumbX)
                        .width(slot)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(21.dp))
                        .background(animatedThumb.copy(alpha = if (palette.isDark) 0.18f else 0.14f))
                        .border(1.5.dp, animatedThumb.copy(alpha = 0.7f), RoundedCornerShape(21.dp))
                )
            }

            Row(Modifier.fillMaxSize()) {
                AUTO_MODES.forEach { option ->
                    val selected = option == mode
                    val tint by animateColorAsState(
                        if (selected) {
                            when (option) {
                                ConnectMode.AUTO_HOME -> palette.home
                                ConnectMode.AUTO_LTE -> palette.lte
                                else -> scheme.primary
                            }
                        } else scheme.onSurfaceVariant,
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
                            .clip(RoundedCornerShape(21.dp))
                            .semantics { this.selected = selected }
                            .clickable(role = Role.Tab) {
                                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(option)
                            }
                    ) {
                        Icon(modeIcon(option), null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(
                            modeShort(option),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            if (count > 0) "$count серв." else "нет серверов",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
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
                "Свой сервер: $serverName",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Вернуть авто",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
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
