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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
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
import com.personal.tpm.data.MaintenanceState
import com.personal.tpm.data.maintenanceStateFilters
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
    var error by mutableStateOf<String?>(null); private set
    private var started = false
    private var ticket = 0

    init { viewModelScope.launch { session.changed.drop(1).collect { if (started) reload() } } }

    fun reload(pull: Boolean = false) {
        started = true
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
                    val maintenance = MaintenanceState.home.filter { it.hasCount }.map { state ->
                        async {
                            try { state.countKey to serverPageCount(session.page(PoolKind.Maintenance.page, filters = maintenanceStateFilters(state.code), limit = 1)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { state.countKey to null }
                        }
                    }
                    val pools = listOf(PoolKind.Precision).map { kind ->
                        async {
                            try { kind.path to serverPageCount(session.page(kind.page, filters = kind.waitingFilters(), limit = 1)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { kind.path to null }
                        }
                    }
                    (orders + maintenance + pools).awaitAll()
                }
                if (mine == ticket) {
                    counts = fresh.mapNotNull { (code, n) -> n?.let { code to it } }.toMap()
                    error = if (fresh.any { it.second == null }) "部分工单数量读取失败，请下拉刷新重试" else null
                }
            } finally { if (mine == ticket) { refreshing = false; loading = false } }
        }
    }

    fun onShown() { if (!started || !loading) reload() }
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
fun HomeTab(vm: HomeViewModel, gridState: LazyGridState, pullState: PullToRefreshState, nested: Modifier, openList: (String) -> Unit, openPool: (PoolKind, String) -> Unit) {
    // The states the home shows, as on iOS (the "报修" draft state has its own place in the list's strip).
    val cards = listOf(FaultState.Reported, FaultState.Received, FaultState.Signed, FaultState.Repaired, FaultState.Verified, FaultState.Resolved, FaultState.Finished)
    val layout = rememberDashboardLayout("home", listOf("repair", "maintain", "precision"))
    CompositionLocalProvider(LocalHomeCountsLoading provides vm.loading) {
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pullState, indicator = {}, modifier = Modifier.fillMaxSize()) {
        PageWidth {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 150.dp),
                modifier = nested.fillMaxSize().scrollEdges(MaterialTheme.colorScheme.background, before = { gridState.canScrollBackward }, after = { gridState.canScrollForward }),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp + LocalGlassContentPadding.current.calculateTopPadding(), bottom = (if (AppPreferences.liquidGlass) 72.dp else 104.dp) + LocalGlassContentPadding.current.calculateBottomPadding()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                vm.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) } }
                layout.order.forEach { section ->
                item(key = "section-$section", span = { GridItemSpan(maxLineSpan) }) {
                DashboardSection(layout, section, when(section) { "repair" -> "维修工单"; "maintain" -> "保养工单"; else -> "精密度检查" }) {
                when (section) {
                "repair" -> {
                AllOrdersCard({ openList("") })
                DashboardTiles(cards.filter { it != FaultState.Finished }, { it.code }) { st ->
                    StateCard(st.title, symbolOf(st), stateColor(st.code), if (st.hasCount) vm.counts[st.code] else null) { openList(st.code) }
                }
                AllOrdersCard({ openList(FaultState.Finished.code) }, "已完结", "查看已完结的维修工单", symbolOf(FaultState.Finished), stateColor(FaultState.Finished.code))
                }
                "maintain" -> {
                AllOrdersCard({ openPool(PoolKind.Maintenance, "") }, "所有保养工单", "查看全部状态的保养工单", Icons.Filled.OilBarrel)
                DashboardTiles(MaintenanceState.home.filter { it != MaintenanceState.Finished }, { it.countKey }) { state ->
                    val icon = when (state) {
                        MaintenanceState.Reported -> Icons.Filled.Inbox; MaintenanceState.Received -> Icons.Filled.PanTool
                        MaintenanceState.Signed -> Icons.Filled.Place; MaintenanceState.Maintained -> Icons.Filled.OilBarrel
                        MaintenanceState.Verified -> Icons.Filled.RateReview; MaintenanceState.Resolved -> Icons.Filled.Insights
                        else -> Icons.Filled.CheckCircle
                    }
                    StateCard(state.title, icon, stateColor(state.code), vm.counts[state.countKey]) { openPool(PoolKind.Maintenance, state.code) }
                }
                AllOrdersCard({ openPool(PoolKind.Maintenance, MaintenanceState.Finished.code) }, "已完结", "查看已完结的保养工单", Icons.Filled.CheckCircle, stateColor(MaintenanceState.Finished.code))
                }
                "precision" -> {
                WideCard("精密度检查工单池", "等待接单的精密度检查工单", Icons.Filled.Straighten, Color(0xFF5C6BC0), vm.counts[PoolKind.Precision.path]) { openPool(PoolKind.Precision, "RECEIVED") }
                }
                }
                }
                }
                }
            }
        }
    }
    }
}

private val LocalHomeCountsLoading = compositionLocalOf<Boolean?> { null }

@Composable
fun SectionTitle(text: String) {
    AnimatedLabel(text, Modifier.padding(start = 4.dp, top = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
}

/** A soft round badge with the icon in the card's colour. */
@Composable
fun Badge(icon: ImageVector, tint: Color) {
    Box(Modifier.size(52.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)), Alignment.Center) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp)) }
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
    ) { value ->
        val loading = LocalHomeCountsLoading.current
        when {
            value != null -> AnimatedLabel(value.toString(), style = style, fontWeight = weight, color = color)
            loading == null -> LoadingText("", style.copy(fontWeight = weight), color)
            loading -> Box(Modifier.width(36.dp).height(22.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape))
            else -> Text("—", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
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
                AnimatedLabel(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                AnimatedLabel(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            AnimatedCount(count, MaterialTheme.typography.headlineSmall, tint)
        }
    }
}

@Composable
private fun AllOrdersCard(onClick: () -> Unit, title: String = "所有工单", subtitle: String = "查看全部状态的维修工单", icon: ImageVector = Icons.Filled.Checklist, tint: Color = MaterialTheme.colorScheme.primary) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = cardElevation,
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Badge(icon, tint)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun StateCard(title: String, icon: ImageVector, tint: Color, count: Int?, onClick: () -> Unit) {
    Card(
        onClick = onClick, modifier = Modifier.fillMaxWidth().height(132.dp), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest), elevation = cardElevation,
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Badge(icon, tint)
                if (title == "已完结") Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                else AnimatedCount(count, MaterialTheme.typography.headlineSmall, tint)
            }
            AnimatedLabel(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
