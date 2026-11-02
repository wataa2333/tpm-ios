package com.personal.tpm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.tpm.data.DraftTarget
import com.personal.tpm.data.FormDraft
import com.personal.tpm.data.FormRules
import com.personal.tpm.data.Jv
import com.personal.tpm.data.OrderKind
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.materialQuantity
import com.personal.tpm.data.materialUnit
import com.personal.tpm.data.quantityText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The ERP outbound (material requisition) order: a new one, or (existingId) the edit of one that has not started its process. [referenceSn] ties a
 * new order to the repair it is made from. 保存 keeps a draft on this phone (nothing is sent, nothing required); 提交 needs the form complete.
 * [onSaved] gets the server's answer (the order's id).
 */
@Composable
fun ErpFormScreen(existingId: String?, referenceSn: String?, onClose: () -> Unit, onSaved: (Jv) -> Unit) {
    val session = LocalSession.current
    val prefs = LocalPickerPrefs.current
    val store = LocalDrafts.current
    val scope = rememberCoroutineScope()
    val form = remember { FormState(OrderKind.Erp) }
    val snackbar = remember { SnackbarHostState() }
    val scopeKey = session.erpDraftScope
    var request by remember { mutableStateOf<PickRequest?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var draftId by remember { mutableStateOf<String?>(null) }
    val target = if (existingId == null) DraftTarget.NewReport else DraftTarget.Order(existingId)
    val draftsAllowed = existingId == null || existingId.isNotBlank()

    LaunchedEffect(existingId) {
        form.load(session, existingId, referenceSn)
        if (existingId == null && form.ready) form.applyDefaults(session, prefs)
        if (draftsAllowed && form.ready) store.drafts(scopeKey, target).firstOrNull()?.let { draft ->
            form.applyDraft(draft); draftId = draft.id
            launch { snackbar.showSnackbar("已加载上次保存的草稿（" + draft.title + "）") }
        }
    }
    fun keepDraft() {
        if (!form.ready || !draftsAllowed) return
        try {
            store.save(FormDraft(id = draftId ?: java.util.UUID.randomUUID().toString(), data = JSONObject(form.data.toString()), selections = form.selections, orderId = existingId, orderSn = form["outNumber"].text.ifEmpty { null }), scopeKey)
            onClose()
        } catch (e: Exception) { failure = "草稿没有保存成功：" + (e.message ?: "") }
    }
    fun submit() {
        form["erpMaterialOutbounds"].array.forEachIndexed { index, line ->
            val stock=line["material"]["onhandQty"]
            val requested=line["entryQuantity"].number
            if(!stock.isNull && stock.number.isFinite() && stock.number>=0 && requested.isFinite() && requested>stock.number) form.setQuantity(index,stock.number)
        }
        FormRules.validate(OrderKind.Erp, form.jv, form.nodes, form.selections)?.let { failure = it; return }
        if (form.saving) return
        form.saving = true
        scope.launch {
            try {
                val payload = FormRules.payload(OrderKind.Erp, form.data, form.nodes, form.selections, true)
                payload.remove("project")
                val result = session.request(if (existingId == null) "erpOutbound/addErpOutbound" else "erpOutbound/updateErpOutbound", body = payload)
                draftId?.let { store.remove(it, scopeKey) }
                session.notifyChanged()
                onSaved(result); onClose()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = e.message ?: "提交失败" }
            finally { form.saving = false }
        }
    }

    FormPage(
        title = "ERP 出库", busy = form.loading || form.saving, onClose = { if (!form.saving) onClose() }, snackbar = snackbar, backEnabled = !form.saving,
        bottomBar = { FormActionBar(busy = form.saving, enabled = form.ready, onSave = { keepDraft() }, onSubmit = { submit() }) },
    ) {
        if (form.error != null && !form.ready) {
            AnimatedLabel(form.error ?: "", color = MaterialTheme.colorScheme.error)
            GlassTextButton(onClick = { scope.launch { form.load(session, existingId, referenceSn) } }) { Text("重试") }
            return@FormPage
        }
        FormTitle("领料申请")
        GroupCard {
            PickRow("申请人", form["user"].label, true) { request = PickRequest(RefSource.Users, { form.set("user", it.raw) }, "erp.user") }
            DateTimeField("申请日期", form["applyDate"].text, dateOnly = true, required = true) { form.set("applyDate", it) }
            PickRow("项目号", listOf(form["projectCode"].text, form["projectDesc"].text).filter { it.isNotEmpty() }.joinToString(" "), true) {
                request = PickRequest(RefSource.Projects, { form.setAll("projectCode" to it["projectCode"].raw, "projectDesc" to it["projectName"].raw) })
            }
            if (form["referenceSn"].text.isNotEmpty()) { ReadOnlyFormRow("关联单号", form["referenceSn"].text) }
            PickRow("领用人", form["recipientUser"].label, true) { request = PickRequest(RefSource.Users, { form.set("recipientUser", it.raw) }, "erp.recipientUser") }
            PickRow("领用部门", form["dept"].label, true) { request = PickRequest(RefSource.Departments, { form.set("dept", it.raw) }, "erp.dept") }
            PickRow("ERP记账部门", form["division"].label, true) { request = PickRequest(RefSource.Divisions, { form.set("division", it.raw) }, "erp.division") }
            DateTimeField("出库时间", form["outboundTime"].text, dateOnly = false, required = true) { form.set("outboundTime", it) }
        }
        val lines = form["erpMaterialOutbounds"].array
        FormTitle("出库物料 · ${lines.size} 项")
        GroupCard {
            Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickableRow { request = PickRequest(RefSource.Materials, { form.addMaterial(it) }, multiple = true, selected = form["erpMaterialOutbounds"].array.map { it["material"]["id"].text }.toSet(), onRemove = { m -> form.set("erpMaterialOutbounds", org.json.JSONArray().also { a -> form["erpMaterialOutbounds"].array.filter { it["material"]["id"] != m["id"] }.forEach { a.put(it.raw) } }) }) }.padding(horizontal = 10.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text("添加出库物料", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            lines.forEachIndexed { index, line ->
                GroupDivider()
                val material = line["material"]
                var text by remember(index, line["material"]["id"].text) { mutableStateOf(quantityText(line["entryQuantity"].number)) }
                LaunchedEffect(line["entryQuantity"].number) {
                    val stock=material["onhandQty"]
                    if(!stock.isNull && (text.toDoubleOrNull() ?: 0.0)>stock.number && line["entryQuantity"].number<=stock.number) text=quantityText(line["entryQuantity"].number)
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(Modifier.padding(start = 26.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            AnimatedLabel(material["materialDesc"].text, style = MaterialTheme.typography.titleSmall)
                            AnimatedLabel(listOf(material["materialCode"].text, "库存 " + materialQuantity(material["onhandQty"].text, material), material["lot"].text, material["subInv"].text).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        GlassIconButton(onClick = { form.removeLine(index) }) { Icon(Icons.Filled.Delete, contentDescription = "删除明细", tint = MaterialTheme.colorScheme.error) }
                    }
                    InputRow("申请数量", text, { text = it; form.setQuantity(index, it.toDoubleOrNull() ?: 0.0) }, required = true, suffix = materialUnit(material), maximum = if(material["onhandQty"].isNull) null else material["onhandQty"].number.coerceAtLeast(0.0))
                }
            }
        }
        TextEntry("用途", form["remark"].text, { form.set("remark", it) }, limit = 140, minLines = 2, required = true, placeholder = "请输入用途")
        if (form.nodes.any { it["assignType"].text in listOf("ASSIGN", "SUPERVISOR") }) {
            FormTitle("审批人")
            GroupCard { AssigneeRows(form.nodes, form.selections, if (existingId == null) "assignee.erp" else null, form::update) { request = it } }
        }
    }

    PickHost(request) { request = null }
    failure?.let { ErrorDialog("提示", it) { failure = null } }
    form.error?.takeIf { form.ready }?.let { ErrorDialog("提示", it) { form.error = null } }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))
