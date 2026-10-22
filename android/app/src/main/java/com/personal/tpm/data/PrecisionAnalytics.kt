package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import org.json.JSONArray

val precisionGrades = listOf("AA", "A", "B", "C", "D")

fun precisionBrandIncluded(asset: Jv): Boolean = asset["brand"]["brandName"].text.trim() !in setOf("钶锐锶", "钶锐鍶")

fun precisionBrand(name: String): String = when (name.trim().lowercase(java.util.Locale.ROOT)) {
    "台群", "taikan" -> "台群"
    "发那科", "fanuc" -> "发那科"
    "" -> "未知品牌"
    else -> name.trim()
}

fun precisionAnalysisRequest() = Jv.obj(
    "inspectStates" to JSONArray(listOf("NOT_START", "EXECUTING", "OVERDUE")),
    "faultStates" to JSONArray(), "maintainStates" to JSONArray(),
    "analysisResults" to JSONArray(precisionGrades.map { "RESULT_$it" }),
    "type" to "region", "layerCode" to "", "categoryIds" to JSONArray(),
    "useStates" to JSONArray(listOf("IN_USE")),
    "assetStates" to JSONArray(listOf("NORMAL", "WARNING", "FAULT")),
)

fun precisionCncRows(response: Jv): List<Jv> {
    require(response.raw is JSONArray && response.array.all { it["preciseAnalysisList"].raw is JSONArray }) { "精密度接口返回格式异常" }
    val rows = response.array.flatMap { it["preciseAnalysisList"].array }.filter {
        val category = it["asset"]["assetCategory"]
        category["categoryCode"].text == "017" && category["categoryName"].text == "017CNC加工中心" && precisionBrandIncluded(it["asset"])
    }
    require(rows.all { it["asset"]["assetId"].text.isNotEmpty() && it["analysisResult"].text in precisionGrades.map { grade -> "RESULT_$grade" } }) { "精密度数据缺少设备ID或包含未知等级" }
    require(rows.map { it["asset"]["assetId"].text }.distinct().size == rows.size) { "接口返回重复设备，暂不计算覆盖率，请刷新核对" }
    return rows
}

data class PrecisionSnapshot(val rows: List<Jv>, val total: Int?, val totalNotice: String, val brandTotals: Map<String, Int>? = null) {
    val brands get(): Map<String, List<Jv>> {
        val grouped = rows.groupBy { precisionBrand(it["asset"]["brand"]["brandName"].text) }
        return ((brandTotals?.keys ?: emptySet()) + grouped.keys).associateWith { grouped[it].orEmpty() }.toSortedMap()
    }
    fun grades(items: List<Jv> = rows) = precisionGrades.map { grade -> items.count { it["analysisResult"].text == "RESULT_$grade" } }
}

suspend fun loadPrecision(session: Session): PrecisionSnapshot {
    val rows = precisionCncRows(session.request("precise/getPreciseAnalysis", body = precisionAnalysisRequest()))
    if (rows.isEmpty()) return PrecisionSnapshot(rows, null, "没有匹配的分析结果，暂不能确定目标设备总数。")
    return try {
        val categoryIds = rows.map { it["asset"]["assetCategory"]["categoryId"].text }.distinct()
        require(categoryIds.all { it.isNotEmpty() }) { "分析数据缺少资产类别ID" }
        val filters = listOf(
            Jv.obj("field" to "assetCategory.categoryId", "operator" to "in", "value" to JSONArray(categoryIds), "logic" to "and"),
            Jv.obj("field" to "useState", "operator" to "in", "value" to JSONArray(listOf("IN_USE")), "logic" to "and"),
            Jv.obj("field" to "assetState", "operator" to "in", "value" to JSONArray(listOf("NORMAL", "WARNING", "FAULT")), "logic" to "and"),
        )
        val assets = linkedMapOf<String, Jv>()
        var expected: Int? = null
        var offset = 0
        do {
            val page = session.request("asset/pageAsset", body = Jv.obj("offset" to offset, "limit" to 500,
                "sort" to "assetCode", "order" to "asc", "filters" to JSONArray(filters)))
            val total = serverPageCount(page) ?: error("服务器没有返回设备总数")
            require(expected == null || expected == total) { "读取期间设备总数发生变化，请刷新重试" }
            expected = total
            require(page["content"].raw is JSONArray) { "资产列表返回格式异常" }
            val items = page["content"].array
            require(items.isNotEmpty() || offset >= total) { "设备列表提前结束，请刷新重试" }
            items.forEach { asset ->
                val id = asset["assetId"].text
                require(id.isNotEmpty() && id !in assets) { "设备列表出现缺失或重复ID，请刷新重试" }
                require(asset["assetCategory"]["categoryCode"].text == "017" && asset["assetCategory"]["categoryName"].text == "017CNC加工中心" && asset["useState"].text == "IN_USE" && asset["assetState"].text in listOf("NORMAL", "WARNING", "FAULT")) { "资产接口没有正确执行筛选，暂不计算覆盖率" }
                assets[id] = asset
            }
            offset += items.size
        } while (offset < (expected ?: 0))
        require(assets.size == expected && rows.all { it["asset"]["assetId"].text in assets }) { "分析结果与设备台账不一致，请刷新核对" }
        require(rows.all { precisionBrand(it["asset"]["brand"]["brandName"].text) == precisionBrand(assets.getValue(it["asset"]["assetId"].text)["brand"]["brandName"].text) }) { "品牌数据在读取期间发生变化，请刷新核对" }
        val included = assets.values.filter(::precisionBrandIncluded)
        val totals = included.groupingBy { precisionBrand(it["brand"]["brandName"].text) }.eachCount()
        require(rows.groupBy { precisionBrand(it["asset"]["brand"]["brandName"].text) }.all { (brand, checked) -> checked.size <= (totals[brand] ?: 0) }) { "品牌数据在读取期间发生变化，请刷新核对" }
        PrecisionSnapshot(rows, included.size, "", totals)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { PrecisionSnapshot(rows, null, "设备总数读取失败：${e.message ?: "未知错误"}。等级统计仍可查看，覆盖率暂不可用。") }
}
