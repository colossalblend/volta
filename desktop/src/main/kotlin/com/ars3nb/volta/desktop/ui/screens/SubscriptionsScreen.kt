package com.ars3nb.volta.desktop.ui.screens

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.desktop.VoltaController
import com.ars3nb.volta.desktop.VoltaUiState
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.components.CardShape
import com.ars3nb.volta.desktop.ui.components.EmptyState
import com.ars3nb.volta.desktop.ui.components.Hint
import com.ars3nb.volta.desktop.ui.components.PageHeader
import com.ars3nb.volta.desktop.ui.components.ScrollPage
import com.ars3nb.volta.desktop.ui.components.SheetAction
import com.ars3nb.volta.desktop.ui.components.SubscriptionCard
import com.ars3nb.volta.desktop.ui.components.SubscriptionCoin
import com.ars3nb.volta.desktop.ui.components.VoltaField
import com.ars3nb.volta.desktop.ui.components.updatedLabel

/**
 * Every subscription Volta holds. Their servers are merged into one list (Servers page)
 * and split by group; routing and geodata stay per subscription, as on Android.
 */
@Composable
fun SubscriptionsScreen(
    state: VoltaUiState,
    controller: VoltaController,
    onOpenImport: () -> Unit,
    copyToClipboard: (String) -> Unit,
    openUrl: (String) -> Unit
) {
    val s = LocalStrings.current
    var editing by remember { mutableStateOf<Long?>(null) }
    val selected = state.selectedServer
    val groups = remember(state.servers) {
        state.servers.groupBy { it.subscriptionId }.mapValues { (_, list) ->
            Triple(
                list.count { it.role == ServerRole.NORMAL },
                list.count { it.role == ServerRole.BYPASS_WHITELIST },
                list.count { it.role == ServerRole.AUTO }
            )
        }
    }

    ScrollPage {
        item(key = "header") {
            PageHeader(s.navSubscriptions) {
                if (state.subscriptions.isNotEmpty()) {
                    IconButton(onClick = controller::refreshAll, enabled = !state.busy) {
                        if (state.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Refresh, s.refreshAll)
                    }
                }
                IconButton(onClick = onOpenImport) { Icon(Icons.Rounded.Add, s.addSubscription) }
            }
        }
        if (state.subscriptions.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    icon = Icons.Rounded.CloudDownload,
                    title = s.noSubsTitle,
                    subtitle = s.noSubsText,
                    action = {
                        Button(onClick = onOpenImport, modifier = Modifier.padding(top = 8.dp).height(48.dp)) {
                            Text(s.addSubscription)
                        }
                    }
                )
            }
        } else {
            item(key = "lead") { Hint(s.subsHint, Modifier.fillMaxWidth().padding(bottom = 2.dp)) }
            items(state.subscriptions, key = { "sub-${it.id}" }) { sub ->
                val (home, lte, auto) = groups[sub.id] ?: Triple(0, 0, 0)
                SubscriptionCard(
                    sub = sub,
                    homeCount = home,
                    lteCount = lte,
                    autoCount = auto,
                    routingName = RoutingResolver.resolve(sub, state.routingProfiles)
                        ?.takeIf { it.sourceSubscriptionId == sub.id }?.name.orEmpty(),
                    active = selected?.subscriptionId == sub.id,
                    onClick = { editing = sub.id },
                    onRefresh = { controller.refreshSubscription(sub.id) }
                )
            }
        }
    }

    editing?.let { id ->
        // The dialog follows the live row, so a refresh made from it shows at once.
        val live = state.subscriptions.firstOrNull { it.id == id }
        if (live != null) {
            SubscriptionEditor(
                subscription = live,
                serverCount = groups[id]?.let { it.first + it.second + it.third } ?: 0,
                busy = state.busy,
                onDismiss = { editing = null },
                onSave = { name, url -> controller.updateSubscription(id, name, url) { ok -> if (ok) editing = null } },
                onRefresh = { controller.refreshSubscription(id) },
                onDelete = {
                    controller.deleteSubscription(id)
                    editing = null
                },
                onCopy = {
                    copyToClipboard(live.url)
                    controller.message(s.linkCopied)
                },
                openUrl = openUrl
            )
        }
    }
}

