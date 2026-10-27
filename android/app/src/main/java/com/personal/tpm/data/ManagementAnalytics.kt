package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Field/state names are from the original client's page queries and dictionaries. */
enum class ManagedKind(val title: String, val page: String, val idField: String, val nameField: String, val stateField: String, val workingState: String, val personField: String, val endField: String) {
    Fault("维修", "fault/pageAssetFault", "faultId", "faultName", "faultState", "REPAIRED", "repairUser", "faultTime"),
    Maintain("保养", "maintain/pageMaintain", "maintainId", "maintainName", "maintainState", "MAINTAINED", "maintainUser", "endTime"),
    Precise("精密度检查", "precise/pagePrecise", "preciseId", "preciseName", "preciseState", "PRECISED", "user", "endTime")
}
enum class ManagedPhase(val title: String) { Sign("待签到"), Finish("待完成"), Processed("已提交处理") }
data class ManagedDocument(val kind: ManagedKind, val id: String, val title: String, val number: String, val phase: ManagedPhase, val personKey: String?, val personName: String, val asset: String, val end: String, val state: String = "") {
    val key get() = "${kind.name}:$id"
}
data class WorkloadSlice(val kind: ManagedKind, val state: String, val documents: List<ManagedDocument>, val complete: Boolean, val message: String = "", val expected: Int? = null)
data class PersonWorkload(val key: String?, val name: String, val sign: Int, val finish: Int, val processed: Int) { val pending get() = sign + finish }
fun managedPhase(kind: ManagedKind, state: String): ManagedPhase? = when (state) {
    "SIGNED" -> ManagedPhase.Sign
    kind.workingState -> ManagedPhase.Finish
    "VERIFIED", "RESOLVED", "FINISHED" -> ManagedPhase.Processed
    else -> null
}
fun workloadFilters(kind: ManagedKind, state: String, span: DateSpan?): List<JSONObject> = buildList {
    add(filter(kind.stateField, state))
    if (span != null) add(dateBetween(kind.endField, span))
}
fun managedDocument(kind: ManagedKind, row: Jv, state: String, span: DateSpan?): ManagedDocument? {
    val phase = managedPhase(kind, state) ?: return null
    if (row[kind.stateField].text != state || row[kind.idField].text.isBlank()) return null
    val end = row[kind.endField].dateText
    if (span != null) {
        val date = runCatching { LocalDate.parse(end.take(10)) }.getOrNull() ?: return null
        if (date < span.days.first || date > span.days.second) return null
    }
    val owner = row[kind.personField]
    val username = owner["userName"].text.trim()
    val id = owner["id"].text.ifBlank { owner["userId"].text }.trim()
    val personKey = when { username.isNotEmpty() -> "user:$username"; id.isNotEmpty() -> "id:$id"; else -> null }
    val personName = owner["realName"].text.ifBlank { username }.ifBlank { if (personKey == null) "执行人未明确" else "用户 $id" }
    return ManagedDocument(kind, row[kind.idField].text, row[kind.nameField].text.ifBlank { kind.title + "工单" }, row["sn"].text, phase, personKey, personName, row["asset"]["assetName"].text, end)
}
fun personnelWorkloads(documents: List<ManagedDocument>): List<PersonWorkload> = documents.distinctBy { it.key }.groupBy { it.personKey }.map { (key, docs) ->
    PersonWorkload(key, if (key == null) "执行人未明确" else docs.first().personName, docs.count { it.phase == ManagedPhase.Sign }, docs.count { it.phase == ManagedPhase.Finish }, docs.count { it.phase == ManagedPhase.Processed })
}.sortedWith(compareByDescending<PersonWorkload> { it.pending }.thenByDescending { it.processed }.thenBy { it.name })

/** Read-only seam. Paging is bounded and incomplete/failed results never masquerade as a complete total. */
suspend fun collectWorkload(
    kind: ManagedKind, state: String, span: DateSpan?,
    page: suspend (offset: Int, limit: Int) -> Jv,
    checkScope: () -> Unit = {}, maxRows: Int = 2000, limit: Int = 100,
    progress: (Int) -> Unit = {},
): WorkloadSlice {
    require(maxRows > 0 && limit > 0)
    val documents = linkedMapOf<String, ManagedDocument>()
    val seen = mutableSetOf<String>()
    var offset = 0
    var expected: Int? = null
    var excluded = false
    try {
        while (offset < maxRows) {
            checkScope()
            val requested = minOf(limit, maxRows - offset)
            val result = page(offset, requested)
            checkScope()
            require(result["content"].raw is JSONArray) { "服务器未返回工单列表" }
            val rows = result["content"].array
            expected = serverPageCount(result) ?: expected
            var fresh = 0
            for (row in rows) {
                val id = row[kind.idField].text
                if (id.isNotBlank() && seen.add(id)) fresh++ else excluded = true
                val document = managedDocument(kind, row, state, span)
                if (document == null) excluded = true else documents.putIfAbsent(document.key, document)
            }
            offset += rows.size
            progress(documents.size)
            val last = if (result["last"].raw is Boolean) result["last"].bool else expected?.let { offset >= it } ?: (rows.size < requested)
            if (last) {
                val complete = !excluded && (expected == null || offset >= expected)
                return WorkloadSlice(kind, state, documents.values.toList(), complete, if (!complete) "记录缺失、状态变化、日期不符或分页总数不一致；结果仅为已加载记录" else "", expected)
            }
            if (rows.isEmpty() || fresh == 0) return WorkloadSlice(kind, state, documents.values.toList(), false, "分页没有新增记录，已停止，结果不完整", expected)
        }
        return WorkloadSlice(kind, state, documents.values.toList(), false, "已达单状态 $maxRows 条上限，结果不完整", expected)
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) { return WorkloadSlice(kind, state, documents.values.toList(), false, e.message ?: "读取失败", expected) }
}

/** The original client exposes these executor realName filters on all three page queries. */
fun personnelFilters(kind: ManagedKind, state: String, span: DateSpan?, name: String): List<JSONObject> =
    workloadFilters(kind, state, span) + listOfNotNull(name.trim().takeIf { it.isNotEmpty() }?.let { filter("${kind.personField}.realName", it) })
fun workloadPageTotal(page: Jv): Int {
    require(page["content"].raw is JSONArray) { "服务器未返回分页数据" }
    return serverPageCount(page) ?: if (page["last"].raw is Boolean && page["last"].bool) page["content"].array.size
        else throw IllegalStateException("服务器未提供总数，不能把首条结果当作统计")
}
