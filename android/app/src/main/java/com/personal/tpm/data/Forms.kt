package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// The pure rules of the forms (new repair report, ERP outbound order, the repair result): what must be filled in, what is sent, how a date is read.
// A port of OriginalForms.swift / OriginalTasks.swift / WorkflowSubmission.swift, kept free of screens so it can be tested.

/** A node whose approver the process suggests itself keeps the suggestion: no "default approver" there. */
fun allowsApproverDefault(node: Jv): Boolean = usernames(node["assignees"]).isEmpty()

/** The date as the server sends it: "yyyy-MM-dd HH:mm[:ss]", "yyyy-MM-dd", or [y, m, d, h, min, s]. Unreadable text reads as now. */
fun parsedDate(value: Jv): Date = parsedBusinessDateOrNull(value) ?: Date()

internal fun parsedBusinessDateOrNull(value: Jv): Date? {
    val parts = value.array
    if (parts.size >= 3) {
        val v = parts.map { it.number.toInt() }
        return try {
            Calendar.getInstance(businessTimeZone()).apply {
                isLenient = false; clear()
                set(v[0], v[1] - 1, v[2], v.getOrElse(3) { 0 }, v.getOrElse(4) { 0 }, v.getOrElse(5) { 0 })
            }.time
        } catch (_: Exception) { null }
    }
    val text = value.text.trim()
    if (text.isEmpty()) return null
    // Parse zoned ISO values before the unzoned patterns, without discarding their time component.
    try { return Date.from(java.time.Instant.parse(text)) } catch (_: Exception) { }
    try { return Date.from(java.time.OffsetDateTime.parse(text).toInstant()) } catch (_: Exception) { }
    for (format in listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd")) {
        val position = java.text.ParsePosition(0)
        val parsed = SimpleDateFormat(format, Locale.US).apply { isLenient = false; timeZone = businessTimeZone() }.parse(text, position)
        if (parsed != null && position.index == text.length) return parsed
    }
    return null
}

private fun isBlank(value: Jv): Boolean = when {
    value.isNull -> true
    value.isObject -> value.keys.isEmpty()
    value.raw is JSONArray -> true
    else -> value.text.isBlank()
}

object FormRules {
    val faultRequired = listOf(
        "faultName" to "报修名称", "asset" to "报修资产", "faultTime" to "故障时间", "applyUser" to "故障申请人", "applyDept" to "申请部门",
        "dept" to "受理部门", "user" to "维修受理人", "userPhone" to "报修人联系电话", "userGroup" to "维修班组", "division" to "ERP记账部门",
    )
    val erpRequired = listOf(
        "user" to "申请人", "applyDate" to "申请日期", "projectCode" to "项目号", "recipientUser" to "领用人", "dept" to "领用部门",
        "division" to "ERP记账部门", "outboundTime" to "出库时间", "remark" to "用途",
    )

    /** The first thing missing from a form that is about to be submitted, or null when it is complete. */
    fun validate(kind: OrderKind, data: Jv, nodes: List<Jv>, selections: Map<String, List<String>>): String? {
        for ((key, label) in if (kind == OrderKind.Fault) faultRequired else erpRequired) if (isBlank(data[key])) return "请填写或选择$label"
        for (node in nodes.filter(::isChosenApprover)) {
            val count = selections[node["variableName"].text].orEmpty().size
            if (count == 0) return "请选择${node["taskName"].text}审批人"
            if (isCountersign(node) && count < 2) return "${node["taskName"].text}请至少选择两位会签审批人"
        }
        if (kind == OrderKind.Erp) {
            val lines = data["erpMaterialOutbounds"].array
            if (lines.isEmpty()) return "请选择出库物料"
            for (line in lines) {
                val quantity = line["entryQuantity"].number.let { if (it == 0.0) 1.0 else it }
                if (quantity < 0 || quantity > line["material"]["onhandQty"].number) return "${line["material"]["materialDesc"].text}：申请数量不能大于库存数量"
            }
        }
        return null
    }

    /** Body of the add/update request: the form's values, `committed`, the chosen approvers, and (ERP) a zero quantity read as 1. */
    fun payload(kind: OrderKind, data: JSONObject, nodes: List<Jv>, selections: Map<String, List<String>>, committed: Boolean): JSONObject {
        val result = JSONObject(data.toString())
        result.put("committed", committed)
        result.put("assignees", assigneesPayload(nodes, selections))
        if (kind == OrderKind.Erp) {
            val lines = result.optJSONArray("erpMaterialOutbounds") ?: JSONArray()
            val fixed = JSONArray()
            for (i in 0 until lines.length()) {
                val line = lines.getJSONObject(i)
                if (line.optDouble("entryQuantity", 0.0) == 0.0) line.put("entryQuantity", 1)
                fixed.put(line)
            }
            result.put("erpMaterialOutbounds", fixed)
        }
        return result
    }

    /** Whether a new repair report holds anything the person typed or chose (the values the form fills in by itself do not count). */
    fun faultHasContent(data: Jv): Boolean {
        if (listOf("faultName", "faultDesc").any { data[it].text.isNotBlank() }) return true
        if (listOf("asset", "dept", "user", "userGroup", "division").any { !isBlank(data[it]) }) return true
        return data["albums"].array.isNotEmpty()
    }
}

