package com.personal.tpm.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import com.personal.tpm.ui.theme.ThemeConfig
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.size
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
    GlassIconButton(onClick = onClick, enabled = !spinning) {
        Icon(androidx.compose.material.icons.Icons.Filled.Refresh, contentDescription = "刷新")
    }
}

/** The multi-select button: an icon (a checklist; a cross while selecting). */
@Composable
fun SelectButton(active: Boolean, onClick: () -> Unit) {
    GlassIconButton(onClick = onClick) {
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

/** Native circular loading/pull feedback, shared by every top bar. */
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
fun BoxScope.TopLoadingBar(reveal: LoadingReveal, linear: Boolean = false) {
    if (linear) {
        if (reveal.fraction > 0f) androidx.compose.material3.LinearProgressIndicator(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
        return
    }
    if (reveal.fraction <= 0f) return
    Box(Modifier.align(Alignment.BottomCenter).height(28.dp * reveal.fraction).fillMaxWidth()
        .graphicsLayer { alpha = reveal.fraction }, Alignment.Center) {
        if (reveal.pulling) androidx.compose.material3.CircularProgressIndicator(progress = { reveal.pull.coerceIn(0f, 1f) },
            modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        else androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}



/**
 * The plain top bar of a screen that was opened from another (a list, an order): back arrow, title, a smaller line under it, actions.
 */
@Composable
fun AppTopBar(title: String, modifier: Modifier = Modifier, subtitle: String? = null, loading: Boolean = false, pull: Float = 0f, onBack: (() -> Unit)? = null, linearLoading: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    val loadingReveal = rememberLoadingReveal(loading, pull)
    val loadingSpace = if (linearLoading) 4.dp else 28.dp * loadingReveal.fraction
    StatusBarIcons(dark = headerColor().luminance() > 0.5f)
    HeaderSurface(modifier.zIndex(1f)) {
        Box {
            Row(Modifier.padding(bottom = loadingSpace).statusBarsPadding().height(64.dp)
                .padding(start = if (AppPreferences.liquidGlass) 16.dp else 0.dp, end = if (AppPreferences.liquidGlass) 16.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    GlassIconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                    Spacer(Modifier.width(if (AppPreferences.liquidGlass) 12.dp else 0.dp))
                }
                else Spacer(Modifier.width(if (AppPreferences.liquidGlass) 4.dp else 20.dp))
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
                if (AppPreferences.liquidGlass) Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
                else actions()
            }
            TopLoadingBar(loadingReveal, linearLoading)
        }
    }
}

/**
 * The page body under a top bar: it starts with rounded top corners, so the bar and the page read as two layers (the bar's
 * tint shows in the corners).
 */
@Composable
fun RoundedSheet(modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.background, content: @Composable () -> Unit) {
    if (AppPreferences.liquidGlass) {
        Box(modifier.fillMaxSize().background(color)) { content() }
        return
    }
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
    val loadingSpace = 28.dp * loadingReveal.fraction
    StatusBarIcons(dark = headerColor().luminance() > 0.5f)
    HeaderSurface(modifier.draggable(
        orientation = Orientation.Vertical,
        state = rememberDraggableState { state.dragBy(it) },
        onDragStopped = { state.settle() },
    ).zIndex(1f)) {
      Box {
        Box(
            Modifier.padding(bottom = loadingSpace).statusBarsPadding().fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                val full = (BarRow + CollapsedRange).roundToPx()
                val placeable = measurable.measure(constraints.copy(minHeight = full, maxHeight = full))
                layout(placeable.width, (full - state.offset).roundToInt().coerceAtLeast(BarRow.roundToPx())) { placeable.place(0, 0) }
            },
        ) {
            Row(Modifier.align(Alignment.TopEnd).height(BarRow).padding(end = if (AppPreferences.liquidGlass) 16.dp else 4.dp),
                horizontalArrangement = Arrangement.spacedBy(if (AppPreferences.liquidGlass) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
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
fun EnsureScaled(content: @Composable () -> Unit) = GlassWindowContent(content)

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
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState, containerColor = if (AppPreferences.liquidGlass) Color.Transparent else containerColor,
        contentWindowInsets = { androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0) }) {
        TransparentSystemBars()
        androidx.compose.runtime.CompositionLocalProvider(LocalScrollSurface provides containerColor, LocalGlassBackdrop provides null, LocalGlassContentPadding provides androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            Column(Modifier.nestedScroll(scrollBoundary).background(containerColor)
                .padding(top = if (AppPreferences.liquidGlass) 12.dp else 0.dp)) { EnsureScaled { content() } }
        }
    }
}
