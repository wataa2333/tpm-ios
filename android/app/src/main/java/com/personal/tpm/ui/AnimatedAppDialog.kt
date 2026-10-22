package com.personal.tpm.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog

/** Retains the window until its exit finishes, including back/outside dismissal. */
@Composable
fun AnimatedAppDialog(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    var present by remember { mutableStateOf(false) }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible) present = true
        progress.animateTo(if (visible) 1f else 0f,
            tween(if (visible) Motion.Slow else Motion.Fast, easing = if (visible) Motion.Arrive else Motion.Leave))
        if (!visible) present = false
    }
    if (present) Dialog(onDismissRequest = { if (visible) onDismiss() }) {
        TransparentSystemBars()
        Box(Modifier.graphicsLayer {
            alpha = progress.value
            scaleX = .94f + .06f * progress.value
            scaleY = scaleX
        }) { content() }
    }
}

@Composable
fun AnimatedMessageDialog(visible: Boolean, onDismissRequest: () -> Unit, title: @Composable () -> Unit,
    text: @Composable () -> Unit, confirmButton: @Composable () -> Unit, dismissButton: @Composable () -> Unit = {}) {
    AnimatedAppDialog(visible, onDismissRequest) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                androidx.compose.runtime.CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.headlineSmall) { title() }
                Box(Modifier.weight(1f, fill = false)) { text() }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { dismissButton(); confirmButton() }
            }
        }
    }
}
