package com.personal.tpm.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Date
import kotlin.coroutines.resume

// Handling many orders at once. A list can enter a selection mode; only orders of one status are selected together (the batch does the same thing
// to each), at most 100. The batch runs in the foreground, one order after the other, with the same requests the single screens make, in the same
// order: read the order again, check it is still at the step the batch was started for, check the permission, read the conditions, read the next
// approvers, commit. Re-reading first is how an order somebody else handled meanwhile (or this account handled on another device) is caught
// before anything is written. A port of BatchSelection.swift / BatchProcessing.swift.

enum class BatchAction(val title: String) {
    Accept("批量受理"), Claim("批量接单"), SignIn("批量签到"), Complete("批量完成"), Approve("批量审批");

    /** Accepting and completing need the same answers for every order, filled in once. */
    val needsPreset: Boolean get() = this == Accept || this == Complete
    /** The other way out of the step: a sent-back order ("退单") or a rejected one ("驳回"). */
    val rejectWord: String get() = if (this == Claim || this == SignIn || this == Complete) "退单" else "驳回"
    val rejectTitle: String get() = "批量$rejectWord"

    companion object {
        /** The one batch action that fits orders of a status, or null when that status has none. */
        fun forGroup(group: String): BatchAction? = when (group) {
            "REPORTED" -> Accept; "RECEIVED" -> Claim; "SIGNED" -> SignIn; "REPAIRED" -> Complete
            "VERIFIED", "RESOLVED" -> Approve
            "ERP:PENDING", "ERP:APPROVED", "ERP:EXECUTING", "ERP:CONFIRM" -> Approve
            else -> null
        }
    }
}

/** What the batch does to the selected orders: take them forward, or send them back with a reason. */
enum class BatchOperation { Proceed, Reject }

/** The status an order is in, as the batch tells statuses apart: the fault state, or "ERP:" and the approval state. */
fun batchGroup(kind: OrderKind, faultState: String, approvalState: String): String = if (kind == OrderKind.Fault) faultState else "ERP:$approvalState"
fun batchGroupName(group: String): String = if (group.startsWith("ERP:")) "ERP " + approvalStateName(group.removePrefix("ERP:")) else faultStateName(group)

/** One order that can be selected. */
data class BatchItem(val id: String, val kind: OrderKind, val name: String, val reference: String, val group: String)

fun batchItem(kind: OrderKind, item: Jv) = BatchItem(
    id = item[kind.idKey].text, kind = kind, name = if (kind == OrderKind.Fault) item["faultName"].text else erpCardTitle(item),
    reference = if (kind == OrderKind.Fault) item["asset"]["assetCode"].text else item["outNumber"].text,
    group = batchGroup(kind, item["faultState"].text, item["approvalState"].text),
)

/** A todo task carries its order in `data`; the order's id is the task's business key. */
fun batchItem(todo: Jv): BatchItem? {
    val kind = todoKind(todo) ?: return null
    if (todo["suspended"].bool || todo["businessKey"].text.isEmpty()) return null
    val data = todo["data"]
    return BatchItem(
        id = todo["businessKey"].text, kind = kind, name = todoTitle(todo),
        reference = if (kind == OrderKind.Fault) data["asset"]["assetCode"].text else data["outNumber"].text,
        group = batchGroup(kind, data["faultState"].text, data["approvalState"].text),
    )
}

const val BATCH_LIMIT = 100

/** The end of today, the date a repair should be done by when nobody says otherwise. */
fun defaultEstimatedTime(now: Date = Date()): String =
    serverDate(Calendar.getInstance().apply { time = now; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59) }.time)

val Jv.isFilled: Boolean get() = isObject && keys.isNotEmpty()

// ---- the answers shared by every order of a batch ----

data class BatchMaterial(val material: Jv, val quantity: Double = 1.0) { val id: String get() = material["id"].text }

/** An existing outbound line (picked from the outbound orders) that every order of the batch links to the same way. */
data class BatchOutboundLine(val line: Jv, val quantity: Double = 1.0) { val id: String get() = outboundLineIdentity(line) }

/**
 * What the batch is answered with, once for all orders, exactly the fields the single screens ask for:
 * accept: the repair group and the date it should be done by, a repairer only to assign it to one; complete: the fault type, the work description
 * and how long it took, optionally ERP material; every step that hands over to a node the person must name: that node's approver.
 */
