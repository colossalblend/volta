package com.ars3nb.volta.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.core.locale.UiText
import com.ars3nb.volta.data.model.TrafficStats
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * [VoltaViewModel.uiState] without the live traffic counters, for screens that never show
 * them. The counters change every two seconds while connected; without this the server
 * list, Settings and the rest would recompose that often for nothing.
 */
@Composable
fun VoltaViewModel.collectQuietState(): State<VoltaUiState> {
    val flow = remember(this) {
        uiState.map { it.withoutTraffic() }.distinctUntilChanged()
    }
    return flow.collectAsStateWithLifecycle(initialValue = remember(this) { uiState.value.withoutTraffic() })
}

/** Only the user-facing message, for the snackbar host at the root of the app. */
@Composable
fun VoltaViewModel.collectMessage(): State<UiText?> {
    val flow = remember(this) { uiState.map { it.message }.distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = uiState.value.message)
}

private val NO_TRAFFIC = TrafficStats()

private fun VoltaUiState.withoutTraffic(): VoltaUiState =
    if (traffic == NO_TRAFFIC) this else copy(traffic = NO_TRAFFIC)
