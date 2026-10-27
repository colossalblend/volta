package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.components.Hint

@Composable
fun SubscriptionEditorDialog(
    subscription: SubscriptionEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    var url by remember(subscription.id) { mutableStateOf(subscription.url) }
    var confirmDelete by remember(subscription.id) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (confirmDelete) "Удалить подписку?" else "Подписка") },
        text = {
            if (confirmDelete) {
                Text(
                    "Будут удалены серверы и правила только этой подписки. " +
                        "Остальные подписки не затрагиваются."
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название") },
                        placeholder = { Text("Оставьте пустым — возьмём с сервера") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("HTTPS-ссылка") },
                        minLines = 2,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (subscription.lastUpdated > 0) {
                        Hint(
                            "Обновлялась " + android.text.format.DateUtils
                                .getRelativeTimeSpanString(subscription.lastUpdated)
                        )
                    }
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Удалить подписку", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (confirmDelete || url.isNotBlank()),
                onClick = { if (confirmDelete) onDelete() else onSave(name, url) }
            ) { Text(if (confirmDelete) "Удалить" else "Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = { if (confirmDelete) confirmDelete = false else onDismiss() }) {
                Text("Отмена")
            }
        }
    )
}
