package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** The eight states of a repair order, in workflow order (the same codes and names as the iOS app). */
enum class FaultState(val code: String, val title: String) {
    Draft("UNTREATED", "报修"), Reported("REPORTED", "待受理"), Received("RECEIVED", "待抢单"), Signed("SIGNED", "待签到"),
    Repaired("REPAIRED", "待完成"), Verified("VERIFIED", "待确认"), Resolved("RESOLVED", "待复线"), Finished("FINISHED", "已完结");

    /** The server gives no total for orders still being reported or already finished. */
    val hasCount: Boolean get() = this != Draft && this != Finished

    companion object { fun byCode(code: String): FaultState? = entries.firstOrNull { it.code == code } }
}

fun faultStateName(code: String): String = if (code.isEmpty()) "全部" else FaultState.byCode(code)?.title ?: code

/** What the search box searches, with the server's field for each. */
enum class FaultSearchField(val label: String, val key: String) {
    Name("报修名称", "faultName"), Number("任务号", "sn"), Machine("机台号", "asset.addressCode"), AssetCode("资产编码", "asset.assetCode"),
    AssetName("资产名称", "asset.assetName"), Address("地址", "asset.address"), Reporter("报修人", "applyUser.realName"),
    Group("维修班组", "userGroup.groupName"), Repairer("执行人", "repairUser.realName"),
}

fun serverPageCount(page: Jv): Int? = page["totalElements"].raw.let { if (it is Number && it.toDouble() >= 0) it.toInt() else null }

class OrderPage(val rows: List<Jv>, val last: Boolean, val total: Int?)

/** Both days are included; the server owns the comparison (the original client's `dateBetween`). */
data class DateSpan(val from: LocalDate, val to: LocalDate) {
    val days: Pair<LocalDate, LocalDate> get() = if (from <= to) from to to else to to from
    val key: String get() = "${days.first}~${days.second}"
    val summary: String get() = if (days.first == days.second) days.first.toString() else "${days.first} 至 ${days.second}"
}

fun dateBetween(field: String, span: DateSpan): JSONObject =
    Jv.obj("field" to field, "value" to JSONArray(listOf(span.days.first.toString(), span.days.second.toString())), "operator" to "dateBetween", "logic" to "and")

/** The search words and the date range: shared by the list and the per-state counts. */
fun faultSearchFilters(query: String, field: FaultSearchField, span: DateSpan?): List<JSONObject> = buildList {
    if (query.isNotEmpty()) add(filter(field.key, query, "like"))
    if (span != null) add(dateBetween("faultTime", span))
}

suspend fun Session.faultPage(state: String, query: String, field: FaultSearchField, span: DateSpan?, offset: Int, limit: Int = 20, independent: Boolean = false): OrderPage {
    val filters = buildList { if (state.isNotEmpty()) add(filter("faultState", state)); addAll(faultSearchFilters(query, field, span)) }
    val page = if (independent) readPage("fault/pageAssetFault", offset, filters, limit) else page("fault/pageAssetFault", offset, filters, limit)
    return OrderPage(page["content"].array, page["last"].bool, serverPageCount(page))
}

private fun Jv.flagged(): Boolean = bool || text.lowercase() in listOf("true", "1")

/** "退单" or "驳回" when the order was sent back and is still at the step it was sent back to (the original app's rule). */
fun faultReturnKind(fault: Jv): String? {
    if (!fault["returnOrder"].flagged()) return null
    return when (fault["faultState"].text) { "REPORTED" -> "退单"; "UNTREATED" -> "驳回"; else -> null }
}

/** "待受理（退单）", "报修（驳回）", or just the state's name. */
fun faultStateLabel(fault: Jv): String {
    val name = faultStateName(fault["faultState"].text)
    return faultReturnKind(fault)?.let { "$name（$it）" } ?: name
}

