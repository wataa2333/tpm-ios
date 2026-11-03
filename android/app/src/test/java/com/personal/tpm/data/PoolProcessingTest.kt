package com.personal.tpm.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoolProcessingTest {
    private fun record(id: Int = 22, title: String = "清理", type: String = "TEXT", result: String = "") = Jv(Jv.obj("recordId" to id, "executeResult" to "NOT", "maintainResult" to result,
        "maintainItem" to Jv.obj("itemName" to title, "itemType" to type, "required" to true)))
    private fun template() = Jv.obj("orderId" to 99, "startTime" to "2026-11-03 08:00", "endTime" to "2026-11-03 09:00",
        "maintainRecords" to JSONArray().put(poolDefaultRecord("maintain", record(999))))
    private val item = BatchItem("1", OrderKind.Fault, "保养", "A1", "MAINTAIN:MAINTAINED", "maintain")
    private class Api(val record: Jv) : BatchApi {
        override val accountName = "test"
        var allowed = true
        var category = "MAINTAINED"
        val writes = mutableListOf<String>()
        var saved: JSONObject? = null
        override suspend fun request(path: String, query: Map<String, String>, body: Any?): Jv = when(path) {
            "maintain/getMaintainDetailById" -> Jv(Jv.obj("maintainState" to category, "taskInfo" to Jv.obj("id" to "task1", "businessKey" to "1", "category" to category, "processInstanceId" to "process1", "taskDefinitionKey" to "complete")))
            "workflow/getWorkflowTaskState" -> Jv(Jv.obj("canSubmit" to allowed))
            "workflow/getFormPropertiesConditionsByTaskId" -> Jv(Jv.obj("flowConditions" to JSONArray().put(Jv.obj("value" to "agree"))))
            "workflow/getAssigneeElementsByProcessCategory", "erpOutbound/getErpMaintainMaterialsByMaintainId" -> Jv(JSONArray())
            "maintain/getLatestMaintainOrderByMaintainId" -> Jv(Jv.obj("orderId" to 12))
            "maintain/getMaintainRecordsByMaintainId" -> Jv(JSONArray().put(record.raw))
            "maintain/handleMaintainOrder" -> { writes += path; saved = JSONObject(body.toString()); Jv.NULL }
            "process/commitTask" -> { writes += path; if (category == "SIGNED") category = "MAINTAINED"; Jv.NULL }
            else -> error("Unexpected endpoint $path")
        }
    }
    @Test fun textDefaultsDoNotOverwriteResultsOrInventNumbersOrOptions() {
        assertEquals("正常处理", Jv(poolDefaultRecord("maintain", record()))["maintainResult"].text)
        assertEquals("已检修", Jv(poolDefaultRecord("maintain", record(result = "已检修")))["maintainResult"].text)
        for (type in listOf("NUMERICAL", "OPTION")) assertEquals("", Jv(poolDefaultRecord("maintain", record(type = type)))["maintainResult"].text)
    }
    @Test fun batchCopiesAnswersAndKeepsEachExecutionsIdentity() = runBlocking {
        val api = Api(record())
        PoolProcessingEngine(api).process(item, BatchAction.Complete, BatchOperation.Proceed, BatchPreset(poolTemplate = template().toString()))
        assertEquals(listOf("maintain/handleMaintainOrder", "process/commitTask"), api.writes)
        assertEquals("12", Jv(api.saved)["orderId"].text)
        assertTrue(Jv(api.saved)["maintainRecords"].array.single()["recordId"].isNull)
        assertEquals("正常处理", Jv(api.saved)["maintainRecords"].array.single()["maintainResult"].text)
    }
    @Test fun incompatibleStandardsFailBeforeAnyWrite() = runBlocking {
        val api = Api(record(title = "不同项目"))
        try { PoolProcessingEngine(api).process(item, BatchAction.Complete, BatchOperation.Proceed, BatchPreset(poolTemplate = template().toString())); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(api.writes.isEmpty())
    }
    @Test fun permissionLossAndStaleTaskFailBeforeSaving() = runBlocking {
        val api = Api(record()); api.allowed = false
        try { PoolProcessingEngine(api).submit(item, "task1", template().toString(), emptyMap()) { }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(api.writes.isEmpty()); api.allowed = true
        try { PoolProcessingEngine(api).submit(item, "oldTask", template().toString(), emptyMap()) { }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(api.writes.isEmpty())
    }
    @Test fun maintenanceAndRepairCanShareNumericIdsWithoutColliding() {
        assertNotEquals(item.key, item.copy(poolPrefix = "").key)
        assertEquals(BatchAction.Complete, BatchAction.forGroup("MAINTAIN:MAINTAINED"))
        assertEquals(BatchAction.Complete, BatchAction.forGroup("PRECISE:PRECISED"))
        assertEquals(BatchAction.SignIn, BatchAction.forGroup("MAINTAIN:SIGNED"))
    }
    @Test fun quickSignInOnlyCommitsSignInAndReturnsAnExecutionItem() = runBlocking {
        val api = Api(record()); api.category = "SIGNED"
        val result = QuickRepairEngine(api).sign(item.copy(group = "MAINTAIN:SIGNED"))
        assertEquals("MAINTAIN:MAINTAINED", result.group)
        assertEquals(listOf("process/commitTask"), api.writes)
        assertNull(api.saved)
    }
    @Test fun quickDiscoveryIncludesThreeKindsAndExcludesApprovalWithSharedIds() = runBlocking {
        fun todo(key: String, prefix: String, stateKey: String, state: String) = Jv.obj("id" to key, "businessKey" to "1", "processDefinitionKey" to prefix,
            "data" to Jv.obj(stateKey to state))
        val rows = JSONArray().put(todo("r", "ASSET_FAULT", "faultState", "REPAIRED"))
            .put(todo("m", "MAINTAIN", "maintainState", "MAINTAINED"))
            .put(todo("p", "PRECISE", "preciseState", "SIGNED"))
            .put(todo("a", "MAINTAIN", "maintainState", "VERIFIED"))
        val api = object : BatchApi {
            override val accountName = "test"
            override suspend fun request(path: String, query: Map<String, String>, body: Any?): Jv {
                assertEquals("process/pageUserTodoTask", path)
                return Jv(Jv.obj("content" to rows, "last" to true))
            }
        }
        val pending = QuickRepairEngine(api).pending()
        assertEquals(3, pending.size)
        assertEquals(3, pending.map { it.key }.toSet().size)
        assertEquals(setOf("", "maintain", "precise"), pending.map { it.poolPrefix }.toSet())
    }
}
