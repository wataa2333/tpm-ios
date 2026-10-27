package com.personal.tpm.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.DraftTarget
import com.personal.tpm.data.FormDraft
import com.personal.tpm.data.FormRules
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.RefSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The repair report: a new one, or (existingId) the edit of one that is still being reported. 保存 keeps what is typed on this phone as a draft and
 * sends nothing; 提交 needs the form complete and writes to the server. Closing a form that holds something asks whether to keep it as a draft.
 * A draft belongs to one order: a new report is offered the last new-report draft, an order only its own.
 */
@Composable
fun FaultFormScreen(existingId: String?, startDraftId: String?, onClose: () -> Unit, onSaved: () -> Unit, initialAsset: com.personal.tpm.data.Jv? = null) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    val store = LocalDrafts.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState(OrderKind.Fault) }
    val snackbar = remember { SnackbarHostState() }
    val scopeKey = session.draftScope
    var request by remember { mutableStateOf<PickRequest?>(null) }
    var photoBusy by remember { mutableStateOf(false) }
    var showDrafts by remember { mutableStateOf(false) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var loadedData by remember { mutableStateOf("") }
    var loadedSelections by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var draftCount by remember { mutableStateOf(0) }
    val orderTarget = if (existingId == null) DraftTarget.NewReport else DraftTarget.Order(existingId)
    val draftsAllowed = existingId == null || existingId.isNotBlank()
    val worthKeeping = draftsAllowed && (if (existingId == null) FormRules.faultHasContent(form.jv) else (form.data.toString() != loadedData || form.selections != loadedSelections))

    fun applyDraft(draft: FormDraft, automatic: Boolean) {
        form.applyDraft(draft)
        draftId = draft.id
        scope.launch { snackbar.showSnackbar((if (automatic) "已加载上次保存的草稿（" else "已加载草稿（") + draft.title + "）") }
    }
    suspend fun initialize() {
        form.load(session, existingId)
        if (existingId == null && form.ready) form.applyDefaults(session, prefs)
        loadedData = form.data.toString(); loadedSelections = form.selections
        if(existingId==null && initialAsset!=null && form.ready) {
            form.loading=true
            try {
                val id=initialAsset["assetId"].text
                val code=initialAsset["assetCode"].text
                require(id.isNotBlank() && code.isNotBlank()) { "资产信息不完整，请手动选择报修资产" }
                val result=session.page("asset/pageAsset",filters=listOf(com.personal.tpm.data.filter("assetId",id)),limit=2)
                val asset=result["content"].array.singleOrNull { it["assetId"].text==id && it["assetCode"].text==code }
                require(asset!=null) { "当前登录账号无法核对该资产，请手动选择报修资产" }
                form.set("asset",asset.raw)
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { failure=e.message ?: "资产带入失败，请手动选择报修资产" }
            finally { form.loading=false }
        }
        if (draftsAllowed && form.ready && initialAsset==null) {
            val all = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.drafts(scopeKey) }
            val chosen = startDraftId?.let { id -> all.firstOrNull { it.id == id && orderTarget.matches(it) } }
            if (chosen != null) applyDraft(chosen, false)
            else all.filter(orderTarget::matches).firstOrNull()?.let { applyDraft(it, true) }
        }
        draftCount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.drafts(scopeKey, orderTarget).size }
    }
    LaunchedEffect(existingId,scopeKey,initialAsset?.toString()) { initialize() }

    fun keepDraft() {
        try {
            store.save(FormDraft(id = draftId ?: java.util.UUID.randomUUID().toString(), data = JSONObject(form.data.toString()), selections = form.selections, orderId = existingId, orderSn = form["sn"].text.ifEmpty { null }), scopeKey)
            onClose()
        } catch (e: Exception) { failure = "草稿没有保存成功：" + (e.message ?: "") }
    }
    /** Close directly, preserving entered content as a local draft. */
    fun close(): Boolean { if (worthKeeping) { keepDraft(); return false }; onClose(); return true }
    fun submit(committed: Boolean) {
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

    FormPage(
        title = if (existingId == null) "故障报修" else "编辑报修", busy = form.loading || form.saving || photoBusy, onClose = { if (!form.saving && !photoBusy) close() }, snackbar = snackbar, backEnabled = !form.saving && !photoBusy, interceptBack = worthKeeping, onBackGesture = { close() },
        actions = {
            if (draftsAllowed) IconButton(onClick = { showDrafts = true }) { Icon(Icons.Filled.Inbox, contentDescription = if (draftCount > 0) "草稿箱（$draftCount）" else "草稿箱") }
        },
        bottomBar = { FormActionBar(busy = form.saving || form.loading, enabled = form.ready && !form.loading && !photoBusy, onSave = { if (draftsAllowed) keepDraft() else submit(false) }, onSubmit = { submit(true) }) },
    ) {
        if (form.error != null && !form.ready) {
            AnimatedLabel(form.error ?: "", color = MaterialTheme.colorScheme.error)
            androidx.compose.material3.TextButton(onClick = { scope.launch { initialize() } }) { Text("重试") }
            return@FormPage
        }
        FormTitle("故障报修")
        GroupCard {
            InputRow("报修名称", form["faultName"].text, { form.set("faultName", it) }, required = true, keyboardType = KeyboardType.Text, limit = 64, placeholder = "请输入报修名称")
            PickRow("报修资产", listOf(form["asset"]["assetCode"].text,form["asset"].label).filter { it.isNotBlank() }.joinToString(" · "), true) { request = PickRequest(RefSource.Assets, { form.set("asset", it.raw) }) }
            DateTimeField("故障时间", form["faultTime"].text, dateOnly = false, required = true) { form.set("faultTime", it) }
        }
        FormTitle("申请与受理")
        GroupCard {
            PickRow("故障申请人", form["applyUser"].label, true) {
                request = PickRequest(RefSource.Users, { user -> form.setAll("applyUser" to user.raw, "applyDept" to (user["depts"].array.firstOrNull()?.raw ?: form["applyDept"].raw)) }, "fault.applyUser")
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
        FormTitle("现场照片")
        GroupCard { PhotosField(form["albums"], { form.set("albums", it) }, "asset_fault", { photoBusy = it }, { failure = "照片上传失败：$it" }) }
    }

    PickHost(request) { request = null }
    if (showDrafts) DraftSheet(scopeKey, orderTarget, onPick = { showDrafts = false; applyDraft(it, false) }, onDismiss = { showDrafts = false; draftCount = store.drafts(scopeKey, orderTarget).size })
    failure?.let { ErrorDialog("提示", it) { failure = null } }
    form.error?.takeIf { form.ready }?.let { ErrorDialog("提示", it) { form.error = null } }
}
