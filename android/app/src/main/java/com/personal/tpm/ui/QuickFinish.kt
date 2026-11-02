package com.personal.tpm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.tpm.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop
import org.json.JSONObject
import java.util.UUID

enum class QuickJobState { Queued, Running, Done, Failed, Review }
class QuickFinishJob(val item: BatchItem, val taskId: String, val frozen: String,
    val choices: Map<String, List<String>>, var actor: Session, val draftId: String, val draftScope: String) {
    var state by mutableStateOf(QuickJobState.Queued)
    var message by mutableStateOf("")
    var wrote = false
}

class QuickFinishViewModel(val owner: Session, private val prefs: PickerPrefs, private val drafts: DraftStore) : ViewModel() {
    val operationKey = "quick:" + UUID.randomUUID()
    var items by mutableStateOf<List<BatchItem>>(emptyList()); private set
    var position by mutableIntStateOf(0); private set
    var loading by mutableStateOf(false); private set
    var confirming by mutableStateOf(false); private set
    var begun by mutableStateOf(false); private set
    var showQueue by mutableStateOf(false)
    var signing by mutableStateOf(false); private set
    var signProgress by mutableStateOf(""); private set
    var failure by mutableStateOf<String?>(null)
    var visible by mutableStateOf(false); private set
    val jobs = mutableStateListOf<QuickFinishJob>()
    val skipped = mutableStateListOf<String>()
    private val edits = mutableMapOf<String, QuickRepairEdit>()
    fun editFor(id: String) = edits[id]
    fun retainEdit(id: String, edit: QuickRepairEdit) {
        if (jobs.none { it.item.id == id && it.state != QuickJobState.Failed } && id !in skipped) edits[id] = edit
    }
    private val previous = mutableMapOf<String, List<String>>()
    private var worker: Job? = null
    private var hide: Job? = null
    private var preparation: Job? = null
    private var valid = true
    val current get() = items.getOrNull(position)
    val busy get() = signing || loading || jobs.any { it.state == QuickJobState.Queued || it.state == QuickJobState.Running }
    val badge: String? get() = when {
        loading -> "读取待办…"
        signing -> signProgress
        !visible -> null
        else -> "提交 ${jobs.count { it.state == QuickJobState.Done }}/${jobs.size}" +
            jobs.count { it.state == QuickJobState.Failed || it.state == QuickJobState.Review }.takeIf { it > 0 }?.let { " · 异常$it" }.orEmpty()
    }
    init {
        viewModelScope.launch { owner.scope.drop(1).collect {
            valid = false; preparation?.cancel(); worker?.cancel(); hide?.cancel()
            jobs.filter { it.state == QuickJobState.Queued || it.state == QuickJobState.Running }.forEach {
                it.state = if (it.wrote) QuickJobState.Review else QuickJobState.Failed
                it.message = "主账号或厂区已改变，任务停止；草稿已保留"
            }
        } }
    }
    private fun engine(actor: Session) = QuickRepairEngine(LiveBatchApi(actor, prefs))
    fun discover() {
        if (loading || signing || !valid) return
        loading = true; failure = null; begun = false; showQueue = true
        preparation = viewModelScope.launch {
            try {
                val pending = engine(owner).pending()
                val blocked = jobs.filter { it.state != QuickJobState.Failed }.map { it.item.id }.toSet()
                items = pending.filter { it.id !in blocked }; position = 0; confirming = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: "读取待办失败" }
            finally { loading = false }
        }
    }
    fun cancelConfirmation() { confirming = false }
    fun begin(actor: Session) {
        if (!confirming || signing || !valid) return
        confirming = false; signing = true; visible = true
        val snapshot = items.toList()
        preparation = viewModelScope.launch {
            val ready = mutableListOf<BatchItem>()
            val failures = mutableListOf<String>()
            try {
                val total = snapshot.count { it.group == "SIGNED" }; var signed = 0
                for (item in snapshot) {
                    if (item.group == "SIGNED") {
                        signProgress = "签到 ${signed + 1}/$total"
                        try { ready += engine(actor).sign(item) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { failures += "${item.name}：${e.message}" }
                        signed++
                    } else ready += item
                }
                items = ready; position = 0; begun = true; showQueue = false
                if (failures.isNotEmpty()) failure = "以下工单未加入填写队列，请核对后再处理：\n" + failures.joinToString("\n")
                owner.notifyChanged()
            } finally { signing = false; if (jobs.isEmpty()) visible = false }
        }
    }
    suspend fun prepare(item: BatchItem, actor: Session): QuickRepairPrepared {
        val prepared = engine(actor).prepare(item)
        return prepared.copy(selections = inheritedQuickApprovers(prepared.nodes, prepared.selections, previous))
    }
    fun rememberApprovers(nodes: List<Jv>, values: Map<String, List<String>>) {
        nodes.filter(::isChosenApprover).forEach { node ->
            previous[quickApproverKey(node)] = values[node["variableName"].text].orEmpty().toList()
        }
    }
    suspend fun enqueue(prepared: QuickRepairPrepared, data: JSONObject, choices: Map<String, List<String>>, actor: Session, existingDraft: QuickDraftRef?) {
        check(valid && owner.signedIn) { "当前操作已失效" }
        check(jobs.none { it.item.id == prepared.item.id && it.state != QuickJobState.Failed }) { "此工单已在提交队列中" }
        val frozen = data.toString()
        repairValidationError(Jv(JSONObject(frozen)))?.let { error(it) }
        missingAssigneeMessage(prepared.nodes, choices)?.let { error(it) }
        rememberApprovers(prepared.nodes, choices)
        val draftId = existingDraft?.id ?: UUID.randomUUID().toString(); val draftScope = existingDraft?.scope ?: actor.repairDraftScope
        withContext(Dispatchers.IO) { drafts.save(FormDraft(id = draftId, data = JSONObject(frozen), selections = choices, orderId = prepared.item.id,
            orderSn = prepared.detail["sn"].text.ifEmpty { null }), draftScope) }
        check(valid) { "账号已改变，内容已保存为草稿" }
        jobs.removeAll { it.item.id == prepared.item.id && it.state == QuickJobState.Failed }
        jobs += QuickFinishJob(prepared.item, prepared.task["id"].text, frozen, choices.mapValues { it.value.toList() }, actor, draftId, draftScope)
        edits.remove(prepared.item.id)
        visible = true; hide?.cancel(); advance(); startWorker()
    }
    fun skip() { current?.let { skipped += it.id; edits.remove(it.id) }; advance() }
    private fun advance() { position++; while (current != null && jobs.any { it.item.id == current?.id }) position++ }
    fun retry(job: QuickFinishJob, actor: Session) {
        if (job.state != QuickJobState.Failed || job.wrote || !valid) return
        job.actor = actor; job.state = QuickJobState.Queued; visible = true; hide?.cancel(); startWorker()
    }
    fun hideCompleted() { if (!busy) visible = false }
    private fun startWorker() {
        if (worker?.isActive == true || !valid) return
        val pendingWorker = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (valid) {
                    val job = jobs.firstOrNull { it.state == QuickJobState.Queued } ?: break
                    job.state = QuickJobState.Running
                    try {
                        engine(job.actor).submit(job.item, job.taskId, job.frozen, job.choices) { job.wrote = true }
                        job.state = QuickJobState.Done; job.message = "提交完成"
                        withContext(Dispatchers.IO) { drafts.remove(job.draftId, job.draftScope) }
                        job.actor.notifyChanged()
                    } catch (e: CancellationException) {
                        job.state = if (job.wrote) QuickJobState.Review else QuickJobState.Failed
                        job.message = "任务停止，填写内容已保存为草稿"; throw e
                    } catch (e: Exception) {
                        job.state = if (job.wrote) QuickJobState.Review else QuickJobState.Failed
                        job.message = e.message ?: "提交失败，草稿已保留"
                    }
                }
            } finally {
                worker = null
                if (valid && jobs.any { it.state == QuickJobState.Queued }) startWorker()
                else if (jobs.isNotEmpty() && jobs.all { it.state == QuickJobState.Done }) {
                    hide = viewModelScope.launch { delay(3000); if (!busy) visible = false }
                }
            }
        }
        worker = pendingWorker
        pendingWorker.start()
    }
}

