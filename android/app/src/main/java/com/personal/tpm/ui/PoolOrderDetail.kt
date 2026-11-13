package com.personal.tpm.ui

import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.isReturnCondition
import com.personal.tpm.data.isRejectCondition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Read contracts recovered from the original App; business writes remain permission gated. */
@Composable
fun PoolOrderDetail(kind: PoolKind, id: String, readOnly: Boolean = false, onClose: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var detail by remember(id) { mutableStateOf<Jv?>(null) }
    var orders by remember(id) { mutableStateOf<List<Jv>>(emptyList()) }
    var records by remember(id) { mutableStateOf<List<Jv>>(emptyList()) }
    var materials by remember(id) { mutableStateOf<List<Jv>>(emptyList()) }
    var selected by remember(id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var canOperate by remember { mutableStateOf(false) }
    var returnBranch by remember { mutableStateOf(Jv.NULL) }
    var action by remember { mutableStateOf<String?>(null) }
    val name = if (kind == PoolKind.Maintenance) "Maintain" else "Precise"
    suspend fun loadRecords(orderId: String) {
        val nextRecords = session.request("${kind.path}/get${name}RecordsBy${name}Id", mapOf(kind.idKey to orderId)).array
        val nextMaterials = if (kind == PoolKind.Maintenance) session.request("erpOutbound/getErpMaintainMaterialsByMaintainId", mapOf("maintainId" to orderId)).array else emptyList()
        records = nextRecords; materials = nextMaterials
        selected = orderId
    }
    suspend fun load(keepContent: Boolean = false) {
        busy = true; error = null; canOperate = false; returnBranch = Jv.NULL
        if (!keepContent) { detail = null; orders = emptyList(); records = emptyList(); materials = emptyList() }
        try {
            detail = loadPoolOrder(session, kind, id)
            val task = detail?.get("taskInfo") ?: Jv.NULL
            if (task["id"].text.isNotEmpty()) canOperate = session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text))["canSubmit"].bool
            if (canOperate && !readOnly) {
                val branches = session.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to task["id"].text))["flowConditions"].array
                returnBranch = branches.firstOrNull(::isReturnCondition) ?: branches.firstOrNull(::isRejectCondition) ?: Jv.NULL
            }
            orders = session.request("${kind.path}/get${name}OrdersBy${name}Id", mapOf(kind.idKey to id)).array
            val first = orders.firstOrNull { it["orderId"].text == selected } ?: orders.firstOrNull()
            if (first == null) { selected = ""; records = emptyList(); materials = emptyList() }
            first?.get("orderId")?.text?.takeIf { it.isNotEmpty() }?.let { loadRecords(it) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { canOperate = false; selected = ""; error = e.message ?: "读取失败，请重试" }
        finally { busy = false }
    }
    LaunchedEffect(id, session.accountKey) { load() }
    Surface(Modifier.fillMaxSize()) {
        FormPage(title = if (kind == PoolKind.Maintenance) "保养工单详情" else "精度检查工单详情",
            busy = busy, onClose = onClose, bottomBar = {
                if (canOperate && !readOnly) GlassBottomSurface {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical), horizontalArrangement = Arrangement.spacedBy(BarSpec.Gap)) {
                    if (!returnBranch.isNull) SoftButton(onClick = { action = "RETURN" }, enabled = !busy, modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight), contentColor = MaterialTheme.colorScheme.error) {
                        Text(if (isReturnCondition(returnBranch)) "退单" else "驳回")
                    }
                    GlassActionButton(onClick = { action = if (detail?.get("taskInfo")?.get("category")?.text == if (kind == PoolKind.Maintenance) "MAINTAINED" else "PRECISED") "EDIT" else "FLOW" },
                        enabled = !busy, modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight)) {
                        Text(detail?.get("taskInfo")?.get("name")?.text?.ifEmpty { "处理任务" } ?: "处理任务")
                    }
                    }
                }
            },
            actions = { GlassIconButton(onClick = { if (!busy) scope.launch { load() } }) { Icon(Icons.Default.Refresh, "刷新") } }) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            detail?.let { row ->
                FormTitle("工单信息")
                PoolDetailGroup {
                    Text(row[kind.nameKey].text, style = MaterialTheme.typography.titleMedium)
                    if (kind == PoolKind.Maintenance) PoolDetailRow("状态", com.personal.tpm.data.maintenanceStateName(row[kind.stateKey].text))
                    poolFacts(row).forEach { (key, value) -> PoolDetailRow(key, value) }
                }
                row["processInstanceId"].text.takeIf { it.isNotBlank() }?.let {
                    GlassTextButton(onClick = { action = "HISTORY" }, enabled = !busy) { Text("查看处理流程") }
                }
            }
            FormTitle("执行记录")
            if (orders.isEmpty() && !busy) Text("暂无执行记录")
            if (orders.size == 1) Text("第 1 次执行 · " + orders.first()["startTime"].dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (orders.size > 1) SingleChoiceGroup(orders.mapIndexed { index, order -> "第 ${index + 1} 次 · ${order["startTime"].dateText}" }, orders.indexOfFirst { it["orderId"].text == selected }, { index ->
                scope.launch {
                    busy = true; error = null
                    try { loadRecords(orders[index]["orderId"].text) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { records = emptyList(); materials = emptyList(); selected = ""; error = e.message ?: "读取记录失败" }
                    finally { busy = false }
                }
            }, enabled = !busy, scrollable = true)
            orders.firstOrNull { it["orderId"].text == selected }?.let { execution ->
                PoolDetailGroup {
                    val sameStart = execution["signTime"].dateText.isNotBlank() && execution["signTime"].dateText == execution["startTime"].dateText
                    listOf("signTime" to if (sameStart) "签到 / 开始" else "签到时间", "startTime" to "开始时间", "endTime" to "结束时间").forEach { (field, label) ->
                        if (field != "startTime" || !sameStart) execution[field].dateText.takeIf { it.isNotBlank() }?.let { PoolDetailRow(label, it) }
                    }
                    execution["content"].text.takeIf { it.isNotBlank() }?.let { PoolDetailRow("执行说明", it) }
                    execution["albums"].array.forEach { PhotoRow(it) }
                }
            }
            if (kind == PoolKind.Maintenance && selected.isNotBlank()) {
                if (materials.isEmpty()) Text("本次执行未领用物料", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                FormTitle("保养领料 · ${materials.size} 项")
                PoolDetailGroup {
                materials.forEachIndexed { index, material ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(material["material"].label, style = MaterialTheme.typography.titleMedium)
                        PoolDetailRow("料号", com.personal.tpm.data.repairMaterialCode(material))
                        PoolDetailRow("出库单号", material["outbound"]["outNumber"].text)
                        PoolDetailRow("使用数量", com.personal.tpm.data.materialQuantity(material["useNum"].text, material["material"]))
                }
                }
                }
            }
            if (records.isNotEmpty()) {
                FormTitle("检查项目 · ${records.size} 项")
                val numberSize = rememberTextMeasurer().measure(AnnotatedString("${records.size}."), style = MaterialTheme.typography.bodyMedium).size
                val numberWidth = with(LocalDensity.current) { numberSize.width.toDp().coerceAtLeast(24.dp) }
                Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 2.dp)) {
                        records.forEachIndexed { index, record ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = numberWidth + 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            PoolCheckRecord(kind, index, record, numberWidth)
                        }
                    }
                }
            }
        }
    }
    SlideInOverlayFor(action, onBack = if (action == "HISTORY") ({ action = null }) else null) { page ->
        if (page == "EDIT") PoolExecutionForm(kind, id, onClose = { action = null; scope.launch { load() } }, onSubmitFlow = { action = "FLOW" })
        else if (page == "HISTORY") {
            WorkflowScreen(processId = detail?.get("processInstanceId")?.text.orEmpty(), onBack = { action = null })
        }
        else TaskScreen(com.personal.tpm.data.OrderKind.Fault, id, page == "RETURN", onBack = { action = null },
            onDone = { stayOnDetail ->
                if (stayOnDetail) {
                    action = null
                    busy = true
                    scope.launch {
                        delay((Motion.Page + Motion.Instant).toLong())
                        session.notifyChanged()
                        load(keepContent = true)
                    }
                } else {
                    onClose()
                    session.notifyChanged()
                }
            }, poolKind = kind, initialCondition = if (page == "RETURN") returnBranch["value"].text else null, refreshOnDone = false)
    }
}

