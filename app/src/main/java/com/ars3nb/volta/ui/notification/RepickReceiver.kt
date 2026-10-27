package com.ars3nb.volta.ui.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ars3nb.volta.VoltaApp
import kotlinx.coroutines.launch

/** "Подобрать сервер" in the VPN notification: re-runs the auto choice on a live tunnel. */
class RepickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? VoltaApp ?: return
        val pending = goAsync()
        app.appScope.launch {
            try {
                runCatching { app.repository.reevaluateAuto() }
            } finally {
                pending.finish()
            }
        }
    }
}
