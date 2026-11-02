package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import org.json.JSONObject

fun quickRepairItem(todo: Jv): BatchItem? = batchItem(todo)?.takeIf {
    todo["id"].text.isNotBlank() &&
    it.kind == OrderKind.Fault && it.group in setOf("SIGNED", "REPAIRED") && it.id.isNotBlank()
}

fun quickApproverKey(node: Jv): String = listOf(node["variableName"].text, node["assignType"].text,
    node["taskName"].text, isCountersign(node).toString()).joinToString("|")

fun inheritedQuickApprovers(nodes: List<Jv>, defaults: Map<String, List<String>>, previous: Map<String, List<String>>): Map<String, List<String>> =
    defaults.toMutableMap().also { selected ->
        nodes.filter(::isChosenApprover).forEach { node ->
            val remembered = previous[quickApproverKey(node)].orEmpty()
            val allowed = usernames(node["assignees"])
            if (remembered.isNotEmpty() && (allowed.isEmpty() || remembered.all { it in allowed }) &&
                (!isCountersign(node) || remembered.size >= 2)) selected[node["variableName"].text] = remembered.toList()
        }
    }

data class QuickRepairPrepared(val item: BatchItem, val detail: Jv, val nodes: List<Jv>, val selections: Map<String, List<String>>) {
    val task: Jv get() = detail["taskInfo"]
}

class QuickRepairWriteUncertain(val stage: String, cause: Exception) : Exception(
    "$stage 的结果未确认，请先打开工单核对；填写内容已保留，未自动重试。${cause.message.orEmpty()}", cause)

/** Same endpoints and validation as the single repair screen, with a testable request seam. */
internal class QuickRepairEngine(private val api: BatchApi) {
    suspend fun pending(): List<BatchItem> {
        val result = linkedMapOf<String, BatchItem>()
        val seenTasks = hashSetOf<String>()
        var offset = 0
        while (true) {
            val page = api.page("process/pageUserTodoTask", offset, limit = 100)
            val rows = page["content"].array
            var fresh = 0
            rows.forEach { row ->
                val identity = row["id"].text.ifEmpty { row.toString() }
                if (seenTasks.add(identity)) fresh++
                quickRepairItem(row)?.let { result.putIfAbsent(it.id, it) }
            }
            if (page["last"].bool || (page["last"].isNull && rows.size < 100)) break
            if (rows.isEmpty() || fresh == 0) error("待办分页没有继续推进，尚未读取完整，请刷新后重试")
            offset += rows.size
        }
        return result.values.toList()
    }

    suspend fun prepare(item: BatchItem): QuickRepairPrepared {
        require(item.kind == OrderKind.Fault && item.group in setOf("SIGNED", "REPAIRED")) { "快速完成仅用于维修待签到和待完成" }
        val detail = api.request(OrderKind.Fault.detailPath, mapOf("faultId" to item.id))
        val task = detail["taskInfo"]
        check(detail["faultState"].text == item.group && task["category"].text == item.group && task["id"].text.isNotBlank()) { "工单状态已变化，请返回待办刷新" }
        check(task["businessKey"].text.ifEmpty { item.id } == item.id) { "流程任务与当前工单不匹配" }
        val permissions = api.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text))
        check(permissions["canSubmit"].bool) { "本账号没有处理权限，可使用右上角的本次操作账号选择其他已保存账号" }
        val form = api.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to task["id"].text))
        check(form["flowConditions"].array.any { it["value"].text == "agree" }) { "此任务没有标准完成选项，请打开工单单独处理" }
        val nodes = api.request("workflow/getAssigneeElementsByProcessCategory", mapOf("processCategory" to OrderKind.Fault.prefix,
            "processInstanceId" to task["processInstanceId"].text, "taskKey" to task["taskDefinitionKey"].text)).array
        val selections = api.approverDefaults(nodes, nodes.associate { it["variableName"].text to usernames(it["assignees"]) }, OrderKind.Fault)
        return QuickRepairPrepared(item, detail, nodes, selections)
    }

    suspend fun sign(item: BatchItem): BatchItem {
        require(item.kind == OrderKind.Fault && item.group == "SIGNED")
        BatchProcessor.process(item, BatchAction.SignIn, BatchOperation.Proceed, BatchPreset(), api) { BatchChoice.Stop }
        val detail = api.request(OrderKind.Fault.detailPath, mapOf("faultId" to item.id))
        check(detail["faultState"].text == "REPAIRED" && detail["taskInfo"]["category"].text == "REPAIRED") { "签到已提交，下一任务尚未就绪，请稍后刷新；不要重复签到" }
        return item.copy(group = "REPAIRED")
    }

    suspend fun submit(item: BatchItem, expectedTask: String, frozen: String, choices: Map<String, List<String>>, onWrite: () -> Unit = {}) {
        require(item.group == "REPAIRED")
        val fresh = prepare(item)
        check(fresh.task["id"].text == expectedTask) { "流程任务已变化，本次未写入" }
        missingAssigneeMessage(fresh.nodes, choices)?.let { error(it) }
        fresh.nodes.filter(::isChosenApprover).forEach { node ->
            val allowed = usernames(node["assignees"])
            check(allowed.isEmpty() || choices[node["variableName"].text].orEmpty().all { it in allowed }) { "下一审批节点已变化，请重新选择审批人" }
        }
        val data = JSONObject(frozen)
        repairValidationError(Jv(data))?.let { error(it) }
        val latest = api.request("fault/getLatestRepairByFaultId", mapOf("faultId" to item.id))
        check(latest["repairId"].text.isNotBlank() && latest["repairId"].text == Jv(data)["repairId"].text) { "维修记录已变化，请重新打开工单" }
        val commit = commitTaskPayload(fresh.task, "agree", "", assigneesPayload(fresh.nodes, choices))
        onWrite()
        try { api.request("repair/handleRepair", body = repairPayload(data, latest["repairId"].raw)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw QuickRepairWriteUncertain("保存维修结果", e) }
        try { api.request("process/commitTask", body = commit) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw QuickRepairWriteUncertain("维修结果已保存，提交流程", e) }
    }
}
