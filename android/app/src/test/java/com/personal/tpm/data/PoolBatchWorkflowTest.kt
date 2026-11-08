package com.personal.tpm.data

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoolBatchWorkflowTest {
    private fun item(id: String = "1", state: String = "MAINTAINED") = BatchItem(id, OrderKind.Fault, "保养$id", "EQ$id", "MAINTAIN:$state", "maintain")
    private fun line(id: Int = 2, available: Int = 5) = Jv(Jv.obj("id" to Jv.obj("outboundId" to 8, "materialId" to id),
        "material" to Jv.obj("id" to id, "materialCode" to "M$id", "materialDesc" to "物料$id", "onhandQty" to available),
        "outbound" to Jv.obj("outboundId" to 8, "outNumber" to "OB8"), "actualQuantity" to available, "usedQuantity" to 0))
    private fun template(wanted: List<Jv> = emptyList()) = Jv.obj("orderId" to 10,
        "startTime" to "2026-11-06 08:00", "endTime" to "2026-11-06 09:00",
        "maintainRecords" to JSONArray().put(Jv.obj("executeResult" to "EXECUTED", "maintainResult" to "正常处理",
            "maintainItem" to Jv.obj("itemName" to "清理", "itemType" to "TEXT", "required" to true))),
        "erpMaintainMaterials" to JSONArray(wanted.map { Jv.obj("material" to it["material"].raw, "outbound" to it["outbound"].raw, "materialOutbound" to it.raw, "useNum" to 1) }))
    private fun requisition() = BatchPreset(materials = listOf(BatchMaterial(line()["material"], 2.0)),
        erpData = Jv.obj("user" to Jv.obj("userName" to "test")), erpRecipient = Jv(Jv.obj("userName" to "test")),
        erpDept = Jv(Jv.obj("deptId" to "D")), erpDivision = Jv(Jv.obj("divisionCode" to "DIV")),
        erpProject = Jv(Jv.obj("projectCode" to "P", "projectName" to "保养项目")), erpRemark = "设备保养")

    private inner class Api : BatchApi {
        override val accountName = "test"
        var allowed = true
        var state = "MAINTAINED"
        var conditions = JSONArray().put(Jv.obj("value" to "agree")).put(Jv.obj("value" to "reject", "name" to "退单"))
        var nodes = JSONArray()
        var existing = emptyList<Jv>()
        var outboundLines = emptyList<Jv>()
        var stock = JSONArray()
        var missingSn = false
        var createFails = false
        var omitCreatedId = false
        val writes = mutableListOf<Pair<String, JSONObject>>()
        override suspend fun request(path: String, query: Map<String, String>, body: Any?): Jv = when (path) {
            "maintain/getMaintainDetailById" -> Jv(Jv.obj("sn" to if (missingSn) "" else "MA${query["maintainId"]}", "maintainState" to state,
                "userGroup" to Jv.obj("groupId" to "G"), "taskInfo" to Jv.obj("id" to "T", "businessKey" to query["maintainId"],
                    "category" to state, "processInstanceId" to "P", "taskDefinitionKey" to "S")))
            "workflow/getWorkflowTaskState" -> Jv(Jv.obj("canSubmit" to allowed))
            "workflow/getFormPropertiesConditionsByTaskId" -> Jv(Jv.obj("flowConditions" to conditions))
            "workflow/getAssigneeElementsByProcessCategory" -> Jv(nodes)
            "maintain/getLatestMaintainOrderByMaintainId" -> Jv(Jv.obj("orderId" to 10))
            "maintain/getMaintainRecordsByMaintainId" -> Jv(template()["maintainRecords"])
            "erpOutbound/getErpMaintainMaterialsByMaintainId" -> Jv(JSONArray(existing.map { it.raw }))
            "erpOutbound/pageErpMaterialOutbound" -> Jv(Jv.obj("content" to JSONArray(outboundLines.map { it.raw })))
            "erpOutbound/pageErpMaterial" -> Jv(Jv.obj("content" to stock))
            "erpOutbound/addErpOutbound" -> {
                writes += path to JSONObject(body.toString())
                if (createFails) throw java.io.IOException("connection ended")
                Jv(if (omitCreatedId) JSONObject() else Jv.obj("outboundId" to writes.size, "outNumber" to "NEW${writes.size}"))
            }
            "maintain/handleMaintainOrder", "process/commitTask" -> {
                writes += path to JSONObject(body.toString()); Jv.NULL
            }
            else -> error("Unexpected endpoint $path")
        }
    }
    private suspend fun run(api: Api, selected: BatchItem = item(), action: BatchAction = BatchAction.Complete,
        preset: BatchPreset = BatchPreset(poolTemplate = template().toString()), operation: BatchOperation = BatchOperation.Proceed,
        answer: BatchAsk = { error("Unexpected problem: ${it.message}") }) = BatchProcessor.process(selected, action, operation, preset, api, answer)

    @Test fun requisitionIsLimitedToMaintenanceSignInAndExecutionStages() {
        assertTrue(supportsPoolRequisition(item(state = "SIGNED")))
        assertTrue(supportsPoolRequisition(item()))
        for (state in listOf("REPORTED", "RECEIVED", "VERIFIED", "RESOLVED")) assertFalse(supportsPoolRequisition(item(state = state)))
        assertFalse(supportsPoolRequisition(item().copy(poolPrefix = "precise", group = "PRECISE:PRECISED")))
        assertFalse(requisition().copy(materials = emptyList()).isComplete(BatchAction.Requisition))
        assertFalse(requisition().copy(materials = listOf(BatchMaterial(line()["material"], Double.NaN))).isComplete(BatchAction.Requisition))
    }

    @Test fun requisitionCreatesSeparateOrdersWithEachMaintenanceReferenceAndNeverCompletesWorkflow() = runBlocking {
        val api = Api().apply { stock.put(line()["material"].raw) }
        for (id in listOf("1", "2")) run(api, item(id), BatchAction.Requisition, requisition())
        assertEquals(listOf("erpOutbound/addErpOutbound", "erpOutbound/addErpOutbound"), api.writes.map { it.first })
        assertEquals(listOf("MA1", "MA2"), api.writes.map { it.second.getString("referenceSn") })
        assertTrue(api.writes.all { it.second.getBoolean("committed") })
        assertEquals(2.0, Jv(api.writes.first().second)["erpMaterialOutbounds"].array.single()["entryQuantity"].number, 0.0)
    }

    @Test fun requisitionChecksPermissionStateAndReferenceBeforeCreatingAnything() = runBlocking {
        for (api in listOf(Api().apply { allowed = false }, Api().apply { state = "VERIFIED" }, Api().apply { missingSn = true })) {
            try { run(api, action = BatchAction.Requisition, preset = requisition()); fail() }
            catch (_: IllegalStateException) { }
            assertTrue(api.writes.isEmpty())
        }
    }

    @Test fun requisitionShortageCanSkipOrStopWithoutAnyWrite() = runBlocking {
        val api = Api()
        try { run(api, action = BatchAction.Requisition, preset = requisition(), answer = { BatchChoice.WithoutMaterials }); fail() }
        catch (_: BatchSkipped) { }
        try { run(api, action = BatchAction.Requisition, preset = requisition(), answer = { BatchChoice.Stop }); fail() }
        catch (_: BatchStopped) { }
        assertTrue(api.writes.isEmpty())
    }

    @Test fun uncertainRequisitionIsNeverRetriedAndExplainsHowToCheck() = runBlocking {
        for (api in listOf(Api().apply { createFails = true }, Api().apply { omitCreatedId = true })) {
            api.stock.put(line()["material"].raw)
            try { run(api, action = BatchAction.Requisition, preset = requisition()); fail() }
            catch (e: BatchError) { assertTrue(e.message.orEmpty().contains("勿直接重复领料")) }
            assertEquals(1, api.writes.size)
        }
    }

    @Test fun batchCompletionKeepsExistingMaterialsWhenNewLinkIsUnavailable() = runBlocking {
        val existing = Jv(mergePoolOutboundMaterials(emptyList(), listOf(line(9)))).array
        val api = Api().apply { this.existing = existing }
        var asked = false
        run(api, preset = BatchPreset(poolTemplate = template(listOf(line())).toString()), answer = { asked = true; BatchChoice.WithoutMaterials })
        assertTrue(asked)
        val saved = Jv(api.writes.first().second)["erpMaintainMaterials"].array
        assertEquals("8-9", outboundLineIdentity(saved.single()["materialOutbound"]))
        assertEquals(listOf("maintain/handleMaintainOrder", "process/commitTask"), api.writes.map { it.first })
    }

    @Test fun batchShortagesCanSkipOnlyMissingLinesOrSkipTheEntireOrder() = runBlocking {
        val api = Api().apply { outboundLines = listOf(line(2), line(3, 0)) }
        val preset = BatchPreset(poolTemplate = template(listOf(line(2), line(3))).toString())
        run(api, preset = preset, answer = { assertTrue(it.canSkipShort); BatchChoice.SkipShortages })
        assertEquals("8-2", outboundLineIdentity(Jv(api.writes.first().second)["erpMaintainMaterials"].array.single()["materialOutbound"]))
        val skipped = Api()
        try { run(skipped, preset = preset, answer = { BatchChoice.Skip }); fail() }
        catch (_: BatchSkipped) { }
        assertTrue(skipped.writes.isEmpty())
    }

    @Test fun batchApprovalAndReturnUseServerConditionsAndDoNotSaveExecutionResults() = runBlocking {
        val approve = Api().apply { state = "VERIFIED" }
        run(approve, item(state = "VERIFIED"), BatchAction.Approve, BatchPreset())
        assertEquals(listOf("process/commitTask"), approve.writes.map { it.first })
        val reject = Api()
        run(reject, preset = BatchPreset(comment = "需要重新检查"), operation = BatchOperation.Reject)
        assertEquals("reject", reject.writes.single().second.getString("condition"))
        assertEquals("需要重新检查", reject.writes.single().second.getString("comment"))
    }

    @Test fun acceptClaimAndSignInOnlyCommitTheirOwnWorkflowStep() = runBlocking {
        for ((state, action) in listOf("REPORTED" to BatchAction.Accept, "RECEIVED" to BatchAction.Claim, "SIGNED" to BatchAction.SignIn)) {
            val api = Api().apply { this.state = state }
            run(api, item(state = state), action, BatchPreset())
            val payload = api.writes.single()
            assertEquals("process/commitTask", payload.first)
            assertEquals("agree", payload.second.getString("condition"))
            if (action == BatchAction.Accept) assertEquals("G", JSONObject(payload.second.getString("taskData")).getString("groupId"))
        }
    }

    @Test fun missingRequiredMaintenanceApproverStopsBeforeSaving() = runBlocking {
        val api = Api().apply { nodes.put(Jv.obj("variableName" to "confirm", "assignType" to "ASSIGN", "taskName" to "保养确认")) }
        try { run(api); fail() }
        catch (_: IllegalStateException) { }
        assertTrue(api.writes.isEmpty())
    }
}
