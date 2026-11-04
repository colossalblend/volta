package com.ars3nb.volta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.update.UpdateInfo
import com.ars3nb.volta.core.update.UpdateState
import com.ars3nb.volta.ui.theme.Motion
import com.ars3nb.volta.ui.theme.VoltaTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Updates in the language of Kit.kt: one row — badge, two lines of words, one pill to
 * press — and a thin track under it while a download runs. The badge says how things
 * stand at a glance (a tick when there is nothing to do, copper when there is), so the
 * words can stay short.
 */

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

/**
 * On the home screen, only while there is a newer build to get or install. A plain card
 * (an outline means "the chosen one" elsewhere, so none here): copper badge, the version,
 * one pill.
 */
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
                    "Вышла версия ${info.versionName}",
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

/**
 * The «О приложении» row in Settings: which version this is and what the update is doing.
 * [storeNote] replaces everything for the Google Play build, which has no updater of its
 * own. The «Проверить» pill is always there, even with automatic checks off.
 */
@Composable
fun UpdatePanel(
    state: UpdateState,
    currentVersion: String,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    storeNote: String? = null
) {
    val scheme = MaterialTheme.colorScheme
    val palette = VoltaTheme.palette
    val quiet = storeNote != null
    val tint = when {
        quiet -> scheme.onSurfaceVariant
        state is UpdateState.UpToDate -> palette.good
        state is UpdateState.Failed -> scheme.error
        else -> scheme.primary
    }
    Column {
        Row(
            Modifier.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconBubble(updateIcon(state), tint, active = !quiet)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (quiet) "Volta $currentVersion" else panelTitle(state, currentVersion),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Hint(storeNote ?: panelHint(state, currentVersion))
            }
            if (!quiet) {
                Spacer(Modifier.width(10.dp))
                UpdatePill(state, onCheck = onCheck, onInstall = onInstall)
            }
        }
        if (!quiet) UpdateTrack(state)
    }
}

/** The one thing to press: check, update, install or try again. A spinner while looking. */
@Composable
fun UpdatePill(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    when (state) {
        UpdateState.Checking ->
            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }

        is UpdateState.Downloading -> Text(
            "${(state.progress * 100).toInt()}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp)
        )

        is UpdateState.Available -> Pill("Обновить", filled = true, onClick = onInstall)
        is UpdateState.Ready -> Pill("Установить", filled = true, onClick = onInstall)
        is UpdateState.Failed ->
            if (state.info != null) Pill("Ещё раз", filled = false, onClick = onInstall)
            else Pill("Ещё раз", filled = false, onClick = onCheck)

        UpdateState.Idle, is UpdateState.UpToDate -> Pill("Проверить", filled = false, onClick = onCheck)
    }
}

/**
 * A pill button in the chip's shape: filled copper for "go ahead", a copper wash for
 * the quieter "check". Same height as a chip, so it sits level with the row's text.
 */
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

/** A thin rounded track under the row while the file comes in; slides away when done. */
@Composable
fun UpdateTrack(state: UpdateState) {
    val downloading = state is UpdateState.Downloading
    val target = (state as? UpdateState.Downloading)?.progress ?: 1f
    val progress by animateFloatAsState(target, Motion.settle(), label = "updateTrack")
    AnimatedVisibility(
        visible = downloading,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        Column {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }
    }
}

private fun updateIcon(state: UpdateState): ImageVector = when (state) {
    is UpdateState.UpToDate -> Icons.Rounded.CheckCircle
    is UpdateState.Available, is UpdateState.Downloading -> Icons.Rounded.Download
    is UpdateState.Ready -> Icons.Rounded.InstallMobile
    is UpdateState.Failed -> Icons.Rounded.ErrorOutline
    UpdateState.Idle, UpdateState.Checking -> Icons.Rounded.SystemUpdate
}

private fun panelTitle(state: UpdateState, current: String): String = when (state) {
    is UpdateState.UpToDate -> "Volta $current"
    is UpdateState.Available -> "Вышла версия ${state.info.versionName}"
    is UpdateState.Downloading -> "Скачиваю версию ${state.info.versionName}"
    is UpdateState.Ready -> "Версия ${state.info.versionName} скачана"
    is UpdateState.Failed -> "Не получилось"
    UpdateState.Idle, UpdateState.Checking -> "Volta $current"
}

private fun panelHint(state: UpdateState, current: String): String = when (state) {
    UpdateState.Idle -> "Узнайте, есть ли новая версия"
    UpdateState.Checking -> "Ищу новую версию…"
    is UpdateState.UpToDate -> "Последняя версия · проверено в ${clock(state.checkedAt)}"
    is UpdateState.Available -> "Сейчас у вас $current · ${formatBytes(state.info.apk.size)}"
    is UpdateState.Downloading -> "Можно пользоваться приложением, пока идёт загрузка"
    is UpdateState.Ready -> readyHint(state)
    is UpdateState.Failed -> state.message
}

private fun bannerHint(state: UpdateState): String = when (state) {
    is UpdateState.Available -> "Скачать ${formatBytes(state.info.apk.size)} и установить"
    is UpdateState.Downloading -> "Скачиваю…"
    is UpdateState.Ready -> readyHint(state)
    else -> ""
}

private fun readyHint(state: UpdateState.Ready): String =
    if (state.needsPermission) "Разрешите Volta устанавливать приложения и нажмите «Установить»"
    else "Осталось подтвердить установку"

private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