fun faultCardFacts(item: Jv): List<Pair<String, String>> = listOf(
    "资产编码" to item["asset"]["assetCode"].text, "资产名称" to item["asset"]["assetName"].text, "机台号" to item["asset"]["addressCode"].text,
    "地址" to item["asset"]["address"].text, "报修人" to item["applyUser"]["realName"].text, "报修时间" to item["faultTime"].dateText,
    "受理组别" to item["userGroup"]["groupName"].text, "受理部门" to item["dept"]["deptName"].text, "受理人" to item["user"]["realName"].text,
    "故障描述" to item["faultDesc"].text,
)

/** The latest repair record of a fault, from the answer of `repair/getRepairsByFaultId`. */
fun latestRepair(answer: Jv): Jv? {
    val list = answer.array.ifEmpty { answer["content"].array }
    return list.firstOrNull()?.takeIf { it.isObject }
}

/** Whether the repair record is worth a request for this state. */
fun faultMayHaveRepairNote(fault: Jv): Boolean = fault["faultState"].text in listOf("SIGNED", "REPAIRED", "VERIFIED", "RESOLVED", "FINISHED") || faultReturnKind(fault) != null

// ---- ERP outbound orders ----

/** Labels from the original app's approval-state dictionary. */
fun approvalStateName(code: String): String = mapOf(
    "PENDING" to "待审批", "EXECUTING" to "审批中", "REFUSE" to "拒绝", "AGREE" to "同意", "FINISHED" to "完成",
    "SUBMIT" to "待提交", "APPROVED" to "待审批", "CONFIRM" to "待确认",
)[code] ?: "状态待核对"

fun erpCardTitle(item: Jv): String = item["remark"].text.trim().ifEmpty { "ERP 领料申请" }

fun erpCardFacts(item: Jv): List<Pair<String, String>> = listOf(
    "申请人" to item["user"]["realName"].text, "领用人" to item["recipientUser"]["realName"].text, "领用部门" to item["dept"]["deptName"].text,
    "项目号" to item["projectCode"].text, "项目名称" to item["projectDesc"].text, "关联单号" to item["referenceSn"].text,
    "ERP记账部门" to item["division"]["divisionDesc"].text, "出库时间" to item["outboundTime"].dateText,
).filter { it.second.isNotEmpty() }

// ---- the todo list ----

/** Which kind of order a todo task belongs to: decided by the start of the process definition key. */
enum class OrderKind(val prefix: String, val title: String, val detailPath: String, val idKey: String) {
    Fault("ASSET_FAULT", "故障报修", "fault/getAssetFaultDetailById", "faultId"),
    Erp("ERP_OUTBOUND", "ERP 出库", "erpOutbound/getErpOutboundById", "outboundId"),
}

fun todoKind(task: Jv): OrderKind? = OrderKind.entries.firstOrNull { task["processDefinitionKey"].text.startsWith(it.prefix) }

fun todoTitle(task: Jv): String {
    val data = task["data"]
    return when (todoKind(task)) {
        OrderKind.Fault -> data["faultName"].text.ifEmpty { task["processInstanceName"].text }
        OrderKind.Erp -> data["remark"].text.ifEmpty { task["processInstanceName"].text }
        null -> task["processInstanceName"].text
    }
}

/** The card's status is the order's business state ("待完成", "待签到"), not the name of the workflow node. */
fun todoStatus(task: Jv): String {
    if (task["suspended"].bool) return "挂起"
    val data = task["data"]
    val named = if (todoKind(task) == OrderKind.Erp) approvalStateName(data["approvalState"].text) else faultStateLabel(data)
    return if (named.isEmpty() || named == "状态待核对" || named == "全部") task["name"].text else named
}

/** The unit of a material: the first of the places the server may put it, "PCS" when none is given. */
fun materialUnit(material: Jv): String {
    for (path in listOf("uom.uomDesc", "uom.uomCode", "uomDesc", "uomCode")) material.at(path).text.trim().takeIf { it.isNotEmpty() }?.let { return it }
    return "PCS"
}

fun materialQuantity(quantity: String, material: Jv): String = "$quantity ${materialUnit(material)}"
