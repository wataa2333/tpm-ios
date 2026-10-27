package com.personal.tpm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.assigneesPayload
import com.personal.tpm.data.commitTaskPayload
import com.personal.tpm.data.isChosenApprover
import com.personal.tpm.data.isCountersign
import com.personal.tpm.data.isRejectCondition
import com.personal.tpm.data.isReturnCondition
import com.personal.tpm.data.materialQuantity
import com.personal.tpm.data.materialUnit
import com.personal.tpm.data.missingAssigneeMessage
import com.personal.tpm.data.serverDate
import com.personal.tpm.data.taskActionTitle
import com.personal.tpm.data.usernames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/** The buttons at the bottom of an order: the step it is waiting on, and "退单" where the step allows it. Nothing shows when it waits for someone else. */
@Composable
fun TaskActionBar(kind: OrderKind, order: Jv, includeNavigationPadding: Boolean = true, onOpen: (returning: Boolean) -> Unit) {
    val session = LocalSession.current
    val task = order["taskInfo"]
    val taskId = task["id"].text
    if (taskId.isEmpty()) return
    val changed by session.changed.collectAsState()
    var canSubmit by remember(taskId) { mutableStateOf(false) }
    var canReturn by remember(taskId) { mutableStateOf(false) }
    var canHandoff by remember(taskId) { mutableStateOf(false) }
    LaunchedEffect(taskId, changed) {
        try {
            val (state, form) = coroutineScope {
                val s = async { session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text)) }
                val f = async { session.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to taskId)) }
                s.await() to f.await()
            }
            canSubmit = state["canSubmit"].bool
            canHandoff = state["canDelegate"].bool || state["canResolveDelegate"].bool
            canReturn = kind == OrderKind.Fault && canSubmit && form["flowConditions"].array.any(::isReturnCondition)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { canSubmit = false; canReturn = false; canHandoff = false }
    }
    if (!canSubmit && !canHandoff) return
    Surface(Modifier.fadeAbove(MaterialTheme.colorScheme.background), color = MaterialTheme.colorScheme.background, shadowElevation = 0.dp) {
        Row(Modifier.fillMaxWidth().then(if(includeNavigationPadding) Modifier.navigationBarsPadding() else Modifier).padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical), horizontalArrangement = Arrangement.spacedBy(BarSpec.Gap)) {
            if (canReturn) SoftButton(onClick = { onOpen(true) }, Modifier.weight(1f).height(BarSpec.ButtonHeight)) { Text("退单") }
            Button(onClick = { onOpen(false) }, Modifier.weight(1f).height(BarSpec.ButtonHeight)) { AnimatedLabel(taskActionTitle(task, kind)) }
        }
    }
}

private fun endOfToday(): String {
    val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59) }
    return serverDate(c.time)
}

