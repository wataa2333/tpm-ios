package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

@Composable
fun PoolExecutionForm(kind: PoolKind, id: String, onClose: () -> Unit, onSubmitFlow: () -> Unit,
    quick: QuickRepairContext? = null, batch: PoolBatchStage? = null) {
    val session = LocalSession.current
    val drafts = LocalDrafts.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val actor = session.accountKey
    val localDrafts = quick == null && batch == null
    val draftScope = remember(kind, id, actor) { session.repairDraftScope + ":pool:" + kind.path }
    val draftGate = remember(kind, id, actor) { Any() }
    val draftEpoch = remember(kind, id, actor) { java.util.concurrent.atomic.AtomicInteger() }
    var restoredDraftId by remember(kind, id, actor) { mutableStateOf<String?>(null) }
    var draftStatus by remember { mutableStateOf("填写内容自动保存到本机") }
    val name = if (kind == PoolKind.Maintenance) "Maintain" else "Precise"
    var data by remember(kind, id) { mutableStateOf(JSONObject()) }
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
    var orderInfo by remember(kind, id) { mutableStateOf(Jv.NULL) }
    var nodes by remember { mutableStateOf(batch?.nodes.orEmpty()) }
    var choices by remember { mutableStateOf(batch?.choices.orEmpty()) }
    var resultPicker by remember { mutableStateOf<Int?>(null) }
    var pickApprover by remember { mutableStateOf<PickRequest?>(null) }
    var existingMaterials by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var creatingErp by remember { mutableStateOf(false) }
    var materialsBusy by remember { mutableStateOf(false) }
    var pendingOutboundId by remember { mutableStateOf<String?>(null) }
    val value = Jv(data)
    fun update(key: String, content: Any?) { data = JSONObject(data.toString()).put(key, content); saved = false; dirty = true }
    fun updateRecord(index: Int, key: String, content: Any) {
        val rows = JSONArray(Jv(data)[kind.path + "Records"].raw.toString())
        rows.getJSONObject(index).put(key, content)
        if (key == kind.path + "Result") rows.getJSONObject(index).optJSONObject(kind.path + "Item")?.put(key, content)
        update(kind.path + "Records", rows)
    }
    fun importOutbound(outboundId: String) {
        pendingOutboundId = outboundId
        update("pendingErpOutboundId", outboundId)
        materialsBusy = true
        scope.launch {
            try {
                check(actor == session.accountKey) { "操作账号已变化，请重新打开" }
                check(outboundId.isNotBlank()) { "未取得新出库单编号，请到 ERP 出库核对后添加已出库物料" }
                val lines = session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to outboundId)).array
                check(actor == session.accountKey) { "操作账号已变化，请重新打开" }
                check(lines.isNotEmpty()) { "出库单尚未返回物料明细" }
                update("erpMaintainMaterials", mergePoolOutboundMaterials(Jv(data)["erpMaintainMaterials"].array, lines))
                update("pendingErpOutboundId", JSONObject.NULL)
                pendingOutboundId = null
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = "出库单已创建，物料回填未完成：${e.message}。请重试读取或核对该出库单，勿重复新建领料。" }
            finally { materialsBusy = false }
        }
    }
    fun refreshMaterials() {
        if (materialsBusy) return
        val current = Jv(data)["erpMaintainMaterials"].array
        materialsBusy = true
        scope.launch {
            try {
                check(actor == session.accountKey) { "操作账号已变化，请重新打开" }
                val lines = current.map { it["materialOutbound"]["id"]["outboundId"].text }.distinct().flatMap { outboundId ->
                    session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to outboundId)).array
                }
                check(actor == session.accountKey) { "操作账号已变化，请重新打开" }
                update("erpMaintainMaterials", refreshPoolOutboundMaterials(Jv(data)["erpMaintainMaterials"].array, lines))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = "刷新出库物料失败：${e.message}" }
            finally { materialsBusy = false }
        }
    }
    LaunchedEffect(kind, id, actor) {
        loading = true; ready = false; saved = false; dirty = false
        try {
            if (quick != null) {
                val current = quick.prepare(); prepared = current; nodes = current.nodes; choices = current.selections
            }
            val detail = loadPoolOrder(session, kind, id)
            orderInfo = detail
            val task = detail["taskInfo"]
            check(task["category"].text == if (kind == PoolKind.Maintenance) "MAINTAINED" else "PRECISED") { "当前节点不是填写执行结果，请刷新工单" }
            coroutineScope {
            val permission = async { session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text)) }
            val latest = async { session.request("${kind.path}/getLatest${name}OrderBy${name}Id", mapOf(kind.idKey to id)) }
            check(permission.await()["canSubmit"].bool) { "当前账号没有处理权限" }
            val order = latest.await()
            check(order["orderId"].text.isNotEmpty()) { "没有可填写的执行记录" }
            val records = async { session.request("${kind.path}/get${name}RecordsBy${name}Id", mapOf(kind.idKey to order["orderId"].text)).array }
            val materials = async { if (kind == PoolKind.Maintenance) session.request("erpOutbound/getErpMaintainMaterialsByMaintainId", mapOf("maintainId" to order["orderId"].text)) else Jv.NULL }
            val rows = records.await()
            val result = withContext(Dispatchers.Default) {
                poolExecutionSnapshot(order).apply {
                    put("startTime", order["startTime"].text.ifEmpty { order["signTime"].text })
                    put("endTime", order["endTime"].text.ifEmpty { serverDate(Date()) })
                    put(kind.path + "Records", JSONArray(rows.map { poolDefaultRecord(kind.path, it) }))
                }
            }
            if (kind == PoolKind.Maintenance) result.put("erpMaintainMaterials", materials.await().raw)
            existingMaterials = Jv(result)["erpMaintainMaterials"].array
            if (batch != null) result.put("erpMaintainMaterials", JSONArray())
            if (localDrafts) {
                val draft = withContext(Dispatchers.IO) { drafts.drafts(draftScope, DraftTarget.Order(id)).firstOrNull { Jv(it.data)["orderId"].text == order["orderId"].text } }
                if (draft != null) {
                    try {
                        val restored = withContext(Dispatchers.Default) { restorePoolDraft(Jv(result), kind.path, Jv(draft.data)) }
                        restored.keys().forEach { key -> result.put(key, restored.get(key)) }
                        restoredDraftId = draft.id; choices = draft.selections; dirty = true
                        draftStatus = "已恢复本机草稿"
                    } catch (e: IllegalStateException) {
                        restoredDraftId = java.util.UUID.randomUUID().toString()
                        draftStatus = e.message ?: "旧草稿与当前执行记录不符，未自动恢复"
                    }
                }
            }
            quick?.loadDraft?.invoke()?.takeIf { Jv(JSONObject(it.data))["orderId"].text == order["orderId"].text }?.let { edit ->
                val kept = JSONObject(edit.data)
                check(Jv(kept)["orderId"].text == order["orderId"].text) { "执行记录已改变，请重新读取待办" }
                kept.keys().forEach { key -> result.put(key, kept.get(key)) }
                choices = inheritedQuickApprovers(nodes, choices, nodes.associate { quickApproverKey(it) to edit.choices[it["variableName"].text].orEmpty() })
            }
            data = result
            pendingOutboundId = if (Jv(result)["pendingErpOutboundId"].isNull) null else Jv(result)["pendingErpOutboundId"].text
            ready = true
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "读取执行记录失败" }
        finally { loading = false }
    }
    val quickSnapshot = remember(data, choices, ready, quick) {
        if (ready && quick != null) QuickRepairEdit(data.toString(), choices.mapValues { it.value.toList() }, quick.restored?.draft) else null
    }
    val edit by rememberUpdatedState(quickSnapshot)
    DisposableEffect(kind, id) { onDispose { edit?.let { quick?.retainEdit?.invoke(it) } } }
    LaunchedEffect(edit?.data, edit?.choices, ready) {
        if (ready && quick != null) { kotlinx.coroutines.delay(450); edit?.let(quick.retainEdit) }
    }
    fun localDraft(): Pair<Int, FormDraft> = draftEpoch.get() to FormDraft(
        // Edits replace data with a copied JSONObject. Hold this immutable version until IO copies the whitelist.
        id = restoredDraftId ?: poolDraftId(draftScope, id, Jv(data)["orderId"].text), data = data,
        selections = choices, orderId = id, orderSn = orderInfo["sn"].text.ifBlank { null })
    suspend fun writeLocalDraft(snapshot: Pair<Int, FormDraft>, finish: Boolean = false) = withContext(Dispatchers.IO) {
        synchronized(draftGate) {
            if (draftEpoch.get() == snapshot.first) {
                val pending = snapshot.second
                drafts.save(FormDraft(id = pending.id, savedAt = pending.savedAt, data = poolDraftSnapshot(Jv(pending.data), kind.path),
                    selections = pending.selections, orderId = pending.orderId, orderSn = pending.orderSn), draftScope)
                if (finish) draftEpoch.incrementAndGet()
                true
            } else false
        }
    }
    val localSnapshot = remember(data, choices, ready, dirty, saved, draftScope, restoredDraftId, orderInfo) {
        if (localDrafts && ready && dirty && !saved) localDraft() else null
    }
    val localEdit by rememberUpdatedState(localSnapshot)
    LaunchedEffect(localSnapshot) {
        val snapshot = localEdit ?: return@LaunchedEffect
        kotlinx.coroutines.delay(450)
        try { if (writeLocalDraft(snapshot) && draftEpoch.get() == snapshot.first) draftStatus = "草稿已自动保存到本机" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { draftStatus = "草稿保存失败，请点保存重试：${e.message}" }
    }
    DisposableEffect(id, draftScope) { onDispose {
        localEdit?.let { snapshot -> CoroutineScope(Dispatchers.IO).launch {
            try { writeLocalDraft(snapshot) } catch (_: Exception) { /* Explicit back/save reports failures before leaving. */ }
        } }
    } }
    fun keepLocalDraft(close: Boolean) {
        if (saving || uploading || materialsBusy) return
        if (!ready || (close && !dirty)) { if (close) onClose(); return }
        val snapshot = localDraft()
        saving = true
        scope.launch {
            try {
                check(writeLocalDraft(snapshot, finish = close)) { "执行记录已变化，请重新打开" }; draftStatus = "草稿已保存到本机"
                if (close) onClose() else snackbar.showSnackbar("草稿已保存到本机")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = "草稿未保存：${e.message}。当前填写仍保留在本页。" }
            finally { saving = false }
        }
    }
    fun save(submit: Boolean, endTask: Boolean = false) {
        if (!ready || saving || uploading || materialsBusy || creatingErp) return
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
                    if (saved && localDrafts) {
                        val draftId = restoredDraftId ?: poolDraftId(draftScope, id, Jv(frozen)["orderId"].text)
                        draftEpoch.incrementAndGet()
                        withContext(Dispatchers.IO) { synchronized(draftGate) { drafts.remove(draftId, draftScope) } }
                        draftStatus = "执行结果已保存到服务器"
                    }
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth.coerceAtMost(960.dp) - 32.dp >= 320.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)) 2 else 1
        val checkRows = remember(data, kind) { value[kind.path + "Records"].array }
        val checkGroups = remember(checkRows, columns) { checkRows.indices.chunked(columns) }
        val checkKeys = remember(checkRows) { poolExecutionRecordKeys(checkRows) }
        val checkGroupKeys = remember(checkKeys, columns) { poolExecutionRowKeys(checkKeys, columns) }
        FormPage(title = (if (kind == PoolKind.Maintenance) "完成保养" else "完成精度检查") + (quick?.let { " ${it.position}" } ?: if (batch != null) " · 批量 ${batch.count} 单" else ""), busy = loading || saving || materialsBusy,
            onClose = { if (!saving && !uploading && !materialsBusy) {
                if (localDrafts) keepLocalDraft(true)
                else if (quick != null) { edit?.let(quick.retainEdit); onClose() }
                else if (dirty) confirmExit = true else onClose()
            } }, backEnabled = !saving && !uploading && !materialsBusy, interceptBack = true, wideContent = true, snackbar = snackbar,
            bottomBar = {
                if (quick != null) GlassBottomSurface {
                    Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SoftButton(onClick = { edit?.let(quick.retainEdit); quick.onPrevious() }, enabled = quick.hasPrevious && !saving && !uploading && !materialsBusy, modifier = Modifier.weight(1f)) { Text("上一单") }
                            SoftButton(onClick = { edit?.let(quick.retainEdit); quick.onSkip() }, enabled = !saving && !uploading && !materialsBusy, modifier = Modifier.weight(1f)) { Text("跳过") }
                            GlassActionButton(onClick = { save(false) }, enabled = quick.hasNext && ready && !saving && !uploading && !materialsBusy, modifier = Modifier.weight(1f)) { Text("下一单") }
                        }
                        GlassActionButton(onClick = {
                            if (!quick.skipped) save(false, true) else scope.launch {
                                saving = true
                                try { quick.onFinish() }
                                catch (e: CancellationException) { throw e }
                                catch (e: Exception) { failure = e.message }
                                finally { saving = false }
                            }
                        }, enabled = ready && !saving && !uploading && !materialsBusy, modifier = Modifier.fillMaxWidth()) { Text("结束任务") }
                    }
                } else if (batch != null) GlassBottomSurface {
                    GlassActionButton(onClick = { save(false) }, enabled = ready && !saving && !uploading && !materialsBusy, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) { Text("开始批量完成（${batch.count} 单）") }
                } else FormActionBar(busy = saving || uploading || materialsBusy, enabled = ready, onSave = { keepLocalDraft(false) }, onSubmit = { save(true) })
            }, lazyContent = {
            if (!ready) item(key = "loading") { Text("正在读取执行项目与处理权限…") }
            if (ready) {
                item(key = "execution-info") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (localDrafts) Text(draftStatus, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                }
                }
                        itemsIndexed(checkGroups, key = { rowIndex, _ -> checkGroupKeys[rowIndex] }, contentType = { _, _ -> "checks" }) { _, indices ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                indices.forEach { index ->
                                    val row = checkRows[index]
                                    key(checkKeys[index]) {
                                    Box(Modifier.weight(1f)) {
                                    val item = row[kind.path + "Item"]
                                    Card(shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)) { Column(Modifier.fillMaxWidth().padding(10.dp)) {
                                        PoolItemTitle("${index + 1}. ${item["itemName"].text}")
                                        SingleChoiceGroup(listOf("完成", "忽略"), if (row["executeResult"].text == "IGNORE") 1 else 0,
                                            { updateRecord(index, "executeResult", if (it == 1) "IGNORE" else "EXECUTED") }, modifier = Modifier.padding(top = 6.dp))
                                        androidx.compose.animation.AnimatedVisibility(row["executeResult"].text != "IGNORE",
                                            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), expandFrom = androidx.compose.ui.Alignment.Top),
                                            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), shrinkTowards = androidx.compose.ui.Alignment.Top)) {
                                            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            if (item["itemType"].text == "OPTION") {
                                                Text(if (item["required"].bool || kind == PoolKind.Maintenance) "检查结果 *" else "检查结果")
                                                GlassTextButton(onClick = { resultPicker = index }, modifier = Modifier.fillMaxWidth()) { Text(row[kind.path + "Result"].text.ifEmpty { "请选择" }, maxLines = 2); Icon(Icons.Default.ExpandMore, "选择检查结果", Modifier.size(18.dp)) }
                                            } else OutlinedTextField(row[kind.path + "Result"].text, { updateRecord(index, kind.path + "Result", it.take(255)) }, Modifier.fillMaxWidth(),
                                                label = { Text(if (item["required"].bool) "检查结果 *" else "检查结果") },
                                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (item["itemType"].text == "NUMERICAL") androidx.compose.ui.text.input.KeyboardType.Decimal else androidx.compose.ui.text.input.KeyboardType.Text))
                                            }
                                        }
                                        Box(Modifier.padding(top = 6.dp)) {
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
                                        }
                                    } }
                                    }
                                    }
                                }
                                if (indices.size < columns) Spacer(Modifier.weight(1f))
                            }
                        }
                if (kind == PoolKind.Maintenance) {
                    item(key = "materials") {
                    PoolOptionalSection("ERP 领料（${value["erpMaintainMaterials"].array.size} 项）", initiallyExpanded = true) {
                    if (batch != null) Text("此处新增物料按同一用量用于所选工单；各单原有领料保留，提交前逐单核对剩余数量。", style = MaterialTheme.typography.bodySmall)
                    if (batch == null) GlassTextButton(onClick = { creatingErp = true }, enabled = !saving && !uploading && !materialsBusy && pendingOutboundId == null && orderInfo["sn"].text.isNotBlank()) { Text("新建出库单") }
                    pendingOutboundId?.let { outboundId ->
                        Text("出库单已创建，待回填物料。请重试读取，无需重复新建。", style = MaterialTheme.typography.bodySmall)
                        GlassTextButton(onClick = { importOutbound(outboundId) }, enabled = !materialsBusy) { Text("重试读取出库物料") }
                    }
                    GlassTextButton(enabled = !saving && !materialsBusy, onClick = { pick = PickRequest(RefSource.OutboundMaterials, { line ->
                        update("erpMaintainMaterials", mergePoolOutboundMaterials(Jv(data)["erpMaintainMaterials"].array, listOf(line)))
                    }, multiple = true, selected = value["erpMaintainMaterials"].array.map { outboundLineIdentity(it["materialOutbound"]) }.toSet(),
                        onRemove = { line -> update("erpMaintainMaterials", JSONArray(Jv(data)["erpMaintainMaterials"].array.filter { outboundLineIdentity(it["materialOutbound"]) != outboundLineIdentity(line) }.map { it.raw })) }) }) { Text("添加已出库物料") }
                    if (value["erpMaintainMaterials"].array.isNotEmpty()) GlassTextButton(onClick = { refreshMaterials() }, enabled = !saving && !materialsBusy) { Text("刷新出库数量") }
                    value["erpMaintainMaterials"].array.forEachIndexed { index, row ->
                        key(outboundLineIdentity(row["materialOutbound"])) {
                        GroupCard {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(row["material"].label)
                                Text("料号：" + row["material"]["materialCode"].text, style = MaterialTheme.typography.bodySmall)
                                Text(listOf(row["outbound"]["outNumber"].text, "实发 " + materialQuantity(row["materialOutbound"]["actualQuantity"].text, row["material"]),
                                    "可用 " + materialQuantity(quantityText(poolMaterialAvailable(row, existingMaterials)), row["material"])).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                if (poolMaterialAvailable(row, existingMaterials) <= 0) Text("尚无可用实发数量；完成出库后刷新数量，再提交保养。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                            InputRow("使用数量", row["useNum"].text, { amount ->
                                val rows = JSONArray(Jv(data)["erpMaintainMaterials"].raw.toString()); rows.getJSONObject(index).put("useNum", amount)
                                update("erpMaintainMaterials", rows)
                            }, required = true, suffix = materialUnit(row["material"]), maximum = poolMaterialAvailable(row, existingMaterials))
                            GlassTextButton(onClick = { update("erpMaintainMaterials", JSONArray(Jv(data)["erpMaintainMaterials"].array.filterIndexed { i, _ -> i != index }.map { it.raw })) }, enabled = !saving && !materialsBusy) { Text("移除") }
                        }
                        }
                    }
                }
                }
                }
                if (saved) item(key = "saved") { Text("执行结果已保存，尚未提交流程", color = MaterialTheme.colorScheme.primary) }
            }
        }) {}
        }
    }
    PickHost(pick) { pick = null }
    PickHost(pickApprover) { pickApprover = null }
    SlideInOverlayFor(creatingErp.takeIf { it }) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ErpFormScreen(existingId = null, referenceSn = orderInfo["sn"].text, onClose = { creatingErp = false }, interceptBack = true,
                onSaved = { result -> importOutbound(result["outboundId"].text) })
        }
    }
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
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), expandFrom = androidx.compose.ui.Alignment.Top),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(Motion.Normal, easing = Motion.Change), shrinkTowards = androidx.compose.ui.Alignment.Top)) {
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
