package com.ars3nb.volta.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.ars3nb.volta.VoltaApp
import com.ars3nb.volta.core.vpn.VoltaVpnService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext as? VoltaApp ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                // An in-app update restarted Volta while the VPN was on: turn it back on.
                val updated = intent.action == Intent.ACTION_MY_PACKAGE_REPLACED &&
                    app.updater.takeReconnectFlag()
                // The boot broadcast allows a foreground-service start only for a short
                // while: hand over to the service now and let it pick the node there.
                // Without VPN consent (or with another VPN set) Android won't allow it.
                if ((updated || app.repository.autoConnect.first()) && VpnService.prepare(context) == null) {
                    runCatching { VoltaVpnService.autoConnect(context) }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
