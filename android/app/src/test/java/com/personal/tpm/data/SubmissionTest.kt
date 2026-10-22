package com.personal.tpm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionTest {
    private fun jv(json: String) = Jv.parse(json)

    @Test fun approversArePickedByTheNodesVariable() {
        val nodes = listOf(
            jv("""{"variableName":"confirmUser","assignType":"ASSIGN","taskName":"【维修】确认"}"""),
            jv("""{"variableName":"team","assignType":"SUPERVISOR","taskName":"会签","countersign":true}"""),
            jv("""{"variableName":"cands","assignType":"CANDIDATE_USERS","assignees":["a","b"]}"""),
        )
        val payload = Jv(assigneesPayload(nodes, mapOf("confirmUser" to listOf("u1"), "team" to listOf("u2", "u3"))))
        assertEquals("u1", payload["confirmUser"].text)
        assertEquals("u2,u3", payload["team"].text)
        assertEquals(2, payload["cands"].array.size)
    }

    @Test fun aMissingApproverIsNamed() {
        val nodes = listOf(jv("""{"variableName":"x","assignType":"ASSIGN","taskName":"确认"}"""), jv("""{"variableName":"y","assignType":"SUPERVISOR","taskName":"会签","countersign":true}"""))
        assertEquals("请选择确认的审批人", missingAssigneeMessage(nodes, emptyMap()))
        assertEquals("会签为会签节点，请至少选择两位审批人", missingAssigneeMessage(nodes, mapOf("x" to listOf("a"), "y" to listOf("b"))))
        assertNull(missingAssigneeMessage(nodes, mapOf("x" to listOf("a"), "y" to listOf("b", "c"))))
    }

    @Test fun commitPayloadCarriesAssigneesOnlyWhenGiven() {
        val task = jv("""{"id":"T1","processInstanceId":"P1"}""")
        val plain = Jv(commitTaskPayload(task, "reject", "备件缺货", null))
        assertEquals("T1", plain["taskId"].text)
        assertEquals("reject", plain["condition"].text)
        assertTrue(plain["assignees"].isNull)
        val withApprovers = Jv(commitTaskPayload(task, "agree", "", assigneesPayload(emptyList(), emptyMap())))
        assertTrue(withApprovers["assignees"].isObject)
    }

    @Test fun conditionsAreRecognisedByName() {
        assertTrue(isReturnCondition(jv("""{"name":"退单","value":"reject"}""")))
        assertTrue(!isReturnCondition(jv("""{"name":"不同意","value":"reject"}""")))
        assertTrue(isRejectCondition(jv("""{"name":"驳回","value":"x"}""")))
        assertTrue(isRejectCondition(jv("""{"name":"不同意","value":"reject"}""")))
        assertTrue(!isRejectCondition(jv("""{"name":"同意","value":"agree"}""")))
    }

    @Test fun usernamesAndTitlesReadTheServersShapes() {
        assertEquals(listOf("a", "b"), usernames(jv("""["a","b"]""")))
        assertEquals(listOf("a", "b"), usernames(jv("""[{"userName":"a"},{"userName":"b"}]""")))
        assertEquals(listOf("a", "b"), usernames(jv("\"[a, b]\"")))
        assertEquals("签到", taskActionTitle(jv("""{"category":"SIGNED","name":"待签到"}"""), OrderKind.Fault))
        assertEquals("出库审批", taskActionTitle(jv("""{"category":"APPROVED"}"""), OrderKind.Erp))
        assertEquals("某节点", taskActionTitle(jv("""{"category":"X","name":"某节点"}"""), OrderKind.Fault))
    }

    @Test fun pinsAreLimitedAndRecentsSkipPinned() {
        fun entry(key: String) = PickerEntry(key, key, Jv.NULL)
        var lists = PickerLists()
        repeat(PickerLists.PIN_LIMIT) { lists = PickerRules.pin(entry("p$it"), lists)!! }
        assertNull(PickerRules.pin(entry("extra"), lists))
        assertNotNull(PickerRules.pin(entry("p3"), lists))          // already pinned: harmless
        assertEquals(PickerLists.PIN_LIMIT - 1, PickerRules.unpin("p1", lists).pins.size)
        var recent = PickerLists()
        for (key in listOf("a", "b", "c", "d", "a")) recent = PickerRules.record(entry(key), recent)
        assertEquals(listOf("a", "d", "c", "b"), recent.recents.map { it.key })
        assertEquals(listOf("d", "c", "b"), PickerRules.shownRecents(recent.copy(pins = listOf(entry("a")))).map { it.key })
        assertEquals(PickerLists.RECENT_SHOWN, PickerRules.shownRecents(recent).size)
    }

    @Test fun todoCardsFollowTheTasksKind() {
        val fault = jv("""{"processDefinitionKey":"ASSET_FAULT_1:2","name":"签到","processInstanceName":"P","data":{"faultName":"换轴承","faultState":"SIGNED"}}""")
        assertEquals(OrderKind.Fault, todoKind(fault))
        assertEquals("换轴承", todoTitle(fault))
        assertEquals("待签到", todoStatus(fault))
        val erp = jv("""{"processDefinitionKey":"ERP_OUTBOUND:1","name":"出库审批","data":{"remark":"领料","approvalState":"APPROVED"}}""")
        assertEquals(OrderKind.Erp, todoKind(erp))
        assertEquals("待审批", todoStatus(erp))
        assertEquals("挂起", todoStatus(jv("""{"suspended":true,"processDefinitionKey":"ASSET_FAULT","data":{}}""")))
    }
}
