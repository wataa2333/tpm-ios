package com.personal.tpm.ui

import org.junit.Assert.*
import org.junit.Test

class CardPreferencesTest {
    @Test fun inlineFieldsUseNaturalWidthsAndOnlyShrinkWhenNecessary() {
        assertEquals(listOf(210,40),inlineCardWidths(listOf(210,40),340))
        assertEquals(listOf(300,40),inlineCardWidths(listOf(400,40),340))
        assertEquals(listOf(170,170),inlineCardWidths(listOf(400,250),340))
        assertEquals(listOf(0,0),inlineCardWidths(listOf(400,250),0))
    }
    @Test fun machineNumberIsAnIndependentConfigurableFieldWithRealModelValues() {
        listOf(CardType.Fault, CardType.TodoFault, CardType.Asset, CardType.Maintenance, CardType.Precision).forEach { type ->
            assertTrue(cardFields(type).contains("机台号"))
            val model = previewCard(type)
            assertEquals("T21", model.facts.single { it.first == "机台号" }.second)
            val configured = configureCard(model) { field, fallback -> if (field == "机台号") FieldPlace.Compact else fallback }
            assertTrue(configured.summary.contains("机台号" to "T21"))
            assertFalse(configured.facts.any { it.first == "机台号" })
        }
    }
    private val model = CardModel("id", "标题", "待完成", "", false, "编号001", "日期", listOf("资产名称" to "机床", "地址" to "厂房"), null, "id")
    @Test fun projectionDoesNotChangeSourceOrIdentity() {
        val card = configureCard(model) { field, fallback -> when(field) { "编号" -> FieldPlace.Hidden; "时间" -> FieldPlace.Detail; "资产名称" -> FieldPlace.Compact; else -> fallback } }
        assertEquals("", card.reference)
        assertEquals("", card.time)
        assertEquals(listOf("资产名称" to "机床"), card.summary)
        assertTrue(card.facts.contains("时间" to "日期"))
        assertEquals("编号001", model.reference)
        assertEquals(model.openId, card.openId)
    }
    @Test fun hiddenFieldsLeaveOnlyTitleAndNoSkeletonFooter() {
        val card = configureCard(model) { _, _ -> FieldPlace.Hidden }
        val plan = skeletonPlan(CardType.Fault) { _, _ -> FieldPlace.Hidden }
        assertTrue(card.summary.isEmpty() && card.facts.isEmpty())
        assertEquals(SkeletonPlan(false, false, 0, false), plan)
        assertEquals(model.title, card.title)
    }
    @Test fun skeletonUsesSameRowGroupingAsConfiguredFields() {
        val compact = listOf("资产编码", "地址", "资产名称", "报修人", "故障描述")
        val plan = skeletonPlan(CardType.Fault) { field, _ -> if (field in compact) FieldPlace.Compact else FieldPlace.Hidden }
        assertEquals(summaryRows(cardFields(CardType.Fault).filter { it in compact }.map { it to "value" }).size, plan.summaryLines)
        assertEquals(5, plan.summaryLines)
    }
    @Test fun everyCardTypeSupportsIndependentDefaultLayout() {
        CardType.entries.forEach { type ->
            assertEquals(cardFields(type).distinct(), cardFields(type))
            val plan = skeletonPlan(type) { _, fallback -> fallback }
            assertTrue(plan.reference && plan.status && plan.footer)
        }
        assertEquals(3, skeletonPlan(CardType.Stock) { _, fallback -> fallback }.summaryLines)
    }
    @Test fun explicitRightColumnPreservesEmptyLeftCell() {
        val rows = summaryRows(listOf("资产名称" to "机床")) { FieldPosition.Right }
        assertEquals(listOf("" to "", "资产名称" to "机床"), rows.single())
    }
    @Test fun explicitPositionMovesMetadataOutOfFixedSlots() {
        val positions: (String) -> FieldPosition = { if (it == "时间") FieldPosition.Full else FieldPosition.Auto }
        val card = configureCard(model, positions) { _, fallback -> fallback }
        assertEquals("", card.time)
        assertTrue(card.summary.contains("时间" to "日期"))
        val plan = skeletonPlan(CardType.Fault, positions) { field, _ -> if (field == "时间") FieldPlace.Compact else FieldPlace.Hidden }
        assertEquals(SkeletonPlan(false, false, 1, false), plan)
    }
    @Test fun fullWidthIdentifiersDoNotLoseHalfTheirAvailableWidth() {
        val rows = summaryRows(listOf("资产编码" to "EWPT-01-017-082J", "资产名称" to "机床"))
        assertEquals(1, rows.first().size)
        assertEquals(2, rows.size)
    }
    @Test fun droppingBeforeOrAfterReordersWithoutDuplicates() {
        val fields = listOf("编号", "资产编码", "时间")
        assertEquals(listOf("资产编码", "编号", "时间"), reorderCardFields(fields, "资产编码", "编号", false))
        assertEquals(listOf("编号", "时间", "资产编码"), reorderCardFields(fields, "资产编码", "时间", true))
    }
    @Test fun invalidDragTargetsKeepLayout() {
        val fields = listOf("编号", "时间")
        assertEquals(fields, reorderCardFields(fields, "编号", "编号", true))
        assertEquals(fields, reorderCardFields(fields, "编号", "不存在", false))
    }
    @Test fun horizontalDropSelectsLeftFullOrRight() {
        assertEquals(FieldPosition.Left, dropCardPosition(.2f))
        assertEquals(FieldPosition.Full, dropCardPosition(.5f))
        assertEquals(FieldPosition.Right, dropCardPosition(.8f))
    }
    @Test fun detailButtonDropSupportsAllFourCorners() {
        assertEquals(DetailButtonPosition.TopLeft, dropDetailButtonPosition(.1f, .1f))
        assertEquals(DetailButtonPosition.TopRight, dropDetailButtonPosition(.9f, .1f))
        assertEquals(DetailButtonPosition.BottomLeft, dropDetailButtonPosition(.1f, .9f))
        assertEquals(DetailButtonPosition.BottomRight, dropDetailButtonPosition(.9f, .9f))
    }
    @Test fun movingButtonToTopMovesItsSkeletonSpaceOutOfFooter() {
        val plan = skeletonPlan(CardType.Fault, buttonPosition = DetailButtonPosition.TopLeft) { field, fallback -> if (field == "时间") FieldPlace.Hidden else fallback }
        assertTrue(plan.topButton)
        assertFalse(plan.footer)
        val noDetails = skeletonPlan(CardType.Fault, buttonPosition = DetailButtonPosition.TopLeft) { _, _ -> FieldPlace.Hidden }
        assertFalse(noDetails.topButton)
        assertFalse(noDetails.footer)
    }
    @Test fun rightThenLeftShareOneRowInsteadOfLeavingTwoHalfEmptyRows() {
        val items = listOf("受理人" to "张三", "报修人" to "李四")
        val rows = summaryRows(items) { if (it == "受理人") FieldPosition.Right else FieldPosition.Left }
        assertEquals(listOf(listOf("报修人" to "李四", "受理人" to "张三")), rows)
    }
    @Test fun autoFieldFillsAvailableLeftSlotOfRightColumnRow() {
        val rows = summaryRows(listOf("受理人" to "张三", "报修人" to "李四")) { if (it == "受理人") FieldPosition.Right else FieldPosition.Auto }
        assertEquals(1, rows.size)
        assertEquals("报修人", rows[0][0].first)
    }
    @Test fun detailButtonUsesCompactFooterEvenWhenExpandedCardIsTall() {
        val compact = androidx.compose.ui.geometry.Rect(0f, 0f, 200f, 150f)
        val expanded = androidx.compose.ui.geometry.Rect(0f, 0f, 200f, 1000f)
        assertEquals(DetailButtonPosition.BottomRight, detailButtonPositionAt(androidx.compose.ui.geometry.Offset(195f, 145f), compact, expanded))
        assertEquals(DetailButtonPosition.BottomRight, detailButtonPositionAt(androidx.compose.ui.geometry.Offset(210f, 160f), compact, expanded))
    }
    @Test fun previewsUseTheirOwnRecordFactoriesAndDistinctContent() {
        val previews = CardType.entries.map { previewCard(it) }
        assertEquals(CardType.entries.size, previews.map { it.title }.distinct().size)
        CardType.entries.forEach { type -> assertEquals(type, previewCard(type).cardType) }
        assertTrue(previewCard(CardType.Erp).isApproval)
        assertTrue(previewCard(CardType.Erp).reference.startsWith("OB"))
        assertTrue(previewCard(CardType.Stock).status.startsWith("库存"))
        assertEquals("在用", previewCard(CardType.Asset).status)
        assertEquals("待接单", previewCard(CardType.Maintenance).status)
        assertTrue(previewCard(CardType.Precision).facts.contains("单号" to "PR261021001"))
    }
    @Test fun adjacentPartnersCannotBeConsumedByEarlierHalfRow() {
        val rows = summaryRows(listOf("旧字段" to "A", "左字段" to "B", "右字段" to "C")) {
            if (it == "左字段") FieldPosition.Left else FieldPosition.Right
        }
        assertEquals(listOf("左字段" to "B", "右字段" to "C"), rows.last())
        assertEquals(2, rows.size)
    }
    @Test fun pairingMovesFixedMetadataIntoSameBodyRowAndPreservesIdentity() {
        val type = CardType.Fault
        CardPreferences.reset(type)
        try {
            CardPreferences.pair(type, "时间", "编号", true)
            val projected = configureCard(model, { CardPreferences.position(type, it) }) { f, d -> CardPreferences.placement(type, f, d) }
            assertEquals("", projected.reference)
            assertEquals("", projected.time)
            assertEquals(model.openId, projected.openId)
            val order = CardPreferences.orderedFields(type)
            val rows = summaryRows(projected.summary.sortedBy { order.indexOf(it.first) }) { CardPreferences.position(type, it) }
            assertEquals(listOf("编号" to "编号001", "时间" to "日期"), rows.single())
            val skeleton = skeletonPlan(type, { CardPreferences.position(type, it) }, order) { f, d -> CardPreferences.placement(type, f, d) }
            assertEquals(1, skeleton.summaryLines)
            assertFalse(skeleton.reference)
        } finally { CardPreferences.reset(type) }
    }

