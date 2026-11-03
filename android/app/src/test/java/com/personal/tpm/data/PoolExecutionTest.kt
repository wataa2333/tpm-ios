package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoolExecutionTest {
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
}