data class BatchPreset(
    val faultType: Jv = Jv.NULL,
    val group: Jv = Jv.NULL,
    val estimated: String = defaultEstimatedTime(),
    val assignUser: Jv = Jv.NULL,
    val content: String = "",
    val durationMinutes: Int? = null,
    val approverNodes: List<Jv> = emptyList(),
    val approvers: Map<String, List<String>> = emptyMap(),
    /** The reason written on every order that is sent back or rejected. */
    val comment: String = "",
    val materials: List<BatchMaterial> = emptyList(),
    val outboundLines: List<BatchOutboundLine> = emptyList(),
    val erpData: JSONObject = JSONObject(),
    val erpProject: Jv = Jv.NULL,
    val erpRemark: String = "",
    val erpRecipient: Jv = Jv.NULL,
    val erpDept: Jv = Jv.NULL,
    val erpDivision: Jv = Jv.NULL,
    val erpNodes: List<Jv> = emptyList(),
    val erpApprovers: Map<String, List<String>> = emptyMap(),
) {
    /** Assigning a repairer ends the accept step in "指派", which names no approver. */
    fun needsApprovers(action: BatchAction) = approverNodes.isNotEmpty() && !(action == BatchAction.Accept && assignUser.isFilled)

    fun isComplete(action: BatchAction, operation: BatchOperation = BatchOperation.Proceed): Boolean {
        if (operation == BatchOperation.Reject) return comment.isNotBlank()
        when (action) {
            BatchAction.Accept -> {
                if (estimated.isBlank()) return false
                if (group.isFilled && group["groupId"].text.isEmpty()) return false
                if (assignUser.isFilled && assignUser["userName"].text.isEmpty()) return false
            }
            BatchAction.Complete -> {
                if (!faultType.isFilled || content.isBlank() || (durationMinutes ?: 1) < 1) return false
                if (outboundLines.any { !it.quantity.isFinite() || it.quantity < 1 }) return false
                if (materials.isNotEmpty()) {
                    if (materials.any { !it.quantity.isFinite() || it.quantity < 1 }) return false
                    if (!erpProject.isFilled || erpRemark.isBlank()) return false
                    if (!erpRecipient.isFilled || !erpDept.isFilled || !erpDivision.isFilled) return false
                    if (missingAssigneeMessage(erpNodes, erpApprovers) != null) return false
                }
            }
            else -> {}
        }
        if (needsApprovers(action) && missingAssigneeMessage(approverNodes, approvers) != null) return false
        return true
    }
}

// ---- problems the person decides during a run ----

class BatchError(message: String) : Exception(message)
/** The person chose "skip" in a problem dialog: the order is left alone and counted as skipped, not failed. */
class BatchSkipped(message: String) : Exception(message)
/** The person chose "stop": the run ends here, this order is not counted as handled. */
class BatchStopped : Exception()

/** [canSkipShort]: the problem is that some materials are not enough, so "skip those materials and go on with the rest" is an answer. */
data class BatchProblem(val order: BatchItem, val title: String, val message: String, val canSkipShort: Boolean = false)
/** SkipShortages: leave out only the materials that are not enough; the others are still taken, and the order is still completed. */
enum class BatchChoice { Skip, WithoutMaterials, Stop, SkipShortages }
typealias BatchAsk = suspend (BatchProblem) -> BatchChoice

data class BatchFailure(val id: String, val name: String, val reference: String, val message: String)

// ---- the processor ----

object BatchProcessor {
    private class Saved(val note: String)
    private class Made(val rows: List<Jv>, val outNumber: String)

    suspend fun process(item: BatchItem, action: BatchAction, operation: BatchOperation, preset: BatchPreset, session: Session, prefs: PickerPrefs, ask: BatchAsk) =
        process(item, action, operation, preset, LiveBatchApi(session, prefs), ask)

