package com.ars3nb.volta.ui.tile

import android.app.PendingIntent
import android.content.Context
import com.ars3nb.volta.core.locale.AppLanguage
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

    /** In the language picked in Settings (Android 12 and older; 13+ does it itself). */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

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
                repo.autoPicking,
                repo.hasLteServers
            ) { state, server, current, picking, hasLte -> TileModel(state, server, current, picking, hasLte) }
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
        // The LTE tile with no LTE server to go to: say so instead of connecting nowhere.
        if (mode == ConnectMode.AUTO_LTE && qsTile?.state == Tile.STATE_UNAVAILABLE) {
            Toast.makeText(app, getString(R.string.home_tile_no_lte), Toast.LENGTH_SHORT).show()
            return
        }
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
            if (!VoltaVpnService.autoConnect(this, mode.name)) {
                // Refused: remember the group and let the app's button finish the job.
                app.appScope.launch { repo.setConnectMode(mode) }
                openApp()
            }
            return
        }
        // The tile's own scope dies moments after the click; the app scope outlives it.
        val modeName = title(this, mode)
        val failed = getString(R.string.home_tile_connect_failed)
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
                    Toast.makeText(app, "$modeName: ${e.message ?: failed}", Toast.LENGTH_LONG).show()
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
            // Nothing to connect to in this group: greyed out with the reason.
            mode == ConnectMode.AUTO_LTE && !model.hasLte -> {
                tile.state = Tile.STATE_UNAVAILABLE
                subtitle = getString(R.string.home_no_servers)
            }

            ours && model.picking && model.state != VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = getString(R.string.home_tile_picking)
            }

            ours && model.state == VpnConnectionState.CONNECTING -> {
                // ACTIVE, not UNAVAILABLE: an unavailable tile swallows the tap that
                // would cancel a connect that hangs.
                tile.state = Tile.STATE_ACTIVE
                subtitle = getString(R.string.vpn_connecting)
            }

            ours && model.state == VpnConnectionState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                subtitle = model.server.ifBlank { getString(R.string.home_tile_on) }
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                // Home+LTE without LTE servers runs as Home: the shade says so.
                subtitle = getString(
                    if (mode == ConnectMode.AUTO_HOME_LTE && !model.hasLte) R.string.home_tile_off_as_home
                    else R.string.home_state_off
                )
            }
        }
        tile.label = label(mode)
        tile.icon = Icon.createWithResource(this, icon(mode))
        tile.contentDescription = if (tile.state == Tile.STATE_ACTIVE) {
            getString(R.string.home_tile_on_description, title(this, mode), subtitle)
        } else {
            getString(R.string.home_tile_off_description, title(this, mode))
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
        val picking: Boolean,
        val hasLte: Boolean
    )

    companion object {
        /** The mode's full name, "Auto Home+LTE", in [context]'s language. */
        fun title(context: Context, mode: ConnectMode): String = context.getString(
            when (mode) {
                ConnectMode.AUTO_HOME_LTE -> R.string.home_mode_auto_home_lte
                ConnectMode.AUTO_HOME -> R.string.home_mode_auto_home
                ConnectMode.AUTO_LTE -> R.string.home_mode_auto_lte
                ConnectMode.MANUAL -> R.string.home_mode_own_server
            }
        )

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
            onResult(manualHint(context))
            return
        }
        val bar = context.getSystemService(android.app.StatusBarManager::class.java)
        val cls = when (mode) {
            ConnectMode.AUTO_HOME -> HomeTileService::class.java
            ConnectMode.AUTO_LTE -> LteTileService::class.java
            else -> HomeLteTileService::class.java
        }
        if (bar == null) {
            onResult(manualHint(context))
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
                            context.getString(R.string.home_tile_added, GroupTileService.label(mode))

                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ->
                            context.getString(R.string.home_tile_already_added, GroupTileService.label(mode))

                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED ->
                            context.getString(R.string.home_tile_not_added)

                        else -> manualHint(context)
                    }
                )
            }
        }.onFailure { onResult(manualHint(context)) }
    }

    /** How to put the tiles in the shade by hand (Android 12 and older, or a refusal). */
    fun manualHint(context: android.content.Context): String = context.getString(R.string.home_tile_manual_hint)

    /** [manualHint] for a caller with no Context at hand, in the app's language. */
    val MANUAL_HINT: String
        get() = manualHint(VoltaApp.instance)
}
