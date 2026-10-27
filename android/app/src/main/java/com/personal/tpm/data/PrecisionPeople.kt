package com.personal.tpm.data

import org.json.JSONArray

/** Personnel belong to the latest completed execution, never the asset owner or query login. */
suspend fun precisionPeople(source: Session, assetId: String): String {
    val executions = mutableListOf<Jv>()
    var offset = 0
    var expected: Int? = null
    do {
        val page = source.page("precise/pagePrecise", offset = offset, limit = 20, filters = listOf(filter("asset.assetId", assetId)))
        require(page["content"].raw is JSONArray) { "检查工单格式异常" }
        val total = serverPageCount(page) ?: error("没有工单总数")
        require(expected == null || expected == total) { "工单总数发生变化" }; expected = total
        val rows = page["content"].array
        require(rows.isNotEmpty() || offset >= total) { "工单提前结束" }
        rows.forEach { row ->
            require(row["asset"]["assetId"].text == assetId) { "返回其他设备的工单" }
            val response = source.request("precise/getPreciseOrdersByPreciseId", mapOf("preciseId" to row["preciseId"].text))
            require(response.raw is JSONArray) { "执行记录格式异常" }
            executions += response.array.filter { it["endTime"].dateText.isNotBlank() }
        }
        offset += rows.size
    } while (offset < (expected ?: 0))
    val latest = executions.maxByOrNull { it["endTime"].dateText } ?: return "工单检测人：暂无已完成工单记录"
    return "工单检测人：${latest["user"].label.ifEmpty { "未记录" }}"
}
