package com.personal.tpm.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Small, non-interactive fades inside sheet list viewports; never add a solid divider. */
@Composable
internal fun SheetScrollEdge(surface: Color, top: Boolean, visible: Boolean, modifier: Modifier) {
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(Motion.Fast), label = "sheet scroll edge")
    Box(modifier.drawWithCache {
        val edge = surface.copy(alpha = .65f)
        val clear = surface.copy(alpha = 0f)
        val gradient = Brush.verticalGradient(if (top) listOf(edge, clear) else listOf(clear, edge))
        onDrawBehind { if (opacity > 0f) drawRect(gradient, alpha = opacity) }
    })
}
