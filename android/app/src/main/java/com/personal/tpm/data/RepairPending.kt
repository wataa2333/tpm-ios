package com.personal.tpm.data

import org.json.JSONArray

/** A resumable, read-only list snapshot. No detail requests or name-based grouping. */
data class RepairPendingPage(
    val state: String,
    val offset: Int = 0,
    val expected: Int? = null,
    val ended: Boolean = false,
    val warnings: Set<String> = emptySet(),
)
data class RepairPendingSnapshot(
    val pages: List<RepairPendingPage> = listOf(RepairPendingPage("SIGNED"), RepairPendingPage("REPAIRED")),
    val documents: List<ManagedDocument> = emptyList(),
    val executorChecks: Map<String, String> = emptyMap(),
    val pageSize: Int = 100,
) {
    val readCount get() = pages.sumOf { it.offset }
    val exhausted get() = pages.all { it.ended }
    val complete get() = exhausted && pages.all { it.warnings.isEmpty() }
}

suspend fun loadRepairPending(
    initial: RepairPendingSnapshot = RepairPendingSnapshot(),
    budget: Int? = 500,
    span: DateSpan? = null,
    pageSize: Int = initial.pageSize,
    checkScope: () -> Unit = {},
    progress: (RepairPendingSnapshot) -> Unit = {},
    page: suspend (state: String, offset: Int, limit: Int) -> Jv,
): RepairPendingSnapshot {
    require(budget == null || budget > 0)
    require(pageSize in 1..1000)
    require(initial.readCount == 0 || initial.pageSize == pageSize) { "继续读取不能更改分页大小，请重新查询" }
    var snapshot = initial
    var remaining = budget
    // Alternate states so both are represented when the first 500-row batch pauses.
    while (remaining == null || remaining > 0) {
        val index = snapshot.pages.indices.filter { !snapshot.pages[it].ended }
            .minByOrNull { snapshot.pages[it].offset } ?: break
        checkScope()
        val cursor = snapshot.pages[index]
        // Keep page size stable: Spring-style backends may turn offset/limit into a page index.
        // Shrinking the final request to the remaining batch budget would overlap earlier records.
        val limit = pageSize
        if (remaining != null && remaining < limit) break
        val response = page(cursor.state, cursor.offset, limit)
        checkScope()
        require(response["content"].raw is JSONArray) { "服务器未返回维修工单列表" }
        val rows = response["content"].array
        require(rows.size <= limit) { "服务器未遵守分页条数，已停止；请重新查询" }
        val expected = serverPageCount(response)
        val warnings = cursor.warnings.toMutableSet()
        if (span == null && cursor.expected != null && expected != null && cursor.expected != expected) warnings += "查询期间总数发生变化"
        val docs = snapshot.documents.associateByTo(linkedMapOf()) { it.key }
        var fresh = 0
        for (row in rows) {
            val actualState = row["faultState"].text
            if (span != null && row["faultId"].text.isNotBlank() && actualState.isNotBlank()) {
                val key = "Fault:${row["faultId"].text}"
                val date = runCatching { java.time.LocalDate.parse(row[ManagedKind.Fault.endField].dateText.take(10)) }.getOrNull()
                // A later observation supersedes the previous one, including leaving the chosen scope.
                if (actualState !in acceptedRepairStates || (date != null && (date < span.days.first || date > span.days.second))) {
                    docs.remove(key)
                    continue
                }
            }
            val document = managedDocument(ManagedKind.Fault, row, if (span != null) actualState else cursor.state, span)
            if (document == null) {
                if (span != null && row["faultId"].text.isNotBlank()) docs.remove("Fault:${row["faultId"].text}")
                warnings += "存在缺少编号、状态不符或故障发现日期不符的记录"; continue
            }
            val kept = if (span == null) document else document.copy(title = "", number = "", asset = "", end = document.end.take(10))
            if (span != null) {
                if (docs.put(document.key, kept) == null) fresh++
            } else if (docs.putIfAbsent(document.key, kept) != null) warnings += "分页出现重复工单或状态变化"
            else fresh++
        }
        val offset = cursor.offset + rows.size
        val total = expected ?: cursor.expected
        val explicitLast = (response["last"].raw as? Boolean)
        var ended = explicitLast ?: total?.let { offset >= it } ?: (rows.size < limit)
        if (span == null && total != null && (offset > total || (ended && offset != total))) warnings += "分页数量与服务端总数不一致"
        if (!ended && (rows.isEmpty() || fresh == 0)) {
            warnings += "分页未返回新记录，已停止"
            ended = true
        }
        if (!ended && rows.size < limit) { warnings += "每页请求${limit}条，服务器本页仅返回${rows.size}条且未结束；请降低每页数量后重新查询"; ended = true }
        snapshot = RepairPendingSnapshot(snapshot.pages.toMutableList().also {
            it[index] = RepairPendingPage(cursor.state, offset, total, ended, warnings)
        }, docs.values.toList(), snapshot.executorChecks, pageSize)
        remaining = remaining?.minus(rows.size)
        progress(snapshot)
    }
    return snapshot
}
