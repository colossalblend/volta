package com.ars3nb.volta.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.desktop.ui.theme.LiteMotion
import com.ars3nb.volta.desktop.ui.theme.LiteMotionScale
import com.ars3nb.volta.desktop.ui.theme.ProvideLiteMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Lite-оформление» turns off every animation in the window, not only the ones Volta's
 * code checks for: the duration scale reaches the window's Recomposer, the way Android
 * hands one to its window (LiteMotionScaleTest there).
 */
class LiteMotionTest {

    @Test
    fun anAnimateAsStateEndsAtOnceUnderLite() {
        // Ten seconds long, looked at 48 ms after its target changed.
        assertEquals(1f, LiteMotion.valueAfterTwoFrames(lite = true), 0f)
    }

    @Test
    fun withoutLiteItTakesItsTime() {
        val value = LiteMotion.valueAfterTwoFrames(lite = false)
        assertTrue("moved to $value in 48 ms of 10 s", value in 0f..0.05f)
    }

    @Test
    fun theScaleFollowsTheSwitch() {
        val scale = LiteMotionScale()
        assertEquals(1f, scale.scaleFactor, 0f)
        scale.lite = true
        assertEquals(0f, scale.scaleFactor, 0f)
        scale.lite = false
        assertEquals(1f, scale.scaleFactor, 0f)
    }

    @Test
    fun aLibraryAnimationNobodyCheckedIsInstantToo() {
        // AnimatedVisibility with a ten-second fade, written with no Lite check at all.
        assertTrue(fadeSettledAfterAFewFrames(lite = true))
        assertFalse(fadeSettledAfterAFewFrames(lite = false))
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun fadeSettledAfterAFewFrames(lite: Boolean): Boolean {
        val scale = LiteMotionScale()
        val visible = MutableTransitionState(false)
        val scene = ImageComposeScene(width = 32, height = 32, density = Density(1f)) {
            ProvideLiteMotion(lite = lite, scale = scale) {
                AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(10_000))) {
                    Box(Modifier.size(8.dp))
                }
            }
        }
        try {
            scene.render(0)
            Snapshot.withMutableSnapshot { visible.targetState = true }
            Snapshot.sendApplyNotifications()
            var t = 0L
            repeat(4) {
                t += 16_000_000L
                scene.render(t)
            }
            return visible.isIdle && visible.currentState
        } finally {
            scene.close()
        }
    }
}