/** Rename, refresh or delete one subscription; on Android this is a bottom sheet. */
@Composable
private fun SubscriptionEditor(
    subscription: SubscriptionEntity,
    serverCount: Int,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    openUrl: (String) -> Unit
) {
    val s = LocalStrings.current
    val scheme = MaterialTheme.colorScheme
    var name by remember(subscription.id) { mutableStateOf(subscription.name) }
    // A hidden address is never put into UI state at all.
    var url by remember(subscription.id) { mutableStateOf(if (subscription.urlHidden) "" else subscription.url) }
    var confirmDelete by remember(subscription.id) { mutableStateOf(false) }
    val changed = name.trim() != subscription.name || (!subscription.urlHidden && url.trim() != subscription.url)

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = CardShape, color = scheme.surface, modifier = Modifier.widthIn(min = 420.dp, max = 520.dp)) {
            AnimatedContent(targetState = confirmDelete, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "subDialog") { deleting ->
                Column(Modifier.fillMaxWidth().padding(22.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SubscriptionCoin(subscription, size = 48)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(subscription.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Hint(
                                listOfNotNull(
                                    s.servers(serverCount).takeIf { serverCount > 0 },
                                    updatedLabel(subscription.lastUpdated)
                                ).joinToString(", ")
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    if (deleting) {
                        Text(s.deleteSubscriptionQuestion(subscription.name), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(6.dp))
                        Text(s.deleteSubscriptionNote, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { confirmDelete = false }, modifier = Modifier.weight(1f).height(48.dp)) { Text(s.keep) }
                            Button(
                                onClick = onDelete,
                                colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text(s.delete) }
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            VoltaField(value = name, onValueChange = { name = it }, label = s.name, singleLine = true, container = scheme.surfaceVariant)
                            if (subscription.urlHidden) {
                                HiddenLinkNote()
                            } else {
                                VoltaField(value = url, onValueChange = { url = it }, label = s.link, minLines = 2, maxLines = 4, container = scheme.surfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp)) { Text(s.cancel) }
                            Button(
                                enabled = !busy && changed && (subscription.urlHidden || url.isNotBlank()),
                                onClick = { onSave(name, if (subscription.urlHidden) "" else url) },
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) { Text(s.save) }
                        }
                        Spacer(Modifier.height(10.dp))
                        SheetAction(icon = Icons.Rounded.Refresh, title = s.refreshNow, subtitle = s.refreshNowHint, onClick = onRefresh)
                        if (!subscription.urlHidden) {
                            SheetAction(icon = Icons.Rounded.ContentCopy, title = s.copyLink, onClick = onCopy)
                        }
                        if (subscription.supportUrl.isNotBlank()) {
                            SheetAction(
                                icon = Icons.Rounded.SupportAgent,
                                title = s.supportLink,
                                subtitle = subscription.supportUrl,
                                onClick = { openUrl(subscription.supportUrl) }
                            )
                        }
                        if (subscription.profileWebPageUrl.isNotBlank() && !subscription.urlHidden) {
                            SheetAction(
                                icon = Icons.AutoMirrored.Rounded.OpenInNew,
                                title = s.providerSite,
                                onClick = { openUrl(subscription.profileWebPageUrl) }
                            )
                        }
                        SheetAction(icon = Icons.Rounded.Delete, title = s.delete, onClick = { confirmDelete = true }, danger = true)
                    }
                }
            }
        }
    }
}

/** In place of the address field when the provider hid the link. */
@Composable
private fun HiddenLinkNote() {
    val s = LocalStrings.current
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Lock, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(s.linkHiddenTitle, style = MaterialTheme.typography.titleSmall)
                Hint(s.linkHiddenNote)
            }
        }
    }
}
