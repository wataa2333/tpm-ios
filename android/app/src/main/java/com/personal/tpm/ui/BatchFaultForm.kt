package com.personal.tpm.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.ApiException
import com.personal.tpm.data.BatchFaultRow
import com.personal.tpm.data.BatchFaultRule
import com.personal.tpm.data.BatchFaultStatus
import com.personal.tpm.data.DraftStore
import com.personal.tpm.data.FaultPartVariable
import com.personal.tpm.data.FaultTitleLimit
import com.personal.tpm.data.FaultTitlePattern
import com.personal.tpm.data.FormDraft
import com.personal.tpm.data.FormRules
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.Session
import com.personal.tpm.data.batchMachineCodes
import com.personal.tpm.data.batchFaultData
import com.personal.tpm.data.batchReportDraft
import com.personal.tpm.data.checkedReportAsset
import com.personal.tpm.data.defaultFaultPartOptions
import com.personal.tpm.data.findBatchMachineAssets
import com.personal.tpm.data.normalizeReportMachineCode
import com.personal.tpm.data.referenceKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

internal data class MachineLookup(val machine: String, val choices: List<Jv>, val message: String)

internal class BatchFaultForm(template: FaultTemplate?, val id: String) {
    val titleEditor = TextFieldState(template?.titlePattern?.format.orEmpty())
    private var hasTitleFormat by mutableStateOf(template?.titlePattern != null)
    var pattern: FaultTitlePattern?
        get() = titleEditor.text.toString().takeIf { hasTitleFormat || it.isNotEmpty() }?.let(::FaultTitlePattern)
        set(value) {
            hasTitleFormat = value != null
            val format = value?.format.orEmpty()
            if (format != titleEditor.text.toString()) titleEditor.edit {
                replace(0, length, format)
                selection = TextRange(length)
            }
        }
    var rows by mutableStateOf<List<BatchFaultRow>>(emptyList()); private set
    var pasted by mutableStateOf("")
    var lookups by mutableStateOf<List<MachineLookup>>(emptyList()); private set
    var checking by mutableStateOf(false); private set
    var progress by mutableStateOf<String?>(null); private set
    var rules by mutableStateOf<List<BatchFaultRule>>(emptyList()); private set
    var assetCodes by mutableStateOf<List<String>>(emptyList()); private set
    var assetErrors by mutableStateOf<Map<String, String>>(emptyMap()); private set
    private var variableOptions by mutableStateOf<Map<String, List<String>>>(emptyMap())

    val pending get() = rows.filter { it.canSubmit }
    val machineCount get() = rows.distinctBy { it.assetId }.size
    val canReview get() = !checking && pending.isNotEmpty() && pasted.isBlank() && lookups.isEmpty() && assetCodes.isEmpty()
    val finished get() = rows.isNotEmpty() && pending.isEmpty() && lookups.isEmpty() && pasted.isBlank() && assetCodes.isEmpty()

    fun restore(draft: FormDraft, form: FormState) {
        val data = Jv(draft.data)
        if (data["form"].isObject) form.applyDraft(draft.withForm(data["form"]))
        rows = data["rows"].array.map(BatchFaultRow::read)
        pasted = data["pasted"].text
        pattern = FaultTitlePattern.read(data["titleFormat"], data)
            ?: form["faultName"].text.takeIf(String::isNotEmpty)?.let(::FaultTitlePattern)
        rules = data["rules"].array.map(BatchFaultRule::read)
        assetCodes = data["assetCodes"].array.map { it.text }
        val options = data["variableOptions"]
        variableOptions = options.keys.associateWith { name -> options[name].array.map { it.text } }
    }

    private fun FormDraft.withForm(form: Jv) = FormDraft(id = id, data = JSONObject(form.toString()), selections = selections)

    fun title(row: BatchFaultRow, form: FormState): String =
        if (!row.canSubmit) row.title else row.editedTitle?.trim() ?: generatedTitle(row, form)

    fun generatedTitle(row: BatchFaultRow, form: FormState): String =
        pattern?.title(row.asset, row.titleValues) ?: form["faultName"].text.trim()

