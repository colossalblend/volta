package com.ars3nb.volta.ui.components

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.core.openflux.OpenFluxLink
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.ui.openflux.OpenFluxCaptchaActivity

/** On the home screen while an OpenFlux carrier waits for a captcha to be passed. */
@Composable
fun OpenFluxCaptchaBanner(captcha: OpenFluxRunner.Status.Captcha) {
    val context = LocalContext.current
    VoltaCard(selected = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Rounded.VerifiedUser,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("OpenFlux просит пройти проверку", style = MaterialTheme.typography.titleSmall)
                Hint(
                    OpenFluxLink.carrierName(captcha.carrier.substringBefore('-')) +
                        if (captcha.proxy.isNotBlank()) " на сервере" else ""
                )
            }
            Button(onClick = {
                context.startActivity(
                    Intent(context, OpenFluxCaptchaActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Открыть") }
        }
    }
}
