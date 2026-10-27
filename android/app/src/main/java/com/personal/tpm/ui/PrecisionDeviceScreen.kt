package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import org.json.JSONArray

/** The web names the records parameter preciseId, but actually sends the execution orderId. */
@Composable
fun PrecisionDeviceScreen(assetId: String, onBack: () -> Unit) {
    val owner = LocalSession.current
    val queries = LocalQueryAccounts.current
    val scope by owner.scope.collectAsState()
    val expectedScope = scope
    val revision = queries.revision
    var page by remember { mutableIntStateOf(0) }
    var workOrders by remember(scope, revision) { mutableStateOf<List<Jv>>(emptyList()) }
    var total by remember(scope, revision) { mutableStateOf<Int?>(null) }
    var selectedPrecise by remember(scope, revision) { mutableStateOf("") }
    var executions by remember(scope, revision) { mutableStateOf<List<Jv>>(emptyList()) }
    var selectedExecution by remember(scope, revision) { mutableStateOf("") }
    var records by remember(scope, revision) { mutableStateOf<List<Jv>>(emptyList()) }
    var loadingPage by remember(scope, revision) { mutableStateOf(false) }
    var loadingOrders by remember(scope, revision) { mutableStateOf(false) }
    var loadingRecords by remember(scope, revision) { mutableStateOf(false) }
    var pageError by remember(scope, revision) { mutableStateOf("") }
    var detailError by remember(scope, revision) { mutableStateOf("") }
    var retry by remember { mutableIntStateOf(0) }
    fun checkScope() { if (expectedScope != owner.scope.value || revision != queries.revision) throw CancellationException("query scope changed") }
    LaunchedEffect(assetId, page, scope, revision, retry) {
        loadingPage = true; pageError = ""; workOrders = emptyList(); selectedPrecise = ""; selectedExecution = ""; executions = emptyList(); records = emptyList()
        try {
            require((assetId.toLongOrNull() ?: 0) > 0) { "设备ID无效" }
            val source = queries.source(owner)
            val response = source.page("precise/pagePrecise", offset = page * 20, limit = 20, filters = listOf(filter("asset.assetId", assetId)))
            checkScope()
            require(response["content"].raw is JSONArray) { "检查工单列表格式异常" }
            val rows = response["content"].array
            require(rows.all { it["asset"]["assetId"].text == assetId && it["preciseId"].text.isNotEmpty() }) { "返回了不匹配设备的工单" }
            total = serverPageCount(response) ?: error("服务器没有返回工单总数")
            workOrders = rows; selectedPrecise = rows.firstOrNull()?.get("preciseId")?.text.orEmpty()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { pageError = e.message ?: "读取工单失败" }
        finally { loadingPage = false }
    }
    LaunchedEffect(selectedPrecise, scope, revision) {
        executions = emptyList(); records = emptyList(); selectedExecution = ""; detailError = ""
        if (selectedPrecise.isNotEmpty()) {
            loadingOrders = true
            try {
                val response = queries.source(owner).request("precise/getPreciseOrdersByPreciseId", query = mapOf("preciseId" to selectedPrecise))
                checkScope(); require(response.raw is JSONArray) { "检查执行记录格式异常" }
                val rows = response.array.sortedByDescending { it["endTime"].dateText.ifEmpty { it["createTime"].dateText } }
                require(rows.all { (it["orderId"].text.toLongOrNull() ?: 0) > 0 }) { "检查执行记录缺少ID" }
                executions = rows; selectedExecution = rows.firstOrNull()?.get("orderId")?.text.orEmpty()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { detailError = e.message ?: "读取执行记录失败" }
            finally { loadingOrders = false }
        }
    }
    LaunchedEffect(selectedExecution, scope, revision) {
        records = emptyList()
        if (selectedExecution.isNotEmpty()) {
            loadingRecords = true; detailError = ""
            try {
                val response = queries.source(owner).request("precise/getPreciseRecordsByPreciseId", query = mapOf("preciseId" to selectedExecution))
                checkScope(); require(response.raw is JSONArray) { "逐项检测结果格式异常" }
                records = response.array.sortedBy { it["preciseItem"]["sortOrder"].number }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { detailError = e.message ?: "读取检测项目失败" }
            finally { loadingRecords = false }
        }
    }
    Scaffold(contentWindowInsets = appContentInsets(), topBar = { AppTopBar("逐项精度数据", onBack = onBack, loading = loadingPage || loadingOrders || loadingRecords) }) { padding ->
        PageWidth {
            ScrollableLazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    val asset = workOrders.firstOrNull()?.get("asset") ?: Jv.NULL
                    AnimatedLabel(asset["addressCode"].text.ifEmpty { asset["assetCode"].text.ifEmpty { "设备检测记录" } }, style = MaterialTheme.typography.titleLarge)
                    AnimatedLabel(asset["assetName"].text, style = MaterialTheme.typography.bodyMedium)
                    AnimatedLabel(asset["assetCode"].text, style = MaterialTheme.typography.bodySmall)
                    AnimatedLabel("查询：${queries.label(owner)} · ${owner.currentFactoryName}", style = MaterialTheme.typography.bodySmall)
                    if (pageError.isNotEmpty()) AnimatedLabel(pageError, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { retry++ }, enabled = !loadingPage) { Text("重新读取") }
                    if (!loadingPage && workOrders.isEmpty() && pageError.isEmpty()) Text("当前查询账号未返回此设备的检查工单，无法从汇总等级还原逐项数据。")
                    if (workOrders.size > 1) SectionTitle("选择检测工单")
                }
                workOrders.forEach { order ->
                    item(key = "precise-${order["preciseId"].text}") {
                        if (workOrders.size == 1) AnimatedLabel("工单：${order["sn"].text}", style = MaterialTheme.typography.bodySmall)
                        else FilterChip(selected = selectedPrecise == order["preciseId"].text, onClick = { selectedPrecise = order["preciseId"].text },
                            label = { AnimatedLabel("${order["planStartDate"].dateText}\n${order["sn"].text}") })
                    }
                }
                item {
                    if ((total ?: 0) > 20) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(enabled = page > 0 && !loadingPage, onClick = { page-- }) { Text("上一页") }
                        AnimatedLabel("${page + 1}页 · 共${total ?: "—"}单", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = total != null && (page + 1) * 20 < total!! && !loadingPage, onClick = { page++ }) { Text("下一页") }
                    }
                    executions.forEach { execution ->
                        if (executions.size == 1) AnimatedLabel("检测时间：${execution["endTime"].dateText.ifEmpty { execution["createTime"].dateText }}\n检测人：${execution["user"].label.ifEmpty { "未记录" }}", style = MaterialTheme.typography.bodyMedium)
                        else FilterChip(selected = selectedExecution == execution["orderId"].text, onClick = { selectedExecution = execution["orderId"].text },
                            label = { AnimatedLabel("${execution["endTime"].dateText.ifEmpty { execution["createTime"].dateText }} · ${execution["user"].label}") })
                    }
                    if (!loadingOrders && selectedPrecise.isNotEmpty() && executions.isEmpty() && detailError.isEmpty()) Text("该工单暂无检查执行记录。")
                    if (detailError.isNotEmpty()) AnimatedLabel(detailError, color = MaterialTheme.colorScheme.error)
                }
                records.forEachIndexed { index, record ->
                    item(key = "record-${record["recordId"].text}-$index") {
                        PrecisionPanel {
                            val name = record["itemName"].text.ifEmpty { record["preciseItem"]["itemName"].text }
                            AnimatedLabel(name.replace(Regex("\\s+"), " ").trim(), style = MaterialTheme.typography.titleSmall)
                            AnimatedLabel("精度判定：${record["preciseResult"].text.ifEmpty { "未记录" }}", style = MaterialTheme.typography.bodyMedium)
                            AnimatedLabel("记录值：${record["description"].text.ifEmpty { "未记录" }}", style = MaterialTheme.typography.bodyMedium)
                            if (record["executeResult"].text != "EXECUTED") AnimatedLabel(when (record["executeResult"].text) { "IGNORE" -> "已忽略"; "NOT" -> "未执行"; else -> "状态：${record["executeResult"].text.ifEmpty { "未知" }}" }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
