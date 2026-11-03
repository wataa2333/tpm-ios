package com.personal.tpm.ui

import com.personal.tpm.data.Jv
import com.personal.tpm.data.filter

/** The two pools the iOS app shows read-only: maintenance orders and precision-check orders waiting to be taken ("待接单"). */
enum class PoolKind(val path: String, val title: String) {
    Maintenance("maintain", "保养工单池"), Precision("precise", "精密度检查工单池");

    val page: String get() = path + (if (this == Maintenance) "/pageMaintain" else "/pagePrecise")
    val nameKey: String get() = path + "Name"
    val idKey: String get() = path + "Id"
    val stateKey: String get() = path + "State"
    /** The original pools default to the RECEIVED state: orders waiting to be taken. */
    fun waitingFilters() = listOf(filter(stateKey, "RECEIVED"))

    companion object { fun byPath(path: String): PoolKind? = entries.firstOrNull { it.path == path } }
}

fun poolFacts(row: Jv): List<Pair<String, String>> = listOf(
    "单号" to row["sn"].text, "资产编码" to row["asset"]["assetCode"].text, "资产名称" to row["asset"]["assetName"].text,
    "机台号" to row["asset"]["addressCode"].text, "地址" to row["asset"]["address"].text, "申请人" to row["applyUser"].label, "受理部门" to row["dept"].label,
    "受理组别" to row["userGroup"].label, "受理人" to row["user"].label, "计划开始" to row["planStartDate"].dateText,
    "计划结束" to row["planEndDate"].dateText,
).filter { it.second.isNotEmpty() }

fun poolCardModel(kind: PoolKind, row: Jv, index: Int) = CardModel(
    key = row[kind.idKey].text.ifEmpty { "${row["sn"].text}#$index" }, title = row[kind.nameKey].text, status = "待接单", colorCode = "RECEIVED", isApproval = false,
    reference = row["asset"]["assetCode"].text.ifEmpty { "—" } + row["asset"]["addressCode"].text.let { if (it.isEmpty()) "" else " · $it" },
    time = row["planStartDate"].dateText, facts = poolFacts(row), kind = null, openId = row[kind.idKey].text, cardType = if (kind == PoolKind.Maintenance) CardType.Maintenance else CardType.Precision,
)

