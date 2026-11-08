package com.personal.tpm.data

data class MaintenanceReport(val processed: Boolean, val span: DateSpan?, val slices: List<WorkloadSlice> = emptyList()) {
    val states get() = if (processed) listOf("VERIFIED", "RESOLVED", "FINISHED") else listOf("SIGNED", "MAINTAINED")
    val documents by lazy { slices.flatMap { it.documents }.associateBy { it.key }.values.toList() }
    val duplicated by lazy { slices.sumOf { it.documents.size } != documents.size }
    val complete by lazy { slices.map { it.state }.toSet() == states.toSet() && slices.all { it.complete } && !duplicated }
    val people by lazy { personnelWorkloads(documents).sortedWith(compareByDescending<PersonWorkload> { if (processed) it.processed else it.pending }.thenBy { it.name }) }
}

/** Reuses the verified maintenance paging, claimant and end-time fields; no analysis endpoint is guessed. */
suspend fun loadMaintenanceReport(processed: Boolean, span: DateSpan?, checkScope: () -> Unit = {},
    progress: (MaintenanceReport) -> Unit = {}, reading: (String, Int) -> Unit = { _, _ -> },
    page: suspend (filters: List<org.json.JSONObject>, offset: Int, limit: Int) -> Jv): MaintenanceReport {
    require(!processed || span != null) { "请选择保养完成日期" }
    var report = MaintenanceReport(processed, if (processed) span else null)
    for (state in report.states) {
        checkScope()
        val filters = workloadFilters(ManagedKind.Maintain, state, report.span)
        val slice = collectWorkload(ManagedKind.Maintain, state, report.span, limit = 100, maxRows = Int.MAX_VALUE,
            checkScope = checkScope, progress = { reading(state, it) }, page = { offset, limit -> page(filters, offset, limit) })
        report = report.copy(slices = report.slices + slice)
        progress(report)
    }
    return report
}
