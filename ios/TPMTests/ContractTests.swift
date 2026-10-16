import Testing
import Foundation
@testable import TPM

@Test func floatingBarPreservesHomeIndicatorClearanceAcrossSafeAreas() {
    for inset in [CGFloat(0), 10, 20, 21, 34, 48] {
        let extensionAmount = bottomBarExtension(safeAreaInset: inset)
        #expect(extensionAmount >= 0)
        #expect(extensionAmount <= 12)
        #expect(inset - extensionAmount >= min(inset, 20))
    }
}

@Test @MainActor func receiverDepartmentAppliesConfiguredDefaultAndClearsPreviousReceiver() {
    let form = OriginalFormModel(kind: "ASSET_FAULT")
    let user = JSONValue.object(["userId": .number(12), "realName": .string("默认受理人")])
    let dept = JSONValue.object(["deptId": .number(7), "acceptUser": user])
    form.selectReceiverDepartment(dept)
    #expect(form.data["dept"] == dept)
    #expect(form.data["user"] == user)
    form.selectReceiverDepartment(.object(["deptId": .number(8)]))
    #expect(form.data["user"] == .null)
}

@Test func productionPoolsUseOriginalReadEndpointsAndStateKeys() {
    #expect(ReadOnlyPoolKind.maintenance.page == "maintain/pageMaintain")
    #expect(ReadOnlyPoolKind.precision.page == "precise/pagePrecise")
    #expect(ReadOnlyPoolKind.precision.stateKey == "preciseState")
    #expect(ReadOnlyPoolKind.maintenance.nameKey == "maintainName")
}


@Test func statusCountUsesServerTotalRatherThanLoadedRows() {
    let page = JSONValue.object(["totalElements": .number(103), "content": .array([.object([:])])])
    #expect(serverPageCount(page) == 103)
    #expect(serverPageCount(.object(["content": .array([])])) == nil)
    #expect(serverPageCount(.object(["totalElements": .number(0)])) == 0)
}

@Test func processRolesDoNotLeakRawExpressions() {
    #expect(workflowParticipantLabel("${initiator}") == "发起人")
    #expect(workflowParticipantLabel("REPAIR_USER") == "维修人员")
    #expect(workflowParticipantLabel("${unknownRule}") == "")
    #expect(workflowParticipantLabel("张三,REPAIR_USER") == "张三、维修人员")
}

@Test func assetUseStateUsesOriginalChineseLabels() {
    #expect(assetDisplayValue(.string("IN_USE"), key: "useState") == "在用")
    #expect(assetDisplayValue(.string("UN_USE"), key: "useState") == "闲置")
    #expect(assetDisplayValue(.string("EWPT-01"), key: "assetCode") == "EWPT-01")
}

@Test func assetRepairSummaryUsesItsKnownAssetCode() {
    let repair = JSONValue.object(["faultName": .string("维修记录")])
    let asset = JSONValue.object(["assetCode": .string("EWPT-01")])
    #expect(assetRepairRecord(repair, asset: asset)["asset"]["assetCode"].text == "EWPT-01")
}

@Test @MainActor func erpKeepsMultipleMaterialLinesAndSaveMode() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    form.addMaterial(.object(["id": .number(11), "onhandQty": .number(8)]))
    form.addMaterial(.object(["id": .number(22), "onhandQty": .number(5)]))
    let payload = form.payload(committed: false)
    #expect(payload["committed"] == .bool(false))
    #expect(payload["erpMaterialOutbounds"]?.array.count == 2)
    #expect(payload["erpMaterialOutbounds"]?.array[1]["id"]["imaterialId"] == .number(22))
    #expect(payload["urgency"] == nil)
}

@Test @MainActor func erpRejectsDuplicateMaterialWithoutLosingExistingLines() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    let material = JSONValue.object(["id": .number(11), "onhandQty": .number(8)])
    form.addMaterial(material); form.addMaterial(material)
    #expect(form.data["erpMaterialOutbounds"]?.array.count == 1)
    #expect(form.error != nil)
}

