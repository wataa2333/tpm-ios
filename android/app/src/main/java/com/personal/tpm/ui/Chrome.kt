package com.personal.tpm.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.personal.tpm.ui.theme.ThemeConfig
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The widest a page's content gets: on a tablet or a folded-out phone it stays a phone-sized column in the middle. */
val ContentMaxWidth = 640.dp

/** Centres its content in a column no wider than [ContentMaxWidth]. */
@Composable
fun PageWidth(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize(), Alignment.TopCenter) { Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxHeight()) { content() } }
}

/** The status-bar icons dark (over a light surface) or light (over a dark one). */
@Composable
fun StatusBarIcons(dark: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = dark
    }
}

/**
 * The soft edge of a bar: a band of the bar's colour that fades out over the page just below it ([fadeBelow]) or just above it ([fadeAbove]),
 * so what scrolls under a bar melts into it instead of being cut off by a hard line. It is drawn outside the bar's own bounds and takes no touches.
 */
fun Modifier.fadeBelow(color: Color, height: androidx.compose.ui.unit.Dp = 18.dp): Modifier = drawBehind {
    val h = height.toPx()
    drawRect(
        Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f), color.copy(alpha = 0.18f), Color.Transparent), startY = size.height, endY = size.height + h),
        topLeft = Offset(0f, size.height), size = androidx.compose.ui.geometry.Size(size.width, h),
    )
}
fun Modifier.fadeAbove(color: Color, height: androidx.compose.ui.unit.Dp = 22.dp): Modifier = drawBehind {
    val h = height.toPx()
    drawRect(
        Brush.verticalGradient(listOf(Color.Transparent, color.copy(alpha = 0.18f), color.copy(alpha = 0.55f), color), startY = -h, endY = 0f),
        topLeft = Offset(0f, -h), size = androidx.compose.ui.geometry.Size(size.width, h),
    )
}

/** The refresh button: while [spinning] it turns round and round; when the work ends it finishes the turn it is in and rests at its starting place. */
@Composable
fun RefreshButton(spinning: Boolean, onClick: () -> Unit) {
    val turn = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) turn.animateTo(turn.value + 360f, tween(900, easing = androidx.compose.animation.core.LinearEasing))
        } else if (turn.value != 0f) {
            val rest = kotlin.math.ceil(turn.value / 360f) * 360f
            turn.animateTo(rest, tween(Motion.Slow, easing = Motion.Change))
            turn.snapTo(0f)
        }
    }
    IconButton(onClick = onClick) {
        Icon(androidx.compose.material.icons.Icons.Filled.Refresh, contentDescription = "刷新", Modifier.graphicsLayer { rotationZ = turn.value })
    }
}

/** The multi-select button: an icon (a checklist; a cross while selecting). */
@Composable
fun SelectButton(active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        androidx.compose.animation.Crossfade(active, animationSpec = tween(Motion.Fast, easing = Motion.Change), label = "selectIcon") { on ->
            if (on) Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "取消选择")
            else Icon(androidx.compose.material.icons.Icons.Filled.Checklist, contentDescription = "选择")
        }
    }
}

/** Fades the content in the first time it is shown (e.g. a detail page when its data arrives), so a loaded page never pops in. */
@Composable
fun Modifier.fadeInOnShow(): Modifier {
    val shown = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(Motion.Slow, easing = Motion.Change)) }
    return graphicsLayer { alpha = shown.value }
}

/** The top bar's own tone: a light tint of the accent, so it is set apart from the white page and the grey background under it. */
@Composable private fun headerColor() = MaterialTheme.colorScheme.background
@Composable private fun headerContent() = MaterialTheme.colorScheme.onBackground

/**
 * The one loading animation of the app, along the bottom edge of the top bar, in the style chosen on the 我的 page. While something loads
 * it runs; while the page is being pulled down it fills in step with the finger ([pull] 0..1), so the pull has feedback without a spinner
 * of its own. All the motion is eased, none linear.
 *
 * Two things keep it quiet at the end: the look it had while loading stays until it has faded away (it used to switch to the "pull" look
 * for that moment), and the pull that is still springing back after a refresh is ignored (it used to draw one more sweep from left to
 * right, as the pull value ran back down to 0).
 */
data class LoadingReveal(val fraction: Float, val pull: Float, val pulling: Boolean)

