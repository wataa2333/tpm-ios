package com.personal.tpm.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.DateSpan
import com.personal.tpm.data.FaultSearchField
import com.personal.tpm.data.FaultState
import com.personal.tpm.data.Jv
import com.personal.tpm.data.Session
import com.personal.tpm.data.SearchCondition
import com.personal.tpm.data.faultPage
import com.personal.tpm.data.faultSearchFilters
import com.personal.tpm.data.filter
import com.personal.tpm.data.serverPageCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * One repair-order list: its status, search, date range, the loaded rows and the numbers on the status strip. It lives as long
 * as the page is on the back stack, so opening an order and coming back finds the list, the scroll position and the
 * expanded cards exactly as they were, without a reload.
 */
class OrderListViewModel(
    private val session: Session,
    initialState: String,
    initialQuery: String,
    initialField: FaultSearchField,
    val assetCode: String = "",
) : ViewModel() {
    /** A page opened from a quick query: every matching order whatever its status, so no status strip. */
    val searchMode = initialQuery.isNotEmpty() || assetCode.isNotBlank()
    private val assetCondition = assetCode.takeIf(String::isNotBlank)?.let {
        SearchCondition(FaultSearchField.AssetCode.key, it, exact = true)
    }
    private fun appliedConditions() = listOfNotNull(assetCondition) + conditions

    var state by mutableStateOf(initialState); private set
    var query by mutableStateOf(initialQuery); private set
    var field by mutableStateOf(initialField); private set
    var span by mutableStateOf<DateSpan?>(null); private set
    var conditions by mutableStateOf<List<SearchCondition>>(emptyList()); private set

    internal var cardResults by mutableStateOf(OrderCardResults()); private set
    val rows: List<Jv> get() = cardResults.rows
    val last: Boolean get() = cardResults.lastPage
    var total by mutableStateOf<Int?>(null); private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var loading by mutableStateOf(true); private set
    var refreshing by mutableStateOf(false); private set
    var loadingMore by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)

    /** Selecting several orders to handle them at once. */
    val batch = BatchSelection(allowReports = true)

    /** Which cards are open (by key). Kept here, not in each card, so the cards are plain and cheap to scroll, and the open ones stay open. */
    val expanded = mutableStateMapOf<String, Boolean>()
    private var ticket = 0
    private var countsTicket = 0

    init { reload(withCounts = true) }
    init { viewModelScope.launch { session.changed.drop(1).collect { reload(withCounts = true) } } }
    init { if (searchMode) viewModelScope.launch { session.queryAccounts?.changes?.drop(1)?.collect { cardResults = OrderCardResults(); counts = emptyMap(); batch.end(); reload(withCounts = true) } } }

    private fun key() = "$state|$query|${field.key}|${span?.key}|${session.queryRevision}|$conditions"

    /** Another status: the old rows go at once (the page slides to the new status and shows grey cards while it loads). */
    fun select(code: String) { if (code != state) { state = code; cardResults = OrderCardResults(); total = null; reload(withCounts = false) } }
    fun search(text: String) { val trimmed = text.trim(); if (trimmed != query) { query = trimmed; reload(withCounts = true) } }
    fun changeField(value: FaultSearchField) { if (value != field) { field = value; if (query.isNotEmpty()) reload(withCounts = true) } }
    fun changeSpan(value: DateSpan?) { span = value; reload(withCounts = true) }
    fun changeConditions(value: List<SearchCondition>) {
        if (conditions == value) return
        conditions = value
        cardResults = OrderCardResults()
        total = null
        counts = emptyMap()
        batch.clear()
        reload(withCounts = true)
    }

    /** [pull]: the system pull-to-refresh is showing its own spinner. */
    fun reload(withCounts: Boolean = true, pull: Boolean = false) {
        val mine = ++ticket
        val snapshot = key()
        loading = true; loadingMore = false; refreshing = pull; error = null
        viewModelScope.launch {
            try {
                val page = session.faultPage(state, query, field, span, 0, independent = searchMode, conditions = appliedConditions())
                if (mine != ticket || snapshot != key()) return@launch
                cardResults = cardResults.replace(page.rows, snapshot, page.last)
                total = page.total
                page.total?.let { total -> counts = counts + (state to total) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) { loading = false; refreshing = false } }
        }
        if (withCounts) reloadCounts()
    }

    fun loadMore() {
        if (loading || loadingMore || last) return
        val mine = ticket
        val snapshot = key()
        if (!cardResults.matchesQuery(snapshot)) return
        loadingMore = true
        viewModelScope.launch {
            try {
                val page = session.faultPage(state, query, field, span, rows.size, independent = searchMode, conditions = appliedConditions())
                if (mine != ticket || snapshot != key()) return@launch
                cardResults = cardResults.append(page.rows, page.last)
                total = page.total ?: total
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (mine == ticket) error = e.message ?: "加载失败" }
            finally { if (mine == ticket) loadingMore = false }
        }
    }

    /** The other states' numbers for the strip, asked in parallel, one row each (only the total is used). */
    private fun reloadCounts() {
        if (searchMode) return
        val mine = ++countsTicket
        val filters = faultSearchFilters(query, field, span, conditions)
        viewModelScope.launch {
            val fresh = try {
                coroutineScope {
                    FaultState.entries.filter { it.hasCount && it.code != state }.map { st ->
                        async {
                            try { st.code to serverPageCount(session.page("fault/pageAssetFault", filters = filters + filter("faultState", st.code), limit = 1)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { st.code to null }
                        }
                    }.awaitAll()
                }
            } catch (e: CancellationException) { throw e }
            if (mine == countsTicket) counts = counts + fresh.mapNotNull { (code, n) -> n?.let { code to it } }
        }
    }
}
