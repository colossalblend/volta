package com.ars3nb.volta.ui.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ars3nb.volta.MainActivity
import com.ars3nb.volta.R
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.model.VpnConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One tile per auto group, so the shade holds three buttons: «Home+LTE», «Home», «LTE».
 *
 * A tap turns the VPN on in that tile's group; a tap on the lit tile turns it off.
 * With the VPN already up in another group the tap moves it here instead. Only the
 * tile of the group in use is lit, so the shade itself shows how Volta is connected.
 */
abstract class GroupTileService(private val mode: ConnectMode) : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        val repo = VoltaApp.instance.repository
        job?.cancel()
        job = scope.launch {
            combine(
                VoltaVpnService.state,
                VoltaVpnService.serverName,
                repo.connectMode,
                repo.autoPicking
            ) { state, server, current, picking -> TileModel(state, server, current, picking) }
                .collectLatest { updateTile(it) }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun { toggle() }
        } else {
            toggle()
        }
    }

    private fun toggle() {
        val app = VoltaApp.instance
        val repo = app.repository
        // Consent can only be granted from an Activity: remember the group, then open
        // the app, where the big button finishes the job.
        if (VpnService.prepare(this) != null) {
            app.appScope.launch { repo.setConnectMode(mode) }
            openApp()
            return
        }
        if (repo.autoPicking.value) return
        val state = VoltaVpnService.state.value
        val running = state == VpnConnectionState.CONNECTED ||
            state == VpnConnectionState.CONNECTING
        if (!running) {
            // Straight into the foreground service while the tap still counts: the
            // pick runs there, so closing the shade can't get the start refused.
            VoltaVpnService.autoConnect(this, mode.name)
            return
        }
        // The tile's own scope dies moments after the click; the app scope outlives it.
        app.appScope.launch {
            if (repo.connectMode.first() == mode) {
                repo.disconnect()
                return@launch
            }
            // Already up in another group: move it here (the running service keeps the
            // app in the foreground, so the reconnect is allowed).
            repo.setConnectMode(mode)
            runCatching { repo.connectWithMode() }.onFailure { e ->
                // Cancelled from the app's button: nothing went wrong.
                if (e is CancellationException) return@onFailure
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        app,
                        "${mode.title}: ${e.message ?: "не удалось подключиться"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile(model: TileModel) {
        val tile = qsTile ?: return
        val ours = model.mode == mode
        val subtitle: String
        when {
            ours && model.picking && model.state != VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = "Подбираю сервер…"
            }

            ours && model.state == VpnConnectionState.CONNECTING -> {
                // ACTIVE, not UNAVAILABLE: an unavailable tile swallows the tap that
                // would cancel a connect that hangs.
                tile.state = Tile.STATE_ACTIVE
                subtitle = "Подключение…"
            }

            ours && model.state == VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = model.server.ifBlank { "Включено" }
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                subtitle = "Выключено"
            }
        }
        tile.label = label(mode)
        tile.icon = Icon.createWithResource(this, icon(mode))
        tile.contentDescription = if (tile.state == Tile.STATE_ACTIVE) {
            "Volta ${mode.title}: $subtitle. Нажмите, чтобы выключить"
        } else {
            "Volta ${mode.title} выключен. Нажмите, чтобы включить"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = subtitle
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private data class TileModel(
        val state: VpnConnectionState,
        val server: String,
        val mode: ConnectMode,
        val picking: Boolean
    )

    companion object {
        fun label(mode: ConnectMode): String = when (mode) {
            ConnectMode.AUTO_HOME_LTE -> "Home+LTE"
            ConnectMode.AUTO_HOME -> "Home"
            ConnectMode.AUTO_LTE -> "LTE"
            ConnectMode.MANUAL -> "Volta"
        }

        fun icon(mode: ConnectMode): Int = when (mode) {
            ConnectMode.AUTO_HOME_LTE -> R.drawable.ic_mode_auto
            ConnectMode.AUTO_HOME -> R.drawable.ic_mode_home
            ConnectMode.AUTO_LTE -> R.drawable.ic_mode_lte
            ConnectMode.MANUAL -> R.drawable.ic_mode_manual
        }
    }
}

class HomeLteTileService : GroupTileService(ConnectMode.AUTO_HOME_LTE)

class HomeTileService : GroupTileService(ConnectMode.AUTO_HOME)

class LteTileService : GroupTileService(ConnectMode.AUTO_LTE)

/**
 * Puts a group tile into the shade from inside the app (Android 13+): the system
 * asks "Add tile?" and the user taps once, instead of hunting through the editor.
 */
object TileInstaller {

    val supported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun request(context: android.content.Context, mode: ConnectMode, onResult: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onResult(MANUAL_HINT)
            return
        }
        val bar = context.getSystemService(android.app.StatusBarManager::class.java)
        val cls = when (mode) {
            ConnectMode.AUTO_HOME -> HomeTileService::class.java
            ConnectMode.AUTO_LTE -> LteTileService::class.java
            else -> HomeLteTileService::class.java
        }
        if (bar == null) {
            onResult(MANUAL_HINT)
            return
        }
        runCatching {
            bar.requestAddTileService(
                android.content.ComponentName(context, cls),
                "Volta ${GroupTileService.label(mode)}",
                Icon.createWithResource(context, GroupTileService.icon(mode)),
                context.mainExecutor
            ) { code ->
                onResult(
                    when (code) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ->
                            "Плитка «${GroupTileService.label(mode)}» добавлена в шторку"

                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            "Плитка «${GroupTileService.label(mode)}» уже в шторке"

                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                            "Плитка не добавлена"

                        else -> MANUAL_HINT
                    }
                )
            }
        }.onFailure { onResult(MANUAL_HINT) }
    }

    const val MANUAL_HINT =
        "Откройте шторку, нажмите карандаш и перетащите плитки Volta на панель"
}
