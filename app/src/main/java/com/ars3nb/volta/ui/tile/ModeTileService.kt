package com.ars3nb.volta.ui.tile

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ars3nb.volta.R
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.data.model.ConnectMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Second tile in the shade: flips between Авто Home+LTE → Авто Home → Авто LTE.
 * With the tunnel up the new group is applied straight away.
 */
class ModeTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        job?.cancel()
        job = scope.launch {
            VoltaApp.instance.repository.connectMode.collectLatest { updateTile(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val repo = VoltaApp.instance.repository
        // Application scope: the tile's own scope dies right after the click.
        VoltaApp.instance.appScope.launch {
            val next = nextAuto(repo.connectMode.first())
            repo.setConnectMode(next)
            if (repo.isConnected()) runCatching { repo.connectWithMode() }
        }
    }

    private fun updateTile(mode: ConnectMode) {
        val tile = qsTile ?: return
        tile.state = if (mode == ConnectMode.MANUAL) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = mode.title
        tile.icon = Icon.createWithResource(this, iconFor(mode))
        tile.contentDescription = "Режим подключения: ${mode.title}. Нажмите, чтобы сменить"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = "Режим Volta"
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun nextAuto(mode: ConnectMode): ConnectMode = when (mode) {
            ConnectMode.AUTO_HOME_LTE -> ConnectMode.AUTO_HOME
            ConnectMode.AUTO_HOME -> ConnectMode.AUTO_LTE
            ConnectMode.AUTO_LTE, ConnectMode.MANUAL -> ConnectMode.AUTO_HOME_LTE
        }

        fun iconFor(mode: ConnectMode): Int = when (mode) {
            ConnectMode.AUTO_HOME_LTE -> R.drawable.ic_mode_auto
            ConnectMode.AUTO_HOME -> R.drawable.ic_mode_home
            ConnectMode.AUTO_LTE -> R.drawable.ic_mode_lte
            ConnectMode.MANUAL -> R.drawable.ic_mode_manual
        }
    }
}
