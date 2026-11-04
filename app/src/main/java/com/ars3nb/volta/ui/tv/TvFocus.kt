package com.ars3nb.volta.ui.tv

import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * Moving around with a TV remote (or a keyboard) instead of a finger. Compose only gives
 * focus to buttons and rows when there is no touch, so everything here stays invisible
 * on a phone in the hand.
 */

/** True inside the app on an Android TV / Google TV device. */
val LocalTv = staticCompositionLocalOf { false }

/**
 * Tells the screens they run on a TV and, there, makes Material's focus highlight strong
 * enough to see from the sofa: every button, menu item and row gets a clear wash under
 * the remote's focus. Must sit inside the theme.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProvideTv(tv: Boolean, content: @Composable () -> Unit) {
    val ripple = if (tv) TvRipple else LocalRippleConfiguration.current
    CompositionLocalProvider(
        LocalTv provides tv,
        LocalRippleConfiguration provides ripple,
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private val TvRipple = RippleConfiguration(
    rippleAlpha = RippleAlpha(
        draggedAlpha = 0.16f,
        focusedAlpha = 0.24f,
        hoveredAlpha = 0.08f,
        pressedAlpha = 0.20f
    )
)

/** How thick the focus outline is. */
val FocusRingWidth: Dp = 3.dp

/**
 * A copper outline in [shape] while the clickable thing after this modifier has the
 * remote's focus, drawn only then, so a phone in the hand never shows it. Put it before
 * `clip` / `clickable`, or pass it in the modifier of a `Surface(onClick)`.
 *
 * The outline sits just inside the bounds, where no parent clips it. A row whose content
 * runs to its edges takes an [outset] instead: the outline then hugs the row from the
 * outside, in the padding of the card around it.
 */
fun Modifier.focusRing(
    shape: Shape,
    color: Color = Color.Unspecified,
    width: Dp = FocusRingWidth,
    outset: Dp = 0.dp
): Modifier = composed {
    val ink = if (color == Color.Unspecified) MaterialTheme.colorScheme.primary else color
    var focused by remember { mutableStateOf(false) }
    Modifier
        .onFocusEvent { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused) {
                val w = width.toPx()
                inset(w / 2f - outset.toPx()) {
                    drawOutline(shape.createOutline(size, layoutDirection, this), ink, style = Stroke(w))
                }
            }
        }
}

/** How far a row's outline stands off the row. */
val RowFocusOutset: Dp = 4.dp

/**
 * On a TV, moves the remote's focus to [requester] when the screen opens, so the first
 * press of OK does the main thing. [target] says what the requester sits on now: the
 * screen's data arrives a moment after it opens, and until [settled] the focus follows
 * the target as it changes. After that, and after coming back to the screen, it is left
 * wherever the user put it.
 */
@Composable
fun StartFocus(requester: FocusRequester, target: Any, settled: Boolean) {
    if (!LocalTv.current) return
    var done by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(target) {
        if (done) return@LaunchedEffect
        // The element joins the layout in this frame; focus can only go to it after.
        withFrameNanos { }
        runCatching { requester.requestFocus() }
        if (settled) done = true
    }
}