    internal suspend fun process(item: BatchItem, action: BatchAction, operation: BatchOperation, preset: BatchPreset, session: BatchApi, ask: BatchAsk) {
        if (BatchAction.forGroup(item.group) != action || !preset.isComplete(action, operation)) throw BatchError("批量处理方案不完整或与单据状态不匹配")
        val detail = wrap("读取单据") { session.request(item.kind.detailPath, mapOf(item.kind.idKey to item.id)) }
        ensureStillAtStep(detail, item)
        val task = detail["taskInfo"]
        if (task["id"].text.isEmpty()) throw BatchError("这张单据现在没有待处理的任务，可能已被他人处理。")
        if (item.kind == OrderKind.Fault && task["category"].text != item.group) {
            throw BatchError("这张单据的任务已从「" + batchGroupName(item.group) + "」往下走了，可能已被他人或你在其他设备上处理。")
        }
        // Permission, branch, task data and required approvers are checked BEFORE any ERP/repair writes.
        val payload = prepareCommit(task, item, action, operation, preset, detail, session)
        var note = ""
        if (action == BatchAction.Complete && operation == BatchOperation.Proceed) {
            val saved = saveRepair(item, detail, preset, session, ask)
            note = saved.note
        }
        try { wrap("提交流程节点") { session.request("process/commitTask", body = payload) } }
        catch (e: BatchError) {
            val warning = if (action == BatchAction.Complete && operation == BatchOperation.Proceed)
                "维修结果已保存，但流程提交未确认成功；请先刷新单据核对，勿重复领料。" else ""
            throw BatchError(warning + (e.message ?: "") + note)
        }
    }

    /** Server and network failures keep their stage and server text; a cancelled request stays a cancellation. */
    private suspend fun <T> wrap(stage: String, block: suspend () -> T): T =
        try { block() } catch (e: CancellationException) { throw e } catch (e: BatchError) { throw e } catch (e: Exception) { throw BatchError(stageFailure(stage, e)) }

    private fun ensureStillAtStep(detail: Jv, item: BatchItem) {
        val now = batchGroup(item.kind, detail["faultState"].text, detail["approvalState"].text)
        if (now != item.group) throw BatchError("单据状态已由「" + batchGroupName(item.group) + "」变为「" + batchGroupName(now) + "」，可能已被他人或你在其他设备上处理。")
    }

