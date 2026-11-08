package com.personal.tpm.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.Jv
import com.personal.tpm.data.Session
import com.personal.tpm.data.batchItem
import com.personal.tpm.data.filter
import com.personal.tpm.data.serverPageCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

/** One way of searching a list: what to call it and the field the server filters by (always "contains"). */
class SearchChoice(val label: String, val key: String)

/**
 * A plain paged list the server answers with `{content, last, totalElements}`: the waiting pools, the asset ledger, ERP outbound orders, ERP
 * stock. [baseFilters] are always sent; the search box adds one more on the field of the chosen [searches] entry.
 */
class PagedViewModel(
    private val session: Session, private val page: String, private var baseFilters: List<JSONObject>, val searches: List<SearchChoice>, private val independent: Boolean = false,
) : ViewModel() {
    var query by mutableStateOf(""); private set
    var choice by mutableStateOf(0); private set
    var rows by mutableStateOf<List<Jv>>(emptyList()); private set
    var last by mutableStateOf(true); private set
    var total by mutableStateOf<Int?>(null); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    val expanded = mutableStateMapOf<String, Boolean>()
    /** Selecting several orders to handle them at once (the ERP outbound list). */
    val batch = BatchSelection()
    private var ticket = 0

    init { reload() }
    init { viewModelScope.launch { session.changed.drop(1).collect { reload() } } }
    init { if (independent) viewModelScope.launch { session.queryAccounts?.changes?.drop(1)?.collect { rows = emptyList(); total = null; expanded.clear(); batch.end(); reload() } } }

    fun search(text: String) { val trimmed = text.trim(); if (trimmed != query) { query = trimmed; reload() } }
    fun choose(index: Int) { if (index != choice) { choice = index; if (query.isNotEmpty()) reload() } }
    fun changeFilters(filters: List<JSONObject>) {
        if (baseFilters.map { it.toString() } == filters.map { it.toString() }) return
        baseFilters = filters; rows = emptyList(); total = null; expanded.clear(); batch.end(); reload()
    }

    private fun filters() = baseFilters + if (query.isEmpty()) emptyList() else listOf(filter(searches[choice].key, query, "like"))

    fun reload(pull: Boolean = false) {
        val mine = ++ticket
        val snapshot = "$choice|$query"
        loading = true; loadingMore = false; refreshing = pull; error = null
        viewModelScope.launch {
            try {
                val answer = if (independent) session.readPage(page, 0, filters()) else session.page(page, 0, filters())
                if (mine != ticket || snapshot != "$choice|$query") return@launch
                rows = answer["content"].array; last = answer["last"].bool; total = serverPageCount(answer)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) { loading = false; refreshing = false } }
        }
    }

    fun loadMore() {
        if (loading || loadingMore || last) return
        val mine = ticket
        val snapshot = "$choice|$query"
        loadingMore = true
        viewModelScope.launch {
            try {
                val answer = if (independent) session.readPage(page, rows.size, filters()) else session.page(page, rows.size, filters())
                if (mine != ticket || snapshot != "$choice|$query") return@launch
                rows = rows + answer["content"].array; last = answer["last"].bool; total = serverPageCount(answer) ?: total
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) loadingMore = false }
        }
    }
}

