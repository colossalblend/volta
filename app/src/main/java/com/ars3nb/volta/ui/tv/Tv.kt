package com.ars3nb.volta.ui.tv

import android.app.UiModeManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build

/**
 * Android TV and Google TV boxes run the same APK as phones. What differs is decided
 * here, in plain functions, so it can be tested without a TV.
 */
object Tv {

    /** This device is a TV: the system says so, or it has the TV launcher. */
    fun isTv(context: Context): Boolean {
        val mode = runCatching {
            context.getSystemService(UiModeManager::class.java)?.currentModeType
        }.getOrNull() ?: Configuration.UI_MODE_TYPE_UNDEFINED
        val leanback = runCatching {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        }.getOrDefault(false)
        return isTv(mode, leanback)
    }

    /**
     * [uiModeType] is UiModeManager.currentModeType. Some boxes report the TV mode
     * without the leanback feature, and some the other way round, so either is enough.
     */
    fun isTv(uiModeType: Int, hasLeanback: Boolean): Boolean =
        hasLeanback || uiModeType == Configuration.UI_MODE_TYPE_TELEVISION

    /** What the Settings screen shows on this device. */
    fun settingsRows(tv: Boolean, sdk: Int): SettingsRows = SettingsRows(
        // A TV has no notification shade, so no Quick Settings tiles.
        tiles = when {
            tv -> TileRows.NONE
            sdk >= Build.VERSION_CODES.TIRAMISU -> TileRows.ADD_BUTTONS
            else -> TileRows.HOW_TO
        },
        // The TV launcher shows the banner, never the icon the picker would change.
        appIcon = !tv
    )

    /** Starts [intent]; false when the device has nothing to open it with (often on a TV). */
    fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }
}

/** The Settings blocks that depend on the device. */
data class SettingsRows(
    val tiles: TileRows,
    val appIcon: Boolean
)

enum class TileRows {
    /** No tiles block at all (TV). */
    NONE,

    /** Buttons that ask Android to add a tile (Android 13+). */
    ADD_BUTTONS,

    /** Only a hint on how to add tiles by hand (older Android). */
    HOW_TO
}
