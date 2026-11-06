package com.ars3nb.volta.ui.components

import android.text.format.DateUtils
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.gemini.GeminiProbe
import com.ars3nb.volta.core.gemini.GeminiReason
import com.ars3nb.volta.core.gemini.GeminiResult
import com.ars3nb.volta.core.gemini.GeminiStatus
import com.ars3nb.volta.core.server.Countries
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.ui.GeminiViewModel
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.theme.rememberReducedMotion
import com.ars3nb.volta.ui.tv.RowFocusOutset
import com.ars3nb.volta.ui.tv.focusRing

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

/** «Gemini работает» or the other two verdicts, as the server sheet words them. */
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

/** Why there is no verdict, in a few words; null when the verdict says it all. */
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

/** 🇺🇸 USA — the country Google placed the request in, when the page named it. */
@Composable
private fun regionText(result: GeminiResult): String? {
    val code = result.regionAlpha2 ?: return null
    val res = LocalContext.current.resources
    return "${Countries.flagOf(code)} ${Countries.name(res, code)}"
}

/**
 * A verdict as a pill, or a small ring while the check runs. A new answer arrives with a
 * slight scale and fade (none with animations off).
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
                text = if (region != null && result.status != GeminiStatus.BLOCKED_OR_ERROR) {
                    "$words · $region"
                } else {
                    words
                },
                color = geminiColor(result.status),
                icon = geminiIcon(result.status),
                maxLines = 2
            )
        }
    }
}

/**
 * «Проверить Gemini» in a server's sheet: opens gemini.google.com through that server and
 * shows the verdict under the action, in place; the sheet stays open. A server only a
 * helper carries (OpenFlux, AmneziaWG) can be checked only while connected to it.
 */
@Composable
fun GeminiSheetRow(server: ServerEntity, vm: GeminiViewModel) {
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val liveId by vm.liveServerId.collectAsStateWithLifecycle()
    val route = remember(server, liveId) { vm.routeFor(server) }
    if (route == GeminiProbe.Route.NONE) return

    val result = results[server.id]
    val busy = server.id in running
    val canCheck = route != GeminiProbe.Route.ONLY_WHEN_CONNECTED
    val scheme = MaterialTheme.colorScheme
    val ink = if (canCheck) scheme.onSurface else scheme.onSurfaceVariant
    val still = rememberReducedMotion()

    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .focusRing(SmallShape, outset = RowFocusOutset)
                .clip(SmallShape)
                .clickable(enabled = canCheck && !busy) { vm.check(server.id) }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.gemini_check_action), style = MaterialTheme.typography.titleSmall, color = ink)
                Hint(
                    stringResource(
                        if (canCheck) R.string.gemini_check_action_text else R.string.gemini_reason_needs_connection
                    )
                )
            }
        }
        AnimatedVisibility(
            visible = busy || result != null,
            enter = arrive(still),
            exit = leave(still)
        ) {
            Column(Modifier.padding(start = 62.dp, end = 8.dp, bottom = 8.dp)) {
                GeminiPill(GeminiShown(result, busy), home = false)
                if (!busy && result != null) {
                    val detail = listOfNotNull(reasonText(result), checkedAgo(result.checkedAt))
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
 * The home screen's line for the server in use, once connected: «Gemini: работает» and
 * so on. Shown only when there is something to say (a verdict or a check running); a tap
 * checks again.
 */
@Composable
fun GeminiHomeLine(vm: GeminiViewModel, modifier: Modifier = Modifier) {
    val results by vm.results.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val liveId by vm.liveServerId.collectAsStateWithLifecycle()
    val id = liveId
    val result = id?.let { results[it] }
    val busy = id != null && id in running
    val still = rememberReducedMotion()
    AnimatedVisibility(
        visible = id != null && (busy || result != null),
        enter = arrive(still),
        exit = leave(still),
        modifier = modifier
    ) {
        val again = stringResource(R.string.gemini_check_again)
        Box(
            Modifier
                .padding(top = 10.dp)
                .focusRing(CircleShape)
                .clip(CircleShape)
                .clickable(enabled = !busy && id != null, onClickLabel = again, role = Role.Button) {
                    id?.let(vm::check)
                }
                .padding(2.dp)
        ) {
            GeminiPill(GeminiShown(result, busy), home = true)
        }
    }
}

/** A line opening up under its row; at once with animations off. */
private fun arrive(still: Boolean): EnterTransition =
    if (still) EnterTransition.None else fadeIn(Motion.settle()) + expandVertically(Motion.settle())

private fun leave(still: Boolean): ExitTransition =
    if (still) ExitTransition.None else fadeOut(Motion.settle()) + shrinkVertically(Motion.settle())

/** «2 min. ago» in the system's words; null for a verdict with no time. */
@Composable
private fun checkedAgo(at: Long): String? {
    if (at <= 0) return null
    val now = System.currentTimeMillis()
    return stringResource(
        R.string.gemini_checked_at,
        DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
    )
}
