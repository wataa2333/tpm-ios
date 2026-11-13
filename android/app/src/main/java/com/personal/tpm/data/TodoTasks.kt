package com.personal.tpm.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray

private const val TODO_PAGE = "process/pageUserTodoTask"
private const val TODO_SCAN_LIMIT = 100
private val todoSearchWhitespace = Regex("\\s+")
private val todoSearchFields = listOf(
    "processInstanceName", "name", "taskName", "processDefinitionName", "businessKey",
    "data.faultName", "data.maintainName", "data.preciseName", "data.remark",
    "data.sn", "data.outNumber", "data.referenceSn",
    "data.asset.addressCode", "data.asset.assetCode", "data.asset.assetName",
    "data.addressCode", "data.assetCode",
)

/** Walk the signed-in account's todo pages, guarding against repeated pages from a shifting result. */
internal suspend fun scanTodoTasks(
    fetch: suspend (path: String, offset: Int, limit: Int) -> Jv,
    onTask: (task: Jv, firstOccurrence: Boolean) -> Unit,
) {
    val seen = hashSetOf<String>()
    var offset = 0
    while (true) {
        currentCoroutineContext().ensureActive()
        val page = fetch(TODO_PAGE, offset, TODO_SCAN_LIMIT)
        val rows = page["content"].array
        var fresh = 0
        rows.forEach { task ->
            val identity = task["id"].text.ifEmpty { task.toString() }
            val firstOccurrence = seen.add(identity)
            if (firstOccurrence) fresh++
            onTask(task, firstOccurrence)
        }
        val nextOffset = offset + rows.size
        val total = serverPageCount(page)
        if (page["last"].bool || (page["last"].isNull &&
                (if (total != null) nextOffset >= total else rows.size < TODO_SCAN_LIMIT))) return
        if (rows.isEmpty() || fresh == 0) error("待办分页没有继续推进，尚未读取完整，请刷新后重试")
        offset = nextOffset
    }
}

internal fun searchTodoTasks(tasks: List<Jv>, query: String): List<Jv> {
    val terms = query.trim().split(todoSearchWhitespace).filter(String::isNotEmpty)
    if (terms.isEmpty()) return tasks
    return tasks.filter { task ->
        val values = todoSearchFields.map { task.at(it).text } + todoStatus(task)
        terms.all { term -> values.any { it.contains(term, ignoreCase = true) } }
    }
}

internal enum class TaskSearchField(val label: String, val paths: List<String> = emptyList()) {
    Type("任务类型", listOf("processDefinitionName", "processDefinitionKey")),
    Title("工单名称", listOf("processInstanceName", "data.faultName", "data.maintainName", "data.preciseName", "data.inspectName", "data.remark", "data.name")),
    Number("工单号", listOf("data.sn", "data.outNumber", "data.referenceSn", "sn", "outNumber")),
    Machine("机台号", listOf("data.asset.addressCode", "data.addressCode", "addressCode")),
    AssetCode("资产编码", listOf("data.asset.assetCode", "data.assetCode", "assetCode")),
    Node("流程节点", listOf("name", "taskName")),
    Assignee("流程办理人", listOf("assignee", "assignee.realName", "assignee.userName")),
    Executor("执行人 / 领用人"),
}

private val taskTypeNames = mapOf(
    "ASSET_FAULT" to listOf("故障报修", "维修"),
    "MAINTAIN" to listOf("保养任务"),
    "PRECISE" to listOf("精密度检查"),
    "ERP_OUTBOUND" to listOf("ERP 出库", "ERP物料出库"),
    "INSPECT" to listOf("巡检任务"),
    "INVENTORY" to listOf("盘点任务"),
    "REVAMP" to listOf("设备改造"),
    "RECTIFICATION" to listOf("整改任务"),
    "WAREHOUSE_INVENTORY" to listOf("仓库盘点"),
    "TASK" to listOf("通用任务"),
)

private fun taskExecutor(task: Jv): Jv {
    val data = task["data"]
    val definition = task["processDefinitionKey"].text
    return when {
        definition.startsWith(OrderKind.Fault.prefix) -> data["repairUser"]
        definition.startsWith("MAINTAIN") -> data["maintainUser"]
        definition.startsWith("PRECISE") -> data["user"]
        definition.startsWith(OrderKind.Erp.prefix) -> data["recipientUser"]
        else -> Jv.NULL
    }
}

private fun TaskSearchField.values(task: Jv): List<String> = when (this) {
    TaskSearchField.Type -> {
        val type = task["processDefinitionKey"].text.substringBefore(':')
        val category = taskTypeNames.keys.firstOrNull { type.startsWith(it) }
        paths.map { task.at(it).text } + category?.let { taskTypeNames[it] }.orEmpty() + listOfNotNull(category)
    }
    TaskSearchField.Executor -> taskExecutor(task).let { listOf(it["realName"].text, it["userName"].text, it.text) }
    else -> paths.map { task.at(it).text }
}

private fun TaskSearchField.matches(task: Jv, condition: SearchCondition): Boolean =
    values(task).any { it.isNotBlank() && condition.matches(it) }

/** Filter a complete todo snapshot or an entire history page, before rendering either as cards. */
internal suspend fun filterTaskConditions(
    tasks: List<Jv>, conditions: List<SearchCondition>, loadRepair: suspend (String) -> Jv,
): List<Jv> {
    val filters = conditions.mapNotNull { condition ->
        TaskSearchField.entries.firstOrNull { it.name == condition.field }?.let { it to condition }
    }
    if (filters.isEmpty()) return tasks
    val candidates = tasks.filter { task ->
        filters.all { (field, condition) ->
            field.matches(task, condition) || (field == TaskSearchField.Executor &&
                task["processDefinitionKey"].text.startsWith(OrderKind.Fault.prefix) &&
                field.values(task).all(String::isBlank) &&
                task["data"]["faultState"].text !in listOf("UNTREATED", "REPORTED"))
        }
    }
    val executor = filters.firstOrNull { it.first == TaskSearchField.Executor }?.second ?: return candidates
    return candidates.chunked(4).flatMap { chunk ->
        coroutineScope {
            chunk.map { task -> async {
                if (TaskSearchField.Executor.matches(task, executor)) return@async task
                val id = task["data"]["faultId"].text.ifEmpty { task["businessKey"].text }
                if (id.isBlank()) return@async null
                val response = loadRepair(id)
                check(response.raw is JSONArray || response["content"].raw is JSONArray) { "维修执行人查询结果格式异常" }
                val repair = latestRepair(response) ?: return@async null
                val repairFaultId = repair["assetFault"]["faultId"].text
                if (repairFaultId.isNotBlank() && repairFaultId != id) return@async null
                val user = repair["user"]
                task.takeIf { listOf(user["realName"].text, user["userName"].text).any { executor.matches(it) } }
            } }.awaitAll().filterNotNull()
        }
    }
}

internal suspend fun Session.filterTaskConditions(tasks: List<Jv>, conditions: List<SearchCondition>): List<Jv> =
    filterTaskConditions(tasks, conditions) { id -> request("repair/getRepairsByFaultId", mapOf("faultId" to id)) }
