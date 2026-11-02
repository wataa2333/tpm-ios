package com.personal.tpm.ui


enum class CardType(val title: String) {
    Fault("维修工单"), Erp("ERP 出库工单"), TodoFault("维修待办"), TodoErp("ERP 待办"), TodoOther("其他待办"),
    Asset("资产台账"), Stock("ERP 库存"), Maintenance("保养工单"), Precision("精密度检查工单")
}
const val DETAIL_BUTTON = "展开／收起详情按钮"
enum class DetailButtonPosition(val title: String, val top: Boolean, val left: Boolean) {
    TopLeft("上方靠左", true, true), TopRight("上方靠右", true, false),
    BottomLeft("下方靠左", false, true), BottomRight("下方靠右", false, false)
}

enum class FieldPosition(val title: String) { Auto("默认"), Left("同行靠前"), Right("同行靠后"), Full("独占一行") }

enum class FieldPlace(val title: String) { Compact("缩略"), Detail("展开"), Hidden("隐藏") }

val faultFields = listOf("资产编码", "资产名称", "机台号", "地址", "报修人", "报修时间", "受理组别", "受理部门", "受理人", "故障描述")
val erpFields = listOf("申请人", "领用人", "领用部门", "项目号", "项目名称", "关联单号", "ERP记账部门", "出库时间")
val assetCardFields = listOf("资产名称", "资产编号", "机台号", "地址", "规格型号", "使用部门", "责任人", "资产类别", "使用状态", "制造商", "旧资产编号")
val stockFields = listOf("ERP库存组织", "批次", "子库", "货位", "货位描述")
val poolCardFields = listOf("单号", "资产编码", "资产名称", "机台号", "地址", "申请人", "受理部门", "受理组别", "受理人", "计划开始", "计划结束")
fun cardFields(type: CardType): List<String> = (listOf("状态", "编号", "时间") + when (type) {
    CardType.Fault -> faultFields
    CardType.Erp -> erpFields
    CardType.TodoFault -> listOf("流程类型", "当前节点") + faultFields
    CardType.TodoErp -> listOf("流程类型", "当前节点") + erpFields
    CardType.TodoOther -> listOf("流程类型", "当前节点")
    CardType.Asset -> assetCardFields
    CardType.Stock -> stockFields
    CardType.Maintenance, CardType.Precision -> poolCardFields
}).distinct()

/** Fixed layouts: old per-device customizations are deliberately no longer read. */
object FixedCardLayout {
    fun placement(type: CardType, field: String, default: FieldPlace) = default
    fun position(type: CardType, field: String) = FieldPosition.Auto
    fun showLabel(type: CardType, field: String) = false
    fun orderedFields(type: CardType) = cardFields(type)
    fun detailButtonPosition(type: CardType) = DetailButtonPosition.BottomRight
    fun detailButtonSlot(type: CardType) = 3
    fun slot(type: CardType, field: String): Int? = null
    fun default(type: CardType, field: String): FieldPlace = if (field in listOf("状态", "编号", "时间") || (type == CardType.Stock && field in stockFields)) FieldPlace.Compact else FieldPlace.Detail
}

/** A pure projection. It never edits the server model or affects business payloads. */
fun configureCard(model: CardModel, position: (String) -> FieldPosition = { FieldPosition.Auto }, choose: (String, FieldPlace) -> FieldPlace): CardModel {
    val compact = mutableListOf<Pair<String, String>>()
    val detail = mutableListOf<Pair<String, String>>()
    fun distribute(label: String, value: String, fallback: FieldPlace) {
        if (value.isBlank()) return
        when (choose(label, fallback)) {
            FieldPlace.Compact -> compact += label to value
            FieldPlace.Detail -> detail += label to value
            FieldPlace.Hidden -> Unit
        }
    }
    val statusPlace = choose("状态", FieldPlace.Compact)
    val referencePlace = choose("编号", FieldPlace.Compact)
    val timePlace = choose("时间", FieldPlace.Compact)
    if (statusPlace == FieldPlace.Detail && model.status.isNotBlank()) detail += "状态" to model.status
    if (referencePlace == FieldPlace.Detail && model.reference.isNotBlank()) detail += "编号" to model.reference
    if (timePlace == FieldPlace.Detail && model.time.isNotBlank()) detail += "时间" to model.time
    if (statusPlace == FieldPlace.Compact && position("状态") != FieldPosition.Auto) compact += "状态" to model.status
    if (referencePlace == FieldPlace.Compact && position("编号") != FieldPosition.Auto) compact += "编号" to model.reference
    if (timePlace == FieldPlace.Compact && position("时间") != FieldPosition.Auto) compact += "时间" to model.time
    (model.summary + model.facts).distinctBy { it.first }.forEach { (label, value) ->
        // The reference already displays this exact asset identifier. Keep other asset fields.
        if (label in listOf("资产编码", "资产编号") && value.isNotBlank() &&
            referencePlace == FieldPlace.Compact && model.reference.split(" · ").any { it.trim() == value.trim() }) return@forEach
        distribute(label, value, if (model.summary.any { it.first == label }) FieldPlace.Compact else FieldPlace.Detail)
    }
    return model.copy(status = if (statusPlace == FieldPlace.Compact && position("状态") == FieldPosition.Auto) model.status else "", reference = if (referencePlace == FieldPlace.Compact && position("编号") == FieldPosition.Auto) model.reference else "",
        time = if (timePlace == FieldPlace.Compact && position("时间") == FieldPosition.Auto) model.time else "", summary = compact, facts = detail)
}

