package com.personal.tpm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.OilBarrel
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.FaultState
import com.personal.tpm.data.Session
import com.personal.tpm.data.filter
import com.personal.tpm.data.serverPageCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The numbers on the home cards: one request per state asking for a single row, only its total is used. */
class HomeViewModel(private val session: Session) : ViewModel() {
    var counts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var refreshing by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    private var ticket = 0

    init { reload() }
    init { viewModelScope.launch { session.changed.drop(1).collect { reload() } } }

    fun reload(pull: Boolean = false) {
        val mine = ++ticket
        refreshing = pull; loading = true
        viewModelScope.launch {
            try {
                val fresh = coroutineScope {
                    val orders = FaultState.entries.filter { it.hasCount }.map { st ->
                        async {
                            try { st.code to serverPageCount(session.page("fault/pageAssetFault", filters = listOf(filter("faultState", st.code)), limit = 1)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { st.code to null }
                        }
                    }
                    // The two pools of waiting maintenance and precision-check orders.
                    val pools = PoolKind.entries.map { kind ->
                        async {
                            try { kind.path to serverPageCount(session.page(kind.page, filters = kind.waitingFilters(), limit = 1)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { kind.path to null }
                        }
                    }
                    (orders + pools).awaitAll()
                }
                if (mine == ticket) counts = counts + fresh.mapNotNull { (code, n) -> n?.let { code to it } }
            } finally { if (mine == ticket) { refreshing = false; loading = false } }
        }
    }

    fun onShown() { if (!loading) reload() }
}

private fun symbolOf(state: FaultState): ImageVector = when (state) {
    FaultState.Reported -> Icons.Filled.Inbox
    FaultState.Received -> Icons.Filled.PanTool
    FaultState.Signed -> Icons.Filled.Place
    FaultState.Repaired -> Icons.Filled.Build
    FaultState.Verified -> Icons.Filled.RateReview
    FaultState.Resolved -> Icons.Filled.Insights
    FaultState.Finished -> Icons.Filled.CheckCircle
    FaultState.Draft -> Icons.Filled.Checklist
}

/**
 * The home page: "所有工单" across the top, then one card per state with its number, two or three to a row by the width. A card
 * opens the list on that state. Every card is the same size and laid out the same way: badge top-left, number top-right, name under.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTab(vm: HomeViewModel, gridState: LazyGridState, pullState: PullToRefreshState, nested: Modifier, openList: (String) -> Unit, openPool: (PoolKind) -> Unit) {
    // The states the home shows, as on iOS (the "报修" draft state has its own place in the list's strip).
    val cards = listOf(FaultState.Reported, FaultState.Received, FaultState.Signed, FaultState.Repaired, FaultState.Verified, FaultState.Finished)
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pullState, indicator = {}, modifier = Modifier.fillMaxSize()) {
        PageWidth {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = nested.fillMaxSize().pullDown(pullState, vm.refreshing).elastic(top = false),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("维修工单") }
                item(span = { GridItemSpan(maxLineSpan) }) { AllOrdersCard { openList("") } }
                items(cards, key = { it.code }) { st ->
                    StateCard(st.title, symbolOf(st), stateColor(st.code), if (st.hasCount) vm.counts[st.code] else null) { openList(st.code) }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("保养工单") }
                item(key = "pool-maintain", span = { GridItemSpan(maxLineSpan) }) { WideCard("保养工单池", "等待接单的保养工单", Icons.Filled.OilBarrel, Color(0xFF26A69A), vm.counts[PoolKind.Maintenance.path]) { openPool(PoolKind.Maintenance) } }
                item(key = "pool-precise", span = { GridItemSpan(maxLineSpan) }) { WideCard("精密度检查工单池", "等待接单的精密度检查工单", Icons.Filled.Straighten, Color(0xFF5C6BC0), vm.counts[PoolKind.Precision.path]) { openPool(PoolKind.Precision) } }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** A soft round badge with the icon in the card's colour. */
@Composable
fun Badge(icon: ImageVector, tint: Color) {
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
}

val cardElevation @Composable get() = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp)

/** A number that rolls to its new value: the old one slides up and out while the new one slides in from below (nothing until it is known). */
@Composable
fun AnimatedCount(count: Int?, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Bold) {
    AnimatedContent(
        targetState = count, modifier = modifier, label = "count",
        transitionSpec = {
            if (initialState == null) fadeIn(tween(Motion.Normal, easing = Motion.Change)) togetherWith fadeOut(tween(Motion.Instant, easing = Motion.Change))
            else (slideInVertically(tween(Motion.Slow, easing = Motion.Change)) { it / 2 } + fadeIn(tween(Motion.Slow, easing = Motion.Change))) togetherWith (slideOutVertically(tween(Motion.Slow, easing = Motion.Change)) { -it / 2 } + fadeOut(tween(Motion.Fast, easing = Motion.Change)))
        },
    ) { value -> if (value == null) LoadingText("", style.copy(fontWeight = weight), color) else Text(value.toString(), style = style, fontWeight = weight, color = color) }
}

/** A full-width card: badge, title and a line under it, the number (or an arrow) on the right. */
@Composable
fun WideCard(title: String, subtitle: String, icon: ImageVector, tint: Color, count: Int?, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Badge(icon, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            AnimatedCount(count, MaterialTheme.typography.headlineSmall, tint)
        }
    }
}

@Composable
private fun AllOrdersCard(onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = cardElevation,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Badge(Icons.Filled.Checklist, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text("所有工单", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("查看全部状态的维修工单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StateCard(title: String, icon: ImageVector, tint: Color, count: Int?, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(104.dp), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = cardElevation,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Badge(icon, tint)
                if (title == "已完结") Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else AnimatedCount(count, MaterialTheme.typography.headlineSmall, tint)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
