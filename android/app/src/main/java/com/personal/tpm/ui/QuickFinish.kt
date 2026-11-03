package com.personal.tpm.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.SizeTransform
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
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
    val choices: Map<String, List<String>>, var actor: Session, val draftId: String, val draftScope: String, val round: Int) {
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
    var showProgress by mutableStateOf(false)
    var completionAcknowledged by mutableStateOf(true); private set
    val signFailures = mutableStateListOf<String>()
    private var round by mutableIntStateOf(0)
    private val roundJobs get() = jobs.filter { it.round == round }
    var signing by mutableStateOf(false); private set
    var signProgress by mutableStateOf(""); private set
    var failure by mutableStateOf<String?>(null)
    var visible by mutableStateOf(false); private set
    val jobs = mutableStateListOf<QuickFinishJob>()
    private val staged = mutableStateListOf<QuickFinishJob>()
    val skipped = mutableStateListOf<String>()
    private val edits = mutableMapOf<String, QuickRepairEdit>()
    fun editFor(id: String) = edits[id]
    fun retainEdit(id: String, edit: QuickRepairEdit) {
        if (jobs.none { it.item.id == id && it.state != QuickJobState.Failed }) edits[id] = edit
    }
    private val protectedOrders = mutableSetOf<String>()
    private val previous = mutableMapOf<String, List<String>>()
    private var worker: Job? = null
    private var preparation: Job? = null
    private var valid = true
    val current get() = items.getOrNull(position)
    val busy get() = signing || loading || jobs.any { it.state == QuickJobState.Queued || it.state == QuickJobState.Running }
    val formBadge: String? get() = if (quickSubmissionActive(roundJobs.map { it.state })) badge else quickPreviousResult(roundJobs.map { it.state }) ?: badge
    val badge: String? get() = when {
        loading -> "读取待办…"
        signing -> signProgress
        !visible || !quickSubmissionActive(jobs.map { it.state }) -> null
        else -> "提交 ${jobs.count { it.state == QuickJobState.Done }}/${jobs.size}" +
            jobs.count { it.state == QuickJobState.Failed || it.state == QuickJobState.Review }.takeIf { it > 0 }?.let { " · 异常$it" }.orEmpty()
    }
    init {
        viewModelScope.launch { owner.scope.drop(1).collect {
            valid = false; preparation?.cancel(); worker?.cancel()
            jobs.filter { it.state == QuickJobState.Queued || it.state == QuickJobState.Running }.forEach {
                it.state = if (it.wrote) QuickJobState.Review else QuickJobState.Failed
                it.message = "主账号或厂区已改变，任务停止；草稿已保留"
            }
        } }
    }
    private fun engine(actor: Session) = QuickRepairEngine(LiveBatchApi(actor, prefs))
    val completionReady get() = quickCompletionReady(begun, current != null, loading || signing || quickSubmissionActive(roundJobs.map { it.state }), completionAcknowledged)
    val summary get() = quickRunSummary(roundJobs.map { it.state }, skipped.size, signFailures.size)
    fun acknowledgeCompletion() { completionAcknowledged = true; visible = quickSubmissionActive(jobs.map { it.state }) }
    fun discover() {
        if (loading || signing || !valid) return
        protectedOrders += jobs.filter { it.state == QuickJobState.Review }.map { it.item.id }
        // Submitted jobs keep their frozen actor while the next run reads fresh server state.
        jobs.removeAll { it.state == QuickJobState.Done || it.state == QuickJobState.Failed }
        round++
        items = emptyList(); position = 0
        staged.clear(); skipped.clear(); signFailures.clear(); edits.clear(); previous.clear()
        completionAcknowledged = false
        loading = true; failure = null; begun = false; showProgress = false
        val blockedAtStart = jobs.filter { quickBlocksNewRun(it.state) }.map { it.item.id }.toSet()
        preparation = viewModelScope.launch {
            try {
                val pending = engine(owner).pending()
                val blocked = protectedOrders + blockedAtStart + jobs.filter { quickBlocksNewRun(it.state) }.map { it.item.id }
                items = pending.filter { it.id !in blocked }; position = 0; confirming = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: "读取待办失败" }
            finally { loading = false }
        }
    }
    fun cancelConfirmation() { confirming = false; completionAcknowledged = true }
    fun begin(actor: Session, onReady: () -> Unit) {
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
                items = ready; position = 0; begun = true
                signFailures.addAll(failures)
                owner.notifyChanged()
                if (ready.isNotEmpty()) onReady()
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
        val submittingRound = round
        check(valid && owner.signedIn) { "当前操作已失效" }
        check(current?.id == prepared.item.id) { "当前工单已切换，请勿重复提交" }
        check(jobs.none { it.item.id == prepared.item.id && it.state != QuickJobState.Failed }) { "此工单已在提交队列中" }
        val frozen = data.toString()
        repairValidationError(Jv(JSONObject(frozen)))?.let { error(it) }
        missingAssigneeMessage(prepared.nodes, choices)?.let { error(it) }
        rememberApprovers(prepared.nodes, choices)
        val draftId = existingDraft?.id ?: UUID.randomUUID().toString(); val draftScope = existingDraft?.scope ?: actor.repairDraftScope
        withContext(Dispatchers.IO) { drafts.save(FormDraft(id = draftId, data = JSONObject(frozen), selections = choices, orderId = prepared.item.id,
            orderSn = prepared.detail["sn"].text.ifEmpty { null }), draftScope) }
        check(valid && round == submittingRound && current?.id == prepared.item.id) { "当前操作已改变，内容已保存为草稿" }
        jobs.removeAll { it.item.id == prepared.item.id && it.state == QuickJobState.Failed }
        staged.removeAll { it.item.id == prepared.item.id }
        skipped.remove(prepared.item.id)
        staged += QuickFinishJob(prepared.item, prepared.task["id"].text, frozen, choices.mapValues { it.value.toList() }, actor, draftId, draftScope, submittingRound)
        edits[prepared.item.id] = QuickRepairEdit(frozen, choices.mapValues { it.value.toList() }, QuickDraftRef(draftId, draftScope))
    }
    fun next() = advance()
    fun previous() { if (position > 0) position-- }
    suspend fun finish() {
        check(valid && begun) { "当前任务已失效" }
        val pending = staged.filter { it.item.id !in skipped }.map { saved ->
            val edit = edits[saved.item.id]
            val data = edit?.data ?: saved.frozen
            val choices = edit?.choices ?: saved.choices
            repairValidationError(Jv(JSONObject(data)))?.let { error("${saved.item.name}：$it，请返回该单修改") }
            val prepared = engine(saved.actor).prepare(saved.item)
            missingAssigneeMessage(prepared.nodes, choices)?.let { error("${saved.item.name}：$it，请返回该单修改") }
            withContext(Dispatchers.IO) { drafts.save(FormDraft(id = saved.draftId, data = JSONObject(data), selections = choices,
                orderId = saved.item.id, orderSn = prepared.detail["sn"].text.ifEmpty { null }), saved.draftScope) }
            QuickFinishJob(saved.item, prepared.task["id"].text, data, choices.mapValues { it.value.toList() }, saved.actor, saved.draftId, saved.draftScope, round)
        }
        check(valid) { "当前任务已失效，草稿已保留" }
        jobs.addAll(pending); staged.clear()
        items.filter { item -> roundJobs.none { it.item.id == item.id } }.forEach { if (it.id !in skipped) skipped += it.id }
        position = items.size; visible = true; startWorker()
    }
    fun skip() { current?.let { if (it.id !in skipped) skipped += it.id; staged.removeAll { job -> job.item.id == it.id } }; advance() }
    private fun advance() { if (position < items.lastIndex) position++ }
    fun retry(job: QuickFinishJob, actor: Session) {
        if (job.state != QuickJobState.Failed || job.wrote || !valid) return
        job.actor = actor; job.state = QuickJobState.Queued; visible = true; startWorker()
    }
    private fun startWorker() {
        if (worker?.isActive == true || !valid) return
        val pendingWorker = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (valid) {
                    val job = jobs.firstOrNull { it.state == QuickJobState.Queued } ?: break
                    job.state = QuickJobState.Running
                    try {
                        engine(job.actor).submit(job.item, job.taskId, job.frozen, job.choices) { job.wrote = true }
                        withContext(Dispatchers.IO) { drafts.remove(job.draftId, job.draftScope) }
                        job.actor.notifyChanged()
                        job.state = QuickJobState.Done; job.message = "提交完成"
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
    val onPrevious: () -> Unit,
    val onFinish: suspend () -> Unit,
    val onNext: () -> Unit,
    val skipped: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val position: String,
)
data class QuickProgress(val text: String?, val open: () -> Unit, val reserveHeaderSpace: Boolean = false)
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

fun quickBlocksNewRun(state: QuickJobState) = state == QuickJobState.Queued || state == QuickJobState.Running || state == QuickJobState.Review

fun quickPreviousResult(states: List<QuickJobState>): String? {
    if (quickSubmissionActive(states)) return null
    val label = when(states.lastOrNull()) {
        QuickJobState.Done -> "上一单已提交"
        QuickJobState.Failed -> "上一单提交失败"
        QuickJobState.Review -> "上一单结果待核对"
        else -> return null
    }
    return "$label · ${states.count { it == QuickJobState.Done }}/${states.size}"
}

fun quickSubmissionActive(states: List<QuickJobState>) = states.any { it == QuickJobState.Queued || it == QuickJobState.Running }

fun quickCompletionReady(begun: Boolean, hasCurrent: Boolean, busy: Boolean, acknowledged: Boolean) =
    begun && !hasCurrent && !busy && !acknowledged

data class QuickRunSummary(val succeeded: Int, val failed: Int, val review: Int, val skipped: Int, val signFailed: Int) {
    val message get() = "成功提交 $succeeded 单，跳过 $skipped 单。" +
        (if (failed > 0) "\n提交未完成 $failed 单，草稿已保留。" else "") +
        (if (review > 0) "\n另有 $review 单结果需要核对，未自动重复提交。" else "") +
        (if (signFailed > 0) "\n有 $signFailed 单未完成签到，请稍后核对。" else "")
}
fun quickRunSummary(states: List<QuickJobState>, skipped: Int, signFailed: Int) = QuickRunSummary(
    states.count { it == QuickJobState.Done }, states.count { it == QuickJobState.Failed },
    states.count { it == QuickJobState.Review }, skipped, signFailed)

private data class QuickFormPage(val item: BatchItem, val index: Int, val total: Int)

@Composable
fun QuickFinishScreen(model: QuickFinishViewModel, onBack: () -> Unit) {
    val actor = LocalSession.current
    val focus = LocalFocusManager.current
    val progress = LocalQuickProgress.current
    val current = model.current
    val currentPage = current?.let { QuickFormPage(it, model.position + 1, model.items.size) }
    var lastPage by remember { mutableStateOf(currentPage) }
    if (currentPage != null) lastPage = currentPage
    val page = currentPage ?: lastPage
    LaunchedEffect(current?.id, model.begun) { if (model.begun && current == null) onBack() }
    if (page != null && model.begun && !model.signing) {
        val change = updateTransition(page, label = "quick form page")
        val moving = change.currentState != change.targetState || change.isRunning
        CompositionLocalProvider(LocalQuickProgress provides QuickProgress(model.formBadge, progress.open, reserveHeaderSpace = true),
            LocalOperationLocked provides moving) {
            Box(Modifier.fillMaxSize().clipToBounds()) {
                change.AnimatedContent(transitionSpec = {
                    (slideIntoContainer((if (targetState.index >= initialState.index) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right), tween(Motion.Normal, easing = Motion.Arrive)) togetherWith
                        slideOutOfContainer((if (targetState.index >= initialState.index) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right), tween(Motion.Normal, easing = Motion.Leave)))
                        .using(SizeTransform(clip = false))
                }, contentKey = { it.item.id }, modifier = Modifier.fillMaxSize()) { shown ->
                    // Capture the outgoing page and its position until its exit animation finishes.
                    RepairFormScreen(shown.item.id, onClose = onBack, onSubmitted = {}, quick = QuickRepairContext(
                        restored = model.editFor(shown.item.id), retainEdit = { model.retainEdit(shown.item.id, it) },
                        prepare = { model.prepare(shown.item, actor) },
                        enqueue = { prepared, data, choices, draft ->
                            model.enqueue(prepared, data, choices, actor, draft)
                            focus.clearFocus()
                        },
                        rememberApprovers = model::rememberApprovers,
                        onSkip = { if (model.current?.id == shown.item.id) { focus.clearFocus(); model.skip() } },
                        onPrevious = { focus.clearFocus(); model.previous() },
                        onFinish = model::finish, onNext = model::next, skipped = shown.item.id in model.skipped, hasPrevious = shown.index > 1, hasNext = shown.index < shown.total,
                        position = "${shown.index}/${shown.total}",
                    ))
                }
                // While two forms are on screen, neither can receive an accidental second submission.
                if (moving) Box(Modifier.matchParentSize().pointerInput(Unit) {
                    awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
                })
            }
        }
    }
}

/** Starting and finishing are dialogs over the existing page; only filling an order opens a route. */
@Composable
fun QuickFinishDialogs(model: QuickFinishViewModel, openForm: () -> Unit, openOrder: (String) -> Unit) {
    val actor = LocalSession.current
    if (model.confirming) GlassAlertDialog(onDismissRequest = model::cancelConfirmation,
        title = { Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开始快速完成？", Modifier.weight(1f)); OperationAccountAction()
        } },
        text = { Text("已读取全部待办：待签到 ${model.items.count { it.group == "SIGNED" }} 单，待完成 ${model.items.count { it.group == "REPAIRED" }} 单。\n" +
            if (model.items.any { it.group == "SIGNED" }) "继续会先一键签到，再逐单填写；每单可以跳过。" else "继续后逐单填写，每单可以跳过。") },
        confirmButton = { GlassTextButton(onClick = { model.begin(actor, openForm) }, enabled = model.items.isNotEmpty()) { Text("继续处理") } },
        dismissButton = { GlassTextButton(onClick = model::cancelConfirmation) { Text("取消") } })
    if (model.completionReady) GlassAlertDialog(onDismissRequest = model::acknowledgeCompletion,
        title = { Text("快速处理完成") }, text = { Text(model.summary.message) },
        confirmButton = { GlassTextButton(onClick = model::acknowledgeCompletion) { Text("知道了") } },
        dismissButton = if (model.summary.failed + model.summary.review + model.summary.signFailed > 0) ({
            GlassTextButton(onClick = { model.acknowledgeCompletion(); model.showProgress = true }) { Text("查看未完成") }
        }) else null)
    if (model.showProgress && !model.completionReady) GlassAlertDialog(onDismissRequest = { model.showProgress = false },
        title = { Text("提交进度") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(if (model.loading) "正在读取全部待办…" else if (model.signing) model.signProgress else model.summary.message)
                    if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (!model.signing && !model.loading) OperationAccountAction()
                }
                model.jobs.filter { it.state == QuickJobState.Failed || it.state == QuickJobState.Review }.forEach { job -> item {
                    Text(job.item.name, style = MaterialTheme.typography.titleSmall)
                    Text(job.message, style = MaterialTheme.typography.bodySmall)
                    Row {
                        if (job.state == QuickJobState.Failed && !job.wrote) GlassTextButton(onClick = { model.retry(job, actor) }) { Text("重试") }
                        GlassTextButton(onClick = { model.showProgress = false; openOrder(job.item.id) }) { Text("核对工单") }
                    }
                } }
                model.signFailures.forEach { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
            }
        },
        confirmButton = { GlassTextButton(onClick = { model.showProgress = false }) { Text("关闭") } })
    model.failure?.let { ErrorDialog("快速完成提示", it) { model.failure = null } }
}
