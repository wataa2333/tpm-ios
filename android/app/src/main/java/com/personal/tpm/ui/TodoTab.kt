package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import com.personal.tpm.ui.ScrollableLazyColumn as LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.CompletionDay
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.Session
import com.personal.tpm.data.batchItem
import com.personal.tpm.data.completedOrdersReader
import com.personal.tpm.data.completionDay
import com.personal.tpm.data.countCompletedOrders
import com.personal.tpm.data.scanTodoTasks
import com.personal.tpm.data.searchTodoTasks
import com.personal.tpm.data.SearchCondition
import com.personal.tpm.data.filterTaskConditions
import com.personal.tpm.data.serverPageCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.Duration
import java.time.Instant

/** The signed-in account's open tasks (`process/pageUserTodoTask`). Its total is the badge on the tab. */
class TodoViewModel(private val session: Session, autoStart: Boolean = true) : ViewModel() {
    private var started = autoStart
    fun ensureStarted() { if (!started) { started = true; reload() } }
    var skeletonTypes by mutableStateOf(listOf(CardType.TodoFault, CardType.TodoErp, CardType.TodoFault, CardType.TodoErp)); private set
    var query by mutableStateOf("") ; private set
    var conditions by mutableStateOf<List<SearchCondition>>(emptyList()); private set
    val searching: Boolean get() = query.isNotEmpty() || conditions.isNotEmpty()
    internal var cardResults by mutableStateOf(OrderCardResults()); private set
    val rows: List<Jv> get() = cardResults.rows
    val last: Boolean get() = cardResults.lastPage
    var total by mutableStateOf<Int?>(null); private set
    /** The number of open tasks with no search applied: what the tab badge shows. */
    var badge by mutableStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var completedToday by mutableStateOf<Int?>(null); private set
    var completedTodayFailed by mutableStateOf(false); private set
    /** Selecting several tasks to handle them at once. */
    val batch = BatchSelection(allowReports = true)

    /** Which cards are open (by key). Kept here, not in each card, so the cards are plain and cheap to scroll, and the open ones stay open. */
    val expanded = mutableStateMapOf<String, Boolean>()
    private var ticket = 0
    private var loadJob: Job? = null
    private var searchSnapshot: Deferred<List<Jv>>? = null
    private var searchScope: Int? = null
    private var completedDay: CompletionDay? = null
    private var completedJob: Job? = null

    init { if (started) reload() }
    init { viewModelScope.launch { session.changed.drop(1).collect { if (started) reload() } } }
    init {
        viewModelScope.launch {
            snapshotFlow { AppPreferences.todoCompletionEnabled }.collect { enabled ->
                if (!enabled) {
                    completedJob?.cancel()
                    completedJob = null
                    completedDay = null
                    completedToday = null
                    completedTodayFailed = false
                }
            }
        }
    }

    fun search(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        if (!searching) clearSearchSnapshot()
        load()
    }

    fun changeConditions(value: List<SearchCondition>) {
        if (conditions == value) return
        conditions = value
        batch.clear()
        if (!searching) clearSearchSnapshot()
        cardResults = OrderCardResults()
        total = null
        load()
    }

    private fun clearSearchSnapshot() {
        searchSnapshot?.cancel()
        searchSnapshot = null
        searchScope = null
    }

    private fun searchableTasks(scope: Int): Deferred<List<Jv>> {
        if (searchScope != scope || searchSnapshot?.isCancelled == true) clearSearchSnapshot()
        searchSnapshot?.let { return it }
        searchScope = scope
        return viewModelScope.async {
            val tasks = mutableListOf<Jv>()
            scanTodoTasks(fetch = { path, offset, limit ->
                if (scope != session.scope.value) throw CancellationException("todo scope changed")
                session.page(path, offset, limit = limit).also {
                    check(it["content"].raw is JSONArray) { "待办搜索结果格式异常，请刷新重试" }
                }
            }) { task, firstOccurrence -> if (firstOccurrence) tasks.add(task) }
            tasks
        }.also { searchSnapshot = it }
    }

    fun reload(pull: Boolean = false) {
        clearSearchSnapshot()
        load(pull)
        if (completedDay != null) refreshCompletedToday(force = true)
    }

    fun refreshCompletedToday(force: Boolean = false) {
        if (!AppPreferences.todoCompletionEnabled || !session.signedIn) return
        val day = completionDay()
        if (!force && day == completedDay && completedJob?.isActive == true) return
        if (day != completedDay) completedToday = null
        completedDay = day
        completedTodayFailed = false
        completedJob?.cancel()
        completedJob = viewModelScope.launch {
            val scope = session.scope.value
            val reader = session.completedOrdersReader()
            try {
                val count = withContext(Dispatchers.Default) { countCompletedOrders(reader, day) }
                if (AppPreferences.todoCompletionEnabled && scope == session.scope.value && day == completedDay) completedToday = count
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (AppPreferences.todoCompletionEnabled && scope == session.scope.value && day == completedDay) completedTodayFailed = true
            }
        }
    }