/** One transition controls opacity and reserved height, including interruption by a second request. */
@Composable
fun rememberLoadingReveal(loading: Boolean, pull: Float): LoadingReveal {
    var ignorePull by remember { mutableStateOf(false) }
    LaunchedEffect(loading, pull) { if (loading) ignorePull = true else if (pull <= 0.02f) ignorePull = false }
    val shownPull = if (ignorePull) 0f else pull
    val pulling = !loading && shownPull > 0.02f
    val visible by animateFloatAsState(if (loading || pulling) 1f else 0f, tween(Motion.Slow, easing = Motion.Change), label = "loadingReveal")
    return LoadingReveal(visible, shownPull, pulling)
}

@Composable
fun BoxScope.TopLoadingBar(reveal: LoadingReveal) {
    if (reveal.fraction <= 0f) return
    val modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        .then(if (ThemeConfig.loadingStyle.iconStyle) Modifier.height(28.dp * reveal.fraction).clipToBounds() else Modifier)
        .graphicsLayer {
        alpha = reveal.fraction
        scaleY = .85f + .15f * reveal.fraction
        transformOrigin = TransformOrigin(.5f, 1f)
    }
    LoadingStrip(ThemeConfig.loadingStyle, !reveal.pulling, reveal.pull, MaterialTheme.colorScheme.primary, modifier)
}

/** One style drawn by its own composable; [running] is the loop, otherwise [pull] (0..1) fills it in step with the finger. */
@Composable
private fun LoadingStrip(style: ThemeConfig.LoadingStyle, running: Boolean, pull: Float, color: Color, modifier: Modifier) {
    when (style) {
        ThemeConfig.LoadingStyle.Ring, ThemeConfig.LoadingStyle.Chase, ThemeConfig.LoadingStyle.Grid, ThemeConfig.LoadingStyle.Plane, ThemeConfig.LoadingStyle.Bounce, ThemeConfig.LoadingStyle.Equalizer -> SpinKitStrip(style, running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Bar -> {
            if (running) LinearProgressIndicator(modifier.height(3.dp), color = color, trackColor = Color.Transparent)
            else LinearProgressIndicator(progress = { pull.coerceIn(0f, 1f) }, modifier = modifier.height(3.dp), color = color, trackColor = Color.Transparent)
        }
        ThemeConfig.LoadingStyle.Spread -> SpreadStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Comet -> CometStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Dots -> DotsStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Dashes -> DashesStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Wave -> WaveStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Meet -> MeetStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Twinkle -> TwinkleStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Ecg -> EcgStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Breath -> BreathStrip(running, pull, color, modifier)
        ThemeConfig.LoadingStyle.Shimmer -> ShimmerStrip(running, pull, color, modifier)
    }
}

/** One loading style running by itself, as it will look in the top bar: the preview under the choices on the 我的 page. */
@Composable
fun LoadingStylePreview(style: ThemeConfig.LoadingStyle, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Box(modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.surfaceContainer), Alignment.Center) {
        androidx.compose.animation.AnimatedContent(
            targetState = style, label = "preview",
            transitionSpec = { androidx.compose.animation.fadeIn(tween(Motion.Normal, easing = Motion.Change)) togetherWith androidx.compose.animation.fadeOut(tween(Motion.Fast, easing = Motion.Change)) },
        ) { which ->
            LoadingStrip(which, true, 0f, color, Modifier.fillMaxWidth())
        }
    }
}

/** The eased 0..1 loop every style runs on; [millis] long. */
@Composable
private fun loopPhase(millis: Int, seamless: Boolean = false): Float {
    // A strip that is the same picture at phase 0 and at phase 1 (a wave, a row of twinkling dots) must not ease: easing stops it at the end of
    // every round and starts it again, which showed as a stutter. The ones that sweep across and fade out keep the eased curve.
    val transition = rememberInfiniteTransition(label = "loop")
    val phase by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(millis, easing = if (seamless) androidx.compose.animation.core.LinearEasing else FastOutSlowInEasing), RepeatMode.Restart), label = "phase")
    return phase
}

/** 0 outside [reach] of [position], rising to 1 on it along a cosine: the swell that travels along a strip. */
private fun swell(index: Float, position: Float, reach: Float): Float {
    val distance = kotlin.math.abs(index - position)
    return if (distance < reach) ((kotlin.math.cos(distance / reach * Math.PI) + 1) / 2).toFloat() else 0f
}

