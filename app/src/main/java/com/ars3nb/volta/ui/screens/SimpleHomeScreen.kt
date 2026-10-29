package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.ConnectPhase
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.PowerButton
import com.ars3nb.volta.ui.theme.VoltaTheme

/**
 * Простой режим: the whole app is one big button. It turns the VPN on (Home+LTE picks
 * the server by itself) and the same button turns it off. Big words say what is going
 * on; nothing else on the screen can be pressed by mistake.
 */
@Composable
fun SimpleHomeScreen(vm: VoltaViewModel, modifier: Modifier = Modifier) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val phase by vm.connectPhase.collectAsStateWithLifecycle()
    val palette = VoltaTheme.palette
    var askLeave by remember { mutableStateOf(false) }

    val working = phase != ConnectPhase.IDLE
    val vpn = state.vpnState
    val on = state.isConnected && !working
    val starting = working || vpn == VpnConnectionState.CONNECTING
    val noServers = state.servers.isEmpty() && !state.isConnected

    val (title, hint) = when {
        noServers -> "Нет серверов" to "Попросите близких добавить подписку в обычном режиме"
        starting -> "Включаю…" to "Подождите несколько секунд"
        vpn == VpnConnectionState.DISCONNECTING -> "Выключаю…" to ""
        on -> "VPN включён" to (state.ipInfo?.country?.takeIf { it.isNotBlank() }
            ?: "Чтобы выключить, нажмите на кнопку")
        vpn == VpnConnectionState.ERROR -> "Не получилось" to "Нажмите на кнопку ещё раз"
        else -> "VPN выключен" to "Чтобы включить, нажмите на кнопку"
    }

    val wash by animateColorAsState(
        targetValue = when {
            on -> palette.good.copy(alpha = if (palette.isDark) 0.20f else 0.16f)
            starting -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            vpn == VpnConnectionState.ERROR -> palette.poor.copy(alpha = 0.12f)
            else -> palette.heroTop
        },
        animationSpec = tween(700),
        label = "simpleWash"
    )

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .background(Brush.verticalGradient(listOf(wash, Color.Transparent)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "VOLTA",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )

            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val buttonSize = min(min(maxWidth * 0.88f, maxHeight * 0.62f), 380.dp)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PowerButton(
                        state = vpn,
                        working = working,
                        enabled = !noServers,
                        onClick = vm::toggleConnect,
                        size = buttonSize
                    )
                    Spacer(Modifier.height(20.dp))
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = {
                            (slideInVertically { it / 2 } + fadeIn()) togetherWith
                                (slideOutVertically { -it / 2 } + fadeOut())
                        },
                        label = "simpleTitle"
                    ) { text ->
                        Text(
                            text,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (hint.isNotBlank()) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp)
                        )
                    }
                }
            }

            // The only other thing on the screen, small and out of the way.
            TextButton(onClick = { askLeave = true }) {
                Text(
                    "Обычный режим",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text("Выйти из простого режима?") },
            text = {
                Text("Появятся все настройки и списки серверов. Вернуться можно в Настройках.")
            },
            confirmButton = {
                Button(onClick = {
                    askLeave = false
                    vm.setSimpleMode(false)
                }) { Text("Выйти") }
            },
            dismissButton = {
                TextButton(onClick = { askLeave = false }) { Text("Остаться") }
            }
        )
    }
}
