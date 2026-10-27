package com.ars3nb.volta.core

import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import com.ars3nb.volta.BuildConfig
import java.security.MessageDigest
import java.util.UUID

/**
 * Hardware ID that survives app reinstalls on the same device.
 *
 * Primary source: Widevine MediaDrm device unique ID (stable across reinstalls).
 * Fallbacks: ANDROID_ID + Build fingerprint hash.
 * Salted with app brand so different apps get different IDs for the same device.
 */
object HwidProvider {

    private val WIDEVINE_UUID: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

    @Volatile
    private var cached: String? = null

    fun getHwid(context: Context): String {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val raw = buildRawId(context)
            val salted = "${BuildConfig.HWID_SALT}:$raw"
            val id = sha256(salted).take(32)
            cached = id
            return id
        }
    }

    /** Short form for UI (8 hex chars). */
    fun getShortHwid(context: Context): String = getHwid(context).take(8)

    private fun buildRawId(context: Context): String {
        val parts = mutableListOf<String>()

        mediaDrmId()?.let { parts += it }

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
        if (androidId.isNotBlank()) parts += androidId

        parts += Build.BOARD
        parts += Build.BRAND
        parts += Build.DEVICE
        parts += Build.HARDWARE
        parts += Build.MANUFACTURER
        parts += Build.MODEL
        parts += Build.PRODUCT

        return parts.joinToString("|")
    }

    private fun mediaDrmId(): String? {
        return try {
            val drm = MediaDrm(WIDEVINE_UUID)
            try {
                val bytes = drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                bytes.joinToString("") { "%02x".format(it) }
            } finally {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    drm.close()
                } else {
                    @Suppress("DEPRECATION")
                    drm.release()
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