/** A bright head with a tail behind it runs across the width, slow at the ends and quick in the middle; the tail thins away. */
@Composable
private fun CometStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1500)
    androidx.compose.foundation.Canvas(modifier.height(4.dp)) {
        val thick = 3.dp.toPx()
        val top = (size.height - thick) / 2
        if (!loading) {
            val width = size.width * pull.coerceIn(0f, 1f)
            drawRoundRect(color, Offset(0f, top), androidx.compose.ui.geometry.Size(width, thick), androidx.compose.ui.geometry.CornerRadius(thick / 2))
            return@Canvas
        }
        val tail = size.width * 0.32f
        val head = phase * (size.width + tail)
        val left = (head - tail).coerceAtLeast(0f)
        val right = head.coerceAtMost(size.width)
        if (right > left) drawRoundRect(
            Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color), startX = head - tail, endX = head), Offset(left, top),
            androidx.compose.ui.geometry.Size(right - left, thick), androidx.compose.ui.geometry.CornerRadius(thick / 2),
        )
    }
}

/** One bar that grows from the middle to both edges and thins away as it gets there. */
@Composable
private fun SpreadStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1300)
    androidx.compose.foundation.Canvas(modifier.height(4.dp)) {
        val thick = 3.dp.toPx()
        val fraction = if (loading) phase else pull.coerceIn(0f, 1f)
        val alpha = if (loading) 1f - phase * phase else 1f
        val width = size.width * fraction
        drawRoundRect(
            color.copy(alpha = alpha), Offset((size.width - width) / 2, (size.height - thick) / 2), androidx.compose.ui.geometry.Size(width, thick),
            androidx.compose.ui.geometry.CornerRadius(thick / 2),
        )
    }
}

/** A faint track with a soft band of light gliding along it, like light over brushed metal. */
@Composable
private fun ShimmerStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1800)
    androidx.compose.foundation.Canvas(modifier.height(4.dp)) {
        val thick = 3.dp.toPx()
        val top = (size.height - thick) / 2
        val corner = androidx.compose.ui.geometry.CornerRadius(thick / 2)
        drawRoundRect(color.copy(alpha = 0.18f), Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, thick), corner)
        if (!loading) {
            drawRoundRect(color, Offset(0f, top), androidx.compose.ui.geometry.Size(size.width * pull.coerceIn(0f, 1f), thick), corner)
            return@Canvas
        }
        val band = size.width * 0.4f
        val centre = phase * (size.width + band) - band / 2
        drawRoundRect(
            Brush.horizontalGradient(listOf(color.copy(alpha = 0f), color, color.copy(alpha = 0f)), startX = centre - band / 2, endX = centre + band / 2),
            Offset(0f, top), androidx.compose.ui.geometry.Size(size.width, thick), corner,
        )
    }
}

// ---- the older loading styles (offered on the developer page only) ----

/** A row of small dots across the whole width; a swell of bigger, brighter dots runs along it. */
@Composable
private fun DotsStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1500)
    androidx.compose.foundation.Canvas(modifier.height(10.dp)) {
        val count = 30
        val step = size.width / count
        val base = 1.6.dp.toPx()
        val grow = 2.6.dp.toPx()
        val reach = 4.5f
        val position = phase * (count + 2 * reach) - reach
        for (i in 0 until count) {
            val bump = if (loading) swell(i.toFloat(), position, reach) else if (i < pull.coerceIn(0f, 1f) * count) 1f else 0f
            drawCircle(color.copy(alpha = 0.22f + 0.78f * bump), base + grow * bump, Offset(step * (i + 0.5f), size.height / 2))
        }
    }
}

/** A row of short dashes with gaps between them; a swell of longer, brighter dashes runs along it. */
@Composable
private fun DashesStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1600)
    androidx.compose.foundation.Canvas(modifier.height(8.dp)) {
        val count = 18
        val step = size.width / count
        val thick = 3.dp.toPx()
        val reach = 3.5f
        val position = phase * (count + 2 * reach) - reach
        for (i in 0 until count) {
            val bump = if (loading) swell(i.toFloat(), position, reach) else if (i < pull.coerceIn(0f, 1f) * count) 1f else 0f
            val length = step * (0.42f + 0.28f * bump)
            val centre = step * (i + 0.5f)
            drawRoundRect(
                color.copy(alpha = 0.22f + 0.78f * bump), Offset(centre - length / 2, (size.height - thick) / 2), androidx.compose.ui.geometry.Size(length, thick),
                androidx.compose.ui.geometry.CornerRadius(thick / 2),
            )
        }
    }
}

