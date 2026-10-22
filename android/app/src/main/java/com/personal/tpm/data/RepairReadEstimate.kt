package com.personal.tpm.data

import kotlinx.coroutines.CancellationException

val acceptedRepairStates = listOf("VERIFIED", "RESOLVED", "FINISHED")
data class RepairReadEstimate(val counts: Map<String, Int?>, val errors: List<String> = emptyList()) {
    val total: Long? get() = if (counts.isNotEmpty() && counts.values.all { it != null }) counts.values.sumOf { it!!.toLong() } else null
}
suspend fun estimateRepairRead(states: List<String>, checkScope: () -> Unit = {}, page: suspend (String) -> Jv): RepairReadEstimate {
    val counts = linkedMapOf<String,Int?>()
    val errors = mutableListOf<String>()
    for (state in states) {
        checkScope()
        counts[state] = try { val result = page(state); checkScope(); workloadPageTotal(result) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { errors += "$state：${e.message ?: "无法获取总数"}"; null }
    }
    return RepairReadEstimate(counts,errors)
}
fun sortedPersonWorkloads(documents: List<ManagedDocument>, processed: Boolean, ascending: Boolean): List<PersonWorkload> {
    val comparator = compareBy<PersonWorkload> { if (processed) it.processed else it.pending }
    return personnelWorkloads(documents).sortedWith((if (ascending) comparator else comparator.reversed()).thenBy { it.name }.thenBy { it.key.orEmpty() })
}
