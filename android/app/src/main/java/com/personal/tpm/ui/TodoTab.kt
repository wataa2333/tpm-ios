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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.Session
import com.personal.tpm.data.batchItem
import com.personal.tpm.data.filter
import com.personal.tpm.data.serverPageCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/** The signed-in account's open tasks (`process/pageUserTodoTask`). Its total is the badge on the tab. */
class TodoViewModel(private val session: Session) : ViewModel() {
    var skeletonTypes by mutableStateOf(listOf(CardType.TodoFault, CardType.TodoErp, CardType.TodoFault, CardType.TodoErp)); private set
    var query by mutableStateOf("") ; private set
    var rows by mutableStateOf<List<Jv>>(emptyList()); private set
    var last by mutableStateOf(true); private set
    var total by mutableStateOf<Int?>(null); private set
    /** The number of open tasks with no search applied: what the tab badge shows. */
    var badge by mutableStateOf(0); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    /** Selecting several tasks to handle them at once. */
    val batch = BatchSelection()

    /** Which cards are open (by key). Kept here, not in each card, so the cards are plain and cheap to scroll, and the open ones stay open. */
    val expanded = mutableStateMapOf<String, Boolean>()
    private var ticket = 0

    init { reload() }
    init { viewModelScope.launch { session.changed.drop(1).collect { reload() } } }

    fun search(text: String) { val trimmed = text.trim(); if (trimmed != query) { query = trimmed; reload() } }

    private fun filters() = if (query.isEmpty()) emptyList() else listOf(filter("processInstanceName", query, "like"))

    fun reload(pull: Boolean = false) {
        val mine = ++ticket
        val snapshot = query
        if (rows.isNotEmpty()) skeletonTypes = rows.take(4).map { todoCardModel(it).cardType }
        loading = true; refreshing = pull; error = null
        viewModelScope.launch {
            try {
                val page = session.page("process/pageUserTodoTask", 0, filters())
                if (mine != ticket || snapshot != query) return@launch
                rows = page["content"].array; last = page["last"].bool; total = serverPageCount(page)
                if (query.isEmpty()) total?.let { badge = it }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) { loading = false; refreshing = false } }
        }
    }

    fun loadMore() {
        if (loading || loadingMore || last) return
        val mine = ticket
        val snapshot = query
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = session.page("process/pageUserTodoTask", rows.size, filters())
                if (mine != ticket || snapshot != query) return@launch
                rows = rows + page["content"].array; last = page["last"].bool; total = serverPageCount(page) ?: total
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { loadingMore = false }
        }
    }
}

/** 我的待办: the tasks waiting for this account, as the same cards as the order lists. A card opens the order of its task. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoTab(vm: TodoViewModel, listState: LazyListState, pullState: PullToRefreshState, nested: Modifier, openOrder: (OrderKind, String) -> Unit) {
    AutoLoadMore(listState, vm.rows.size, !vm.loading && !vm.last && vm.error == null) { vm.loadMore() }
    val focus = LocalFocusManager.current
    var input by rememberSaveable { mutableStateOf(vm.query) }
    LaunchedEffect(input) { if (input.trim() != vm.query) { delay(350); vm.search(input) } }
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pullState, indicator = {}, modifier = Modifier.fillMaxSize()) {
        PageWidth {
            Box(nested.fillMaxSize()) {
                LazyColumn(
                    state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "search", contentType = "search") { TodoSearch(input, { input = it }, { vm.search(input); focus.clearFocus() }) }
                    if (vm.loading && vm.rows.isEmpty()) items(4, key = { "skeleton$it" }, contentType = { "skeleton" }) { index -> SkeletonCard(fadeItem(skeleton = true), vm.skeletonTypes[index % vm.skeletonTypes.size]) }
                    itemsIndexed(vm.rows, key = { index, task -> task["id"].text + "#" + index }, contentType = { _, _ -> "task" }) { _, task ->
                        val model = remember(task) { todoCardModel(task) }
                        val entry = remember(task) { batchItem(task) }
                        OrderCard(
                            model, vm.expanded[model.key] == true, { vm.expanded[model.key] = vm.expanded[model.key] != true }, fadeItem(),
                            onOpen = { model.kind?.let { OrderPreview.put(it.toString(), model.openId, task["data"]); openOrder(it, model.openId) } },
                            selecting = if (vm.batch.active) {
                                if (entry != null) CardSelecting(vm.batch.contains(entry), vm.batch.isSelectable(entry), { vm.batch.toggle(entry) }, { vm.batch.peeking = entry })
                                else CardSelecting(false, false, { vm.batch.notice = "这条待办暂不支持批量处理" }, { vm.batch.notice = "这条待办暂不支持批量处理" })
                            } else null,
                        )
                    }
                    item(contentType = "footer") {
                        when {
                            vm.error != null -> AnimatedLabel(vm.error ?: "", Modifier.fillMaxWidth().padding(16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                            !vm.loading && vm.rows.isEmpty() -> EmptyState("暂无待办")
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

@Composable
private fun TodoSearch(input: String, onInput: (String) -> Unit, onSearch: () -> Unit) {
    SearchField(input, onInput, "搜索待办名称", onSearch)
}
