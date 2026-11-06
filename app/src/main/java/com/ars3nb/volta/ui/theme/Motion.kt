package com.ars3nb.volta.ui.theme

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * How things move. Answers to a touch are springs, so they keep the finger's energy and
 * settle by themselves; nothing moves on its own except the power disc, and only while
 * there is something to show (connecting, traffic).
 */
object Motion {
    /** A press: quick to give, a little bounce back. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 520f)

    /** A thumb or indicator sliding to where the finger went. */
    fun <T> slide(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)

    /** Something arriving: settles without overshoot. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
}

/** «Lite-оформление» in Settings: fewer animations, for slower phones. */
val LocalLiteMode = staticCompositionLocalOf { false }

/**
 * The system's "remove animations" switch, or Volta's own «Lite-оформление»: then
 * nothing loops and changes are instant.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    val lite = LocalLiteMode.current
    return lite || remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}
