package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Requests for taking a task forward, built the way the original client and the iOS app build them (a port of WorkflowSubmission.swift).

/** User names written as a list of objects, a list of names, or "[a, b]" text. */
fun usernames(value: Jv): List<String> {
    if (value.array.isNotEmpty()) return value.array.map { if (it.isObject) it["userName"].text else it.text }.filter { it.isNotEmpty() }
    return value.text.replace("[", "").replace("]", "").replace("\"", "").split(Regex("[,，;； ]")).filter { it.isNotEmpty() }
}

fun isCountersign(node: Jv): Boolean =
    listOf("countersign", "countersignTask", "countersignEnabled", "multiInstance").any { node[it].bool } ||
        node["approvalMode"].text.uppercase() in listOf("COUNTERSIGN", "ALL", "ANY") ||
        node["documentation"].text.contains("会签节点") || node["description"].text.contains("会签节点")

/** A node whose approver the person has to choose: not one the process names by itself. */
fun isChosenApprover(node: Jv): Boolean = node["assignType"].text in listOf("ASSIGN", "SUPERVISOR")

fun isReturnCondition(condition: Jv): Boolean = condition["name"].text.let { it.contains("退单") || it.contains("返单") }
fun isRejectCondition(condition: Jv): Boolean =
    isReturnCondition(condition) || condition["name"].text.contains("驳回") || condition["name"].text.contains("拒绝") || condition["value"].text == "reject"

/** `assignees` of a submission: ASSIGN/SUPERVISOR nodes map their variable to the chosen user name (countersign: comma separated), CANDIDATE_USERS pass through. */
fun assigneesPayload(nodes: List<Jv>, selections: Map<String, List<String>>): JSONObject {
    val result = JSONObject()
    for (node in nodes) {
        val key = node["variableName"].text
        val names = selections[key].orEmpty()
        when (node["assignType"].text) {
            "ASSIGN", "SUPERVISOR" -> result.put(key, if (isCountersign(node)) names.joinToString(",") else names.firstOrNull().orEmpty())
            "CANDIDATE_USERS" -> result.put(key, node["assignees"].raw ?: JSONArray(names))
        }
    }
    return result
}

/** The message for the first next-node approver that is still missing, or null when every required node has someone. */
fun missingAssigneeMessage(nodes: List<Jv>, selections: Map<String, List<String>>): String? {
    for (node in nodes.filter(::isChosenApprover)) {
        val count = selections[node["variableName"].text].orEmpty().size
        val name = node["taskName"].text.ifEmpty { "下一节点" }
        if (count == 0) return "请选择${name}的审批人"
        if (isCountersign(node) && count < 2) return "${name}为会签节点，请至少选择两位审批人"
    }
    return null
}

/** Body of `POST process/commitTask`. `assignees` is only sent for the "agree" condition. */
fun commitTaskPayload(task: Jv, condition: String, comment: String, assignees: JSONObject?, taskData: Any? = null): JSONObject {
    val payload = Jv.obj(
        "taskId" to task["id"].raw, "processInstanceId" to task["processInstanceId"].raw,
        "condition" to condition, "comment" to comment, "taskData" to taskData,
    )
    if (assignees != null) payload.put("assignees", assignees)
    return payload
}

/** What the verb on a task's button says (the node's own name is a state: "待抢单"). Unknown categories fall back to the node name. */
fun taskActionTitle(task: Jv, kind: OrderKind): String {
    val verbs = if (kind == OrderKind.Fault) mapOf(
        "UNTREATED" to "提交报修", "SUBMIT" to "提交报修", "REPORTED" to "受理派单", "RECEIVED" to "抢单", "SIGNED" to "签到",
        "REPAIRED" to "完成任务", "VERIFIED" to "确认完成", "RESOLVED" to "复线确认",
    ) else mapOf("SUBMIT" to "提交申请", "APPROVED" to "出库审批", "EXECUTING" to "出库", "CONFIRM" to "出库确认")
    return verbs[task["category"].text] ?: task["name"].text.ifEmpty { "处理" }
}

/** The server's date format for a time: "yyyy-MM-dd HH:mm". */
fun serverDate(date: Date, dateOnly: Boolean = false): String =
    SimpleDateFormat(if (dateOnly) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm", Locale.US).format(date)
