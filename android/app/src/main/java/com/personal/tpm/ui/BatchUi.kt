package com.personal.tpm.ui

import androidx.compose.foundation.clickable
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import com.personal.tpm.ui.pageVerticalScroll as verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.personal.tpm.data.BATCH_LIMIT
import com.personal.tpm.data.BatchAction
import com.personal.tpm.data.BatchChoice
import com.personal.tpm.data.BatchItem
import com.personal.tpm.data.BatchMaterial
import com.personal.tpm.data.BatchOperation
import com.personal.tpm.data.BatchOutboundLine
import com.personal.tpm.data.BatchPreset
import com.personal.tpm.data.BatchProcessor
import com.personal.tpm.data.BatchRun
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.PickerPrefs
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.Session
import com.personal.tpm.data.batchGroupName
import com.personal.tpm.data.isChosenApprover
import com.personal.tpm.data.materialQuantity
import com.personal.tpm.data.materialUnit
import com.personal.tpm.data.missingAssigneeMessage
import com.personal.tpm.data.outboundLineIdentity
import com.personal.tpm.data.quantityText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The ERP order form as it opens (the person's own details and the approvers of an outbound order), so the material can be set up once. */
class ErpSetup(val data: JSONObject, val nodes: List<Jv>, val selections: Map<String, List<String>>)

/** What the preset sheet opens with: the approver nodes the first selected order leads to. */
class PresetRequest(val action: BatchAction, val operation: BatchOperation, val kind: OrderKind, val nodes: List<Jv>, val selections: Map<String, List<String>>, val erp: ErpSetup?, val items: List<BatchItem>)

/**
 * The selection of a list in selection mode, and everything the batch needs around it. Orders of one status only (one batch action does the same
 * thing to each), at most [BATCH_LIMIT]. A tap on a card while selecting opens a read-only peek, so the selection and the loaded list stay as
 * they were.
 */
@Stable
class BatchSelection {
    val operationKey = "batch:" + java.util.UUID.randomUUID()
    var active by mutableStateOf(false); private set
    val items = mutableStateListOf<BatchItem>()
    /** Said in a snackbar: why a card cannot be selected. */
    var notice by mutableStateOf<String?>(null)
    /** The order being looked at in the peek. Showing it changes nothing about the selection or the list. */
    var peeking by mutableStateOf<BatchItem?>(null)
    var presetting by mutableStateOf<PresetRequest?>(null)
    var preparing by mutableStateOf(false); private set
    var run by mutableStateOf<BatchRun?>(null)

    val group: String? get() = items.firstOrNull()?.group
    val action: BatchAction? get() = group?.let { BatchAction.forGroup(it) }
    val groupTitle: String get() = group?.let { batchGroupName(it) } ?: "点左侧圆圈选择单据"

    fun begin() { active = true }
    fun end() { active = false; items.clear(); peeking = null }
    fun clear() { items.clear() }
    fun contains(item: BatchItem) = items.any { it.key == item.key }
    /** Whether the circle of this card works: its status has a batch action and matches what is already selected. */
    fun isSelectable(item: BatchItem): Boolean {
        if (item.id.isEmpty()) return false
        if (BatchAction.forGroup(item.group) == null) return false
        val current = group
        return current == null || current == item.group
    }
    fun toggle(item: BatchItem) {
        if (item.id.isEmpty()) { notice = "这张单据没有编号，请刷新列表"; return }
        val index = items.indexOfFirst { it.key == item.key }
        if (index >= 0) { items.removeAt(index); return }
        if (BatchAction.forGroup(item.group) == null) { notice = "「" + batchGroupName(item.group) + "」的单据暂不支持批量处理"; return }
        val current = group
        if (current != null && current != item.group) { notice = "只能选择同一种状态的单据：已选「" + batchGroupName(current) + "」，不能再选「" + batchGroupName(item.group) + "」"; return }
        if (items.size >= BATCH_LIMIT) { notice = "一次最多选择 $BATCH_LIMIT 张单据"; return }
        items.add(item)
    }
    /** Selects the loaded orders of the status already chosen (or of the first order that can be selected). */
    fun selectAll(candidates: List<BatchItem>) {
        val target = group ?: candidates.firstOrNull { BatchAction.forGroup(it.group) != null }?.group
        if (target == null) { notice = "没有可批量处理的单据"; return }
        var full = false
        for (candidate in candidates) {
            if (candidate.id.isEmpty() || candidate.group != target || contains(candidate)) continue
            if (items.size >= BATCH_LIMIT) { full = true; break }
            items.add(candidate)
        }
        if (full) notice = "一次最多选择 $BATCH_LIMIT 张单据，已选满"
    }
    fun remove(ids: Set<String>) { items.removeAll { it.id in ids } }

    /**
     * Reads, from the first selected order, who the next node's approver has to be, then opens the preset sheet when anything has to be answered
     * (accept and complete always; the others only if an approver is missing). Otherwise the run starts at once.
     */
    suspend fun prepare(operation: BatchOperation, session: Session, prefs: PickerPrefs) {
        val chosenAction = action ?: return
        val first = items.firstOrNull() ?: return
        val snapshot = items.toList()
        if (preparing) return
        // Sending back needs only the reason: no approvers, no other answers.
        if (operation == BatchOperation.Reject) { presetting = PresetRequest(chosenAction, operation, first.kind, emptyList(), emptyMap(), null, snapshot); return }
        preparing = true
        try {
            val setup = try { BatchProcessor.approverSetup(first, session, prefs) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { notice = "读取审批人失败：" + (e.message ?: ""); return }
            val erp = if (first.poolPrefix.isEmpty() && chosenAction == BatchAction.Complete) erpSetup(session, prefs) else null
            if (!active || items.toList() != snapshot) { notice = "已选单据发生变化，请重新开始批量处理"; return }
            val choosable = setup.first.filter { isChosenApprover(it) }
            if (chosenAction.needsPreset || missingAssigneeMessage(choosable, setup.second) != null) presetting = PresetRequest(chosenAction, BatchOperation.Proceed, first.kind, choosable, setup.second, erp, snapshot)
            else run = BatchRun(chosenAction, BatchOperation.Proceed, snapshot, BatchPreset())
        } finally { preparing = false }
    }

    private suspend fun erpSetup(session: Session, prefs: PickerPrefs): ErpSetup? {
        val form = FormState(OrderKind.Erp)
        form.load(session, null)
        if (!form.ready) return null
        form.applyDefaults(session, prefs)
        return ErpSetup(form.data, form.nodes, form.selections)
    }
}

/** The "+ 新建…" button at the bottom right of a list, fixed in place; it shrinks away when selection starts and returns a moment after it ends. */
@Composable
fun NewButton(visible: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), Alignment.BottomEnd) {
        androidx.compose.animation.AnimatedVisibility(
            visible = visible,
            enter = androidx.compose.animation.scaleIn(tween(Motion.Normal, delayMillis = 200)) + androidx.compose.animation.fadeIn(tween(Motion.Normal, delayMillis = 200)),
            exit = androidx.compose.animation.scaleOut(tween(Motion.Fast, easing = Motion.Change)) + androidx.compose.animation.fadeOut(tween(Motion.Fast, easing = Motion.Change)),
        ) {
            if (AppPreferences.liquidGlass) GlassButton(onClick, prominent = true) {
                Icon(Icons.Filled.Add, contentDescription = null)
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp)); AnimatedLabel(label)
            } else androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = onClick, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                icon = { Icon(Icons.Filled.Add, contentDescription = null) }, text = { AnimatedLabel(label) },
            )
        }
    }
}

