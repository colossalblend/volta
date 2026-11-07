package com.ars3nb.volta.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.MotionDurationScale

/**
 * How long Compose animations take, for the whole window: 0 with «Lite-оформление» on —
 * every animation in the app ends the moment it starts, including the ones nobody wrote
 * a check for (list items, sheets, dialogs, crossfades, infinite loops) — and otherwise
 * the system's own "animation duration scale", the same value Compose uses by default.
 *
 * Handed to the window's Recomposer (MainActivity), which uses a [MotionDurationScale]
 * found in its context instead of its own; read on every frame, so a switch takes effect
 * at once.
 */
class LiteMotionScale(private val context: Context) : MotionDurationScale {

    @Volatile
    var lite: Boolean = false

    @Volatile
    private var system: Float = readSystem()

    override val scaleFactor: Float
        get() = if (lite) 0f else system

    /** Settings can change while the app is away: re-read on return. */
    fun refreshSystem() {
        system = readSystem()
    }

    private fun readSystem(): Float = runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }.getOrDefault(1f)
}
