package com.ars3nb.volta.ui.screens

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.ui.ConnectPhase
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.components.InternetBanner
import com.ars3nb.volta.ui.components.PowerButton
import com.ars3nb.volta.ui.components.rememberInternetStatus
import com.ars3nb.volta.ui.theme.VoltaTheme
import com.ars3nb.volta.ui.tv.StartFocus

/**
 * Simple mode: the whole app is one big button. It turns the VPN on (Home+LTE picks
 * the server by itself) and the same button turns it off. Big words say what is going
 * on; nothing else on the screen can be pressed by mistake.
 */
@Composable
fun SimpleHomeScreen(vm: VoltaViewModel, modifier: Modifier = Modifier) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val phase by vm.connectPhase.collectAsStateWithLifecycle()
    val internet by rememberInternetStatus()
    val palette = VoltaTheme.palette
    var askLeave by remember { mutableStateOf(false) }

    val working = phase != ConnectPhase.IDLE
    val vpn = state.vpnState
    val on = state.isConnected && !working
    val starting = working || vpn == VpnConnectionState.CONNECTING
    val noServers = state.servers.isEmpty() && !state.isConnected

    // On a TV the remote starts on the button (once there are servers to connect to).
    val startFocus = remember { FocusRequester() }
    StartFocus(startFocus, target = !noServers, settled = !noServers)

    val (title, hint) = when {
        noServers -> stringResource(R.string.home_no_servers) to stringResource(R.string.home_simple_no_servers_hint)
        starting -> stringResource(R.string.home_simple_turning_on) to stringResource(R.string.home_simple_wait)
        vpn == VpnConnectionState.DISCONNECTING -> stringResource(R.string.home_simple_turning_off) to ""
        on -> stringResource(R.string.home_simple_on) to (state.ipInfo?.country?.takeIf { it.isNotBlank() }
            ?: stringResource(R.string.home_simple_tap_to_turn_off))
        vpn == VpnConnectionState.ERROR ->
            stringResource(R.string.home_simple_failed) to stringResource(R.string.home_simple_tap_again)
        else -> stringResource(R.string.home_simple_off) to stringResource(R.string.home_simple_tap_to_turn_on)
    }

    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
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
                "Volta",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            InternetBanner(internet, Modifier.padding(top = 10.dp))

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
                        size = buttonSize,
                        modifier = Modifier.focusRequester(startFocus)
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
                            color = when {
                                on -> palette.good
                                vpn == VpnConnectionState.ERROR -> palette.poor
                                else -> MaterialTheme.colorScheme.onBackground
                            },
                            textAlign = TextAlign.Center
                        )
                    }
                    if (hint.isNotBlank()) {
                        Text(
                            hint,
                            style = MaterialTheme.typography.titleMedium.copy(fontSize = 19.sp, lineHeight = 26.sp),
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
                    stringResource(R.string.home_simple_normal_mode),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (askLeave) {
        AlertDialog(
            onDismissRequest = { askLeave = false },
            title = { Text(stringResource(R.string.home_simple_leave_title)) },
            text = {
                Text(stringResource(R.string.home_simple_leave_text))
            },
            confirmButton = {
                Button(onClick = {
                    askLeave = false
                    vm.setSimpleMode(false)
                }) { Text(stringResource(R.string.home_simple_leave)) }
            },
            dismissButton = {
                TextButton(onClick = { askLeave = false }) { Text(stringResource(R.string.home_simple_stay)) }
            }
        )
    }
}
