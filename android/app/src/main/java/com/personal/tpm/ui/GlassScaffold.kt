package com.personal.tpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Shared by all secondary routes. Scroll containers consume the measured chrome insets. */
@Composable
fun GlassScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {}, bottomBar: @Composable () -> Unit = {},
    snackbarHost: @Composable () -> Unit = {}, floatingActionButton: @Composable () -> Unit = {},
    floatingActionButtonPosition: FabPosition = FabPosition.End,
    containerColor: Color = MaterialTheme.colorScheme.background,
    contentColor: Color = contentColorFor(containerColor),
    contentWindowInsets: WindowInsets = appContentInsets(),
    floatingOverlay: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    if (!AppPreferences.liquidGlass) {
        Box {
            Scaffold(modifier, topBar, bottomBar, snackbarHost, floatingActionButton,
                floatingActionButtonPosition, containerColor, contentColor, contentWindowInsets, content)
            floatingOverlay()
        }
        return
    }
    val density = LocalDensity.current
    val capture = currentGlassRendering() in listOf(GlassRendering.Blur, GlassRendering.Liquid)
    val backdrop = if (capture) rememberLayerBackdrop { drawRect(containerColor); drawContent() } else null
    var topHeight by remember { mutableIntStateOf(0) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    val insets = with(density) { PaddingValues(top = topHeight.toDp(), bottom = bottomHeight.toDp()) }
    Box(modifier.fillMaxSize().background(containerColor).windowInsetsPadding(contentWindowInsets)) {
        Box(Modifier.fillMaxSize().then(if (backdrop == null) Modifier else Modifier.layerBackdrop(backdrop))) {
            CompositionLocalProvider(LocalGlassBackdrop provides null, LocalGlassContentPadding provides insets,
                LocalContentColor provides contentColor) { content(PaddingValues(0.dp)) }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop, LocalGlassContentPadding provides PaddingValues(0.dp),
            LocalContentColor provides contentColor) {
            Box(Modifier.align(Alignment.TopCenter).onSizeChanged { topHeight = it.height }) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomHeight = it.height }) {
                if (bottomHeight > 0) GlassFooterBackground(Modifier.matchParentSize())
                bottomBar()
            }
            Box(Modifier.align(if (floatingActionButtonPosition == FabPosition.Center) Alignment.BottomCenter else Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = insets.calculateBottomPadding() + 16.dp)) { floatingActionButton() }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 16.dp)) { snackbarHost() }
            floatingOverlay()
        }
    }
}

/** Native circular indicators are shared by indeterminate requests and measured task progress. */
@Composable
fun AppProgressIndicator(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = Color.Transparent) {
    Box(modifier.heightIn(min = 32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(24.dp), color = color, trackColor = trackColor, strokeWidth = 2.dp)
    }
}

@Composable
fun AppProgressIndicator(progress: () -> Float, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary, trackColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh) {
    val value = progress().coerceIn(0f, 1f)
    Row(modifier.heightIn(min = 32.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically) {
        LinearProgressIndicator(progress = { value }, modifier = Modifier.weight(1f), color = color, trackColor = trackColor)
        Text("${(value * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
    }
}

/** Bar contents remain sharp over their sampled, tinted material. */
@Composable
fun GlassBottomSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!AppPreferences.liquidGlass) {
        Surface(modifier.fadeAbove(MaterialTheme.colorScheme.background), color = MaterialTheme.colorScheme.background, content = content)
    } else {
        Box(modifier.fillMaxWidth()) {
            content()
        }
    }
}