    @Test fun compactGridCapsThreeBodyRowsAndKeepsOverflow() {
        val items = (1..10).map { "字段$it" to "值$it" }
        val layout = compactLayout(items, true)
        assertEquals(6, layout.cells.size)
        assertEquals(5, layout.overflow.size)
        assertTrue(layout.cells.all { it.slot in 0..5 })
        assertEquals(3, layout.cells.single { it.field == DETAIL_BUTTON }.slot)
    }
    @Test fun snapUsesTheSameSixCellsAndOuterTolerance() {
        val area = androidx.compose.ui.geometry.Rect(10f, 100f, 210f, 400f)
        assertEquals(0, snapCompactSlot(androidx.compose.ui.geometry.Offset(20f, 120f), area, 32f))
        assertEquals(3, snapCompactSlot(androidx.compose.ui.geometry.Offset(200f, 250f), area, 32f))
        assertEquals(5, snapCompactSlot(androidx.compose.ui.geometry.Offset(230f, 420f), area, 32f))
        assertNull(snapCompactSlot(androidx.compose.ui.geometry.Offset(250f, 450f), area, 32f))
    }
    @Test fun fieldCanTakeDefaultButtonSlotWithoutDisappearing() {
        CardPreferences.reset(CardType.Fault)
        try {
            CardPreferences.setSlot(CardType.Fault, "报修人", 3)
            val layout = compactLayout(listOf("报修人" to "张三"), true, slot = { CardPreferences.slot(CardType.Fault, it) })
            assertEquals(3, layout.cells.single { it.field == "报修人" }.slot)
            assertEquals(2, layout.cells.single { it.field == DETAIL_BUTTON }.slot)
        } finally { CardPreferences.reset(CardType.Fault) }
    }

