package com.personal.tpm.ui

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

@Composable
fun HeaderSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    if (!AppPreferences.liquidGlass) {
        Surface(modifier.fillMaxWidth().fadeBelow(colors.background), color = colors.background,
            contentColor = colors.onBackground, content = content)
    } else {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground) {
            Box(modifier.fillMaxWidth()) {
                GlassHeaderBackground(Modifier.matchParentSize().glassHeaderExtension())
                content()
            }
        }
    }
}

/** Only chrome outside the recorded body may sample this layer; never record its readers. */
val LocalGlassBackdrop = compositionLocalOf<Backdrop?> { null }
val LocalGlassContentPadding = compositionLocalOf { PaddingValues(0.dp) }

@Composable
fun currentGlassRendering(): GlassRendering = glassRendering(
    AppPreferences.liquidGlass, Build.VERSION.SDK_INT, LocalView.current.isHardwareAccelerated,
)

/** In-content controls sample an independent theme material, avoiding recursive page capture. */
@Composable
private fun rememberControlBackdrop(): Backdrop {
    val colors = MaterialTheme.colorScheme
    return rememberCanvasBackdrop {
        drawRect(Brush.linearGradient(listOf(colors.surfaceContainerHigh, colors.primaryContainer, colors.surface)))
    }
}

@Composable
fun Modifier.glassSurface(
    backdrop: Backdrop? = LocalGlassBackdrop.current,
    shape: RoundedCornerShape = RoundedCornerShape(28.dp),
    tint: Color = MaterialTheme.colorScheme.surface,
    prominent: Boolean = false,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier {
    val rendering = currentGlassRendering()
    val material = backdrop ?: rememberControlBackdrop()
    // Color.Transparent carries black RGB. Giving it a new alpha would create a black button.
    val surfaceTint = glassTintColor(tint, if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
    if (rendering == GlassRendering.Solid) return graphicsLayer(layerBlock ?: {}).background(surfaceTint, shape)
    if (rendering == GlassRendering.Translucent) return graphicsLayer(layerBlock ?: {}).background(surfaceTint.copy(alpha = .90f * surfaceTint.alpha), shape)
    val alpha = (if (prominent) .88f else .52f) * surfaceTint.alpha
    return drawBackdrop(
        backdrop = material, shape = { shape },
        effects = {
            vibrancy()
            blur(3.dp.toPx())
            if (rendering == GlassRendering.Liquid) lens(6.dp.toPx(), 12.dp.toPx())
        },
        highlight = null,
        shadow = null,
        layerBlock = layerBlock,
        onDrawSurface = { drawRect(surfaceTint.copy(alpha = alpha)) },
    )
}

/** Match Material's public button API, preserving every caller's action and enabled state. */
@Composable
fun GlassActionButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    shape: Shape = ButtonDefaults.shape, colors: ButtonColors = ButtonDefaults.buttonColors(),
    elevation: ButtonElevation? = ButtonDefaults.buttonElevation(), border: BorderStroke? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding, interactionSource: MutableInteractionSource? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val feedback = rememberGlassFeedback(source, AppPreferences.liquidGlass)
    val glass = AppPreferences.liquidGlass
    val fill = if (enabled) colors.containerColor else colors.disabledContainerColor
    Button(onClick = onClick,
        modifier = if (glass) modifier.then(feedback.input)
            .glassSurface(backdrop = null, shape = shape as? RoundedCornerShape ?: RoundedCornerShape(28.dp), tint = fill, prominent = true, layerBlock = feedback.layer) else modifier,
        enabled = enabled, shape = shape,
        colors = if (glass) colors.copy(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent) else colors,
        elevation = if (glass) null else elevation, border = if (glass) null else border, contentPadding = contentPadding,
        interactionSource = source, content = content)
}

/** Paint below the measured header without reserving another opaque strip or catching touches. */
fun Modifier.glassHeaderExtension(): Modifier = layout { measurable, constraints ->
    val extra = 28.dp.roundToPx()
    val child = measurable.measure(constraints.copy(minHeight = constraints.minHeight + extra, maxHeight = constraints.maxHeight + extra))
    layout(child.width, (child.height - extra).coerceAtLeast(0)) { child.place(0, 0) }
}

/**
 * Backdrop's official alpha-masked progressive blur recipe, with the app's theme tint.
 * This fades one GPU blur; it does not claim a continuously varying blur radius.
 * Source: https://kyant.gitbook.io/backdrop/tutorials/progressive-blur
 * Copyright 2025 Kyant. Adapted under Apache-2.0; see assets/licenses/Backdrop.txt.
 */
private const val HeaderAlphaMask = """
uniform shader content;
uniform float2 size;
layout(color) uniform half4 tint;
uniform float tintIntensity;
half4 main(float2 coord) {
    float blurAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    float tintAlpha = smoothstep(size.y, size.y * 0.5, coord.y);
    return mix(content.eval(coord) * blurAlpha, tint * tintAlpha, tintIntensity);
}
"""

@Composable
fun GlassHeaderBackground(modifier: Modifier = Modifier) {
    val source = LocalGlassBackdrop.current
    val rendering = currentGlassRendering()
    val surface = MaterialTheme.colorScheme.background
    val fallbackBrush = Brush.verticalGradient(listOf(surface.copy(alpha = .96f), surface.copy(alpha = .88f), surface.copy(alpha = .50f), Color.Transparent))
    if (source == null || rendering in listOf(GlassRendering.Solid, GlassRendering.Translucent)) {
        Box(modifier.background(fallbackBrush))
        return
    }
    if (rendering == GlassRendering.Liquid) {
        Box(modifier.drawPlainBackdrop(source, shape = { RectangleShape }, effects = {
            blur(6.dp.toPx())
            effect(RenderEffect.createRuntimeShaderEffect(obtainRuntimeShader("HeaderAlphaMask", HeaderAlphaMask).apply {
                setFloatUniform("size", size.width, size.height)
                setColorUniform("tint", surface.toArgb())
                setFloatUniform("tintIntensity", .72f)
            }, "content"))
        }))
    } else {
        // Android 12 supports RenderEffect blur but no RuntimeShader. Isolate the alpha mask.
        Box(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(listOf(Color.White, Color.White, Color.Transparent)), blendMode = BlendMode.DstIn)
            }
            .drawPlainBackdrop(source, shape = { RectangleShape }, effects = { blur(6.dp.toPx()) },
                onDrawSurface = { drawRect(surface.copy(alpha = .72f)) }))
    }
}

