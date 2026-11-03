package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

@Composable
fun PoolExecutionForm(kind: PoolKind, id: String, onClose: () -> Unit, onSubmitFlow: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val actor = session.accountKey
    val name = if (kind == PoolKind.Maintenance) "Maintain" else "Precise"
    var data by remember(id) { mutableStateOf(JSONObject()) }
    var loading by remember { mutableStateOf(true) }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pick by remember { mutableStateOf<PickRequest?>(null) }
    var saved by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var existingMaterials by remember { mutableStateOf<List<Jv>>(emptyList()) }
    val value = Jv(data)
    fun update(key: String, content: Any?) { data = JSONObject(data.toString()).put(key, content); saved = false; dirty = true }
    fun updateRecord(index: Int, key: String, content: Any) {
        val rows = JSONArray(Jv(data)[kind.path + "Records"].raw.toString())
        rows.getJSONObject(index).put(key, content)
        if (key == kind.path + "Result") rows.getJSONObject(index).optJSONObject(kind.path + "Item")?.put(key, content)
        update(kind.path + "Records", rows)
    }
    LaunchedEffect(id, actor) {
        loading = true; ready = false
        try {
            val detail = loadPoolOrder(session, kind, id)
            val task = detail["taskInfo"]
            check(task["category"].text == if (kind == PoolKind.Maintenance) "MAINTAINED" else "PRECISED") { "当前节点不是填写执行结果，请刷新工单" }
            val state = session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text))
            check(state["canSubmit"].bool) { "当前账号没有处理权限" }
            val order = session.request("${kind.path}/getLatest${name}OrderBy${name}Id", mapOf(kind.idKey to id))
            check(order["orderId"].text.isNotEmpty()) { "没有可填写的执行记录" }
            val rows = session.request("${kind.path}/get${name}RecordsBy${name}Id", mapOf(kind.idKey to order["orderId"].text)).array
            val result = poolExecutionSnapshot(order)
            result.put("startTime", order["startTime"].text.ifEmpty { order["signTime"].text })
            result.put("endTime", order["endTime"].text.ifEmpty { serverDate(Date()) })
            result.put(kind.path + "Records", JSONArray(rows.map { row -> JSONObject(row.raw.toString()).also {
                if (row["executeResult"].text == "NOT") it.put("executeResult", "EXECUTED")
            } }))
            if (kind == PoolKind.Maintenance) result.put("erpMaintainMaterials", session.request("erpOutbound/getErpMaintainMaterialsByMaintainId", mapOf("maintainId" to order["orderId"].text)).raw)
            data = result; existingMaterials = Jv(result)["erpMaintainMaterials"].array; ready = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "读取执行记录失败" }
        finally { loading = false }
    }
    fun save(submit: Boolean) {
        if (!ready || saving || uploading) return
        poolExecutionError(value, kind.path, existingMaterials)?.let { failure = it; return }
        val frozen = JSONObject(data.toString())
        saving = true
        scope.launch {
            try {
                check(actor == session.accountKey) { "操作账号已变化，请重新打开" }
                val fresh = loadPoolOrder(session, kind, id)
                check(fresh["taskInfo"]["category"].text == if (kind == PoolKind.Maintenance) "MAINTAINED" else "PRECISED") { "当前节点已变化，请刷新工单" }
                val state = session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to fresh["taskInfo"]["processInstanceId"].text))
                check(state["canSubmit"].bool) { "当前账号已无处理权限" }
                val latest = session.request("${kind.path}/getLatest${name}OrderBy${name}Id", mapOf(kind.idKey to id))
                check(latest["orderId"].text == Jv(frozen)["orderId"].text) { "执行记录已变化，请重新打开" }
                if (!saved) {
                    session.request("${kind.path}/handle${name}Order", body = frozen)
                    saved = data.toString() == frozen.toString(); dirty = !saved; session.notifyChanged()
                }
                if (submit) {
                    check(saved) { "保存期间填写内容已变化，请重新保存最新内容" }
                    onSubmitFlow()
                } else scope.launch { snackbar.showSnackbar(if (saved) "执行结果已保存，尚未提交流程" else "已保存本次快照，最新修改仍需保存") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = "保存未完成：${e.message}。请核对服务器记录后再操作。" }
            finally { saving = false }
        }
    }
    Surface(Modifier.fillMaxSize()) {
        FormPage(title = if (kind == PoolKind.Maintenance) "完成保养" else "完成精度检查", busy = loading || saving,
            onClose = { if (!saving && !uploading) { if (dirty) confirmExit = true else onClose() } }, backEnabled = !saving && !uploading, interceptBack = true, wideContent = true, snackbar = snackbar,
            bottomBar = { FormActionBar(busy = saving || uploading, enabled = ready, onSave = { save(false) }, onSubmit = { save(true) }) }) {
            if (!ready) Text("正在读取执行项目与处理权限…")
            if (ready) {
                FormTitle("执行信息")
                GroupCard {
                    DateTimeField("开始时间", value["startTime"].text, false, true) { update("startTime", it) }
                    DateTimeField("结束时间", value["endTime"].text, false, true) { update("endTime", it) }
                }
                PoolOptionalSection("说明与现场照片（${value["albums"].array.size} 张）") {
                OutlinedTextField(value["content"].text, { update("content", it) }, Modifier.fillMaxWidth(), label = { Text("说明") }, minLines = 2)
                PhotosField(value["albums"], { update("albums", it) }, "${session.currentFactoryId}/${kind.path}Order", { uploading = it }, { failure = it }, limit = 15)
                }
                FormTitle("逐项检查")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 640.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)) 2 else 1
                    val rows = value[kind.path + "Records"].array
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        rows.indices.chunked(columns).forEach { indices ->
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                indices.forEach { index ->
                                    val row = rows[index]
                                    key(row["recordId"].text.ifEmpty { index.toString() }) {
                                    Box(Modifier.weight(1f)) {
                                    val item = row[kind.path + "Item"]
                                    GroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("${index + 1}. ${item["itemName"].text}", style = MaterialTheme.typography.titleMedium)
                                        PoolOptionalSection("作业标准与指导") {
                                        listOf("intervalPeriod" to "间隔周期要求", "guidance" to "作业指导书", "methodBenchmark" to "方法和基准").forEach { (key, label) ->
                                            item[key].text.takeIf { it.isNotEmpty() }?.let { FactRow(label, it) }
                                        }
                                        }
                                        SingleChoiceGroup(listOf("完成", "忽略"), if (row["executeResult"].text == "IGNORE") 1 else 0,
                                            { updateRecord(index, "executeResult", if (it == 1) "IGNORE" else "EXECUTED") })
                                        if (row["executeResult"].text != "IGNORE") {
                                            val options = item["optionList"].array.map(::poolResultOption)
                                            if (item["itemType"].text == "OPTION") {
                                                Text(if (item["required"].bool || kind == PoolKind.Maintenance) "检查结果 *" else "检查结果")
                                                SingleChoiceGroup(options, options.indexOf(row[kind.path + "Result"].text), { updateRecord(index, kind.path + "Result", options[it]) }, columns = 3)
                                            } else OutlinedTextField(row[kind.path + "Result"].text, { updateRecord(index, kind.path + "Result", it.take(255)) }, Modifier.fillMaxWidth(),
                                                label = { Text(if (item["required"].bool) "检查结果 *" else "检查结果") },
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (item["itemType"].text == "NUMERICAL") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
                                        }
                                        PoolOptionalSection(if (row["description"].text.isEmpty()) "结果说明（选填）" else "结果说明（已填写）") {
                                            OutlinedTextField(row["description"].text, { updateRecord(index, "description", it) }, Modifier.fillMaxWidth(), label = { Text("结果说明（选填）") })
                                        }
                                    } }
                                    }
                                    }
                                }
                                if (indices.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
                if (kind == PoolKind.Maintenance) {
                    PoolOptionalSection("出库物料（${value["erpMaintainMaterials"].array.size} 项）") {
                    GlassTextButton(onClick = { pick = PickRequest(RefSource.OutboundMaterials, { line ->
                        val rows = value["erpMaintainMaterials"].array
                        if (rows.any { outboundLineIdentity(it["materialOutbound"]) == outboundLineIdentity(line) }) return@PickRequest
                        update("erpMaintainMaterials", JSONArray(rows.map { it.raw } + JSONObject().put("material", line["material"].raw)
                            .put("outbound", line["outbound"].raw).put("materialOutbound", line.raw).put("useNum", 1)))
                    }) }) { Text("添加已出库物料") }
                    value["erpMaintainMaterials"].array.forEachIndexed { index, row ->
                        GroupCard {
                            Text(row["material"].label, Modifier.padding(16.dp))
                            InputRow("使用数量", row["useNum"].text, { amount ->
                                val rows = JSONArray(value["erpMaintainMaterials"].raw.toString()); rows.getJSONObject(index).put("useNum", amount)
                                update("erpMaintainMaterials", rows)
                            }, required = true, suffix = materialUnit(row["material"]))
                            GlassTextButton(onClick = { update("erpMaintainMaterials", JSONArray(value["erpMaintainMaterials"].array.filterIndexed { i, _ -> i != index }.map { it.raw })) }) { Text("移除") }
                        }
                    }
                }
                }
                if (saved) Text("执行结果已保存，尚未提交流程", color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    PickHost(pick) { pick = null }
    failure?.let { ErrorDialog("提示", it) { failure = null } }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false }, title = { Text("返回工单详情？") },
        text = { Text("当前修改尚未保存，返回后需要重新填写。") },
        confirmButton = { TextButton(onClick = { confirmExit = false; onClose() }) { Text("放弃修改并返回") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("继续填写") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PoolGroupPicker(onPick: (Jv) -> Unit, onClose: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var groups by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { groups = session.request("user/getEnabledUserGroupsByGroupType", mapOf("groupType" to "MAINTAIN")).array }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message }
    }
    AppSheet(onDismissRequest = onClose, sheetState = state) {
        Text("选择保养班组", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        ScrollableLazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(groups.size) { i -> GlassTextButton(onClick = { scope.launch { state.hide(); onPick(groups[i]); onClose() } }) { Text(groups[i].label) } }
        }
    }
}

@Composable
private fun PoolOptionalSection(label: String, initiallyExpanded: Boolean = false, content: @Composable () -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initiallyExpanded) }
    Column(Modifier.fillMaxWidth()) {
        DisclosureToggle(expanded, label, compact = true) { expanded = !expanded }
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Normal)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Fast))) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}
