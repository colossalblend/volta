package com.ars3nb.volta.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.gemini.GeminiProbe
import com.ars3nb.volta.core.gemini.GeminiResult
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.ServerEntity
import com.ars3nb.volta.data.model.VpnConnectionState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The Gemini check for the screens: the verdicts, what is being checked, the setting.
 * A view model of its own, so the main one stays as it is; the checks live in the app
 * ([VoltaApp.gemini]) and outlast any screen.
 */
class GeminiViewModel(app: Application) : AndroidViewModel(app) {

    // The app object, not the one passed in: the screen tests give a plain Application.
    private val checks = VoltaApp.instance.gemini

    val results: StateFlow<Map<Long, GeminiResult>> = checks.results
    val running: StateFlow<Set<Long>> = checks.running

    val afterConnect: StateFlow<Boolean> = checks.afterConnect
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The server the tunnel runs on, or null while it is not connected. */
    val liveServerId: StateFlow<Long?> = combine(VoltaVpnService.state, VoltaVpnService.serverId) { state, id ->
        id.takeIf { state == VpnConnectionState.CONNECTED && it > 0 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun check(serverId: Long) = checks.check(serverId)

    fun setAfterConnect(enabled: Boolean) = checks.setAfterConnect(enabled)

    fun routeFor(server: ServerEntity): GeminiProbe.Route = checks.routeFor(server)
}
