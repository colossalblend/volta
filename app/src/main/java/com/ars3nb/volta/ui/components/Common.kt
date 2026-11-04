package com.ars3nb.volta.ui.components

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.focusRing
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
                Modifier.focusRing(CardShape).clickable(onClick = onClick).padding(contentPadding.dp)
            } else {
                Modifier.padding(contentPadding.dp)
            },
            content = content
        )
    }
}

/**
 * Top bars sit on the page itself and only take a surface once content scrolls under
 * them, so a screen reads as one sheet rather than a band over a body.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun barColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.surface
)

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
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
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
                maxLines = 1,
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

fun formatBytes(bytes: Long): String {
    val b = abs(bytes)
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    return when {
        b >= tb -> String.format(Locale.US, "%.2f ТБ", b / tb)
        b >= gb -> String.format(Locale.US, "%.1f ГБ", b / gb)
        b >= mb -> String.format(Locale.US, "%.0f МБ", b / mb)
        b >= kb -> String.format(Locale.US, "%.0f КБ", b / kb)
        else -> "$b Б"
    }
}

fun formatSpeed(bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024 ->
        String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024))

    bytesPerSecond >= 1024 -> String.format(Locale.US, "%.0f КБ/с", bytesPerSecond / 1024.0)
    else -> "$bytesPerSecond Б/с"
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
    val days = ((at - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        days < 0 -> "истекла"
        days == 0 -> "истекает сегодня"
        days == 1 -> "остался 1 день"
        days < 5 -> "осталось $days дня"
        else -> "осталось $days дн."
    }
}

fun formatLatency(ms: Long): String = if (ms < 0) "—" else "$ms мс"
