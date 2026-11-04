package com.ars3nb.volta.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ars3nb.volta.desktop.ui.LocalStrings
import com.ars3nb.volta.desktop.ui.theme.Motion
import com.ars3nb.volta.desktop.ui.theme.VoltaTheme

/*
 * The small parts every screen is built from (the Android app's Kit, unchanged), in the
 * language of the redesigned home screen: tonal fills a step off the background instead of outlines, an outline only
 * for "this one / in focus", copper for the chosen thing, springs for answers to a touch.
 */

val FieldShape = RoundedCornerShape(18.dp)

/**
 * A text field as a tonal well. No underline and no outline at rest: the outline comes
 * in only while the field has focus, the same way a selected card is outlined.
 */
@Composable
fun VoltaField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    shape: Shape = FieldShape,
    container: Color = MaterialTheme.colorScheme.surface,
    enabled: Boolean = true,
    /** The outline while the field is not in focus; none by default. */
    restingOutline: Color = Color.Transparent
) {
    val scheme = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val ring by animateColorAsState(
        if (focused) scheme.primary.copy(alpha = 0.8f) else restingOutline,
        label = "fieldRing"
    )
    // Typed slots: a lambda built inside ?.let would not be @Composable.
    val labelSlot: (@Composable () -> Unit)? = if (label == null) null else ({ Text(label) })
    val placeholderSlot: (@Composable () -> Unit)? = if (placeholder == null) null else ({
        Text(placeholder, color = scheme.onSurfaceVariant.copy(alpha = 0.7f))
    })
    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth().border(1.5.dp, ring, shape),
        enabled = enabled,
        label = labelSlot,
        placeholder = placeholderSlot,
        leadingIcon = leading,
        trailingIcon = trailing,
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        interactionSource = interaction,
        shape = shape,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            disabledContainerColor = container.copy(alpha = 0.6f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedLabelColor = scheme.primary,
            unfocusedLabelColor = scheme.onSurfaceVariant,
            cursorColor = scheme.primary
        )
    )
}

/** The search well: a pill with the magnifier in front and a clear button once typed in. */
@Composable
fun VoltaSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    val clear: (@Composable () -> Unit)? = if (value.isEmpty()) null else ({
        IconButton(onClick = { onValueChange("") }) {
            Icon(Icons.Rounded.Close, LocalStrings.current.clearSearch)
        }
    })
    VoltaField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        singleLine = true,
        shape = CircleShape,
        modifier = modifier,
        leading = {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailing = clear
    )
}

/**
 * A filter or choice chip without Material's outline and tick: the chosen one takes a
 * wash of its colour, the rest sit on the plain tonal fill.
 */
@Composable
fun VoltaChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    /** The fill of a chip not chosen: pass surfaceVariant when the chip sits on a card. */
    rest: Color = MaterialTheme.colorScheme.surface
) {
    val scheme = MaterialTheme.colorScheme
    val dark = VoltaTheme.palette.isDark
    val fill by animateColorAsState(
        if (selected) accent.copy(alpha = if (dark) 0.18f else 0.14f) else rest,
        label = "chipFill"
    )
    val ink by animateColorAsState(
        when {
            !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.5f)
            selected -> accent
            else -> scheme.onSurface
        },
        label = "chipInk"
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = fill,
        modifier = modifier.semantics { this.selected = selected }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                color = ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * A few mutually exclusive options on one track with a sliding thumb, like the auto
 * mode selector on the home screen. For two to four short labels.
 */
@Composable
fun <T> SlidingChoice(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val scheme = MaterialTheme.colorScheme
    val dark = VoltaTheme.palette.isDark
    val index = options.indexOf(selected).coerceAtLeast(0)
    Surface(modifier = modifier, shape = CircleShape, color = scheme.surface) {
        BoxWithConstraints(Modifier.padding(3.dp).height(height - 6.dp)) {
            val slot = maxWidth / options.size
            val x by animateDpAsState(slot * index, Motion.slide(), label = "choiceThumb")
            Box(
                Modifier
                    .offset(x = x)
                    .width(slot)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (dark) 0.2f else 0.15f))
            )
            Row(Modifier.fillMaxSize()) {
                options.forEach { option ->
                    val on = option == selected
                    val ink by animateColorAsState(if (on) accent else scheme.onSurfaceVariant, label = "choiceInk")
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .semantics { this.selected = on }
                            .clickable(role = Role.Tab) { onSelect(option) }
                    ) {
                        Text(
                            label(option),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** The round coloured badge in front of a row, as on the routes screen. */
@Composable
fun IconBubble(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    active: Boolean = true
) {
    val fill by animateColorAsState(
        if (active) tint.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        label = "bubbleFill"
    )
    val ink by animateColorAsState(
        if (active) tint else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "bubbleInk"
    )
    Box(
        modifier.size(size).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, tint = ink, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * A setting that is on or off. The whole row toggles, and the badge lights up with the
 * switch, so a glance down the card shows what is on.
 */
@Composable
fun SwitchRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .clickable(role = Role.Switch) { onChecked(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBubble(icon, tint, active = checked)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(subtitle)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

/** A row that opens something: badge, text, chevron (or whatever [trailing] puts there). */
@Composable
fun LinkRow(
    icon: ImageVector,
    tint: Color,
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBubble(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (!subtitle.isNullOrBlank()) Hint(subtitle)
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One action in a bottom sheet. A dangerous one is drawn in the error colour. */
@Composable
fun SheetAction(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    danger: Boolean = false
) {
    val ink = if (danger) MaterialTheme.colorScheme.error else tint
    Row(
        modifier
            .fillMaxWidth()
            .clip(SmallShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (danger) ink.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (danger) ink else MaterialTheme.colorScheme.onSurface
            )
            if (!subtitle.isNullOrBlank()) Hint(subtitle)
        }
    }
}

/** Vertical rhythm inside a card holding several rows. */
val RowGap: Arrangement.Vertical = Arrangement.spacedBy(2.dp)