/** A row of thin upright bars whose heights ripple along it like a sound wave. */
@Composable
private fun WaveStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1400, seamless = true)
    androidx.compose.foundation.Canvas(modifier.height(14.dp)) {
        val count = 36
        val step = size.width / count
        val barWidth = 2.dp.toPx()
        val low = 2.dp.toPx()
        val high = 11.dp.toPx()
        for (i in 0 until count) {
            val lift = if (loading) {
                val wave = kotlin.math.sin((phase * 2.0 - i / 9.0) * Math.PI).toFloat()
                wave * wave
            } else if (i < pull.coerceIn(0f, 1f) * count) 1f else 0f
            val height = low + (high - low) * lift
            drawRoundRect(
                color.copy(alpha = 0.25f + 0.75f * lift), Offset(step * (i + 0.5f) - barWidth / 2, size.height - height), androidx.compose.ui.geometry.Size(barWidth, height),
                androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Two heads run in from the two edges, meet in the middle and fade there, then start again. */
@Composable
private fun MeetStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1400)
    androidx.compose.foundation.Canvas(modifier.height(4.dp)) {
        val thick = 3.dp.toPx()
        val top = (size.height - thick) / 2
        val corner = androidx.compose.ui.geometry.CornerRadius(thick / 2)
        if (!loading) {
            val each = size.width / 2f * pull.coerceIn(0f, 1f)
            drawRoundRect(color, Offset(0f, top), androidx.compose.ui.geometry.Size(each, thick), corner)
            drawRoundRect(color, Offset(size.width - each, top), androidx.compose.ui.geometry.Size(each, thick), corner)
            return@Canvas
        }
        val reach = size.width / 2f * phase
        val length = size.width * 0.22f
        val alpha = if (phase > 0.85f) (1f - phase) / 0.15f else 1f
        val head = reach.coerceAtMost(size.width / 2f)
        val from = (head - length).coerceAtLeast(0f)
        drawRoundRect(color.copy(alpha = alpha), Offset(from, top), androidx.compose.ui.geometry.Size(head - from, thick), corner)
        drawRoundRect(color.copy(alpha = alpha), Offset(size.width - head, top), androidx.compose.ui.geometry.Size(head - from, thick), corner)
    }
}

/** A row of small dots that brighten and dim out of step with one another, like lights on a string. */
@Composable
private fun TwinkleStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1800, seamless = true)
    androidx.compose.foundation.Canvas(modifier.height(10.dp)) {
        val count = 26
        val step = size.width / count
        val radius = 2.2.dp.toPx()
        for (i in 0 until count) {
            val lit = if (loading) {
                val offset = ((i * 0.6180339f) % 1f)
                val t = (phase + offset) % 1f
                val wave = kotlin.math.sin(t * Math.PI).toFloat()
                wave * wave
            } else if (i < pull.coerceIn(0f, 1f) * count) 1f else 0f
            drawCircle(color.copy(alpha = 0.15f + 0.85f * lit), radius * (0.7f + 0.5f * lit), Offset(step * (i + 0.5f), size.height / 2))
        }
    }
}