@Test @MainActor func erpValidatesEachLineAgainstItsOwnStock() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    form.data = ["user": .object(["userId": .number(1)]), "applyDate": .string("2026-10-15"), "projectCode": .string("P-1"), "recipientUser": .object(["userId": .number(1)]), "dept": .object(["deptId": .number(1)]), "division": .object(["divisionId": .number(1)]), "outboundTime": .string("2026-10-15 09:30"), "remark": .string("设备维修")]
    form.addMaterial(.object(["id": .number(11), "onhandQty": .number(2)]))
    form.addMaterial(.object(["id": .number(22), "onhandQty": .number(0.5)]))
    #expect(form.validationError()?.contains("库存数量") == true)
}

@Test func backendJSONPreservesObjectIDsAndNulls() throws {
    let body = Data("{\"material\":{\"id\":12345},\"outboundId\":null,\"committed\":false}".utf8)
    let record = try JSONDecoder().decode(JSONValue.self, from: body)
    let roundTrip = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(record))
    #expect(roundTrip == record)
    #expect(roundTrip["material"]["id"].text == "12345")
}

@Test func missingMaterialUnitUsesPCSAndExistingUnitIsKept() {
    #expect(materialUnit(.object([:])) == "PCS")
    #expect(materialUnit(.object(["uom": .object(["uomDesc": .string("米")])])) == "米")
    #expect(materialQuantity("2", material: .object([:])) == "2 PCS")
}

@Test func faultStagesFollowOriginalWorkflowOrder() {
    #expect(FaultState.allCases.map(\.code) == ["UNTREATED", "REPORTED", "RECEIVED", "SIGNED", "REPAIRED", "VERIFIED", "RESOLVED", "FINISHED"])
    #expect(FaultSearchField.assetCode.key == "asset.assetCode")
    #expect(FaultSearchField.machine.key == "asset.addressCode")
    #expect(FaultSearchField.reporter.key == "applyUser.realName")
}

@Test func returnActionMustBeNamedByOriginalServer() {
    #expect(isReturnCondition(.object(["name": .string("退单"), "value": .string("reject")])))
    #expect(!isReturnCondition(.object(["name": .string("不同意"), "value": .string("reject")])))
}

@Test func textLimitPreservesUnicodeAndOriginalUTF16Limit() {
    #expect(limitedText(String(repeating: "中", count: 65), limit: 64).utf16.count == 64)
    #expect(limitedText("A😀B", limit: 2) == "A")
    #expect(limitedText("A😀B", limit: 3) == "A😀")
}

@Test func filteringHierarchicalChoicesKeepsTheParent() {
    let values = PreviewReferenceData.values(.faultTypes)
    let result = matchingFaultTypes(values, query: "轴承")
    #expect(result.count == 1)
    #expect(result[0]["children"].array.count == 1)
    #expect(result[0]["children"].array[0]["typeName"].text == "轴承磨损")
}

@Test func repairSavePayloadKeepsOnlyOriginalFormFieldsAndAllMaterialDetail() {
    let payload = repairSubmissionPayload(ContractFixtures.repairRecord, repairID: .number(88))
    #expect(Set(payload.keys) == ContractFixtures.repairBodyKeys)
    #expect(payload["assetFault"] == nil)
    #expect(payload["signTime"] == nil)
    #expect(payload["erpRepairMaterials"]?.array.first == ContractFixtures.materialLine)
    var sparse = ContractFixtures.repairRecord
    sparse.removeValue(forKey: "albums"); sparse.removeValue(forKey: "erpRepairMaterials")
    let minimal = repairSubmissionPayload(sparse, repairID: .number(88))
    #expect(minimal["albums"] == nil)
    #expect(minimal["erpRepairMaterials"] == .array([]))
}

@Test func missingNextNodeApproverBlocksSubmission() {
    let nodes = [ContractFixtures.confirmNode]
    #expect(missingAssigneeMessage(nodes: nodes, selections: [:])?.contains("【维修】确认") == true)
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureRepairConfirmUser": []]) != nil)
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureRepairConfirmUser": ["a"]]) == nil)
    var none = ContractFixtures.confirmNode
    none["assignType"] = .string("NONE")
    #expect(missingAssigneeMessage(nodes: [none], selections: [:]) == nil)
}

