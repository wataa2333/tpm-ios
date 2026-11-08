package com.personal.tpm.ui

import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GlassFilterChip(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val accent = MaterialTheme.colorScheme.primary
    val fill = if (selected) accent else MaterialTheme.colorScheme.surfaceContainerHigh
    val ink = glassContentColor(accent, accent, MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurface)
    FilterChip(selected, onClick, label, modifier = if (AppPreferences.liquidGlass)
        modifier.glassSurface(backdrop = null, shape = CircleShape, tint = fill, prominent = selected, quiet = !selected) else modifier,
        enabled = enabled, shape = CircleShape,
        leadingIcon = if (selected) ({ Icon(androidx.compose.material.icons.Icons.Default.Check, null, Modifier.size(16.dp)) }) else null,
        border = if (AppPreferences.liquidGlass) null else FilterChipDefaults.filterChipBorder(enabled, selected),
        colors = if (AppPreferences.liquidGlass) FilterChipDefaults.filterChipColors(containerColor = Color.Transparent,
            selectedContainerColor = Color.Transparent, selectedLabelColor = ink, selectedLeadingIconColor = ink,
            disabledSelectedContainerColor = Color.Transparent) else FilterChipDefaults.filterChipColors())
}

@Composable
fun GlassTextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.textShape, colors: ButtonColors = ButtonDefaults.textButtonColors(),
    border: BorderStroke? = null, contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    interactionSource: MutableInteractionSource? = null, content: @Composable RowScope.() -> Unit) {
    if (!AppPreferences.liquidGlass) TextButton(onClick = onClick, modifier = modifier, enabled = enabled,
        shape = shape, colors = colors, border = border, contentPadding = contentPadding, interactionSource = interactionSource, content = content)
    else GlassActionButton(onClick, modifier, enabled, shape, colors, elevation = null,
        contentPadding = contentPadding, interactionSource = interactionSource, content = content)
}

@Composable
fun Modifier.glassSearchSurface(shape: Shape = CircleShape, quiet: Boolean = false): Modifier = if (AppPreferences.liquidGlass)
    glassSurface(backdrop = null, shape = shape as? RoundedCornerShape ?: RoundedCornerShape(16.dp),
        tint = MaterialTheme.colorScheme.surfaceContainerHigh, quiet = quiet) else this

@Composable
fun searchFieldColors(onCard: Boolean = false): TextFieldColors {
    val base = if (onCard) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    return TextFieldDefaults.colors(
        focusedContainerColor = if (AppPreferences.liquidGlass) Color.Transparent else base,
        unfocusedContainerColor = if (AppPreferences.liquidGlass) Color.Transparent else base,
        disabledContainerColor = if (AppPreferences.liquidGlass) Color.Transparent else base,
        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledIndicatorColor = Color.Transparent,
    )
}

@Composable
fun glassOutlinedFieldColors(): TextFieldColors = if (!AppPreferences.liquidGlass) OutlinedTextFieldDefaults.colors()
else OutlinedTextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent, focusedBorderColor = Color.Transparent,
    unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent)

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
    if (!AppPreferences.liquidGlass) {
        Switch(checked, onCheckedChange, modifier, thumbContent, enabled, colors, interactionSource)
        return
    }
    // Material owns the thumb, gesture and accessibility. Glass belongs to its 52x32 dp track, not the 48 dp touch bounds.
    val tint = if (checked) colors.checkedTrackColor else colors.uncheckedTrackColor
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.size(52.dp, 32.dp).glassSurface(backdrop = null, shape = CircleShape, tint = tint, prominent = checked))
        Switch(checked, onCheckedChange, thumbContent = thumbContent, enabled = enabled, interactionSource = interactionSource,
            colors = colors.copy(checkedTrackColor = Color.Transparent, uncheckedTrackColor = Color.Transparent,
                disabledCheckedTrackColor = Color.Transparent, disabledUncheckedTrackColor = Color.Transparent,
                checkedBorderColor = Color.Transparent, uncheckedBorderColor = Color.Transparent,
                disabledCheckedBorderColor = Color.Transparent, disabledUncheckedBorderColor = Color.Transparent))
    }
}

/** Native track and thumb preserve keyboard, accessibility and drag handling. */
@Composable
fun GlassSlider(value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null, colors: SliderColors = SliderDefaults.colors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }) {
    Slider(value, onValueChange, modifier, enabled, valueRange, steps, onValueChangeFinished, colors, interactionSource)
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
