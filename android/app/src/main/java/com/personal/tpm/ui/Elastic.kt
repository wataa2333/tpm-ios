package com.personal.tpm.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The rubber-band at the end of a scrolling thing: pulled past its end it stretches along with the finger (harder the further it goes), and
 * on release it springs back with a little overshoot. One state per axis.
 *
 * The spring runs in a coroutine of its own and is cancelled the moment a finger is down again. (It used to run inside the fling callback,
 * which holds the next drag back until it has finished, so a second pull at the end of a page did nothing for a second or so.)
 */
@Stable
class ElasticState(
    private val maxPx: Float, private val scope: CoroutineScope, private val horizontal: Boolean, private val start: Boolean,
    /** Called when the finger lets go after the far end was pulled out far enough: a list uses it to load its next page. */
    private val onEnd: (() -> Unit)? = null,
) {
    var offset by mutableFloatStateOf(0f)
    private var job: Job? = null

    private fun main(o: Offset) = if (horizontal) o.x else o.y
    private fun main(v: Velocity) = if (horizontal) v.x else v.y
    private fun along(v: Float) = if (horizontal) Offset(v, 0f) else Offset(0f, v)

    /** The distance the finger moves, as it counts at the present stretch: the further out, the less of it. */
    fun resisted(d: Float) = d * 0.5f / (1f + abs(offset) / (maxPx * 0.6f))

    /** A finger is down again: whatever spring was running lets go. */
    fun stop() { job?.cancel(); job = null }

    /** Pull by [d] (finger movement). */
    fun pull(d: Float) { stop(); offset = (offset + resisted(d)).coerceIn(-maxPx, maxPx) }

    /** The finger is up: spring back to rest, starting with the speed that is left ([velocity], px/s along the axis). */
    fun release(velocity: Float = 0f) {
        if (offset == 0f && velocity == 0f) return
        stop()
        if (onEnd != null && offset <= -maxPx * 0.25f) onEnd.invoke()
        job = scope.launch {
            animate(offset, 0f, initialVelocity = velocity, animationSpec = spring(dampingRatio = 0.45f, stiffness = 320f)) { value, _ -> offset = value }
        }
    }

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val d = main(available)
            if (source != NestedScrollSource.UserInput || d == 0f) return Offset.Zero
            stop()
            // The finger comes back while it is stretched: the stretch gives way first.
            if (offset != 0f && ((offset > 0f && d < 0f) || (offset < 0f && d > 0f))) {
                val used = if (offset > 0f) max(d, -offset) else min(d, -offset)
                offset += used
                return along(used)
            }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val d = main(available)
            if (source != NestedScrollSource.UserInput || d == 0f) return Offset.Zero
            // At the start a pull-to-refresh above, or a sheet being dragged away, has the pull: only the far end stretches there.
            if (d > 0f && !start) return Offset.Zero
            offset = (offset + resisted(d)).coerceIn(-maxPx, maxPx)
            return along(d)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            // The finger lifted while it is stretched: it springs back (in a coroutine of its own, so this returns at once).
            if (offset != 0f) { release(0f); return available }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            // Flung into the end: the speed that is left stretches it, and it springs back.
            val v = main(available)
            if (v != 0f && (v < 0f || start)) release(v * 0.12f)
            return Velocity.Zero
        }
    }
}

/**
 * Makes the scrolling content under this modifier stretch at its ends and spring back. [top]: also at the start (false where a
 * pull-to-refresh or a bottom sheet owns the pull there). [sides]: also a pull left or right, which nothing scrolls in, stretches the page
 * sideways and lets it spring back.
 */
