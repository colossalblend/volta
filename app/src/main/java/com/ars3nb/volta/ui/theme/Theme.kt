package com.ars3nb.volta.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.ars3nb.volta.data.prefs.DesignTheme

/**
 * Extra roles Material's scheme has no slot for: the hero gradient, latency ramp
 * and status colours. Kept in one place so every screen speaks the same language.
 */
@Immutable
data class VoltaPalette(
    val heroTop: Color,
    val heroBottom: Color,
    val glow: Color,
    val good: Color,
    val fair: Color,
    val poor: Color,
    val cardBorder: Color,
    val elevated: Color,
    val isDark: Boolean
) {
    val heroBrush: Brush get() = Brush.verticalGradient(listOf(heroTop, heroBottom))

    /** Colour for a latency reading; -1 means "not measured". */
    fun latency(ms: Long): Color = when {
        ms < 0 -> poor.copy(alpha = 0.35f)
        ms < 120 -> good
        ms < 300 -> fair
        else -> poor
    }
}

val LocalVoltaPalette = staticCompositionLocalOf {
    VoltaPalette(
        heroTop = VoltaSurfaceHigh,
        heroBottom = VoltaBackground,
        glow = VoltaCopper,
        good = VoltaSuccess,
        fair = VoltaWarning,
        poor = VoltaError,
        cardBorder = VoltaOutline,
        elevated = VoltaSurfaceHigh,
        isDark = true
    )
}

object VoltaTheme {
    val palette: VoltaPalette
        @Composable @ReadOnlyComposable get() = LocalVoltaPalette.current
}

private val VoltaDarkScheme = darkColorScheme(
    primary = VoltaCopper,
    onPrimary = Color(0xFF2A1508),
    primaryContainer = VoltaCopperDeep,
    onPrimaryContainer = VoltaCopperSoft,
    secondary = VoltaCopperSoft,
    onSecondary = Color(0xFF2A1508),
    secondaryContainer = VoltaSurfaceHigh,
    onSecondaryContainer = VoltaCream,
    tertiary = VoltaSuccess,
    onTertiary = Color(0xFF04150D),
    background = VoltaBackground,
    onBackground = VoltaCream,
    surface = VoltaSurface,
    onSurface = VoltaCream,
    surfaceVariant = VoltaSurfaceHigh,
    onSurfaceVariant = VoltaMuted,
    surfaceContainer = VoltaSurface,
    surfaceContainerHigh = VoltaSurfaceHigh,
    outline = VoltaOutline,
    outlineVariant = VoltaOutline,
    error = VoltaError,
    onError = Color.White
)

private val VoltaLightScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDDC7),
    onPrimaryContainer = Color(0xFF3A1600),
    secondary = Color(0xFF6C5D50),
    onSecondary = Color.White,
    secondaryContainer = LightSurfaceHigh,
    onSecondaryContainer = Color(0xFF241C14),
    tertiary = Color(0xFF2C7F5B),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceHigh,
    onSurfaceVariant = LightMuted,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceHigh,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = Color(0xFFBE3B32),
    onError = Color.White
)

private val OceanScheme = darkColorScheme(
    primary = OceanPrimary,
    onPrimary = Color(0xFF04211F),
    primaryContainer = Color(0xFF11544E),
    onPrimaryContainer = OceanFoam,
    secondary = OceanFoam,
    onSecondary = OceanBackground,
    secondaryContainer = OceanSurfaceHigh,
    onSecondaryContainer = OceanFoam,
    tertiary = Color(0xFF6FD0A0),
    onTertiary = Color(0xFF042014),
    background = OceanBackground,
    onBackground = OceanFoam,
    surface = OceanSurface,
    onSurface = OceanFoam,
    surfaceVariant = OceanSurfaceHigh,
    onSurfaceVariant = OceanMuted,
    surfaceContainer = OceanSurface,
    surfaceContainerHigh = OceanSurfaceHigh,
    outline = OceanOutline,
    outlineVariant = OceanOutline,
    error = VoltaError,
    onError = Color.White
)

private val EmberScheme = darkColorScheme(
    primary = EmberPrimary,
    onPrimary = Color(0xFF2A1305),
    primaryContainer = Color(0xFF6E3A15),
    onPrimaryContainer = Color(0xFFFFDCC6),
    secondary = Color(0xFFD8B99C),
    onSecondary = EmberBackground,
    secondaryContainer = EmberSurfaceHigh,
    onSecondaryContainer = EmberOnSurface,
    tertiary = Color(0xFF63C79A),
    onTertiary = Color(0xFF04200F),
    background = EmberBackground,
    onBackground = EmberOnSurface,
    surface = EmberSurface,
    onSurface = EmberOnSurface,
    surfaceVariant = EmberSurfaceHigh,
    onSurfaceVariant = EmberMuted,
    surfaceContainer = EmberSurface,
    surfaceContainerHigh = EmberSurfaceHigh,
    outline = EmberOutline,
    outlineVariant = EmberOutline,
    error = VoltaError,
    onError = Color.White
)

@Composable
fun VoltaTheme(
    design: DesignTheme = DesignTheme.VOLTA,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val useLight = design == DesignTheme.LIGHT ||
        (design == DesignTheme.SYSTEM && !darkTheme)

    val scheme = when {
        useLight -> VoltaLightScheme
        design == DesignTheme.OCEAN -> OceanScheme
        design == DesignTheme.EMBER -> EmberScheme
        else -> VoltaDarkScheme
    }

    val palette = when {
        useLight -> VoltaPalette(
            heroTop = Color(0xFFFFE3CE),
            heroBottom = LightBackground,
            glow = LightPrimary,
            good = Color(0xFF2C7F5B),
            fair = Color(0xFFB07C15),
            poor = Color(0xFFBE3B32),
            cardBorder = LightOutline,
            elevated = LightSurfaceHigh,
            isDark = false
        )

        design == DesignTheme.OCEAN -> VoltaPalette(
            heroTop = Color(0xFF143B42),
            heroBottom = OceanBackground,
            glow = OceanPrimary,
            good = Color(0xFF56CFA2),
            fair = VoltaWarning,
            poor = VoltaError,
            cardBorder = OceanOutline,
            elevated = OceanSurfaceHigh,
            isDark = true
        )

        design == DesignTheme.EMBER -> VoltaPalette(
            heroTop = Color(0xFF3A2415),
            heroBottom = EmberBackground,
            glow = EmberPrimary,
            good = Color(0xFF63C79A),
            fair = VoltaWarning,
            poor = VoltaError,
            cardBorder = EmberOutline,
            elevated = EmberSurfaceHigh,
            isDark = true
        )

        else -> VoltaPalette(
            heroTop = Color(0xFF262A31),
            heroBottom = VoltaBackground,
            glow = VoltaCopper,
            good = VoltaSuccess,
            fair = VoltaWarning,
            poor = VoltaError,
            cardBorder = VoltaOutline,
            elevated = VoltaSurfaceHigh,
            isDark = true
        )
    }

    CompositionLocalProvider(LocalVoltaPalette provides palette) {
        MaterialTheme(
            colorScheme = scheme,
            typography = VoltaTypography,
            content = content
        )
    }
}