    /** "完成": the repair result is saved first (`repair/handleRepair`), then the node is committed, like the single form. */
    private suspend fun saveRepair(item: BatchItem, detail: Jv, preset: BatchPreset, session: BatchApi, ask: BatchAsk): Saved {
        val repair = wrap("读取维修记录") { session.request("fault/getLatestRepairByFaultId", mapOf("faultId" to item.id)) }
        if (repair["repairId"].text.isEmpty()) throw BatchError("没有取得维修记录编号，请打开这张单据单独处理。")
        val repairTask = repair["assetFault"]["taskInfo"]["id"].text
        if (repairTask.isNotEmpty() && repairTask != detail["taskInfo"]["id"].text) throw BatchError("维修记录的任务已改变，请刷新后单独处理。")
        val data = JSONObject(repair.raw.toString())
        data.put("faultType", preset.faultType.raw)
        data.put("content", preset.content)
        if (!data.has("spend") || data.isNull("spend")) data.put("spend", 0)
        val now = Date()
        var start = preset.durationMinutes?.let { Date(now.time - it * 60_000L) } ?: parsedDate(if (repair["signTime"].isNull) repair["createTime"] else repair["signTime"])
        if (start.after(now)) start = now
        data.put("startTime", serverDate(start))
        data.put("endTime", serverDate(now))
        var lines = wrap("读取维修领料") { session.request("erpOutbound/getErpRepairMaterialsByRepairId", mapOf("repairId" to repair["repairId"].text)).array }
        for (line in lines) {
            val available = line["materialOutbound"]["actualQuantity"].number - line["materialOutbound"]["usedQuantity"].number
            if (line["useNum"].number < 1 || line["useNum"].number > available) throw BatchError("维修领料的使用数量超出可用范围，请打开这张单据单独处理。")
        }
        var note = ""
        var takesMaterial = true
        if (preset.outboundLines.isNotEmpty()) {
            val linked = linkOutbound(item, preset, lines, session, ask)
            if (linked != null) lines = lines + linked else takesMaterial = false
        }
        if (takesMaterial && preset.materials.isNotEmpty()) {
            val made = takeMaterials(item, detail, preset, session, ask)
            if (made != null) { lines = lines + made.rows; note = "（已创建出库单 ${made.outNumber}，请到 ERP 出库里核对）" }
        }
        data.put("erpRepairMaterials", JSONArray().also { a -> lines.forEach { a.put(it.raw) } })
        repairValidationError(Jv(data))?.let { throw BatchError("维修结果未提交：$it" + note) }
        try { session.request("repair/handleRepair", body = repairPayload(data, repair["repairId"].raw)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw BatchError(stageFailure("保存维修结果", e) + note) }
        return Saved(note)
    }

    private suspend fun decideLines(item: BatchItem, title: String, message: String, ask: BatchAsk): List<Jv>? = when (ask(BatchProblem(item, title, message))) {
        BatchChoice.WithoutMaterials, BatchChoice.SkipShortages -> null
        BatchChoice.Stop -> throw BatchStopped()
        BatchChoice.Skip -> throw BatchSkipped(message)
    }

    /** Links the preset outbound lines to this order's repair, each in the preset quantity, after checking what is left of each right now. Null means "complete without material". */
    private suspend fun linkOutbound(item: BatchItem, preset: BatchPreset, existing: List<Jv>, session: BatchApi, ask: BatchAsk): List<Jv>? {
        val rows = mutableListOf<Jv>()
        val problems = mutableListOf<String>()
        for (want in preset.outboundLines) {
            val number = want.line["outbound"]["outNumber"].text
            val page = try {
                session.page(
                    "erpOutbound/pageErpMaterialOutbound", 0,
                    listOf(filter("outbound.outNumber", number, "like"), filter("material.materialCode", want.line["material"]["materialCode"].text, "like")), limit = 50,
                )
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { return decideLines(item, "读取出库物料失败", stageFailure("读取出库物料", e), ask) }
            val name = describe(want.line["material"]) + "（" + number + "）"
            val current = page["content"].array.firstOrNull { outboundLineIdentity(it) == want.id }
            if (current == null) { problems += "$name：这条出库物料已经不存在"; continue }
            val available = current["actualQuantity"].number - current["usedQuantity"].number
            if (available < want.quantity) { problems += name + "：可用 " + quantityText(available) + "，需要 " + quantityText(want.quantity); continue }
            if (existing.any { outboundLineIdentity(it["materialOutbound"]) == want.id }) { problems += "$name：这张单已经关联过这条出库物料"; continue }
            rows += Jv(Jv.obj("material" to current["material"].raw, "outbound" to current["outbound"].raw, "materialOutbound" to current.raw, "useNum" to want.quantity))
        }
        if (problems.isNotEmpty()) {
            // Only the lines that cannot be linked are left out when the answer is "skip"; the others are still linked to this order.
            val message = problems.joinToString("\n")
            return when (ask(BatchProblem(item, "出库物料不够", message, canSkipShort = true))) {
                BatchChoice.SkipShortages -> rows
                BatchChoice.WithoutMaterials -> null
                BatchChoice.Stop -> throw BatchStopped()
                BatchChoice.Skip -> throw BatchSkipped(message)
            }
        }
        return rows
    }

    private fun describe(material: Jv) = listOf(material["materialDesc"].text, material["materialCode"].text.takeIf { it.isNotEmpty() }?.let { "料号 $it" }.orEmpty()).filter { it.isNotEmpty() }.joinToString(" · ")

    /** Makes this order's own ERP outbound order for the preset material and returns the lines to link to the repair. Null means "complete without material". */
    private suspend fun takeMaterials(item: BatchItem, detail: Jv, preset: BatchPreset, session: BatchApi, ask: BatchAsk): Made? {
        suspend fun decide(title: String, message: String): Made? = when (ask(BatchProblem(item, title, message))) {
            BatchChoice.WithoutMaterials, BatchChoice.SkipShortages -> null
            BatchChoice.Stop -> throw BatchStopped()
            BatchChoice.Skip -> throw BatchSkipped(message)
        }
        // 1. The stock as it is now: earlier orders of this batch may have used some of it.
        val fresh = mutableListOf<Jv>()
        val quantities = mutableListOf<Double>()
        val shortages = mutableListOf<String>()
        for (line in preset.materials) {
            val page = try { session.page("erpOutbound/pageErpMaterial", 0, listOf(filter("materialCode", line.material["materialCode"].text, "eq")), limit = 100) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { return decide("读取库存失败", stageFailure("读取库存", e)) }
            val current = page["content"].array.firstOrNull { it["id"] == line.material["id"] }
            if (current == null) { shortages += describe(line.material) + "：账上已经没有这条库存"; continue }
            val onhand = current["onhandQty"].number
            if (onhand < line.quantity) { shortages += describe(current) + "：库存 " + quantityText(onhand) + "，需要 " + quantityText(line.quantity); continue }
            fresh += current
            quantities += line.quantity
        }
        if (shortages.isNotEmpty()) {
            // "Skip" here means: leave out the materials that are not enough and still take the others for this order.
            val message = shortages.joinToString("\n")
            when (ask(BatchProblem(item, "物料不够", message, canSkipShort = true))) {
                BatchChoice.SkipShortages -> {}
                BatchChoice.WithoutMaterials -> return null
                BatchChoice.Stop -> throw BatchStopped()
                BatchChoice.Skip -> throw BatchSkipped(message)
            }
        }
        if (fresh.isEmpty()) return null

        // 2. The outbound order, built and checked like the ERP form does.
        val now = Date()
        val data = JSONObject(preset.erpData.toString())
        data.put("recipientUser", preset.erpRecipient.raw).put("dept", preset.erpDept.raw).put("division", preset.erpDivision.raw)
        data.put("projectCode", preset.erpProject["projectCode"].raw ?: JSONObject.NULL).put("projectDesc", preset.erpProject["projectName"].raw ?: JSONObject.NULL)
        data.put("remark", preset.erpRemark).put("referenceSn", detail["sn"].raw ?: JSONObject.NULL)
        data.put("applyDate", serverDate(now, dateOnly = true)).put("outboundTime", serverDate(now))
        data.put("erpMaterialOutbounds", JSONArray().also { a ->
            fresh.forEachIndexed { index, material ->
                a.put(Jv.obj("material" to material.raw, "id" to Jv.obj("imaterialId" to material["id"].raw, "outboundId" to null), "entryQuantity" to quantities[index]))
            }
        })
        FormRules.validate(OrderKind.Erp, Jv(data), preset.erpNodes, preset.erpApprovers)?.let { return decide("领料单不完整", it) }
        val payload = FormRules.payload(OrderKind.Erp, data, preset.erpNodes, preset.erpApprovers, true).also { it.remove("project") }
        val result = try { session.request("erpOutbound/addErpOutbound", body = payload) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { return decide("创建 ERP 出库单失败", stageFailure("创建 ERP 出库单", e)) }

        // 3. Its lines, linked to the repair with the preset quantity.
        val outboundId = result["outboundId"].text
        if (outboundId.isEmpty()) return decide("创建 ERP 出库单失败", "服务器没有返回出库单编号，请到 ERP 出库里核对是否已创建。")
        val created = try { session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to outboundId)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw BatchError(stageFailure("读取新建的出库物料", e) + "（出库单 " + result["outNumber"].text + " 已创建）") }
        val rows = try { linkCreatedBatchMaterials(created.array, fresh, quantities) }
        catch (e: BatchError) { throw BatchError(e.message + "（出库单 ${result["outNumber"].text.ifEmpty { outboundId }} 已创建，请核对后单独关联，勿直接重复领料）") }
        return Made(rows, result["outNumber"].text)
    }

    // ---- the node ----

    private suspend fun prepareCommit(task: Jv, item: BatchItem, action: BatchAction, operation: BatchOperation, preset: BatchPreset, detail: Jv, session: BatchApi): JSONObject {
        val state = wrap("读取处理权限") { session.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text)) }
        if (!state["canSubmit"].bool) throw BatchError("当前账号不能处理这一步，可能已被他人处理或已转办。")
        val form = wrap("读取处理选项") { session.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to task["id"].text)) }
        val conditions = form["flowConditions"].array
        val rejecting = operation == BatchOperation.Reject
        val assigning = !rejecting && action == BatchAction.Accept && preset.assignUser.isFilled
        val condition: String
        if (rejecting) {
            val found = conditions.firstOrNull(::isRejectCondition) ?: throw BatchError("这一步没有「${action.rejectWord}」选项，请打开这张单据单独处理。")
            condition = found["value"].text
        } else {
            condition = if (assigning) conditions.firstOrNull { it["value"].text in listOf("assign", "appoint") }?.get("value")?.text.orEmpty() else "agree"
            if (conditions.none { it["value"].text == condition }) throw BatchError("这一步没有「" + (if (assigning) "指派" else "同意") + "」选项，请打开这张单据单独处理。")
        }
        val assignees = if (condition == "agree") nextApprovers(task, item.kind, preset, session) else null
        val payload = commitTaskPayload(task, condition, if (rejecting) preset.comment else "", assignees)
        if (!rejecting) commitTaskData(task, item, action, assigning, preset, detail, session)?.let { payload.put("taskData", it) }
        return payload
    }

    /** The next nodes of a task and who would be ticked for them before the person chooses anything. */
    private suspend fun approverModel(task: Jv, kind: OrderKind, session: BatchApi): Pair<List<Jv>, Map<String, List<String>>> {
        val values = wrap("读取下一节点审批人") {
            session.request("workflow/getAssigneeElementsByProcessCategory", mapOf("processCategory" to kind.prefix, "processInstanceId" to task["processInstanceId"].text, "taskKey" to task["taskDefinitionKey"].text)).array
        }
        val picked = values.associate { it["variableName"].text to usernames(it["assignees"]) }
        return values to session.approverDefaults(values, picked, kind)
    }

    /** The approvers of the next node: what the process suggests, replaced by the person's own default, and finally by what was picked for the whole batch. */
    private suspend fun nextApprovers(task: Jv, kind: OrderKind, preset: BatchPreset, session: BatchApi): JSONObject {
        val (nodes, base) = approverModel(task, kind, session)
        val selections = base.toMutableMap()
        for (node in nodes) {
            val key = node["variableName"].text
            preset.approvers[key]?.takeIf { it.isNotEmpty() }?.let { selections[key] = it }
        }
        missingAssigneeMessage(nodes, selections)?.let { throw BatchError("$it。请单独处理这张单据，或在批量方案里选好审批人。") }
        return assigneesPayload(nodes, selections)
    }

    /** For the preset sheet: the approver nodes the first selected order leads to, with what is already ticked. Every order of one status leads to the same next node. */
    suspend fun approverSetup(item: BatchItem, session: Session, prefs: PickerPrefs): Pair<List<Jv>, Map<String, List<String>>> {
        val detail = wrap("读取单据") { session.request(item.kind.detailPath, mapOf(item.kind.idKey to item.id)) }
        ensureStillAtStep(detail, item)
        val task = detail["taskInfo"]
        if (task["id"].text.isEmpty()) return emptyList<Jv>() to emptyMap()
        return approverModel(task, item.kind, LiveBatchApi(session, prefs))
    }

    /** The `taskData` the single screens send: the repair arrangement when a fault is accepted or claimed, the material lines when an ERP order is approved. */
    private suspend fun commitTaskData(task: Jv, item: BatchItem, action: BatchAction, assigning: Boolean, preset: BatchPreset, detail: Jv, session: BatchApi): String? {
        if (item.kind == OrderKind.Fault && (action == BatchAction.Accept || action == BatchAction.Claim)) {
            // The group chosen for the batch wins; without one, each order keeps the group it already carries (as the single screen does).
            val group = if (action == BatchAction.Accept && preset.group.isFilled) preset.group else detail["userGroup"]
            val groupId = group["groupId"]
            if (groupId.text.isEmpty()) throw BatchError("这张单据没有维修班组，请打开单独处理。")
            val data = JSONObject()
                .put("repairWay", "SELF").put("faultType", detail["faultType"].raw ?: JSONObject.NULL).put("groupId", groupId.raw)
                .put("estimatedCompleteTime", if (action == BatchAction.Accept && preset.estimated.isNotEmpty()) preset.estimated else defaultEstimatedTime())
                .put("repairman", session.accountName).put("repairmanPhone", JSONObject.NULL).put("userGroup", JSONObject().put("groupId", groupId.raw))
            if (assigning) data.put("assignUser", preset.assignUser.raw).put("assignUserName", preset.assignUser["userName"].text)
            return data.toString()
        }
        if (item.kind == OrderKind.Erp && task["category"].text in listOf("EXECUTING", "CONFIRM")) {
            val lines = wrap("读取出库物料") { session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", mapOf("outboundId" to task["businessKey"].text)) }
            for (line in lines.array) if (!line["actualQuantity"].number.isFinite() || line["actualQuantity"].number < 1 || line["actualQuantity"].number > line["entryQuantity"].number) throw BatchError("实发数量应在 1 与申请数量之间，请打开这张单据填写实发数量。")
            return JSONArray().also { a -> lines.array.forEach { a.put(it.raw) } }.toString()
        }
        return null
    }
}

