package com.personal.tpm.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PrecisionAnalyticsTest {
    private fun row(id: String, code: String = "017", name: String = "017CNC加工中心", grade: String = "A") = Jv.obj(
        "asset" to Jv.obj("assetId" to id, "assetCategory" to Jv.obj("categoryCode" to code, "categoryName" to name)),
        "analysisResult" to "RESULT_$grade", "analysisDate" to "2026-10-22",
    )
    private fun response(vararg rows: Any) = Jv(JSONArray().put(Jv.obj("id" to 453, "preciseAnalysisList" to JSONArray(rows.toList()))))

    @Test fun onlyExactCncCategoryCountsEvenWhenAnotherCategoryHasSameCode() {
        val rows = precisionCncRows(response(row("1"), row("2", name = "017标准件"), row("3", code = "018")))
        assertEquals(1, rows.size)
        assertEquals("1", rows.single()["asset"]["assetId"].text)
    }
    @Test fun malformedEnvelopeMustNotAppearAsZeroSuccess() {
        assertThrows(IllegalArgumentException::class.java) { precisionCncRows(Jv(Jv.obj("message" to "denied"))) }
        assertThrows(IllegalArgumentException::class.java) { precisionCncRows(Jv(JSONArray().put(Jv.obj("id" to 453)))) }
    }
    @Test fun duplicateAssetAndUnknownGradeAreNotSilentlyCounted() {
        assertThrows(IllegalArgumentException::class.java) { precisionCncRows(response(row("1"), row("1", grade = "B"))) }
        assertThrows(IllegalArgumentException::class.java) { precisionCncRows(response(row("1", grade = "UNKNOWN"))) }
    }
    @Test fun queryAllowlistRejectsWritesUploadAndPermissionEndpoints() {
        assertTrue(precisionReadAllowed("precise/getPreciseAnalysis", emptyMap(), precisionAnalysisRequest()))
        for (path in listOf("precise/updatePrecise", "precise/removePreciseById", "workflow/commit", "upload/uploadImage", "security/getMobileLoginUserInfo")) {
            assertFalse(path, precisionReadAllowed(path, emptyMap(), Jv.obj()))
        }
        assertFalse(precisionReadAllowed("precise/getPreciseAnalysis", emptyMap(), null))
        assertFalse(precisionReadAllowed("precise/getPreciseAnalysis", emptyMap(), precisionAnalysisRequest().put("command", "write")))
        assertFalse(precisionReadAllowed("asset/pageAsset", emptyMap(), Jv.obj("limit" to 0, "offset" to 0)))
        for (path in listOf("precise/getPreciseOrdersByPreciseId", "precise/getPreciseRecordsByPreciseId")) {
            assertTrue(precisionReadAllowed(path, mapOf("preciseId" to "5862"), null))
            assertFalse(precisionReadAllowed(path, mapOf("preciseId" to "-1"), null))
            assertFalse(precisionReadAllowed(path, mapOf("preciseId" to "5862"), Jv.obj()))
            assertFalse(precisionReadAllowed(path, mapOf("preciseId" to "5862", "command" to "write"), null))
        }
    }
    @Test fun brandAliasesMergeWithoutDroppingBrandsWithZeroChecks() {
        fun branded(id: String, brand: String) = row(id).also { it.getJSONObject("asset").put("brand", Jv.obj("brandName" to brand)) }
        val rows = precisionCncRows(response(branded("1", "台群"), branded("2", " taIKan "), branded("3", "FANUC"), branded("4", "发那科")))
        val snapshot = PrecisionSnapshot(rows, 10, "", mapOf("台群" to 4, "发那科" to 5, "其他品牌" to 1))
        assertEquals(setOf("台群", "发那科", "其他品牌"), snapshot.brands.keys)
        assertEquals(2, snapshot.brands.getValue("台群").size)
        assertEquals(2, snapshot.brands.getValue("发那科").size)
        assertTrue(snapshot.brands.getValue("其他品牌").isEmpty())
        assertEquals("未知品牌", precisionBrand(" "))
    }
    @Test fun excludedBrandCannotEnterAnalysisOrAssetDenominator() {
        val excluded = row("1").also { it.getJSONObject("asset").put("brand", Jv.obj("brandName" to "钶锐锶")) }
        assertEquals(listOf("2"), precisionCncRows(response(excluded, row("2"))).map { it["asset"]["assetId"].text })
        assertFalse(precisionBrandIncluded(Jv(excluded.getJSONObject("asset"))))
        assertTrue(precisionBrandIncluded(Jv(row("2").getJSONObject("asset"))))
    }
    @Test fun gradeSearchIsExactAndDatesSortNewestFirst() {
        val a = row("1", grade = "A").put("analysisDate", "2026-09-01")
        val aa = row("2", grade = "AA").put("analysisDate", "2026-10-21")
        val latest = row("3", grade = "A").put("analysisDate", "2026-10-22")
        val rows = precisionCncRows(response(a, aa, latest))
        assertEquals(listOf("3", "1"), com.personal.tpm.ui.precisionSearch(rows, "a", null).map { it["asset"]["assetId"].text })
        assertEquals(listOf("2"), com.personal.tpm.ui.precisionSearch(rows, "", "AA").map { it["asset"]["assetId"].text })
        assertEquals(listOf("3", "2", "1"), com.personal.tpm.ui.precisionSearch(rows, "", null).map { it["asset"]["assetId"].text })
    }
}
