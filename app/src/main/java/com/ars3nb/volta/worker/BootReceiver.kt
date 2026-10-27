package com.ars3nb.volta.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ars3nb.volta.VoltaApp
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
                if (app.repository.autoConnect.first()) {
                    runCatching { app.repository.connectSelected() }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
