package com.ars3nb.volta.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
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
 * How much went through the VPN: today, this month and last month, and the last two
 * weeks as small bars with today's in copper.
 */
@Composable
fun TrafficCard(summary: TrafficSummary, modifier: Modifier = Modifier) {
    val months = stringArrayResource(R.array.home_months)
    VoltaCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.home_traffic_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Hint(stringResource(R.string.home_traffic_period))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth()) {
            TrafficFigure(stringResource(R.string.home_traffic_today), summary.today, Modifier.weight(1f))
            TrafficFigure(months.getOrElse(summary.thisMonthOf.value - 1) { "" }, summary.thisMonth, Modifier.weight(1f))
            TrafficFigure(months.getOrElse(summary.lastMonthOf.value - 1) { "" }, summary.lastMonth, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        DayBars(summary.recent, Modifier.fillMaxWidth().height(40.dp))
        Spacer(Modifier.height(10.dp))
        Hint(stringResource(R.string.home_traffic_note))
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
    val description = stringResource(R.string.home_traffic_bars_description)
    Canvas(modifier.semantics { contentDescription = description }) {
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