/** Whether a server answer says the account has no right to do this (not that this one order is in the wrong state). */
fun isPermissionFailure(message: String): Boolean =
    (message.contains("没有") && (message.contains("角色") || message.contains("权限"))) || message.contains("权限不足") || message.contains("无权") ||
        message.contains("（403）") || message.contains("(403)") || message.contains("不能进行抢单") || message.contains("不允许")

// ---- the run ----

/** One batch being processed: the progress the sheet shows, and the question asked of the person when a problem comes up. */
class BatchRun(val action: BatchAction, val operation: BatchOperation, val items: List<BatchItem>, val preset: BatchPreset) {
    var processed by mutableIntStateOf(0); private set
    val succeeded = mutableStateListOf<String>()
    val failures = mutableStateListOf<BatchFailure>()
    /** Orders the person chose to skip when a problem came up (not enough material, ...). */
    val skipped = mutableStateListOf<BatchFailure>()
    var current by mutableStateOf<BatchItem?>(null); private set
    var running by mutableStateOf(false); private set
    var finished by mutableStateOf(false); private set
    /** Set when the run ended before its last order, and why. */
    var endedEarly by mutableStateOf<String?>(null); private set
    /** A question for the person; the run waits for the answer. */
    var problem by mutableStateOf<BatchProblem?>(null); private set
    private var waiting: CancellableContinuation<BatchChoice>? = null
    /** "后面遇到同样的情况都这样处理". */
    private val decisions = BatchDecisions()
    private var stopRequested = false

