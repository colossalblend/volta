package com.ars3nb.volta.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R
import com.ars3nb.volta.core.routing.RoutingResolver
import com.ars3nb.volta.data.model.ServerRole
import com.ars3nb.volta.data.model.SubscriptionEntity
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.collectQuietState
import com.ars3nb.volta.ui.components.EmptyState
import com.ars3nb.volta.ui.components.Hint
import com.ars3nb.volta.ui.components.SubscriptionCard
import com.ars3nb.volta.ui.components.barColors
import com.ars3nb.volta.ui.components.shownProfileName

/**
 * Every subscription the app holds. Their servers are merged into one list (Servers tab)
 * and split by group; routing and geodata stay per subscription, exactly as in Happ.
 * Adding lives in the top bar, like the other tabs, instead of a button over the cards.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    vm: VoltaViewModel,
    onOpenImport: () -> Unit
) {
    val state by vm.collectQuietState()
    var editing by remember { mutableStateOf<SubscriptionEntity?>(null) }
    val selected = state.selectedServer
    // Group counts per subscription, computed once per list change, not per card.
    val groups = remember(state.servers) {
        state.servers.groupBy { it.subscriptionId }.mapValues { (_, list) ->
            Triple(
                list.count { it.role == ServerRole.NORMAL },
                list.count { it.role == ServerRole.BYPASS_WHITELIST },
                list.count { it.role == ServerRole.AUTO }
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text(stringResource(R.string.settings_subs_title), fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (state.subscriptions.isNotEmpty()) {
                        IconButton(onClick = vm::refreshAllSubscriptions, enabled = !state.busy) {
                            if (state.busy) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Rounded.Refresh, stringResource(R.string.settings_subs_refresh_all))
                            }
                        }
                    }
                    IconButton(onClick = onOpenImport) {
                        Icon(Icons.Rounded.Add, stringResource(R.string.settings_import_add_sub))
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.subscriptions.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Rounded.CloudDownload,
                        title = stringResource(R.string.settings_subs_empty_title),
                        subtitle = stringResource(R.string.settings_subs_empty_text),
                        action = {
                            Button(
                                onClick = onOpenImport,
                                modifier = Modifier.padding(top = 8.dp).height(48.dp)
                            ) { Text(stringResource(R.string.settings_import_add_sub)) }
                        }
                    )
                }
            } else {
                item(key = "lead") {
                    Hint(
                        stringResource(R.string.settings_subs_lead),
                        Modifier.fillMaxWidth().padding(bottom = 2.dp)
                    )
                }
                items(state.subscriptions, key = { "sub-${it.id}" }) { sub ->
                    val (home, lte, auto) = groups[sub.id] ?: Triple(0, 0, 0)
                    SubscriptionCard(
                        sub = sub,
                        homeCount = home,
                        lteCount = lte,
                        autoCount = auto,
                        routingName = RoutingResolver.resolve(sub, state.routingProfiles)?.let { shownProfileName(it) }.orEmpty(),
                        active = selected?.subscriptionId == sub.id,
                        onClick = { editing = sub },
                        onRefresh = { vm.refreshSubscription(sub.id) },
                        modifier = Modifier.animateItem()
                    )
                }
                item { Spacer(Modifier.size(8.dp)) }
            }
        }
    }

    editing?.let { sub ->
        // The sheet follows the live row, so a refresh made from it shows at once.
        val live = state.subscriptions.firstOrNull { it.id == sub.id } ?: sub
        SubscriptionEditorDialog(
            subscription = live,
            serverCount = groups[sub.id]?.let { it.first + it.second + it.third } ?: 0,
            busy = state.busy,
            onDismiss = { editing = null },
            onSave = { name, url ->
                vm.updateSubscription(sub.id, name, url) { ok -> if (ok) editing = null }
            },
            onRefresh = { vm.refreshSubscription(sub.id) },
            onDelete = {
                vm.deleteSubscription(sub.id)
                editing = null
            }
        )
    }
}
