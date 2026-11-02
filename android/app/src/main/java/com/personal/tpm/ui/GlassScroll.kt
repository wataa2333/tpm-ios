package com.personal.tpm.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Native scrolling with a small edge mask on the strip, so partially visible glass has no hard cut. */
fun Modifier.glassHorizontalScroll(state: ScrollState): Modifier = composed {
    if (!AppPreferences.liquidGlass) horizontalScroll(state)
    else graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (state.canScrollBackward || state.canScrollForward) {
                val edge = (16.dp.toPx() / size.width.coerceAtLeast(1f)).coerceAtMost(.25f)
                drawRect(Brush.horizontalGradient(0f to if (state.canScrollBackward) Color.Transparent else Color.White,
                    edge to Color.White, (1f - edge) to Color.White,
                    1f to if (state.canScrollForward) Color.Transparent else Color.White), blendMode = BlendMode.DstIn)
            }
        }.horizontalScroll(state)
}
