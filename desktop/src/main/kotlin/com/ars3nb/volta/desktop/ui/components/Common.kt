package com.ars3nb.volta.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.desktop.i18n.I18n
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme
import java.util.Locale
import kotlin.math.abs

val CardShape = RoundedCornerShape(22.dp)
val SmallShape = RoundedCornerShape(14.dp)

/**
 * The one card style used across the app: a tonal fill a step above the background,
 * no border and no shadow. Only a selected card gets an outline, so an outline always
 * means "this one".
 */
@Composable
fun VoltaCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    contentPadding: Int = 16,
    content: @Composable ColumnScope.() -> Unit
) {
    val palette = VoltaTheme.palette
    val border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CardShape,
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = if (palette.isDark) 0.10f else 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = border
    ) {
        Column(
            modifier = if (onClick != null) {
                Modifier.clickable(onClick = onClick).padding(contentPadding.dp)
            } else {
                Modifier.padding(contentPadding.dp)
            },
            content = content
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        action?.invoke(this)
    }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** Compact status pill: a dot plus a label. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /** More than one line for a longer sentence; the pill then rounds less. */
    maxLines: Int = 1
) {
    Surface(
        modifier = modifier,
        shape = if (maxLines > 1) RoundedCornerShape(14.dp) else CircleShape,
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            } else {
                Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Number-first tile used in the home dashboard row. */
@Composable
fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface
) {
    Surface(
        modifier = modifier,
        shape = SmallShape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        icon,
                        null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(5.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Thin usage bar for subscription traffic. */
@Composable
fun UsageBar(used: Long, total: Long, modifier: Modifier = Modifier) {
    if (total <= 0) return
    val fraction = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    val palette = VoltaTheme.palette
    val color = when {
        fraction > 0.9f -> palette.poor
        fraction > 0.7f -> palette.fair
        else -> MaterialTheme.colorScheme.primary
    }
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        action?.invoke()
    }
}

// ------------------------------------------------------------------ formatting
// The same figures as on Android, with the units of the language in use.

fun formatBytes(bytes: Long): String {
    val u = I18n.s.byteUnits
    val b = abs(bytes)
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    return when {
        b >= tb -> String.format(Locale.US, "%.2f %s", b / tb, u[4])
        b >= gb -> String.format(Locale.US, "%.1f %s", b / gb, u[3])
        b >= mb -> String.format(Locale.US, "%.0f %s", b / mb, u[2])
        b >= kb -> String.format(Locale.US, "%.0f %s", b / kb, u[1])
        else -> "$b ${u[0]}"
    }
}

fun formatSpeed(bytesPerSecond: Long): String {
    val u = I18n.s.byteUnits
    val per = I18n.s.perSecond
    return when {
        bytesPerSecond >= 1024L * 1024 ->
            String.format(Locale.US, "%.1f %s%s", bytesPerSecond / (1024.0 * 1024), u[2], per)

        bytesPerSecond >= 1024 -> String.format(Locale.US, "%.0f %s%s", bytesPerSecond / 1024.0, u[1], per)
        else -> "$bytesPerSecond ${u[0]}$per"
    }
}

fun formatDuration(millis: Long): String {
    if (millis <= 0) return "—"
    val totalSeconds = millis / 1000
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%02d:%02d", m, s)
}

fun formatExpiry(at: Long): String {
    if (at <= 0) return ""
    val s = I18n.s
    val days = ((at - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        days < 0 -> s.expired
        days == 0 -> s.expiresToday
        else -> s.daysLeft(days)
    }
}

fun formatLatency(ms: Long): String = if (ms < 0) "—" else I18n.s.ms(ms)

/** "обновлена 5 мин назад". */
fun updatedLabel(at: Long): String {
    val s = I18n.s
    if (at <= 0) return s.neverUpdated
    val minutes = (System.currentTimeMillis() - at) / 60_000
    return when {
        minutes < 1 -> s.updatedJustNow
        minutes < 60 -> s.updatedMinutes(minutes)
        minutes < 60 * 24 -> s.updatedHours(minutes / 60)
        else -> s.updatedDays(minutes / (60 * 24))
    }
}
