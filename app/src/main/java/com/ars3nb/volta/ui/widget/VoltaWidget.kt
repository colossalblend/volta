package com.ars3nb.volta.ui.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ars3nb.volta.MainActivity
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.VpnConnectionState

class VoltaWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent(
                    state = VoltaVpnService.state.value,
                    serverName = VoltaVpnService.serverName.value
                )
            }
        }
    }
}

@Composable
private fun WidgetContent(state: VpnConnectionState, serverName: String) {
    val connected = state == VpnConnectionState.CONNECTED
    val status = when (state) {
        VpnConnectionState.CONNECTED -> "Подключено"
        VpnConnectionState.CONNECTING -> "Подключение…"
        VpnConnectionState.DISCONNECTING -> "Отключение…"
        VpnConnectionState.ERROR -> "Ошибка"
        VpnConnectionState.DISCONNECTED -> "Выключено"
    }
    val bg = if (connected) Color(0xFF1D2A24) else Color(0xFF16181C)
    val accent = if (connected) Color(0xFF44C08A) else Color(0xFFE08A4C)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(bg))
            .cornerRadius(20.dp)
            .padding(16.dp)
            // Tapping opens the app; toggling from a widget would need consent anyway.
            .clickable(actionStartActivity<MainActivity>()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = Alignment.Start
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Volta",
                style = TextStyle(
                    color = ColorProvider(Color(0xFFF3EFE9)),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
        Spacer(GlanceModifier.height(6.dp))
        Text(
            text = status,
            style = TextStyle(
                color = ColorProvider(accent),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
        if (serverName.isNotBlank()) {
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = serverName,
                maxLines = 1,
                style = TextStyle(color = ColorProvider(Color(0xFF9AA1AC)), fontSize = 12.sp)
            )
        }
    }
}

class VoltaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = VoltaWidget()
}
