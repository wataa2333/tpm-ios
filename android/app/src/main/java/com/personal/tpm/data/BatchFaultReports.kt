package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject

internal const val FaultTitleLimit = 64
internal const val FaultPartVariable = "配件项目"
internal val defaultFaultPartOptions = listOf("X轴左护罩", "X轴右护罩", "Y轴前护罩", "Y轴后护罩", "Z轴护罩")

internal fun faultVariableOptions(input: String): List<String> =
    input.lineSequence().map(String::trim).filter(String::isNotEmpty).distinct().toList()

internal val faultTitleVariable = Regex("【([^【】\\r\\n]+)】")
private val unselectedPartToken = Regex("(?<=[ \\t])【配件项目】[ \\t]*|【配件项目】")

enum class FaultTitleAssetField(val label: String, val key: String) {
    Machine("机台号", "addressCode"),
    Code("资产编码", "assetCode"),
    Name("资产名称", "assetName"),
}

data class FaultTitlePattern(val format: String) {
    val variables: List<String> get() = faultTitleVariable.findAll(format).map { it.groupValues[1] }.distinct().toList()
    val customVariables: List<String> get() = variables.filterNot { name -> FaultTitleAssetField.entries.any { it.label == name } }

    private fun value(name: String, asset: Jv, values: Map<String, String>): String {
        val field = FaultTitleAssetField.entries.firstOrNull { it.label == name }
        return (if (field == null) values[name].orEmpty() else asset[field.key].text).trim()
    }

    fun missing(asset: Jv, values: Map<String, String>): List<String> =
        variables.filter { it != FaultPartVariable && value(it, asset, values).isEmpty() }

    fun title(asset: Jv, values: Map<String, String> = emptyMap()): String {
        val source = if (values[FaultPartVariable].isNullOrBlank()) format.replace(unselectedPartToken, "") else format
        return faultTitleVariable.replace(source) { value(it.groupValues[1], asset, values).ifEmpty { it.value } }.trim()
    }

    companion object {
        fun token(name: String) = "【$name】"

        fun read(format: Jv, legacy: Jv): FaultTitlePattern? {
            if (!format.isNull) return FaultTitlePattern(format.text)
            if (legacy["prefix"].isNull && legacy["suffix"].isNull) return null
            return FaultTitlePattern(listOf(legacy["prefix"].text.trim(), token(FaultTitleAssetField.Machine.label),
                legacy["suffix"].text.trim()).filter(String::isNotEmpty).joinToString(" "))
        }
    }
}

internal fun batchMachineCodes(input: String): List<String> =
    input.split(Regex("[\\s,，、;；]+")).map { it.trim().uppercase() }
        .filter(String::isNotEmpty).distinctBy(::normalizeReportMachineCode)

internal suspend fun Session.findBatchMachineAssets(machine: String): List<Jv> {
    val normalized = normalizeReportMachineCode(machine)
    val compact = normalized.takeIf { it.matches(Regex("[A-Z]-[A-Z][0-9]{2}")) }?.replace("-", "")
    val result = linkedMapOf<String, Jv>()
    for (code in listOfNotNull(machine, normalized, compact).distinct()) {
        var offset = 0
        val seen = hashSetOf<String>()
        while (true) {
            val page = page("asset/pageAsset", offset, listOf(filter("addressCode", code)), limit = 50)
            val content = page["content"]
            check(content.raw is JSONArray) { "资产列表返回格式异常" }
            val rows = content.array
            for (row in rows) {
                val id = row["assetId"].text
                if (id.isNotBlank() && row["assetCode"].text.isNotBlank() &&
                    normalizeReportMachineCode(row["addressCode"].text) == normalized) result[id] = row
            }
            val total = serverPageCount(page)
            val end = page["last"].bool || (total != null && offset + rows.size >= total) ||
                (page["last"].isNull && total == null && rows.size < 50)
            if (end) break
            check(rows.count { seen.add(it.toString()) } > 0) { "资产列表分页未推进，请重试" }
            offset += rows.size
        }
    }
    return result.values.toList()
}

internal suspend fun Session.checkedReportAsset(reference: Jv): Jv {
    val id = reference["assetId"].text
    val code = reference["assetCode"].text
    require(code.isNotBlank()) { "资产信息不完整，请重新选择报修资产" }
    val condition = if (id.isNotBlank()) filter("assetId", id) else filter("assetCode", code)
    val result = page("asset/pageAsset", filters = listOf(condition), limit = 2)
    return requireNotNull(result["content"].array.singleOrNull {
        it["assetId"].text.isNotBlank() && (id.isBlank() || it["assetId"].text == id) && it["assetCode"].text == code
    }) { "当前登录账号无法核对该资产，请重新选择" }
}

