package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

@Composable
fun PoolExecutionForm(kind: PoolKind, id: String, onClose: () -> Unit, onSubmitFlow: () -> Unit,
    quick: QuickRepairContext? = null, batch: PoolBatchStage? = null) {
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
    var prepared by remember { mutableStateOf<QuickRepairPrepared?>(null) }
    var orderInfo by remember(id) { mutableStateOf(Jv.NULL) }
    var nodes by remember { mutableStateOf(batch?.nodes.orEmpty()) }
    var choices by remember { mutableStateOf(batch?.choices.orEmpty()) }
    var resultPicker by remember { mutableStateOf<Int?>(null) }
    var pickApprover by remember { mutableStateOf<PickRequest?>(null) }
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
            if (quick != null) {
                val current = quick.prepare(); prepared = current; nodes = current.nodes; choices = current.selections
            }
            val detail = loadPoolOrder(session, kind, id)
            orderInfo = detail
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
            result.put(kind.path + "Records", JSONArray(rows.map { poolDefaultRecord(kind.path, it) }))
            if (kind == PoolKind.Maintenance) result.put("erpMaintainMaterials", session.request("erpOutbound/getErpMaintainMaterialsByMaintainId", mapOf("maintainId" to order["orderId"].text)).raw)
            existingMaterials = Jv(result)["erpMaintainMaterials"].array
            if (batch != null) result.put("erpMaintainMaterials", JSONArray())
            quick?.loadDraft?.invoke()?.takeIf { Jv(JSONObject(it.data))["orderId"].text == order["orderId"].text }?.let { edit ->
                val kept = JSONObject(edit.data)
                check(Jv(kept)["orderId"].text == order["orderId"].text) { "执行记录已改变，请重新读取待办" }
                kept.keys().forEach { key -> result.put(key, kept.get(key)) }
                choices = inheritedQuickApprovers(nodes, choices, nodes.associate { quickApproverKey(it) to edit.choices[it["variableName"].text].orEmpty() })
            }
            data = result; ready = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "读取执行记录失败" }
        finally { loading = false }
    }
    val edit by rememberUpdatedState(if (ready && quick != null) QuickRepairEdit(data.toString(), choices.mapValues { it.value.toList() }, quick.restored?.draft) else null)
    DisposableEffect(id) { onDispose { edit?.let { quick?.retainEdit?.invoke(it) } } }
    LaunchedEffect(edit?.data, edit?.choices, ready) {
        if (ready && quick != null) { kotlinx.coroutines.delay(450); edit?.let(quick.retainEdit) }
    }
    fun save(submit: Boolean, endTask: Boolean = false) {
        if (!ready || saving || uploading) return
        poolExecutionError(value, kind.path, existingMaterials)?.let { failure = it; return }
        if (quick != null || batch != null) missingAssigneeMessage(nodes, choices)?.let { failure = it; return }
        val frozen = JSONObject(data.toString())
        saving = true
        scope.launch {
            try {
                if (quick != null) {
                    quick.enqueue(prepared ?: error("任务尚未就绪"), frozen, choices, quick.restored?.draft)
                    if (endTask) quick.onFinish() else quick.onNext()
                    return@launch
                }
                if (batch != null) { batch.onStage(frozen, choices); return@launch }
                check(actor == session.accountKey) { "操作账号已变化，请重新打开" }
                val fresh = loadPoolOrder(session, kind, id)
                check(fresh["taskInfo"]["category"].text == if (kind == PoolKind.Maintenance) "MAINTAINED" else "PRECISED") { "当前节点已变化，请刷新工单" }
                val state = session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to fresh["taskInfo"]["processInstanceId"].text))
                check(state["canSubmit"].bool) { "当前账号已无处理权限" }
                val latest = session.request("${kind.path}/getLatest${name}OrderBy${name}Id", mapOf(kind.idKey to id))
                check(latest["orderId"].text == Jv(frozen)["orderId"].text) { "执行记录已变化，请重新打开" }
                if (!saved) {
                    session.request("${kind.path}/handle${name}Order", body = poolExecutionPayload(Jv(frozen), kind.path))
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
        FormPage(title = (if (kind == PoolKind.Maintenance) "完成保养" else "完成精度检查") + (quick?.let { " ${it.position}" } ?: if (batch != null) " · 批量 ${batch.count} 单" else ""), busy = loading || saving,
            onClose = { if (!saving && !uploading) { if (dirty) confirmExit = true else onClose() } }, backEnabled = !saving && !uploading, interceptBack = true, wideContent = true, snackbar = snackbar,
            bottomBar = {
                if (quick != null) GlassBottomSurface {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton(onClick = { edit?.let(quick.retainEdit); quick.onPrevious() }, enabled = quick.hasPrevious && !saving && !uploading, modifier = Modifier.weight(1f)) { Text("上一单") }
                            SoftButton(onClick = { edit?.let(quick.retainEdit); quick.onSkip() }, enabled = !saving && !uploading, modifier = Modifier.weight(1f)) { Text("跳过") }
                            GlassActionButton(onClick = { save(false) }, enabled = quick.hasNext && ready && !saving && !uploading, modifier = Modifier.weight(1f)) { Text("下一单") }
                        }
                        GlassActionButton(onClick = {
                            if (!quick.skipped) save(false, true) else scope.launch {
                                saving = true
                                try { quick.onFinish() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failure = e.message }
                                finally { saving = false }
                            }
                        }, enabled = ready && !saving && !uploading, modifier = Modifier.fillMaxWidth()) { Text("结束任务") }
                    }
                } else if (batch != null) GlassBottomSurface {
                    GlassActionButton(onClick = { save(false) }, enabled = ready && !saving && !uploading, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("开始批量完成（${batch.count} 单）") }
                } else FormActionBar(busy = saving || uploading, enabled = ready, onSave = { save(false) }, onSubmit = { save(true) })
            }) {
            if (!ready) Text("正在读取执行项目与处理权限…")
            if (ready) {
                if (quick != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("编辑后自动保存到本机", style = MaterialTheme.typography.labelSmall)
                    TextButton(enabled = !saving && !uploading, onClick = { scope.launch {
                        try { edit?.let { quick.saveDraft(it) }; snackbar.showSnackbar("草稿已保存") }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = "草稿未保存：${e.message}" }
                    } }) { Text("手动保存草稿") }
                }
                if (quick != null) GroupCard {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PoolItemTitle(orderInfo[kind.nameKey].text.ifEmpty { prepared?.item?.name.orEmpty() })
                        orderInfo["sn"].text.takeIf { it.isNotEmpty() }?.let { Text("单号：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val asset = orderInfo["asset"]
                        Text(listOf(asset["assetCode"].text, asset["addressCode"].text).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        asset["assetName"].text.takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
                    }
                }
                FormTitle("执行信息")
                GroupCard {
                    DateTimeField("开始时间", value["startTime"].text, false, true) { update("startTime", it) }
                    DateTimeField("结束时间", value["endTime"].text, false, true) { update("endTime", it) }
                }
                if (quick != null || batch != null) {
                    FormTitle("审批人")
                    GroupCard { AssigneeRows(nodes, choices, null, update = { key, change ->
                        choices = choices + (key to change(choices[key].orEmpty()))
                        quick?.rememberApprovers?.invoke(nodes, choices)
                    }, pick = { pickApprover = it }) }
                }
                PoolOptionalSection("说明与现场照片（${value["albums"].array.size} 张）") {
                OutlinedTextField(value["content"].text, { update("content", it) }, Modifier.fillMaxWidth(), label = { Text("说明") }, minLines = 2)
                PhotosField(value["albums"], { update("albums", it) }, "${session.currentFactoryId}/${kind.path}Order", { uploading = it }, { failure = it }, limit = 15)
                }
                FormTitle("逐项检查")
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 320.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)) 2 else 1
                    val rows = value[kind.path + "Records"].array
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        rows.indices.chunked(columns).forEach { indices ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                indices.forEach { index ->
                                    val row = rows[index]
                                    key(row["recordId"].text.ifEmpty { index.toString() }) {
                                    Box(Modifier.weight(1f)) {
                                    val item = row[kind.path + "Item"]
                                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) { Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        PoolItemTitle("${index + 1}. ${item["itemName"].text}")
                                        SingleChoiceGroup(listOf("完成", "忽略"), if (row["executeResult"].text == "IGNORE") 1 else 0,
                                            { updateRecord(index, "executeResult", if (it == 1) "IGNORE" else "EXECUTED") })
                                        if (row["executeResult"].text != "IGNORE") {
                                            val options = item["optionList"].array.map(::poolResultOption)
                                            if (item["itemType"].text == "OPTION") {
                                                Text(if (item["required"].bool || kind == PoolKind.Maintenance) "检查结果 *" else "检查结果")
                                                GlassTextButton(onClick = { resultPicker = index }, modifier = Modifier.fillMaxWidth()) { Text(row[kind.path + "Result"].text.ifEmpty { "请选择" }, maxLines = 2); Icon(Icons.Default.ExpandMore, "选择检查结果", Modifier.size(18.dp)) }
                                            } else OutlinedTextField(row[kind.path + "Result"].text, { updateRecord(index, kind.path + "Result", it.take(255)) }, Modifier.fillMaxWidth(),
                                                label = { Text(if (item["required"].bool) "检查结果 *" else "检查结果") },
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (item["itemType"].text == "NUMERICAL") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
                                        }
                                        PoolOptionalSection(if (row["description"].text.isEmpty()) "标准与备注" else "标准／备注已填") {
                                            Text(item["itemName"].text, style = MaterialTheme.typography.bodySmall)
                                            listOf("intervalPeriod" to "间隔周期要求", "guidance" to "作业指导书", "methodBenchmark" to "方法和基准").forEach { (field, label) ->
                                                item[field].text.takeIf { it.isNotEmpty() }?.let { raw ->
                                                    val display = if (field == "intervalPeriod") when (raw) {
                                                        "DAY" -> "每日"; "WEEK" -> "每周"; "MONTH" -> "每月"; "QUARTER" -> "每季度"; "YEAR" -> "每年"; else -> raw
                                                    } else raw
                                                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                        Text(display, style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                            }
                                            OutlinedTextField(row["description"].text, { updateRecord(index, "description", it) }, Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall, label = { Text("结果说明（选填）", style = MaterialTheme.typography.labelSmall) })
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
                    if (batch != null) Text("此处新增物料按同一用量用于所选工单；各单原有领料保留，提交前逐单核对剩余数量。", style = MaterialTheme.typography.bodySmall)
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
    PickHost(pickApprover) { pickApprover = null }
    resultPicker?.let { index ->
        val record = value[kind.path + "Records"].array.getOrNull(index)
        if (record != null) PoolResultPicker(record[kind.path + "Item"]["optionList"].array.map(::poolResultOption),
            onPick = { updateRecord(index, kind.path + "Result", it) }, onClose = { resultPicker = null })
    }
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
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).then(Modifier.clickable { expanded = !expanded }), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            val angle by androidx.compose.animation.core.animateFloatAsState(if (expanded) 180f else 0f, androidx.compose.animation.core.tween(Motion.Normal), label = "pool disclosure")
            Icon(Icons.Default.ExpandMore, if (expanded) "收起" else "展开", Modifier.size(18.dp).graphicsLayer { rotationZ = angle }, tint = MaterialTheme.colorScheme.primary)
        }
        androidx.compose.animation.AnimatedVisibility(expanded,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change)) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(Motion.Normal)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change)) + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(Motion.Fast))) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PoolResultPicker(options: List<String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    fun close(value: String? = null) {
        if (closing) return
        closing = true
        scope.launch { try { state.hide(); value?.let(onPick); onClose() } finally { closing = false } }
    }
    AppSheet(onDismissRequest = onClose, sheetState = state) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("选择检查结果", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            GlassTextButton(onClick = { close() }, enabled = !closing) { Text("关闭") }
        }
        ScrollableLazyColumn(Modifier.heightIn(max = 400.dp)) {
            items(options.size) { i -> GlassTextButton(onClick = { close(options[i]) }, enabled = !closing, modifier = Modifier.fillMaxWidth()) { Text(options[i]) } }
            if (options.isEmpty()) item { Text("当前标准没有可选结果，请核对标准配置", Modifier.padding(20.dp)) }
        }
    }
}

@Composable
private fun PoolItemTitle(text: String) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().then(Modifier.clickable(enabled = expanded || overflowing) { expanded = !expanded })
        .animateContentSize(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change)), verticalAlignment = androidx.compose.ui.Alignment.Top) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
            minLines = 2, maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow })
        if (expanded || overflowing) Icon(Icons.Default.ExpandMore, if (expanded) "收起名称" else "展开名称",
            Modifier.size(18.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f }, tint = MaterialTheme.colorScheme.primary)
        else Spacer(Modifier.size(18.dp))
    }
}
