package com.ars3nb.volta.desktop.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.gemini.GeminiChecks
import com.ars3nb.volta.core.gemini.GeminiProbe
import com.ars3nb.volta.core.gemini.GeminiReason
import com.ars3nb.volta.core.gemini.GeminiResult
import com.ars3nb.volta.core.gemini.GeminiStatus
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.desktop.ServerCheck
import com.ars3nb.volta.desktop.i18n.R
import com.ars3nb.volta.desktop.i18n.stringResource
import com.ars3nb.volta.desktop.ui.LocalLang
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.theme.Motion
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme
import com.ars3nb.volta.desktop.ui.theme.rememberReducedMotion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/*
 * The Gemini check on Windows, as on Android (ui/components/Gemini.kt): a row in a
 * server's menu that checks it and shows the verdict in place, and a line on the home
 * screen for the server in use. Same words, same colours, same reasons in small print.
 */

/** What a Gemini chip shows: a verdict, or that the check is running. */
private data class GeminiShown(val result: GeminiResult?, val running: Boolean)

/** The colour of a verdict: good, poor, or plain when nothing could be said. */
@Composable
private fun geminiColor(status: GeminiStatus?): Color = when (status) {
    GeminiStatus.AVAILABLE -> VoltaTheme.palette.good
    GeminiStatus.UNSUPPORTED_REGION -> VoltaTheme.palette.poor
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun geminiIcon(status: GeminiStatus?): ImageVector = when (status) {
    GeminiStatus.AVAILABLE -> Icons.Rounded.CheckCircle
    GeminiStatus.UNSUPPORTED_REGION -> Icons.Rounded.Block
    else -> Icons.AutoMirrored.Rounded.HelpOutline
}

/** «Gemini работает» or the other two verdicts, as the server menu words them. */
@Composable
private fun verdict(status: GeminiStatus): String = stringResource(
    when (status) {
        GeminiStatus.AVAILABLE -> R.string.gemini_available
        GeminiStatus.UNSUPPORTED_REGION -> R.string.gemini_unsupported
        GeminiStatus.BLOCKED_OR_ERROR -> R.string.gemini_failed
    }
)

/** The same verdicts in the short form of the home screen: «Gemini: работает». */
@Composable
private fun homeVerdict(status: GeminiStatus): String = stringResource(
    when (status) {
        GeminiStatus.AVAILABLE -> R.string.gemini_home_available
        GeminiStatus.UNSUPPORTED_REGION -> R.string.gemini_home_unsupported
        GeminiStatus.BLOCKED_OR_ERROR -> R.string.gemini_home_failed
    }
)

/** Why the verdict is what it is, in a few words; null when the verdict says it all. */
@Composable
private fun reasonText(result: GeminiResult): String? = when (result.reason) {
    GeminiReason.NETWORK, GeminiReason.TIMEOUT -> stringResource(R.string.gemini_reason_no_answer)
    GeminiReason.CAPTCHA, GeminiReason.FORBIDDEN -> stringResource(R.string.gemini_reason_blocked)
    GeminiReason.CONSENT -> stringResource(R.string.gemini_reason_consent)
    GeminiReason.HTTP_ERROR -> stringResource(R.string.gemini_reason_http, result.httpCode)
    GeminiReason.UNRECOGNIZED -> stringResource(R.string.gemini_reason_unrecognized)
    GeminiReason.FLAG_ON -> stringResource(R.string.gemini_reason_flag_on)
    GeminiReason.SUPPORTED_REGION -> stringResource(R.string.gemini_reason_supported_region)
    GeminiReason.UNSUPPORTED_REGION_LIST -> stringResource(R.string.gemini_reason_region_list)
    GeminiReason.NOT_AVAILABLE_TEXT -> stringResource(R.string.gemini_reason_text)
    GeminiReason.FLAG_OFF -> stringResource(R.string.gemini_reason_flag_off)
    GeminiReason.UNSURE -> stringResource(R.string.gemini_reason_unsure)
    GeminiReason.NEEDS_CONNECTION -> stringResource(R.string.gemini_reason_needs_connection)
    GeminiReason.CANNOT_DIAL -> stringResource(R.string.gemini_reason_cannot_dial)
    else -> null
}

/**
 * The country Google placed the request in, when the page named it. Windows draws no
 * flag emoji, so it is the country's name alone (as everywhere in the Windows app).
 */
@Composable
private fun regionText(result: GeminiResult): String? {
    val code = result.regionAlpha2 ?: return null
    return countryName(code, LocalLang.current, LocalStrings.current)
}

/**
 * A verdict as a pill, or a small ring while the check runs. A new answer arrives with a
 * slight scale and fade (none with «Lite»).
 */
@Composable
private fun GeminiPill(shown: GeminiShown, home: Boolean, modifier: Modifier = Modifier) {
    val still = rememberReducedMotion()
    AnimatedContent(
        targetState = shown,
        contentKey = { if (it.running) null else it.result?.status },
        transitionSpec = {
            if (still) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (scaleIn(Motion.press(), initialScale = 0.9f) + fadeIn(Motion.settle())) togetherWith
                    fadeOut(Motion.settle())
            }
        },
        label = "geminiPill",
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) { now ->
        val result = now.result
        if (now.running || result == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (home) R.string.gemini_home_checking else R.string.gemini_checking),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val region = regionText(result)
            val words = if (home) homeVerdict(result.status) else verdict(result.status)
            StatusPill(
                text = if (region != null && result.status != GeminiStatus.BLOCKED_OR_ERROR) "$words · $region" else words,
                color = geminiColor(result.status),
                icon = geminiIcon(result.status),
                maxLines = 2
            )
        }
    }
}

