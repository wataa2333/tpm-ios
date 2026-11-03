package com.personal.tpm.data

import org.json.JSONObject

/** Contracts from base.apk's MaintainOrderHandlePage and PreciseOrderHandlePage. */
fun poolExecutionError(data: Jv, prefix: String, existingMaterials: List<Jv> = emptyList()): String? {
    if (data["orderId"].text.isEmpty()) return "未取得执行记录编号，请重新读取"
    val start = parsedBusinessDateOrNull(data["startTime"])
    val end = parsedBusinessDateOrNull(data["endTime"])
    if (start == null || end == null) return "请填写有效的开始和结束时间"
    if (start.after(end)) return "开始时间不能晚于结束时间"
    for ((i, row) in data[prefix + "Records"].array.withIndex()) {
        if (row["executeResult"].text !in listOf("EXECUTED", "IGNORE")) return "第 ${i + 1} 项请选择完成或忽略"
        if (row["executeResult"].text == "IGNORE") continue
        val item = row[prefix + "Item"]
        val result = row[prefix + "Result"].text.trim()
        if ((item["required"].bool || (prefix == "maintain" && item["itemType"].text == "OPTION")) && result.isEmpty()) return "第 ${i + 1} 项检查结果必填"
        if (result.isNotEmpty() && item["itemType"].text == "NUMERICAL" && result.toDoubleOrNull()?.isFinite() != true)
            return "第 ${i + 1} 项请输入有效数值"
        val options = item["optionList"].array.map { poolResultOption(it) }
        if (item["itemType"].text == "OPTION" && result.isNotEmpty() && result !in options) return "第 ${i + 1} 项请选择标准内的结果"
    }
    if (prefix == "maintain") for (material in data["erpMaintainMaterials"].array) {
        val value = material["useNum"].text.toDoubleOrNull()
        if (value == null || !value.isFinite() || value <= 0) return "请填写有效的物料使用数量"
        val line = material["materialOutbound"]
        val original = existingMaterials.filter { outboundLineIdentity(it["materialOutbound"]) == outboundLineIdentity(line) }.sumOf { it["useNum"].number }
        val available = line["actualQuantity"].number - line["usedQuantity"].number + original
        if (!line["actualQuantity"].isNull && value > available) return "物料使用数量超过剩余数量"
    }
    return null
}

fun poolResultOption(option: Jv): String = option["value"].text.ifEmpty { option["text"].text.ifEmpty { option.text } }

fun poolExecutionSnapshot(data: Jv) = JSONObject(data.raw.toString())

/** Match uni-forms' registered fields, rather than serializing the execution-detail graph. */
fun poolExecutionPayload(data: Jv, prefix: String): JSONObject {
    require(prefix in setOf("maintain", "precise"))
    val out = JSONObject()
    for (key in listOf("orderId", "startTime", "endTime", "content", "albums"))
        if (!data[key].isNull) out.put(key, data[key].raw)
    out.put(prefix + "Records", org.json.JSONArray(data[prefix + "Records"].array.map { row ->
        JSONObject().also { record ->
            for (key in listOf(prefix + "Item", "executeResult", "description"))
                if (!row[key].isNull) record.put(key, poolExecutionSnapshotValue(row[key]))
            // The original form unmounts the result field for ignored items.
            if (row["executeResult"].text == "EXECUTED" && !row[prefix + "Result"].isNull)
                record.put(prefix + "Result", row[prefix + "Result"].raw)
        }
    }))
    if (prefix == "maintain") {
        val materials = linkedMapOf<Pair<String, String>, Any?>()
        data["erpMaintainMaterials"].array.forEach { row ->
            materials[row["outbound"]["outboundId"].text to row["material"]["id"].text] = row.raw
        }
        out.put("erpMaintainMaterials", org.json.JSONArray(materials.values.toList()))
    }
    return out
}

private fun poolExecutionSnapshotValue(value: Jv): Any? =
    if (value.isObject) poolExecutionSnapshot(value) else value.raw

fun poolDefaultRecord(prefix: String, row: Jv): JSONObject = poolExecutionSnapshot(row).also {
    if (row["executeResult"].text == "NOT") it.put("executeResult", "EXECUTED")
    if (row[prefix + "Result"].text.isBlank() && row[prefix + "Item"]["itemType"].text == "TEXT") it.put(prefix + "Result", "正常处理")
}
