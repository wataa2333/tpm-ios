package com.personal.tpm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** Main tabs share one source. Only overlay chrome reads it, so there is no capture feedback. */
@Composable
fun GlassMainFrame(
    topBar: @Composable () -> Unit,
    bottomBar: @Composable () -> Unit,
    floatingActionButton: @Composable () -> Unit,
    snackbarHost: @Composable () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    if (!AppPreferences.liquidGlass) {
        CompositionLocalProvider(LocalGlassContentPadding provides PaddingValues(0.dp), LocalGlassBackdrop provides null) {
            Scaffold(contentWindowInsets = appContentInsets(), containerColor = MaterialTheme.colorScheme.background,
                topBar = topBar, bottomBar = bottomBar, floatingActionButton = floatingActionButton,
                snackbarHost = snackbarHost, content = content)
        }
        return
    }
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.background
    val capture = currentGlassRendering() in listOf(GlassRendering.Blur, GlassRendering.Liquid)
    val backdrop = if (capture) rememberLayerBackdrop { drawRect(color); drawContent() } else null
    val initialTop = 120.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var topHeight by remember(density.density) { mutableIntStateOf(with(density) { initialTop.roundToPx() }) }
    var bottomHeight by remember { mutableIntStateOf(0) }
    // Match nested-scroll consumption: the first item moves up as the large title collapses.
    val topInset = with(density) { topHeight.toDp() }
    val bottomInset = with(density) { bottomHeight.toDp() }
    Box(Modifier.fillMaxSize().background(color).windowInsetsPadding(appContentInsets())) {
        Box(Modifier.fillMaxSize().then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)) {
            // In-content controls use their own independent material, never this captured layer.
            CompositionLocalProvider(LocalGlassBackdrop provides null,
                LocalGlassContentPadding provides PaddingValues(top = topInset, bottom = bottomInset)) {
                content(PaddingValues(0.dp))
            }
        }
        CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
            Box(Modifier.align(Alignment.TopCenter).onSizeChanged { topHeight = it.height }) { topBar() }
            Box(Modifier.align(Alignment.BottomCenter).onSizeChanged { bottomHeight = it.height }) { bottomBar() }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomInset + 16.dp)) { floatingActionButton() }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 80.dp)) { snackbarHost() }
        }
    }
}