    fun editTitle(row: BatchFaultRow, title: String?) {
        rows.firstOrNull { it.id == row.id && it.canSubmit }?.let { update(it.copy(editedTitle = title)) }
    }

    fun optionsFor(variable: String): List<String> =
        variableOptions[variable] ?: if (variable == FaultPartVariable) defaultFaultPartOptions else emptyList()

    fun setOptions(variable: String, options: List<String>) {
        variableOptions = variableOptions + (variable to options)
    }

    fun setTitleValues(row: BatchFaultRow, values: Map<String, String>) {
        rows.firstOrNull { it.id == row.id && it.canSubmit }?.let { update(it.copy(titleValues = it.titleValues + values)) }
    }

    fun togglePart(assetId: String, option: String) {
        val orders = rows.filter { it.assetId == assetId }
        val editable = orders.filter { it.canSubmit }
        val first = editable.firstOrNull() ?: return
        val matches = orders.filter { it.titleValues[FaultPartVariable] == option }
        if (matches.any { !it.canSubmit }) return
        if (matches.isNotEmpty()) {
            if (matches.size == orders.size) {
                val kept = matches.first().copy(titleValues = first.titleValues - FaultPartVariable)
                rows = rows.mapNotNull { row ->
                    when {
                        row.id == kept.id -> kept
                        row in matches -> null
                        else -> row
                    }
                }
            } else rows = rows - matches.toSet()
            return
        }

        val blank = editable.firstOrNull { it.titleValues[FaultPartVariable].isNullOrBlank() }
        if (blank != null) {
            setTitleValues(blank, mapOf(FaultPartVariable to option))
        } else {
            val added = BatchFaultRow(first.asset, titleValues = first.titleValues + (FaultPartVariable to option),
                id = UUID.randomUUID().toString())
            val index = rows.indexOfLast { it.assetId == assetId }
            rows = rows.toMutableList().apply { add(index + 1, added) }
        }
    }

    fun pasteTitleValues(input: String) {
        val variables = pattern?.customVariables.orEmpty()
        require(variables.isNotEmpty()) { "请先添加自定义变量" }
        val rowsByMachine = pending.groupBy { normalizeReportMachineCode(it.displayCode) }
        val seen = hashSetOf<String>()
        val changes = input.lineSequence().filter(String::isNotBlank).mapIndexed { index, line ->
            val cells = line.split('\t')
            require(cells.size == variables.size + 1) { "第 ${index + 1} 行应有机台号和 ${variables.size} 个变量值，请用制表符分隔" }
            val code = normalizeReportMachineCode(cells.first())
            val orders = requireNotNull(rowsByMachine[code]) { "机台 ${cells.first()} 不在待提交列表中" }
            val values = variables.zip(cells.drop(1).map(String::trim)).toMap()
            val row = orders.singleOrNull() ?: requireNotNull(orders.singleOrNull {
                it.titleValues[FaultPartVariable].orEmpty() == values[FaultPartVariable]
            }) { "机台 ${cells.first()} 有多张报修单，请填写对应的配件项目" }
            require(seen.add(row.id)) { "机台 ${cells.first()} 的同一报修单在粘贴内容中重复" }
            row.id to (row.titleValues + values)
        }.toMap()
        rows = rows.map { row -> changes[row.id]?.let { row.copy(titleValues = it) } ?: row }
    }

    fun add(asset: Jv) {
        val row = BatchFaultRow(asset)
        require(row.assetId.isNotBlank() && asset["assetCode"].text.isNotBlank()) {
            "所选资产缺少资产编码"
        }
        val sameMachine = rows.firstOrNull { it.assetId == row.assetId ||
            row.machine.isNotBlank() && normalizeReportMachineCode(it.machine) == normalizeReportMachineCode(row.machine) }
        require(sameMachine == null || sameMachine.assetId == row.assetId) { "${row.machine} 已选其他资产，请先移除原选择" }
        require(sameMachine == null || pending.any { it.assetId == row.assetId }) { "${row.machine} 已提交或正在等待核对" }
        if (sameMachine == null) rows = rows + row
        removeAssetCode(asset["assetCode"].text)
        lookups = lookups.filterNot { normalizeReportMachineCode(it.machine) == normalizeReportMachineCode(row.machine) }
    }

