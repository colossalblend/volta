package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.roleLabel

/**
 * Long-press actions for one server. A bottom sheet rather than a dropdown so the
 * role picker and the share/copy actions fit without scrolling a tiny menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerActionsSheet(
    server: ServerEntity,
    onDismiss: () -> Unit,
    onPing: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetRole: (ServerRole) -> Unit,
    onDelete: (() -> Unit)?,
    onCopied: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val shareText = server.rawLink.ifBlank { server.rawJson }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text(
                    server.remark,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Hint("${server.protocol.label} · ${server.address}:${server.port}")
            }
            Spacer(Modifier.height(12.dp))

            ListItem(
                headlineContent = { Text("Проверить отклик") },
                leadingContent = { Icon(Icons.Rounded.Speed, null) },
                modifier = Modifier.clickableRow { onPing(); onDismiss() }
            )
            ListItem(
                headlineContent = {
                    Text(if (server.isFavorite) "Убрать из избранного" else "В избранное")
                },
                leadingContent = {
                    Icon(
                        if (server.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        null
                    )
                },
                modifier = Modifier.clickableRow { onToggleFavorite(); onDismiss() }
            )

            if (shareText.isNotBlank()) {
                ListItem(
                    headlineContent = { Text("Скопировать ссылку") },
                    leadingContent = { Icon(Icons.Rounded.ContentCopy, null) },
                    modifier = Modifier.clickableRow {
                        clipboard.setText(AnnotatedString(shareText))
                        onCopied()
                        onDismiss()
                    }
                )
                ListItem(
                    headlineContent = { Text("Поделиться") },
                    leadingContent = { Icon(Icons.Rounded.Share, null) },
                    modifier = Modifier.clickableRow {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(
                            Intent.createChooser(send, "Поделиться сервером")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDismiss()
                    }
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Column(Modifier.padding(horizontal = 24.dp)) {
                Text("Роль сервера", style = MaterialTheme.typography.titleSmall)
                Hint("Определяет, какой автоподбор будет его использовать.")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerRole.entries.forEach { role ->
                        FilterChip(
                            selected = server.role == role,
                            onClick = { onSetRole(role); onDismiss() },
                            label = { Text(roleLabel(role)) }
                        )
                    }
                }
            }

            if (onDelete != null) {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = {
                        Text("Удалить сервер", color = MaterialTheme.colorScheme.error)
                    },
                    leadingContent = {
                        Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier.clickableRow { onDelete(); onDismiss() }
                )
            }
        }
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    fillMaxWidth().clickable(onClick = onClick)