    @Test fun defaultCardHasTwoBodyRowsAndNoRepeatedAssetCode() {
        val card = model.copy(reference = "A001 · T21", facts = listOf("资产编码" to "A001", "资产名称" to "设备"))
        val projected = configureCard(card) { _, default -> default }
        assertFalse(projected.facts.any { it.first == "资产编码" })
        assertTrue(projected.facts.contains("资产名称" to "设备"))
        val grid = compactLayout(listOf("编号" to projected.reference, "时间" to projected.time), true)
        assertEquals(3, grid.cells.maxOf { it.slot })
        val hiddenReference = configureCard(card) { field, default -> if (field == "编号") FieldPlace.Hidden else default }
        assertTrue(hiddenReference.facts.contains("资产编码" to "A001"))
    }

    @Test fun defaultButtonMovesWithoutOverwritingCustomPlacement() {
        CardPreferences.reset(CardType.Fault)
        try {
            assertEquals(3, CardPreferences.detailButtonSlot(CardType.Fault))
            CardPreferences.setSlot(CardType.Fault, DETAIL_BUTTON, 5)
            assertEquals(5, CardPreferences.detailButtonSlot(CardType.Fault))
            CardPreferences.resetDetailButton(CardType.Fault)
            assertEquals(3, CardPreferences.detailButtonSlot(CardType.Fault))
        } finally { CardPreferences.reset(CardType.Fault) }
    }

}
