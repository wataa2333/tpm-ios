package com.personal.tpm.data

import com.personal.tpm.ui.BatchSelection
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BatchWorkflowTest {
    private fun j(s: String) = Jv.parse(s)
    private val completePreset get() = BatchPreset(faultType = j("""{"typeName":"其他故障"}"""), content = "已维修")
    private fun item(group: String = "REPAIRED") = BatchItem("F-1", OrderKind.Fault, "测试工单", "EQ-1", group)
    private class Fake(var group: String = "REPAIRED") : BatchApi {
        override val accountName = "test-user"
        var allowed = true
        var nodes = Jv.parse("[]")
        var conditions = Jv.parse("""[{"value":"agree","name":"提交任务"},{"value":"back-to-accept","name":"退单"}]""")
        var repairTask = "T-1"
        var existing = Jv.parse("[]")
        var hasGroup = true
        var failCommit = false
        val calls = mutableListOf<String>()
        val writes = mutableMapOf<String, JSONObject>()
        override suspend fun request(path: String, query: Map<String, String>, body: Any?): Jv {
            calls += path
            if (body is JSONObject) writes[path] = body
            if (path == "process/commitTask" && failCommit) throw java.io.IOException("test connection ended")
            return when (path) {
                OrderKind.Fault.detailPath -> Jv.parse("""{"faultState":"$group","taskInfo":{"id":"T-1","category":"$group","processInstanceId":"P-1","taskDefinitionKey":"STEP","businessKey":"F-1"},"userGroup":${if (hasGroup) "{\"groupId\":\"G-1\"}" else "null"},"faultType":{"typeName":"原故障"}}""")
                "workflow/getWorkflowTaskState" -> Jv(JSONObject().put("canSubmit", allowed))
                "workflow/getFormPropertiesConditionsByTaskId" -> Jv(JSONObject().put("flowConditions", conditions.raw))
                "workflow/getAssigneeElementsByProcessCategory" -> nodes
                "fault/getLatestRepairByFaultId" -> Jv.parse("""{"repairId":"R-1","createTime":"2026-01-01 08:00","assetFault":{"taskInfo":{"id":"$repairTask"}},"repairUsers":[],"albums":[{"url":"test-picture"}]}""")
                "erpOutbound/getErpRepairMaterialsByRepairId" -> existing
                "repair/handleRepair", "process/commitTask" -> Jv.parse("{}")
                else -> error("Unexpected request $path")
            }
        }
    }
    private suspend fun process(api: Fake, preset: BatchPreset = completePreset, operation: BatchOperation = BatchOperation.Proceed) =
        BatchProcessor.process(item(api.group), BatchAction.forGroup(api.group)!!, operation, preset, api) { error("Unexpected problem dialog") }
    private suspend fun fails(block: suspend () -> Unit): String {
        try { block(); fail("Expected validation failure") } catch (e: BatchError) { return e.message.orEmpty() }
        return ""
    }
    @Test fun missingApproverCannotSaveRepairOrCreateOutbound() = runTest {
        val api = Fake().apply { nodes = j("""[{"variableName":"confirmUser","assignType":"ASSIGN","taskName":"维修确认"}]""") }
        assertTrue(fails { process(api) }.contains("审批人"))
        assertFalse(api.calls.any { it in listOf("repair/handleRepair", "erpOutbound/addErpOutbound", "process/commitTask") })
        assertFalse(api.calls.contains("fault/getLatestRepairByFaultId"))
    }
    @Test fun permissionAndStaleStateAreRejectedBeforeWrites() = runTest {
        val api = Fake().apply { allowed = false }
        fails { process(api) }
        assertTrue(api.writes.isEmpty())
        val stale = Fake("SIGNED")
        fails { BatchProcessor.process(item(), BatchAction.Complete, BatchOperation.Proceed, completePreset, stale) { BatchChoice.Skip } }
        assertTrue(stale.writes.isEmpty())
    }
    @Test fun successfulCompleteValidatesThenSavesThenCommitsWithApprover() = runTest {
        val api = Fake().apply { nodes = j("""[{"variableName":"confirmUser","assignType":"ASSIGN","taskName":"维修确认"}]""") }
        process(api, completePreset.copy(approvers = mapOf("confirmUser" to listOf("approver-1"))))
        assertTrue(api.calls.indexOf("workflow/getWorkflowTaskState") < api.calls.indexOf("repair/handleRepair"))
        assertTrue(api.calls.indexOf("workflow/getAssigneeElementsByProcessCategory") < api.calls.indexOf("repair/handleRepair"))
        assertTrue(api.calls.indexOf("repair/handleRepair") < api.calls.indexOf("process/commitTask"))
        assertEquals("approver-1", api.writes.getValue("process/commitTask").getJSONObject("assignees").getString("confirmUser"))
        assertEquals("test-picture", api.writes.getValue("repair/handleRepair").getJSONArray("albums").getJSONObject(0).getString("url"))
    }
    @Test fun taskChangedWhileReadingRepairCannotWriteAnything() = runTest {
        val api = Fake().apply { repairTask = "T-other" }
        assertTrue(fails { process(api) }.contains("任务已改变"))
        assertTrue(api.writes.isEmpty())
    }
    @Test fun commitFailureWarnsAboutSavedRepairAndDoesNotRetryWrites() = runTest {
        val api = Fake().apply { failCommit = true }
        val message = fails { process(api) }
        assertTrue(message.contains("维修结果已保存"))
        assertTrue(message.contains("勿重复领料"))
        assertEquals(1, api.calls.count { it == "repair/handleRepair" })
        assertEquals(1, api.calls.count { it == "process/commitTask" })
    }
    @Test fun returnUsesServerConditionAndNeverSavesRepair() = runTest {
        val api = Fake()
        process(api, BatchPreset(comment = "测试原因"), BatchOperation.Reject)
        assertFalse(api.calls.contains("repair/handleRepair"))
        val payload = api.writes.getValue("process/commitTask")
        assertEquals("back-to-accept", payload.getString("condition"))
        assertEquals("测试原因", payload.getString("comment"))
    }
    @Test fun missingPerOrderGroupIsBlockedButSharedGroupAndAppointAreSupported() = runTest {
        val missing = Fake("REPORTED").apply { hasGroup = false }
        fails { process(missing, BatchPreset()) }
        assertTrue(missing.writes.isEmpty())
        val api = Fake("REPORTED").apply {
            hasGroup = false
            conditions = j("""[{"value":"appoint","name":"指派"}]""")
        }
        process(api, BatchPreset(group = j("""{"groupId":"G-2"}"""), assignUser = j("""{"userName":"repairer"}""")))
        val payload = api.writes.getValue("process/commitTask")
        assertEquals("appoint", payload.getString("condition"))
        assertEquals("G-2", JSONObject(payload.getString("taskData")).getString("groupId"))
    }
    @Test fun createdMaterialMappingNeverFallsBackToQuantityOne() {
        val wanted = listOf(j("""{"id":1}"""), j("""{"id":2}"""))
        val a = j("""{"material":{"id":1,"materialCode":"M1"}}""")
        val b = j("""{"material":{"id":2,"materialCode":"M2"}}""")
        val linked = linkCreatedBatchMaterials(listOf(b, a), wanted, listOf(3.0, 8.0))
        assertEquals(8.0, linked[0]["useNum"].number, 0.0)
        assertEquals(3.0, linked[1]["useNum"].number, 0.0)
        for (rows in listOf(emptyList(), listOf(a), listOf(a, a), listOf(a, j("""{"material":{"id":99}}""")))) {
            assertThrows(BatchError::class.java) { linkCreatedBatchMaterials(rows, wanted, listOf(3.0, 8.0)) }
        }
    }
    @Test fun materialCodeSupportsBothEndpointShapes() {
        assertEquals("M1", repairMaterialCode(j("""{"material":{"materialCode":"M1"}}""")))
        assertEquals("M2", repairMaterialCode(j("""{"materialOutbound":{"material":{"materialCode":"M2"}}}""")))
    }
    @Test fun standingDecisionOnlyAppliesToTheSameProblem() {
        val choices = BatchDecisions()
        val shortage = BatchProblem(item(), "物料不够", "test", true)
        choices.remember(shortage, BatchChoice.WithoutMaterials)
        assertEquals(BatchChoice.WithoutMaterials, choices.choice(shortage.copy(message = "next material")))
        assertNull(choices.choice(shortage.copy(title = "创建 ERP 出库单失败", canSkipShort = false)))
    }
    @Test fun sameNumericIdAcrossKindsCannotUnselectAnotherOrder() {
        val selected = BatchSelection().apply { begin() }
        val fault = item("VERIFIED")
        val erp = fault.copy(kind = OrderKind.Erp, group = "ERP:APPROVED")
        selected.toggle(fault)
        selected.toggle(erp)
        assertTrue(selected.contains(fault))
        assertFalse(selected.contains(erp))
        assertEquals(1, selected.items.size)
    }
    @Test fun nonFiniteQuantityIsNotAValidBatchPreset() {
        assertFalse(completePreset.copy(outboundLines = listOf(BatchOutboundLine(j("{}"), Double.NaN))).isComplete(BatchAction.Complete))
    }
}