    val title: String get() = if (operation == BatchOperation.Reject) action.rejectTitle else action.title
    val total: Int get() = items.size
    val remaining: Int get() = total - processed

    fun stop() {
        stopRequested = true
        if (waiting != null) answer(BatchChoice.Stop, false)
    }

    private suspend fun ask(question: BatchProblem): BatchChoice {
        // A standing "skip the short materials" only fits a problem of short materials; any other problem is asked again.
        decisions.choice(question)?.let { return it }
        problem = question
        return suspendCancellableCoroutine { continuation -> waiting = continuation }
    }

    fun answer(choice: BatchChoice, applyToRest: Boolean) {
        if (applyToRest) problem?.let { decisions.remember(it, choice) }
        problem = null
        val continuation = waiting
        waiting = null
        if (continuation?.isActive == true) continuation.resume(choice)
    }

    /** One order at a time, in the foreground: the server does not take orders handled side by side. */
    suspend fun start(session: Session, prefs: PickerPrefs) {
        if (running || finished) return
        val originalScope = session.scope.value
        running = true
        try {
            for (item in items) {
                if (stopRequested) { endedEarly = "已停止，还有 $remaining 张没有处理。"; break }
                if (!session.signedIn) { endedEarly = "登录已失效，还有 $remaining 张没有处理，请重新登录。"; break }
                if (session.scope.value != originalScope) { endedEarly = "账号或厂区已切换，还有 $remaining 张没有处理。"; break }
                current = item
                try {
                    BatchProcessor.process(item, action, operation, preset, session, prefs) { ask(it) }
                    succeeded += item.id
                } catch (skip: BatchSkipped) {
                    skipped += BatchFailure(item.id, item.name, item.reference, skip.message ?: "")
                } catch (stop: BatchStopped) {
                    endedEarly = "已停止，还有 $remaining 张没有处理。"; break
                } catch (cancel: CancellationException) {
                    endedEarly = "账号或厂区已切换，还有 $remaining 张没有处理。"; break
                } catch (e: Exception) {
                    val message = e.message ?: "未知错误"
                    failures += BatchFailure(item.id, item.name, item.reference, message)
                    // The account may not do this at all (no repair role, no right to approve): the same answer would come for every order, so the run stops here.
                    if (isPermissionFailure(message)) {
                        processed += 1
                        endedEarly = "遇到权限错误，已停止，还有 $remaining 张没有处理。请换有权限的账号，或到服务器端给这个账号开通权限。"
                        break
                    }
                }
                processed += 1
            }
        } finally { current = null; running = false; finished = true }
    }
}
