package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SheetAction
import com.ars3nb.volta.ui.components.SubscriptionCoin
import com.ars3nb.volta.ui.components.VoltaField
import com.ars3nb.volta.ui.components.serversCount
import com.ars3nb.volta.ui.components.updatedLabel

/**
 * One subscription, opened from its card: rename it, change its address (unless the
 * provider hid it), refresh it now or delete it. A bottom sheet, like the server
 * actions, so it opens where the thumb is; deleting asks once, in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionEditorDialog(
    subscription: SubscriptionEntity,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onDelete: () -> Unit,
    serverCount: Int = 0,
    onRefresh: () -> Unit = {}
) {
    val scheme = MaterialTheme.colorScheme
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    // A hidden address is never put into UI state at all.
    var url by remember(subscription.id) {
        mutableStateOf(if (subscription.urlHidden) "" else subscription.url)
    }
    var confirmDelete by remember(subscription.id) { mutableStateOf(false) }
    val changed = name.trim() != subscription.name ||
        (!subscription.urlHidden && url.trim() != subscription.url)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = scheme.surface
    ) {
        AnimatedContent(
            targetState = confirmDelete,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "subSheet"
        ) { deleting ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SubscriptionCoin(subscription, size = 48)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(subscription.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Hint(
                            listOfNotNull(
                                serversCount(serverCount).takeIf { serverCount > 0 },
                                updatedLabel(subscription.lastUpdated)
                            ).joinToString(", ")
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))

                if (deleting) {
                    Text(stringResource(R.string.settings_subs_delete_question), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.settings_subs_delete_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = onDelete,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = scheme.error,
                            contentColor = scheme.onError
                        ),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(stringResource(R.string.settings_subs_delete)) }
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = { confirmDelete = false },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(stringResource(R.string.settings_subs_keep)) }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        VoltaField(
                            value = name,
                            onValueChange = { name = it },
                            label = stringResource(R.string.settings_subs_name),
                            singleLine = true,
                            container = scheme.surfaceVariant
                        )
                        if (subscription.urlHidden) {
                            HiddenLinkNote()
                        } else {
                            VoltaField(
                                value = url,
                                onValueChange = { url = it },
                                label = stringResource(R.string.settings_subs_url),
                                minLines = 2,
                                maxLines = 4,
                                container = scheme.surfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Button(
                        enabled = !busy && changed && (subscription.urlHidden || url.isNotBlank()),
                        // A hidden subscription never hands its address back to the screen.
                        onClick = { onSave(name, if (subscription.urlHidden) "" else url) },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text(stringResource(R.string.settings_subs_save)) }
                    Spacer(Modifier.height(10.dp))
                    SheetAction(
                        icon = Icons.Rounded.Refresh,
                        title = stringResource(R.string.settings_subs_refresh),
                        subtitle = stringResource(R.string.settings_subs_refresh_text),
                        onClick = onRefresh
                    )
                    SheetAction(
                        icon = Icons.Rounded.Delete,
                        title = stringResource(R.string.settings_subs_delete_action),
                        onClick = { confirmDelete = true },
                        danger = true
                    )
                }
            }
        }
    }
}

/** In place of the address field when the provider hid the link. */
@Composable
private fun HiddenLinkNote() {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.Lock,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.settings_subs_hidden_title), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(R.string.settings_subs_hidden_text))
            }
        }
    }
}
