package com.ars3nb.volta.desktop.ui.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How things move. Answers to a click are springs, so they settle by themselves; nothing
 * moves on its own except the power disc, and only while there is something to show.
 */
object Motion {
    /** A press: quick to give, a little bounce back. */
    fun <T> press(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.5f, stiffness = 520f)

    /** A thumb or indicator sliding to where the pointer went. */
    fun <T> slide(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.74f, stiffness = Spring.StiffnessMediumLow)

    /** Something arriving: settles without overshoot. */
    fun <T> settle(): FiniteAnimationSpec<T> = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow)
}

/** «Lite-оформление» in Settings: every animation off (see LiteMotion.kt). */
val LocalLiteMode = staticCompositionLocalOf { false }

/**
 * Volta's own «Lite-оформление»: then nothing loops and changes are instant. (Every
 * Compose animation is instant then anyway, through the window's MotionDurationScale;
 * this also stops the loops that would otherwise keep redrawing a still picture.)
 */
@Composable
fun rememberReducedMotion(): Boolean = LocalLiteMode.current