/** Fields of a new form that the person has set a default for. The order matters: a person fills their department, a department its assignee. */
data class DefaultField(val field: String, val dataKey: String, val source: RefSource)

fun defaultFields(kind: OrderKind): List<DefaultField> = if (kind == OrderKind.Fault) listOf(
    DefaultField("fault.applyUser", "applyUser", RefSource.Users), DefaultField("fault.applyDept", "applyDept", RefSource.Departments),
    DefaultField("fault.dept", "dept", RefSource.Departments), DefaultField("fault.user", "user", RefSource.Users),
    DefaultField("fault.userGroup", "userGroup", RefSource.Groups), DefaultField("fault.division", "division", RefSource.Divisions),
) else listOf(
    DefaultField("erp.user", "user", RefSource.Users), DefaultField("erp.recipientUser", "recipientUser", RefSource.Users),
    DefaultField("erp.dept", "dept", RefSource.Departments), DefaultField("erp.division", "division", RefSource.Divisions),
)

/** What picking a row really chooses: a department, a person and a fault type are read once more for their full record. */
suspend fun resolveReference(session: Session, source: RefSource, value: Jv): Jv = when (source) {
    RefSource.Departments -> session.request("dept/getDeptById", mapOf("deptId" to value["value"].text))
    RefSource.Users -> session.request("user/getUserDeptByUserId", mapOf("userId" to value["userId"].text))
    RefSource.FaultTypes -> session.request("faultType/getFaultTypeById", mapOf("typeId" to value["key"].text))
    else -> value
}

// ---- the repair result ----

/** Form items the original client registers; `repair/handleRepair` takes these plus the repair id and the ERP lines, not the whole record. */
val repairSubmissionKeys = listOf("faultType", "spend", "startTime", "endTime", "repairUsers", "content", "albums")

fun clampRepairQuantities(data: JSONObject): JSONObject = JSONObject(data.toString()).also { result ->
    val rows = Jv(data.opt("erpRepairMaterials")).array
    if (rows.isNotEmpty()) result.put("erpRepairMaterials", JSONArray(rows.map { row ->
        val left = row["materialOutbound"]["actualQuantity"].number - row["materialOutbound"]["usedQuantity"].number
        val requested = row["useNum"].number
        JSONObject(row.raw.toString()).also { if (left.isFinite() && left >= 0 && requested.isFinite() && requested > left) it.put("useNum", left) }
    }))
}

fun repairPayload(data: JSONObject, repairId: Any?): JSONObject {
    val result = JSONObject()
    for (key in repairSubmissionKeys) if (data.has(key)) result.put(key, data.get(key))
    result.put("repairId", repairId ?: JSONObject.NULL)
    result.put("erpRepairMaterials", data.optJSONArray("erpRepairMaterials") ?: JSONArray())
    return result
}

/** The first thing wrong with a repair result about to be submitted (the original client's checks), or null. */
fun repairValidationError(data: Jv, now: Date = Date()): String? {
    if (isBlank(data["faultType"])) return "请选择故障类型"
    if (data["content"].text.isBlank()) return "请输入说明"
    val spend = data["spend"].number
    if (!spend.isFinite() || spend < 0 || spend > 1e18) return "维修花费超出允许范围"
    val start = parsedDate(data["startTime"])
    val end = parsedDate(data["endTime"])
    if (start.after(end)) return "开始时间不能晚于结束时间"
    if (start.after(now) || end.after(now)) return "维修时间不能晚于当前时间"
    for (line in data["erpRepairMaterials"].array) {
        val left = line["materialOutbound"]["actualQuantity"].number - line["materialOutbound"]["usedQuantity"].number
        if (!line["useNum"].number.isFinite() || !left.isFinite() || line["useNum"].number < 1 || line["useNum"].number > left) return "使用数量超出可用范围"
    }
    return null
}

/** Identity of one outbound material line: the outbound order plus the material. */
fun outboundLineIdentity(line: Jv): String = "${line["id"]["outboundId"].text}-${line["id"]["materialId"].text}"

/** One row of the repair's ERP material, built from an outbound line picked in the list. */
fun repairMaterialRow(line: Jv): JSONObject = Jv.obj(
    "material" to line["material"].raw, "outbound" to line["outbound"].raw, "materialOutbound" to line.raw, "useNum" to 1,
)

fun quantityText(value: Double): String = if (value == Math.rint(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** The failure text with the stage it happened in ("保存维修结果失败：…"). */
fun stageFailure(stage: String, error: Throwable): String = stage + "失败：" + (error.message ?: "未知错误")

/** The approver the person set as the default for a step comes first, where the step lets the person choose; a step the process names by itself keeps its suggestion. */
fun withApproverDefaults(nodes: List<Jv>, selections: Map<String, List<String>>, prefs: PickerPrefs, scope: String, prefix: String): Map<String, List<String>> {
    val result = selections.toMutableMap()
    for (node in nodes.filter(::isChosenApprover)) {
        if (!allowsApproverDefault(node)) continue
        val key = node["variableName"].text
        prefs.defaultChoice(scope, "$prefix.$key")?.value?.get("userName")?.text?.takeIf { it.isNotEmpty() }?.let { result[key] = listOf(it) }
    }
    return result
}
