package com.ars3nb.volta.desktop.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.unit.Density
import kotlin.coroutines.CoroutineContext

/**
 * How long Compose animations take, for the whole window: 0 with «Lite-оформление» on —
 * every animation in the app ends the moment it starts, including the ones nobody wrote
 * a check for (list items, menus, crossfades, the page change, infinite loops) — and 1
 * otherwise. Read on every animation, so the switch takes effect at once.
 *
 * Android hands such a scale to the window's Recomposer when it creates it
 * (createLifecycleAwareWindowRecomposer, see the Android MainActivity). Compose for
 * desktop makes the window's Recomposer itself, inside ComposeWindow, from a context
 * with no MotionDurationScale and no way to pass one in (compose-multiplatform 1.8:
 * ComposeContainer's coroutineContext is MainUIDispatcher + an exception handler). So
 * [ProvideLiteMotion] adds the scale to that Recomposer's effect context from inside
 * the window, before the content is composed: every LaunchedEffect, and with it every
 * animate*AsState, AnimatedContent and transition, runs in that context and reads the
 * scale from it. When a later Compose changes its internals so the scale can't be
 * added, nothing breaks: Volta's own animations still follow [LocalLiteMode].
 */
class LiteMotionScale : MotionDurationScale {

    @Volatile
    var lite: Boolean = false

    override val scaleFactor: Float
        get() = if (lite) 0f else 1f
}

object LiteMotion {

    /** The one scale of the app's window. */
    val scale = LiteMotionScale()

    /** Whether the scale reached the window's Recomposer (for the self-test and the log). */
    @Volatile
    var installed: Boolean = false
        internal set

    /**
     * Adds [scale] to the effect context of the Recomposer that runs [composer]'s
     * composition. True when it is there (now or from before).
     */
    internal fun install(composer: Composer, scale: MotionDurationScale): Boolean = runCatching {
        val recomposer = recomposerOf(composer) ?: return false
        val field = Recomposer::class.java.getDeclaredField("effectCoroutineContext")
        if (!CoroutineContext::class.java.isAssignableFrom(field.type)) return false
        field.isAccessible = true
        val now = field.get(recomposer) as CoroutineContext
        if (now[MotionDurationScale] !== scale) field.set(recomposer, now + scale)
        (field.get(recomposer) as CoroutineContext)[MotionDurationScale] === scale
    }.getOrDefault(false)

    /**
     * The Recomposer at the root of [composer]'s composition: a composition's parent is
     * either the Recomposer or the composition it is nested in (a lazy list's item, a
     * popup), whose own parent is looked at in turn.
     */
    private fun recomposerOf(composer: Composer): Recomposer? {
        var node: Any? = composer
        repeat(32) {
            when (val n = node) {
                is Recomposer -> return n
                null -> return null
                else -> {
                    val parent = fieldValue(n, "parentContext")
                    node = parent ?: fieldValue(n, "this$0")
                }
            }
        }
        return null
    }

    private fun fieldValue(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = runCatching { type.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(target)
            }
            type = type.superclass
        }
        return null
    }

    /**
     * An animate*AsState of ten seconds, drawn off-screen with or without Lite: its value
     * two frames (32 ms) after its target changed from 0 to 1. Lite gives 1 (it ended at
     * once), without it the value has hardly moved. For the self-test and the unit test.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    fun valueAfterTwoFrames(lite: Boolean): Float {
        val scale = LiteMotionScale().apply { this.lite = lite }
        var target by mutableStateOf(0f)
        var seen = -1f
        val scene = ImageComposeScene(width = 16, height = 16, density = Density(1f)) {
            ProvideLiteMotion(lite, scale) {
                val value by animateFloatAsState(target, tween(durationMillis = 10_000), label = "liteProbe")
                seen = value
            }
        }
        try {
            scene.render(0)
            Snapshot.withMutableSnapshot { target = 1f }
            Snapshot.sendApplyNotifications()
            var t = 0L
            repeat(3) {
                t += 16_000_000L
                scene.render(t)
            }
        } finally {
            scene.close()
        }
        return seen
    }
}

/**
 * The window's content with «Lite-оформление» applied: the duration scale in its
 * Recomposer (see [LiteMotionScale]) and [LocalLiteMode] for Volta's own loops.
 */
@Composable
fun ProvideLiteMotion(lite: Boolean, scale: LiteMotionScale = LiteMotion.scale, content: @Composable () -> Unit) {
    scale.lite = lite
    val composer = currentComposer
    val installed = remember(scale) { LiteMotion.install(composer, scale) }
    if (scale === LiteMotion.scale) LiteMotion.installed = installed
    CompositionLocalProvider(LocalLiteMode provides lite, content = content)
}
