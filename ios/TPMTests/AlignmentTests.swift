import Testing
import Foundation
@testable import TPM

private func returnNode(time: Double, actor: JSONValue = .null, candidates: JSONValue = .null, ended: Bool = true, condition: String = "退单") -> JSONValue {
    .object(["activityType": .string("userTask"), "condition": .string(condition), "sortTime": .number(time),
             "endTime": ended ? .string("2026-10-21 12:00:00") : .null,
             "assignee": actor, "candidateUsers": candidates])
}

@Test func returnActorUsesLatestCompletedReturnOnly() {
    let nodes = [returnNode(time: 1, actor: .string("old")),
                 returnNode(time: 3, actor: .string("pending"), ended: false),
                 returnNode(time: 4, actor: .string("approver"), condition: "通过"),
                 returnNode(time: 2, actor: .string("actual"), candidates: .string("candidate"))]
    #expect(latestReturnActors(nodes) == ["actual"])
}

@Test func missingLatestActorDoesNotBorrowOlderActorOrCandidates() {
    #expect(latestReturnActors([returnNode(time: 1, actor: .string("old")),
                               returnNode(time: 2, candidates: .string("candidate"))]).isEmpty)
    #expect(latestReturnActors([returnNode(time: 1, actor: .string("${initiator}"))]).isEmpty)
}

@Test func returnActorSupportsDateOrderingAndRejectsAmbiguousOrdering() {
    let older = JSONValue.object(["activityType": .string("userTask"), "condition": .string("退单"),
                                  "endTime": .string("2026-10-20 12:00:00"), "assignee": .string("old")])
    let newer = JSONValue.object(["activityType": .string("userTask"), "condition": .string("退单"),
                                  "endTime": .array([.number(2026), .number(10), .number(21), .number(12), .number(0)]),
                                  "assignees": .array([.string("actual"), .string("actual")])])
    #expect(latestReturnActors([newer, older]) == ["actual"])
    #expect(latestReturnActors([returnNode(time: 1, actor: .string("a")), returnNode(time: 1, actor: .string("b"))]).isEmpty)
    let undated = JSONValue.object(["activityType": .string("userTask"), "condition": .string("退单"),
                                    "endTime": .string("unknown"), "assignee": .string("unknown")])
    #expect(latestReturnActors([older, undated]).isEmpty)
}

@Test @MainActor func materialDeselectionPreservesOtherLinesAndQuantities() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    let first = JSONValue.object(["id": .number(1)])
    let second = JSONValue.object(["id": .number(2)])
    form.data["erpMaterialOutbounds"] = .array([
        .object(["material": first, "entryQuantity": .number(8)]),
        .object(["material": second, "entryQuantity": .number(3)])])
    form.addMaterial(first)
    let rows = form.data["erpMaterialOutbounds"]?.array ?? []
    #expect(rows.count == 1)
    #expect(rows.first?["material"] == second)
    #expect(rows.first?["entryQuantity"] == .number(3))
    form.addMaterial(first)
    #expect(form.data["erpMaterialOutbounds"]?.array.count == 2)
}

@Test func repairMaterialCodeHandlesBothOriginalNestingShapes() {
    #expect(repairMaterialCode(.object(["material": .object(["materialCode": .string("A-01")])])) == "A-01")
    #expect(repairMaterialCode(.object(["materialOutbound": .object(["material": .object(["materialCode": .string("B-02")])])])) == "B-02")
    #expect(repairMaterialCode(.null) == "未提供")
}
