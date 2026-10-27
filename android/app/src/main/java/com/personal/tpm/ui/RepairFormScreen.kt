package com.personal.tpm.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.personal.tpm.data.Jv
import com.personal.tpm.data.RefSource
import com.personal.tpm.data.materialQuantity
import com.personal.tpm.data.materialUnit
import com.personal.tpm.data.outboundLineIdentity
import com.personal.tpm.data.parsedDate
import com.personal.tpm.data.quantityText
import com.personal.tpm.data.repairPayload
import com.personal.tpm.data.repairSubmissionKeys
import com.personal.tpm.data.repairValidationError
import com.personal.tpm.data.serverDate
import com.personal.tpm.data.stageFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date

/**
 * "完成任务" of a repair: the repair result. Fault type, cost, start and end time, helpers, ERP material (new outbound order or lines already issued),
 * work description, pictures. 保存 keeps it on this phone as a draft of this order; 提交 saves it to the server (`repair/handleRepair`) and goes on to
 * the step's own page ([onSubmitted]). Reading the repair record, the ERP lines and writing it each say in which stage they failed.
 */
@Composable
fun RepairFormScreen(faultId: String, onClose: () -> Unit, onSubmitted: () -> Unit) {
    val session = LocalSession.current
    val store = LocalDrafts.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val scopeKey = session.repairDraftScope
    var data by remember { mutableStateOf(JSONObject()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var photoBusy by remember { mutableStateOf(false) }
    var request by remember { mutableStateOf<PickRequest?>(null) }
    var creatingErp by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<Pair<String, String>?>(null) }
    var draftId by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    val draftKeys = repairSubmissionKeys + "erpRepairMaterials"
    val jv = Jv(data)

    fun update(key: String, value: Any?) { data = JSONObject(data.toString()).put(key, value ?: JSONObject.NULL) }
    fun rows(): List<Jv> = Jv(data.opt("erpRepairMaterials")).array
    fun setRows(list: List<Any?>) = update("erpRepairMaterials", JSONArray().also { a -> list.forEach { a.put(it) } })
    fun addMaterial(material: Jv, allowUnissued: Boolean = false) {
        if (!material["material"].isObject) { failure = "提示" to "此出库ERP物料信息丢失，请与ERP联系"; return }
        if (!allowUnissued && material["actualQuantity"].number - material["usedQuantity"].number < 1) { failure = "提示" to "库存小于1，不能选择"; return }
        if (rows().any { it["material"]["id"] == material["id"]["materialId"] && it["outbound"]["outboundId"] == material["id"]["outboundId"] }) { failure = "提示" to "已选择此出库物料"; return }
        setRows(rows().map { it.raw } + Jv.obj("material" to material["material"].raw, "outbound" to material["outbound"].raw, "materialOutbound" to material.raw, "useNum" to 1))
    }

    suspend fun load() {
        loading = true
        try {
            val repair = try { session.request("fault/getLatestRepairByFaultId", mapOf("faultId" to faultId)) } catch (e: CancellationException) { throw e } catch (e: Exception) { throw IllegalStateException(stageFailure("加载维修任务", e)) }
            val copy = JSONObject(repair.raw.toString())
            if (!copy.has("spend") || copy.isNull("spend")) copy.put("spend", 0)
            copy.put("startTime", serverDate(parsedDate(if (repair["signTime"].isNull) repair["createTime"] else repair["signTime"])))
            copy.put("endTime", serverDate(Date()))
            copy.put("erpRepairMaterials", try { session.request("erpOutbound/getErpRepairMaterialsByRepairId", mapOf("repairId" to repair["repairId"].text)).raw ?: JSONArray() } catch (e: CancellationException) { throw e } catch (e: Exception) { throw IllegalStateException(stageFailure("加载维修领料", e)) })
            data = copy
            loaded = true
            // A draft of this order's result is put back over the fresh record.
            store.drafts(scopeKey, DraftTarget.Order(faultId)).firstOrNull()?.let { draft ->
                val merged = JSONObject(data.toString())
                for (key in draftKeys) if (draft.data.has(key)) merged.put(key, draft.data.get(key))
                data = merged; draftId = draft.id
                scope.launch { snackbar.showSnackbar("已加载上次保存的草稿（" + draft.title + "）") }
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { failure = "加载失败" to (e.message ?: "未知错误") }
        finally { loading = false }
    }
    LaunchedEffect(faultId) { load() }

    fun keepDraft() {
        if (!loaded) return
        val kept = JSONObject()
        for (key in draftKeys) if (data.has(key)) kept.put(key, data.get(key))
        try {
            store.save(FormDraft(id = draftId ?: java.util.UUID.randomUUID().toString(), data = kept, orderId = faultId, orderSn = jv["assetFault"]["sn"].text.ifEmpty { null }), scopeKey)
            onClose()
        } catch (e: Exception) { failure = "提示" to ("草稿没有保存成功：" + (e.message ?: "")) }
    }
    fun submit() {
        val capped = com.personal.tpm.data.clampRepairQuantities(data)
        data = capped
        repairValidationError(Jv(capped))?.let { failure = "请检查表单" to it; return }
        if (saving) return
        if (jv["repairId"].isNull) { failure = "保存未完成" to "保存维修结果失败：没有取得维修记录编号，请关闭后重新打开此任务"; return }
        saving = true
        scope.launch {
            try {
                try { session.request("repair/handleRepair", body = repairPayload(capped, jv["repairId"].raw)) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { throw IllegalStateException(stageFailure("保存维修结果", e)) }
                draftId?.let { store.remove(it, scopeKey); draftId = null }
                onSubmitted()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure = "保存未完成" to (e.message ?: "") }
            finally { saving = false }
        }
    }

    FormPage(
        title = "完成任务", busy = loading || saving || photoBusy, onClose = { if (!saving && !photoBusy) onClose() }, snackbar = snackbar, backEnabled = !saving && !photoBusy,
        bottomBar = { FormActionBar(busy = saving, enabled = loaded && !photoBusy, onSave = { keepDraft() }, onSubmit = { submit() }, submitLabel = "提交") },
    ) {
        FormTitle("维修任务信息")
        GroupCard {
            PickRow("维修故障类型", jv["faultType"].label, true) { request = PickRequest(RefSource.FaultTypes, { update("faultType", it.raw) }) }
            var spend by remember(loaded) { mutableStateOf(quantityText(jv["spend"].number)) }
            InputRow("维修花费", spend, { spend = it; update("spend", it.toDoubleOrNull() ?: 0) }, suffix = "元")
            DateTimeField("维修开始时间", jv["startTime"].text, dateOnly = false, required = true) { update("startTime", it) }
            DateTimeField("维修结束时间", jv["endTime"].text, dateOnly = false, required = true) { update("endTime", it) }
            val helpers = jv["repairUsers"].array
            PickRow("辅助维修人", helpers.joinToString("、") { it["realName"].text.ifEmpty { it["userName"].text } }, false) {
                request = PickRequest(
                    RefSource.Users,
                    onPick = { user ->
                        val current = Jv(data.opt("repairUsers")).array
                        val name = user["userName"].text
                        val next = if (current.any { it["userName"].text == name }) current.filter { it["userName"].text != name } else current + user
                        update("repairUsers", JSONArray().also { a -> next.forEach { a.put(it.raw) } })
                    },
                    multiple = true, selected = helpers.map { it["userName"].text }.toSet(),
                )
            }
        }
        FormTitle("ERP 出库")
        GroupCard {
            ActionRow(Icons.Filled.Add, "新建出库单") { creatingErp = true }
            GroupDivider()
            ActionRow(Icons.Filled.Inventory2, "添加出库物料") {
                request = PickRequest(RefSource.OutboundMaterials, { addMaterial(it) }, multiple = true, selected = rows().map { outboundLineIdentity(it["materialOutbound"]) }.toSet(), onRemove = { m -> setRows(rows().filter { outboundLineIdentity(it["materialOutbound"]) != outboundLineIdentity(m) }.map { it.raw }) })
            }
            rows().forEachIndexed { index, row ->
                GroupDivider()
                val material = row["material"]
                val line = row["materialOutbound"]
                var use by remember(index, row["materialOutbound"]["id"].toString()) { mutableStateOf(quantityText(row["useNum"].number)) }
                val maximum = (line["actualQuantity"].number - line["usedQuantity"].number).coerceAtLeast(0.0)
                LaunchedEffect(row["useNum"].number, maximum) {
                    if ((use.toDoubleOrNull() ?: 0.0) > maximum && row["useNum"].number <= maximum) use = quantityText(row["useNum"].number)
                }
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    Row(Modifier.padding(start = 26.dp, end = 12.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            AnimatedLabel(material["materialDesc"].text, style = MaterialTheme.typography.titleSmall)
                            AnimatedLabel("料号：" + com.personal.tpm.data.repairMaterialCode(row).ifEmpty { "未提供" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                listOf(row["outbound"]["outNumber"].text, row["outbound"]["recipientUser"]["realName"].text, "申请 " + materialQuantity(line["entryQuantity"].text, material), "实发 " + materialQuantity(line["actualQuantity"].text, material),
                                    "剩余 " + materialQuantity(quantityText(line["actualQuantity"].number - line["usedQuantity"].number), material)).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { setRows(rows().filterIndexed { i, _ -> i != index }.map { it.raw }) }) { Icon(Icons.Filled.Delete, contentDescription = "删除明细", tint = MaterialTheme.colorScheme.error) }
                    }
                    InputRow("使用数量", use, { use = it; setRows(rows().mapIndexed { i, r -> if (i == index) JSONObject(r.raw.toString()).put("useNum", it.toDoubleOrNull() ?: 0.0) else r.raw }) }, required = true, suffix = materialUnit(material), maximum = maximum)
                }
            }
        }
        FormTitle("工作说明")
        TextEntry("工作说明", jv["content"].text, { update("content", it) }, limit = 255, minLines = 2, required = true, placeholder = "请输入说明")
        FormTitle("图片")
        GroupCard { PhotosField(jv["albums"], { update("albums", it) }, "asset_fault", { photoBusy = it }, { failure = "照片上传失败" to it }) }
    }

    PickHost(request) { request = null }
    // The ERP form is laid over this page inside the same window (its own back key closes it).
    if (creatingErp) SlideInOverlay {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            ErpFormScreen(existingId = null, referenceSn = jv["assetFault"]["sn"].text, onClose = { creatingErp = false }, onSaved = { result ->
                scope.launch {
                    try {
                        val created = session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to result["outboundId"].text))
                        created.array.forEach { addMaterial(it, allowUnissued = true) }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure = "提示" to (e.message ?: "读取新出库单失败") }
                }
            })
        }
    }
    failure?.let { (title, message) -> ErrorDialog(title, message) { failure = null } }
}

@Composable
private fun ActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        AnimatedLabel(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}