/**
 * Brings the batch bar up from the bottom when selection starts and takes it back down when it ends. The room it takes grows and shrinks with it
 * (so the list above gives way smoothly instead of being re-fitted in one step), and the bar slides and fades in the same eased motion; with
 * a slide alone the bar's room appeared at once and the motion was lost against it.
 */
@Composable
fun BatchBarHost(active: Boolean, content: @Composable () -> Unit) {
    val transition = updateTransition(active, label = "batchBar")
    val progress by transition.animateFloat(
        transitionSpec = { tween(if (targetState) Motion.Slow else Motion.Normal, easing = if (targetState) Motion.Arrive else Motion.Leave) },
        label = "batchBarProgress",
    ) { if (it) 1f else 0f }
    val present = active || transition.currentState || transition.isRunning
    // The system bar stays transparent. This app surface, not the window's system-bar
    // color, owns everything behind it while selection is entering, visible or leaving.
    // Draw before padding so the surface includes the navigation inset; buttons do not.
    Box(Modifier.fillMaxWidth()
        .background(if (present && !AppPreferences.liquidGlass) MaterialTheme.colorScheme.background else Color.Transparent)
        .then(if (present) Modifier.navigationBarsPadding() else Modifier)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBarInsetsHandled provides true) {
            // Keep the content alive through the complete exit, including quick reversals.
            if (present) {
                Box(Modifier.drawWithContent {
                    // Preserve the top fade, but do not let exiting buttons slide into
                    // the gesture area, which belongs to the opaque host surface.
                    val contentScope = this
                    if (progress < 1f) clipRect(top = -22.dp.toPx(), bottom = size.height) { contentScope.drawContent() }
                    else drawContent()
                }.layout { measurable, constraints ->
                    val child = measurable.measure(constraints.copy(minHeight = 0))
                    layout(child.width, (child.height * progress).roundToInt()) {
                        child.placeRelative(0, 0)
                    }
                }.graphicsLayer {
                    alpha = progress
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }) { content() }
            }
        }
    }
}

