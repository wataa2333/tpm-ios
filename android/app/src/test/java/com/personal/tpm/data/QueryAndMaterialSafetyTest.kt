package com.personal.tpm.data

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class QueryAndMaterialSafetyTest {
    @Test fun stockSixCapsSixteenWithoutMutatingOriginalOrIncreasingSmallerAmounts() {
        val original = Jv.obj("erpRepairMaterials" to JSONArray(listOf(
            Jv.obj("useNum" to 16, "materialOutbound" to Jv.obj("actualQuantity" to 8,"usedQuantity" to 2)),
            Jv.obj("useNum" to 3, "materialOutbound" to Jv.obj("actualQuantity" to 6,"usedQuantity" to 0)))))
        val capped=Jv(clampRepairQuantities(original))["erpRepairMaterials"].array
        assertEquals(6.0,capped[0]["useNum"].number,0.0)
        assertEquals(3.0,capped[1]["useNum"].number,0.0)
        assertEquals(16.0,Jv(original)["erpRepairMaterials"].array[0]["useNum"].number,0.0)
    }
    @Test fun fallbackOnlyHandlesAuthenticationOrPermissionFailures() {
        assertTrue(queryFailureAllowsFallback(ApiException("expired",401)))
        assertTrue(queryFailureAllowsFallback(ApiException("forbidden",403)))
        assertFalse(queryFailureAllowsFallback(ApiException("server",500)))
        assertFalse(queryFailureAllowsFallback(java.io.IOException("offline")))
        assertFalse(queryFailureAllowsFallback(kotlinx.coroutines.CancellationException()))
        val body=Jv.obj("offset" to 0,"limit" to 500,"filters" to JSONArray())
        assertFalse(precisionReadAllowed("fault/downloadAssetFaultExcel",emptyMap(),body))
        assertTrue(precisionReadAllowed("fault/pageAssetFault",emptyMap(),body))
        assertFalse(precisionReadAllowed("process/commitTask",emptyMap(),JSONObject()))
    }
}
