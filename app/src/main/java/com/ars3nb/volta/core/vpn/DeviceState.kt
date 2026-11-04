package com.ars3nb.volta.core.vpn

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager

/**
 * What the phone itself is doing, read from system services in microseconds — no
 * radio, no wake-up. The background check uses it to stay out of the way: no checks
 * behind a captive portal, longer gaps on a low battery.
 */
object DeviceState {

    data class Power(val powerSave: Boolean, val batteryLow: Boolean, val charging: Boolean)

    enum class Underlying {
        /** No physical network at all: nothing for a server check to learn. */
        NONE,

        /** A sign-in page (hotel, metro Wi-Fi) holds every connection; no server is to blame. */
        CAPTIVE,

        OK
    }

    fun power(context: Context): Power {
        val pm = context.getSystemService(PowerManager::class.java)
        // A sticky broadcast: reading it registers nothing and wakes nothing.
        val battery = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else 100
        return Power(
            powerSave = runCatching { pm?.isPowerSaveMode == true }.getOrDefault(false),
            batteryLow = percent in 0..LOW_BATTERY_PERCENT,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        )
    }

    /**
     * The physical network this app sees (it is outside its own VPN, so its default
     * network is the one under the tunnel).
     */
    fun underlying(context: Context): Underlying = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Underlying.OK
        val net = cm.activeNetwork ?: return Underlying.NONE
        val caps = cm.getNetworkCapabilities(net) ?: return Underlying.NONE
        when {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> Underlying.NONE
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) -> Underlying.CAPTIVE
            else -> Underlying.OK
        }
    }.getOrDefault(Underlying.OK)

    private const val LOW_BATTERY_PERCENT = 15
}