/** Set by [BatchBarHost]: the space for the navigation bar is already kept outside, so the bar does not add it again. */
val LocalBarInsetsHandled = androidx.compose.runtime.compositionLocalOf { false }

/** What a card needs to take part in selection. */
class CardSelecting(val selected: Boolean, val enabled: Boolean, val toggle: () -> Unit, val peek: () -> Unit)

// ---- the bar ----

/** The bar at the bottom of a list in selection mode: the count and the status, "全选", and the two ways out of the step as full-width buttons. */
@Composable
fun BatchBar(batch: BatchSelection, candidates: () -> List<BatchItem>) {
    OperationAccountScope(batch.operationKey) { BatchBarContent(batch, candidates) }
}
@Composable
private fun BatchBarContent(batch: BatchSelection, candidates: () -> List<BatchItem>) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    GlassBottomSurface() {
        Column {
        // While the first order is read (who approves next, the ERP form), a thin bar runs here. The buttons keep their look: greying them out and
        // changing their words for that second was the flash on tapping 批量完成.
        Box(Modifier.fillMaxWidth()) { if (batch.preparing) AppProgressIndicator(Modifier.fillMaxWidth()) }
        Column(Modifier.fillMaxWidth().then(if (LocalBarInsetsHandled.current) Modifier else Modifier.navigationBarsPadding()).padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    AnimatedLabel("已选 ${batch.items.size}/$BATCH_LIMIT", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.animation.Crossfade(batch.groupTitle, animationSpec = tween(Motion.Normal, easing = Motion.Change), label = "groupTitle") { title ->
                        AnimatedLabel(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                OperationAccountAction(locked = batch.preparing || batch.run != null)
                Box {
                    SoftButton(onClick = { menu = true }) { Text("全选") }
                    AppOptionSheet(expanded = menu, onDismissRequest = { menu = false }) {
                        AppOptionItem(text = { Text("全选已加载的单据") }, onClick = { menu = false; batch.selectAll(candidates()) })
                        AppOptionItem(text = { Text("清空已选") }, onClick = { menu = false; batch.clear() })
                    }
                }
            }
            // The two buttons are always on the bar: grey and disabled while nothing is picked (with the words of the last step, or general ones before
            // the first pick), in colour once something is. The bar never changes height, so nothing moves when the first order is picked or the last
            // one dropped.
            var shownAction by remember { mutableStateOf(batch.action) }
            batch.action?.let { shownAction = it }
            val action = shownAction
            Row(horizontalArrangement = Arrangement.spacedBy(BarSpec.Gap)) {
                SoftButton(
                    onClick = { scope.launch { batch.prepare(BatchOperation.Reject, session, prefs) } },
                    enabled = batch.items.isNotEmpty(), modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight),
                    contentColor = MaterialTheme.colorScheme.error,
                ) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, Modifier.padding(end = 6.dp)); AnimatedLabel(action?.rejectTitle ?: "批量退单", maxLines = 1) }
                GlassActionButton(
                    onClick = { scope.launch { batch.prepare(BatchOperation.Proceed, session, prefs) } },
                    enabled = batch.items.isNotEmpty(), modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight),
                ) { AnimatedLabel(action?.title ?: "批量处理", maxLines = 1) }
            }
        }
        }
    }
}

