package com.personal.tpm.data

import com.personal.tpm.ui.FaultTemplate
import com.personal.tpm.ui.FaultTemplateField
import com.personal.tpm.ui.BatchFaultForm
import com.personal.tpm.ui.BatchSelection
import com.personal.tpm.ui.FormState
import com.personal.tpm.ui.faultTemplateLabel
import com.personal.tpm.ui.migrateFaultPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class BatchFaultReportsTest {
    @Test fun machineVariablesGenerateLiveTitlesAndManualEditsSurviveDrafts() {
        val batch = BatchFaultForm(null, "batch")
        val form = FormState(OrderKind.Fault)
        batch.pattern = FaultTitlePattern("配件维修 【机台号】")
        batch.add(Jv.parse("""{"assetId":1,"assetCode":"A1","addressCode":"AB01"}"""))
        batch.add(Jv.parse("""{"assetId":2,"assetCode":"A2","addressCode":"AB02"}"""))
        batch.pattern = FaultTitlePattern("配件维修 【机台号】 【部位】")
        batch.setOptions("部位", listOf("Y轴护罩", "X轴护罩"))
        batch.setTitleValues(batch.rows[0], mapOf("部位" to "Y轴护罩"))
        batch.setTitleValues(batch.rows[1], mapOf("部位" to "X轴护罩"))
        assertEquals(listOf("配件维修 AB01 Y轴护罩", "配件维修 AB02 X轴护罩"), batch.rows.map { batch.title(it, form) })
        batch.editTitle(batch.rows[0], "AB01 更换Y轴护罩")
        batch.editTitle(batch.rows[1], "")
        val draft = batchReportDraft("batch", form.data, emptyMap(), batch.pattern, batch.rows, "",
            variableOptions = mapOf("部位" to batch.optionsFor("部位")))
        batch.restore(draft, form)
        assertEquals(listOf("Y轴护罩", "X轴护罩"), batch.optionsFor("部位"))
        batch.pattern = FaultTitlePattern("加急 【机台号】 【部位】")
        assertEquals(listOf("AB01 更换Y轴护罩", ""), batch.rows.map { batch.title(it, form) })
        batch.editTitle(batch.rows[1], null)
        assertEquals("加急 AB02 X轴护罩", batch.title(batch.rows[1], form))
    }

    @Test fun partOptionsCanBeCustomizedAndUsedIndependentlyPerMachine() {
        val batch = BatchFaultForm(null, "batch")
        val form = FormState(OrderKind.Fault)
        assertEquals(listOf("X轴左护罩", "X轴右护罩", "Y轴前护罩", "Y轴后护罩", "Z轴护罩"), batch.optionsFor(FaultPartVariable))
        val options = faultVariableOptions(" Y轴前护罩\n\n定制护罩\n定制护罩 ")
        batch.setOptions(FaultPartVariable, options)
        batch.pattern = FaultTitlePattern("【机台号】 【配件项目】")
        batch.add(Jv.parse("""{"assetId":1,"assetCode":"A1","addressCode":"AB01"}"""))
        batch.add(Jv.parse("""{"assetId":2,"assetCode":"A2","addressCode":"AB02"}"""))
        batch.setTitleValues(batch.rows[0], mapOf(FaultPartVariable to options[0]))
        batch.setTitleValues(batch.rows[1], mapOf(FaultPartVariable to options[1]))
        assertEquals(listOf("AB01 Y轴前护罩", "AB02 定制护罩"), batch.rows.map { batch.title(it, form) })
    }

    @Test fun multiplePartsKeepIndependentOrdersThroughDraftRestoreAndTitleReset() {
        val batch = BatchFaultForm(null, "batch")
        val form = FormState(OrderKind.Fault)
        batch.pattern = FaultTitlePattern("【机台号】 【配件项目】 维修")
        val first = Jv.parse("""{"assetId":1,"assetCode":"A1","addressCode":"AB01"}""")
        val second = Jv.parse("""{"assetId":2,"assetCode":"A2","addressCode":"AB02"}""")
        batch.add(first)
        batch.add(second)
        batch.togglePart("1", "X轴前护罩")
        batch.togglePart("1", "Y轴后护罩")
        batch.add(first)
        assertEquals(2, batch.machineCount)
        val ids = batch.rows.map { it.id }
        assertEquals(3, ids.distinct().size)
        assertEquals(listOf("AB01 X轴前护罩 维修", "AB01 Y轴后护罩 维修", "AB02 维修"), batch.rows.map { batch.title(it, form) })
        assertEquals(emptyList<String>(), batch.pattern?.missing(second, emptyMap()))

        batch.editTitle(batch.rows[1], "AB01 单独编辑")
        val draft = batchReportDraft("batch", form.data, emptyMap(), batch.pattern, batch.rows, "")
        batch.restore(draft, form)
        assertEquals(ids, batch.rows.map { it.id })
        assertEquals("AB01 单独编辑", batch.title(batch.rows[1], form))
        batch.editTitle(batch.rows[1], null)
        assertEquals("AB01 Y轴后护罩 维修", batch.title(batch.rows[1], form))
        batch.removeOrder(batch.rows.first())
        assertEquals(listOf(ids[1], ids[2]), batch.rows.map { it.id })
        batch.togglePart("1", "Y轴后护罩")
        assertEquals(listOf("AB01 维修", "AB02 维修"), batch.rows.map { batch.title(it, form) })
    }

    @Test fun submittedPartCannotBeToggledWhileOtherPartsAreEditable() {
        val batch = BatchFaultForm(null, "batch")
        val form = FormState(OrderKind.Fault)
        val asset = Jv.parse("""{"assetId":1,"assetCode":"A1","addressCode":"AB01"}""")
        val submitted = BatchFaultRow(asset, BatchFaultStatus.Submitted, titleValues = mapOf(FaultPartVariable to "X轴前护罩"))
        val pending = BatchFaultRow(asset, id = "second", titleValues = mapOf(FaultPartVariable to "Z轴护罩"))
        batch.restore(batchReportDraft("batch", form.data, emptyMap(), null, listOf(submitted, pending), ""), form)
        batch.togglePart("1", "X轴前护罩")
        assertEquals(listOf(submitted, pending), batch.rows)
        batch.togglePart("1", "Z轴护罩")
        assertEquals(listOf(submitted), batch.rows)
    }

    @Test fun rulesFollowEachMachineAndPreserveCommonFields() {
        val common = Jv.obj("userPhone" to "13500000000", "faultDesc" to "共用说明")
        val ab01 = BatchFaultRow(Jv.parse("""{"assetId":1,"addressCode":"A-B01"}"""), titleValues = mapOf("部位" to "Y轴护罩"))
        val ab02 = BatchFaultRow(Jv.parse("""{"assetId":2,"addressCode":"A-B02"}"""), titleValues = mapOf("部位" to "X轴护罩"))
        val rules = listOf(
            BatchFaultRule("机台号", "AB01", "调整安排", Jv(Jv.obj("faultTime" to "2026-11-13 08:00"))),
            BatchFaultRule("部位", "Y轴护罩", "Y轴方案", Jv(Jv.obj("faultDesc" to "更换Y轴护罩"))),
            BatchFaultRule("部位", "X轴护罩", "X轴方案", Jv(Jv.obj("faultDesc" to "更换X轴护罩"))),
        )
        val draft = batchReportDraft("draft", common, emptyMap(), null, listOf(ab01, ab02), "", rules)
        val restored = Jv(draft.data)["rules"].array.map(BatchFaultRule::read)
        val first = Jv(batchFaultData(common, ab01, "配件维修 AB01 Y轴护罩", restored))
        val second = Jv(batchFaultData(common, ab02, "配件维修 AB02 X轴护罩", restored))
        assertEquals("更换Y轴护罩", first["faultDesc"].text)
        assertEquals("更换X轴护罩", second["faultDesc"].text)
        assertEquals("2026-11-13 08:00", first["faultTime"].text)
        assertEquals("13500000000", second["userPhone"].text)
        assertEquals("共用说明", common.getString("faultDesc"))
    }

    @Test fun mixedStatesCanCreateReportsWithoutEnablingWorkflowActions() {
        val batch = BatchSelection(allowReports = true)
        batch.begin()
        batch.toggle(BatchItem("1", OrderKind.Fault, "维修一", "A1", "FINISHED"))
        batch.toggle(BatchItem("2", OrderKind.Fault, "维修二", "A1", "REPORTED"))
        batch.toggle(BatchItem("3", OrderKind.Fault, "维修三", "A2", "SIGNED"))
        assertEquals(listOf("A1", "A2"), batch.reportCodes)
        assertNull(batch.action)
    }

    @Test fun generatesEachTitleAndDeduplicatesMachineAliases() {
        val pattern = FaultTitlePattern("配件维修 【机台号】 Y轴护罩维修")
        val codes = batchMachineCodes("AB01、ab01， A-B01\nAB03; AB07")
        assertEquals(listOf("AB01", "AB03", "AB07"), codes)
        assertEquals(
            listOf("配件维修 AB01 Y轴护罩维修", "配件维修 AB03 Y轴护罩维修", "配件维修 AB07 Y轴护罩维修"),
            codes.map { pattern.title(Jv(Jv.obj("addressCode" to it))) },
        )
    }

    @Test fun titlePatternRoundTripsWithoutChangingOldTemplateNames() {
        val old = FaultTemplate.decode(Jv.parse("""{"id":"one","name":"旧模板","faultName":"固定标题"}"""))!!
        assertNull(old.titlePattern)
        assertEquals("固定标题", old.value(FaultTemplateField.Name).text)

        val pattern = FaultTitlePattern("配件维修 【机台号】 护罩维修")
        val saved = old.copy(titlePattern = pattern)
        assertEquals(pattern, FaultTemplate.decode(Jv(saved.encode()))?.titlePattern)
        val previous = Jv.parse("""{"id":"legacy","titlePattern":{"prefix":"配件维修","suffix":"护罩维修"}}""")
        assertEquals(pattern, FaultTemplate.decode(previous)?.titlePattern)
        assertEquals("固定标题", FaultTemplate.decode(Jv(saved.encode()))?.singleTitle)
        assertEquals("", FaultTemplate.decode(previous)?.singleTitle)
    }

    @Test fun presetMigrationAddsScreenshotDefaultsWhileKeepingSavedEdits() {
        val custom = FaultTemplate(id = "custom", name = "自定义")
        val savedParts = FaultTemplate(id = "parts-repair", name = "配件维修", values = mapOf(
            FaultTemplateField.RepairGroup to Jv.parse("""{"groupName":"我选的班组","groupId":123}"""),
        ))
        val templates = migrateFaultPresets(listOf(savedParts, custom))
        assertEquals(listOf("配件维修", "厂务维修", "CNC维修部", "自定义"), templates.map { it.name })
        assertEquals("我选的班组", templates[0].value(FaultTemplateField.RepairGroup).label)
        assertEquals("茶山技改维修部-配件维修科", templates[0].value(FaultTemplateField.ReceivingDepartment).faultTemplateLabel())
        assertEquals("吴丽", templates[1].value(FaultTemplateField.RepairReceiver).faultTemplateLabel())
        assertEquals("广东CNC维修部组", templates[2].value(FaultTemplateField.RepairGroup).faultTemplateLabel())
        assertFalse(FaultTemplateField.Phone in templates[0].checked)
    }

    @Test fun selectedApplicantSuppliesTheirOwnPhoneAndDepartment() {
        val form = FormState(OrderKind.Fault)
        form.set("userPhone", "previous")
        form.selectFaultApplicant(Jv.parse("""{"mobile":"13500000001","depts":[{"deptName":"设备维修科"}]}"""))
        assertEquals("13500000001", form["userPhone"].text)
        assertEquals("设备维修科", form["applyDept"].label)
        form.selectFaultApplicant(Jv.parse("""{"realName":"新申请人"}"""))
        assertEquals("", form["userPhone"].text)
    }

    @Test fun interleavesFixedTextAndMultipleValuesWithoutReplacingTextInsideAValue() {
        val pattern = FaultTitlePattern("维修【部位】 护罩/加急 【机台号】-【处理方式】 【资产编码】")
        val asset = Jv.parse("""{"addressCode":"AB03","assetCode":"A-300"}""")
        val values = mapOf("部位" to "Y轴", "处理方式" to "换件【原厂】")
        assertEquals(listOf("部位", "处理方式"), pattern.customVariables)
        assertEquals("维修Y轴 护罩/加急 AB03-换件【原厂】 A-300", pattern.title(asset, values))
        assertEquals(listOf("处理方式"), pattern.missing(asset, values - "处理方式"))
        assertEquals("固定报修", FaultTitlePattern("固定报修").title(asset))
    }

    @Test fun interruptedRequestRestoresAsUnknownAndCannotBeSubmittedAgain() {
        val asset = Jv.parse("""{"assetId":1,"assetCode":"A1","addressCode":"AB01"}""")
        val row = BatchFaultRow(asset, BatchFaultStatus.Checking, "配件维修 AB01", titleValues = mapOf("部位" to "Y轴"))
        val draft = batchReportDraft("batch", Jv.obj("userPhone" to "12345"), emptyMap(), null, listOf(row), "AB02")
        val data = Jv(draft.data)
        val restored = BatchFaultRow.read(data["rows"].array.single())
        assertEquals(BatchFaultStatus.Unknown, restored.status)
        assertFalse(restored.canSubmit)
        assertEquals("配件维修 AB01", restored.title)
        assertEquals(mapOf("部位" to "Y轴"), restored.titleValues)
        assertEquals("AB02", data["pasted"].text)
        assertEquals("12345", data.at("form.userPhone").text)
    }

    @Test fun tablePasteKeepsValuesOnTheirMachinesAndRejectsInvalidPasteAsAWhole() {
        val batch = BatchFaultForm(null, "batch")
        batch.pattern = FaultTitlePattern("【机台号】 护罩维修 【部位】 【方式】")
        batch.add(Jv.parse("""{"assetId":1,"assetCode":"A1","addressCode":"AB01"}"""))
        batch.add(Jv.parse("""{"assetId":2,"assetCode":"A2","addressCode":"AB02"}"""))
        batch.pasteTitleValues("AB02\tZ轴\t换件\nAB01\tY轴\t调整")
        assertEquals(listOf("Y轴", "Z轴"), batch.rows.map { it.titleValues["部位"] })
        assertEquals(listOf("调整", "换件"), batch.rows.map { it.titleValues["方式"] })
        assertThrows(IllegalArgumentException::class.java) {
            batch.pasteTitleValues("AB01\tX轴\t换件\nAB99\tZ轴\t换件")
        }
        assertEquals("Y轴", batch.rows.first().titleValues["部位"])
    }
}
