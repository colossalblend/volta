package com.ars3nb.volta.ui.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.ars3nb.volta.MainActivity
import com.ars3nb.volta.R
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.TrafficStats
import com.ars3nb.volta.ui.components.formatSpeed

object VpnNotificationHelper {
    const val CHANNEL_ID = "volta_vpn"
    const val NOTIFICATION_ID = 7101

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.vpn_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.home_notification_channel_description)
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
    }

    fun build(
        context: Context,
        serverName: String,
        connecting: Boolean,
        connected: Boolean,
        traffic: TrafficStats = TrafficStats()
    ): Notification {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectPi = PendingIntent.getService(
            context, 1,
            Intent(context, VoltaVpnService::class.java).setAction(VoltaVpnService.ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // "Connect" raises a foreground service: started as one, or Android 8+ refuses
        // it while the app is in the background.
        val toggleIntent = Intent(context, VoltaVpnService::class.java).setAction(VoltaVpnService.ACTION_TOGGLE)
        val toggleFlags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val togglePi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, 2, toggleIntent, toggleFlags)
        } else {
            PendingIntent.getService(context, 2, toggleIntent, toggleFlags)
        }

        val title = when {
            connecting -> context.getString(R.string.vpn_connecting)
            connected -> context.getString(R.string.vpn_connected)
            else -> context.getString(R.string.vpn_disconnected)
        }

        val text = buildString {
            append(serverName.ifBlank { "Volta" })
            if (connected && (traffic.downlinkSpeed > 0 || traffic.uplinkSpeed > 0)) {
                val res = context.resources
                append("  ↓ ${formatSpeed(res, traffic.downlinkSpeed)}  ↑ ${formatSpeed(res, traffic.uplinkSpeed)}")
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(connected || connecting)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setShowWhen(false)

        if (connected || connecting) {
            builder.addAction(
                R.drawable.ic_vpn_off,
                context.getString(R.string.action_disconnect),
                disconnectPi
            )
            if (connected) {
                builder.addAction(
                    R.drawable.ic_swap,
                    context.getString(R.string.action_repick),
                    PendingIntent.getBroadcast(
                        context,
                        3,
                        Intent(context, RepickReceiver::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }
        } else {
            builder.addAction(
                R.drawable.ic_vpn_key,
                context.getString(R.string.action_connect),
                togglePi
            )
        }

        return builder.build()
    }
}
