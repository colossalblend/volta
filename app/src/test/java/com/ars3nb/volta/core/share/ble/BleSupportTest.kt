package com.ars3nb.volta.core.share.ble

import android.Manifest
import com.ars3nb.volta.core.share.ble.BleSupport.Gate
import org.junit.Assert.assertEquals
import org.junit.Test

class BleSupportTest {

    @Test
    fun android12AndNewerAskForNearbyDevices() {
        val nearby = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        assertEquals(nearby, BleSupport.runtimePermissions(31, scans = true))
        assertEquals(nearby, BleSupport.runtimePermissions(36, scans = false))
    }

    @Test
    fun olderAndroidAsksForLocationOnlyToScan() {
        assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), BleSupport.runtimePermissions(26, scans = true))
        assertEquals(emptyList<String>(), BleSupport.runtimePermissions(30, scans = false))
    }

    @Test
    fun gatesComeInOrder() {
        fun gate(sdk: Int, ble: Boolean = true, missing: Boolean = false, on: Boolean = true, scans: Boolean = true, location: Boolean = true) =
            BleSupport.gate(sdk, ble, missing, on, scans, location)

        assertEquals(Gate.NO_BLE, gate(34, ble = false, missing = true, on = false))
        // Android 12+: the permission first, since turning Bluetooth on needs it.
        assertEquals(Gate.PERMISSION, gate(34, missing = true, on = false))
        assertEquals(Gate.BLUETOOTH_OFF, gate(34, on = false))
        assertEquals(Gate.READY, gate(34, location = false))
        // Android 8–11: Bluetooth first, then location permission, then location itself.
        assertEquals(Gate.BLUETOOTH_OFF, gate(29, missing = true, on = false))
        assertEquals(Gate.PERMISSION, gate(29, missing = true))
        assertEquals(Gate.LOCATION_OFF, gate(29, location = false))
        assertEquals(Gate.READY, gate(29, scans = false, location = false))
        assertEquals(Gate.READY, gate(29))
    }
}
