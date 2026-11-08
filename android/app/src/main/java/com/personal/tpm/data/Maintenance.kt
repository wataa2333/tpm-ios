package com.personal.tpm.data

/** Codes and order from the original maintainState dictionary; labels follow the Android workbench. */
enum class MaintenanceState(val code: String, val title: String) {
    NotStart("NOT_START", "未开始"), Approved("APPROVED", "待审批"), Reported("REPORTED", "待受理"),
    Received("RECEIVED", "待抢单"), Signed("SIGNED", "待签到"), Maintained("MAINTAINED", "待完成"),
    Verified("VERIFIED", "待确认"), Resolved("RESOLVED", "待复线"), Finished("FINISHED", "已完结"), Overdue("OVERDUE", "逾期作废");

    val hasCount get() = this != Finished
    val countKey get() = "maintain:$code"
    companion object {
        val home = listOf(Reported, Received, Signed, Maintained, Verified, Resolved, Finished)
        fun byCode(code: String) = entries.firstOrNull { it.code == code }
    }
}

fun maintenanceStateName(code: String): String = if (code.isEmpty()) "全部" else MaintenanceState.byCode(code)?.title ?: code
fun maintenanceStateFilters(code: String): List<org.json.JSONObject> {
    require(code.isEmpty() || MaintenanceState.byCode(code) != null) { "未知保养状态" }
    return if (code.isEmpty()) emptyList() else listOf(filter("maintainState", code))
}