data class QuickDraftRef(val id: String, val scope: String)
data class QuickRepairEdit(val data: String, val choices: Map<String, List<String>>, val draft: QuickDraftRef?)

data class QuickRepairContext(
    val restored: QuickRepairEdit?,
    val retainEdit: (QuickRepairEdit) -> Unit,
    val prepare: suspend () -> QuickRepairPrepared,
    val enqueue: suspend (QuickRepairPrepared, JSONObject, Map<String, List<String>>, QuickDraftRef?) -> Unit,
    val rememberApprovers: (List<Jv>, Map<String, List<String>>) -> Unit,
    val onSkip: () -> Unit,
    val position: String,
)
data class QuickProgress(val text: String?, val open: () -> Unit)
val LocalQuickProgress = compositionLocalOf { QuickProgress(null, {}) }

@Composable
fun QuickQueueCapsule() {
    val progress = LocalQuickProgress.current
    progress.text?.let { text ->
        Box(Modifier.height(18.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = progress.open).padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun QuickFinishScreen(model: QuickFinishViewModel, onBack: () -> Unit, openOrder: (String) -> Unit) {
    val actor = LocalSession.current
    LaunchedEffect(Unit) { if (!model.begun && !model.confirming && !model.loading) model.discover() }
    val item = model.current
    if (model.begun && item != null && !model.signing && !model.showQueue) {
        key(item.id) {
            RepairFormScreen(item.id, onClose = onBack, onSubmitted = {}, quick = QuickRepairContext(
                restored = model.editFor(item.id), retainEdit = { model.retainEdit(item.id, it) },
                prepare = { model.prepare(item, actor) },
                enqueue = { prepared, data, choices, draft -> model.enqueue(prepared, data, choices, actor, draft) },
                rememberApprovers = model::rememberApprovers, onSkip = model::skip,
                position = "${model.position + 1}/${model.items.size}",
            ))
        }
    } else GlassScaffold(topBar = { AppTopBar("快速完成工单", onBack = onBack, operationLocked = model.signing || model.loading) }) { padding ->
        ScrollableLazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (model.loading) "正在读取全部待办…" else if (model.signing) model.signProgress else "填写与提交进度", style = MaterialTheme.typography.titleLarge)
                if (model.loading || model.signing) StatisticsProgressIndicator()
                Text("仅处理维修待签到、待完成。跳过的工单保持原状。", style = MaterialTheme.typography.bodyMedium)
            }
            if (!model.busy) item { GlassTonalButton(onClick = model::discover) { Text("重新读取待办") } }
            if (model.begun && model.current != null) item { GlassTonalButton(onClick = { model.showQueue = false }) { Text("继续填写 ${model.position + 1}/${model.items.size}") } }
            items(model.jobs.size) { index ->
                val job = model.jobs[index]
                GroupCard { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(job.item.name, style = MaterialTheme.typography.titleSmall)
                    Text("操作账号：${job.actor.fullName.ifEmpty { job.actor.accountName }}", style = MaterialTheme.typography.bodySmall)
                    Text(when(job.state) { QuickJobState.Queued -> "等待提交"; QuickJobState.Running -> "正在提交"; QuickJobState.Done -> "提交完成"; else -> job.message })
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (job.state == QuickJobState.Failed && !job.wrote) GlassTextButton(onClick = { model.retry(job, actor) }) { Text("重试") }
                        if (job.state == QuickJobState.Failed || job.state == QuickJobState.Review) GlassTextButton(onClick = { openOrder(job.item.id) }) { Text("查看工单并核对") }
                    }
                } }
            }
            if (model.skipped.isNotEmpty()) item { Text("已跳过 ${model.skipped.size} 单") }
            if (!model.busy) item { GlassTextButton(onClick = model::hideCompleted) { Text("关闭进度提示") } }
        }
    }
    if (model.confirming) GlassAlertDialog(onDismissRequest = model::cancelConfirmation, title = { Text("开始快速完成？") },
        text = { Text("已读取全部待办：待签到 ${model.items.count { it.group == "SIGNED" }} 单，待完成 ${model.items.count { it.group == "REPAIRED" }} 单。\n" +
            if (model.items.any { it.group == "SIGNED" }) "继续会先一键签到，再逐单填写；每单可以跳过。" else "继续后逐单填写，每单可以跳过。") },
        confirmButton = { GlassTextButton(onClick = { model.begin(actor) }, enabled = model.items.isNotEmpty()) { Text("继续处理") } },
        dismissButton = { GlassTextButton(onClick = model::cancelConfirmation) { Text("取消") } })
    model.failure?.let { ErrorDialog("快速完成提示", it) { model.failure = null } }
}