data class SkeletonPlan(val reference: Boolean, val status: Boolean, val summaryLines: Int, val footer: Boolean, val topButton: Boolean = false)
fun skeletonPlan(type: CardType, position: (String) -> FieldPosition = { FieldPosition.Auto }, order: List<String> = cardFields(type), buttonPosition: DetailButtonPosition = DetailButtonPosition.BottomRight, choose: (String, FieldPlace) -> FieldPlace): SkeletonPlan {
    val fields = order
    val compact = fields.filter { (it !in listOf("状态", "编号", "时间") || position(it) != FieldPosition.Auto) && choose(it, FixedCardLayout.default(type, it)) == FieldPlace.Compact }
    val lines = summaryRows(compact.map { it to "" }, position).size
    val hasDetail = fields.any { choose(it, FixedCardLayout.default(type, it)) == FieldPlace.Detail }
    return SkeletonPlan(choose("编号", FieldPlace.Compact) == FieldPlace.Compact && position("编号") == FieldPosition.Auto, choose("状态", FieldPlace.Compact) == FieldPlace.Compact && position("状态") == FieldPosition.Auto, lines,
        (choose("时间", FieldPlace.Compact) == FieldPlace.Compact && position("时间") == FieldPosition.Auto) || (hasDetail && !buttonPosition.top), hasDetail && buttonPosition.top)
}

/** Shared row grouping keeps placeholders and loaded cards at the same configured density. */
fun isFullWidthCardField(label: String) = label in listOf("资产编码", "资产编号", "旧资产编号", "编号", "单号", "地址", "故障描述", "项目名称", "货位描述")
fun summaryRows(items: List<Pair<String, String>>, position: (String) -> FieldPosition = { FieldPosition.Auto }): List<List<Pair<String, String>>> {
    val rows = mutableListOf<List<Pair<String, String>>>()
    var left: Pair<String, String>? = null
    var right: Pair<String, String>? = null
    fun flush() {
        if (left != null || right != null) rows += listOf(left ?: ("" to ""), right ?: ("" to ""))
        left = null; right = null
    }
    var index = 0
    while (index < items.size) {
        val item = items[index++]
        val slot = position(item.first)
        val next = items.getOrNull(index)
        val nextSlot = next?.let { position(it.first) }
        // Adjacent explicit partners form a row before a preceding half-row can consume one.
        if (next != null && ((slot == FieldPosition.Left && nextSlot == FieldPosition.Right) ||
                    (slot == FieldPosition.Right && nextSlot == FieldPosition.Left &&
                        items.getOrNull(index + 1)?.let { position(it.first) } != FieldPosition.Right))) {
            flush()
            rows += if (slot == FieldPosition.Left) listOf(item, next) else listOf(next, item)
            index++
            continue
        }
        when {
            slot == FieldPosition.Full || (slot == FieldPosition.Auto && isFullWidthCardField(item.first)) -> { flush(); rows += listOf(item) }
            slot == FieldPosition.Left -> { if (left != null) flush(); left = item }
            slot == FieldPosition.Right -> { if (right != null) flush(); right = item }
            else -> { if (left == null) left = item else if (right == null) right = item else { flush(); left = item } }
        }
        if (left != null && right != null) flush()
    }
    flush()
    return rows
}

