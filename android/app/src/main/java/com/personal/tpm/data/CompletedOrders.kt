package com.personal.tpm.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import java.time.Instant
import java.time.LocalTime

private const val CompletedOrdersPageSize = 100
private val businessDayStart = LocalTime.of(8, 0)

internal data class CompletionDay(val start: Instant, val end: Instant) {
    operator fun contains(time: Instant): Boolean = time >= start && time < end
}

internal fun completionDay(now: Instant = Instant.now()): CompletionDay {
    val localNow = now.atZone(BusinessZone)
    val date = if (localNow.toLocalTime() < businessDayStart) localNow.toLocalDate().minusDays(1) else localNow.toLocalDate()
    return CompletionDay(
        date.atTime(businessDayStart).atZone(BusinessZone).toInstant(),
        date.plusDays(1).atTime(businessDayStart).atZone(BusinessZone).toInstant(),
    )
}

internal data class CompletedOrder(val key: String, val finishedAt: Instant, val task: Jv)

internal fun completedOrder(task: Jv): CompletedOrder? {
    if (task["taskDefinitionKey"].text == "ACT_INITIATOR") return null
    val finishedAt = parsedBusinessDateOrNull(task["endTime"])?.toInstant() ?: return null
    val definition = task["processDefinitionKey"].text
    val type = todoKind(task)?.prefix ?: when {
        definition.startsWith("MAINTAIN") -> "MAINTAIN"
        definition.startsWith("PRECISE") -> "PRECISE"
        else -> definition.substringBefore(':')
    }
    val businessId = task["businessKey"].text
    val processId = task["processInstanceId"].text
    val taskId = task["id"].text
    val key = when {
        businessId.isNotBlank() -> "order:$type:$businessId"
        processId.isNotBlank() -> "process:$processId"
        taskId.isNotBlank() -> "task:$taskId"
        else -> return null
    }
    return CompletedOrder(key, finishedAt, task)
}

/** The personal history endpoint returns finished tasks in descending end-time order. */
internal class CompletedOrdersReader(private val fetch: suspend (offset: Int) -> Jv) {
    private var offset = 0
    private val seenTasks = mutableSetOf<String>()
    var last = false
        private set

    suspend fun next(): List<CompletedOrder> {
        if (last) return emptyList()
        currentCoroutineContext().ensureActive()
        val page = fetch(offset)
        val content = page["content"]
        check(content.raw is JSONArray) { "已完成工单列表格式异常" }
        val rows = content.array
        val nextOffset = offset + rows.size
        val total = serverPageCount(page)
        val reachedEnd = page["last"].bool || (page["last"].isNull &&
            (if (total != null) nextOffset >= total else rows.size < CompletedOrdersPageSize))
        val fresh = rows.filter { seenTasks.add(it["id"].text.ifEmpty { it.toString() }) }
        check(reachedEnd || fresh.isNotEmpty()) { "已完成工单分页没有继续推进，请刷新重试" }
        offset = nextOffset
        last = reachedEnd
        return fresh.mapNotNull(::completedOrder)
    }
}

internal fun Session.completedOrdersReader(): CompletedOrdersReader {
    val ownerScope = scope.value
    return CompletedOrdersReader { offset ->
        if (ownerScope != scope.value) throw CancellationException("completed order scope changed")
        val page = page("process/pageUserHistoryTask", offset = offset, limit = CompletedOrdersPageSize)
        if (ownerScope != scope.value) throw CancellationException("completed order scope changed")
        page
    }
}

internal suspend fun countCompletedOrders(reader: CompletedOrdersReader, day: CompletionDay): Int {
    val orders = mutableSetOf<String>()
    var previous: Instant? = null
    var descending = true
    while (!reader.last) {
        val records = reader.next()
        for (order in records) {
            if (previous?.let { order.finishedAt > it } == true) descending = false
            previous = order.finishedAt
            if (order.finishedAt in day) orders += order.key
        }
        // If the server changes the observed order, read through the end instead of stopping at the date boundary.
        if (descending && records.lastOrNull()?.finishedAt?.let { it < day.start } == true) break
    }
    return orders.size
}
