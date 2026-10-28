package com.ars3nb.volta

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ars3nb.volta.core.HwidProvider
import com.ars3nb.volta.core.openflux.OpenFluxRunner
import com.ars3nb.volta.core.update.AppUpdater
import com.ars3nb.volta.core.vpn.NetworkWatcher
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.ConnectMode
import com.ars3nb.volta.data.repository.VoltaRepository
import com.ars3nb.volta.ui.widget.VoltaWidget
import com.ars3nb.volta.worker.ConnectionGuard
import com.ars3nb.volta.worker.SubscriptionRefreshWorker
// updateAll is a top-level extension in androidx.glance.appwidget, not a member.
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class VoltaApp : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var repository: VoltaRepository
        private set

    /** New builds from GitHub: the settings screen, the home banner and the worker. */
    val updater: AppUpdater by lazy { AppUpdater(this, appScope) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        OpenFluxRunner.init(this)
        // Constructing the repository also arms the VPN service failover provider.
        repository = VoltaRepository(this)

        appScope.launch {
            repository.ensureSeeded()
            // MediaDrm is slow on some devices; warm the cache off the main thread so no
            // screen ever blocks on the first read.
            runCatching { HwidProvider.getHwid(this@VoltaApp) }
        }

        // The home-screen widget has no lifecycle of its own — push state to it.
        // StateFlow already conflates equal values, so no distinctUntilChanged here.
        appScope.launch {
            VoltaVpnService.state.collectLatest {
                runCatching { VoltaWidget().updateAll(this@VoltaApp) }
            }
        }

        // Wi-Fi ↔ mobile data: the right server group may have changed under the tunnel.
        NetworkWatcher(this, appScope) { _ ->
            repository.forgetNetwork()
            if (repository.isConnected() &&
                repository.followNetwork.first() &&
                repository.connectMode.first() == ConnectMode.AUTO_HOME_LTE
            ) {
                repository.reevaluateAuto()
            }
        }.start()

        // Every two minutes while the VPN is on and the screen is on: the sites (Home+LTE
        // only) and the server in use, pinged directly; a dead one or the wrong group
        // is replaced.
        ConnectionGuard(appScope, repository).start(this)

        // Quiet background refresh. The worker itself honours each subscription's own
        // update interval, so waking hourly costs almost nothing; UPDATE (not KEEP) lets
        // a new build change the schedule instead of freezing the first one forever.
        val work = PeriodicWorkRequestBuilder<SubscriptionRefreshWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "volta_sub_refresh",
            ExistingPeriodicWorkPolicy.UPDATE,
            work
        )
    }

    companion object {
        lateinit var instance: VoltaApp
            private set
    }
}
