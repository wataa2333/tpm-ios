import Testing
import Foundation
@testable import TPM

@Test func marqueePausesAtBothEndsAndStaysInsideTheField() {
    #expect(marqueeOffset(elapsed: 0, distance: 52) == 0)
    #expect(marqueeOffset(elapsed: 1, distance: 52) == 0)
    #expect(abs(marqueeOffset(elapsed: 2.4, distance: 52) - 26) < 0.001)
    #expect(marqueeOffset(elapsed: 4, distance: 52) == 52)
    #expect(marqueeOffset(elapsed: 100, distance: 0) == 0)
    for tick in 0...1000 {
        let offset = marqueeOffset(elapsed: Double(tick) / 10, distance: 52)
        #expect(offset >= 0 && offset <= 52)
    }
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

@Test func loadingUsesWindowCenterRatherThanInsetListCenter() {
    let center = loadingIndicatorCenter(container: CGRect(x: 0, y: 200, width: 393, height: 600), viewport: CGRect(x: 0, y: 60, width: 393, height: 760))
    #expect(center.y == 240)
    #expect(center.x == 196.5)
    let sheet = loadingIndicatorCenter(container: CGRect(x: 0, y: 550, width: 393, height: 260), viewport: CGRect(x: 0, y: 60, width: 393, height: 760))
    #expect(sheet.y == 130)
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
