package com.personal.tpm.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur

/** Small, non-interactive fades inside sheet list viewports; never add a solid divider. */
@Composable
internal fun SheetScrollEdge(backdrop: Backdrop?, surface: Color, top: Boolean, visible: Boolean, modifier: Modifier) {
    val opacity by animateFloatAsState(if (visible) 1f else 0f, tween(Motion.Fast), label = "sheet scroll edge")
    val mask = if (top) listOf(Color.White, Color.Transparent) else listOf(Color.Transparent, Color.White)
    val radius = AppPreferences.glassEffects.blur.radiusDp.dp
    val material = if (backdrop != null) Modifier.drawPlainBackdrop(backdrop, shape = { RectangleShape },
        effects = { if (radius > 0.dp) blur(radius.toPx()) },
        onDrawSurface = { drawRect(surface.copy(alpha = .38f)) })
    else Modifier.background(surface.copy(alpha = .38f))
    Box(modifier.graphicsLayer { alpha = opacity; compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(Brush.verticalGradient(mask), blendMode = BlendMode.DstIn)
        }.then(material))
}
