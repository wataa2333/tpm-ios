package com.personal.tpm.ui

import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt

/** One shared tray and one moving selection, with native radio semantics. */
@Composable
fun SingleChoiceGroup(options: List<String>, selected: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, scrollable: Boolean = false,
    columns: Int = options.size.coerceAtLeast(1)) {
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(18.dp)
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<Int, Pair<IntOffset, IntSize>>() }
    val target = bounds[selected]
    LaunchedEffect(selected, target, scroll.viewportSize) {
        if (scrollable && target != null) {
            val left = target.first.x
            val right = left + target.second.width
            val visibleLeft = scroll.value
            val visibleRight = visibleLeft + scroll.viewportSize
            if (left < visibleLeft) scroll.animateScrollTo(left.coerceAtLeast(0))
            else if (right > visibleRight) scroll.animateScrollTo((right - scroll.viewportSize).coerceAtLeast(0))
        }
    }
    val accent = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val ink = glassContentColor(accent, accent, surface, MaterialTheme.colorScheme.onSurface)
    Box(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .62f), RoundedCornerShape(22.dp))
        .then(if (scrollable) Modifier.glassHorizontalScroll(scroll) else Modifier).padding(4.dp).selectableGroup()) {
        if (target != null) {
            val offset by animateIntOffsetAsState(target.first,
                tween(Motion.Normal, easing = Motion.Change), label = "selection position")
            val size by animateIntSizeAsState(target.second,
                tween(Motion.Normal, easing = Motion.Change), label = "selection size")
            Box(Modifier.offset { offset }
                .size(with(density) { size.width.toDp() }, with(density) { size.height.toDp() })
                .then(if (AppPreferences.liquidGlass) Modifier.glassSurface(backdrop = null, shape = shape, tint = accent, prominent = true)
                    else Modifier.background(accent, shape)))
        }
        val chunks = if (scrollable) listOf(options.indices.toList()) else options.indices.chunked(columns.coerceAtLeast(1))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            chunks.forEach { indices ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    indices.forEach { index ->
                        val active = index == selected
                        Row(Modifier.then(if (scrollable) Modifier else Modifier.weight(1f))
                            .onGloballyPositioned { coordinates ->
                                // The parent row is itself placed in this column.
                                val rowY = coordinates.parentLayoutCoordinates?.positionInParent()?.y ?: 0f
                                val p = coordinates.positionInParent() + Offset(0f, rowY)
                                bounds[index] = IntOffset(p.x.roundToInt(), p.y.roundToInt()) to coordinates.size
                            }
                            .heightIn(min = 44.dp).selectable(active, enabled = enabled, role = Role.RadioButton, onClick = { onSelect(index) })
                            .padding(horizontal = if (scrollable) 14.dp else 8.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            val content = if (active && !AppPreferences.liquidGlass) MaterialTheme.colorScheme.onPrimary
                                else if (active) ink else MaterialTheme.colorScheme.onSurfaceVariant
                            if (active) Icon(Icons.Default.Check, null, Modifier.size(16.dp).padding(end = 3.dp), tint = content)
                            Text(options[index], style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                color = content.copy(alpha = if (enabled) 1f else .55f), maxLines = if (scrollable) 1 else 2)
                        }
                    }
                    if (!scrollable && indices.size < columns) repeat(columns - indices.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}