internal data class BatchFaultRule(val variable: String, val value: String, val label: String, val fields: Jv) {
    fun json() = Jv.obj("variable" to variable, "value" to value, "label" to label, "fields" to fields.raw)
    fun matches(row: BatchFaultRow): Boolean {
        val actual = row.variable(variable)
        return if (variable == FaultTitleAssetField.Machine.label) normalizeReportMachineCode(actual) == normalizeReportMachineCode(value)
        else actual.equals(value, ignoreCase = true)
    }

    companion object {
        fun read(json: Jv) = BatchFaultRule(json["variable"].text, json["value"].text, json["label"].text, json["fields"])
    }
}

internal fun batchFaultData(form: JSONObject, row: BatchFaultRow, title: String, rules: List<BatchFaultRule>): JSONObject {
    val data = JSONObject(form.toString())
    rules.filter { it.matches(row) }.forEach { rule ->
        rule.fields.keys.forEach { key -> data.put(key, rule.fields[key].raw ?: JSONObject.NULL) }
    }
    return data.put("faultName", title).put("asset", row.asset.raw)
}

internal enum class BatchFaultStatus { Ready, Checking, Failed, Unknown, Submitted }

internal data class BatchFaultRow(
    val asset: Jv,
    val status: BatchFaultStatus = BatchFaultStatus.Ready,
    val title: String = "",
    val detail: String = "",
    val titleValues: Map<String, String> = emptyMap(),
    val editedTitle: String? = null,
    val id: String = asset["assetId"].text,
) {
    val assetId get() = asset["assetId"].text
    val machine get() = asset["addressCode"].text
    val displayCode get() = machine.ifBlank { asset["assetCode"].text }
    val canSubmit get() = status == BatchFaultStatus.Ready || status == BatchFaultStatus.Failed
    val assetLabel get() = listOf(machine, asset["assetCode"].text, asset.label)
        .filter(String::isNotBlank).distinct().joinToString(" · ")
    fun variable(name: String): String {
        val key = FaultTitleAssetField.entries.firstOrNull { it.label == name }?.key
        return (if (key == null) titleValues[name].orEmpty() else asset[key].text).trim()
    }

    fun json() = Jv.obj("rowId" to id, "asset" to asset.raw, "status" to status.name, "title" to title, "detail" to detail,
        "titleValues" to JSONObject(titleValues), "editedTitle" to editedTitle)

    companion object {
        fun read(row: Jv): BatchFaultRow {
            val status = BatchFaultStatus.entries.firstOrNull { it.name == row["status"].text } ?: BatchFaultStatus.Unknown
            val values = row["titleValues"]
            val asset = row["asset"]
            return BatchFaultRow(asset, if (status == BatchFaultStatus.Checking) BatchFaultStatus.Unknown else status,
                row["title"].text, row["detail"].text, values.keys.associateWith { values[it].text },
                row["editedTitle"].takeUnless { it.isNull }?.text, row["rowId"].text.ifBlank { asset["assetId"].text })
        }
    }
}

internal fun batchReportDraft(
    id: String, form: JSONObject, selections: Map<String, List<String>>, pattern: FaultTitlePattern?,
    rows: List<BatchFaultRow>, pasted: String, rules: List<BatchFaultRule> = emptyList(),
    assetCodes: List<String> = emptyList(), variableOptions: Map<String, List<String>> = emptyMap(),
): FormDraft {
    val label = pattern?.format ?: Jv(form)["faultName"].text
    return FormDraft(
        id = id,
        data = Jv.obj(
            "faultName" to listOf(label, "${rows.size} 单").filter(String::isNotBlank).joinToString(" · "),
            "form" to form, "titleFormat" to pattern?.format,
            "rows" to JSONArray(rows.map(BatchFaultRow::json)), "pasted" to pasted,
            "rules" to JSONArray(rules.map(BatchFaultRule::json)), "assetCodes" to JSONArray(assetCodes),
            "variableOptions" to Jv.obj(*variableOptions.map { (name, options) -> name to JSONArray(options) }.toTypedArray()),
        ),
        selections = selections,
    )
}
