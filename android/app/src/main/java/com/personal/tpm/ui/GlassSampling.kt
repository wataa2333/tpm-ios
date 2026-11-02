package com.personal.tpm.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.draw
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.unit.IntSize
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlin.math.ceil

/** Keep the library's coordinate handling; rasterize only its background source at a lower resolution. */
@Composable
internal fun rememberGlassPageBackdrop(color: Color): LayerBackdrop {
    val sampling = AppPreferences.glassEffects.sampling
    if (sampling == GlassSampling.Clear) return rememberLayerBackdrop { drawRect(color); drawContent() }
    val sampled = rememberGraphicsLayer()
    val factor = 1f / sampling.divisor
    return rememberLayerBackdrop {
        val source = this
        val fullSize = size
        val rasterSize = IntSize(sampling.pixels(ceil(size.width).toInt()), sampling.pixels(ceil(size.height).toInt()))
        sampled.compositingStrategy = CompositingStrategy.Offscreen
        sampled.record(density = source, layoutDirection = layoutDirection, size = rasterSize) {
            // Route the original ContentDrawScope into the recording canvas, rather than scaling the visible page.
            source.draw(density = source, layoutDirection = layoutDirection, canvas = drawContext.canvas,
                size = fullSize, graphicsLayer = drawContext.graphicsLayer) {
                scale(factor, pivot = Offset.Zero) { drawRect(color); source.drawContent() }
            }
        }
        scale(1f / factor, pivot = Offset.Zero) { drawLayer(sampled) }
    }
}
