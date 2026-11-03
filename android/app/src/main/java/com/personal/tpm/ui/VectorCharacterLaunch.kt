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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipPath
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
fun CharacterLaunch(systemReleased: Boolean = true, systemIconBounds: Rect? = null, content: @Composable (finished: Boolean) -> Unit) {
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
    val character = if (!finished) ImageBitmap.imageResource(R.drawable.launch_character_first) else null
    val density = LocalContext.current.applicationContext.resources.displayMetrics.density
    val accent = MaterialTheme.colorScheme.primary
    val aperture = remember { Path() }
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
        if (!finished && character != null) Canvas(Modifier.fillMaxSize()) {
            val width = systemIconBounds?.width ?: (161.28f * density).coerceAtMost(size.width * .48f)
            val actorCenter = systemIconBounds?.center?.minus(origin) ?: center
            val phase = reveal.value
            val fade = (1f - phase / .32f).coerceIn(0f, 1f)
            drawImage(character, dstOffset = IntOffset((actorCenter.x - width / 2).roundToInt(), (actorCenter.y - width / 2).roundToInt()),
                dstSize = IntSize(width.roundToInt(), width.roundToInt()), alpha = fade)
            val theta = (if (phase > 0f) releaseAngle else angle) * PI.toFloat() / 180f
            for (index in 0..4) {
                val a = theta - index * .25f
                val point = actorCenter + Offset(cos(a) * width * .66f, sin(a) * width * .66f)
                if (index == 0 && phase > 0f) {
                    val merge = (phase / .32f).coerceIn(0f, 1f)
                    drawCircle(accent, 3.6f * density * (1f + merge * .2f), point * (1f - merge) + center * merge)
                } else drawCircle(accent.copy(alpha = (1f - index * .16f) * fade), (3.6f - index * .35f) * density, point)
            }
        }
        Box(Modifier.fillMaxSize().drawWithContent {
            if (finished) drawContent()
            else {
                val progress = launchRevealProgress(reveal.value)
                if (progress > 0f) {
                    val radius = launchRevealRadius(size.width, size.height) * progress
                    aperture.reset()
                    aperture.addOval(Rect(center.x-radius, center.y-radius, center.x+radius, center.y+radius))
                    clipPath(aperture) { this@drawWithContent.drawContent() }
                }
            }
        }) { if (mounted || finished) CompositionLocalProvider(LocalLaunchReady provides { pageReady = true }) { content(finished) } }
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
