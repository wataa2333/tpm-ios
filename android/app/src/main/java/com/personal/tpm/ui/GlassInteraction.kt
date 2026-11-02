package com.personal.tpm.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

fun glassTabIndex(x: Float, width: Float, count: Int): Int =
    if (count <= 0 || width <= 0f || !width.isFinite() || !x.isFinite()) 0
    else ((x / width * count).toInt()).coerceIn(0, count - 1)

fun glassDragOffset(delta: Offset, limit: Float): Offset = Offset(
    if (delta.x.isFinite()) (delta.x * .3f).coerceIn(-limit, limit) else 0f,
    if (delta.y.isFinite()) (delta.y * .3f).coerceIn(-limit, limit) else 0f,
)

fun glassTintColor(tint: Color, fallback: Color): Color = if (tint.isSpecified && tint.alpha > 0f) tint else fallback

/** A translucent primary fill cannot safely reuse white-on-primary text. */
fun glassContentColor(requested: Color, tint: Color, surface: Color, fallback: Color): Color {
    val background = tint.copy(alpha = .32f * tint.alpha).compositeOver(surface)
    val foreground = requested.copy(alpha = 1f)
    val light = maxOf(foreground.luminance(), background.luminance())
    val dark = minOf(foreground.luminance(), background.luminance())
    return if ((light + .05f) / (dark + .05f) >= 4.5f) requested else fallback.copy(alpha = requested.alpha)
}

internal data class GlassFeedback(
    val input: Modifier,
    val layer: GraphicsLayerScope.() -> Unit,
    val illumination: DrawScope.() -> Unit,
    val highlightAngle: () -> Float,
)

/** Observe movement without consuming it: the parent list still owns scroll gestures. */
@Composable
internal fun rememberGlassFeedback(source: MutableInteractionSource, enabled: Boolean): GlassFeedback {
    val pressed by source.collectIsPressedAsState()
    val effects = AppPreferences.glassEffects
    var touching by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf(Offset.Zero) }
    var touch by remember { mutableStateOf(Offset.Unspecified) }
    val limit = with(LocalDensity.current) { 10.dp.toPx() }
    val active = enabled && (pressed || touching)
    val progress by animateFloatAsState(if (active) 1f else 0f, spring(dampingRatio = .7f, stiffness = 500f), label = "glass touch light")
    val x by animateFloatAsState(if (active && effects.motion) drag.x else 0f, spring(dampingRatio = .65f, stiffness = 420f), label = "glass drag x")
    val y by animateFloatAsState(if (active && effects.motion) drag.y else 0f, spring(dampingRatio = .65f, stiffness = 420f), label = "glass drag y")
    val input = if (!enabled) Modifier else Modifier.pointerInput(source, limit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touching = true
            touch = down.position
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position != change.previousPosition && change.isConsumed)) break
                    drag = glassDragOffset(change.position - down.position, limit)
                    touch = change.position
                } while (true)
            } finally { drag = Offset.Zero; touching = false }
        }
    }
    return GlassFeedback(input,
        layer = {
            if (effects.motion) {
                // Four dp of inflation, plus directional stretch. It follows the finger without changing layout.
                val scale = 1f + (4.dp.toPx() / size.height.coerceAtLeast(1f)).coerceAtMost(.12f) * progress.coerceIn(0f, 1f)
                val stretchX = (abs(x) / limit.coerceAtLeast(1f)).coerceIn(0f, 1f) * .045f
                val stretchY = (abs(y) / limit.coerceAtLeast(1f)).coerceIn(0f, 1f) * .045f
                scaleX = scale + stretchX - stretchY * .35f
                scaleY = scale + stretchY - stretchX * .35f
                translationX = x; translationY = y
            }
        },
        illumination = {
            if (effects.highlight && progress > 0f) {
                val lightCenter = if (touch.isSpecified) Offset(touch.x.coerceIn(0f, size.width), touch.y.coerceIn(0f, size.height)) else center
                drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = .2f * progress.coerceIn(0f, 1f)), Color.Transparent),
                    center = lightCenter, radius = size.maxDimension.coerceAtLeast(1f)), blendMode = BlendMode.Plus)
            }
        },
        highlightAngle = { 45f + (x / limit.coerceAtLeast(1f)).coerceIn(-1f, 1f) * 25f - (y / limit.coerceAtLeast(1f)).coerceIn(-1f, 1f) * 15f },
    )
}
