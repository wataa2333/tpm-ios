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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

fun glassTabIndex(x: Float, width: Float, count: Int): Int =
    if (count <= 0 || width <= 0f || !width.isFinite() || !x.isFinite()) 0
    else ((x / width * count).toInt()).coerceIn(0, count - 1)

fun glassDragOffset(delta: Offset, limit: Float): Offset = Offset(
    if (delta.x.isFinite()) (delta.x * .3f).coerceIn(-limit, limit) else 0f,
    if (delta.y.isFinite()) (delta.y * .3f).coerceIn(-limit, limit) else 0f,
)

fun glassTintColor(tint: Color, fallback: Color): Color = if (tint.isSpecified && tint.alpha > 0f) tint else fallback

internal data class GlassFeedback(val input: Modifier, val layer: GraphicsLayerScope.() -> Unit)

/** Observe movement without consuming it: the parent list still owns scroll gestures. */
@Composable
internal fun rememberGlassFeedback(source: MutableInteractionSource, enabled: Boolean): GlassFeedback {
    val pressed by source.collectIsPressedAsState()
    var drag by remember { mutableStateOf(Offset.Zero) }
    val limit = with(LocalDensity.current) { 10.dp.toPx() }
    val scale by animateFloatAsState(if (enabled && pressed) 1.045f else 1f, spring(dampingRatio = .65f, stiffness = 420f), label = "glass elastic press")
    val x by animateFloatAsState(if (enabled && pressed) drag.x else 0f, spring(dampingRatio = .65f, stiffness = 500f), label = "glass drag x")
    val y by animateFloatAsState(if (enabled && pressed) drag.y else 0f, spring(dampingRatio = .65f, stiffness = 500f), label = "glass drag y")
    val input = if (!enabled) Modifier else Modifier.pointerInput(source, limit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || (change.position != change.previousPosition && change.isConsumed)) break
                    drag = glassDragOffset(change.position - down.position, limit)
                } while (true)
            } finally { drag = Offset.Zero }
        }
    }
    return GlassFeedback(input) {
        scaleX = scale; scaleY = 1f - (scale - 1f) * .35f
        translationX = x; translationY = y
    }
}
