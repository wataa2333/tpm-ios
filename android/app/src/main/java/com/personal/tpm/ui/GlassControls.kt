package com.personal.tpm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GlassFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(16.dp)
    FilterChip(selected, onClick, label, modifier = if (AppPreferences.liquidGlass)
        modifier.glassSurface(backdrop = null, shape = shape,
            tint = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) else modifier,
        enabled = enabled, shape = shape,
        colors = if (AppPreferences.liquidGlass) FilterChipDefaults.filterChipColors(containerColor = Color.Transparent,
            selectedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent) else FilterChipDefaults.filterChipColors())
}

@Composable
fun GlassTonalButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.filledTonalShape, colors: ButtonColors = ButtonDefaults.filledTonalButtonColors(),
    elevation: ButtonElevation? = ButtonDefaults.filledTonalButtonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    if (!AppPreferences.liquidGlass) FilledTonalButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
    else GlassActionButton(onClick, modifier, enabled, shape, colors, null, border, contentPadding, interactionSource, content)
}

@Composable
fun GlassOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.outlinedShape, colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    elevation: ButtonElevation? = null, border: BorderStroke? = ButtonDefaults.outlinedButtonBorder(enabled),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit) {
    if (!AppPreferences.liquidGlass) {
        OutlinedButton(onClick, modifier, enabled, shape, colors, elevation, border, contentPadding, interactionSource, content)
    } else {
        GlassActionButton(onClick, modifier, enabled, shape,
            colors.copy(containerColor = MaterialTheme.colorScheme.surface, disabledContainerColor = MaterialTheme.colorScheme.surface),
            elevation = null, border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content)
    }
}

@Composable
fun GlassSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null, enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(), interactionSource: MutableInteractionSource? = null) {
    Switch(checked, onCheckedChange,
        if (AppPreferences.liquidGlass) modifier.glassSurface(backdrop = null, shape = RoundedCornerShape(24.dp)) else modifier,
        thumbContent, enabled, colors, interactionSource)
}

/** Retain Material's keyboard, accessibility and drag handling on a shared glass capsule. */
@Composable
fun GlassSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null, colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }) {
    Slider(value, onValueChange,
        if (AppPreferences.liquidGlass) modifier.glassSurface(backdrop = null, shape = RoundedCornerShape(24.dp)) else modifier,
        enabled, valueRange, steps, onValueChangeFinished, colors, interactionSource)
}

@Composable
fun GlassWindowContent(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalGlassBackdrop provides null, LocalGlassContentPadding provides PaddingValues(0.dp), content = content)
}

@Composable
fun GlassPanelSurface(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.extraLarge,
    color: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable () -> Unit) {
    if (!AppPreferences.liquidGlass) Surface(modifier, shape = shape, color = color, content = content)
    else Surface(modifier.glassSurface(backdrop = null, shape = shape as? RoundedCornerShape ?: RoundedCornerShape(28.dp), tint = color, prominent = true),
        shape = shape, color = Color.Transparent, content = content)
}

@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit, modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null, icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null, text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape, containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor, titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor, tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(),
) {
    GlassWindowContent {
        AlertDialog(onDismissRequest, confirmButton,
            modifier = if (AppPreferences.liquidGlass) modifier.glassSurface(backdrop = null,
                shape = shape as? RoundedCornerShape ?: RoundedCornerShape(28.dp), tint = containerColor, prominent = true) else modifier,
            dismissButton = dismissButton, icon = icon, title = title, text = text, shape = shape,
            containerColor = if (AppPreferences.liquidGlass) Color.Transparent else containerColor,
            iconContentColor = iconContentColor, titleContentColor = titleContentColor, textContentColor = textContentColor,
            tonalElevation = tonalElevation, properties = properties)
    }
}
