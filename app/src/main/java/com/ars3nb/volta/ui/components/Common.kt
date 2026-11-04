package com.ars3nb.volta.ui.components

import android.content.res.Resources
import androidx.annotation.StringRes
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
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
//
// Units and phrases come from strings_home.xml, in the language of the Resources given:
// a screen's (screenResources()) or a notification's. The forms without them are for
// plain functions with no Context at hand; they read [FormatText.resources], which
// MainActivity keeps pointed at its own resources, already in the app's language.
// Figures stay in Latin digits with a dot, as before, so they line up in every language.

/** Where the helpers without a Resources argument read their words; set by MainActivity. */
object FormatText {
    @Volatile
    var resources: Resources? = null
}

/** The screen's resources, in the language it is drawn in. */
@Composable
@ReadOnlyComposable
fun screenResources(): Resources {
    LocalConfiguration.current
    return LocalContext.current.resources
}

fun formatBytes(bytes: Long): String = formatBytes(FormatText.resources, bytes)

fun formatSpeed(bytesPerSecond: Long): String = formatSpeed(FormatText.resources, bytesPerSecond)

fun formatExpiry(at: Long): String = formatExpiry(FormatText.resources, at)

fun formatLatency(ms: Long): String = formatLatency(FormatText.resources, ms)

fun formatBytes(res: Resources?, bytes: Long): String {
    val b = abs(bytes)
    val kb = 1024.0
    val mb = kb * 1024
    val gb = mb * 1024
    val tb = gb * 1024
    return when {
        b >= tb -> unit(res, R.string.home_unit_tb, "TB", String.format(Locale.US, "%.2f", b / tb))
        b >= gb -> unit(res, R.string.home_unit_gb, "GB", String.format(Locale.US, "%.1f", b / gb))
        b >= mb -> unit(res, R.string.home_unit_mb, "MB", String.format(Locale.US, "%.0f", b / mb))
        b >= kb -> unit(res, R.string.home_unit_kb, "KB", String.format(Locale.US, "%.0f", b / kb))
        else -> unit(res, R.string.home_unit_b, "B", "$b")
    }
}

fun formatSpeed(res: Resources?, bytesPerSecond: Long): String = when {
    bytesPerSecond >= 1024L * 1024 -> unit(
        res, R.string.home_unit_mb_per_s, "MB/s",
        String.format(Locale.US, "%.1f", bytesPerSecond / (1024.0 * 1024))
    )

    bytesPerSecond >= 1024 -> unit(
        res, R.string.home_unit_kb_per_s, "KB/s",
        String.format(Locale.US, "%.0f", bytesPerSecond / 1024.0)
    )

    else -> unit(res, R.string.home_unit_b_per_s, "B/s", "$bytesPerSecond")
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

fun formatExpiry(res: Resources?, at: Long): String {
    if (at <= 0 || res == null) return ""
    val days = ((at - System.currentTimeMillis()) / (1000L * 60 * 60 * 24)).toInt()
    return when {
        days < 0 -> res.getString(R.string.home_expiry_expired)
        days == 0 -> res.getString(R.string.home_expiry_today)
        else -> res.getQuantityString(R.plurals.home_expiry_days_left, days, days)
    }
}

fun formatLatency(res: Resources?, ms: Long): String =
    if (ms < 0) "—" else unit(res, R.string.home_unit_ms, "ms", "$ms")

/** "48 ms": the figure with the language's own unit; international symbols without one. */
private fun unit(res: Resources?, @StringRes id: Int, symbol: String, figure: String): String =
    res?.getString(id, figure) ?: "$figure $symbol"
