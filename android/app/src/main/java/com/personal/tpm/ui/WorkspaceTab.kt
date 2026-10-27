package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PrecisionManufacturing
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.FaultSearchField
import com.personal.tpm.data.Jv
import com.personal.tpm.data.Session
import com.personal.tpm.data.serverPageCount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The lists the workbench opens (also the keys of its routes). */
enum class WorkList(val key: String, val title: String, val page: String) {
    Assets("assets", "资产台账", "asset/pageAsset"),
    Outbound("erp", "ERP 出库", "erpOutbound/pageErpOutbound"),
    Stock("stock", "ERP 库存", "erpOutbound/pageErpMaterial");

    fun base(): List<JSONObject> = if (this == Stock) listOf(Jv.obj("field" to "onhandQty", "operator" to "gt", "value" to 0)) else emptyList()
    fun searches(): List<SearchChoice> = when (this) {
        Assets -> listOf(SearchChoice("名称", "assetName"), SearchChoice("编码", "assetCode"))
        Outbound -> listOf(SearchChoice("出库单号", "outNumber"))
        Stock -> listOf(SearchChoice("物料名称", "materialDesc"), SearchChoice("料号", "materialCode"))
    }
    companion object { fun byKey(key: String): WorkList? = entries.firstOrNull { it.key == key } }
}

/** The numbers on the workbench cards: one request per card asking for a single row; only the total is used. */
class WorkspaceViewModel(private val session: Session) : ViewModel() {
    var counts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var refreshing by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    private var ticket = 0

    init { reload() }
    init { viewModelScope.launch { session.changed.drop(1).collect { reload() } } }
    init { viewModelScope.launch { session.queryAccounts?.changes?.drop(1)?.collect { counts = emptyMap(); reload() } } }

    fun reload(pull: Boolean = false) {
        val mine = ++ticket
        refreshing = pull; loading = true; error = null
        viewModelScope.launch {
            try {
                val fresh = coroutineScope {
                    WorkList.entries.map { list ->
                        async {
                            try { list.key to serverPageCount(session.readPage(list.page, filters = list.base(), limit = 1)) }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { if (mine == ticket) error = e.message ?: "查询数据读取失败"; list.key to null }
                        }
                    }.awaitAll()
                }
                if (mine == ticket) counts = counts + fresh.mapNotNull { (key, n) -> n?.let { key to it } }
            } finally { if (mine == ticket) { refreshing = false; loading = false } }
        }
    }
}

/** 工作台: the asset ledger, ERP outbound orders and stock, and the two quick queries for a repair order (by machine number, by asset code). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceTab(vm: WorkspaceViewModel, gridState: LazyGridState, pullState: PullToRefreshState, nested: Modifier, openList: (WorkList) -> Unit, openQuery: (FaultSearchField, String) -> Unit, openDashboard: () -> Unit, openPrecision: () -> Unit) {
    PullToRefreshBox(isRefreshing = vm.refreshing, onRefresh = { vm.reload(pull = true) }, state = pullState, indicator = {}, modifier = Modifier.fillMaxSize()) {
        PageWidth {
            LazyVerticalGrid(
                state = gridState, columns = GridCells.Adaptive(minSize = 150.dp), modifier = nested.fillMaxSize().pullDown(pullState, vm.refreshing).elastic(top = false),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                vm.error?.let { message -> item(span = { GridItemSpan(maxLineSpan) }) { Text(message, color = MaterialTheme.colorScheme.error) } }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("看板") }
                if (AppPreferences.management) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Card(onClick=openDashboard,modifier=Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.large,colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainerLowest)) {
                            Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Badge(Icons.Filled.SettingsSuggest,MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f)) { Text("人员与工单分析",style=MaterialTheme.typography.titleMedium); Text("查看维修待处理与人员趋势",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,contentDescription="进入",tint=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Card(onClick = openPrecision, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Badge(Icons.Filled.PrecisionManufacturing, MaterialTheme.colorScheme.primary)
                            Column(Modifier.weight(1f)) { Text("精密度检查看板", style = MaterialTheme.typography.titleMedium); Text("017CNC · 进度与品牌等级", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "进入", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("资产") }
                item(span = { GridItemSpan(maxLineSpan) }) { WideCard("资产台账", "全部资产", Icons.Filled.PrecisionManufacturing, Color(0xFF1E88E5), vm.counts[WorkList.Assets.key]) { openList(WorkList.Assets) } }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("ERP") }
                item { StateCard("ERP 出库", Icons.Filled.LocalShipping, Color(0xFFFB8C00), vm.counts[WorkList.Outbound.key]) { openList(WorkList.Outbound) } }
                item { StateCard("ERP 库存", Icons.Filled.Inventory2, Color(0xFF00897B), vm.counts[WorkList.Stock.key]) { openList(WorkList.Stock) } }
                item(span = { GridItemSpan(maxLineSpan) }) { SectionTitle("快速查询维修") }
                item(span = { GridItemSpan(maxLineSpan) }) { QuickQueryCard("按机台号查询", Icons.Filled.SettingsSuggest, Color(0xFF8E24AA), "输入机台号") { openQuery(FaultSearchField.Machine, it) } }
                item(span = { GridItemSpan(maxLineSpan) }) { QuickQueryCard("按资产编码查询", Icons.Filled.QrCode2, Color(0xFF26A69A), "输入编码的尾号或中间一段") { openQuery(FaultSearchField.AssetCode, it) } }
            }
        }
    }
}

/** A card with a text box: type, press the keyboard's search key (or the button) and the repair orders that match open as one list. */
@Composable
private fun QuickQueryCard(title: String, icon: ImageVector, tint: Color, placeholder: String, onSearch: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    fun go() { val typed = text.trim(); if (typed.isNotEmpty()) { text = ""; onSearch(typed) } }
    Card(
        Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Badge(icon, tint)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.TextField(colors = blockFieldColors(onCard = true), shape = MaterialTheme.shapes.medium, 
                    value = text, onValueChange = { text = it }, singleLine = true, placeholder = { Text(placeholder) }, modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { go() }),
                )
                FilledIconButton(onClick = { go() }, enabled = text.isNotBlank()) { Icon(Icons.Filled.Search, contentDescription = "查询") }
            }
        }
    }
}
