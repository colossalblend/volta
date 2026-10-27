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
import java.util.Locale

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
                description = "Статус VPN Volta"
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

        val togglePi = PendingIntent.getService(
            context, 2,
            Intent(context, VoltaVpnService::class.java).setAction(VoltaVpnService.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = when {
            connecting -> context.getString(R.string.vpn_connecting)
            connected -> context.getString(R.string.vpn_connected)
            else -> context.getString(R.string.vpn_disconnected)
        }

        val text = buildString {
            append(serverName.ifBlank { "Volta" })
            if (connected && (traffic.downlinkSpeed > 0 || traffic.uplinkSpeed > 0)) {
                append("  ↓ ${speed(traffic.downlinkSpeed)}  ↑ ${speed(traffic.uplinkSpeed)}")
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
        } else {
            builder.addAction(
                R.drawable.ic_vpn_key,
                context.getString(R.string.action_connect),
                togglePi
            )
        }

        return builder.build()
    }

    private fun speed(bytesPerSecond: Long): String = when {
        bytesPerSecond >= 1024L * 1024 ->
            String.format(Locale.US, "%.1f МБ/с", bytesPerSecond / (1024.0 * 1024.0))

        bytesPerSecond >= 1024 ->
            String.format(Locale.US, "%.0f КБ/с", bytesPerSecond / 1024.0)

        else -> "$bytesPerSecond Б/с"
    }
}