// ---- the overlays: preset, run, peek ----

/** Put once in a list screen that has a [BatchSelection]: the preset sheet, the progress of a run, the read-only peek. */
@Composable
fun BatchOverlays(batch: BatchSelection) {
    OperationAccountScope(batch.operationKey) { BatchOverlaysContent(batch) }
}
@Composable
private fun BatchOverlaysContent(batch: BatchSelection) {
    val session = LocalSession.current
    val accounts = LocalOperationAccounts.current
    LaunchedEffect(batch.active) { if (!batch.active && batch.run == null) accounts?.clear(batch.operationKey) }
    DisposableEffect(batch.operationKey) { onDispose { accounts?.clear(batch.operationKey) } }
    batch.presetting?.let { request ->
        CompactTheme { BatchPresetSheet(
            request, request.items.size, onDismiss = { batch.presetting = null },
            onStart = { preset -> batch.presetting = null; batch.run = BatchRun(request.action, request.operation, request.items, preset) },
        ) }
    }
    SlideUpOverlayFor(batch.run) { run ->
        androidx.compose.runtime.CompositionLocalProvider(LocalOperationLocked provides true) { CompactTheme { BatchRunDialog(run) {
            // The orders that went through leave the selection; the ones that failed stay selected for another try.
            batch.remove(run.succeeded.toSet())
            if (batch.items.isEmpty()) batch.end()
            batch.run = null
            accounts?.clear(batch.operationKey)
            if (run.succeeded.isNotEmpty()) session.notifyChanged()
        } } }
    }
    // Laid over the page inside the same window (a dialog window has its own insets and put the buttons under the navigation bar). It slides in and
    // out like every other page.
    androidx.activity.compose.BackHandler(enabled = batch.peeking != null) { batch.peeking = null }
    SlideInOverlayFor(batch.peeking) { item ->
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (item.poolPrefix.isNotEmpty()) PoolOrderDetail(PoolKind.byPath(item.poolPrefix)!!, item.id, onClose = { batch.peeking = null }, readOnly = true)
            else if (item.kind == OrderKind.Fault) FaultDetailScreen(item.id, onBack = { batch.peeking = null }, onOpenFlow = {}, onOpenTask = {}, peeking = true)
            else ErpDetailScreen(item.id, onBack = { batch.peeking = null }, onOpenFlow = {}, onOpenTask = {}, peeking = true)
        }
    }
}