/** One number rail; title, result and remarks all share the same content edge. */
@Composable
private fun PoolCheckRecord(kind: PoolKind, index: Int, record: Jv, numberWidth: Dp) {
    val status = record["executeResult"].text
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("${index + 1}.", Modifier.width(numberWidth).alignByBaseline(), style = MaterialTheme.typography.bodyMedium, color = muted)
        Column(Modifier.weight(1f).alignByBaseline(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(record["itemName"].text.ifBlank { record[kind.path + "Item"]["itemName"].text }.ifBlank { "未命名检查项目" },
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(when (status) { "EXECUTED" -> "已完成"; "IGNORE" -> "已忽略"; "NOT" -> "未执行"; else -> "未记录" },
                    Modifier.alignByBaseline(), style = MaterialTheme.typography.labelMedium,
                    color = if (status == "EXECUTED") MaterialTheme.colorScheme.primary else muted)
                if (status != "IGNORE") {
                    Row(Modifier.weight(1f).alignByBaseline(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("结果", Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = muted)
                        Text(record[kind.path + "Result"].text.ifBlank { "未填写" }, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            record["description"].text.takeIf { it.isNotBlank() }?.let { note ->
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    Text("备注", Modifier.alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(note, Modifier.weight(1f).alignByBaseline(), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

@Composable
private fun PoolDetailGroup(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}

@Composable
private fun PoolDetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(label, Modifier.width(80.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}