@Composable
fun Modifier.elastic(top: Boolean = true, sides: Boolean = true, onEnd: (() -> Unit)? = null): Modifier {
    val maxPx = with(LocalDensity.current) { 160.dp.toPx() }
    val scope = rememberCoroutineScope()
    val latestEnd by androidx.compose.runtime.rememberUpdatedState(onEnd)
    val vertical = remember(top, maxPx, onEnd != null) { ElasticState(maxPx, scope, horizontal = false, start = top, onEnd = if (onEnd != null) ({ latestEnd?.invoke() }) else null) }
    val sideways = remember(maxPx) { ElasticState(maxPx * 0.5f, scope, horizontal = true, start = true) }
    return this
        .clipToBounds()
        .scrollEdges(LocalScrollSurface.current ?: androidx.compose.material3.MaterialTheme.colorScheme.background)
        .nestedScroll(vertical.connection)
        .then(if (sides) Modifier.sidePull(sideways) else Modifier)
        .graphicsLayer { translationY = vertical.offset; translationX = sideways.offset }
}

/** For things that scroll sideways (tab rows, rows of chips): they stretch at either end and spring back. */
@Composable
fun Modifier.elasticRow(): Modifier {
    val maxPx = with(LocalDensity.current) { 96.dp.toPx() }
    val scope = rememberCoroutineScope()
    val state = remember(maxPx) { ElasticState(maxPx, scope, horizontal = true, start = true) }
    return this.clipToBounds().nestedScroll(state.connection).graphicsLayer { translationX = state.offset }
}

/**
 * A horizontal pull on something that does not scroll sideways: it follows the finger with resistance and springs back. A child that scrolls
 * sideways itself has the gesture first (it consumes it), and this does not start then; taps are not affected.
 */
private fun Modifier.sidePull(state: ElasticState): Modifier = pointerInput(state) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.stop()
        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over -> change.consume(); state.pull(over) }
        if (drag != null) {
            horizontalDrag(drag.id) { change ->
                state.pull(change.positionChange().x)
                change.consume()
            }
        }
        state.release(0f)
    }
}

/**
 * Pulling a page down to refresh: the page follows the finger a little way (rubber-band, at most 56 dp) and, the moment the refresh starts,
 * springs back to its place with a bounce. It does not stay pushed down while it refreshes (that left a blank strip above the list): from then
 * on the only feedback is the loading animation of the top bar.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun Modifier.pullDown(state: PullToRefreshState, refreshing: Boolean): Modifier {
    val maxPx = with(LocalDensity.current) { 56.dp.toPx() }
    val offset = remember { androidx.compose.animation.core.Animatable(0f) }
    val fraction = state.distanceFraction.coerceIn(0f, 1.2f)
    // Whether the page follows the finger. It stops following the moment the refresh starts, and only follows again after the pull state has
    // come all the way back to rest: the state animates its own value down after a refresh, and following that made the page jump and shake.
    val following = remember { booleanArrayOf(true) }
    // The bounce back runs once, on its own: nothing else touches the offset while it runs. It runs in a scope of its own, not in the effect keyed on
    // [refreshing]: a quick refresh ends while the spring is still moving, and an effect cancelled at that moment left the page pushed down by what
    // was left of the pull, until the pull state finished and the page snapped up to its place (the little jump after a refresh).
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(refreshing) {
        if (refreshing) {
            following[0] = false
            scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
        }
    }
    LaunchedEffect(fraction, refreshing) {
        if (refreshing) return@LaunchedEffect
        if (fraction <= 0.01f) following[0] = true
        if (following[0]) { if (!offset.isRunning) offset.snapTo(fraction) }
        // Not following and the spring is not running: whatever is left of the pull goes back to nothing by a short eased move, never a jump.
        else if (!offset.isRunning && offset.value != 0f) offset.animateTo(0f, androidx.compose.animation.core.tween(Motion.Fast, easing = Motion.Change))
    }
    // The page never goes above its own place: the spring that brings it back may swing past 0, and that overshoot showed as the page jumping up
    // a little, which at the end of a short refresh looked like a stutter.
    return this.graphicsLayer { translationY = offset.value.coerceAtLeast(0f) * maxPx }
}
