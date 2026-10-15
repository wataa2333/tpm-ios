import Testing
import Foundation
@testable import TPM

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
