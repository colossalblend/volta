package com.ars3nb.volta.ui.theme

import android.provider.Settings
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class LiteMotionScaleTest {

    private val context: android.content.Context = RuntimeEnvironment.getApplication()

    @Test
    fun liteStopsEveryAnimationAndOffFollowsTheSystem() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0.5f)
        val scale = LiteMotionScale(context)
        assertEquals(0.5f, scale.scaleFactor)
        scale.lite = true
        assertEquals(0f, scale.scaleFactor)
        scale.lite = false
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 2f)
        scale.refreshSystem()
        assertEquals(2f, scale.scaleFactor)
    }
}
