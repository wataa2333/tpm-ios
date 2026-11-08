package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

fun poolDraftId(scope: String, mainId: String, executionId: String): String =
    UUID.nameUUIDFromBytes("$scope|$mainId|$executionId".toByteArray(Charsets.UTF_8)).toString()

/** Persist editable fields and record identities, never the surrounding workflow/detail graph. */
fun poolDraftSnapshot(data: Jv, prefix: String): JSONObject = JSONObject().also { draft ->
    for (key in listOf("orderId", "startTime", "endTime", "content", "albums", prefix + "Records") +
        if (prefix == "maintain") listOf("erpMaintainMaterials", "pendingErpOutboundId") else emptyList()) {
        if (!data[key].isNull) draft.put(key, data[key].raw)
    }
}.let { JSONObject(it.toString()) }

/** Restore answers onto freshly read standards, only for the same execution and matching record identities. */
fun restorePoolDraft(fresh: Jv, prefix: String, draft: Jv): JSONObject {
    check(fresh["orderId"].text.isNotBlank() && fresh["orderId"].text == draft["orderId"].text) { "草稿属于另一条执行记录，请核对后继续" }
    val recordsKey = prefix + "Records"
    val saved = draft[recordsKey].array
    val records = fresh[recordsKey].array
    check(saved.size == records.size) { "检查项目已变化，旧草稿已保留，请重新填写" }
    val currentIdCounts = records.groupingBy { it["recordId"].text }.eachCount()
    val savedById = saved.groupBy { it["recordId"].text }
    val restored = records.mapIndexed { index, current ->
        val recordId = current["recordId"].text
        // Placeholder IDs are not identities: keep positional answers only when standards still match below.
        val previous = if (recordId.isNotBlank() && currentIdCounts[recordId] == 1) savedById[recordId]?.singleOrNull()
            else saved.getOrNull(index)?.takeIf { it["recordId"].text == recordId }
        check(previous != null && previous[prefix + "Item"]["itemName"].text == current[prefix + "Item"]["itemName"].text &&
            previous[prefix + "Item"]["itemType"].text == current[prefix + "Item"]["itemType"].text) { "检查标准已变化，旧草稿已保留，请重新填写" }
        poolExecutionSnapshot(current).also { row ->
            for (field in listOf("executeResult", prefix + "Result", "description")) if (!previous[field].isNull) row.put(field, previous[field].raw)
            if (!previous[prefix + "Result"].isNull) row.optJSONObject(prefix + "Item")?.put(prefix + "Result", previous[prefix + "Result"].raw)
        }
    }
    return poolExecutionSnapshot(fresh).also { result ->
        for (key in listOf("startTime", "endTime", "content", "albums") +
            if (prefix == "maintain") listOf("erpMaintainMaterials", "pendingErpOutboundId") else emptyList()) {
            if (!draft[key].isNull) result.put(key, draft[key].raw)
        }
        result.put(recordsKey, JSONArray(restored))
    }
}
