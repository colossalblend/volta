package com.ars3nb.volta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.update.UpdateState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A newer build is there to download or install. */
val UpdateState.offersInstall: Boolean
    get() = this is UpdateState.Available || this is UpdateState.Downloading || this is UpdateState.Ready

/** On the home screen, only while there is a newer build to get or install. */
@Composable
fun UpdateBanner(state: UpdateState, onInstall: () -> Unit) {
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Ready -> state.info
        else -> return
    }
    VoltaCard(selected = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.SystemUpdate,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Обновление ${info.versionName}", style = MaterialTheme.typography.titleSmall)
                Hint(updateHint(state))
            }
            UpdateAction(state, onCheck = {}, onInstall = onInstall)
        }
        UpdateProgress(state)
    }
}

/** What the update is doing, in one line. */
fun updateHint(state: UpdateState): String = when (state) {
    UpdateState.Idle -> "Ещё не проверяли"
    UpdateState.Checking -> "Ищу обновления…"
    is UpdateState.UpToDate ->
        "Последняя версия · проверено в " +
            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(state.checkedAt))

    is UpdateState.Available -> "Можно скачать, ${formatBytes(state.info.apk.size)}"
    is UpdateState.Downloading -> "Скачиваю… ${(state.progress * 100).toInt()}%"
    is UpdateState.Ready ->
        if (state.needsPermission) {
            "Разрешите Volta устанавливать приложения и нажмите «Установить»"
        } else {
            "Скачано, осталось подтвердить установку"
        }

    is UpdateState.Failed -> state.message
}

/** The one button that moves the update on: check, download, install or retry. */
@Composable
fun UpdateAction(state: UpdateState, onCheck: () -> Unit, onInstall: () -> Unit) {
    when (state) {
        UpdateState.Checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        is UpdateState.Available -> Button(onClick = onInstall) { Text("Обновить") }
        is UpdateState.Downloading -> Unit
        is UpdateState.Ready -> Button(onClick = onInstall) { Text("Установить") }
        is UpdateState.Failed ->
            if (state.info != null) {
                TextButton(onClick = onInstall) { Text("Повторить") }
            } else {
                TextButton(onClick = onCheck) { Text("Проверить") }
            }

        UpdateState.Idle, is UpdateState.UpToDate -> TextButton(onClick = onCheck) { Text("Проверить") }
    }
}

@Composable
fun UpdateProgress(state: UpdateState) {
    if (state !is UpdateState.Downloading) return
    Spacer(Modifier.height(10.dp))
    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
}