/** The answers every order of the batch gets. Chosen once, applied to each order as the single form would. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchPresetSheet(request: PresetRequest, count: Int, onDismiss: () -> Unit, onStart: (BatchPreset) -> Unit) {
    if (request.items.firstOrNull()?.poolPrefix?.isNotEmpty() == true) {
        PoolBatchPreset(request, onDismiss, onStart)
        return
    }
    val action = request.action
    val operation = request.operation
    var preset by remember {
        mutableStateOf(
            BatchPreset(
                approverNodes = request.nodes, approvers = request.selections,
                erpData = request.erp?.data ?: JSONObject(), erpRecipient = Jv(request.erp?.data?.opt("recipientUser")), erpDept = Jv(request.erp?.data?.opt("dept")),
                erpDivision = Jv(request.erp?.data?.opt("division")), erpNodes = request.erp?.nodes ?: emptyList(), erpApprovers = request.erp?.selections ?: emptyMap(),
            ),
        )
    }
    var pick by remember { mutableStateOf<PickRequest?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val prefix = "assignee.task." + request.kind.prefix

    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Text(
                if (operation == BatchOperation.Reject) action.rejectTitle else action.title,
                Modifier.zIndex(1f).fadeBelow(MaterialTheme.colorScheme.background).background(MaterialTheme.colorScheme.background).fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleLarge,
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (operation == BatchOperation.Reject) {
                    TextEntry(action.rejectWord + "原因", preset.comment, { preset = preset.copy(comment = it) }, limit = 255, minLines = 3, required = true, placeholder = "请输入${action.rejectWord}原因")
                    AnimatedLabel("所选 $count 张单据都会${action.rejectWord}，并写上这条原因。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    if (action == BatchAction.Accept) {
                        FormTitle("受理方案")
                        GroupCard {
                            PickRow("维修班组（不选则用各单自带）", preset.group.label, false) { pick = PickRequest(RefSource.Groups, { preset = preset.copy(group = it) }) }
                            DateTimeField("预计完成时间", preset.estimated, dateOnly = false, required = true) { preset = preset.copy(estimated = it) }
                            PickRow("维修人员（选填）", preset.assignUser.label, false) { pick = PickRequest(RefSource.Users, { preset = preset.copy(assignUser = it) }) }
                        }
                        AnimatedLabel("所选 $count 张单据都按这个方案受理，各单的故障类型不变；维修班组不选时，用各单原来带的班组。不选维修人员就是派单，选了就按“指派”处理。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (action == BatchAction.Complete) CompleteSections(request, preset, count, { preset = it }) { pick = it }
                    if (preset.needsApprovers(action)) {
                        FormTitle("审批人")
                        GroupCard {
                            AssigneeRows(preset.approverNodes, preset.approvers, prefix, { key, change -> preset = preset.copy(approvers = preset.approvers + (key to change(preset.approvers[key].orEmpty()))) }) { pick = it }
                        }
                        Text("下一步的审批人，所选单据都用这里选的人。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            val sheetColor = MaterialTheme.colorScheme.background
            Box(Modifier.zIndex(1f).fadeAbove(sheetColor).background(sheetColor)) {
            GlassActionButton(
                onClick = { scope.launch { sheetState.hide(); onStart(preset) } }, enabled = preset.isComplete(action, operation),
                colors = if (operation == BatchOperation.Reject) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical).height(BarSpec.ButtonHeight),
            ) { AnimatedLabel("开始处理 $count 张") }
            }
        }
    }
    PickHost(pick) { pick = null }
}

@Composable
private fun CompleteSections(request: PresetRequest, preset: BatchPreset, count: Int, set: (BatchPreset) -> Unit, pick: (PickRequest) -> Unit) {
    FormTitle("维修结果")
    GroupCard {
        PickRow("维修故障类型", preset.faultType.label, true) { pick(PickRequest(RefSource.FaultTypes, { set(preset.copy(faultType = it)) })) }
    }
    AnimatedLabel("所选 $count 张单据都按这个方案提交维修结果；每张单据原有的维修领料不变。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextEntry("工作说明", preset.content, { set(preset.copy(content = it)) }, limit = 255, minLines = 3, required = true, placeholder = "请输入工作说明")
    GroupCard {
        InputRow(
            "耗时", preset.durationMinutes?.toString().orEmpty(), { set(preset.copy(durationMinutes = it.filter(Char::isDigit).take(6).toIntOrNull())) },
            suffix = "分钟", keyboardType = KeyboardType.Number, placeholder = "留空则从签到时间算起",
        )
    }
    Text("填写后，每张单据的维修开始时间 = 现在往前推这么多分钟，结束时间为现在。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // Existing outbound lines: every order links the same lines, each in the same quantity.
    FormTitle("关联已有出库物料（选填）")
    GroupCard {
        LineAction(Icons.Filled.Inventory2, "添加已出库物料") {
            pick(PickRequest(
                RefSource.OutboundMaterials,
                { line -> if (preset.outboundLines.none { it.id == outboundLineIdentity(line) }) set(preset.copy(outboundLines = preset.outboundLines + BatchOutboundLine(line))) },
                multiple = true, selected = preset.outboundLines.map { it.id }.toSet(), onRemove = { line -> set(preset.copy(outboundLines = preset.outboundLines.filter { it.id != outboundLineIdentity(line) })) },
            ))
        }
        preset.outboundLines.forEachIndexed { index, entry ->
            HorizontalDivider()
            val left = entry.line["actualQuantity"].number - entry.line["usedQuantity"].number
            QuantityLine(
                entry.line["material"]["materialDesc"].text,
                listOf(entry.line["outbound"]["outNumber"].text, entry.line["material"]["materialCode"].text.takeIf { it.isNotEmpty() }?.let { "料号 $it" }.orEmpty(), "现可用 " + materialQuantity(quantityText(left), entry.line["material"])).filter { it.isNotEmpty() }.joinToString(" · "),
                "每单使用数量", entry.quantity, materialUnit(entry.line["material"]),
                { q -> set(preset.copy(outboundLines = preset.outboundLines.mapIndexed { i, l -> if (i == index) l.copy(quantity = q) else l })) },
                { set(preset.copy(outboundLines = preset.outboundLines.filterIndexed { i, _ -> i != index })) },
            )
        }
    }
    Text("所选每张单据都使用这些出库物料、同样的数量。可用数量不够时会弹窗，让你选择跳过这张单、不领料继续或停止。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    // New ERP material: each order gets its own outbound order.
    FormTitle("ERP 领料（选填）")
    if (request.erp == null) {
        Text("没能读取 ERP 领料设置，这次不能批量领料。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    GroupCard {
        LineAction(Icons.Filled.Add, "添加领料物料") {
            pick(PickRequest(RefSource.Materials, { m -> if (preset.materials.none { it.material["id"] == m["id"] }) set(preset.copy(materials = preset.materials + BatchMaterial(m))) }, multiple = true, selected = preset.materials.map { it.id }.toSet(), onRemove = { m -> set(preset.copy(materials = preset.materials.filter { it.id != m["id"].text })) }))
        }
        preset.materials.forEachIndexed { index, line ->
            HorizontalDivider()
            QuantityLine(
                line.material["materialDesc"].text,
                listOf(line.material["materialCode"].text.takeIf { it.isNotEmpty() }?.let { "料号 $it" }.orEmpty(), "库存 " + materialQuantity(line.material["onhandQty"].text, line.material)).filter { it.isNotEmpty() }.joinToString(" · "),
                "每单数量", line.quantity, materialUnit(line.material),
                { q -> set(preset.copy(materials = preset.materials.mapIndexed { i, l -> if (i == index) l.copy(quantity = q) else l })) },
                { set(preset.copy(materials = preset.materials.filterIndexed { i, _ -> i != index })) },
            )
        }
    }
    Text("所选每张单据都领用这些物料、同样的数量：为每张单据各建一张出库单并关联到维修。处理中发现账上物料不够或其他问题，会弹窗让你选择跳过、不领料继续或停止。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (preset.materials.isNotEmpty()) {
        FormTitle("领料单信息")
        GroupCard {
            PickRow("项目号", listOf(preset.erpProject["projectCode"].text, preset.erpProject["projectName"].text).filter { it.isNotEmpty() }.joinToString(" "), true) { pick(PickRequest(RefSource.Projects, { set(preset.copy(erpProject = it)) })) }
            PickRow("领用人", preset.erpRecipient.label, true) { pick(PickRequest(RefSource.Users, { set(preset.copy(erpRecipient = it)) }, "erp.recipientUser")) }
            PickRow("领用部门", preset.erpDept.label, true) { pick(PickRequest(RefSource.Departments, { set(preset.copy(erpDept = it)) }, "erp.dept")) }
            PickRow("ERP记账部门", preset.erpDivision.label, true) { pick(PickRequest(RefSource.Divisions, { set(preset.copy(erpDivision = it)) }, "erp.division")) }
        }
        TextEntry("用途", preset.erpRemark, { set(preset.copy(erpRemark = it)) }, limit = 140, minLines = 2, required = true, placeholder = "请输入用途")
        if (preset.erpNodes.any { isChosenApprover(it) }) {
            FormTitle("领料单审批人")
            GroupCard { AssigneeRows(preset.erpNodes, preset.erpApprovers, "assignee.erp", { key, change -> set(preset.copy(erpApprovers = preset.erpApprovers + (key to change(preset.erpApprovers[key].orEmpty())))) }, pick) }
        }
    }
}

@Composable
private fun LineAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        AnimatedLabel(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun QuantityLine(title: String, subtitle: String, label: String, quantity: Double, unit: String, onQuantity: (Double) -> Unit, onDelete: () -> Unit) {
    var text by remember(title, subtitle) { mutableStateOf(quantityText(quantity)) }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                AnimatedLabel(title, style = MaterialTheme.typography.titleSmall)
                AnimatedLabel(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassIconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { InputRow(label, text, { text = it; onQuantity(it.toDoubleOrNull() ?: 0.0) }, suffix = unit) }
        }
    }
}

/**
 * The progress of a run: how many orders there are, how many are done, how many went through and how many failed. Failures are only reported here.
 * The "完成" button is always on screen; it works once the run is over. The screen stays awake while it runs.
 */
