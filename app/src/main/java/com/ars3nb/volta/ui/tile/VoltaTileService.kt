package com.ars3nb.volta.ui.tile

import android.content.Intent
import android.content.Context
import com.ars3nb.volta.core.locale.AppLanguage
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.ars3nb.volta.MainActivity
import com.ars3nb.volta.R
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.VpnConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** The quick button in the shade: turns the VPN on in the current mode (Auto Home+LTE and so on). */
@RequiresApi(Build.VERSION_CODES.N)
class VoltaTileService : TileService() {

    /** In the language picked in Settings (Android 12 and older; 13+ does it itself). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch {
            combine(
                VoltaVpnService.state,
                VoltaVpnService.serverName,
                VoltaApp.instance.repository.connectMode
            ) { state, server, mode -> Triple(state, server, mode) }
                .collectLatest { (state, server, mode) -> updateTile(state, server, mode) }
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
        // Into the foreground service at once; it picks the node and connects there.
        // Refused (Android didn't count the tap as the user's): the app finishes the job.
        if (!VoltaVpnService.autoConnect(this)) openApp()
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

    private fun updateTile(state: VpnConnectionState, server: String, mode: ConnectMode) {
        val tile = qsTile ?: return
        val subtitle: String
        when (state) {
            VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Volta"
                subtitle = server.ifBlank { GroupTileService.title(this, mode) }
                tile.contentDescription = getString(R.string.home_tile_vpn_on_description, subtitle)
            }

            VpnConnectionState.CONNECTING, VpnConnectionState.DISCONNECTING -> {
                // ACTIVE, not UNAVAILABLE: an unavailable tile swallows the tap that
                // would let the user cancel a connect that hangs.
                tile.state = Tile.STATE_ACTIVE
                tile.label = "Volta"
                subtitle = getString(
                    if (state == VpnConnectionState.CONNECTING) R.string.vpn_connecting else R.string.home_disconnecting
                )
                tile.contentDescription = subtitle
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "Volta"
                subtitle = GroupTileService.title(this, mode)
                tile.contentDescription = getString(R.string.home_tile_vpn_off_description, subtitle)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.icon = Icon.createWithResource(this, R.drawable.ic_vpn_key)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
