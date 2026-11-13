package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.personal.tpm.data.CompletedOrder
import com.personal.tpm.data.SearchCondition
import com.personal.tpm.data.Session
import com.personal.tpm.data.completedOrdersReader
import com.personal.tpm.data.filterTaskConditions
import com.personal.tpm.data.searchTodoTasks
import com.personal.tpm.data.serverDate
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun TodayCompletedOrdersCard(count: Int?, failed: Boolean, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card(
        onClick = onOpen, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("今日已完成", style = MaterialTheme.typography.titleMedium)
                if (failed) Text("读取失败", style = MaterialTheme.typography.bodySmall, color = colors.error)
                else AnimatedCount(count, MaterialTheme.typography.headlineSmall, colors.primary)
            }
            Text("全部已完成", style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = colors.onSurfaceVariant)
        }
    }
}

internal class CompletedOrdersViewModel(private val session: Session) : ViewModel() {
    var query by mutableStateOf(""); private set
    var conditions by mutableStateOf<List<SearchCondition>>(emptyList()); private set
    val searching: Boolean get() = query.isNotEmpty() || conditions.isNotEmpty()
    var rows by mutableStateOf<List<CompletedOrder>>(emptyList()); private set
    var loading by mutableStateOf(true); private set
    var loadingMore by mutableStateOf(false); private set
    var refreshing by mutableStateOf(false); private set
    var last by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var reader = session.completedOrdersReader()
    private var job: Job? = null
    private var generation = 0

    init { reload() }

    fun search(text: String) {
        val trimmed = text.trim()
        if (trimmed == query) return
        query = trimmed
        rows = emptyList()
        reload()
    }

    fun changeConditions(value: List<SearchCondition>) {
        if (conditions == value) return
        conditions = value
        rows = emptyList()
        reload()
    }

    fun reload(pull: Boolean = false) {
        reader = session.completedOrdersReader()
        load(replace = true, pull = pull)
    }

    fun loadMore() {
        if (loading || loadingMore || last || error != null) return
        load(replace = false, pull = false)
    }

    private fun load(replace: Boolean, pull: Boolean) {
        val mine = ++generation
        val scope = session.scope.value
        val currentReader = reader
        val existing = if (replace) emptyList() else rows
        val words = query
        val filters = conditions
        job?.cancel()
        loading = replace; loadingMore = !replace; refreshing = pull; error = null
        job = viewModelScope.launch {
            try {
                val next = withContext(Dispatchers.Default) {
                    val merged = existing.associateByTo(linkedMapOf()) { it.key }
                    do {
                        val page = currentReader.next()
                        val matching = session.filterTaskConditions(searchTodoTasks(page.map { it.task }, words), filters).toHashSet()
                        page.filter { it.task in matching }.forEach { order ->
                            val previous = merged[order.key]
                            if (previous == null || previous.finishedAt < order.finishedAt) merged[order.key] = order
                        }
                    } while (merged.size == existing.size && !currentReader.last)
                    merged.values.sortedByDescending { it.finishedAt }
                }
                if (mine == generation && scope == session.scope.value) {
                    rows = next
                    last = currentReader.last
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == generation) error = e.message ?: "读取已完成工单失败" }
            finally { if (mine == generation) { loading = false; loadingMore = false; refreshing = false } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CompletedOrdersScreen(onBack: () -> Unit, openWorkflow: (String) -> Unit) {
    val session = LocalSession.current
    val vm = viewModel<CompletedOrdersViewModel>(factory = viewModelFactory { initializer { CompletedOrdersViewModel(session) } })
    val listState = rememberLazyListState()
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf(vm.query) }
    var searchFocused by remember { mutableStateOf(false) }
    LaunchedEffect(input) {
        if (input.trim() != vm.query) { delay(350); vm.search(input) }
    }
    AutoLoadMore(listState, vm.rows.size, !vm.loading && !vm.loadingMore && !vm.last && vm.error == null, vm::loadMore)
    GlassScaffold(
        contentWindowInsets = appContentInsets(), containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar("全部已完成", onBack = onBack, loading = vm.loading || vm.loadingMore, actions = {
                RefreshButton(spinning = vm.loading || vm.loadingMore) { vm.reload(pull = true) }
            })
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, indicator = {},
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            PageWidth {
                ScrollableLazyColumn(
                    modifier = Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "completed-search") {
                        SearchField(input, { input = it }, "搜名称、单号、机台号、资产编码", onSearch = {
                            vm.search(input)
                            focus.clearFocus()
                        }, modifier = Modifier.onFocusChanged { searchFocused = it.hasFocus })
                        SearchConditionsBar(taskSearchChoices, vm.conditions, vm::changeConditions, searching = searchFocused || input.isNotBlank())
                    }
                    if (vm.loading && vm.rows.isEmpty()) {
                        items(4, key = { "completed-skeleton-$it" }) { SkeletonCard(fadeItem(skeleton = true)) }
                    }
                    items(vm.rows, key = { it.key }, contentType = { "completed-order" }) { order ->
                        val model = remember(order) {
                            val card = todoCardModel(order.task)
                            card.copy(
                                key = order.key,
                                title = card.title.ifBlank { order.task["name"].text.ifBlank { "已完成工单" } },
                                status = "已完成", colorCode = "FINISHED", isApproval = false,
                                time = serverDate(Date.from(order.finishedAt)),
                                facts = card.facts.map { (label, value) ->
                                    (if (label == "当前节点") "处理节点" else label) to value
                                },
                            )
                        }
                        val process = order.task["processInstanceId"].text
                        OrderCard(model, expanded[order.key] == true, { expanded[order.key] = expanded[order.key] != true },
                            fadeItem(), onOpen = if (process.isBlank()) null else ({ openWorkflow(process) }))
                    }
                    item(key = "completed-footer") {
                        val error = vm.error
                        when {
                            error != null -> Column {
                                Text(error, color = MaterialTheme.colorScheme.error)
                                GlassTextButton(onClick = { vm.reload() }) { Text("重试") }
                            }
                            !vm.loading && vm.rows.isEmpty() -> EmptyState(if (vm.searching) "没有找到匹配的已完成工单" else "暂无已完成工单")
                            !vm.last && vm.rows.isNotEmpty() -> LoadMoreFooter(vm.loadingMore)
                        }
                    }
                }
            }
        }
    }
}