@Composable
private fun BatchRunDialog(run: BatchRun, onClose: () -> Unit) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    var closing by remember(run) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(run) { run.start(session, prefs) }
    // The page is laid over the screen inside the same window, so the status bar and the navigation bar are handled as everywhere else. No way
    // out with the back key while it runs: the "完成" button is the way out.
    androidx.activity.compose.BackHandler(enabled = true) {}
    Box(Modifier.fillMaxSize()) {
        GlassScaffold(topBar = { AppTopBar(title = run.title) },
            bottomBar = { GlassBottomSurface {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = BarSpec.Horizontal, vertical = BarSpec.Vertical), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (run.running) SoftButton(onClick = { run.stop() }, Modifier.weight(1f).height(48.dp)) { Text("停止") }
                    GlassActionButton(onClick = { closing = true; onClose() }, enabled = !run.running && !closing, modifier = Modifier.weight(1f).height(BarSpec.ButtonHeight)) { Text("完成") }
                }
            } }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GroupCard {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppProgressIndicator(progress = { run.processed.toFloat() / maxOf(run.total, 1) }, Modifier.fillMaxWidth())
                            Row {
                                AnimatedLabel(runHeadline(run), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                                AnimatedLabel("${run.processed}/${run.total}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            run.current?.let {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("正在处理", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    AnimatedLabel(it.name + if (it.reference.isEmpty()) "" else " · ${it.reference}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            run.endedEarly?.let { AnimatedLabel(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    FormTitle("统计")
                    GroupCard {
                        FactRow("全部", run.total.toString())
                        FactRow("已处理", run.processed.toString())
                        FactRow("完成", run.succeeded.size.toString())
                        FactRow("跳过", run.skipped.size.toString())
                        FactRow("失败", run.failures.size.toString())
                    }
                    if (run.skipped.isNotEmpty()) {
                        FormTitle("跳过的单据")
                        GroupCard { run.skipped.forEach { item -> ResultRow(item.name, item.reference, item.message, MaterialTheme.colorScheme.tertiary) { clipboard.setText(AnnotatedString(item.name + "：" + item.message)) } } }
                    }
                    if (run.failures.isNotEmpty()) {
                        FormTitle("失败的单据")
                        GroupCard { run.failures.forEach { item -> ResultRow(item.name, item.reference, item.message, MaterialTheme.colorScheme.error) { clipboard.setText(AnnotatedString(item.name + "：" + item.message)) } } }
                    }
                }
        }
        run.problem?.let { problem ->
            var applyToRest by remember(problem) { mutableStateOf(false) }
            Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) { TransparentSystemBars(); EnsureScaled {
                GlassPanelSurface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        AnimatedLabel(problem.title, style = MaterialTheme.typography.titleLarge)
                        AnimatedLabel(problem.order.name + if (problem.order.reference.isEmpty()) "" else " · ${problem.order.reference}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer { AnimatedLabel(problem.message, Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyLarge) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("后面遇到同样的情况，都这样处理", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            GlassSwitch(checked = applyToRest, onCheckedChange = { applyToRest = it })
                        }
                        if (problem.canSkipShort) {
                            GlassActionButton(onClick = { run.answer(BatchChoice.SkipShortages, applyToRest) }, Modifier.fillMaxWidth().height(48.dp)) { Text("跳过不够的物料，其余照常领料") }
                            SoftButton(onClick = { run.answer(BatchChoice.WithoutMaterials, applyToRest) }, Modifier.fillMaxWidth().height(48.dp)) { Text("这张单都不领料，继续完成") }
                        } else {
                            GlassActionButton(onClick = { run.answer(BatchChoice.WithoutMaterials, applyToRest) }, Modifier.fillMaxWidth().height(48.dp)) { Text("不领料，继续完成这张单") }
                        }
                        SoftButton(onClick = { run.answer(BatchChoice.Skip, applyToRest) }, Modifier.fillMaxWidth().height(48.dp)) { Text("跳过这张单") }
                        SoftButton(
                            onClick = { run.answer(BatchChoice.Stop, false) }, Modifier.fillMaxWidth().height(BarSpec.ButtonHeight),
                            contentColor = MaterialTheme.colorScheme.error,
                        ) { Text("停止批量处理") }
                    }
                }
                }
            }
        }
    }
}

private fun runHeadline(run: BatchRun): String {
    if (run.running) return "正在处理…"
    if (run.endedEarly != null) return "已结束"
    val parts = listOfNotNull(
        if (run.skipped.isEmpty()) null else "${run.skipped.size} 张跳过", if (run.failures.isEmpty()) null else "${run.failures.size} 张失败",
    )
    return if (parts.isEmpty()) "全部处理完成" else "处理完成，有 " + parts.joinToString("、")
}

@Composable
private fun ResultRow(name: String, reference: String, message: String, color: androidx.compose.ui.graphics.Color, onCopy: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AnimatedLabel(name, style = MaterialTheme.typography.titleSmall)
            if (reference.isNotEmpty()) AnimatedLabel(reference, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SelectionContainer { AnimatedLabel(message, style = MaterialTheme.typography.bodyMedium, color = color) }
        }
        GlassIconButton(onClick = onCopy) { Icon(Icons.Filled.ContentCopy, contentDescription = "复制") }
    }
}
