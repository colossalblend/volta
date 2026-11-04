package com.ars3nb.volta.worker

import android.content.Context
import com.ars3nb.volta.core.balancer.GuardPolicy
import com.ars3nb.volta.core.vpn.DeviceState
import com.ars3nb.volta.core.vpn.ScreenState
import com.ars3nb.volta.core.vpn.VoltaVpnService
import com.ars3nb.volta.data.model.VpnConnectionState
import com.ars3nb.volta.data.repository.VoltaRepository
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The background check while the VPN is on and the screen is on.
 *
 * Every two minutes (four on a low battery) the server in use is pinged directly and,
 * in Home+LTE with LTE servers present, the sites are asked what this network lets
 * through (see [VoltaRepository.guardCheck]): a dead, silent or clearly slow node is
 * replaced, and the group is only measured when that calls for it. With the screen off
 * nothing runs at all, and a check that fell due meanwhile runs the moment the screen
 * comes back on.
 */
class ConnectionGuard(
    private val scope: CoroutineScope,
    private val repository: VoltaRepository
) {
    private val checking = AtomicBoolean(false)

    @Volatile
    private var lastCheck = 0L

    @Volatile
    private var lastFull = 0L

    private lateinit var appContext: Context

    fun start(context: Context) {
        appContext = context.applicationContext
        ScreenState.start(context)
        scope.launch {
            combine(VoltaVpnService.state, ScreenState.on) { state, screenOn ->
                state == VpnConnectionState.CONNECTED && screenOn
            }
                .distinctUntilChanged()
                .collectLatest { active -> if (active) watch() }
        }
    }

    private suspend fun watch() {
        while (true) {
            // Counted from the last check or the connect, whichever is later: a fresh
            // tunnel was just checked by the service, an overdue check runs right away.
            val since = maxOf(lastCheck, VoltaVpnService.connectedSince.value)
            // Two minutes; four on Android's battery saver or a low battery.
            val power = DeviceState.power(appContext)
            val every = GuardPolicy.interval(power.powerSave, power.batteryLow, power.charging)
            delay((every - (System.currentTimeMillis() - since)).coerceAtLeast(0))

            val now = System.currentTimeMillis()
            lastCheck = now
            // Clash url-test on top: now and then the whole group is compared again, and
            // a clearly faster node takes over. Hysteresis keeps that from flapping.
            val fullEvery = if (repository.batterySaver.first()) FULL_SAVER_MS else FULL_EVERY_MS
            val full = repository.autoRecheck.first() &&
                now - maxOf(lastFull, VoltaVpnService.connectedSince.value) >= fullEvery

            if (!checking.compareAndSet(false, true)) continue
            if (full) lastFull = now
            // Own job: a server switch reconnects, which restarts this loop.
            scope.launch {
                try {
                    runCatching { repository.guardCheck(full) }
                } finally {
                    checking.set(false)
                }
            }
        }
    }

    private companion object {
        val FULL_EVERY_MS = TimeUnit.MINUTES.toMillis(15)
        val FULL_SAVER_MS = TimeUnit.MINUTES.toMillis(30)
    }
}