@Test func countersignNodeNeedsTwoApproversAndJoinsThemWithCommas() {
    let nodes = [ContractFixtures.countersignNode]
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureCountersignUsers": ["a"]]) != nil)
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureCountersignUsers": ["a", "b"]]) == nil)
    #expect(assigneesPayload(nodes: nodes, selections: ["fixtureCountersignUsers": ["a", "b"]])["fixtureCountersignUsers"] == .string("a,b"))
}

@Test func commitPayloadOnlyCarriesApproversForAgree() {
    let agree = commitTaskPayload(task: ContractFixtures.repairTask, condition: "agree", comment: "好",
                                  assignees: assigneesPayload(nodes: [ContractFixtures.confirmNode], selections: ["fixtureRepairConfirmUser": ["zhang"]]))
    #expect(agree["assignees"]?["fixtureRepairConfirmUser"] == .string("zhang"))
    #expect(agree["taskData"] == .null)
    let reject = commitTaskPayload(task: ContractFixtures.repairTask, condition: "reject", comment: "退", assignees: nil)
    #expect(reject["assignees"] == nil)
    #expect(Set(reject.keys) == ["taskId", "processInstanceId", "condition", "comment", "taskData"])
}

@Test func serverErrorSummaryIsOneSafeLine() {
    #expect(serverErrorSummary(Data("{\"message\":\"保存失败\"}".utf8)) == "保存失败")
    #expect(serverErrorSummary(Data("<html>oops</html>".utf8)) == "")
    #expect(serverErrorSummary(Data()) == "")
    #expect(serverErrorSummary(Data("第一行\n第二行 stack".utf8)) == "第一行")
    #expect(!serverErrorSummary(Data("{\"msg\":\"token=abc123 failed\"}".utf8)).contains("abc123"))
    #expect(serverErrorSummary(Data(String(repeating: "长", count: 400).utf8)).count <= 160)
}

@Test func requestLabelShowsMethodAndPathOnly() {
    var request = URLRequest(url: URL(string: "http://example.invalid:8855/rest/repair/handleRepair?taskId=secret")!)
    request.httpMethod = "POST"
    #expect(requestLabel(request) == "POST repair/handleRepair")
}

@Test func employeeNumbersAreKeptAsProcessHandlers() {
    #expect(workflowParticipantLabel("C0070034") == "C0070034")
    #expect(workflowParticipantLabel("IN_USE") == "")
}

@Test func historyShowsWhoHandledEachNodeAndHowLongItTook() {
    let node = JSONValue.object(["activityType": .string("userTask"), "assignee": .string("C0070034"), "condition": .string("抢单"), "endTime": .string("2026-10-09 14:54"), "durationInMillis": .number(133_760_197)])
    #expect(historyAssigneeNames(node) == ["C0070034"])
    #expect(showsConditionTag(node))
    #expect(historyTimePrefix(node) == "完成")
    #expect(historyDuration(133_760_197) == "1天13时9分20.197秒")
    let running = JSONValue.object(["activityType": .string("userTask"), "condition": .string("提交"), "endTime": .null])
    #expect(!showsConditionTag(running))
    #expect(historyTimePrefix(running) == "当前")
    #expect(historyAssigneeNames(.object(["assignee": .string("${initiator}")])) == ["发起人"])
}

@Test func historyListsEveryHandlerOnceAndKeepsOrder() {
    let nodes: [JSONValue] = [
        .object(["activityId": .string("b"), "activityType": .string("userTask"), "assignee": .string("u2"), "endTime": .string("t"), "sortTime": .number(2)]),
        .object(["activityId": .string("a"), "activityType": .string("startEvent"), "assignee": .string("u1"), "endTime": .string("t"), "sortTime": .number(1)]),
        .object(["activityId": .string("c"), "activityType": .string("userTask"), "assignee": .string("u2"), "endTime": .null])
    ]
    #expect(orderedHistory(nodes).map { $0["activityId"].text } == ["a", "b", "c"])
    #expect(historyUserNames(orderedHistory(nodes)) == ["u1", "u2"])
}