/** A heartbeat line: a thin baseline, and a beat travels along it and fades behind. */
@Composable
private fun EcgStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(1700)
    androidx.compose.foundation.Canvas(modifier.height(16.dp)) {
        val mid = size.height / 2
        val line = 1.dp.toPx()
        drawLine(color.copy(alpha = 0.25f), Offset(0f, mid), Offset(size.width, mid), line)
        if (!loading) {
            drawLine(color, Offset(0f, mid), Offset(size.width * pull.coerceIn(0f, 1f), mid), line * 2)
            return@Canvas
        }
        val shape = listOf(0f to 0f, 0.15f to 0f, 0.20f to -0.15f, 0.25f to 0f, 0.35f to 0f, 0.40f to 0.25f, 0.47f to -1f, 0.54f to 0.6f, 0.60f to 0f, 0.75f to 0f, 0.82f to -0.25f, 0.90f to 0f, 1f to 0f)
        fun at(u: Float): Float {
            for (i in 1 until shape.size) if (u <= shape[i].first) {
                val (x0, y0) = shape[i - 1]; val (x1, y1) = shape[i]
                return y0 + (y1 - y0) * ((u - x0) / (x1 - x0))
            }
            return 0f
        }
        val span = size.width * 0.34f
        val head = phase * (size.width + span)
        val path = androidx.compose.ui.graphics.Path()
        var started = false
        var x = (head - span).coerceAtLeast(0f)
        val end = head.coerceAtMost(size.width)
        while (x <= end) {
            val u = (x - (head - span)) / span
            val y = mid + at(u) * (size.height / 2 - 1.dp.toPx())
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
            x += 2.dp.toPx()
        }
        if (started) drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** One line from the middle that widens and brightens as if breathing in, and narrows as it breathes out. */
@Composable
private fun BreathStrip(loading: Boolean, pull: Float, color: Color, modifier: Modifier) {
    val phase = loopPhase(2600, seamless = true)
    androidx.compose.foundation.Canvas(modifier.height(4.dp)) {
        val thick = 3.dp.toPx()
        val breath = kotlin.math.sin(phase * Math.PI).toFloat().let { it * it }
        val fraction = if (loading) 0.18f + 0.82f * breath else pull.coerceIn(0f, 1f)
        val alpha = if (loading) 0.35f + 0.65f * breath else 1f
        val width = size.width * fraction
        drawRoundRect(
            color.copy(alpha = alpha), Offset((size.width - width) / 2, (size.height - thick) / 2), androidx.compose.ui.geometry.Size(width, thick),
            androidx.compose.ui.geometry.CornerRadius(thick / 2),
        )
    }
}

/**
 * The plain top bar of a screen that was opened from another (a list, an order): back arrow, title, a smaller line under it, actions.
 */
@Composable
fun AppTopBar(title: String, modifier: Modifier = Modifier, subtitle: String? = null, loading: Boolean = false, pull: Float = 0f, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val loadingReveal = rememberLoadingReveal(loading, pull)
    val loadingSpace = if (ThemeConfig.loadingStyle.iconStyle) 28.dp * loadingReveal.fraction else 0.dp
    StatusBarIcons(dark = headerColor().luminance() > 0.5f)
    Surface(modifier.zIndex(1f).fadeBelow(headerColor()).fillMaxWidth(), color = headerColor(), contentColor = headerContent()) {
        Box {
            Row(Modifier.padding(bottom = loadingSpace).statusBarsPadding().height(64.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                else Spacer(Modifier.width(20.dp))
                // The title changes by sliding up out and in; the line under it (the number of orders) opens and closes with its own motion, and since the
                // two are centred together the title glides up when the line appears and back down when it goes, instead of jumping.
                var lastSubtitle by remember { mutableStateOf(subtitle) }
                if (subtitle != null) lastSubtitle = subtitle
                val curve = FastOutSlowInEasing
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                    androidx.compose.animation.AnimatedContent(
                        targetState = title, label = "title",
                        transitionSpec = {
                            (androidx.compose.animation.slideInVertically(tween(Motion.Normal, easing = Motion.Change)) { it / 2 } + androidx.compose.animation.fadeIn(tween(Motion.Normal, easing = Motion.Change))) togetherWith
                                (androidx.compose.animation.slideOutVertically(tween(Motion.Normal, easing = Motion.Change)) { -it / 2 } + androidx.compose.animation.fadeOut(tween(Motion.Fast, easing = Motion.Change)))
                        },
                    ) { text -> AnimatedLabel(text, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = subtitle != null,
                        enter = androidx.compose.animation.expandVertically(tween(Motion.Normal, easing = Motion.Change)) + androidx.compose.animation.fadeIn(tween(Motion.Normal, easing = Motion.Change)),
                        exit = androidx.compose.animation.shrinkVertically(tween(Motion.Normal, easing = Motion.Change)) + androidx.compose.animation.fadeOut(tween(Motion.Fast, easing = Motion.Change)),
                    ) {
                        androidx.compose.animation.AnimatedContent(
                            targetState = lastSubtitle, label = "subtitle",
                            transitionSpec = { (androidx.compose.animation.slideInVertically(tween(Motion.Normal, easing = Motion.Change)) { it } + androidx.compose.animation.fadeIn(tween(Motion.Normal, easing = Motion.Change))) togetherWith (androidx.compose.animation.slideOutVertically(tween(Motion.Fast, easing = Motion.Change)) { -it } + androidx.compose.animation.fadeOut(tween(Motion.Fast, easing = Motion.Change))) },
                        ) { text -> AnimatedLabel(text.orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = headerContent().copy(alpha = 0.75f)) }
                    }
                }
                actions()
            }
            TopLoadingBar(loadingReveal)
        }
    }
}

/**
 * The page body under a top bar: it starts with rounded top corners, so the bar and the page read as two layers (the bar's
 * tint shows in the corners).
 */
@Composable
fun RoundedSheet(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(28.dp).background(headerColor()))
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = color) { content() }
    }
}

