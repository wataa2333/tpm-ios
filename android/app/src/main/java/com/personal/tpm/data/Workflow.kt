package com.personal.tpm.data

import java.time.Duration
import java.time.LocalDateTime

// The process history of an order, read and ordered the way the iOS app does (a port of WorkflowHistory.swift).

/** A running user task can be reported twice; keep one, preferring the copy that carries comments. */
fun uniqueRunningUserTasks(nodes: List<Jv>): List<Jv> {
    val result = mutableListOf<Jv>()
    for (node in nodes) {
        if (node["activityType"].text != "userTask" || !node["endTime"].isNull) { result += node; continue }
        val index = result.indexOfFirst { other ->
            other["activityType"].text == "userTask" && other["endTime"].isNull &&
                (other["taskId"].text == node["taskId"].text || other["activityId"].text == node["activityId"].text || other["activityName"].text == node["activityName"].text)
        }
        if (index < 0) { result += node; continue }
        if (result[index]["comments"].array.isEmpty() && node["comments"].array.isNotEmpty()) result[index] = node
    }
    return result
}

private fun sortWeight(node: Jv): Int {
    val type = node["activityType"].text
    val id = node["activityId"].text
    return when {
        type == "startEvent" || id == "START" -> 0
        id == "ACT_INITIATOR" -> 1
        type == "userTask" && !node["endTime"].isNull -> 2
        type in listOf("transfer", "delegate", "delegateResolve") -> 3
        type == "userTask" -> 4
        type == "endEvent" || id == "END" -> 9
        else -> 5
    }
}

fun orderedHistory(raw: List<Jv>): List<Jv> = uniqueRunningUserTasks(raw).sortedWith { a, b ->
    val ta = if (a["sortTime"].isNull) Double.MAX_VALUE else a["sortTime"].number
    val tb = if (b["sortTime"].isNull) Double.MAX_VALUE else b["sortTime"].number
    if (ta != tb) ta.compareTo(tb) else sortWeight(a).compareTo(sortWeight(b))
}

fun historyTimePrefix(node: Jv): String {
    if (node["displayTimePrefix"].text.isNotEmpty()) return node["displayTimePrefix"].text
    return when (node["activityType"].text) {
        "transfer" -> "转办"
        "delegate" -> "委派"
        "delegateResolve" -> "委派完成"
        "userTask" -> if (node["endTime"].isNull) "当前" else "完成"
        else -> "完成"
    }
}

fun historyTimeText(node: Jv): String {
    for (key in listOf("displayTimeText", "endTimeText", "startTimeText")) if (node[key].text.isNotEmpty()) return node[key].text
    for (key in listOf("endTime", "startTime")) if (!node[key].isNull) return node[key].dateText
    return ""
}

/** "9天前" style text for a "yyyy-MM-dd HH:mm" time. */
fun relativeTimeText(time: String, now: LocalDateTime = LocalDateTime.now()): String {
    if (time.isEmpty()) return ""
    val moment = try { LocalDateTime.parse(time.take(16).replace(' ', 'T')) } catch (e: Exception) { return "" }
    val minutes = Duration.between(moment, now).toMinutes()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes}分钟前"
        minutes < 60 * 24 -> "${minutes / 60}小时前"
        minutes < 60 * 24 * 30 -> "${minutes / (60 * 24)}天前"
        minutes < 60 * 24 * 365 -> "${minutes / (60 * 24 * 30)}个月前"
        else -> "${minutes / (60 * 24 * 365)}年前"
    }
}

fun historyDuration(millis: Double): String {
    val days = (millis / 86_400_000).toInt()
    val hours = ((millis % 86_400_000) / 3_600_000).toInt()
    val minutes = ((millis % 3_600_000) / 60_000).toInt()
    val seconds = (millis % 60_000) / 1000
    val secondText = if (seconds > 0) (Math.round(seconds * 1000) / 1000.0).let { if (it == Math.rint(it)) it.toLong().toString() else it.toString() } + "秒" else "00秒"
    return (if (days > 0) "${days}天" else "") + (if (hours > 0) "${hours}时" else "") + (if (minutes > 0) "${minutes}分" else "") + secondText
}

