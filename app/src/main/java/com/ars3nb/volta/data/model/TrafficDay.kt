package com.ars3nb.volta.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Bytes that went through the VPN server on one calendar day of the phone's clock.
 * Only traffic of the proxy outbound counts: sites the routing sends direct never touch
 * the server, so they are not part of what a provider bills or limits.
 */
@Entity(tableName = "traffic_days")
data class TrafficDayEntity(
    /** The day as `LocalDate.toEpochDay()`. */
    @PrimaryKey val day: Long,
    val uplinkBytes: Long = 0,
    val downlinkBytes: Long = 0
) {
    val totalBytes: Long get() = uplinkBytes + downlinkBytes
}
