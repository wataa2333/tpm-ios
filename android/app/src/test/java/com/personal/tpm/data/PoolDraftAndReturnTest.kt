package com.personal.tpm.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PoolDraftAndReturnTest {
    private fun form(execution: Int = 10) = Jv(Jv.obj("orderId" to execution, "startTime" to "2026-11-06 08:00", "endTime" to "2026-11-06 09:00",
        "maintainRecords" to JSONArray().put(Jv.obj("recordId" to 1, "executeResult" to "EXECUTED", "maintainResult" to "正常处理",
            "maintainItem" to Jv.obj("itemName" to "清理", "itemType" to "TEXT", "guidance" to "最新标准")))))

    @Test fun draftIdentitySeparatesAccountsFactoriesMainOrdersAndExecutions() {
        val id = poolDraftId("userA:factoryA:maintain", "42", "10")
        assertEquals(id, poolDraftId("userA:factoryA:maintain", "42", "10"))
        for (other in listOf(poolDraftId("userB:factoryA:maintain", "42", "10"), poolDraftId("userA:factoryB:maintain", "42", "10"),
            poolDraftId("userA:factoryA:precise", "42", "10"), poolDraftId("userA:factoryA:maintain", "43", "10"), poolDraftId("userA:factoryA:maintain", "42", "11"))) assertNotEquals(id, other)
    }

    @Test fun draftSnapshotKeepsAnswersAndPendingRequisitionWithoutWorkflowOrCredentials() {
        val source = poolExecutionSnapshot(form()).put("token", "must-not-save").put("taskInfo", JSONObject().put("id", "server-task"))
            .put("pendingErpOutboundId", "8").put("content", "已清理")
        val snapshot = poolDraftSnapshot(Jv(source), "maintain")
        assertFalse(snapshot.has("token")); assertFalse(snapshot.has("taskInfo"))
        assertEquals("8", snapshot.getString("pendingErpOutboundId"))
        source.put("content", "changed")
        assertEquals("已清理", snapshot.getString("content"))
    }

    @Test fun restoringDraftKeepsFreshStandardsAndOnlyRestoresAnswers() {
        val saved = poolDraftSnapshot(form(), "maintain")
        val record = saved.getJSONArray("maintainRecords").getJSONObject(0)
        record.put("executeResult", "IGNORE").put("maintainResult", "已清洁").put("description", "等备件")
        record.getJSONObject("maintainItem").put("guidance", "旧标准")
        val restored = Jv(restorePoolDraft(form(), "maintain", Jv(saved)))["maintainRecords"].array.single()
        assertEquals("IGNORE", restored["executeResult"].text)
        assertEquals("已清洁", restored["maintainResult"].text)
        assertEquals("等备件", restored["description"].text)
        assertEquals("最新标准", restored["maintainItem"]["guidance"].text)
        val payload = poolExecutionPayload(Jv(restorePoolDraft(form(), "maintain", Jv(saved))), "maintain")
        assertFalse(Jv(payload)["maintainRecords"].array.single().keys.contains("maintainResult"))
    }

    @Test fun newExecutionsOrChangedRecordIdentitiesCannotRestoreOldAnswers() {
        val saved = poolDraftSnapshot(form(), "maintain")
        assertThrows(IllegalStateException::class.java) { restorePoolDraft(form(11), "maintain", Jv(saved)) }
        saved.getJSONArray("maintainRecords").getJSONObject(0).put("recordId", 999)
        assertThrows(IllegalStateException::class.java) { restorePoolDraft(form(), "maintain", Jv(saved)) }
    }

    @Test fun returnUsesTheExactServerBranchAndNeverFallsBackToAgreement() {
        val agree = Jv(Jv.obj("name" to "同意", "value" to "agree"))
        val back = Jv(Jv.obj("name" to "退单", "value" to "back-to-accept"))
        assertEquals("back-to-accept", initialTaskCondition(listOf(agree, back), true, "back-to-accept"))
        assertEquals("back-to-accept", initialTaskCondition(listOf(agree, back), true))
        assertEquals("", initialTaskCondition(listOf(agree), true, "back-to-accept"))
        assertEquals("", initialTaskCondition(listOf(agree), true))
        assertEquals("", initialTaskCondition(listOf(agree, back), true, "agree"))
        assertEquals("agree", initialTaskCondition(listOf(agree, back), false))
    }

    private fun repeatedIdForm() = Jv(Jv.obj("orderId" to 10, "maintainRecords" to JSONArray(List(3) { index ->
        Jv.obj("recordId" to 0, "executeResult" to "EXECUTED", "maintainResult" to "",
            "maintainItem" to Jv.obj("itemName" to "检查$index", "itemType" to "TEXT", "guidance" to "最新标准"))
    })))

    @Test fun repeatedPlaceholderIdsRestoreEachAnswerAtItsOwnPosition() {
        val fresh = repeatedIdForm()
        val saved = poolDraftSnapshot(fresh, "maintain")
        repeat(3) { index -> saved.getJSONArray("maintainRecords").getJSONObject(index)
            .put("maintainResult", "结果$index").put("description", "备注$index")
            .put("executeResult", if (index == 1) "IGNORE" else "EXECUTED") }
        val restored = Jv(restorePoolDraft(fresh, "maintain", Jv(saved)))["maintainRecords"].array
        assertEquals(listOf("结果0", "结果1", "结果2"), restored.map { it["maintainResult"].text })
        assertEquals(listOf("备注0", "备注1", "备注2"), restored.map { it["description"].text })
        assertEquals("IGNORE", restored[1]["executeResult"].text)
        assertTrue(fresh["maintainRecords"].array.all { it["maintainResult"].text.isEmpty() })
    }

    @Test fun repeatedIdsCannotRestoreWhenStandardsOrOrderChange() {
        val saved = Jv(poolDraftSnapshot(repeatedIdForm(), "maintain"))
        val changed = repeatedIdForm().raw as JSONObject
        changed.getJSONArray("maintainRecords").getJSONObject(1).getJSONObject("maintainItem").put("itemName", "另一检查项目")
        assertThrows(IllegalStateException::class.java) { restorePoolDraft(Jv(changed), "maintain", saved) }
        val reordered = poolExecutionSnapshot(repeatedIdForm())
        val rows = reordered.getJSONArray("maintainRecords")
        val first = rows.get(0); rows.put(0, rows.get(1)); rows.put(1, first)
        assertThrows(IllegalStateException::class.java) { restorePoolDraft(Jv(reordered), "maintain", saved) }
    }

    @Test fun uniqueIdsStillRestoreByIdentityAfterReordering() {
        val fresh = poolExecutionSnapshot(repeatedIdForm())
        repeat(3) { fresh.getJSONArray("maintainRecords").getJSONObject(it).put("recordId", it + 1) }
        val saved = poolDraftSnapshot(Jv(fresh), "maintain")
        repeat(3) { saved.getJSONArray("maintainRecords").getJSONObject(it).put("maintainResult", "结果$it") }
        val rows = fresh.getJSONArray("maintainRecords")
        val first = rows.get(0); rows.put(0, rows.get(2)); rows.put(2, first)
        val restored = Jv(restorePoolDraft(Jv(fresh), "maintain", Jv(saved)))["maintainRecords"].array
        assertEquals(listOf("结果2", "结果1", "结果0"), restored.map { it["maintainResult"].text })
    }
}