/**
 * Takes the order's current step forward: pick what to do, fill what that step needs (the repair group and finish time when accepting, who repairs it
 * when assigning, the issued quantities when an ERP order goes out, the approver of the next step), write the opinion, and send it. Pressing the
 * button sends: there is no confirmation dialog. "完成任务" (the repair result) is not here yet.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskScreen(kind: OrderKind, id: String, startReturn: Boolean, onBack: () -> Unit, onDone: (stay: Boolean) -> Unit) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    val scope = rememberCoroutineScope()
    var detail by remember { mutableStateOf<Jv?>(null) }
    var state by remember { mutableStateOf(Jv.NULL) }
    var conditions by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var condition by remember { mutableStateOf("") }
    var comment by remember { mutableStateOf("") }
    var group by remember { mutableStateOf(Jv.NULL) }
    var assignUser by remember { mutableStateOf(Jv.NULL) }
    var estimated by remember { mutableStateOf(endOfToday()) }
    var lines by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var actual by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var nodes by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var selections by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var loading by remember { mutableStateOf(true) }
    var assignmentsLoading by remember { mutableStateOf(false) }
    var assignmentsReady by remember { mutableStateOf(false) }
    var autoSubmitAttempted by remember(id) { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<String?>(null) } // "group", "assignUser" or "node:<variable>"
    var assignmentTicket by remember { mutableStateOf(0) }
    var handoff by remember { mutableStateOf<String?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    val task = detail?.get("taskInfo") ?: Jv.NULL
    val category = task["category"].text
    val selectedCondition = conditions.firstOrNull { it["value"].text == condition }
    val returning = selectedCondition?.let(::isReturnCondition) == true
    val rejecting = selectedCondition?.let(::isRejectCondition) == true
    val needsFaultData = kind == OrderKind.Fault && category in listOf("REPORTED", "RECEIVED")
    val needsErpLines = kind == OrderKind.Erp && category in listOf("EXECUTING", "CONFIRM")

    suspend fun loadAssignments(forCondition: String, t: Jv) {
        val mine = ++assignmentTicket
        assignmentsReady = false
        if (forCondition != "agree" || t["id"].text.isEmpty()) { nodes = emptyList(); selections = emptyMap(); assignmentsLoading = false; assignmentsReady = true; return }
        assignmentsLoading = true
        try {
            val values = session.request(
                "workflow/getAssigneeElementsByProcessCategory",
                mapOf("processCategory" to kind.prefix, "processInstanceId" to t["processInstanceId"].text, "taskKey" to t["taskDefinitionKey"].text),
            ).array
            if (mine != assignmentTicket) return
            val chosen = HashMap<String, List<String>>()
            for (node in values) {
                val key = node["variableName"].text
                var names = usernames(node["assignees"])
                // The step lets the person choose: the person's own default for it comes first. A step the process names by itself keeps its suggestion.
                if (names.isEmpty() && isChosenApprover(node)) prefs.defaultChoice(session.draftScope, "assignee.task.${kind.prefix}.$key")?.value?.get("userName")?.text?.takeIf { it.isNotEmpty() }?.let { names = listOf(it) }
                chosen[key] = names
            }
            nodes = values; selections = chosen; assignmentsReady = true
        } finally { if (mine == assignmentTicket) assignmentsLoading = false }
    }

    LaunchedEffect(id) {
        try {
            val order = session.request(kind.detailPath, mapOf(kind.idKey to id))
            detail = order
            val t = order["taskInfo"]
            if (t["id"].text.isEmpty()) { loadError = "这张单据现在没有待处理的任务，可能已被他人处理。"; return@LaunchedEffect }
            val (taskState, form) = coroutineScope {
                val s = async { session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to t["processInstanceId"].text)) }
                val f = async { session.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to t["id"].text)) }
                s.await() to f.await()
            }
            state = taskState
            conditions = form["flowConditions"].array
            condition = (if (startReturn) conditions.firstOrNull(::isReturnCondition) else null)?.get("value")?.text ?: (conditions.firstOrNull { it["value"].text == "agree" } ?: conditions.firstOrNull())?.get("value")?.text.orEmpty()
            group = order["userGroup"]
            if (kind == OrderKind.Erp && t["category"].text in listOf("EXECUTING", "CONFIRM")) {
                lines = session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to t["businessKey"].text)).array
                actual = lines.mapIndexed { i, line -> i to line["actualQuantity"].text }.toMap()
            }
            loading = false
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.message ?: "加载失败" }
        finally { loading = false }
    }
    // The approvers of the next step depend on the chosen action: they are read again whenever it changes.
    LaunchedEffect(condition, detail) {
        val order = detail
        if (order != null && condition.isNotEmpty()) {
            try { loadAssignments(condition, order["taskInfo"]) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = "加载下一节点审批人失败：" + (e.message ?: "") }
        }
    }

    fun submit() {
        if (submitting) return
        val order = detail ?: return
        if (condition == "agree") missingAssigneeMessage(nodes, selections)?.let { failure = it; return }
        submitting = true
        scope.launch {
            try {
                val payload = commitTaskPayload(task, condition, comment, if (condition == "agree") assigneesPayload(nodes, selections) else null)
                if (needsFaultData && condition in listOf("agree", "assign", "appoint")) {
                    val assigning = condition == "assign" || condition == "appoint"
                    if (assigning && assignUser["userName"].text.isEmpty()) throw IllegalStateException("请选择维修人员")
                    if (!assigning && (group["groupId"].text.isEmpty() || estimated.isEmpty())) throw IllegalStateException("请选择维修班组和预计完成时间")
                    val groupId = group["groupId"].raw
                    val data = JSONObject()
                        .put("repairWay", "SELF").put("faultType", order["faultType"].raw ?: JSONObject.NULL).put("groupId", groupId)
                        .put("userGroup", JSONObject().put("groupId", groupId)).put("estimatedCompleteTime", estimated)
                        .put("repairman", session.accountName).put("repairmanPhone", JSONObject.NULL)
                    if (assigning) data.put("assignUser", assignUser.raw).put("assignUserName", assignUser["userName"].text)
                    payload.put("taskData", data.toString())
                }
                if (needsErpLines) {
                    val sent = JSONArray()
                    lines.forEachIndexed { i, line ->
                        val quantity = actual[i]?.toDoubleOrNull() ?: 0.0
                        if (quantity < 1 || quantity > line["entryQuantity"].number) throw IllegalStateException("实发数量应在 1 与申请数量之间")
                        sent.put(JSONObject(line.raw.toString()).put("actualQuantity", quantity))
                    }
                    payload.put("taskData", sent.toString())
                }
                session.request("process/commitTask", body = payload)
                session.notifyChanged()
                onDone(kind == OrderKind.Fault && category == "SIGNED" && condition == "agree")
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalStateException) { failure = e.message }
            catch (e: Exception) { failure = "提交流程节点失败：" + (e.message ?: "") }
            finally { submitting = false }
        }
    }

    val ready = !loading && assignmentsReady && !assignmentsLoading && !submitting && state["canSubmit"].bool && condition.isNotEmpty()
    LaunchedEffect(ready) {
        if (!ready || autoSubmitAttempted || loadError != null) return@LaunchedEffect
        autoSubmitAttempted = true
        if (startReturn || returning || rejecting || missingAssigneeMessage(nodes, selections) != null) return@LaunchedEffect
        if (needsFaultData && condition in listOf("agree", "assign", "appoint")) {
            if (condition in listOf("assign", "appoint")) {
                if (assignUser["userName"].text.isEmpty()) return@LaunchedEffect
            } else if (group["groupId"].text.isEmpty() || estimated.isEmpty()) return@LaunchedEffect
        }
        if (needsErpLines && lines.indices.any { i ->
            val quantity = actual[i]?.toDoubleOrNull() ?: 0.0
            quantity < 1 || quantity > lines[i]["entryQuantity"].number
        }) return@LaunchedEffect
        submit()
    }
    val processingDefault = !startReturn && loadError == null && failure == null && (!autoSubmitAttempted || submitting) && (loading || assignmentsLoading || !assignmentsReady || state["canSubmit"].bool)
    if (processingDefault) {
        Scaffold(contentWindowInsets = appContentInsets(), containerColor = MaterialTheme.colorScheme.background) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.CircularProgressIndicator()
                    AnimatedLabel(if (submitting) "正在提交…" else "正在准备提交…")
                }
            }
        }
        return
    }
    Scaffold(contentWindowInsets = appContentInsets(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = if (task.isNull) "处理" else taskActionTitle(task, kind), onBack = onBack, loading = loading || assignmentsLoading || submitting,
                actions = {
                    // Handing the task to someone else: transfer, delegate, or finish a delegated one.
                    if (state["canDelegate"].bool || state["canResolveDelegate"].bool) Box {
                        IconButton(onClick = { moreMenu = true }, enabled = !submitting && !loading) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                        AppOptionSheet(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                            if (state["canDelegate"].bool) {
                                AppOptionItem(text = { Text("转办") }, onClick = { moreMenu = false; handoff = "transfer" })
                                AppOptionItem(text = { Text("委派") }, onClick = { moreMenu = false; handoff = "delegate" })
                            }
                            if (state["canResolveDelegate"].bool) AppOptionItem(text = { Text("委派完成") }, onClick = { moreMenu = false; handoff = "resolve" })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(Modifier.fadeAbove(MaterialTheme.colorScheme.background), color = MaterialTheme.colorScheme.background, shadowElevation = 0.dp) {
                Button(
                    onClick = { submit() }, enabled = ready,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical).height(BarSpec.ButtonHeight),
                ) { AnimatedLabel(if (submitting) "正在提交…" else if (category == "SIGNED" && condition == "agree") "签到" else "提交") }
            }
        },
    ) { padding ->
        Box(Modifier.padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
            RoundedSheet {
                PageWidth {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        loadError?.let { AnimatedLabel(it, color = MaterialTheme.colorScheme.error) }
                        if (kind == OrderKind.Fault && category == "SIGNED") {
                            FormTitle("签到设备")
                            GroupCard {
                                FactRow("资产名称", detail?.get("asset")?.get("assetName")?.text.orEmpty())
                                FactRow("资产编码", detail?.get("asset")?.get("assetCode")?.text.orEmpty())
                                FactRow("地址", detail?.get("asset")?.get("address")?.text.orEmpty())
                            }
                        }
                        if (conditions.isNotEmpty()) {
                            FormTitle("操作类型")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                conditions.forEach { c -> ChoiceChip(c["value"].text == condition, { condition = c["value"].text }, c["name"].text) }
                            }
                        }
                        // Only when there is something to fill in: accepting (the group and the date) or assigning (who repairs). Claiming has nothing to ask.
                        val assigningNow = condition == "assign" || condition == "appoint"
                        if (needsFaultData && (assigningNow || (category == "REPORTED" && condition == "agree"))) {
                            FormTitle("受理信息")
                            GroupCard {
                                if (assigningNow) PickRow("维修人员", assignUser.label, true) { picking = "assignUser" }
                                if (category == "REPORTED") {
                                    PickRow("维修班组", group.label, true) { picking = "group" }
                                    DateTimeField("预计完成时间", estimated, dateOnly = false, required = true) { estimated = it }
                                }
                            }
                        }
                        if (needsErpLines) {
                            FormTitle("出库物料")
                            GroupCard {
                                lines.forEachIndexed { i, line ->
                                    val material = line["material"]
                                    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp)) {
                                        AnimatedLabel(material["materialDesc"].text, style = MaterialTheme.typography.titleSmall)
                                        AnimatedLabel("申请数量 " + materialQuantity(line["entryQuantity"].text, material), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        InputRow("实发数量", actual[i].orEmpty(), { actual = actual + (i to it) }, required = true, suffix = materialUnit(material))
                                    }
                                    if (i < lines.lastIndex) GroupDivider()
                                }
                            }
                        }
                        val chosen = nodes.filter(::isChosenApprover)
                        if (condition == "agree" && chosen.isNotEmpty()) {
                            FormTitle("审批人")
                            GroupCard {
                                chosen.forEachIndexed { index, node ->
                                    val key = node["variableName"].text
                                    PickRow(node["taskName"].text, selections[key].orEmpty().joinToString("、") { UserNames.show(it) }, true) { picking = "node:$key" }
                                }
                            }
                        }
                        FormTitle(if (returning) "退单原因" else if (rejecting) "驳回意见" else "意见")
                        TextEntry(
                            "意见", comment, { comment = it }, limit = 255, minLines = 2,
                            placeholder = if (returning) "请输入退单原因" else if (rejecting) "请输入驳回意见" else "请输入意见",
                        )
                    }
                }
            }
        }
    }

    when (val which = picking) {
        "group" -> ReferenceSheet(RefSource.Groups, emptySet(), false, null, onToggle = { group = it }, onDismiss = { picking = null })
        "assignUser" -> ReferenceSheet(RefSource.Users, emptySet(), false, null, onToggle = { assignUser = it }, onDismiss = { picking = null })
        null -> {}
        else -> {
            val key = which.removePrefix("node:")
            val node = nodes.firstOrNull { it["variableName"].text == key }
            val multiple = node != null && (isCountersign(node) || node["assigneeMultiple"].bool)
            ReferenceSheet(
                RefSource.Users, selections[key].orEmpty().toSet(), multiple, "assignee.task.${kind.prefix}.$key".takeIf { node != null && usernames(node["assignees"]).isEmpty() },
                onToggle = { user ->
                    val name = user["userName"].text
                    val current = selections[key].orEmpty()
                    selections = selections + (key to if (multiple) (if (name in current) current - name else current + name) else listOf(name))
                },
                onDismiss = { picking = null },
            )
        }
    }
    failure?.let { ErrorDialog("提交未完成", it) { failure = null } }
    handoff?.let { operation -> HandoffSheet(state["taskId"].text, operation, onDismiss = { handoff = null }, onDone = { handoff = null; session.notifyChanged(); onDone(false) }) }
}

/** 转办 / 委派 / 委派完成: choose who (not for the last), write the reason, send. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HandoffSheet(taskId: String, operation: String, onDismiss: () -> Unit, onDone: () -> Unit) {
    val session = LocalSession.current
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(Jv.NULL) }
    var reason by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    val title = when (operation) { "transfer" -> "流程转办"; "delegate" -> "流程委派"; else -> "委派完成" }
    AppSheet(onDismissRequest = { if (!saving) onDismiss() }, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AnimatedLabel(title, style = MaterialTheme.typography.titleLarge)
            if (operation != "resolve") GroupCard { PickRow(if (operation == "transfer") "转办人" else "委派给", user.label, true) { picking = true } }
            TextEntry(if (operation == "resolve") "处理说明" else "原因", reason, { reason = it }, limit = 255, minLines = 3, required = true, placeholder = "请输入")
            Button(
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val body = JSONObject().put("taskId", taskId)
                            val path = when (operation) {
                                "resolve" -> { body.put("opinion", reason); "workflow/resolveDelegatedTask" }
                                "transfer" -> { body.put("transferTo", user["userName"].text).put("reason", reason); "workflow/transferTask" }
                                else -> { body.put("delegateTo", user["userName"].text).put("reason", reason); "workflow/delegateTask" }
                            }
                            session.request(path, body = body)
                            onDone()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failure = e.message ?: "提交失败" }
                        finally { saving = false }
                    }
                },
                enabled = !saving && reason.isNotBlank() && (operation == "resolve" || user["userName"].text.isNotEmpty()),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { AnimatedLabel(if (saving) "正在提交…" else "提交") }
        }
    }
    if (picking) ReferenceSheet(RefSource.WorkflowUsers, emptySet(), false, null, onToggle = { user = it }, onDismiss = { picking = false })
    failure?.let { ErrorDialog("提交未完成", it) { failure = null } }
}
