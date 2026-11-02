package com.personal.tpm.data

import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class QuickRepairTest {
    private fun todo(id: String, state: String, kind: OrderKind = OrderKind.Fault, suspended: Boolean = false) = Jv(Jv.obj(
        "id" to "task-$id", "businessKey" to id, "processDefinitionKey" to kind.prefix,
        "processInstanceName" to id, "suspended" to suspended, "data" to Jv.obj("faultState" to state)))
    private fun item(state: String = "REPAIRED") = BatchItem("f", OrderKind.Fault, "test", "asset", state)
    private fun node(name: String = "审核", allowed: List<String> = emptyList()) = Jv(Jv.obj(
        "variableName" to "reviewer", "taskName" to name, "assignType" to "ASSIGN", "assignees" to JSONArray(allowed)))
    private fun data(content: String = "first") = Jv.obj("repairId" to "r", "faultType" to Jv.obj("typeId" to "type"),
        "content" to content, "spend" to 0, "startTime" to serverDate(Date(System.currentTimeMillis() - 600_000)),
        "endTime" to serverDate(Date(System.currentTimeMillis() - 60_000)), "albums" to JSONArray().put(Jv.obj("id" to "photo-first")))

    private inner class Api(override val accountName: String = "actor") : BatchApi {
        var group = "REPAIRED"
        var task = "task"
        var repairId = "r"
        var permitted = true
        var saveFails = false
        var commitFails = false
        var nextNodes = listOf(node())
        val writes = mutableListOf<Pair<String, JSONObject>>()
        val offsets = mutableListOf<Int>()
        var pages = listOf(Jv(Jv.obj("last" to true, "content" to JSONArray())))
        override suspend fun page(path: String, offset: Int, filters: List<JSONObject>, limit: Int): Jv {
            offsets += offset
            return pages[(offsets.size - 1).coerceAtMost(pages.lastIndex)]
        }
        override suspend fun request(path: String, query: Map<String, String>, body: Any?): Jv = when(path) {
            OrderKind.Fault.detailPath -> Jv(Jv.obj("faultState" to group, "taskInfo" to Jv.obj("id" to task,
                "category" to group, "businessKey" to "f", "processInstanceId" to "process", "taskDefinitionKey" to "key")))
            "workflow/getWorkflowTaskState" -> Jv(Jv.obj("canSubmit" to permitted))
            "workflow/getFormPropertiesConditionsByTaskId" -> Jv(Jv.obj("flowConditions" to JSONArray().put(Jv.obj("value" to "agree"))))
            "workflow/getAssigneeElementsByProcessCategory" -> Jv(JSONArray(nextNodes.map { it.raw }))
            "fault/getLatestRepairByFaultId" -> Jv(Jv.obj("repairId" to repairId))
            "repair/handleRepair" -> {
                writes += path to JSONObject(body.toString())
                if (saveFails) error("timeout")
                Jv.NULL
            }
            "process/commitTask" -> {
                writes += path to JSONObject(body.toString())
                if (commitFails) error("timeout")
                if (group == "SIGNED") { group = "REPAIRED"; task = "after-sign" }
                Jv.NULL
            }
            else -> error("Unexpected endpoint: $path")
        }
    }

    @Test fun onlyRepairSignAndFinishTasksAreEligible() {
        assertNotNull(quickRepairItem(todo("a", "SIGNED")))
        assertNotNull(quickRepairItem(todo("b", "REPAIRED")))
        for (state in listOf("UNTREATED", "REPORTED", "RECEIVED", "VERIFIED", "RESOLVED")) assertNull(quickRepairItem(todo("x", state)))
        assertNull(quickRepairItem(todo("x", "SIGNED", OrderKind.Erp)))
        assertNull(quickRepairItem(todo("x", "SIGNED", suspended = true)))
    }
    @Test fun discoveryReadsAllPagesAndRemovesDuplicateBusinessIds() = runTest {
        val api = Api()
        api.pages = listOf(
            Jv(Jv.obj("last" to false, "content" to JSONArray(listOf(todo("a", "SIGNED").raw, todo("b", "VERIFIED").raw)))),
            Jv(Jv.obj("last" to true, "content" to JSONArray(listOf(todo("a", "SIGNED").raw, todo("c", "REPAIRED").raw)))))
        assertEquals(listOf("a", "c"), QuickRepairEngine(api).pending().map { it.id })
        assertEquals(listOf(0, 2), api.offsets)
    }
    @Test fun brokenPaginationFailsInsteadOfConfirmingAPartialList() = runTest {
        val api = Api(); api.pages = listOf(Jv(Jv.obj("last" to false, "content" to JSONArray().put(todo("a", "SIGNED").raw))))
        assertTrue(runCatching { QuickRepairEngine(api).pending() }.isFailure)
    }
    @Test fun approverCarryOverRequiresTheSameNodeAndEligibleNames() {
        val n = node()
        assertEquals(listOf("alice"), inheritedQuickApprovers(listOf(n), emptyMap(), mapOf(quickApproverKey(n) to listOf("alice")))["reviewer"])
        assertNull(inheritedQuickApprovers(listOf(node("不同节点")), emptyMap(), mapOf(quickApproverKey(n) to listOf("alice")))["reviewer"])
        val restricted = node(allowed = listOf("bob"))
        assertEquals(listOf("bob"), inheritedQuickApprovers(listOf(restricted), mapOf("reviewer" to listOf("bob")),
            mapOf(quickApproverKey(restricted) to listOf("alice")))["reviewer"])
    }
    @Test fun missingPermissionOrApproverDoesNotWriteAnything() = runTest {
        val api = Api(); api.permitted = false
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), mapOf("reviewer" to listOf("a"))) }.isFailure)
        assertTrue(api.writes.isEmpty())
        api.permitted = true
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), emptyMap()) }.isFailure)
        assertTrue(api.writes.isEmpty())
    }
    @Test fun changedTaskOrRepairRecordCannotSubmitStaleData() = runTest {
        val api = Api(); api.task = "new-task"
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), mapOf("reviewer" to listOf("a"))) }.isFailure)
        assertTrue(api.writes.isEmpty())
        api.task = "task"; api.repairId = "new-repair"
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), mapOf("reviewer" to listOf("a"))) }.isFailure)
        assertTrue(api.writes.isEmpty())
    }
    @Test fun nextApproverIsRecheckedBeforeSavingRepair() = runTest {
        val api = Api(); api.nextNodes = listOf(node(allowed = listOf("bob")))
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), mapOf("reviewer" to listOf("alice"))) }.isFailure)
        assertTrue(api.writes.isEmpty())
    }
    @Test fun frozenPerOrderContentAndPhotosAreSavedBeforeTheWorkflowCommit() = runTest {
        val api = Api("temporary-actor"); val changing = data(); val frozen = changing.toString()
        changing.put("content", "next-order").put("albums", JSONArray())
        QuickRepairEngine(api).submit(item(), "task", frozen, mapOf("reviewer" to listOf("alice")))
        assertEquals(listOf("repair/handleRepair", "process/commitTask"), api.writes.map { it.first })
        assertEquals("first", api.writes[0].second.getString("content"))
        assertEquals("photo-first", api.writes[0].second.getJSONArray("albums").getJSONObject(0).getString("id"))
        assertEquals("alice", api.writes[1].second.getJSONObject("assignees").getString("reviewer"))
        assertEquals("task", api.writes[1].second.getString("taskId"))
    }
    @Test fun saveTimeoutStopsBeforeCommitAndRequiresReview() = runTest {
        val api = Api(); api.saveFails = true
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), mapOf("reviewer" to listOf("a"))) }.exceptionOrNull() is QuickRepairWriteUncertain)
        assertEquals(listOf("repair/handleRepair"), api.writes.map { it.first })
    }
    @Test fun commitTimeoutDoesNotRepeatTheSavedRepair() = runTest {
        val api = Api(); api.commitFails = true
        assertTrue(runCatching { QuickRepairEngine(api).submit(item(), "task", data().toString(), mapOf("reviewer" to listOf("a"))) }.exceptionOrNull() is QuickRepairWriteUncertain)
        assertEquals(2, api.writes.size)
    }
    @Test fun signInOnlyMovesTheSignTaskAndThenBuildsAFinishCandidate() = runTest {
        val api = Api(); api.group = "SIGNED"; api.nextNodes = emptyList()
        assertEquals("REPAIRED", QuickRepairEngine(api).sign(item("SIGNED")).group)
        assertEquals(listOf("process/commitTask"), api.writes.map { it.first })
    }
}
