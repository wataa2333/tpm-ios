package com.personal.tpm.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.DateRangePicker
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.DateSpan
import com.personal.tpm.data.FaultSearchField
import com.personal.tpm.data.FaultState
import com.personal.tpm.data.batchItem
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderListScreen(vm: OrderListViewModel, title: String, onBack: () -> Unit, onOpen: (String) -> Unit, onNew: (() -> Unit)? = null, reportAsset: ((com.personal.tpm.data.Jv) -> Unit)? = null) {
    val focus = LocalFocusManager.current
    val batch = vm.batch
    var activeList by remember { mutableStateOf<androidx.compose.foundation.lazy.LazyListState?>(null) }
    val scope = rememberCoroutineScope()
    val hide = remember { ScrollHideState() }
    val pull = rememberPullToRefreshState()
    var input by rememberSaveable { mutableStateOf(vm.query) }
    var choosingDates by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Typing searches by itself a moment after the last key; a quick-query page searches only when confirmed (Search key).
    LaunchedEffect(input) {
        if (!vm.searchMode && input.trim() != vm.query) { delay(350); vm.search(input) }
    }
    // A failed load is said in a snackbar with a "重试" action, and the list keeps what it has.
    LaunchedEffect(vm.error) {
        val message = vm.error ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "重试", withDismissAction = true)
        vm.error = null
        if (result == SnackbarResult.ActionPerformed) vm.reload()
    }
    // Another status is another batch: the selection starts over. A notice (why a card cannot be selected) goes to the snackbar.
    LaunchedEffect(vm.state) { batch.clear() }
    LaunchedEffect(batch.notice) { batch.notice?.let { snackbar.showSnackbar(it); batch.notice = null } }
    androidx.activity.compose.BackHandler(enabled = batch.active) { batch.end() }
    /** The refresh button: brings the list back to the top, shows the search strip again, and runs the same refresh as pulling down. */
    fun refreshFromTop() {
        scope.launch { launch { hide.show() }; activeList?.animateScrollToItem(0) }
        vm.reload(pull = true)
    }

    val filtersOrderListScreen: @Composable () -> Unit = {
                        Column(Modifier.then(if (AppPreferences.liquidGlass) Modifier else Modifier.scrollHide(hide))) {
                            SearchRow(vm, input, { input = it }, focus)
                            if (!vm.searchMode) StatusStrip(vm)
                            vm.span?.let { span ->
                                BlockChip(
                                    "日期 " + span.summary, { choosingDates = true }, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), selected = true,
                                    trailing = { Icon(Icons.Filled.Close, contentDescription = "清除日期", Modifier.size(18.dp).clickable { vm.changeSpan(null) }) },
                                )
                            }
                        }
    }
    GlassScaffold(contentWindowInsets = appContentInsets(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingOverlay = { if (onNew != null) NewButton(visible = !batch.active, label = "新建报修", onClick = onNew) },
        bottomBar = { BatchBarHost(batch.active) { BatchBar(batch) { vm.rows.map { batchItem(com.personal.tpm.data.OrderKind.Fault, it) } } } },
        topBar = {
            AppTopBar(
                title = title, onBack = onBack,
                subtitle = vm.total?.let { "共 $it 单" },
                loading = vm.loading || vm.refreshing || vm.loadingMore, pull = if (vm.refreshing) 0f else pull.distanceFraction,
                actions = {
                    SelectButton(batch.active) { if (batch.active) batch.end() else batch.begin() }
                    RefreshButton(spinning = vm.loading || vm.refreshing) { refreshFromTop() }
                    GlassIconButton(onClick = { choosingDates = true }) { Icon(Icons.Filled.DateRange, contentDescription = "按日期筛选") }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding())) {
            RoundedSheet {
                PageWidth {
                    Column(Modifier.fillMaxSize()) {
                        // The search box, the way of searching, the status tabs and the date chip fold away while the list is
                        // scrolled towards its end and come back at the first scroll the other way.
                        if (!AppPreferences.liquidGlass) filtersOrderListScreen()
                        // Keep the filters and page identity stable when changing category.
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                        val stateCode = vm.state
                        val listState = rememberLazyListState()
                        LaunchedEffect(stateCode) { listState.scrollToItem(0) }
                        AutoLoadMore(listState, vm.rows.size, stateCode == vm.state && !vm.loading && !vm.last && vm.error == null) { vm.loadMore() }
                        if (stateCode == vm.state) SideEffect { activeList = listState }
                        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pull, indicator = {}, modifier = Modifier.fillMaxSize()) {
                            // While a search or a date is loading, the old rows stay but dim; they fade out as the new ones fade in.
                            val dim by animateFloatAsState(if (vm.loading && vm.rows.isNotEmpty()) 0.45f else 1f, tween(Motion.Fast, easing = Motion.Change), label = "dim")
                            Box(Modifier.fillMaxSize().then(if (AppPreferences.liquidGlass) Modifier else Modifier.nestedScroll(hide.connection))) {
                                LazyColumn(
                                    state = listState, modifier = Modifier.fillMaxSize().then(if (dim < 1f) Modifier.graphicsLayer { alpha = dim } else Modifier),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (onNew != null) 88.dp else 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (AppPreferences.liquidGlass) item(key = "glass-filters") { filtersOrderListScreen() }
                                    if (vm.loading && vm.rows.isEmpty()) {
                                        items(4, key = { "skeleton$it" }, contentType = { "skeleton" }) { SkeletonCard(fadeItem(skeleton = true)) }
                                    }
                                    // The key carries the position: a page that shifts under the list can repeat an order, and a repeated key crashes a lazy list.
                                    itemsIndexed(vm.rows, key = { index, row -> row["faultId"].text + "#" + index }, contentType = { _, _ -> "order" }) { _, item ->
                                        val model = remember(item) { faultCardModel(item) }
                                        val entry = remember(item) { batchItem(com.personal.tpm.data.OrderKind.Fault, item) }
                                        OrderCard(
                                            model, vm.expanded[model.key] == true, { vm.expanded[model.key] = vm.expanded[model.key] != true }, fadeItem(),
                                            onOpen = { OrderPreview.put("Fault", model.openId, item); onOpen(model.openId) },
                                            bottomAction = if(reportAsset!=null && item["asset"]["assetId"].text.isNotBlank() && item["asset"]["assetCode"].text.isNotBlank()) ({
                                                AssetReportButton(onClick={reportAsset(item["asset"])},modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                                            }) else null,
                                            selecting = if (batch.active) CardSelecting(batch.contains(entry), batch.isSelectable(entry), { batch.toggle(entry) }, { batch.peeking = entry }) else null,
                                        )
                                    }
                                    item(contentType = "footer") {
                                        when {
                                            !vm.loading && vm.rows.isEmpty() && vm.error == null -> EmptyState()
                                            // More pages are read only when the list is pulled up past its end (the loading animation of the top bar runs then).
                                            vm.rows.isNotEmpty() && !vm.last -> { Text("加载更多内容", Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                            vm.rows.isNotEmpty() -> Text(
                                                vm.total?.let { "共 $it 单" } ?: "已显示全部 ${vm.rows.size} 条",
                                                Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                            )
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
    }
    // The "+" button is laid over the screen, not placed by the Scaffold: the Scaffold put it above the bottom bar, so when the batch bar slid away
    // the button dropped with it. Here it stays where it is, and shows itself once the bar is on its way out.
    BatchOverlays(batch)
    if (choosingDates) DateRangeDialog(vm.span, onDismiss = { choosingDates = false }) { choosingDates = false; vm.changeSpan(it) }
}

@Composable
private fun SearchRow(vm: OrderListViewModel, input: String, onInput: (String) -> Unit, focus: FocusManager) {
    var fieldMenu by remember { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(
            value = input, onValueChange = onInput, singleLine = true, modifier = Modifier.fillMaxWidth().glassSearchSurface(),
            placeholder = { AnimatedLabel("搜索" + vm.field.label) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = { if (input.isNotEmpty()) GlassIconButton(onClick = { onInput(""); if (vm.searchMode) vm.search("") }) { Icon(Icons.Filled.Close, contentDescription = "清空") } },
            shape = CircleShape,
            colors = searchFieldColors(),
            // Return (the keyboard's search key) searches at once and puts the keyboard away.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { vm.search(input); focus.clearFocus() }),
        )
        Box {
            BlockChip("按" + vm.field.label + "搜索", { fieldMenu = true }, trailing = { Icon(Icons.Filled.ExpandMore, contentDescription = null, Modifier.size(18.dp)) })
            AppOptionSheet(expanded = fieldMenu, onDismissRequest = { fieldMenu = false }, title = "搜索方式") {
                FaultSearchField.entries.forEach { option ->
                    AppOptionItem(text = { AnimatedLabel(option.label) }, selected = option == vm.field, onClick = { fieldMenu = false; vm.changeField(option) })
                }
            }
        }
    }
}

/**
 * The eight states with their numbers as capsules in a row that scrolls sideways: the chosen one is filled with the theme colour, the others are
 * soft grey; the number always has the colour of its own word. The chosen capsule is brought into view when it is not.
 */
@Composable
private fun StatusStrip(vm: OrderListViewModel) {
    val states = FaultState.entries
    val selected = states.indexOfFirst { it.code == vm.state }
    val scroll = rememberScrollState()
    val lefts = remember { androidx.compose.runtime.mutableStateMapOf<Int, Int>() }
    LaunchedEffect(selected, lefts[selected]) {
        val left = lefts[selected] ?: return@LaunchedEffect
        scroll.animateScrollTo((left - 48).coerceAtLeast(0))
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).glassHorizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        states.forEachIndexed { index, st ->
            val on = index == selected
            val background by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh, tween(Motion.Normal, easing = Motion.Change), label = "capsule")
            val content by androidx.compose.animation.animateColorAsState(if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, tween(Motion.Normal, easing = Motion.Change), label = "capsuleText")
            if (AppPreferences.liquidGlass) GlassFilterChip(selected = on, onClick = { vm.select(st.code) },
                modifier = Modifier.onGloballyPositioned { lefts[index] = it.positionInParent().x.toInt() }, label = {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(st.title, style = MaterialTheme.typography.labelLarge)
                        if (st.hasCount) AnimatedCount(vm.counts[st.code], MaterialTheme.typography.labelLarge, MaterialTheme.colorScheme.onSurface)
                    }
                }) else Row(
                Modifier.onGloballyPositioned { lefts[index] = it.positionInParent().x.toInt() }.clip(CircleShape).background(background)
                    .clickable { vm.select(st.code) }.padding(horizontal = 14.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                AnimatedLabel(st.title, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = content, maxLines = 1, softWrap = false)
                if (st.hasCount) AnimatedCount(vm.counts[st.code], MaterialTheme.typography.labelLarge, content, weight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeDialog(current: DateSpan?, onDismiss: () -> Unit, onPick: (DateSpan?) -> Unit) {
    fun millis(date: java.time.LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = current?.let { millis(it.days.first) }, initialSelectedEndDateMillis = current?.let { millis(it.days.second) },
    )
    fun day(ms: Long?) = ms?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            GlassTextButton(
                enabled = state.selectedStartDateMillis != null,
                onClick = { val from = day(state.selectedStartDateMillis)!!; onPick(DateSpan(from, day(state.selectedEndDateMillis) ?: from)) },
            ) { Text("确定") }
        },
        dismissButton = { GlassTextButton(onClick = onDismiss) { Text("取消") } },
    ) { DateRangePicker(state, Modifier.height(500.dp), title = { Text("按故障时间筛选", Modifier.padding(start = 24.dp, top = 16.dp)) }) }
}
