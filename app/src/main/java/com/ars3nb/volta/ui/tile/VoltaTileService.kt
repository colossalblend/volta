package com.ars3nb.volta.ui.tile

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.ars3nb.volta.MainActivity
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Быстрая кнопка в шторке уведомлений. */
@RequiresApi(Build.VERSION_CODES.N)
class VoltaTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch {
            VoltaVpnService.state.collectLatest { updateTile(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val active = VoltaVpnService.state.value == VpnConnectionState.CONNECTED ||
            VoltaVpnService.state.value == VpnConnectionState.CONNECTING
        if (active) {
            VoltaVpnService.disconnect(this)
            return
        }

        // Consent can only be granted from an Activity, so bounce there when missing.
        if (VpnService.prepare(this) != null) {
            openApp()
            return
        }
        // The tile's own scope dies with onDestroy moments after the click; the
        // application scope outlives it, so the connect actually completes.
        VoltaApp.instance.appScope.launch {
            runCatching { VoltaApp.instance.repository.connectSelected() }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(state: VpnConnectionState) {
        val tile = qsTile ?: return
        when (state) {
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Volta · вкл"
                tile.contentDescription = "VPN включён, нажмите чтобы выключить"
            }

            VpnConnectionState.CONNECTING, VpnConnectionState.DISCONNECTING -> {
                tile.state = Tile.STATE_UNAVAILABLE
                tile.label = "Volta…"
                tile.contentDescription = "Переключение VPN"
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Volta"
                tile.contentDescription = "VPN выключен, нажмите чтобы включить"
            }
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
