package com.personal.tpm.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/** In-place pages still own their close action, so consume the gesture progress before closing. */
@Composable
fun predictivePageBack(enabled: Boolean, onBack: () -> Unit): Modifier {
    val active by rememberUpdatedState(enabled)
    val close by rememberUpdatedState(onBack)
    var tracking by remember { mutableStateOf(false) }
    var distance by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(distance,
        animationSpec = if (tracking) snap() else tween(Motion.Fast), label = "返回手势")
    PredictiveBackHandler(enabled) { events ->
        val startedEnabled = active
        tracking = startedEnabled
        try {
            events.collect { event ->
                if (startedEnabled && active) {
                    val direction = if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
                    distance = event.progress * direction
                }
            }
            if (startedEnabled && active) close()
        } finally {
            tracking = false
            distance = 0f
        }
    }
    return Modifier.graphicsLayer { translationX = offset * size.width * 0.25f }
}
