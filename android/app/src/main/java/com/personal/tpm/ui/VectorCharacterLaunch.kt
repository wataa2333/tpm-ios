package com.personal.tpm.ui

import android.animation.ValueAnimator
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlin.math.*
import com.personal.tpm.R

val LocalLaunchReady = compositionLocalOf<() -> Unit> { {} }

/** Static logo character; moving dots and aperture are native vector geometry. */
@Composable
fun CharacterLaunch(systemReleased: Boolean = true, systemIconBounds: Rect? = null, systemIconSnapshot: ImageBitmap? = null,
    onFinished: () -> Unit = {}, content: @Composable (finished: Boolean) -> Unit) {
    var finished by rememberSaveable { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var mounted by remember { mutableStateOf(finished) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var releaseAngle by remember { mutableFloatStateOf(0f) }
    val reveal = remember { Animatable(0f) }
    val angle by if (!finished) {
        val orbit = rememberInfiniteTransition(label = "launch orbit")
        orbit.animateFloat(0f, 360f, infiniteRepeatable(tween(1350, easing = LinearEasing)), label = "orbit angle")
    } else remember { mutableFloatStateOf(0f) }
    val character = if (!finished) systemIconSnapshot ?: ImageBitmap.imageResource(R.drawable.launch_character_first) else null
    val density = LocalContext.current.applicationContext.resources.displayMetrics.density
    val accent = MaterialTheme.colorScheme.primary
    val aperture = remember { Path() }
    LaunchedEffect(finished) { if (finished) onFinished() }
    LaunchedEffect(Unit) {
        withFrameNanos { }; withFrameNanos { }; delay(100)
        mounted = true
    }
    LaunchedEffect(pageReady, systemReleased) {
        if (finished) return@LaunchedEffect
        if (!ValueAnimator.areAnimatorsEnabled()) { mounted = true; finished = true; return@LaunchedEffect }
        if (pageReady && systemReleased) {
            releaseAngle = angle
            reveal.animateTo(1f, tween(700, easing = LinearEasing))
            finished = true
        }
    }
    BackHandler(!finished) { }
    Box(Modifier.fillMaxSize().background(Color.White).onGloballyPositioned { origin = it.positionInWindow() }) {
        // Render the real page behind the cover, so its expensive first draw is not deferred to reveal.
        Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = if (finished) CompositingStrategy.Auto else CompositingStrategy.Offscreen }) {
            if (mounted || finished) CompositionLocalProvider(LocalLaunchReady provides { pageReady = true }) { content(finished) }
        }
        if (!finished) Canvas(Modifier.fillMaxSize()) {
            val progress = launchRevealProgress(reveal.value)
            if (progress <= 0f) drawRect(Color.White)
            else {
                val radius = launchRevealRadius(size.width, size.height) * progress
                aperture.reset()
                aperture.fillType = PathFillType.EvenOdd
                aperture.addRect(Rect(Offset.Zero, size))
                aperture.addOval(Rect(center.x-radius, center.y-radius, center.x+radius, center.y+radius))
                drawPath(aperture, Color.White)
            }
        }
        if (!finished && character != null) Canvas(Modifier.fillMaxSize()) {
            val destination = launchIconDestination(character.width.toFloat(), character.height.toFloat(), systemIconBounds,
                origin, center, (161.28f * density).coerceAtMost(size.width * .48f))
            val width = destination.width
            val actorCenter = destination.center
            val phase = reveal.value
            val fade = (1f - phase / .32f).coerceIn(0f, 1f)
            drawImage(character, dstOffset = IntOffset(destination.left.roundToInt(), destination.top.roundToInt()),
                dstSize = IntSize(width.roundToInt(), destination.height.roundToInt()), alpha = fade)
            val orbitWidth = if (systemIconSnapshot != null) width * .56f else width
            val theta = (if (phase > 0f) releaseAngle else angle) * PI.toFloat() / 180f
            for (index in 0..4) {
                val a = theta - index * .25f
                val point = actorCenter + Offset(cos(a) * orbitWidth * .66f, sin(a) * orbitWidth * .66f)
                if (index == 0 && phase > 0f) {
                    val merge = (phase / .32f).coerceIn(0f, 1f)
                    drawCircle(accent.copy(alpha = if (phase <= .32f) 1f else (1f - (phase - .32f) / .04f).coerceIn(0f,1f)),
                        3.6f * density * (1f + merge * .2f), point * (1f - merge) + center * merge)
                } else drawCircle(accent.copy(alpha = (1f - index * .16f) * fade), (3.6f - index * .35f) * density, point)
            }
        }
        if (!finished) Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
    }
}

internal fun launchRevealProgress(phase: Float): Float {
    val t = ((phase - .32f) / .68f).coerceIn(0f, 1f)
    return 1f - (1f - t).pow(3)
}
internal fun launchRevealRadius(width: Float, height: Float): Float = hypot(width / 2f, height / 2f) + 1f

internal fun launchIconDestination(sourceWidth: Float, sourceHeight: Float, measured: Rect?, origin: Offset,
    fallbackCenter: Offset, fallbackWidth: Float): Rect {
    val width = measured?.width ?: fallbackWidth
    val height = width * sourceHeight / sourceWidth
    val center = measured?.center?.minus(origin) ?: fallbackCenter
    return Rect(center.x - width / 2, center.y - height / 2, center.x + width / 2, center.y + height / 2)
}