    private fun load(pull: Boolean = false) {
        started = true
        val mine = ++ticket
        val words = query
        val filters = conditions
        val snapshot = "$words|$filters"
        val scope = session.scope.value
        loadJob?.cancel()
        if (rows.isNotEmpty()) skeletonTypes = rows.take(4).map { todoCardModel(it).cardType }
        loading = true; loadingMore = false; refreshing = pull; error = null
        loadJob = viewModelScope.launch {
            try {
                if (words.isEmpty() && filters.isEmpty()) {
                    val page = session.page("process/pageUserTodoTask")
                    if (mine != ticket || scope != session.scope.value) return@launch
                    cardResults = cardResults.replace(page["content"].array, snapshot, page["last"].bool)
                    total = serverPageCount(page)
                    total?.let { badge = it }
                } else {
                    val tasks = searchableTasks(scope).await()
                    val matches = withContext(Dispatchers.Default) {
                        session.filterTaskConditions(searchTodoTasks(tasks, words), filters)
                    }
                    if (mine != ticket || scope != session.scope.value) return@launch
                    cardResults = cardResults.replace(matches, snapshot, lastPage = true)
                    total = matches.size
                    badge = tasks.size
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) { loading = false; refreshing = false } }
        }
    }

    fun loadMore() {
        if (loading || loadingMore || last || searching) return
        val mine = ticket
        val snapshot = query
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = session.page("process/pageUserTodoTask", rows.size)
                if (mine != ticket || snapshot != query) return@launch
                cardResults = cardResults.append(page["content"].array, page["last"].bool)
                total = serverPageCount(page) ?: total
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) loadingMore = false }
        }
    }
}

/** 我的待办: the tasks waiting for this account, as the same cards as the order lists. A card opens the order of its task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoTab(
    vm: TodoViewModel, listState: LazyListState, pullState: PullToRefreshState, nested: Modifier,
    openOrder: (OrderKind, String) -> Unit, openCompletedOrders: () -> Unit,
    openPoolOrder: (PoolKind, String) -> Unit = { _, _ -> },
    openAssetReports: ((String) -> Unit)? = null,
    reportAsset: ((Jv) -> Unit)? = null,
) {
    val skeletonCount = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 80 + 2).coerceIn(4, 40)
    val cards = rememberDisplayedOrderCards(vm.cardResults, listState, "id")
    AutoLoadMore(listState, vm.rows.size, !cards.updating && !vm.loading && !vm.last && vm.error == null) { vm.loadMore() }
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val showCompletedOrders = AppPreferences.todoCompletionEnabled
    var input by rememberSaveable { mutableStateOf(vm.query) }
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(input) { if (input.trim() != vm.query) { delay(350); vm.search(input) } }
    LaunchedEffect(vm.conditions) { listState.scrollToItem(0) }
    LaunchedEffect(vm, lifecycle, showCompletedOrders) {
        if (!showCompletedOrders) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.refreshCompletedToday()
            while (true) {
                val now = Instant.now()
                delay(Duration.between(now, completionDay(now).end).toMillis().coerceAtLeast(1L))
                vm.refreshCompletedToday()
            }
        }
    }
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pullState, indicator = {}, modifier = Modifier.fillMaxSize()) {
        PageWidth {
            Box(nested.fillMaxSize()) {
                LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (showCompletedOrders) {
                        item(key = "completed-today") {
                            TodayCompletedOrdersCard(vm.completedToday, vm.completedTodayFailed, openCompletedOrders)
                        }
                    }
                    item(key = "search", contentType = "search") {
                        SearchField(input, { input = it }, "搜名称、单号、机台号、资产编码", onSearch = {
                            vm.search(input)
                            focus.clearFocus()
                        }, modifier = Modifier.onFocusChanged { searchFocused = it.hasFocus })
                        SearchConditionsBar(taskSearchChoices, vm.conditions, vm::changeConditions, searching = searchFocused || input.isNotBlank())
                    }
                    if (vm.loading && cards.rows.isEmpty()) items(skeletonCount, key = { "skeleton$it" }, contentType = { "skeleton" }) { index -> SkeletonCard(fadeItem(skeleton = true), vm.skeletonTypes[index % vm.skeletonTypes.size]) }
                    items(cards.rows, key = { it.key }, contentType = { "task" }) { card ->
                        val task = card.row
                        val model = remember(task) { todoCardModel(task) }
                        val entry = remember(task) { batchItem(task) }
                        val asset = task.at("data.asset")
                        val assetCode = asset["assetCode"].text
                        OrderCard(
                            model, vm.expanded[model.key] == true, { vm.expanded[model.key] = vm.expanded[model.key] != true }, fadeItem().then(cards.exitMotion(card.key)),
                            onOpen = { val pool = poolKindForTask(task); if (pool != null) openPoolOrder(pool, model.openId) else model.kind?.let { OrderPreview.put(it.toString(), model.openId, task["data"]); openOrder(it, model.openId) } },
                            selecting = if (vm.batch.active) {
                                if (entry != null) CardSelecting(vm.batch.contains(entry), vm.batch.isSelectable(entry), { vm.batch.toggle(entry) }, { vm.batch.peeking = entry })
                                else CardSelecting(false, false, { vm.batch.notice = "这条待办暂不支持批量处理" }, { vm.batch.notice = "这条待办暂不支持批量处理" })
                            } else null,
                            bottomAction = if (model.kind == OrderKind.Fault && assetCode.isNotBlank()) ({
                                AssetReportActions(
                                    onHistory = openAssetReports?.let { action -> { action(assetCode) } },
                                    onReport = reportAsset?.let { action -> { action(asset) } },
                                )
                            }) else null,
                        )
                    }
                    item(contentType = "footer") {
                        when {
                            vm.error != null -> AnimatedLabel(vm.error ?: "", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            !vm.loading && cards.rows.isEmpty() -> EmptyState(if (vm.searching) "没有找到匹配的待办" else "暂无待办")
                            vm.rows.isNotEmpty() && !vm.last -> { LoadMoreFooter(vm.loadingMore) }
                            vm.rows.isNotEmpty() -> Text(
                                vm.total?.let { "共 $it 个待办" } ?: "已显示全部 ${vm.rows.size} 条",
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
