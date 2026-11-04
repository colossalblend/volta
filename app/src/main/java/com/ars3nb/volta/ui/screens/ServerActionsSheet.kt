package com.ars3nb.volta.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ars3nb.volta.R
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SheetAction
import com.ars3nb.volta.ui.components.VoltaChip
import com.ars3nb.volta.ui.components.flagOf
import com.ars3nb.volta.ui.components.groupColor
import com.ars3nb.volta.ui.components.roleLabel
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.components.shownServerName

/**
 * Long-press actions for one server. A bottom sheet rather than a dropdown so the
 * group picker and the share/copy actions fit without scrolling a tiny menu.
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
    onCopied: () -> Unit,
    /** «Отправить по Bluetooth»; null where the device has no Bluetooth LE. */
    onSendBluetooth: (() -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val shareText = server.rawLink.ifBlank { server.rawJson }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = scheme.surface
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 12.dp)
                .navigationBarsPadding()
        ) {
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(scheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(flagOf(server.remark), fontSize = 24.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        shownServerName(server.remark),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Hint(stringResource(R.string.servers_group_named, roleLabel(server.role)))
                }
            }
            Spacer(Modifier.height(16.dp))

            SheetAction(
                icon = Icons.Rounded.Speed,
                title = stringResource(R.string.servers_check_response),
                onClick = { onPing(); onDismiss() }
            )
            SheetAction(
                icon = if (server.isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                title = stringResource(
                    if (server.isFavorite) R.string.servers_unfavorite else R.string.servers_favorite
                ),
                tint = if (server.isFavorite) scheme.primary else scheme.onSurface,
                onClick = { onToggleFavorite(); onDismiss() }
            )
            if (shareText.isNotBlank()) {
                val chooserTitle = stringResource(R.string.servers_share_server)
                SheetAction(
                    icon = Icons.Rounded.ContentCopy,
                    title = stringResource(R.string.servers_copy_link),
                    onClick = {
                        clipboard.setText(AnnotatedString(shareText))
                        onCopied()
                        onDismiss()
                    }
                )
                SheetAction(
                    icon = Icons.Rounded.Share,
                    title = stringResource(R.string.servers_share),
                    onClick = {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(
                            Intent.createChooser(send, chooserTitle)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        onDismiss()
                    }
                )
                if (onSendBluetooth != null) {
                    SheetAction(
                        icon = Icons.Rounded.Bluetooth,
                        title = stringResource(R.string.share_send_action),
                        subtitle = stringResource(R.string.share_send_action_text),
                        onClick = { onSendBluetooth(); onDismiss() }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.servers_group), style = MaterialTheme.typography.titleSmall)
                Hint(
                    stringResource(
                        if (server.roleManual) R.string.servers_group_manual
                        else if (server.roleFromProvider) R.string.servers_group_provider
                        else R.string.servers_group_by_name
                    )
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerRole.entries.forEach { role ->
                        VoltaChip(
                            text = roleLabel(role),
                            selected = server.role == role,
                            onClick = { onSetRole(role); onDismiss() },
                            accent = groupColor(
                                when (role) {
                                    ServerRole.NORMAL -> true
                                    ServerRole.BYPASS_WHITELIST -> false
                                    ServerRole.AUTO -> null
                                }
                            ).takeIf { role != ServerRole.AUTO } ?: scheme.primary
                        )
                    }
                }
            }

            if (onDelete != null) {
                Spacer(Modifier.height(12.dp))
                SheetAction(
                    icon = Icons.Rounded.Delete,
                    title = stringResource(R.string.servers_delete),
                    onClick = { onDelete(); onDismiss() },
                    danger = true
                )
            }
        }
    }
}