    suspend fun loadAssets(session: Session, codes: List<String> = assetCodes) {
        if (checking || codes.isEmpty()) return
        assetCodes = codes.distinct()
        checking = true
        try {
            for (chunk in codes.distinct().chunked(4)) {
                progress = "正在核对所选资产"
                val found = coroutineScope {
                    chunk.map { code -> async {
                        try { code to Result.success(session.checkedReportAsset(Jv(Jv.obj("assetCode" to code)))) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { code to Result.failure<Jv>(e) }
                    } }.awaitAll()
                }
                found.forEach { (code, result) ->
                    try { add(result.getOrThrow()) }
                    catch (e: Exception) { assetErrors = assetErrors + (code to (e.message ?: "核对失败")) }
                }
            }
        } finally { progress = null; checking = false }
    }

    fun removeAssetCode(code: String) {
        assetCodes = assetCodes - code
        assetErrors = assetErrors - code
    }

    fun removeRule(rule: BatchFaultRule) { rules = rules - rule }

    fun matchingRules(row: BatchFaultRow) = rules.filter { it.matches(row) }

    fun reportData(row: BatchFaultRow, form: FormState): Jv = Jv(batchFaultData(form.data, row, title(row, form), rules))

    fun removeOrder(row: BatchFaultRow) {
        rows = rows.filterNot { it.id == row.id && it.canSubmit }
    }

    fun picker() = PickRequest(
        RefSource.Assets, onPick = ::add, multiple = true,
        selected = pending.map { referenceKey(RefSource.Assets, it.asset) }.toSet(),
        onRemove = { asset -> rows = rows.filterNot { it.assetId == asset["assetId"].text && it.canSubmit } },
    )

    suspend fun lookup(session: Session) {
        if (checking) return
        val input = batchMachineCodes(unresolvedInput())
        if (input.isEmpty()) { pasted = ""; return }
        checking = true
        try {
            val selectedCodes = rows.map { normalizeReportMachineCode(it.machine) }.toSet()
            val remaining = input.filterNot { normalizeReportMachineCode(it) in selectedCodes }
            val unresolved = mutableListOf<MachineLookup>()
            for ((index, chunk) in remaining.chunked(4).withIndex()) {
                progress = "正在核对机台 ${index * 4 + 1} / ${remaining.size}"
                val found = coroutineScope {
                    chunk.map { code -> async {
                        try {
                            val assets = session.findBatchMachineAssets(code)
                            MachineLookup(code, assets, if (assets.isEmpty()) "未找到对应资产" else "请选定报修资产")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { MachineLookup(code, emptyList(), e.message ?: "核对失败") }
                    } }.awaitAll()
                }
                for (match in found) {
                    val asset = match.choices.singleOrNull()
                    if (asset == null) unresolved += match else add(asset)
                }
            }
            lookups = unresolved
            pasted = ""
        } finally {
            progress = null
            checking = false
        }
    }

    fun resolve(lookup: MachineLookup, asset: Jv?) {
        if (asset != null) add(asset)
        lookups = lookups - lookup
    }

    fun retryLookup(lookup: MachineLookup) {
        lookups = lookups - lookup
        pasted = listOf(pasted, lookup.machine).filter(String::isNotBlank).joinToString("\n")
    }

    private fun unresolvedInput() = (lookups.map { it.machine } + batchMachineCodes(pasted)).joinToString("\n")

    suspend fun save(store: DraftStore, scope: String, form: FormState) {
        val draft = batchReportDraft(id, form.data, form.selections, pattern, rows, unresolvedInput(), rules, assetCodes, variableOptions)
        withContext(Dispatchers.IO) { store.save(draft, scope, durable = true) }
    }

    private fun update(row: BatchFaultRow) {
        rows = rows.map { if (it.id == row.id) row else it }
    }

    suspend fun recordVerification(
        row: BatchFaultRow, found: Boolean, store: DraftStore, scope: String, form: FormState,
    ) {
        check(rows.any { it == row && it.status == BatchFaultStatus.Unknown })
        update(row.copy(
            status = if (found) BatchFaultStatus.Submitted else BatchFaultStatus.Ready,
            detail = if (found) "已人工核对工单" else "",
        ))
        try { save(store, scope, form) }
        catch (e: Exception) { update(row); throw e }
    }

    fun validate(form: FormState): String? {
        if (!canReview) return "请先完成机台核对并选择报修资产"
        for (row in pending) {
            val missing = pattern?.missing(row.asset, row.titleValues).orEmpty()
            if (row.editedTitle == null && missing.isNotEmpty()) return "${row.displayCode} 缺少 ${missing.joinToString("、")}"
            val name = title(row, form)
            if (name.length > FaultTitleLimit) return "${row.displayCode} 的报修名称超过 ${FaultTitleLimit} 个字符"
            if (name.any { it == '\n' || it == '\r' }) return "${row.displayCode} 的报修名称请填写为一行"
            val data = batchFaultData(form.data, row, name, rules)
            FormRules.validate(OrderKind.Fault, Jv(data), form.nodes, form.selections)?.let { return "${row.displayCode} · $it" }
        }
        return null
    }

    suspend fun submit(session: Session, store: DraftStore, scope: String, form: FormState) {
        check(progress == null)
        val items = pending
        val owner = session.scope.value
        val titles = items.associate { it.id to title(it, form) }
        progress = "正在提交"
        try {
            for ((index, row) in items.withIndex()) {
                if (owner != session.scope.value) throw CancellationException("报修账号或厂区已变化")
                progress = "正在提交 ${index + 1}/${items.size} · ${row.displayCode}"
                val title = titles.getValue(row.id)
                val fresh = try {
                    session.checkedReportAsset(row.asset).also {
                        require(it["addressCode"].text == row.machine) { "资产机台号已变化，请移除此行后重新选择" }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    update(row.copy(status = BatchFaultStatus.Failed, title = title, detail = e.message ?: "资产核对失败"))
                    save(store, scope, form)
                    continue
                }
                val freshRow = row.copy(asset = fresh)
                val freshTitle = title(freshRow, form)
                val data = batchFaultData(form.data, freshRow, freshTitle, rules)
                if (freshTitle != title || matchingRules(freshRow) != matchingRules(row)) {
                    update(row.copy(asset = fresh, status = BatchFaultStatus.Failed, title = title, detail = "资产资料已更新，请重新预览"))
                    save(store, scope, form)
                    continue
                }
                val payload = FormRules.payload(OrderKind.Fault, data, form.nodes, form.selections, committed = true)
                update(row.copy(status = BatchFaultStatus.Checking, title = title, detail = "提交结果待核对"))
                try {
                    save(store, scope, form)
                } catch (e: Exception) {
                    update(row)
                    throw e
                }
                try {
                    val response = session.request("fault/addAssetFault", body = payload)
                    val number = response["sn"].text.ifBlank { response["data"]["sn"].text }
                    val faultId = response["faultId"].text.ifBlank { response["data"]["faultId"].text }
                    update(row.copy(status = BatchFaultStatus.Submitted, title = title,
                        detail = when {
                            number.isNotBlank() -> "工单号 $number"
                            faultId.isNotBlank() -> "工单 ID $faultId"
                            else -> "提交成功"
                        }))
                } catch (e: CancellationException) {
                    update(row.copy(status = BatchFaultStatus.Unknown, title = title, detail = "提交中断，请查询工单核对"))
                    throw e
                } catch (e: Exception) {
                    val status = (e as? ApiException)?.status
                    val rejected = status in setOf(400, 401, 403, 404, 405, 413, 415, 422, 429)
                    update(row.copy(status = if (rejected) BatchFaultStatus.Failed else BatchFaultStatus.Unknown, title = title,
                        detail = e.message ?: "提交结果待核对"))
                } finally {
                    withContext(NonCancellable) { save(store, scope, form) }
                }
            }
        } finally {
            progress = null
            session.notifyChanged()
        }
    }
}

@Composable
internal fun BatchFaultAssets(
    batch: BatchFaultForm, form: FormState, submitting: Boolean,
    pick: (PickRequest) -> Unit, onLookup: () -> Unit, onChoose: (MachineLookup) -> Unit, onVerify: (BatchFaultRow) -> Unit,
    onRetryAssets: () -> Unit = {},
) {
    GroupCard {
        if (!submitting) {
            if (batch.checking) ReadOnlyFormRow("选择报修资产", "已选 ${batch.machineCount} 台")
            else PickRow("选择报修资产", "已选 ${batch.machineCount} 台", true) { pick(batch.picker()) }
            TextEntry("粘贴机台号", batch.pasted, { batch.pasted = it }, minLines = 2, limit = 4000,
                placeholder = "例：AB01、AB03、AB07", enabled = !batch.checking)
            AnimatedVisibility(batch.pasted.isNotBlank() || batch.checking, modifier = Modifier.padding(horizontal = 12.dp),
                enter = expandVertically(animationSpec = tween(Motion.Normal)) + fadeIn(tween(Motion.Fast)),
                exit = shrinkVertically(animationSpec = tween(Motion.Normal)) + fadeOut(tween(Motion.Fast))) {
                FilledTonalButton(onClick = onLookup, enabled = !batch.checking,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    if (batch.checking) CircularProgressIndicator(Modifier.padding(end = 8.dp).size(16.dp),
                        color = accentInk(), strokeWidth = 2.dp)
                    Text(if (batch.checking) batch.progress ?: "正在核对机台" else "核对机台",
                        color = if (batch.checking) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.material3.LocalContentColor.current)
                }
            }
        }
        if (submitting) batch.progress?.let { Text(it, Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall) }
        if (batch.rows.any { it.status != BatchFaultStatus.Ready }) {
            val completed = batch.rows.count { it.status == BatchFaultStatus.Submitted }
            val failed = batch.rows.count { it.status == BatchFaultStatus.Failed }
            val unknown = batch.rows.count { it.status == BatchFaultStatus.Unknown }
            Text("已提交 $completed · 失败 $failed · 待核对 $unknown", Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        AnimatedBatchRows(batch.lookups, MachineLookup::machine) { lookup, active ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text("${lookup.machine} · ${lookup.message}", style = MaterialTheme.typography.bodyMedium)
                if (!submitting && !batch.checking) Row {
                    TextButton(enabled = active, contentPadding = PaddingValues(end = 12.dp), onClick = {
                        if (lookup.choices.isNotEmpty()) onChoose(lookup)
                        else { batch.retryLookup(lookup); onLookup() }
                    }) { Text(if (lookup.choices.isNotEmpty()) "选择资产" else "重新核对") }
                    TextButton(enabled = active, onClick = { batch.resolve(lookup, null) }) { Text("移除机台") }
                }
            }
        }
        if (!submitting && !batch.checking) {
            AnimatedBatchRows(batch.assetCodes, { it }) { code, active ->
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("$code · ${batch.assetErrors[code] ?: "待核对资产"}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { batch.removeAssetCode(code) }, enabled = active) { Icon(Icons.Filled.Close, "移除 $code") }
                }
            }
            Reveal(batch.assetCodes.isNotEmpty()) {
                TextButton(onClick = onRetryAssets, enabled = batch.assetCodes.isNotEmpty(), modifier = Modifier.padding(start = 16.dp)) {
                    Text("重新核对资产")
                }
            }
        }
    }
    if (submitting) BatchLiveTitles(batch, form, editable = false, onVerify = onVerify)
}

/** Retain the last displayed row only until its exit finishes. Form data changes immediately. */
@Composable
private fun <T> AnimatedBatchRows(
    rows: List<T>, rowKey: (T) -> String, divider: (@Composable () -> Unit)? = null,
    content: @Composable (T, Boolean) -> Unit,
) {
    var displayed by remember { mutableStateOf(rows) }
    val currentKeys = rows.mapTo(hashSetOf(), rowKey)
    val firstKey = rows.firstOrNull()?.let(rowKey)
    val merged = rows.toMutableList()
    displayed.forEachIndexed { index, row ->
        if (rowKey(row) !in currentKeys) merged.add(index.coerceAtMost(merged.size), row)
    }
    SideEffect { if (displayed != merged) displayed = merged }
    for (row in merged) key(rowKey(row)) {
        val id = rowKey(row)
        val active = id in currentKeys
        val visibility = remember { MutableTransitionState(displayed.any { rowKey(it) == id }) }
        visibility.targetState = active
        AnimatedVisibility(visibility,
            enter = expandVertically(tween(Motion.Normal, easing = Motion.Arrive), expandFrom = Alignment.Top) + fadeIn(tween(Motion.Fast)),
            exit = shrinkVertically(tween(Motion.Normal, easing = Motion.Leave), shrinkTowards = Alignment.Top) + fadeOut(tween(Motion.Fast))) {
            Column {
                if (active && id != firstKey) divider?.invoke()
                content(row, active)
            }
        }
        LaunchedEffect(active, visibility.isIdle) {
            if (!active && visibility.isIdle) displayed = displayed.filterNot { rowKey(it) == id }
        }
    }
}

@Composable
internal fun BatchLiveTitles(
    batch: BatchFaultForm, form: FormState, editable: Boolean = true,
    onEditTitle: (BatchFaultRow) -> Unit = {}, onPasteValues: () -> Unit = {}, onVerify: (BatchFaultRow) -> Unit = {},
) {
    val hasRows = batch.rows.isNotEmpty()
    Reveal(hasRows) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            FormTitle(if (batch.finished) "报修明细" else "实时预览 · ${batch.rows.size} 单", Modifier.weight(1f))
            if (editable && batch.pending.isNotEmpty() && batch.pattern?.customVariables?.isNotEmpty() == true) {
                TextButton(onClick = onPasteValues) { Text("粘贴变量值", maxLines = 1, softWrap = false) }
            }
        }
    }
    val machineAccent = accentInk()
    // Keep the card in the layout during the last row's exit, then collapse its padding as well.
    val cardVisibility = remember { MutableTransitionState(hasRows) }
    cardVisibility.targetState = hasRows
    AnimatedVisibility(cardVisibility,
        enter = expandVertically(tween(Motion.Normal, easing = Motion.Arrive), expandFrom = Alignment.Top),
        exit = shrinkVertically(tween(Motion.Normal, easing = Motion.Leave), shrinkTowards = Alignment.Top)) {
      GroupCard {
        AnimatedBatchRows(batch.rows, BatchFaultRow::id, divider = {
            HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }) { row, active ->
            val colors = MaterialTheme.colorScheme
            val typography = MaterialTheme.typography
            val canEdit = editable && row.canSubmit
            val title = if (row.canSubmit) row.editedTitle ?: batch.generatedTitle(row, form) else row.title
            val part = row.titleValues[FaultPartVariable].orEmpty()
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(listOf(row.displayCode, part).filter(String::isNotBlank).joinToString(" · "),
                            style = typography.labelMedium, color = machineAccent)
                        if (canEdit) {
                            BasicTextField(value = title, enabled = active, onValueChange = {
                                batch.editTitle(row, it.replace('\r', ' ').replace('\n', ' ').take(FaultTitleLimit))
                            }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "${row.displayCode} 报修名称" },
                                textStyle = typography.bodyMedium.copy(color = if (title.length > FaultTitleLimit) colors.error else colors.onSurface),
                                cursorBrush = SolidColor(colors.primary), maxLines = 2, decorationBox = { input ->
                                    androidx.compose.foundation.layout.Box {
                                        if (title.isEmpty()) Text("填写报修名称", style = typography.bodyMedium, color = colors.onSurfaceVariant)
                                        input()
                                    }
                                })
                        } else Text(title.ifBlank { "待填报修名称" }, style = typography.bodyMedium)
                    }
                    if (canEdit) {
                        IconButton(onClick = { onEditTitle(row) }, enabled = active, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Edit, "编辑 ${row.displayCode} 的报修名称", Modifier.size(17.dp), tint = colors.onSurfaceVariant)
                        }
                        IconButton(onClick = { batch.editTitle(row, null) }, enabled = active && row.editedTitle != null,
                            modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Refresh, "按格式重新生成名称", Modifier.size(17.dp))
                        }
                        IconButton(onClick = { batch.removeOrder(row) }, enabled = active, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Filled.Close, "移除 ${row.displayCode} 的这张报修单", Modifier.size(17.dp))
                        }
                    }
                }
                if (row.canSubmit) batch.matchingRules(row).takeIf { it.isNotEmpty() }?.let { matches ->
                    Text(matches.joinToString(" · ") { it.label }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                }
                val status = when (row.status) {
                    BatchFaultStatus.Ready -> ""
                    BatchFaultStatus.Checking -> "正在提交"
                    BatchFaultStatus.Failed -> "提交失败，可重试"
                    BatchFaultStatus.Unknown -> "结果待核对，请在维修工单中查询"
                    BatchFaultStatus.Submitted -> "已提交"
                }
                if (status.isNotEmpty()) Text(listOf(status, row.detail).filter(String::isNotBlank).distinct().joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.status == BatchFaultStatus.Failed || row.status == BatchFaultStatus.Unknown) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                if (editable && row.status == BatchFaultStatus.Unknown) TextButton(onClick = { onVerify(row) }) { Text("登记核对结果") }
            }
        }
      }
    }
}

