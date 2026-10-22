package com.personal.tpm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTest {
    private fun jv(json: String) = Jv.parse(json)

    @Test fun eachStatusHasItsOwnBatchAction() {
        assertEquals(BatchAction.Accept, BatchAction.forGroup("REPORTED"))
        assertEquals(BatchAction.Claim, BatchAction.forGroup("RECEIVED"))
        assertEquals(BatchAction.SignIn, BatchAction.forGroup("SIGNED"))
        assertEquals(BatchAction.Complete, BatchAction.forGroup("REPAIRED"))
        assertEquals(BatchAction.Approve, BatchAction.forGroup("VERIFIED"))
        assertEquals(BatchAction.Approve, BatchAction.forGroup("ERP:EXECUTING"))
        assertNull(BatchAction.forGroup("FINISHED"))
        assertNull(BatchAction.forGroup("UNTREATED"))
    }

    @Test fun theOtherWayOutIsASendBackOrARejection() {
        assertEquals("退单", BatchAction.SignIn.rejectWord)
        assertEquals("退单", BatchAction.Claim.rejectWord)
        assertEquals("退单", BatchAction.Complete.rejectWord)
        assertEquals("驳回", BatchAction.Accept.rejectWord)
        assertEquals("批量驳回", BatchAction.Approve.rejectTitle)
        assertTrue(BatchAction.Accept.needsPreset && BatchAction.Complete.needsPreset)
        assertFalse(BatchAction.SignIn.needsPreset)
    }

    @Test fun ordersAreToldApartByTheirStatusAcrossKinds() {
        val fault = batchItem(OrderKind.Fault, jv("""{"faultId":"7","faultName":"漏油","faultState":"SIGNED","asset":{"assetCode":"EQ-1"}}"""))
        assertEquals("SIGNED", fault.group)
        assertEquals("7", fault.id)
        assertEquals("EQ-1", fault.reference)
        val erp = batchItem(OrderKind.Erp, jv("""{"outboundId":"3","remark":"领轴承","approvalState":"EXECUTING","outNumber":"CK-3"}"""))
        assertEquals("ERP:EXECUTING", erp.group)
        assertEquals("ERP 审批中", batchGroupName(erp.group))
        assertEquals("待签到", batchGroupName("SIGNED"))
    }

    @Test fun aTodoTaskCarriesItsOrderAndASuspendedOneIsLeftOut() {
        val task = jv("""{"processDefinitionKey":"ASSET_FAULT_1","businessKey":"55","data":{"faultName":"皮带断裂","faultState":"RECEIVED","asset":{"assetCode":"EQ-5"}}}""")
        val item = batchItem(task)
        assertNotNull(item)
        assertEquals("55", item!!.id)
        assertEquals("RECEIVED", item.group)
        assertNull(batchItem(jv("""{"processDefinitionKey":"ASSET_FAULT_1","businessKey":"55","suspended":true,"data":{}}""")))
        assertNull(batchItem(jv("""{"processDefinitionKey":"OTHER","businessKey":"55","data":{}}""")))
    }

    @Test fun anAcceptPresetCanKeepEachOrdersGroupButNeedsADate() {
        val empty = BatchPreset()
        // Group is optional in the shared preset; the processor requires each order's own group.
        assertTrue(empty.isComplete(BatchAction.Accept))
        val group = jv("""{"groupId":"1","groupName":"A 组"}""")
        assertTrue(empty.copy(group = group).isComplete(BatchAction.Accept))
        assertFalse(empty.copy(group = group, estimated = "").isComplete(BatchAction.Accept))
    }

    @Test fun aCompletePresetNeedsTheFaultTypeTheWorkAndAPositiveDuration() {
        val type = jv("""{"typeName":"轴承磨损"}""")
        val ok = BatchPreset(faultType = type, content = "已更换")
        assertTrue(ok.isComplete(BatchAction.Complete))
        assertFalse(ok.copy(content = "  ").isComplete(BatchAction.Complete))
        assertFalse(ok.copy(durationMinutes = 0).isComplete(BatchAction.Complete))
        assertTrue(ok.copy(durationMinutes = 30).isComplete(BatchAction.Complete))
        assertFalse(ok.copy(outboundLines = listOf(BatchOutboundLine(jv("""{"id":{"outboundId":1,"materialId":2}}"""), quantity = 0.0))).isComplete(BatchAction.Complete))
    }

    @Test fun takingMaterialNeedsTheWholeOutboundOrderFilledIn() {
        val base = BatchPreset(faultType = jv("""{"typeName":"x"}"""), content = "c", materials = listOf(BatchMaterial(jv("""{"id":1,"materialDesc":"轴承"}"""))))
        assertFalse(base.isComplete(BatchAction.Complete))
        val filled = base.copy(
            erpProject = jv("""{"projectCode":"P"}"""), erpRemark = "用途", erpRecipient = jv("""{"realName":"a"}"""),
            erpDept = jv("""{"deptName":"d"}"""), erpDivision = jv("""{"divisionDesc":"v"}"""),
        )
        assertTrue(filled.isComplete(BatchAction.Complete))
        assertFalse(filled.copy(materials = listOf(BatchMaterial(jv("""{"id":1}"""), quantity = 0.0))).isComplete(BatchAction.Complete))
    }

    @Test fun aSendBackNeedsOnlyAReason() {
        assertFalse(BatchPreset().isComplete(BatchAction.SignIn, BatchOperation.Reject))
        assertTrue(BatchPreset(comment = "设备已修好").isComplete(BatchAction.SignIn, BatchOperation.Reject))
    }

    @Test fun anApproverNodeMustBeNamedUnlessAnAssignmentEndsTheStep() {
        val node = jv("""{"variableName":"u","assignType":"ASSIGN","taskName":"确认"}""")
        val preset = BatchPreset(group = jv("""{"groupId":"1"}"""), approverNodes = listOf(node))
        assertFalse(preset.isComplete(BatchAction.Accept))
        assertTrue(preset.copy(approvers = mapOf("u" to listOf("a"))).isComplete(BatchAction.Accept))
        // assigning a repairer ends the accept step in "指派", which names no approver
        assertTrue(preset.copy(assignUser = jv("""{"userName":"w"}""")).isComplete(BatchAction.Accept))
    }

    @Test fun theEstimatedTimeDefaultsToTheEndOfToday() {
        assertTrue(defaultEstimatedTime().endsWith("23:59"))
    }

    @Test fun aMissingRightStopsTheRunButAWrongStateDoesNot() {
        assertTrue(isPermissionFailure("提交流程节点失败：请求失败（400）：流程完成监听器执行失败，原因：该用户没有维修人员角色,不能进行抢单"))
        assertTrue(isPermissionFailure("请求失败（403）：Forbidden"))
        assertFalse(isPermissionFailure("单据状态已由「待受理」变为「待抢单」，可能已被他人或你在其他设备上处理。"))
        assertFalse(isPermissionFailure("读取单据失败：网络错误：连接失败"))
    }
}
