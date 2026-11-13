package com.personal.tpm.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.BatchFaultRow
import com.personal.tpm.data.DraftTarget
import com.personal.tpm.data.FormDraft
import com.personal.tpm.data.FormRules
import com.personal.tpm.data.FaultTitleLimit
import com.personal.tpm.data.FaultTitlePattern
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.checkedReportAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

/**
 * The repair report: a new one, or (existingId) the edit of one that is still being reported. 保存 keeps what is typed on this phone as a draft and
 * sends nothing; 提交 needs the form complete and writes to the server. Closing a form that holds something asks whether to keep it as a draft.
 * A draft belongs to one order: a new report is offered the last new-report draft, an order only its own.
 */
@Composable
fun FaultFormScreen(
    existingId: String?, startDraftId: String?, onClose: () -> Unit, onSaved: () -> Unit,
    initialAsset: Jv? = null, template: FaultTemplate? = null, batchMode: Boolean = false,
    initialAssetCodes: List<String> = emptyList(),
) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    val store = LocalDrafts.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState(OrderKind.Fault) }
    val batch = if (batchMode) {
        val id = rememberSaveable(startDraftId) { startDraftId ?: UUID.randomUUID().toString() }
        remember(id) { BatchFaultForm(template, id) }
    } else null
    val focus = LocalFocusManager.current
    val snackbar = remember { SnackbarHostState() }
    val scopeKey = remember { session.draftScope }
    val batchScopeKey = remember { session.batchFaultDraftScope }
    var request by remember { mutableStateOf<PickRequest?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var loadedData by remember { mutableStateOf("") }
    var loadedSelections by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var draftCount by remember { mutableStateOf(0) }
    var reviewing by remember { mutableStateOf(false) }
    var closingDraft by remember { mutableStateOf(false) }
    var choosingMachine by remember { mutableStateOf<MachineLookup?>(null) }
    var verifying by remember { mutableStateOf<BatchFaultRow?>(null) }
    var editingTitle by remember { mutableStateOf<BatchFaultRow?>(null) }
    var pastingTitleValues by remember { mutableStateOf(false) }
    var variableSheet by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }
    val orderTarget = if (existingId == null) DraftTarget.NewReport else DraftTarget.Order(existingId)
    val draftsAllowed = batch == null && (existingId == null || existingId.isNotBlank())
    val worthKeeping = draftsAllowed && form.ready && !form.loading &&
        (if (existingId == null && (template == null || template.id == FaultTemplate.blank.id) && initialAsset == null)
            FormRules.faultHasContent(form.jv) else (form.data.toString() != loadedData || form.selections != loadedSelections))

    fun applyDraft(draft: FormDraft, automatic: Boolean) {
        form.applyDraft(draft)
        draftId = draft.id
        scope.launch { snackbar.showSnackbar((if (automatic) "已加载上次保存的草稿（" else "已加载草稿（") + draft.title + "）") }
    }
    val useTemplate = template != null && template.id != FaultTemplate.blank.id
    val seededAsset = if (batch == null) initialAsset ?: template?.takeIf { FaultTemplateField.Asset in it.checked }
        ?.value(FaultTemplateField.Asset)?.takeUnless { it.isNull } else null

    fun selectAsset(asset: Jv) = form.set("asset", asset.raw)

    suspend fun initialize() {
        form.load(session, existingId)
        if (!form.ready) return
        if (batch != null) {
            form.loading = true
            try {
                val draft = withContext(Dispatchers.IO) { store.drafts(batchScopeKey).firstOrNull { it.id == batch.id } }
                check(draft != null || startDraftId == null) { "这份批量报修草稿已不存在" }
                if (draft != null) {
                    batch.restore(draft, form)
                    return
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { form.ready = false; form.error = e.message ?: "恢复批量报修草稿失败"; return }
            finally { form.loading = false }
        }
        if (existingId == null) {
            form.loading = true
            try { form.applyDefaults(session, prefs) }
            finally { form.loading = false }
        }
        loadedData = form.data.toString(); loadedSelections = form.selections
        if (existingId == null && seededAsset != null && form.ready) {
            form.loading=true
            try {
                selectAsset(session.checkedReportAsset(seededAsset))
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { failure=e.message ?: "资产带入失败，请手动选择报修资产" }
            finally { form.loading=false }
        }
        if (existingId == null && template != null && form.ready) {
            if (useTemplate) {
                form.loading = true
                try {
                    val resolved = session.resolveFaultPreset(template)
                    for ((field, value) in resolved.values) {
                        when (field) {
                            FaultTemplateField.Asset, FaultTemplateField.Name -> Unit
                            FaultTemplateField.Applicant -> if (!value.isNull) form.selectFaultApplicant(value)
                            FaultTemplateField.ReceivingDepartment -> form.selectReceiverDepartment(value)
                            else -> form.set(field.key, value.raw)
                        }
                    }
                    if (batch == null) form.set("faultName", template.singleTitle)
                    else if (template.titlePattern == null && template.singleTitle.isNotEmpty()) {
                        batch.pattern = FaultTitlePattern(template.singleTitle)
                    }
                    if (resolved.unavailable.isNotEmpty()) {
                        failure = "当前厂区无法带入以下预设，请手动选择：\n" + resolved.unavailable.joinToString("\n")
                    }
                } finally { form.loading = false }
            }
            loadedData = form.data.toString()
            loadedSelections = form.selections
        }
        if (draftsAllowed && form.ready && seededAsset == null && !useTemplate) {
            val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.drafts(scopeKey) }
            val chosen = startDraftId?.let { id -> all.firstOrNull { it.id == id && orderTarget.matches(it) } }
            if (chosen != null) applyDraft(chosen, false)
            else all.filter(orderTarget::matches).firstOrNull()?.let { applyDraft(it, true) }
        }
        if (draftsAllowed) draftCount = withContext(Dispatchers.IO) { store.drafts(scopeKey, orderTarget).size }
        if (batch != null && initialAssetCodes.isNotEmpty()) batch.loadAssets(session, initialAssetCodes)
    }
    LaunchedEffect(existingId, session.accountKey, initialAsset?.toString()) {
        if (form.ready) form.refreshActor(session, existingId) else initialize()
    }

    fun keepDraft() {
        try {
            store.save(FormDraft(id = draftId ?: java.util.UUID.randomUUID().toString(), data = JSONObject(form.data.toString()), selections = form.selections, orderId = existingId, orderSn = form["sn"].text.ifEmpty { null }), scopeKey)
            onClose()
        } catch (e: Exception) { failure = "草稿没有保存成功：" + (e.message ?: "") }
    }
    /** Close directly, preserving entered content as a local draft. */
    fun close(): Boolean { if (worthKeeping) { keepDraft(); return false }; onClose(); return true }
    fun submit(committed: Boolean) {
        if (form["faultName"].text.length > FaultTitleLimit) { failure = "报修名称不能超过 ${FaultTitleLimit} 个字符"; return }
        FormRules.validate(OrderKind.Fault, form.jv, form.nodes, form.selections)?.let { failure = it; return }
        if (form.saving) return
        form.saving = true
        scope.launch {
            try {
                val payload = FormRules.payload(OrderKind.Fault, form.data, form.nodes, form.selections, committed)
                if (existingId != null) payload.put("taskInfo", JSONObject.NULL)
                session.request(if (existingId == null) "fault/addAssetFault" else "fault/updateAssetFault", body = payload)
                draftId?.let { store.remove(it, scopeKey) }
                session.notifyChanged()
                onSaved(); onClose()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: "提交失败" }
            finally { form.saving = false }
        }
    }

    fun saveBatch() {
        val current = batch ?: return
        if (closingDraft || form.saving || current.checking) return
        closingDraft = true
        scope.launch {
            try {
                current.save(store, batchScopeKey, form)
                onClose()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                closingDraft = false
                failure = "批量报修草稿保存失败：" + (e.message ?: "")
            }
        }
    }
    fun reviewBatch() {
        val current = batch ?: return
        focus.clearFocus()
        if (current.canReview) reviewing = true else failure = "请先完成机台核对并选择报修资产"
    }
    fun submitBatch() {
        val current = batch ?: return
        if (form.saving) return
        current.validate(form)?.let { reviewing = false; failure = it; return }
        reviewing = false
        form.saving = true
        scope.launch {
            try {
                current.submit(session, store, batchScopeKey, form)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = "批量提交已暂停：" + (e.message ?: "无法保存提交记录") }
            finally { form.saving = false }
        }
    }
    val busy = form.loading || form.saving || photoBusy || batch?.checking == true
    val batchCanKeep = batch != null && form.ready && (batch.rows.isNotEmpty() || batch.pasted.isNotBlank() ||
        batch.lookups.isNotEmpty() || batch.assetCodes.isNotEmpty() || batch.rules.isNotEmpty() ||
        batch.pattern?.format?.isNotBlank() == true || FormRules.faultHasContent(form.jv))
    fun closeForm(): Boolean {
        if (closingDraft || form.saving || photoBusy || batch?.checking == true) return false
        if (batchCanKeep) { saveBatch(); return false }
        return close()
    }
    Box(Modifier.fillMaxSize()) {
    FormPage(
        title = if (batch != null) "批量报修" else if (existingId == null) "单次报修" else "编辑报修",
        busy = busy, onClose = { closeForm() }, snackbar = snackbar,
        backEnabled = !closingDraft && !form.saving && !photoBusy && batch?.checking != true,
        interceptBack = worthKeeping || batchCanKeep, onBackGesture = ::closeForm, animateInterceptedBack = batch == null,
        actions = {
            if (draftsAllowed) GlassIconButton(onClick = { showDrafts = true }) { Icon(Icons.Filled.Inbox, contentDescription = if (draftCount > 0) "草稿箱（$draftCount）" else "草稿箱") }
        },
        bottomBar = {
            if (batch == null) {
                FormActionBar(busy = form.saving || form.loading, enabled = form.ready && !form.loading && !photoBusy,
                    onSave = { if (draftsAllowed) keepDraft() else submit(false) }, onSubmit = { submit(true) })
            } else {
                FormActionBar(busy = busy, enabled = form.ready && !closingDraft, onSave = if (batch.finished) null else ::saveBatch,
                    onSubmit = { if (batch.finished) closeForm() else reviewBatch() },
                    submitLabel = if (batch.finished) "完成" else "预览报修")
            }
        },
    ) {
        if (form.error != null && !form.ready) {
            AnimatedLabel(form.error ?: "", color = MaterialTheme.colorScheme.error)
            GlassTextButton(onClick = { scope.launch { initialize() } }) { Text("重试") }
            return@FormPage
        }
        if (batch != null && !form.ready) return@FormPage
        if (batch != null) {
            if (form.saving) {
                BatchFaultAssets(batch, form, submitting = true, pick = {}, onLookup = {}, onChoose = {}, onVerify = {})
                return@FormPage
            }
            FormTitle("报修名称")
            FaultTitleEditor(batch.titleEditor, enabled = !batch.checking,
                onVariable = { name, insert -> focus.clearFocus(); variableSheet = name to insert })
            BatchLiveTitles(batch, form, editable = !batch.checking, onEditTitle = { row ->
                focus.clearFocus()
                editingTitle = row
            },
                onPasteValues = { pastingTitleValues = true }, onVerify = { verifying = it })
            FormTitle("机台与资产")
            BatchFaultAssets(batch, form, submitting = false, pick = { request = it }, onLookup = {
                focus.clearFocus()
                scope.launch { batch.lookup(session) }
            }, onChoose = { choosingMachine = it }, onVerify = { verifying = it },
                onRetryAssets = { scope.launch { batch.loadAssets(session) } })
        } else FormTitle("报修信息")
        GroupCard {
            if (batch == null) {
                InputRow("报修名称", form["faultName"].text, { form.set("faultName", it) }, required = true, keyboardType = KeyboardType.Text, limit = FaultTitleLimit, placeholder = "请输入报修名称")
                PickRow("报修资产", listOf(form["asset"]["assetCode"].text,form["asset"].label).filter { it.isNotBlank() }.joinToString(" · "), true) { request = PickRequest(RefSource.Assets, ::selectAsset) }
            }
            DateTimeField("故障时间", form["faultTime"].text, dateOnly = false, required = true) { form.set("faultTime", it) }
        }
        FormTitle(if (batch == null) "申请与受理" else "共用申请与受理信息")
        GroupCard {
            PickRow("故障申请人", form["applyUser"].label, true) {
                request = PickRequest(RefSource.Users, form::selectFaultApplicant, "fault.applyUser")
            }
            PickRow("申请部门", form["applyDept"].label, true) { request = PickRequest(RefSource.Departments, { form.set("applyDept", it.raw) }, "fault.applyDept") }
            PickRow("受理部门", form["dept"].label, true) { request = PickRequest(RefSource.Departments, { form.selectReceiverDepartment(it) }, "fault.dept") }
            PickRow("维修受理人", form["user"].label, true) {
                request = PickRequest(RefSource.Users, { user -> form.setAll("user" to user.raw, "dept" to (user["depts"].array.firstOrNull()?.raw ?: form["dept"].raw)) }, "fault.user")
            }
            if (!form["dept"].isNull && form["user"].isNull) {
                Text("该部门未设置默认受理人，请手动选择维修受理人", Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InputRow("报修人联系电话", form["userPhone"].text, { form.set("userPhone", it) }, required = true, keyboardType = KeyboardType.Phone, limit = 32, placeholder = "请输入联系电话")
            PickRow("维修班组", form["userGroup"].label, true) { request = PickRequest(RefSource.Groups, { form.set("userGroup", it.raw) }, "fault.userGroup") }
            PickRow("ERP记账部门", form["division"].label, true) { request = PickRequest(RefSource.Divisions, { form.set("division", it.raw) }, "fault.division") }
        }
        if (form.nodes.any { it["assignType"].text in listOf("ASSIGN", "SUPERVISOR") }) {
            FormTitle("审批人")
            GroupCard { AssigneeRows(form.nodes, form.selections, if (existingId == null) "assignee.fault" else null, form::update) { request = it } }
        }
        TextEntry("故障描述", form["faultDesc"].text, { form.set("faultDesc", it) }, limit = 255, minLines = 2, placeholder = "请输入故障描述")
        FormTitle(if (batch == null) "现场照片" else "共用现场照片")
        GroupCard { PhotosField(form["albums"], { form.set("albums", it) }, "asset_fault", { photoBusy = it }, { failure = "照片上传失败：$it" }) }
    }
    if (closingDraft) Box(Modifier.matchParentSize().pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
        }
    })
    }

    PickHost(request) { request = null }
    if (batch != null) {
        variableSheet?.let { (name, insert) ->
            BatchVariableSheet(batch, name, onDismiss = { variableSheet = null }, onInsert = insert, onSelectAssets = {
                variableSheet = null
                request = batch.picker()
            })
        }
        editingTitle?.let { row ->
            EditBatchTitle(row, batch.generatedTitle(row, form), onDismiss = { editingTitle = null }) { title ->
                batch.editTitle(row, title)
                editingTitle = null
            }
        }
        if (pastingTitleValues) BatchTitlePaste(batch) { pastingTitleValues = false }
        verifying?.let { row ->
            BatchFaultVerification(row, onDismiss = { verifying = null }) { found ->
                verifying = null
                form.saving = true
                scope.launch {
                    try { batch.recordVerification(row, found, store, batchScopeKey, form) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = e.message ?: "核对结果保存失败" }
                    finally { form.saving = false }
                }
            }
        }
        choosingMachine?.let { lookup ->
            BatchMachineChoice(lookup, onDismiss = { choosingMachine = null }) { asset ->
                try { batch.resolve(lookup, asset) }
                catch (e: IllegalArgumentException) { failure = e.message ?: "选择资产失败" }
                choosingMachine = null
            }
        }
        if (reviewing && form.ready && batch.canReview) {
            BatchFaultPreview(batch, form, onDismiss = { reviewing = false }, onSubmit = ::submitBatch)
        }
    }
    if (showDrafts) DraftSheet(scopeKey, orderTarget, onPick = { showDrafts = false; applyDraft(it, false) }, onDismiss = { showDrafts = false; draftCount = store.drafts(scopeKey, orderTarget).size })
    failure?.let { ErrorDialog("提示", it) { failure = null } }
    form.error?.takeIf { form.ready }?.let { ErrorDialog("提示", it) { form.error = null } }
}
