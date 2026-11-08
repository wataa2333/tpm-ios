package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoolExecutionTest {
    @Test fun saveUsesRegisteredFormFieldsAndOmitsIgnoredAnswers() {
        val data = form("maintain", result = "正常处理")
        data.put("maintain", JSONObject().put("taskInfo", JSONObject().put("id", "workflow")))
        data.put("pendingErpOutboundId", "local-retry-only")
        val row = data.getJSONArray("maintainRecords").getJSONObject(0)
        row.put("recordId", 99).put("executeResult", "IGNORE")
        val payload = poolExecutionPayload(Jv(data), "maintain")
        assertFalse(payload.has("maintain"))
        assertFalse(payload.has("pendingErpOutboundId"))
        val sent = payload.getJSONArray("maintainRecords").getJSONObject(0)
        assertFalse(sent.has("recordId"))
        assertFalse(sent.has("maintainResult"))
        assertTrue(sent.has("maintainItem"))
        assertEquals("IGNORE", sent.getString("executeResult"))
        assertEquals(0, payload.getJSONArray("erpMaintainMaterials").length())
        assertTrue(row.has("maintainResult"))
    }
    private fun form(prefix: String = "precise", type: String = "TEXT", result: String = "", required: Boolean = true): JSONObject =
        JSONObject().put("orderId", 12).put("startTime", "2026-11-03 08:00").put("endTime", "2026-11-03 09:00")
            .put(prefix + "Records", JSONArray().put(JSONObject().put("executeResult", "EXECUTED").put(prefix + "Result", result)
                .put(prefix + "Item", JSONObject().put("itemType", type).put("required", required).put("optionList", JSONArray().put("合格").put("不合格")))))

    @Test fun requiredResultIsCheckedButIgnoredItemsDoNotRequireResults() {
        val data = form()
        assertNotNull(poolExecutionError(Jv(data), "precise"))
        data.getJSONArray("preciseRecords").getJSONObject(0).put("executeResult", "IGNORE")
        assertNull(poolExecutionError(Jv(data), "precise"))
    }
    @Test fun optionContractIsOptionAndMaintenanceAlwaysRequiresItsSelection() {
        assertNull(poolExecutionError(Jv(form(type = "OPTION", result = "合格")), "precise"))
        assertNotNull(poolExecutionError(Jv(form(type = "OPTION", result = "其它")), "precise"))
        assertNotNull(poolExecutionError(Jv(form("maintain", "OPTION", required = false)), "maintain"))
    }
    @Test fun invalidDatesAndReversedRangesAreRejected() {
        val data = form(result = "完成")
        data.put("startTime", "invalid")
        assertNotNull(poolExecutionError(Jv(data), "precise"))
        data.put("startTime", "2026-11-03 10:00")
        assertNotNull(poolExecutionError(Jv(data), "precise"))
    }
    @Test fun numericalResultsAreFiniteAndPreservePrecisionInPayload() {
        val data = form(type = "NUMERICAL", result = "0.020")
        assertNull(poolExecutionError(Jv(data), "precise"))
        assertEquals("0.020", poolExecutionSnapshot(Jv(data)).getJSONArray("preciseRecords").getJSONObject(0).getString("preciseResult"))
        data.getJSONArray("preciseRecords").getJSONObject(0).put("preciseResult", "NaN")
        assertNotNull(poolExecutionError(Jv(data), "precise"))
    }
    @Test fun materialsCannotExceedRemainingAndSnapshotDoesNotMutateServerRecord() {
        val data = form("maintain", result = "完成").put("erpMaintainMaterials", JSONArray().put(JSONObject().put("useNum", 3)
            .put("materialOutbound", JSONObject().put("actualQuantity", 5).put("usedQuantity", 3))))
        assertNotNull(poolExecutionError(Jv(data), "maintain"))
        val copy = poolExecutionSnapshot(Jv(data)); copy.getJSONArray("erpMaintainMaterials").getJSONObject(0).put("useNum", 2)
        assertNull(poolExecutionError(Jv(copy), "maintain"))
        assertEquals(3, data.getJSONArray("erpMaintainMaterials").getJSONObject(0).getInt("useNum"))
    }
    @Test fun editingExistingUsageCanReuseItsOwnReservedQuantity() {
        val data = form("maintain", result = "完成").put("erpMaintainMaterials", JSONArray().put(JSONObject().put("useNum", 3)
            .put("materialOutbound", JSONObject().put("id", JSONObject().put("outboundId", 8).put("materialId", 9))
                .put("actualQuantity", 5).put("usedQuantity", 3))))
        val existing = Jv(poolExecutionSnapshot(Jv(data)))["erpMaintainMaterials"].array
        assertNull(poolExecutionError(Jv(data), "maintain", existing))
        data.getJSONArray("erpMaintainMaterials").getJSONObject(0).put("useNum", 6)
        assertNotNull(poolExecutionError(Jv(data), "maintain", existing))
    }

    private fun outboundLine(outbound: Int, material: Int, actual: Int = 8, used: Int = 0) = Jv(Jv.obj(
        "id" to Jv.obj("outboundId" to outbound, "materialId" to material),
        "material" to Jv.obj("id" to material, "materialCode" to "M$material", "unit" to "PCS"),
        "outbound" to Jv.obj("outboundId" to outbound, "outNumber" to "OB$outbound"),
        "entryQuantity" to 8, "actualQuantity" to actual, "usedQuantity" to used))

    @Test fun importingMultipleLinesKeepsEditedUsageAndSeparatesOutboundOrders() {
        val original = Jv(mergePoolOutboundMaterials(emptyList(), listOf(outboundLine(1, 2)))).array
        (original.single().raw as JSONObject).put("useNum", 3)
        val merged = Jv(mergePoolOutboundMaterials(original, listOf(outboundLine(1, 2), outboundLine(1, 3), outboundLine(2, 2), outboundLine(1, 3)))).array
        assertEquals(3, merged.size)
        assertEquals(3.0, merged.first()["useNum"].number, 0.0)
        assertEquals(setOf("1-2", "1-3", "2-2"), merged.map { outboundLineIdentity(it["materialOutbound"]) }.toSet())
        val payload = poolExecutionPayload(Jv(form("maintain", result = "完成").put("erpMaintainMaterials", JSONArray(merged.map { it.raw }))), "maintain")
        assertEquals(3, payload.getJSONArray("erpMaintainMaterials").length())
        assertEquals("OB2", Jv(payload)["erpMaintainMaterials"].array.last()["outbound"]["outNumber"].text)
    }

    @Test fun invalidImportLeavesPreviouslySelectedMaterialsUntouched() {
        val original = Jv(mergePoolOutboundMaterials(emptyList(), listOf(outboundLine(1, 2)))).array
        val before = original.single().raw.toString()
        try { mergePoolOutboundMaterials(original, listOf(outboundLine(1, 3), Jv(JSONObject()))); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(before, original.single().raw.toString())
        assertEquals(1, original.size)
    }

    @Test fun pendingOutboundCannotCompleteUntilIssuedQuantitiesAreRefreshed() {
        val pending = Jv(mergePoolOutboundMaterials(emptyList(), listOf(outboundLine(1, 2, actual = 0)))).array
        val data = form("maintain", result = "完成").put("erpMaintainMaterials", JSONArray(pending.map { it.raw }))
        assertNotNull(poolExecutionError(Jv(data), "maintain"))
        data.put("erpMaintainMaterials", refreshPoolOutboundMaterials(pending, listOf(outboundLine(1, 2, actual = 8))))
        assertNull(poolExecutionError(Jv(data), "maintain"))
        assertEquals(0.0, pending.single()["materialOutbound"]["actualQuantity"].number, 0.0)
    }

    @Test fun failedImportCannotBeBypassedByRestoringAQuickCompletionDraft() {
        val draft = form("maintain", result = "完成").put("pendingErpOutboundId", "8")
        assertNotNull(poolExecutionError(Jv(poolExecutionSnapshot(Jv(draft))), "maintain"))
        draft.put("pendingErpOutboundId", JSONObject.NULL)
        assertNull(poolExecutionError(Jv(draft), "maintain"))
    }

    @Test fun stockRefreshKeepsAssociationAndUserQuantityAndRevalidatesShortage() {
        val rows = Jv(mergePoolOutboundMaterials(emptyList(), listOf(outboundLine(1, 2)))).array
        (rows.single().raw as JSONObject).put("useNum", 3).put("associationId", "kept")
        val refreshed = refreshPoolOutboundMaterials(rows, listOf(outboundLine(1, 2, actual = 5, used = 3)))
        assertEquals("kept", Jv(refreshed).array.single()["associationId"].text)
        assertEquals(3.0, Jv(refreshed).array.single()["useNum"].number, 0.0)
        assertNotNull(poolExecutionError(Jv(form("maintain", result = "完成").put("erpMaintainMaterials", refreshed)), "maintain"))
        assertEquals(5.0, poolMaterialAvailable(Jv(refreshed).array.single(), Jv(refreshed).array), 0.0)
        try { refreshPoolOutboundMaterials(rows, listOf(outboundLine(2, 2))); fail() }
        catch (_: IllegalStateException) { }
    }
}
