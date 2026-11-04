package com.ars3nb.volta.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.stats.TrafficSummary
import com.ars3nb.volta.ui.theme.TABULAR

/**
 * How much went through the VPN: today, this month and last month as three numbers,
 * and the last two weeks as small bars with today's in copper. Handy against a
 * provider's traffic limit or a mobile data plan.
 */
@Composable
fun TrafficCard(summary: TrafficSummary, modifier: Modifier = Modifier) {
    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Трафик через VPN",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            Hint("за 2 недели")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            TrafficFigure("Сегодня", summary.today, Modifier.weight(1f))
            TrafficFigure(summary.thisMonthName, summary.thisMonth, Modifier.weight(1f))
            TrafficFigure(summary.lastMonthName, summary.lastMonth, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        DayBars(
            summary.recent,
            Modifier
                .fillMaxWidth()
                .height(40.dp)
        )
        Spacer(Modifier.height(10.dp))
        Hint("Считается только то, что прошло через VPN-сервер")
    }
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

/** One bar per day, oldest on the left. A day without traffic is a low stub. */
@Composable
private fun DayBars(values: List<Long>, modifier: Modifier = Modifier) {
    val today = MaterialTheme.colorScheme.primary
    val past = today.copy(alpha = 0.38f)
    val idle = MaterialTheme.colorScheme.surfaceVariant
    val peak = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
    Canvas(modifier.semantics { contentDescription = "Трафик по дням за две недели" }) {
        if (values.isEmpty()) return@Canvas
        val gap = 4.dp.toPx()
        val barW = (size.width - gap * (values.size - 1)) / values.size
        val stub = 3.dp.toPx()
        values.forEachIndexed { i, bytes ->
            val barH = if (bytes <= 0L) stub else maxOf(stub, size.height * (bytes.toFloat() / peak))
            drawRoundRect(
                color = when {
                    bytes <= 0L -> idle
                    i == values.lastIndex -> today
                    else -> past
                },
                topLeft = Offset(i * (barW + gap), size.height - barH),
                size = Size(barW, barH),
                cornerRadius = CornerRadius(barW / 3f, barW / 3f)
            )
        }
    }
}
