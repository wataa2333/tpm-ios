package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

fun poolPrefixForTask(task: Jv): String = when {
    task["processDefinitionKey"].text.startsWith("MAINTAIN") -> "maintain"
    task["processDefinitionKey"].text.startsWith("PRECISE") -> "precise"
    else -> ""
}

fun quickPoolItem(task: Jv): BatchItem? = batchItem(task)?.takeIf {
    it.poolPrefix.isNotEmpty() && it.group.substringAfter(":") in setOf("SIGNED", "MAINTAINED", "PRECISED")
}

/** Copy answers, never another execution's record ids, standard objects or stock reservations. */
fun poolTemplateForOrder(prefix: String, latest: Jv, records: List<Jv>, template: Jv): JSONObject {
    val source = template[prefix + "Records"].array
    val result = poolExecutionSnapshot(latest)
    for (key in listOf("startTime", "endTime", "content", "albums")) if (!template[key].isNull) result.put(key, template[key].raw)
    val mapped = records.map { row ->
        val standard = row[prefix + "Item"]
        val matches = source.filter { other ->
            val item = other[prefix + "Item"]
            item["itemName"].text == standard["itemName"].text && item["itemType"].text == standard["itemType"].text
        }
        check(matches.size == 1) { "检查标准不一致或项目重名，请单独填写：${standard["itemName"].text}" }
        val answer = matches.single()
        JSONObject(row.raw.toString()).also { out ->
            for (key in listOf("executeResult", prefix + "Result", "description")) if (!answer[key].isNull) out.put(key, answer[key].raw)
            out.optJSONObject(prefix + "Item")?.put(prefix + "Result", answer[prefix + "Result"].raw)
        }
    }
    result.put(prefix + "Records", JSONArray(mapped))
    return result
}

