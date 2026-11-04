package com.ars3nb.volta.ui.components

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AirplanemodeActive
import androidx.compose.material.icons.rounded.SignalCellularAlt1Bar
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.R
import com.ars3nb.volta.core.vpn.InternetCheck
import com.ars3nb.volta.ui.theme.VoltaTheme

/** Whether there is internet under the tunnel, live while the screen is shown. */
@Composable
fun rememberInternetStatus(): State<InternetCheck.Status> {
    val context = LocalContext.current.applicationContext
    val flow = remember(context) { InternetCheck.watch(context) }
    val initial = remember(context) { InternetCheck.status(context) }
    return flow.collectAsStateWithLifecycle(initial)
}

/**
 * A calm note on the home screen when the VPN has nothing to go over (airplane mode, no
 * network) or only a weak mobile link. Nothing is shown while the internet is fine.
 */
@Composable
fun InternetBanner(status: InternetCheck.Status, modifier: Modifier = Modifier) {
    if (status == InternetCheck.Status.OK) return
    val context = LocalContext.current
    val palette = VoltaTheme.palette
    val (icon, title, text) = when (status) {
        InternetCheck.Status.AIRPLANE -> Triple(
            Icons.Rounded.AirplanemodeActive,
            R.string.network_airplane_title,
            R.string.network_airplane_text
        )
        InternetCheck.Status.NO_NETWORK -> Triple(
            Icons.Rounded.WifiOff,
            R.string.network_none_title,
            R.string.network_none_text
        )
        else -> Triple(
            Icons.Rounded.SignalCellularAlt1Bar,
            R.string.network_weak_title,
            R.string.network_weak_text
        )
    }
    val tint = if (status.blocksConnect) palette.poor else palette.fair
    VoltaCard(modifier = modifier, contentPadding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBubble(icon, tint)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                Hint(stringResource(text))
            }
            if (status.blocksConnect) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { openNetworkSettings(context, status) }) {
                    Text(stringResource(R.string.network_open_settings))
                }
            }
        }
    }
}

/** The system screen that fixes it; a TV box may lack the specific one, then the general one. */
private fun openNetworkSettings(context: Context, status: InternetCheck.Status) {
    val specific = when {
        status == InternetCheck.Status.AIRPLANE -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Settings.Panel.ACTION_INTERNET_CONNECTIVITY
        else -> Settings.ACTION_WIRELESS_SETTINGS
    }
    for (action in listOf(specific, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
        val opened = runCatching {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        if (opened) return
    }
}
