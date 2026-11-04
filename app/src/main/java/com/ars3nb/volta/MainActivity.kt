package com.ars3nb.volta

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.ui.VoltaViewModel
import com.ars3nb.volta.ui.navigation.VoltaAppRoot

class MainActivity : ComponentActivity() {

    private val vm: VoltaViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        vm.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The tunnel still runs without it; only the status notification is hidden. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

        setContent {
            val needsPermission by vm.needsVpnPermission.collectAsStateWithLifecycle()
            LaunchedEffect(needsPermission) {
                if (needsPermission) {
                    val intent = VpnService.prepare(this@MainActivity)
                    if (intent != null) vpnPermission.launch(intent)
                    else vm.onVpnPermissionResult(true)
                }
            }
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                VoltaAppRoot(vm = vm)
            }
        }

        handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcut(intent)
    }

    /** Android 13+ hides foreground-service notifications without this grant. */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            "com.ars3nb.volta.CONNECT" -> vm.connect()
            "com.ars3nb.volta.DISCONNECT" -> vm.disconnect()
            "com.ars3nb.volta.TOGGLE" -> vm.toggleConnect()
            "com.ars3nb.volta.CONNECT_MODE" -> intent.getStringExtra("mode")
                ?.let { name -> ConnectMode.entries.firstOrNull { it.name == name } }
                ?.let(vm::connectIn)
            Intent.ACTION_VIEW -> intent.data?.let { vm.handleDeepLink(it.toString()) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.trim()?.takeIf { it.isNotBlank() }?.let { vm.importLink(it) }
        }
        // A re-delivered intent must not import the same link twice.
        intent?.action = null
    }
}