@Composable
internal fun EditBatchTitle(
    row: BatchFaultRow, generated: String,
    onDismiss: () -> Unit, onSave: (String?) -> Unit,
) {
    var value by androidx.compose.runtime.remember(row.id) { mutableStateOf(row.editedTitle ?: generated) }
    AnimatedMessageDialog(
        visible = true, onDismissRequest = onDismiss, title = { Text("编辑报修名称") },
        text = {
            TextEntry(row.displayCode, value, { value = it.replace('\r', ' ').replace('\n', ' ') },
                limit = FaultTitleLimit, minLines = 2)
        },
        confirmButton = { TextButton(enabled = value.isNotBlank() && value.length <= FaultTitleLimit,
            onClick = { onSave(value.takeUnless { it == generated }) }) { Text("保存") } },
        dismissButton = {
            TextButton(onClick = { value = generated }, enabled = value != generated) { Text("按格式重新生成") }
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
internal fun BatchTitlePaste(batch: BatchFaultForm, onDismiss: () -> Unit) {
    var input by androidx.compose.runtime.remember { mutableStateOf("") }
    var error by androidx.compose.runtime.remember { mutableStateOf<String?>(null) }
    val columns = listOf("机台号") + batch.pattern?.customVariables.orEmpty()
    AnimatedMessageDialog(
        visible = true, onDismissRequest = onDismiss, title = { Text("粘贴变量值") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("从表格复制数据行，列顺序为 ${columns.joinToString(" · ")}。", style = MaterialTheme.typography.bodyMedium)
                if (FaultPartVariable in columns && batch.pending.size > batch.pending.distinctBy { it.assetId }.size) {
                    Text("同一台机有多张单时，用配件项目区分。", style = MaterialTheme.typography.bodySmall)
                }
                TextEntry("数据行", input, { input = it; error = null }, minLines = 2, limit = 24000)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = input.isNotBlank(), onClick = {
                try { batch.pasteTitleValues(input); onDismiss() }
                catch (e: IllegalArgumentException) { error = e.message ?: "数据格式错误" }
            }) { Text("应用") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun BatchFaultVerification(row: BatchFaultRow, onDismiss: () -> Unit, onResult: (Boolean) -> Unit) {
    AnimatedMessageDialog(
        visible = true, onDismissRequest = onDismiss, title = { Text("核对 ${row.displayCode} 的报修") },
        text = { Text("请先按资产编码和报修名称查找「${row.title}」，再登记核对结果。") },
        confirmButton = { TextButton(onClick = { onResult(true) }) { Text("已找到") } },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
            TextButton(onClick = { onResult(false) }) { Text("确认未生成") }
        },
    )
}

@Composable
internal fun BatchMachineChoice(lookup: MachineLookup, onDismiss: () -> Unit, onPick: (Jv) -> Unit) {
    AnimatedMessageDialog(visible = true, onDismissRequest = onDismiss, title = { Text("选择 ${lookup.machine} 的资产") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                lookup.choices.forEach { asset ->
                    PickRow("资产", listOf(asset["assetCode"].text, asset.label).distinct().joinToString(" · "), false) { onPick(asset) }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BatchFaultPreview(batch: BatchFaultForm, form: FormState, onDismiss: () -> Unit, onSubmit: () -> Unit) {
    var editing by remember { mutableStateOf<BatchFaultRow?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val orders = batch.pending
    val colors = MaterialTheme.colorScheme
    val typography = MaterialTheme.typography
    val accent = accentInk()
    AppSheet(onDismissRequest = { if (!confirming) onDismiss() }, sheetState = sheetState,
        containerColor = colors.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.88f).navigationBarsPadding().padding(horizontal = BarSpec.Horizontal)) {
            Column(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("预览报修", style = typography.titleLarge)
                Text("${orders.distinctBy { it.assetId }.size} 台 · ${orders.size} 张报修单",
                    style = typography.bodySmall, color = colors.onSurfaceVariant)
            }
            HorizontalDivider()
            ScrollableLazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(orders, key = { it.id }) { row ->
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(batch.title(row, form).ifBlank { "待填写报修名称" }, style = typography.titleSmall)
                            Text(listOf(row.displayCode, row.asset["assetCode"].text).filter(String::isNotBlank).distinct().joinToString(" · "),
                                style = typography.labelMedium, color = accent)
                            if (row.asset.label.isNotBlank()) Text(row.asset.label, style = typography.bodySmall, color = colors.onSurfaceVariant)
                            if (batch.matchingRules(row).isNotEmpty()) {
                                val data = batch.reportData(row, form)
                                val routing = listOf(data["dept"].label, data["user"].label, data["userGroup"].label)
                                    .filter(String::isNotBlank).joinToString(" · ")
                                if (routing.isNotBlank()) Text(routing, style = typography.bodySmall, color = colors.onSurfaceVariant)
                                data["faultDesc"].text.takeIf(String::isNotBlank)?.let { Text(it, style = typography.bodySmall) }
                            }
                        }
                        IconButton(onClick = { editing = row }, enabled = !confirming) { Icon(Icons.Filled.Edit, "编辑 ${row.displayCode} 的报修名称", tint = accent) }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(BarSpec.Gap)) {
                SoftButton(onClick = {
                    if (!confirming) {
                        confirming = true
                        scope.launch {
                            try { sheetState.hide(); onDismiss() }
                            finally { confirming = false }
                        }
                    }
                }, Modifier.weight(1f).heightIn(min = BarSpec.ButtonHeight), enabled = !confirming) { Text("返回修改") }
                GlassActionButton(onClick = {
                    if (!confirming) {
                        confirming = true
                        scope.launch {
                            try {
                                sheetState.hide()
                                delay(Motion.Instant.toLong())
                                onSubmit()
                            } finally { confirming = false }
                        }
                    }
                }, enabled = editing == null && !confirming,
                    modifier = Modifier.weight(1f).heightIn(min = BarSpec.ButtonHeight)) { Text("批量提交") }
            }
        }
    }
    editing?.let { row ->
        EditBatchTitle(row, batch.generatedTitle(row, form), onDismiss = { editing = null }) { title ->
            batch.editTitle(row, title)
            editing = null
        }
    }
}