/** Names written as a list, an array or "[a, b]" text. */
fun splitUserNames(value: Jv): List<String> {
    if (value.array.isNotEmpty()) return value.array.map { it.text.trim() }.filter { it.isNotEmpty() }
    return value.text.replace("[", "").replace("]", "").split(Regex("[,，、;；\\s]+")).filter { it.isNotEmpty() }
}

/** Process definition variables describe roles, not literal user names. */
fun workflowParticipantLabel(raw: String): String {
    val roles = mapOf("\${initiator}" to "发起人", "initiator" to "发起人", "REPAIR_USER" to "维修人员")
    return raw.split(Regex("[,，;；]")).mapNotNull { part ->
        val value = part.trim()
        roles[value]
            ?: value.takeIf {
                it.isNotEmpty() && !it.contains("\${") && !it.contains("#{") &&
                    // Technical enums look like IN_USE or REPAIR_USER (underscore); employee numbers such as C0070034 stay.
                    !Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$").matches(it) && !Regex("^[0-9]+$").matches(it)
            }
    }.joinToString("、")
}

fun countersignNames(node: Jv, kind: String): List<String> {
    fun first(keys: List<String>): List<String> {
        for (key in keys) if (!node[key].isNull) { val names = splitUserNames(node[key]); if (names.isNotEmpty()) return names }
        return emptyList()
    }
    return when (kind) {
        "completed" -> first(listOf("countersignCompletedAssignees", "completedAssignees"))
        "pending" -> first(listOf("countersignPendingAssignees", "pendingAssignees"))
        else -> first(listOf("countersignAssignees", "countersignAllAssignees", "countersignRuntimeAssignees"))
    }
}

fun isCountersignHistory(node: Jv): Boolean =
    node["countersignTask"].bool || node["countersign"].bool || node["multiInstance"].bool || node["countersignStatusText"].text.isNotEmpty() ||
        node["countersignCollectionVariable"].text.isNotEmpty() || node["countersignTotal"].number > 0 || countersignNames(node, "all").isNotEmpty()

/** Who handled (or has to handle) a history entry, as user names. Process expressions are turned into their role word or dropped. */
fun historyAssigneeNames(node: Jv): List<String> {
    var names = emptyList<String>()
    for (key in listOf("assignee", "assignees", "candidateUsers", "candidateUserIds")) {
        if (!node[key].isNull && splitUserNames(node[key]).isNotEmpty()) { names = splitUserNames(node[key]); break }
    }
    if (names.isEmpty() && isCountersignHistory(node)) {
        val pending = splitUserNames(if (node["countersignPendingAssignees"].isNull) node["pendingAssignees"] else node["countersignPendingAssignees"])
        names = pending.ifEmpty { countersignNames(node, "all") }
    }
    return names.mapNotNull { name ->
        if (name.contains("\${") || name.contains("#{")) workflowParticipantLabel(name).ifEmpty { null } else name
    }
}

/** Every user name that appears in the history, so real names can be looked up once each. */
fun historyUserNames(nodes: List<Jv>): List<String> =
    nodes.flatMap { historyAssigneeNames(it) + countersignNames(it, "all") + countersignNames(it, "completed") + countersignNames(it, "pending") }.distinct()

fun showsConditionTag(node: Jv): Boolean {
    val condition = node["condition"].text
    if (condition.isEmpty()) return false
    val type = node["activityType"].text
    if (type in listOf("transfer", "delegate", "delegateResolve")) return false
    if (type == "userTask" && node["endTime"].isNull && condition == "提交") return false
    return listOf("转办记录", "委派记录", "委派完成").none { condition.startsWith(it) }
}

fun activityTypeText(type: String): String = when (type) {
    "transfer" -> "流程转办"; "delegate" -> "流程委派"; "delegateResolve" -> "委派完成"; "" -> "流程节点"; else -> type
}

fun commentTypeText(comment: Jv): String = when (comment["type"].text) {
    "transfer" -> "转办记录"; "delegate" -> "委派记录"; "delegateResolve" -> "委派完成"; else -> "意见说明"
}
