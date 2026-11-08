package com.personal.tpm.ui

import com.personal.tpm.data.Jv
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoolExecutionKeysTest {
    @Test fun repeatedPlaceholderIdsCannotDuplicateLazyRowOrChildKeys() {
        // Regression: seven unfinished maintenance records can all have recordId=0.
        val records = List(7) { index -> Jv(JSONObject().put("recordId", 0).put("maintainItem", JSONObject().put("itemName", "项目$index"))) }
        val keys = poolExecutionRecordKeys(records)
        assertEquals(records.size, keys.toSet().size)
        for (columns in 1..4) {
            val rows = poolExecutionRowKeys(keys, columns)
            assertEquals((records.size + columns - 1) / columns, rows.size)
            assertEquals(rows.size, rows.toSet().size)
        }
        assertEquals(7, records.size)
        assertTrue(records.all { it["recordId"].text == "0" })
    }

    @Test fun absentBlankNumericAndRepeatedTextIdsRemainSeparate() {
        val records = listOf(JSONObject(), JSONObject().put("recordId", JSONObject.NULL), JSONObject().put("recordId", ""),
            JSONObject().put("recordId", "0"), JSONObject().put("recordId", 0), JSONObject().put("recordId", "same"),
            JSONObject().put("recordId", "same"), JSONObject().put("recordId", "checks:check:0-0")).map(::Jv)
        val original = records.map { it.toString() }
        val keys = poolExecutionRecordKeys(records)
        assertEquals(records.size, keys.toSet().size)
        assertEquals(4, poolExecutionRowKeys(keys, 2).toSet().size)
        assertEquals(original, records.map { it.toString() })
    }

    @Test fun editingResultsAndRestoringJsonKeepsTheSameIdentities() {
        for (prefix in listOf("maintain", "precise")) {
            val records = List(5) { Jv(JSONObject().put("recordId", 0).put("executeResult", "EXECUTED").put(prefix + "Result", "正常处理")) }
            val keys = poolExecutionRecordKeys(records)
            val updated = records.mapIndexed { index, record -> Jv(JSONObject(record.raw.toString())
                .put("executeResult", if (index == 0) "IGNORE" else "EXECUTED")
                .put(prefix + "Result", "新结果$index").put("description", "备注$index")) }
            assertEquals(keys, poolExecutionRecordKeys(updated))
            assertEquals(poolExecutionRowKeys(keys, 2), poolExecutionRowKeys(poolExecutionRecordKeys(updated), 2))
        }
    }

    @Test fun emptyRecordsProduceNoLazyRows() {
        assertTrue(poolExecutionRecordKeys(emptyList()).isEmpty())
        assertTrue(poolExecutionRowKeys(emptyList(), 2).isEmpty())
    }
}
