package com.ars3nb.volta.core.share.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Whether Bluetooth sharing can run on this device, and what is still in the way.
 * The decisions are plain functions ([runtimePermissions], [gate]) so they are tested
 * without a radio.
 */
object BleSupport {

    /** What stands between opening the sheet and a session, in the order it is asked. */
    enum class Gate {
        /** No Bluetooth LE at all: the feature is not offered. */
        NO_BLE,

        /** A runtime permission is missing (Nearby devices; location up to Android 11). */
        PERMISSION,

        /** Bluetooth is off. */
        BLUETOOTH_OFF,

        /** Android up to 11 finds nothing while location is off. */
        LOCATION_OFF,

        READY
    }

    /**
     * The permissions to ask for when the user opens the feature. Android 12+: the three
     * Nearby devices ones (SCAN is declared neverForLocation). Android 8–11: BLUETOOTH and
     * BLUETOOTH_ADMIN are granted at install; scanning also needs fine location.
     */
    fun runtimePermissions(sdk: Int, scans: Boolean): List<String> = when {
        sdk >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        scans -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyList()
    }

    /**
     * The next thing to sort out. On Android 12+ the permissions come first (turning
     * Bluetooth on needs BLUETOOTH_CONNECT); before that Bluetooth comes first, because
     * whether the receiver must scan (and so needs location) is known only once it is on.
     */
    fun gate(
        sdk: Int,
        hasBle: Boolean,
        missingPermissions: Boolean,
        bluetoothOn: Boolean,
        scans: Boolean,
        locationOn: Boolean
    ): Gate = when {
        !hasBle -> Gate.NO_BLE
        sdk >= Build.VERSION_CODES.S && missingPermissions -> Gate.PERMISSION
        !bluetoothOn -> Gate.BLUETOOTH_OFF
        missingPermissions -> Gate.PERMISSION
        sdk < Build.VERSION_CODES.S && scans && !locationOn -> Gate.LOCATION_OFF
        else -> Gate.READY
    }

    fun adapter(context: Context): BluetoothAdapter? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /** The device has Bluetooth LE: the entries to the feature are shown only then. */
    fun hasBle(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter(context) != null

    fun isOn(context: Context): Boolean = runCatching { adapter(context)?.isEnabled == true }.getOrDefault(false)

    /**
     * The radio can advertise, so this device can be the one that is found. Cheap TV
     * boxes often cannot; known only while Bluetooth is on.
     */
    @SuppressLint("MissingPermission")
    fun canAdvertise(context: Context): Boolean = runCatching {
        val a = adapter(context)
        a != null && a.isEnabled && a.isMultipleAdvertisementSupported && a.bluetoothLeAdvertiser != null
    }.getOrDefault(false)

    fun missing(context: Context, scans: Boolean): List<String> =
        runtimePermissions(Build.VERSION.SDK_INT, scans).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    fun locationOn(context: Context): Boolean = runCatching {
        val lm = context.getSystemService(LocationManager::class.java) ?: return@runCatching true
        LocationManagerCompat.isLocationEnabled(lm)
    }.getOrDefault(true)

    /** Whether this side will scan: a sender always does, a receiver only if it cannot advertise. */
    fun scans(context: Context, sender: Boolean): Boolean = sender || !canAdvertise(context)
}
