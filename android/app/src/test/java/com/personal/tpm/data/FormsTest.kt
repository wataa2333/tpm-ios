package com.personal.tpm.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class FormsTest {
    private fun jv(json: String) = Jv.parse(json)
    private val approver = jv("""{"variableName":"confirmUser","assignType":"ASSIGN","taskName":"【维修】确认"}""")

    private fun fullFault() = """{"faultName":"主轴异响","asset":{"assetName":"加工中心"},"faultTime":"2026-10-17 09:30","applyUser":{"realName":"张三"},"applyDept":{"deptName":"设备部"},
        "dept":{"deptName":"维修科"},"user":{"realName":"李四"},"userPhone":"13800000000","userGroup":{"groupName":"A 组"},"division":{"divisionDesc":"总厂"}}"""

    @Test fun aCompleteFaultFormPassesAndAMissingFieldIsNamed() {
        assertNull(FormRules.validate(OrderKind.Fault, jv(fullFault()), emptyList(), emptyMap()))
        val noPhone = jv(fullFault().replace("\"userPhone\":\"13800000000\"", "\"userPhone\":\"\""))
        assertEquals("请填写或选择报修人联系电话", FormRules.validate(OrderKind.Fault, noPhone, emptyList(), emptyMap()))
        val noAsset = jv(fullFault().replace("\"asset\":{\"assetName\":\"加工中心\"}", "\"asset\":{}"))
        assertEquals("请填写或选择报修资产", FormRules.validate(OrderKind.Fault, noAsset, emptyList(), emptyMap()))
    }

    @Test fun anApproverStepMustBeFilledBeforeSubmitting() {
        assertEquals("请选择【维修】确认审批人", FormRules.validate(OrderKind.Fault, jv(fullFault()), listOf(approver), emptyMap()))
        assertNull(FormRules.validate(OrderKind.Fault, jv(fullFault()), listOf(approver), mapOf("confirmUser" to listOf("u1"))))
    }

    @Test fun anErpOrderNeedsMaterialsAndNotMoreThanIsInStock() {
        val base = """"user":{"realName":"张三"},"applyDate":"2026-10-17","projectCode":"P1","recipientUser":{"realName":"李四"},"dept":{"deptName":"维修科"},"division":{"divisionDesc":"总厂"},"outboundTime":"2026-10-17 10:00","remark":"换轴承""""
        assertEquals("请选择出库物料", FormRules.validate(OrderKind.Erp, jv("{$base,\"erpMaterialOutbounds\":[]}"), emptyList(), emptyMap()))
        val tooMany = jv("{$base,\"erpMaterialOutbounds\":[{\"material\":{\"materialDesc\":\"轴承\",\"onhandQty\":3},\"entryQuantity\":5}]}")
        assertEquals("轴承：申请数量不能大于库存数量", FormRules.validate(OrderKind.Erp, tooMany, emptyList(), emptyMap()))
        val fine = jv("{$base,\"erpMaterialOutbounds\":[{\"material\":{\"materialDesc\":\"轴承\",\"onhandQty\":3},\"entryQuantity\":2}]}")
        assertNull(FormRules.validate(OrderKind.Erp, fine, emptyList(), emptyMap()))
    }

    @Test fun thePayloadCarriesCommittedAssigneesAndReadsAZeroQuantityAsOne() {
        val data = JSONObject("""{"remark":"x","erpMaterialOutbounds":[{"material":{"id":1},"entryQuantity":0}]}""")
        val payload = Jv(FormRules.payload(OrderKind.Erp, data, listOf(approver), mapOf("confirmUser" to listOf("u1")), committed = true))
        assertTrue(payload["committed"].bool)
        assertEquals("u1", payload["assignees"]["confirmUser"].text)
        assertEquals(1.0, payload["erpMaterialOutbounds"].array[0]["entryQuantity"].number, 0.0)
        // the form's own data is not changed by building the payload
        assertEquals(0, data.getJSONArray("erpMaterialOutbounds").getJSONObject(0).getInt("entryQuantity"))
    }

    @Test fun aNewReportCountsAsFilledOnlyWhenThePersonTypedOrChoseSomething() {
        assertFalse(FormRules.faultHasContent(jv("""{"faultTime":"2026-10-17 09:30","applyUser":{"realName":"张三"},"userPhone":"1","albums":[]}""")))
        assertTrue(FormRules.faultHasContent(jv("""{"faultName":"主轴异响"}""")))
        assertTrue(FormRules.faultHasContent(jv("""{"asset":{"assetName":"加工中心"}}""")))
        assertTrue(FormRules.faultHasContent(jv("""{"albums":[{"name":"a.jpg"}]}""")))
    }

    @Test fun datesAreReadFromTextAndFromServerArrays() {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        assertEquals("2026-10-17 09:30:00", format.format(parsedDate(jv("\"2026-10-17 09:30\""))))
        assertEquals("2026-10-17 00:00:00", format.format(parsedDate(jv("\"2026-10-17\""))))
        assertEquals("2026-10-17 09:30:15", format.format(parsedDate(jv("[2026,10,17,9,30,15]"))))
    }

    @Test fun theRepairResultIsCheckedLikeTheOriginalClient() {
        val ok = """"faultType":{"typeName":"轴承磨损"},"content":"已更换","spend":10,"startTime":"2020-01-01 08:00","endTime":"2020-01-01 09:00""""
        assertNull(repairValidationError(jv("{$ok}")))
        assertEquals("请选择故障类型", repairValidationError(jv("""{"content":"x","startTime":"2020-01-01 08:00","endTime":"2020-01-01 09:00"}""")))
        assertEquals("请输入说明", repairValidationError(jv(ok.replace("已更换", "  ").let { "{$it}" })))
        assertEquals("开始时间不能晚于结束时间", repairValidationError(jv(ok.replace("2020-01-01 08:00", "2020-01-01 10:00").let { "{$it}" })))
        assertEquals("维修时间不能晚于当前时间", repairValidationError(jv(ok.replace("2020-01-01 09:00", "2999-01-01 09:00").let { "{$it}" })))
        val badLine = """{$ok,"erpRepairMaterials":[{"useNum":5,"materialOutbound":{"actualQuantity":3,"usedQuantity":1}}]}"""
        assertEquals("使用数量超出可用范围", repairValidationError(jv(badLine)))
    }

    @Test fun theRepairBodyHasOnlyTheRegisteredFieldsPlusTheIdAndTheLines() {
        val data = JSONObject("""{"faultType":{"typeName":"x"},"content":"c","spend":1,"startTime":"s","endTime":"e","repairUsers":[],"albums":[],"assetFault":{"sn":"BX1"},"sign":"drop me","erpRepairMaterials":[{"useNum":1}]}""")
        val body = repairPayload(data, 42)
        assertEquals(42, body.getInt("repairId"))
        assertFalse(body.has("assetFault"))
        assertFalse(body.has("sign"))
        assertEquals(1, body.getJSONArray("erpRepairMaterials").length())
        assertTrue(body.has("faultType") && body.has("content") && body.has("albums"))
    }

    @Test fun aDraftSurvivesItsJsonAndIsOfferedOnlyToItsOwnOrder() {
        val data = JSONObject("""{"faultName":"主轴异响","asset":{"assetName":"加工中心"}}""")
        val draft = FormDraft(data = data, selections = mapOf("confirmUser" to listOf("u1")), orderId = "F9", orderSn = "BX-9")
        val back = FormDraft.fromJson(JSONObject(draft.toJson().toString()))
        assertEquals(draft.id, back.id)
        assertEquals("F9", back.orderId)
        assertEquals(listOf("u1"), back.selections["confirmUser"])
        assertEquals("单号 BX-9 · 主轴异响", back.subtitle)
        assertTrue(DraftTarget.Order("F9").matches(back))
        assertFalse(DraftTarget.Order("F1").matches(back))
        assertFalse(DraftTarget.NewReport.matches(back))
        assertTrue(DraftTarget.NewReport.matches(FormDraft(data = data)))
        assertEquals("还没有填写内容", FormDraft(data = JSONObject()).subtitle)
    }

    @Test fun anApproverTheProcessNamesItselfHasNoDefault() {
        assertTrue(allowsApproverDefault(approver))
        assertFalse(allowsApproverDefault(jv("""{"variableName":"x","assignType":"ASSIGN","assignees":["boss"]}""")))
    }

    @Test fun referencesAreKeyedByTheFieldTheServerUses() {
        assertEquals("u1", referenceKey(RefSource.Users, jv("""{"userName":"u1","userId":9}""")))
        assertEquals("7", referenceKey(RefSource.Departments, jv("""{"value":7,"label":"设备部"}""")))
        assertEquals("P-1", referenceKey(RefSource.Projects, jv("""{"projectCode":"P-1"}""")))
        assertEquals("5-8", referenceKey(RefSource.OutboundMaterials, jv("""{"id":{"outboundId":5,"materialId":8}}""")))
        assertEquals(listOf(PickerSearch.Name, PickerSearch.Code), searchModes(RefSource.Materials))
        assertEquals("outbound.recipientUser.realName", PickerSearch.Recipient.field(RefSource.OutboundMaterials))
    }
}
