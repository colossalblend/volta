package com.ars3nb.volta.desktop.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.sp

/**
 * Two faces, each with one job, as on Android. Unbounded, wide and heavy, speaks for the
 * app: titles, the connection status, the numbers you glance at. Onest carries everything
 * you read. Android reads one variable font per face; here each weight is a static copy
 * of it (made with fontTools), since the desktop renderer picks weights by file.
 */
private fun family(name: String, weights: List<Int>): FontFamily = FontFamily(
    weights.mapNotNull { w ->
        val bytes = Thread.currentThread().contextClassLoader?.getResourceAsStream("font/$name-$w.ttf")
            ?.use { it.readBytes() }
            ?: VoltaFonts::class.java.getResourceAsStream("/font/$name-$w.ttf")?.use { it.readBytes() }
            ?: return@mapNotNull null
        Font(identity = "volta-$name-$w", data = bytes, weight = FontWeight(w))
    }.ifEmpty { return FontFamily.Default }
)

private object VoltaFonts

val Unbounded: FontFamily = family("unbounded", listOf(300, 400, 500, 600, 700, 800))
val Onest: FontFamily = family("onest", listOf(400, 500, 600, 700))

/** Figures that keep their width, so a ticking timer or speed doesn't jitter. */
const val TABULAR = "tnum"

val VoltaTypography = Typography(
    displayLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.6).sp),
    displayMedium = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp, letterSpacing = (-0.4).sp),
    displaySmall = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontFamily = Unbounded, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Onest, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = Onest, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp)
)
