package com.personal.tpm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.personal.tpm.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.UUID

private const val OrdersPerPage = 10
private enum class TextQueryStep { Paste, Mark, Review, Reply, Revisit }

@Composable
fun RepairTextQueryScreen(onBack: () -> Unit, openOrder: (String) -> Unit) {
    val session = LocalSession.current
    val scope = session.draftScope
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    var text by rememberSaveable(scope) { mutableStateOf("") }
    val navigation = rememberNavController()
    var machineCodes by rememberSaveable(scope) { mutableStateOf(arrayListOf<String>()) }
    var replies by rememberSaveable(scope) { mutableStateOf(arrayListOf<String>()) }
    var choices by rememberSaveable(scope) { mutableStateOf(arrayListOf<String>()) }
    var reviewIndex by rememberSaveable(scope) { mutableIntStateOf(0) }
    var revisitIndex by rememberSaveable(scope) { mutableIntStateOf(0) }
    var manualLines by rememberSaveable(scope) { mutableStateOf(arrayListOf<String?>()) }
    var pendingChoice by rememberSaveable(scope) { mutableStateOf("") }
    var pendingReply by rememberSaveable(scope) { mutableStateOf("") }
    var confirmReplace by rememberSaveable(scope) { mutableStateOf(false) }
    // Destination lambdas are retained by the navigation graph; read the latest report and marks.
    val lines by rememberUpdatedState(remember(text) { text.lines() })
    val selected by rememberUpdatedState(remember(machineCodes) {
        machineCodes.indices.filter { machineCodes[it].isNotBlank() }
    })

    fun open(step: TextQueryStep) {
        navigation.navigate(step.name) { launchSingleTop = true }
    }

    fun returnToReply() {
        confirmReplace = false
        if (navigation.currentDestination?.route == TextQueryStep.Revisit.name) navigation.popBackStack()
    }

    fun identify() {
        machineCodes = ArrayList(lines.map(::reportMachineCode))
        choices = ArrayList(List(lines.size) { "" })
        replies = ArrayList(List(lines.size) { "" })
        manualLines = ArrayList(List(lines.size) { null })
        reviewIndex = 0
        open(TextQueryStep.Mark)
    }
    fun advance() {
        if (reviewIndex < selected.lastIndex) reviewIndex++ else open(TextQueryStep.Reply)
    }
    fun updateLine(line: Int, choice: String, reply: String) {
        choices = ArrayList(choices).apply { set(line, choice) }
        replies = ArrayList(replies).apply { set(line, reply) }
    }

    fun setManual(line: Int, value: String?) {
        manualLines = ArrayList(List(lines.size) { manualLines.getOrNull(it) }).apply { set(line, value) }
    }

    fun saveReviewedLine(replaceManual: Boolean) {
        val line = selected.getOrNull(revisitIndex) ?: return
        updateLine(line, pendingChoice, pendingReply)
        if (replaceManual) setManual(line, null)
        returnToReply()
    }

    fun openReplyLine(line: Int) {
        val position = selected.indexOf(line)
        if (position < 0) return
        revisitIndex = position
        pendingChoice = choices[line]
        pendingReply = replies[line]
        open(TextQueryStep.Revisit)
    }

    val duration = Motion.Page
    NavHost(
        navController = navigation,
        startDestination = TextQueryStep.Paste.name,
        enterTransition = { slideInHorizontally(tween(duration, easing = Motion.Arrive)) { it } },
        exitTransition = { slideOutHorizontally(tween(duration, easing = Motion.Leave)) { -it / 4 } },
        popEnterTransition = { slideInHorizontally(tween(duration, easing = Motion.Arrive)) { -it / 4 } },
        popExitTransition = { slideOutHorizontally(tween(duration, easing = Motion.Leave)) { it } },
    ) {
        TextQueryStep.entries.forEach { step ->
            composable(step.name) {
                val reviewingFromReply = step == TextQueryStep.Revisit
                val index = if (reviewingFromReply) revisitIndex else if (step == TextQueryStep.Review) reviewIndex else 0
                key(index) {
                    FormPage(
                        title = when (step) {
                            TextQueryStep.Paste, TextQueryStep.Mark -> "文本查询维修"
                            TextQueryStep.Review, TextQueryStep.Revisit -> "逐台核对 ${index + 1}/${selected.size}"
                            TextQueryStep.Reply -> "维修交期回复"
                        },
                        busy = false,
                        onClose = {
                            if (navigation.currentDestination?.route == step.name) {
                                if (step == TextQueryStep.Paste) onBack() else navigation.popBackStack()
                            }
                        },
                        snackbar = snackbar,
                        bottomBar = {
                            GlassBottomSurface {
                                val line = selected.getOrNull(index)
                                Row(
                                    Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    when (step) {
                                        TextQueryStep.Paste -> GlassActionButton(onClick = ::identify, enabled = text.isNotBlank(), modifier = Modifier.weight(1f)) { Text("识别机台号") }
                                        TextQueryStep.Mark -> GlassActionButton(onClick = { reviewIndex = 0; open(TextQueryStep.Review) }, enabled = selected.isNotEmpty(), modifier = Modifier.weight(1f)) {
                                            Text("开始核对（${selected.size} 行 · ${selected.map { normalizeReportMachineCode(machineCodes[it]) }.distinct().size} 台）")
                                        }
                                        TextQueryStep.Revisit -> {
                                            GlassTextButton(onClick = ::returnToReply, modifier = Modifier.weight(1f)) { Text("取消") }
                                            GlassActionButton(onClick = {
                                                if (line != null && manualLines.getOrNull(line) != null && pendingReply != replies[line]) {
                                                    confirmReplace = true
                                                } else {
                                                    saveReviewedLine(replaceManual = false)
                                                }
                                            }, modifier = Modifier.weight(1f)) { Text("保存并返回") }
                                        }
                                        TextQueryStep.Review -> {
                                            GlassTextButton(onClick = {
                                                if (line != null) updateLine(line, "", "")
                                                advance()
                                            }, modifier = Modifier.weight(1f)) { Text("跳过") }
                                            GlassTextButton(onClick = { open(TextQueryStep.Reply) }, modifier = Modifier.weight(1f)) { Text("就此完成") }
                                            GlassActionButton(onClick = ::advance, enabled = line != null && replies.getOrNull(line).orEmpty().isNotEmpty(),
                                                modifier = Modifier.weight(1f)) { Text(if (index == selected.lastIndex) "生成回复" else "下一条") }
                                        }
                                        TextQueryStep.Reply -> GlassActionButton(
                                            onClick = {
                                                clipboard?.setPrimaryClip(ClipData.newPlainText("维修交期", buildRepairReply(lines, replies, manualLines)))
                                                uiScope.launch { snackbar.showSnackbar("已复制回复") }
                                            }, modifier = Modifier.weight(1f),
                                        ) { Text("复制回复") }
                                    }
                                }
                            }
                        },
                    ) {
                        when (step) {
                            TextQueryStep.Paste -> {
                                OutlinedTextField(
                                    value = text,
                                    onValueChange = { text = it.take(24000) },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("原始报备") },
                                    placeholder = { Text("把群里的故障报备粘贴到这里") },
                                    minLines = 9,
                                    maxLines = 16,
                                    supportingText = { Text("${text.length}/24000 字符") },
                                )
                                TextButton(onClick = {
                                    val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)
                                    if (pasted != null) text = pasted.toString().take(24000)
                                }) { Text("从剪贴板粘贴") }
                            }
                            TextQueryStep.Mark -> MarkedReport(
                                lines, machineCodes, onCode = { line, code ->
                                    machineCodes = ArrayList(machineCodes).apply { set(line, code) }
                                    updateLine(line, "", "")
                                },
                            )
                            TextQueryStep.Review, TextQueryStep.Revisit -> selected.getOrNull(index)?.let { line ->
                                key(line) {
                                    MachineReview(
                                        original = lines[line], machine = normalizeReportMachineCode(machineCodes[line]),
                                        initialChoice = if (reviewingFromReply) pendingChoice else choices[line],
                                        onChange = { choice, reply ->
                                            if (reviewingFromReply) { pendingChoice = choice; pendingReply = reply }
                                            else updateLine(line, choice, reply)
                                        },
                                        previous = if (!reviewingFromReply && index > 0) ({ reviewIndex-- }) else null, openOrder = openOrder,
                                    )
                                }
                            }
                            TextQueryStep.Reply -> {
                                Text("已整理 ${selected.count { replies[it].isNotEmpty() }}/${selected.size} 行。点机台行回看核对，点右侧笔形图标改文字。")
                                ReplyLines(lines, replies, manualLines, selected.toSet(), ::openReplyLine, ::setManual)
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmReplace) {
        GlassAlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("这一行已有手动文字") },
            text = { Text("可以保留手动文字，或仅用新的核对结果替换这一行。") },
            confirmButton = { TextButton(onClick = { saveReviewedLine(replaceManual = true) }) { Text("使用核对结果") } },
            dismissButton = { TextButton(onClick = { saveReviewedLine(replaceManual = false) }) { Text("保留手动文字") } },
        )
    }
}

@Composable
private fun ReplyLines(
    lines: List<String>, replies: List<String>, manualLines: List<String?>, selected: Set<Int>,
    onReview: (Int) -> Unit, onEdit: (Int, String?) -> Unit,
) {
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var draft by rememberSaveable { mutableStateOf("") }

    fun edit(index: Int) {
        draft = repairReplyLine(lines[index], replies.getOrNull(index).orEmpty(), manualLines.getOrNull(index))
        editing = index
    }

    GroupCard {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            lines.forEachIndexed { index, original ->
                val line = repairReplyLine(original, replies.getOrNull(index).orEmpty(), manualLines.getOrNull(index))
                val canReview = index in selected
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line.ifEmpty { "空行" }, Modifier.weight(1f)
                        .clickable(role = Role.Button, onClickLabel = if (canReview) "核对机台" else "编辑这一行") {
                            if (canReview) onReview(index) else edit(index)
                        }.padding(horizontal = 8.dp, vertical = 6.dp),
                        color = if (line.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { edit(index) }) {
                        Icon(Icons.Filled.Edit, contentDescription = "编辑第 ${index + 1} 行")
                    }
                }
            }
        }
    }
    if (editing in lines.indices) {
        GlassAlertDialog(
            onDismissRequest = { editing = -1 },
            title = { Text("手动编辑第 ${editing + 1} 行") },
            text = {
                OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8,
                    label = { Text("回复内容") })
            },
            confirmButton = { TextButton(onClick = {
                val automatic = repairReplyLine(lines[editing], replies.getOrNull(editing).orEmpty())
                onEdit(editing, draft.takeUnless { it == automatic })
                editing = -1
            }) { Text("保存") } },
            dismissButton = {
                Row {
                    if (manualLines.getOrNull(editing) != null) {
                        TextButton(onClick = { onEdit(editing, null); editing = -1 }) { Text("恢复自动") }
                    }
                    TextButton(onClick = { editing = -1 }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun MarkedReport(lines: List<String>, machineCodes: List<String>, onCode: (Int, String) -> Unit) {
    var editing by rememberSaveable { mutableIntStateOf(-1) }
    var machine by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val markedColor = colors.primary.copy(alpha = 0.23f).compositeOver(colors.surfaceContainerLowest)
    Text("有底色的行会查询。点任意行可补填、修改或取消机台号。", style = MaterialTheme.typography.bodySmall)
    GroupCard {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lines.forEachIndexed { index, line ->
                val code = machineCodes.getOrNull(index).orEmpty()
                if (line.isBlank()) {
                    Spacer(Modifier.height(8.dp))
                } else {
                    Surface(
                        onClick = { editing = index; machine = code },
                        shape = MaterialTheme.shapes.medium,
                        color = if (code.isEmpty()) colors.surfaceContainerLowest else markedColor,
                        contentColor = colors.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(line)
                            if (code.isNotEmpty() && code != reportMachineCode(line)) {
                                Text("机台号：$code", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
    if (editing in lines.indices) {
        GlassAlertDialog(
            onDismissRequest = { editing = -1 },
            title = { Text("标注机台号") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(lines[editing])
                    OutlinedTextField(machine, { machine = it.take(32) }, label = { Text("机台号") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = machine.trim().isNotEmpty(), onClick = {
                    onCode(editing, machine.trim().uppercase()); editing = -1
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { onCode(editing, ""); editing = -1 }) { Text("取消标记") }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MachineReview(
    original: String,
    machine: String,
    initialChoice: String,
    onChange: (String, String) -> Unit,
    previous: (() -> Unit)?,
    openOrder: (String) -> Unit,
) {
    val session = LocalSession.current
    val context = LocalContext.current
    val presets = remember(context, session.draftScope) {
        context.getSharedPreferences("repair-text-presets-" + session.draftScope, Context.MODE_PRIVATE)
    }
    val restored = remember { Jv.parse(initialChoice) }
    var presetId by rememberSaveable { mutableStateOf(restored["preset"].text) }
    var children by rememberSaveable { mutableStateOf(restored["children"].text) }
    var planned by rememberSaveable { mutableStateOf(restored["planned"].text) }
    var presetList by remember { mutableStateOf(readReplyPresets(presets.getString("projects-v3", null), presets.getString("projects-v2", null), presets.getString("projects", null))) }
    var editingPresets by remember { mutableStateOf(false) }
    val current = presetList.firstOrNull { it.id == presetId }

    fun record(preset: ReplyPreset? = current, detail: String = children, time: String = planned) {
        presetId = preset?.id.orEmpty()
        children = detail
        planned = time
        val note = if (preset == null) "" else listOf(
            preset.name, detail, if (time.isNotBlank() && preset.acceptsPlan) "计划完成：$time" else "",
        ).filter(String::isNotBlank).joinToString("，")
        onChange(Jv.obj("preset" to presetId, "children" to detail, "planned" to time).toString(), note)
    }

    GroupCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(original, style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("查询机台：$machine", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                previous?.let { TextButton(onClick = it) { Text("上一条") } }
            }
        }
    }
    GroupCard {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("交期回复", style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { editingPresets = true }) { Text("编辑预设") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                presetList.forEach { option ->
                    FilterChip(selected = presetId == option.id, onClick = {
                        if (presetId == option.id) record(preset = null, detail = "")
                        else record(option, "", if (option.acceptsPlan) planned else "")
                    }, label = { Text(option.name) })
                }
            }
            AnimatedContent(
                targetState = current?.takeIf { it.children.isNotEmpty() },
                transitionSpec = { fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast)) },
                label = "交期回复子类",
            ) { option ->
                if (option != null) {
                    Column {
                        Text("子类", style = MaterialTheme.typography.labelLarge)
                        val selected = children.split("、").filter(String::isNotBlank)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            option.children.forEach { child ->
                                FilterChip(selected = child in selected, onClick = {
                                    if (current?.id == option.id) {
                                        val next = if (child in selected) selected - child else selected + child
                                        record(detail = next.joinToString("、"))
                                    }
                                }, label = { Text(child) })
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = current?.acceptsPlan != false,
                enter = expandVertically(tween(Motion.Normal)),
                exit = shrinkVertically(tween(Motion.Normal)),
            ) {
                PlannedTimeOptions(planned, presets) { record(time = it) }
            }
        }
    }
    MachineRecentOrders(machine, openOrder)
    if (editingPresets) {
        ReplyPresetEditor(presetList, onClose = { editingPresets = false }) { updated ->
            presets.edit().putString("projects-v3", JSONArray(updated.map { it.encode() }).toString()).apply()
            presetList = updated
            val kept = updated.firstOrNull { it.id == presetId }
            val keptChildren = children.split("、").filter { it in kept?.children.orEmpty() }.joinToString("、")
            record(kept, keptChildren)
            editingPresets = false
        }
    }
}

private val dailySlots = listOf("上午", "下午", "晚上", "夜班")
private val weekdays = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val defaultTimePresets = listOf(
    ReplyPreset("today", "今天", dailySlots),
    ReplyPreset("tomorrow", "明天", dailySlots),
    ReplyPreset("day-after", "后天", dailySlots),
    ReplyPreset("this-week", "本周", weekdays),
    ReplyPreset("next-week", "下周", weekdays),
)

private fun plannedLabel(group: ReplyPreset, child: String): String =
    formatRepairPlannedTime(group.name, child)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannedTimeOptions(planned: String, prefs: SharedPreferences, onChange: (String) -> Unit) {
    var groups by remember { mutableStateOf(decodeReplyPresets(prefs.getString("planned-projects", null)) ?: defaultTimePresets) }
    var editing by remember { mutableStateOf(false) }
    val selected = groups.firstOrNull { group ->
        group.name == planned || group.children.any { plannedLabel(group, it) == planned }
    }
    val selectedChild = selected?.children?.firstOrNull { plannedLabel(selected, it) == planned }.orEmpty()
    val exact = planned.takeIf { selected == null }.orEmpty()
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("计划完成时间", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            DateTimeField("计划完成时间", exact, dateOnly = false, required = false,
                trigger = { open -> TextButton(onClick = open) { Text(exact.ifBlank { "手动选择时间" }) } },
                onChange = onChange,
            )
            TextButton(onClick = { editing = true }) { Text("编辑预设") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            groups.forEach { group ->
                FilterChip(selected = selected?.id == group.id,
                    onClick = { onChange(if (selected?.id == group.id) "" else group.name) }, label = { Text(group.name) })
            }
        }
        AnimatedContent(
            targetState = selected?.takeIf { it.children.isNotEmpty() },
            transitionSpec = { fadeIn(tween(Motion.Fast)) togetherWith fadeOut(tween(Motion.Fast)) },
            label = "计划时间子项",
        ) { group ->
            if (group != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.children.forEach { child ->
                        FilterChip(selected = selected?.id == group.id && planned == plannedLabel(group, child),
                            onClick = {
                                if (selected?.id == group.id) {
                                    val label = plannedLabel(group, child)
                                    onChange(if (planned == label) group.name else label)
                                }
                            },
                            label = { Text(child) })
                    }
                }
            }
        }
    }
    if (editing) {
        ReplyPresetEditor(groups, onClose = { editing = false }, timePresets = true) { updated ->
            prefs.edit().putString("planned-projects", JSONArray(updated.map { it.encode() }).toString()).apply()
            groups = updated
            if (selected != null) {
                val kept = updated.firstOrNull { it.id == selected.id }
                onChange(if (kept == null) "" else plannedLabel(kept, selectedChild.takeIf { it in kept.children }.orEmpty()))
            }
            editing = false
        }
    }
}

private const val NotReported = "not-reported"
private const val Repaired = "repaired"
private data class ReplyPreset(val id: String, val name: String, val children: List<String> = emptyList()) {
    val acceptsPlan: Boolean get() = id != NotReported && id != Repaired
    fun encode() = Jv.obj("id" to id, "name" to name, "children" to JSONArray(children))
}

private fun decodeReplyPresets(text: String?): List<ReplyPreset>? {
    val parsed = text?.let { Jv.parse(it) }
    return if (parsed?.raw is JSONArray) {
        parsed.array.map { row -> ReplyPreset(row["id"].text, row["name"].text, row["children"].array.map { it.text }) }
    } else null
}

private fun readReplyPresets(stored: String?, previous: String?, legacy: String?): List<ReplyPreset> {
    decodeReplyPresets(stored)?.let { return it }
    val waitingMaterial = ReplyPreset("waiting-material", "待物料")
    val repaired = ReplyPreset(Repaired, "已修复", listOf("昨天", "前天"))
    fun List<ReplyPreset>.addMissing(option: ReplyPreset) =
        if (any { it.id == option.id || it.name.trim() == option.name }) this else this + option
    decodeReplyPresets(previous)?.let { return it.addMissing(repaired) }
    decodeReplyPresets(legacy)?.let { return it.addMissing(waitingMaterial).addMissing(repaired) }
    return listOf(
        ReplyPreset(NotReported, "未报修"),
        ReplyPreset("cnc", "已报CNC维修部", listOf("主轴", "X轴丝杆", "Y轴丝杆", "Z轴丝杆",
            "X轴导轨", "Y轴导轨", "Z轴导轨", "主轴电机")),
        ReplyPreset("in-progress", "处理中"),
        waitingMaterial,
        repaired,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReplyPresetEditor(
    presets: List<ReplyPreset>, onClose: () -> Unit, timePresets: Boolean = false, onSave: (List<ReplyPreset>) -> Unit,
) {
    val groupLabel = if (timePresets) "日期大类" else "项目"
    val childLabel = if (timePresets) "子项" else "子类"
    var drafts by remember { mutableStateOf(presets) }
    var editing by remember { mutableStateOf<ReplyPreset?>(null) }
    var childInput by remember { mutableStateOf("") }
    val item = editing
    val newChild = childInput.trim()
    fun edit(value: ReplyPreset) {
        childInput = ""
        editing = value
    }

    GlassAlertDialog(
        onDismissRequest = { if (item == null) onClose() else editing = null },
        title = { Text(if (item == null) {
            if (timePresets) "编辑计划时间预设" else "编辑交期回复预设"
        } else if (drafts.any { it.id == item.id }) "编辑$groupLabel" else "新增$groupLabel") },
        text = {
            key(item?.id) {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item == null) {
                        drafts.forEach { project ->
                            ListItem(
                                headlineContent = { Text(project.name) },
                                supportingContent = { Text(if (project.children.isEmpty()) "无$childLabel" else "${project.children.size} 个$childLabel") },
                                trailingContent = { Icon(Icons.Filled.Edit, contentDescription = "编辑 ${project.name}") },
                                modifier = Modifier.clickable(role = Role.Button) { edit(project) },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                            )
                        }
                        TextButton(onClick = { edit(ReplyPreset(UUID.randomUUID().toString(), "")) }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("新增$groupLabel")
                        }
                    } else {
                        OutlinedTextField(item.name, { editing = item.copy(name = it.take(60)) },
                            label = { Text("${groupLabel}名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Text(childLabel, style = MaterialTheme.typography.labelLarge)
                        if (item.children.isEmpty()) Text("还没有$childLabel", style = MaterialTheme.typography.bodySmall)
                        else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item.children.forEach { child ->
                                InputChip(selected = false, onClick = { editing = item.copy(children = item.children - child) },
                                    label = { Text(child) },
                                    trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "删除$childLabel $child", modifier = Modifier.size(16.dp)) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedTextField(childInput, { childInput = it.take(60) }, label = { Text("新增$childLabel") },
                                singleLine = true, modifier = Modifier.weight(1f))
                            IconButton(enabled = newChild.isNotBlank() && newChild !in item.children, onClick = {
                                editing = item.copy(children = item.children + newChild)
                                childInput = ""
                            }) { Icon(Icons.Filled.Add, contentDescription = "添加$childLabel") }
                        }
                        if (drafts.any { it.id == item.id }) {
                            TextButton(onClick = { drafts = drafts.filterNot { it.id == item.id }; editing = null }) {
                                Text("删除$groupLabel", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (item == null) {
                TextButton(enabled = drafts.isNotEmpty(), onClick = { onSave(drafts) }) { Text("保存预设") }
            } else {
                TextButton(enabled = item.name.isNotBlank(), onClick = {
                    val children = if (newChild.isNotBlank() && newChild !in item.children) item.children + newChild else item.children
                    val updated = item.copy(name = item.name.trim(), children = children)
                    drafts = if (drafts.any { it.id == item.id }) drafts.map { if (it.id == item.id) updated else it }
                    else drafts + updated
                    editing = null
                }) { Text("完成") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (item == null) onClose() else editing = null }) {
                Text(if (item == null) "取消" else "返回列表")
            }
        },
    )
}

@Composable
private fun MachineRecentOrders(machine: String, openOrder: (String) -> Unit) {
    val session = LocalSession.current
    var page by rememberSaveable(machine) { mutableIntStateOf(0) }
    var retry by remember { mutableIntStateOf(0) }
    var orders by remember { mutableStateOf<List<Jv>>(emptyList()) }
    var executors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var last by remember { mutableStateOf(true) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(machine, page, retry) {
        loading = true; failure = null; orders = emptyList(); executors = emptyMap()
        try {
            val offset = page * OrdersPerPage
            val result = session.readPage("fault/pageAssetFault", offset,
                listOf(filter(FaultSearchField.Machine.key, machine)), OrdersPerPage)
            require(result["content"].raw is org.json.JSONArray) { "工单列表返回格式异常" }
            val rows = result["content"].array
            orders = rows.filter { normalizeReportMachineCode(it["asset"]["addressCode"].text) == machine }
            val total = serverPageCount(result)
            last = rows.isEmpty() || result["last"].bool || (total != null && offset + rows.size >= total) ||
                (result["last"].isNull && total == null && rows.size < OrdersPerPage)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = e.message ?: "工单查询失败" }
        finally { loading = false }
    }
    LaunchedEffect(orders) {
        orders.filter { order ->
            order["faultId"].text.isNotBlank() && order["repairUser"].label.isBlank() &&
                order["faultState"].text in listOf("RECEIVED", "SIGNED", "REPAIRED", "VERIFIED", "RESOLVED", "FINISHED")
        }.chunked(3).forEach { chunk ->
            val result = coroutineScope {
                chunk.map { order ->
                    async {
                        val id = order["faultId"].text
                        try {
                            val repair = latestRepair(session.readRequest("repair/getRepairsByFaultId", mapOf("faultId" to id)))
                            val owner = repair?.get("assetFault")?.get("faultId")?.text.orEmpty()
                            id to (repair?.get("user")?.label.orEmpty().takeIf { owner.isBlank() || owner == id }.orEmpty())
                        } catch (e: CancellationException) { throw e }
                        catch (_: Exception) { id to "" }
                    }
                }.awaitAll()
            }
            executors = executors + result
        }
    }
    FormTitle("最近维修工单")
    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if (failure != null) {
        Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
        TextButton(onClick = { retry++ }) { Text("重试") }
    } else {
        if (orders.isEmpty()) Text("本页没有匹配工单，请核对机台号后再判断是否报修。")
        orders.forEach { order ->
            RecentRepairCard(order, executors[order["faultId"].text].orEmpty(), openOrder)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { page-- }, enabled = !loading && page > 0) { Text("上一页") }
        Text("第 ${page + 1} 页", Modifier.padding(top = 12.dp))
        TextButton(onClick = { page++ }, enabled = !loading && failure == null && !last) { Text("下一页") }
    }
}


@Composable
private fun RecentRepairCard(order: Jv, resolvedExecutor: String, openOrder: (String) -> Unit) {
    val status = faultStateLabel(order)
    val statusColor = stateColor(order["faultState"].text)
    val executor = order["repairUser"].label.ifBlank { resolvedExecutor }.ifBlank { "暂无" }
    val subdued = MaterialTheme.colorScheme.onSurfaceVariant
    val type = MaterialTheme.typography.bodySmall
    val id = order["faultId"].text
    GroupCard {
        val open = if (id.isNotBlank()) Modifier.clickable(role = Role.Button, onClickLabel = "查看工单") { openOrder(id) }
            else Modifier
        Column(open.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(order["faultName"].text.ifBlank { "维修工单" }, Modifier.weight(1f),
                    style = type, fontWeight = FontWeight.SemiBold)
                Text(status, Modifier.background(statusColor.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 2.dp), color = statusColor, style = type)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text("故障时间  " + order["faultTime"].dateText.ifBlank { "暂无" }, Modifier.weight(1f), style = type, color = subdued)
                Text("维修执行人  $executor", Modifier.widthIn(max = 132.dp), style = type,
                    color = if (executor == "暂无") subdued else MaterialTheme.colorScheme.onSurface)
            }
            Text("受理部门  " + order["dept"].label.ifBlank { "暂无" }, style = type, color = subdued)
            Text("维修班组  " + order["userGroup"].label.ifBlank { "暂无" }, style = type, color = subdued)
            val description = order["faultDesc"].text
            if (description.isNotBlank() && description != order["faultName"].text) {
                Text(description, style = type, color = subdued, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            val sn = order["sn"].text
            if (sn.isNotBlank() || id.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(sn, Modifier.weight(1f), style = type, color = subdued)
                    if (id.isNotBlank()) Text("查看工单", style = type, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