// ---- the large title that collapses with the finger ----

/**
 * How far the big top bar of a tab is folded: 0 is fully open, [maxCollapsePx] fully closed. It follows the finger one-to-one while
 * a scrolling list under it moves, and when the finger lifts it settles to the nearer end.
 */
@Stable
class CollapsingBarState(private val maxCollapsePx: Float) {
    var offset by mutableFloatStateOf(0f)
    private var motionGeneration = 0
    val fraction: Float get() = if (maxCollapsePx <= 0f) 0f else (offset / maxCollapsePx).coerceIn(0f, 1f)

    /** Return only the finger distance actually used by the header. A new drag supersedes an old settle. */
    fun dragBy(delta: Float): Float {
        motionGeneration++
        val before = offset
        offset = (offset - delta).coerceIn(0f, maxCollapsePx.coerceAtLeast(0f))
        return before - offset
    }

    /** Put this on the container of the scrolling list (inside any pull-to-refresh, so the bar opens before the refresh starts). */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Reveal before the child's native overscroll handles a downward drag; fold before scrolling up.
            return if (available.y != 0f) Offset(0f, dragBy(available.y)) else Offset.Zero
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // The list is at its top and the finger keeps moving down: the bar opens.
            if (available.y > 0f && offset > 0f) {
                return Offset(0f, dragBy(available.y))
            }
            return Offset.Zero
        }
        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            settle()
            return Velocity.Zero
        }
    }

    suspend fun settle() {
        if (offset <= 0f || offset >= maxCollapsePx) return
        val mine = ++motionGeneration
        animate(offset, if (fraction > 0.5f) maxCollapsePx else 0f, animationSpec = tween(Motion.Fast, easing = Motion.Change)) { value, _ -> if(mine == motionGeneration) offset = value }
    }
    suspend fun expand() {
        val mine = ++motionGeneration
        if (offset > 0f) animate(offset, 0f, animationSpec = tween(Motion.Normal, easing = Motion.Change)) { value, _ -> if(mine == motionGeneration) offset = value }
    }
}

@Composable
fun rememberCollapsingBarState(): CollapsingBarState {
    val density = LocalDensity.current
    return remember(density.density) { CollapsingBarState(with(density) { CollapsedRange.toPx() }) }
}

private val BarRow = 64.dp
private val CollapsedRange = 56.dp

/**
 * The top bar of a tab: a big title that shrinks into the bar as the page scrolls. It is one piece of text that is scaled and moved by
 * the same amount as the finger has moved (not two texts swapped), so the change is continuous and follows the hand.
 */
