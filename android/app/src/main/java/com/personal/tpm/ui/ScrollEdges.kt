package com.personal.tpm.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalScrollSurface = compositionLocalOf<Color?> { null }

/** Same soft colour edge as the chrome; paint only, never intercepts a tap or changes scroll geometry. */
fun Modifier.scrollEdges(color: Color, before: () -> Boolean = { true }, after: () -> Boolean = { true }): Modifier = drawWithContent {
    drawContent()
    val top = minOf(18.dp.toPx(), size.height / 3)
    val bottom = minOf(22.dp.toPx(), size.height / 3)
    if (before()) drawRect(Brush.verticalGradient(listOf(color, color.copy(alpha = .55f), Color.Transparent), 0f, top), size = Size(size.width, top))
    if (after()) drawRect(Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = .55f), color), size.height - bottom, size.height), Offset(0f, size.height - bottom), Size(size.width, bottom))
}