internal class PoolProcessingEngine(private val api: BatchApi) {
    private fun name(prefix: String) = if (prefix == "maintain") "Maintain" else "Precise"
    private suspend fun detail(item: BatchItem): Jv {
        val p = item.poolPrefix
        var row = api.request("$p/get${name(p)}DetailById", mapOf((p + "Id") to item.id))
        if (row["taskInfo"]["id"].text.isEmpty() && row["processInstanceId"].text.isNotEmpty()) {
            val task = api.request("workflow/getUserTaskByProcessInstanceId", mapOf("processInstanceId" to row["processInstanceId"].text))
            row = Jv(JSONObject(row.raw.toString()).put("taskInfo", task.raw))
        }
        return row
    }
    suspend fun prepare(item: BatchItem, requireAgree: Boolean = true): QuickRepairPrepared {
        check(item.poolPrefix in setOf("maintain", "precise"))
        val row = detail(item)
        val task = row["taskInfo"]
        val expected = item.group.substringAfter(":")
        check(row[item.poolPrefix + "State"].text == expected && task["category"].text == expected && task["id"].text.isNotBlank()) { "工单节点已变化，请刷新" }
        check(task["businessKey"].text.ifEmpty { item.id } == item.id) { "流程与单据不匹配" }
        val state = api.request("workflow/getWorkflowTaskState", mapOf("processInstanceId" to task["processInstanceId"].text))
        check(state["canSubmit"].bool) { "当前账号不能处理此工单" }
        val form = api.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to task["id"].text))
        if (requireAgree) check(form["flowConditions"].array.any { it["value"].text == "agree" }) { "没有标准同意分支，请单独处理" }
        val nodes = if (requireAgree) api.request("workflow/getAssigneeElementsByProcessCategory", mapOf("processCategory" to item.poolPrefix.uppercase(),
            "processInstanceId" to task["processInstanceId"].text, "taskKey" to task["taskDefinitionKey"].text)).array else emptyList()
        return QuickRepairPrepared(item, row, nodes, nodes.associate { it["variableName"].text to usernames(it["assignees"]) })
    }
    private fun choices(prepared: QuickRepairPrepared, selected: Map<String, List<String>>): Map<String, List<String>> {
        val result = prepared.selections.toMutableMap()
        prepared.nodes.filter(::isChosenApprover).forEach { node ->
            val key = node["variableName"].text
            selected[key]?.let { values ->
                val allowed = usernames(node["assignees"])
                check(allowed.isEmpty() || values.all { it in allowed }) { "审批人范围已变化，请单独处理" }
                result[key] = values
            }
        }
        missingAssigneeMessage(prepared.nodes, result)?.let { error(it) }
        return result
    }
    suspend fun sign(item: BatchItem): BatchItem {
        process(item, BatchAction.SignIn, BatchOperation.Proceed, BatchPreset())
        val row = detail(item)
        val group = row[item.poolPrefix + "State"].text
        check(group == if (item.poolPrefix == "maintain") "MAINTAINED" else "PRECISED") { "签到后节点尚未就绪，请刷新核对，不要重复签到" }
        return item.copy(group = item.poolPrefix.uppercase() + ":" + group)
    }
    suspend fun submit(item: BatchItem, expectedTask: String, data: String, selected: Map<String, List<String>>, onWrite: () -> Unit) {
        val current = prepare(item)
        check(current.task["id"].text == expectedTask) { "任务已变化，本次未写入" }
        val approvals = choices(current, selected)
        val latest = api.request("${item.poolPrefix}/getLatest${name(item.poolPrefix)}OrderBy${name(item.poolPrefix)}Id", mapOf(item.poolPrefix + "Id" to item.id))
        val payload = Jv(JSONObject(data))
        check(latest["orderId"].text == payload["orderId"].text) { "执行记录已变化，请重新填写" }
        val existing = if (item.poolPrefix == "maintain") api.request("erpOutbound/getErpMaintainMaterialsByMaintainId", mapOf("maintainId" to latest["orderId"].text)).array else emptyList()
        poolExecutionError(payload, item.poolPrefix, existing)?.let { error(it) }
        val commit = commitTaskPayload(current.task, "agree", "", assigneesPayload(current.nodes, approvals))
        onWrite()
        try { api.request("${item.poolPrefix}/handle${name(item.poolPrefix)}Order", body = JSONObject(data)) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw QuickRepairWriteUncertain("保存执行结果", e) }
        try { api.request("process/commitTask", body = commit) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { throw QuickRepairWriteUncertain("执行结果已保存，提交流程", e) }
    }
    suspend fun process(item: BatchItem, action: BatchAction, operation: BatchOperation, preset: BatchPreset) {
        check(BatchAction.forGroup(item.group) == action) { "批量操作与状态不匹配" }
        val prepared = prepare(item, operation == BatchOperation.Proceed)
        if (action == BatchAction.Complete && operation == BatchOperation.Proceed) {
            val template = Jv(JSONObject(preset.poolTemplate ?: error("请填写批量执行结果")))
            val p = item.poolPrefix; val n = name(p)
            val latest = api.request("$p/getLatest${n}OrderBy${n}Id", mapOf(p + "Id" to item.id))
            val records = api.request("$p/get${n}RecordsBy${n}Id", mapOf(p + "Id" to latest["orderId"].text)).array
            val data = poolTemplateForOrder(p, latest, records, template)
            if (p == "maintain") {
                val existing = api.request("erpOutbound/getErpMaintainMaterialsByMaintainId", mapOf("maintainId" to latest["orderId"].text)).array
                val combined = existing.map { it.raw }.toMutableList()
                for (wanted in template["erpMaintainMaterials"].array) {
                    val old = wanted["materialOutbound"]
                    check(existing.none { outboundLineIdentity(it["materialOutbound"]) == outboundLineIdentity(old) }) { "本单已关联此出库物料，请单独调整数量" }
                    val response = api.page("erpOutbound/pageErpMaterialOutbound", filters = listOf(
                        filter("outbound.outNumber", old["outbound"]["outNumber"].text, "like"),
                        filter("material.materialCode", old["material"]["materialCode"].text, "like")), limit = 50)
                    val fresh = response["content"].array.firstOrNull { outboundLineIdentity(it) == outboundLineIdentity(old) } ?: error("出库物料已变化，请重新选择")
                    val quantity = wanted["useNum"].number
                    check(quantity.isFinite() && quantity > 0 && quantity <= fresh["actualQuantity"].number - fresh["usedQuantity"].number) { "出库物料剩余数量不足" }
                    combined.add(Jv.obj("material" to fresh["material"].raw, "outbound" to fresh["outbound"].raw, "materialOutbound" to fresh.raw, "useNum" to quantity))
                }
                data.put("erpMaintainMaterials", JSONArray(combined))
            }
            submit(item, prepared.task["id"].text, data.toString(), preset.approvers) { }
            return
        }
        val form = api.request("workflow/getFormPropertiesConditionsByTaskId", mapOf("taskId" to prepared.task["id"].text))
        val condition = if (operation == BatchOperation.Proceed) "agree" else form["flowConditions"].array.firstOrNull { isReturnCondition(it) || isRejectCondition(it) }?.get("value")?.text ?: error("没有退回或驳回分支")
        check(operation != BatchOperation.Reject || preset.comment.isNotBlank()) { "请填写退回原因" }
        val payload = commitTaskPayload(prepared.task, condition, preset.comment,
            if (operation == BatchOperation.Proceed) assigneesPayload(prepared.nodes, choices(prepared, preset.approvers)) else null)
        if (action == BatchAction.Accept && operation == BatchOperation.Proceed) {
            val group = preset.group.takeIf { it["groupId"].text.isNotEmpty() } ?: prepared.detail["userGroup"]
            check(group["groupId"].text.isNotEmpty()) { "请选择保养班组" }
            payload.put("taskData", JSONObject().put(item.poolPrefix + "Id", item.id).put("groupId", group["groupId"].raw).put("userGroup", group.raw).toString())
        }
        api.request("process/commitTask", body = payload)
    }
}
