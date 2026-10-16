import Foundation
@testable import TPM

/// Sanitized fixtures: every id, name and variable below is fictional. None of it was
/// copied from the production server, a session or a real work order.
enum ContractFixtures {
    /// 【维修】确认 — next node of the repair task, "用户自选分配" (user picks the approver).
    static let confirmNode: Fields = [
        "taskName": .string("【维修】确认"), "variableName": .string("fixtureRepairConfirmUser"), "assignType": .string("ASSIGN")
    ]
    static let countersignNode: Fields = [
        "taskName": .string("【维修】会签"), "variableName": .string("fixtureCountersignUsers"), "assignType": .string("ASSIGN"),
        "documentation": .string("会签节点")
    ]

    static let materialLine = JSONValue.object([
        "material": .object(["id": .number(1), "materialDesc": .string("深沟球轴承"), "uom": .object(["uomDesc": .string("个")])]),
        "outbound": .object(["outboundId": .number(5), "outNumber": .string("CK-FIXTURE-001")]),
        "materialOutbound": .object(["actualQuantity": .number(3), "usedQuantity": .number(1)]),
        "useNum": .number(2)
    ])

    /// Shape of `fault/getLatestRepairByFaultId` including read-only/related objects the
    /// original form never sends back.
    static let repairRecord: Fields = [
        "repairId": .number(88), "faultType": .object(["typeId": .number(3), "typeName": .string("轴承磨损")]),
        "spend": .number(0), "startTime": .string("2026-10-15 09:30"), "endTime": .string("2026-10-15 10:30"),
        "repairUsers": .array([]), "content": .string("更换轴承"), "albums": .array([]),
        "assetFault": .object(["sn": .string("BX-FIXTURE-1"), "taskInfo": .object(["id": .string("T-FIXTURE")])]),
        "createTime": .array([.number(2026), .number(10), .number(15), .number(9), .number(0)]),
        "signTime": .null, "erpRepairMaterials": .array([materialLine])
    ]

    static let repairTask = JSONValue.object([
        "id": .string("T-FIXTURE"), "processInstanceId": .string("P-FIXTURE"), "taskDefinitionKey": .string("repairTask"), "category": .string("REPAIRED")
    ])

    static let repairBodyKeys: Set<String> = ["repairId", "faultType", "spend", "startTime", "endTime", "repairUsers", "content", "albums", "erpRepairMaterials"]
}
