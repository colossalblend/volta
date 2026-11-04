package com.ars3nb.volta.ui.components

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.R

/**
 * The launcher icons a user can pick. Each one is an `activity-alias` in the manifest;
 * exactly one of them is enabled at a time, and that one is what the launcher shows.
 */
enum class AppIcon(
    val alias: String,
    @param:StringRes val title: Int,
    @param:DrawableRes val background: Int,
    @param:DrawableRes val foreground: Int
) {
    FOLD("IconFold", R.string.settings_icon_fold, R.drawable.ic_launcher_background, R.drawable.ic_launcher_foreground),
    SPARK("IconSpark", R.string.settings_icon_spark, R.drawable.ic_launcher_spark_background, R.drawable.ic_launcher_spark_foreground),
    ORBIT("IconOrbit", R.string.settings_icon_orbit, R.drawable.ic_launcher_orbit_background, R.drawable.ic_launcher_orbit_foreground),
    TUNNEL("IconTunnel", R.string.settings_icon_tunnel, R.drawable.ic_launcher_tunnel_background, R.drawable.ic_launcher_tunnel_foreground),
    PIXEL("IconPixel", R.string.settings_icon_pixel, R.drawable.ic_launcher_pixel_background, R.drawable.ic_launcher_pixel_foreground);

    private fun component(context: Context) = ComponentName(context.packageName, "com.ars3nb.volta.$alias")

    companion object {
        /** The icon the launcher shows now; the manifest default is [FOLD]. */
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull {
                pm.getComponentEnabledSetting(it.component(context)) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } ?: FOLD
        }

        /** Swaps the launcher entry. The new one is enabled first so there is never none. */
        fun apply(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            pm.setComponentEnabledSetting(
                icon.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            entries.filter { it != icon }.forEach {
                pm.setComponentEnabledSetting(
                    it.component(context),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}

/** A row of icon previews; tapping one swaps the launcher icon. */
@Composable
fun AppIconPicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var selected by remember { mutableStateOf(AppIcon.current(context)) }
    Column(modifier) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            AppIcon.entries.forEach { icon ->
                IconChoice(icon, selected = icon == selected) {
                    if (icon != selected) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        AppIcon.apply(context, icon)
                        selected = icon
                    }
                }
            }
        }
        Text(
            stringResource(R.string.settings_icon_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp)
        )
    }
}

@Composable
private fun IconChoice(icon: AppIcon, selected: Boolean, onClick: () -> Unit) {
    val title = stringResource(icon.title)
    val ring by animateDpAsState(if (selected) 2.dp else 0.dp, label = "ring")
    val pop by animateFloatAsState(
        targetValue = if (selected) 1f else 0.9f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "pop"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(64.dp)
                .border(ring, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                .padding(5.dp)
                .scale(pop)
                .clip(CircleShape)
        ) {
            // Adaptive layers are 108dp with the visible part in the middle 72dp.
            val layer = Modifier.requiredSize(54.dp * 108f / 72f)
            Image(painterResource(icon.background), contentDescription = null, modifier = layer)
            Image(painterResource(icon.foreground), contentDescription = title, modifier = layer)
        }
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}