/** Transparent Material buttons keep click semantics, ripple, focus and minimum touch targets. */
@Composable
fun GlassButton(
    onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
    prominent: Boolean = false, contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable RowScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val feedback = rememberGlassFeedback(interactions, true)
    val shape = RoundedCornerShape(28.dp)
    val tint = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val ink = if (!enabled) contentColor.copy(alpha = .38f) else contentColor
    Button(
        onClick, modifier.then(feedback.input)
            .glassSurface(backdrop = null, shape = shape, tint = tint, prominent = prominent, layerBlock = feedback.layer)
            .then(if (loading) Modifier.semantics { stateDescription = "正在处理" } else Modifier),
        enabled = enabled && !loading, shape = shape, interactionSource = interactions,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = ink,
            disabledContainerColor = Color.Transparent, disabledContentColor = ink),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        if (loading) { CircularProgressIndicator(Modifier.size(18.dp), color = ink, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        content()
    }
}

@Composable
fun GlassIconButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    colors: IconButtonColors = IconButtonDefaults.iconButtonColors(), interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit) {
    if (!AppPreferences.liquidGlass) { IconButton(onClick, modifier, enabled = enabled, colors = colors, interactionSource = interactionSource, content = content); return }
    val interactions = interactionSource ?: remember { MutableInteractionSource() }
    val feedback = rememberGlassFeedback(interactions, enabled)
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick, Modifier.size(44.dp).then(feedback.input)
            .glassSurface(shape = CircleShape, layerBlock = feedback.layer), enabled = enabled, colors = colors, interactionSource = interactions, content = content)
    }
}

data class GlassTab(val title: String, val icon: ImageVector)

@Composable
fun GlassBottomBar(tabs: List<GlassTab>, selected: Int, badge: Int, onSelect: (Int) -> Unit) {
    var width by remember { mutableIntStateOf(0) }
    var dragX by remember { mutableStateOf<Float?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val cell = if (tabs.isEmpty()) 0f else width.toFloat() / tabs.size
    val visual = dragX?.let { glassTabIndex(it, width.toFloat(), tabs.size) } ?: selected
    val target = dragX?.let { (it - cell / 2).coerceIn(0f, (width - cell).coerceAtLeast(0f)) } ?: (selected * cell)
    val indicator by animateFloatAsState(target, androidx.compose.animation.core.spring(dampingRatio = .78f, stiffness = 450f), label = "glass tab drag")
    val stretch by animateFloatAsState(if (dragX != null) 1.04f else 1f, androidx.compose.animation.core.spring(), label = "glass tab stretch")
    val dragState = androidx.compose.foundation.gestures.rememberDraggableState { delta -> dragX = ((dragX ?: selected * cell + cell / 2) + delta).coerceIn(0f, width.toFloat()) }
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
            .glassSurface(shape = RoundedCornerShape(36.dp), layerBlock = { scaleX = stretch; scaleY = stretch })
            .padding(5.dp).onSizeChanged { width = it.width }
            .draggable(state = dragState, orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                onDragStarted = { dragX = it.x.coerceIn(0f, width.toFloat()) },
                onDragStopped = {
                    val next = glassTabIndex(dragX ?: selected * cell + cell / 2, width.toFloat(), tabs.size)
                    dragX = null
                    if (next != selected) onSelect(next)
                })) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(indicator.toInt(), 0) }
                    .width(with(density) { cell.toDp() }).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), RoundedCornerShape(30.dp)))
            }
            Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
                tabs.forEachIndexed { index, tab ->
                    val ink = if (index == visual) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(Modifier.weight(1f).heightIn(min = 60.dp).clip(RoundedCornerShape(30.dp))
                        .selectable(index == selected, role = Role.Tab, onClick = { if (index != selected) onSelect(index) })
                        .padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
                        if (index == 1 && badge > 0) BadgedBox(badge = { Badge { Text(if (badge > 99) "99+" else "$badge") } }) {
                            Icon(tab.icon, contentDescription = null, tint = ink)
                        } else Icon(tab.icon, contentDescription = null, tint = ink)
                        Text(tab.title, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A visible sample lives next to the developer switch so every control state is reviewable. */
@Composable
fun GlassControlPreview() {
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (busy) { kotlinx.coroutines.delay(1500); busy = false } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassButton(onClick = { busy = true }, loading = busy, prominent = true) { Text("按钮预览") }
        GlassButton(onClick = {}, enabled = false) { Text("不可用") }
    }
}
