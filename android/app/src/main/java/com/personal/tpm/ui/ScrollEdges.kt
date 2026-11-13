package com.personal.tpm.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val LocalScrollSurface = compositionLocalOf<Color?> { null }

/** Same soft colour edge as the chrome; paint only, never intercepts a tap or changes scroll geometry. */
fun Modifier.scrollEdges(color: Color, before: () -> Boolean = { true }, after: () -> Boolean = { true }): Modifier = drawWithCache {
    val top = minOf(18.dp.toPx(), size.height / 3)
    val bottom = minOf(22.dp.toPx(), size.height / 3)
    val topFade = Brush.verticalGradient(listOf(color, color.copy(alpha = .55f), color.copy(alpha = 0f)), 0f, top)
    val bottomFade = Brush.verticalGradient(listOf(color.copy(alpha = 0f), color.copy(alpha = .55f), color), size.height - bottom, size.height)
    onDrawWithContent {
        drawContent()
        if (top > 0f && before()) drawRect(topFade, size = Size(size.width, top))
        if (bottom > 0f && after()) drawRect(bottomFade, Offset(0f, size.height - bottom), Size(size.width, bottom))
    }
}