/**
 * «Проверить Gemini» in a server's menu: opens gemini.google.com through that server and
 * shows the verdict under the item, in place; the menu stays open. A server only a
 * helper carries (OpenFlux, AmneziaWG) can be checked only while connected to it.
 */
@Composable
fun GeminiMenuRow(server: ServerEntity, gemini: GeminiChecks, route: GeminiProbe.Route) {
    val results by gemini.results.collectAsState()
    val running by gemini.running.collectAsState()
    if (route == GeminiProbe.Route.NONE) return

    val result = results[server.id]
    val busy = server.id in running
    val canCheck = route != GeminiProbe.Route.ONLY_WHEN_CONNECTED
    val scheme = MaterialTheme.colorScheme
    val ink = if (canCheck) scheme.onSurface else scheme.onSurfaceVariant
    val still = rememberReducedMotion()

    Column(Modifier.widthIn(min = 260.dp, max = 360.dp)) {
        MenuRow(
            icon = Icons.Rounded.AutoAwesome,
            title = stringResource(R.string.gemini_check_action),
            subtitle = stringResource(if (canCheck) R.string.gemini_check_action_text else R.string.gemini_reason_needs_connection),
            ink = ink,
            enabled = canCheck && !busy,
            onClick = { gemini.check(server.id) }
        )
        AnimatedVisibility(visible = busy || result != null, enter = arrive(still), exit = leave(still)) {
            Column(Modifier.padding(start = 52.dp, end = 12.dp, bottom = 8.dp)) {
                GeminiPill(GeminiShown(result, busy), home = false)
                if (!busy && result != null) {
                    val detail = listOfNotNull(reasonText(result), checkedAt(result.checkedAt))
                    if (detail.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Hint(detail.joinToString(" · "))
                    }
                }
            }
        }
    }
}

/**
 * «Проверить отклик» in a server's menu: the answer of the check shows under the item
 * (a ring and «Проверяем…» while it runs, then the time in its colour or why there is
 * none), and the menu stays open, as Android's sheet does.
 */