/**
 * A paged list as cards, with the search box and the way of searching that fold away while the list scrolls. [open], when given, makes the card
 * open something; without it the card only expands to show what the server gives.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PagedScreen(
    vm: PagedViewModel, title: String, emptyText: String, modelOf: (Jv, Int) -> CardModel, onBack: () -> Unit, open: ((CardModel, Jv) -> Unit)? = null,
    onNew: (() -> Unit)? = null, batchKind: com.personal.tpm.data.OrderKind? = null, openNotice: String? = null, cardType: CardType = CardType.Fault,
    reportAsset: ((Jv) -> Unit)? = null, poolKind: PoolKind? = null, extraFilters: (@Composable () -> Unit)? = null,
) {
    val skeletonCount = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 80 + 2).coerceIn(4, 40)
    var noticeShown by remember { mutableStateOf(false) }
    fun selectedItem(row: Jv) = poolKind?.let { poolBatchItem(it, row) } ?: batchKind?.let { batchItem(it, row) }
    val batch = vm.batch
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val listState = rememberLazyListState()
    AutoLoadMore(listState, vm.rows.size, !vm.loading && !vm.last && vm.error == null) { vm.loadMore() }
    val scope = rememberCoroutineScope()
    val hide = remember { ScrollHideState() }
    val pull = rememberPullToRefreshState()
    var input by rememberSaveable { mutableStateOf(vm.query) }
    var menu by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(input) { if (input.trim() != vm.query) { delay(350); vm.search(input) } }
    LaunchedEffect(vm.error) {
        val message = vm.error ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(message, actionLabel = "重试", withDismissAction = true)
        vm.error = null
        if (result == SnackbarResult.ActionPerformed) vm.reload()
    }
    LaunchedEffect(batch.notice) { batch.notice?.let { snackbar.showSnackbar(it); batch.notice = null } }
    androidx.activity.compose.BackHandler(enabled = batch.active) { batch.end() }
    val filtersPagedScreen: @Composable () -> Unit = {
                        Column(Modifier.then(if (AppPreferences.liquidGlass) Modifier else Modifier.scrollHide(hide)).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchField(input, { input = it }, "搜索" + vm.searches[vm.choice].label, onSearch = { vm.search(input); focus.clearFocus() }, onChoose = if (vm.searches.size > 1) ({ menu = true }) else null)
                            if (vm.searches.size > 1) Box {
                                AppOptionSheet(expanded = menu, onDismissRequest = { menu = false }, title = "搜索方式") {
                                    vm.searches.forEachIndexed { index, option -> AppOptionItem(text = { AnimatedLabel(option.label) }, selected = index == vm.choice, onClick = { menu = false; vm.choose(index) }) }
                                }
                            }
                            extraFilters?.invoke()
                        }
    }
    GlassScaffold(contentWindowInsets = appContentInsets(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingOverlay = { if (onNew != null) NewButton(visible = !batch.active, label = "新建领料单", onClick = onNew) },
        bottomBar = { if (batchKind != null || poolKind != null) BatchBarHost(batch.active) { BatchBar(batch) { vm.rows.mapNotNull { selectedItem(it) } } } },
        topBar = {
            AppTopBar(
                title = title, subtitle = vm.total?.let { "共 $it 条" }, onBack = onBack,
                loading = vm.loading || vm.refreshing, pull = if (vm.refreshing) 0f else pull.distanceFraction,
                actions = {
                    if (batchKind != null || poolKind != null) SelectButton(batch.active) { if (batch.active) batch.end() else batch.begin() }
                    RefreshButton(spinning = vm.loading || vm.refreshing) {
                        scope.launch { launch { hide.show() }; listState.animateScrollToItem(0) }
                        vm.reload(pull = true)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding())) {
            RoundedSheet {
                PageWidth {
                    Column(Modifier.fillMaxSize()) {
                        if (!AppPreferences.liquidGlass) filtersPagedScreen()
                        PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pull, indicator = {}, modifier = Modifier.weight(1f).fillMaxWidth()) {
                            val dim by animateFloatAsState(if (vm.loading && vm.rows.isNotEmpty()) 0.45f else 1f, tween(Motion.Fast, easing = Motion.Change), label = "dim")
                            Box(Modifier.fillMaxSize().then(if (AppPreferences.liquidGlass) Modifier else Modifier.nestedScroll(hide.connection))) {
                                LazyColumn(
                                    state = listState, modifier = Modifier.fillMaxSize().then(if (dim < 1f) Modifier.graphicsLayer { alpha = dim } else Modifier),
                                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (onNew != null) 88.dp else 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    if (AppPreferences.liquidGlass) item(key = "glass-filters") { filtersPagedScreen() }
                                    if (vm.loading && vm.rows.isEmpty()) items(skeletonCount, key = { "skeleton$it" }, contentType = { "skeleton" }) { SkeletonCard(fadeItem(skeleton = true), cardType) }
                                    // The key is the position: a page that shifts under the list can repeat a row, and a repeated key crashes a lazy list (and building a card just for its key was slow).
                                    items(vm.rows.size, key = { "row#$it" }, contentType = { "card" }) { index ->
                                        val row = vm.rows[index]
                                        val model = remember(row) { modelOf(row, index) }
                                        val entry = remember(row) { selectedItem(row) }
                                        OrderCard(
                                            model, vm.expanded[model.key] == true,
                                            { val opening = vm.expanded[model.key] != true; vm.expanded[model.key] = opening; if (opening && openNotice != null) noticeShown = true },
                                            fadeItem(),
                                            onOpen = if (open != null && model.openId.isNotEmpty()) ({ open(model, row) }) else null,
                                            bottomAction = if(reportAsset!=null && row["assetId"].text.isNotBlank() && row["assetCode"].text.isNotBlank()) ({
                                                AssetReportButton(onClick={reportAsset(row)},modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                                            }) else null,
                                            selecting = if (batch.active && entry != null) CardSelecting(batch.contains(entry), batch.isSelectable(entry), { batch.toggle(entry) }, { batch.peeking = entry }) else null,
                                        )
                                    }
                                    item(contentType = "footer") {
                                        when {
                                            !vm.loading && vm.rows.isEmpty() && vm.error == null -> EmptyState(emptyText)
                                            // More rows are read only when the list is pulled up past its end (the loading animation of the top bar runs then).
                                            vm.rows.isNotEmpty() && !vm.last -> { LoadMoreFooter(vm.loadingMore) }
                                            vm.rows.isNotEmpty() -> AnimatedLabel(vm.total?.let { "共 $it 条" } ?: "已显示全部 ${vm.rows.size} 条", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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
    if (noticeShown) GlassAlertDialog(
        onDismissRequest = { noticeShown = false },
        title = { Text("提示") }, text = { AnimatedLabel(openNotice.orEmpty()) },
        confirmButton = { GlassTextButton(onClick = { noticeShown = false }) { Text("确认") } },
    )
}

// ---- what the workbench lists show ----

private val assetFields = listOf(
    "assetCode" to "资产编号", "assetName" to "资产名称", "model" to "规格型号", "addressCode" to "机台号", "address" to "地址", "dept.deptName" to "使用部门",
    "user.realName" to "责任人", "assetCategory.categoryName" to "资产类别", "useState" to "使用状态", "manufacturer" to "制造商", "oldAssetCode" to "旧资产编号",
)
private val useStates = mapOf("IN_FACTORY" to "入厂", "IN_USE" to "在用", "UN_USE" to "闲置", "SCRAP" to "待报废", "SELLOFF" to "已报废", "DISABLED" to "禁用", "LEASE" to "出租", "OTHER" to "其它")

fun assetCardModel(row: Jv, index: Int) = CardModel(
    key = row["assetId"].text.ifEmpty { row["assetCode"].text + "#$index" }, title = row["assetName"].text,
    status = useStates[row["useState"].text] ?: "", colorCode = "", isApproval = false,
    reference = row["assetCode"].text.ifEmpty { "—" } + row["addressCode"].text.let { if (it.isEmpty()) "" else " · $it" }, time = "",
    facts = assetFields.map { (key, label) -> label to row.at(key).let { v -> if (key == "useState") (useStates[v.text] ?: v.text) else v.dateText } }.filter { it.second.isNotEmpty() },
    kind = null, openId = "", cardType = CardType.Asset,
)

fun stockCardModel(row: Jv, index: Int): CardModel {
    val unit = com.personal.tpm.data.materialUnit(row)
    return CardModel(
        key = row["id"].text.ifEmpty { row["materialCode"].text + "#$index" }, title = row["materialDesc"].text,
        status = "库存 " + row["onhandQty"].text + " " + unit, colorCode = "", isApproval = false,
        reference = row["materialCode"].text.ifEmpty { "—" },
        // The time at the foot of the card is the record's update time, as in the original app.
        time = row["updateTime"].dateText.let { if (it.isEmpty()) "" else "入库 $it" },
        summary = listOf(
            "ERP库存组织" to row["erpTpmCompany"]["organizationName"].text, "批次" to row["lot"].text, "子库" to row["subInv"].text,
            "货位" to row["locatorCode"].text, "货位描述" to row["locatorDesc"].text,
        ).filter { it.second.isNotEmpty() },
        facts = emptyList(),
        kind = null, openId = "", cardType = CardType.Stock,
    )
}
