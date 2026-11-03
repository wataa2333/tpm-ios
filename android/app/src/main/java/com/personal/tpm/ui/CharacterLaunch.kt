package com.personal.tpm.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.personal.tpm.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

val LocalLaunchReady = compositionLocalOf<() -> Unit> { {} }

/** One complete drawing per pose. The live destination is drawn ABOVE the actor. */
@Composable
fun CharacterLaunch(content: @Composable (finished: Boolean) -> Unit) {
    var finished by rememberSaveable { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var waitingFrame by remember { mutableIntStateOf(0) }
    var releasing by remember { mutableStateOf(false) }
    var destinationMounted by remember { mutableStateOf(finished) }
    val context = LocalContext.current
    val firstFrame = if (!finished) ImageBitmap.imageResource(R.drawable.launch_character_first) else null
    var assetLoaded by remember { mutableStateOf(false) }
    val sheet by produceState<ImageBitmap?>(null) {
        if (!finished) value = runCatching { withContext(Dispatchers.IO) {
            android.graphics.BitmapFactory.decodeResource(context.resources, R.drawable.launch_character_frames)?.asImageBitmap()
        } }.getOrNull()
        assetLoaded = true
    }
    val clock = remember { Animatable(0f) }
    LaunchedEffect(assetLoaded, finished) {
        if (finished) destinationMounted = true
        else if (assetLoaded) {
            // Decode and present the actor first; build the destination after animation has started.
            withFrameNanos { }; withFrameNanos { }; delay(120)
            destinationMounted = true
        }
    }
    LaunchedEffect(sheet, assetLoaded) {
        if (finished) return@LaunchedEffect
        if (!ValueAnimator.areAnimatorsEnabled()) { finished = true; return@LaunchedEffect }
        if (assetLoaded && sheet == null) { finished = true; return@LaunchedEffect }
        if (sheet != null) {
            val start = withFrameNanos { it }
            do {
                val elapsed = withFrameNanos { (it - start) / 1_000_000L }
                waitingFrame = launchWaitingFrame(elapsed)
            } while (!pageReady || waitingFrame != 8)
            releasing = true
            clock.snapTo(540f)
            clock.animateTo(1100f, tween(560, easing = LinearEasing))
            finished = true
        }
    }
    BackHandler(!finished) { }
    Box(Modifier.fillMaxSize().background(Color.White)) {
        if (!finished) (sheet ?: firstFrame)?.let { image ->
            val cleanAlpha = remember { ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
                1f,0f,0f,0f,0f, 0f,1f,0f,0f,0f, 0f,0f,1f,0f,0f, 0f,0f,0f,1.2f,-51f
            ))) }
            Canvas(Modifier.fillMaxSize()) {
                val isSheet = sheet != null
                val frame = if (releasing) launchFrameIndex(clock.value) else waitingFrame
                val cellWidth = if (isSheet) image.width / 4 else image.width
                val cellHeight = if (isSheet) image.height / 3 else image.height
                // System splash uses physical device density, not the app's user-adjusted UI density.
                val width = (161.28f * context.applicationContext.resources.displayMetrics.density).coerceAtMost(size.width * .48f)
                val height = width * cellHeight / cellWidth
                drawImage(image, srcOffset = if (isSheet) IntOffset(frame % 4 * cellWidth, frame / 4 * cellHeight) else IntOffset.Zero,
                    srcSize = IntSize(cellWidth, cellHeight),
                    dstOffset = IntOffset(((size.width - width) / 2).roundToInt(), ((size.height - height) / 2).roundToInt()),
                    dstSize = IntSize(width.roundToInt(), height.roundToInt()), colorFilter = cleanAlpha)
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer {
            val progress = if (finished) 1f else launchPullProgress(clock.value)
            // No surface or shadow can leak into the viewport before the pull begins.
            alpha = if (progress <= 0f) 0f else 1f
            translationY = -size.height * 1.12f * (1f - progress)
            shadowElevation = if (progress in 0.001f..0.999f) (14.dp.toPx() * kotlin.math.sin(progress * Math.PI).toFloat()) else 0f
        }) { if (destinationMounted || finished) CompositionLocalProvider(LocalLaunchReady provides { pageReady = true }) { content(finished) } }
        if (!finished) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
    }
}

internal fun launchPullProgress(milliseconds: Float): Float {
    val t = ((milliseconds - 540f) / 560f).coerceIn(0f, 1f)
    return 1f - (1f - t) * (1f - t) * (1f - t)
}

internal fun launchFrameIndex(milliseconds: Float): Int = (milliseconds / 60f).toInt().coerceIn(0, 11)

internal fun launchWaitingFrame(milliseconds: Long): Int {
    val step = (milliseconds / 60).toInt()
    if (step < 4) return step
    val circle = (step - 4) % 8
    return if (circle <= 4) 4 + circle else 12 - circle
}