@Composable
fun CheckMenuRow(check: ServerCheck?, onCheck: () -> Unit) {
    val palette = VoltaTheme.palette
    val scheme = MaterialTheme.colorScheme
    val still = rememberReducedMotion()
    Column(Modifier.widthIn(min = 260.dp, max = 360.dp)) {
        MenuRow(
            icon = Icons.Rounded.Speed,
            title = stringResource(R.string.servers_check_response),
            subtitle = null,
            ink = scheme.onSurface,
            enabled = check?.running != true,
            onClick = onCheck
        )
        AnimatedVisibility(visible = check != null, enter = arrive(still), exit = leave(still)) {
            AnimatedContent(
                targetState = check,
                contentKey = { it?.running },
                transitionSpec = {
                    if (still) {
                        EnterTransition.None togetherWith ExitTransition.None
                    } else {
                        (scaleIn(Motion.press(), initialScale = 0.85f) + fadeIn(Motion.settle())) togetherWith
                            fadeOut(Motion.settle())
                    }
                },
                label = "checkAnswer",
                modifier = Modifier.padding(start = 52.dp, end = 12.dp, bottom = 8.dp)
            ) { shown ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                ) {
                    if (shown == null || shown.running) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.servers_check_running),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant
                        )
                    } else {
                        if (shown.ok && shown.latencyMs >= 0) {
                            SignalBars(shown.latencyMs)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            shown.text?.resolve(LocalLang.current.tag).orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (shown.ok) palette.latency(shown.latencyMs) else palette.poor
                        )
                    }
                }
            }
        }
    }
}

/** A menu item drawn by hand: an icon, a title and a line under it; it doesn't close the menu. */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    ink: Color,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else ink.copy(alpha = 0.6f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = ink)
            if (subtitle != null) Hint(subtitle)
        }
    }
}

/**
 * The home screen's line for the server in use, once connected: «Gemini: работает» and
 * so on. Shown only when there is something to say (a verdict or a check running); a
 * click checks again.
 */
@Composable
fun GeminiHomeLine(gemini: GeminiChecks, liveId: Long?, modifier: Modifier = Modifier) {
    val results by gemini.results.collectAsState()
    val running by gemini.running.collectAsState()
    val result = liveId?.let { results[it] }
    val busy = liveId != null && liveId in running
    val still = rememberReducedMotion()
    AnimatedVisibility(
        visible = liveId != null && (busy || result != null),
        enter = arrive(still),
        exit = leave(still),
        modifier = modifier
    ) {
        val again = stringResource(R.string.gemini_check_again)
        Box(
            Modifier
                .padding(top = 10.dp)
                .clip(CircleShape)
                .clickable(enabled = !busy && liveId != null, onClickLabel = again, role = Role.Button) {
                    liveId?.let(gemini::check)
                }
                .padding(2.dp)
        ) {
            GeminiPill(GeminiShown(result, busy), home = true)
        }
    }
}

/** A line opening up under its row; at once with «Lite». */
private fun arrive(still: Boolean): EnterTransition =
    if (still) EnterTransition.None else fadeIn(Motion.settle()) + expandVertically(Motion.settle())

private fun leave(still: Boolean): ExitTransition =
    if (still) ExitTransition.None else fadeOut(Motion.settle()) + shrinkVertically(Motion.settle())

/**
 * «Проверено 14:05», or the date for an older verdict. Java has no "2 minutes ago" of
 * Android's DateUtils in fifteen languages; the time of day says the same.
 */
@Composable
private fun checkedAt(at: Long): String? {
    if (at <= 0) return null
    val locale = LocalLang.current.locale
    val zone = ZoneId.systemDefault()
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val pattern = if (then.toLocalDate() == LocalDate.now(zone)) "HH:mm" else "d MMM, HH:mm"
    return stringResource(R.string.gemini_checked_at, DateTimeFormatter.ofPattern(pattern, locale).format(then))
}