@Composable
fun CollapsingTopBar(title: String, state: CollapsingBarState, modifier: Modifier = Modifier, loading: Boolean = false, pull: Float = 0f, onTitleClick: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val loadingReveal = rememberLoadingReveal(loading, pull)
    val loadingSpace = if (ThemeConfig.loadingStyle.iconStyle) 28.dp * loadingReveal.fraction else 0.dp
    StatusBarIcons(dark = headerColor().luminance() > 0.5f)
    Surface(modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { state.dragBy(it) },
        onDragStopped = { state.settle() },
    ).zIndex(1f).fadeBelow(headerColor()).fillMaxWidth(), color = headerColor(), contentColor = headerContent()) {
      Box {
        Box(
            Modifier.padding(bottom = loadingSpace).statusBarsPadding().fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                val full = (BarRow + CollapsedRange).roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = full, maxHeight = full))
                layout(placeable.width, (full - state.offset).roundToInt().coerceAtLeast(BarRow.roundToPx())) { placeable.place(0, 0) }
            },
        ) {
            Row(Modifier.align(Alignment.TopEnd).height(BarRow).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
            Text(
                title,
                Modifier.align(Alignment.BottomStart).then(
                    // No ripple and no sound: the title only counts taps (see the developer page).
                    if (onTitleClick != null) Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onTitleClick) else Modifier,
                ).padding(start = 20.dp, end = 180.dp, bottom = 16.dp).graphicsLayer {
                    val f = state.fraction
                    val scale = 1f - 0.22f * f
                    scaleX = scale; scaleY = scale
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    // It moves up with the bar's bottom edge; this last part settles it on the middle of the folded bar.
                    translationY = -state.offset + f * (size.height / 2f - 16.dp.toPx())
                },
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        TopLoadingBar(loadingReveal)
      }
    }
}

// ---- parts of a page that fold away while the list scrolls ----

/**
 * A strip (search box, status tabs) that folds away when the list is scrolled towards its end and comes straight back when it is
 * scrolled the other way, giving the list the room. It follows the finger; nothing is animated on its own.
 */
@Stable
class ScrollHideState {
    var offset by mutableFloatStateOf(0f)
    var maxPx = 0f

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val dy = available.y
            if (dy == 0f || maxPx <= 0f) return Offset.Zero
            val next = (offset - dy).coerceIn(0f, maxPx)
            val used = -(next - offset)
            offset = next
            return Offset(0f, used)
        }
    }

    suspend fun show() {
        if (offset > 0f) animate(offset, 0f, animationSpec = tween(Motion.Normal, easing = Motion.Change)) { value, _ -> offset = value }
    }
}

fun Modifier.scrollHide(state: ScrollHideState): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        state.maxPx = placeable.height.toFloat()
        val hidden = state.offset.coerceIn(0f, state.maxPx).roundToInt()
        layout(placeable.width, placeable.height - hidden) { placeable.place(0, -hidden) }
    }
    .graphicsLayer { alpha = 1f - (state.offset / max(state.maxPx, 1f)).coerceIn(0f, 1f) }


/**
 * Kept for the call sites: the interface size is now applied to the whole Activity (MainActivity.attachBaseContext), so sheets, dialogs and
 * pickers already come out at the right size and nothing has to be put right here.
 */
@Composable
fun EnsureScaled(content: @Composable () -> Unit) = content()

/**
 * Sheets and dialogs are windows of their own, and a window made by the system gets the phone's own translucent shade behind the navigation bar
 * (the "contrast scrim"; the main window has it switched off in MainActivity). Call this inside the content of such a window: its navigation bar
 * becomes transparent like the page's, and what the window draws shows through.
 */
@Composable
fun TransparentSystemBars() {
    val view = LocalView.current
    val highRefresh = ThemeConfig.highRefresh
    val darkNavigationIcons = MaterialTheme.colorScheme.surface.luminance() > .5f
    SideEffect {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: view.context.findActivity()?.window ?: return@SideEffect
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarDividerColor = android.graphics.Color.TRANSPARENT
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        WindowCompat.getInsetsController(window,view).isAppearanceLightNavigationBars=darkNavigationIcons
        applyWindowRefreshRate(window, view.display, highRefresh)
    }
}

/** A bottom sheet that always follows the interface size. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit, sheetState: androidx.compose.material3.SheetState = androidx.compose.material3.rememberModalBottomSheetState(),
    containerColor: Color = androidx.compose.material3.BottomSheetDefaults.ContainerColor, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    // Let the list consume its own drag/fling, including its overscroll at either edge.
    // Drags on non-scrolling areas such as the handle remain available for dismissing the sheet.
    val scrollBoundary = remember { object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) = Offset(0f, available.y)
        override suspend fun onPostFling(consumed: Velocity, available: Velocity) = Velocity(0f, available.y)
    } }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = containerColor,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) }) {
        TransparentSystemBars()
        androidx.compose.runtime.CompositionLocalProvider(LocalScrollSurface provides containerColor) {
            Column(Modifier.nestedScroll(scrollBoundary)) { EnsureScaled { content() } }
        }
    }
}
