package com.ars3nb.volta.desktop.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.ars3nb.volta.desktop.platform.WinApi

/**
 * The Material theme on Windows. Android's Material You takes its colours from the
 * wallpaper; Windows has the accent colour the user picks in Settings → Personalization
 * → Colors (also taken from the wallpaper when "Automatic" is on), and that is the seed
 * here. The scheme is built from it the way Material's tonal palettes go: the same hue
 * at fixed lightness steps, the neutrals barely tinted with it.
 */
object Accent {

    /** Material's own baseline purple, for a Windows that has no accent to give. */
    private const val FALLBACK = 0xFF6750A4.toInt()

    /** The accent as 0xAARRGGBB: DWM's AccentColor is stored as 0xAABBGGRR. */
    fun windows(): Int {
        val abgr = WinApi.regGetInt(WinApi.Root.CURRENT_USER, "Software\\Microsoft\\Windows\\DWM", "AccentColor")
            ?: return WinApi.regGetInt(WinApi.Root.CURRENT_USER, "Software\\Microsoft\\Windows\\DWM", "ColorizationColor")
                ?.let { it or (0xFF shl 24) } ?: FALLBACK
        val r = abgr and 0xFF
        val g = (abgr shr 8) and 0xFF
        val b = (abgr shr 16) and 0xFF
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    @Volatile
    private var cached: Int? = null

    /** Read once per run: the registry is not something to touch on every frame. */
    val seed: Int get() = cached ?: runCatching { windows() }.getOrDefault(FALLBACK).also { cached = it }

    /** [argb]'s hue and saturation at lightness [l] (0..1), saturation scaled by [sat]. */
    fun tone(argb: Int, l: Float, sat: Float = 1f): Color {
        val (h, s, _) = hsl(argb)
        return fromHsl(h, (s * sat).coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    fun scheme(argb: Int, dark: Boolean): ColorScheme = if (dark) {
        val sec = shiftHue(argb, 30f)
        val ter = shiftHue(argb, 60f)
        darkColorScheme(
            primary = tone(argb, 0.80f),
            onPrimary = tone(argb, 0.20f),
            primaryContainer = tone(argb, 0.30f),
            onPrimaryContainer = tone(argb, 0.90f),
            secondary = tone(sec, 0.80f, 0.35f),
            onSecondary = tone(sec, 0.20f, 0.35f),
            secondaryContainer = tone(sec, 0.30f, 0.35f),
            onSecondaryContainer = tone(sec, 0.90f, 0.35f),
            tertiary = tone(ter, 0.80f, 0.6f),
            onTertiary = tone(ter, 0.20f, 0.6f),
            background = tone(argb, 0.07f, 0.12f),
            onBackground = tone(argb, 0.90f, 0.08f),
            surface = tone(argb, 0.11f, 0.12f),
            onSurface = tone(argb, 0.90f, 0.08f),
            surfaceVariant = tone(argb, 0.20f, 0.16f),
            onSurfaceVariant = tone(argb, 0.78f, 0.14f),
            surfaceContainer = tone(argb, 0.12f, 0.12f),
            surfaceContainerHigh = tone(argb, 0.17f, 0.12f),
            outline = tone(argb, 0.45f, 0.12f),
            outlineVariant = tone(argb, 0.28f, 0.14f),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410)
        )
    } else {
        val sec = shiftHue(argb, 30f)
        val ter = shiftHue(argb, 60f)
        lightColorScheme(
            primary = tone(argb, 0.40f),
            onPrimary = Color.White,
            primaryContainer = tone(argb, 0.90f),
            onPrimaryContainer = tone(argb, 0.12f),
            secondary = tone(sec, 0.40f, 0.35f),
            onSecondary = Color.White,
            secondaryContainer = tone(sec, 0.90f, 0.35f),
            onSecondaryContainer = tone(sec, 0.12f, 0.35f),
            tertiary = tone(ter, 0.40f, 0.6f),
            onTertiary = Color.White,
            background = tone(argb, 0.98f, 0.20f),
            onBackground = tone(argb, 0.10f, 0.08f),
            surface = tone(argb, 0.995f, 0.20f),
            onSurface = tone(argb, 0.10f, 0.08f),
            surfaceVariant = tone(argb, 0.91f, 0.16f),
            onSurfaceVariant = tone(argb, 0.30f, 0.14f),
            surfaceContainer = tone(argb, 0.95f, 0.18f),
            surfaceContainerHigh = tone(argb, 0.92f, 0.18f),
            outline = tone(argb, 0.50f, 0.12f),
            outlineVariant = tone(argb, 0.80f, 0.14f),
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }

    private fun shiftHue(argb: Int, by: Float): Int {
        val (h, s, l) = hsl(argb)
        val c = fromHsl((h + by) % 360f, s, l)
        return (0xFF shl 24) or ((c.red * 255).toInt() shl 16) or ((c.green * 255).toInt() shl 8) or (c.blue * 255).toInt()
    }

    private fun hsl(argb: Int): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Triple(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
        val m = l - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
    }
}