fun reorderCardFields(fields: List<String>, field: String, target: String, after: Boolean): List<String> {
    if (field == target || field !in fields || target !in fields) return fields
    return fields.toMutableList().apply {
        remove(field)
        add(indexOf(target) + if (after) 1 else 0, field)
    }
}
fun dropCardPosition(fraction: Float): FieldPosition = when {
    fraction < .4f -> FieldPosition.Left
    fraction > .6f -> FieldPosition.Right
    else -> FieldPosition.Full
}

fun dropDetailButtonPosition(x: Float, y: Float): DetailButtonPosition = when {
    y < .5f && x < .5f -> DetailButtonPosition.TopLeft
    y < .5f -> DetailButtonPosition.TopRight
    x < .5f -> DetailButtonPosition.BottomLeft
    else -> DetailButtonPosition.BottomRight
}

fun detailButtonPositionAt(point: androidx.compose.ui.geometry.Offset, compact: androidx.compose.ui.geometry.Rect, card: androidx.compose.ui.geometry.Rect): DetailButtonPosition {
    val area = compact.takeIf { it.width > 0 && it.height > 0 } ?: card
    if (area.width <= 0 || area.height <= 0) return DetailButtonPosition.BottomRight
    return dropDetailButtonPosition((point.x - area.left) / area.width, (point.y - area.top) / area.height)
}

/** Three body rows plus the fixed title. Every cell has a real, visible drop area. */
data class CompactCell(val field: String, val value: String, val slot: Int, val full: Boolean = false)

/** Natural inline widths; only overflowing text shrinks, without imposing two half-width columns. */
fun inlineCardWidths(natural: List<Int>, available: Int): List<Int> {
    val room = available.coerceAtLeast(0)
    if (natural.sum() <= room) return natural
    var low = 0
    var high = natural.maxOrNull() ?: 0
    while (low < high) {
        val cap = (low + high + 1) / 2
        if (natural.sumOf { minOf(it, cap) } <= room) low = cap else high = cap - 1
    }
    return natural.map { minOf(it, low) }
}
data class CompactLayout(val cells: List<CompactCell>, val overflow: List<Pair<String, String>>)
fun compactLayout(items: List<Pair<String, String>>, button: Boolean, buttonSlot: Int = 3,
    slot: (String) -> Int? = { null }, position: (String) -> FieldPosition = { FieldPosition.Auto }): CompactLayout {
    val cells = mutableListOf<CompactCell>()
    val overflow = mutableListOf<Pair<String, String>>()
    val used = mutableSetOf<Int>()
    fun insert(field: String, value: String, at: Int, full: Boolean): Boolean {
        val occupied = if (full) listOf(at / 2 * 2, at / 2 * 2 + 1) else listOf(at)
        if (occupied.any { it in used }) return false
        used.addAll(occupied); cells += CompactCell(field, value, if (full) at / 2 * 2 else at, full)
        return true
    }
    if (button) insert(DETAIL_BUTTON, "", slot(DETAIL_BUTTON) ?: buttonSlot, false)
    // Explicit slots win; displaced fields are kept in expanded details, never silently discarded.
    val ordered = items.distinctBy { it.first }.sortedBy { if (slot(it.first) != null) 0 else 1 }
    ordered.forEach { (field, value) ->
        val explicit = slot(field)
        val full = explicit == null && (position(field) == FieldPosition.Full || (position(field) == FieldPosition.Auto && isFullWidthCardField(field)))
        val choices = if (explicit != null) listOf(explicit) else (0..5).filter {
            when (position(field)) { FieldPosition.Left -> it % 2 == 0; FieldPosition.Right -> it % 2 == 1; else -> true }
        }
        if (choices.none { insert(field, value, it, full) }) overflow += field to value
    }
    return CompactLayout(cells.sortedBy { it.slot }, overflow)
}
fun snapCompactSlot(point: androidx.compose.ui.geometry.Offset, area: androidx.compose.ui.geometry.Rect, margin: Float): Int? {
    if (area.width <= 0 || area.height <= 0 || !area.inflate(margin).contains(point)) return null
    val row = (((point.y - area.top) / area.height) * 3).toInt().coerceIn(0, 2)
    return row * 2 + if (point.x < area.center.x) 0 else 1
}
