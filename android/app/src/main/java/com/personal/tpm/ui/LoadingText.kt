package com.personal.tpm.ui

import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay

@Composable
fun LoadingText(prefix: String = "加载中", style: TextStyle = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color: Color = androidx.compose.material3.LocalContentColor.current) {
    var step by remember { mutableIntStateOf(1) }
    LaunchedEffect(Unit) { while (true) { delay(400); step = step % 3 + 1 } }
    // Fixed three-character suffix prevents neighbouring text from moving on each tick.
    Text(prefix + ".".repeat(step) + "\u00a0".repeat(3 - step), style = style, color = color)
}
