package com.personal.tpm.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Read contracts recovered from the original App; business writes remain permission gated. */
@Composable
fun PoolOrderDetail(kind: PoolKind, id: String, onClose: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<Jv?>(null) }
    var orders by remember(id) { mutableStateOf<List<Jv>>(emptyList()) }
    var records by remember(id) { mutableStateOf<List<Jv>>(emptyList()) }
    var selected by remember(id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var canOperate by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf<String?>(null) }
    val name = if (kind == PoolKind.Maintenance) "Maintain" else "Precise"
    suspend fun loadRecords(orderId: String) {
        records = session.request("${kind.path}/get${name}RecordsBy${name}Id", mapOf(kind.idKey to orderId)).array
        selected = orderId
    }
    suspend fun load() {
        busy = true; error = null; canOperate = false
        try {
            detail = loadPoolOrder(session, kind, id)
            val task = detail?.get("taskInfo") ?: Jv.NULL
            if (task["id"].text.isNotEmpty()) canOperate = session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text))["canSubmit"].bool
            orders = session.request("${kind.path}/get${name}OrdersBy${name}Id", mapOf(kind.idKey to id)).array
            val first = orders.firstOrNull { it["orderId"].text == selected } ?: orders.firstOrNull()
            records = emptyList()
            first?.get("orderId")?.text?.takeIf { it.isNotEmpty() }?.let { loadRecords(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取失败，请重试" }
        finally { busy = false }
    }
    LaunchedEffect(id, session.accountKey) { load() }
    Surface(Modifier.fillMaxSize()) {
        FormPage(title = if (kind == PoolKind.Maintenance) "保养工单详情" else "精度检查工单详情",
            busy = busy, onClose = onClose, bottomBar = {
                if (canOperate) GlassBottomSurface {
                    GlassActionButton(onClick = { action = if (detail?.get("taskInfo")?.get("category")?.text == if (kind == PoolKind.Maintenance) "MAINTAINED" else "PRECISED") "EDIT" else "FLOW" },
                        enabled = !busy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                        Text(detail?.get("taskInfo")?.get("name")?.text?.ifEmpty { "处理任务" } ?: "处理任务")
                    }
                }
            }, interceptBack = true,
            actions = { GlassIconButton(onClick = { if (!busy) scope.launch { load() } }) { Icon(Icons.Default.Refresh, "刷新") } }) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            detail?.let { row ->
                FormTitle("工单信息")
                GroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(row[kind.nameKey].text, style = MaterialTheme.typography.titleMedium)
                    poolFacts(row).forEach { (key, value) -> FactRow(key, value) }
                } }
            }
            FormTitle("执行记录")
            if (orders.isEmpty() && !busy) Text("暂无执行记录")
            orders.forEachIndexed { index, order ->
                GlassTextButton(enabled = !busy, onClick = { scope.launch {
                    busy = true; error = null
                    try { loadRecords(order["orderId"].text) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { records = emptyList(); error = e.message ?: "读取记录失败" }
                    finally { busy = false }
                } }) { Text((if (selected == order["orderId"].text) "✓ " else "") + "第 ${index + 1} 次执行 · " + order["startTime"].dateText) }
            }
            records.forEach { record -> GroupCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(record["itemName"].text.ifEmpty { record[kind.path + "Item"]["itemName"].text }, style = MaterialTheme.typography.titleMedium)
                    FactRow("执行状态", record["executeResult"].text.ifEmpty { "未记录" })
                    FactRow("检查结果", record[kind.path + "Result"].text.ifEmpty { "未记录" })
                    FactRow("记录值／说明", record["description"].text.ifEmpty { "未记录" })
                }
            } }
        }
    }
    SlideInOverlayFor(action) { page ->
        if (page == "EDIT") PoolExecutionForm(kind, id, onClose = { action = null; scope.launch { load() } }, onSubmitFlow = { action = "FLOW" })
        else TaskScreen(com.personal.tpm.data.OrderKind.Fault, id, false, onBack = { action = null },
            onDone = { action = null; scope.launch { load() } }, poolKind = kind)
    }
}
