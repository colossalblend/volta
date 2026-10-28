package com.ars3nb.volta.core.vpn

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the screen is on. Background checks only run while it is: with the phone in
 * a pocket they wait, and the one that fell due runs as soon as the screen comes on.
 */
object ScreenState {

    private val _on = MutableStateFlow(true)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        _on.value = runCatching {
            app.getSystemService(PowerManager::class.java)?.isInteractive
        }.getOrNull() ?: true

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> _on.value = true
                    Intent.ACTION_SCREEN_OFF -> _on.value = false
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Screen on/off is only ever delivered to receivers registered at runtime.
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }
    }
}
