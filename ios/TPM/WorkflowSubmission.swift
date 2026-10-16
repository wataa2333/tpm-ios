import Foundation

// Pure request builders for the two-step "complete repair task" flow, kept free of
// SwiftUI state so the contract with the original client can be unit tested.

/// Form items the original client registers on its uni-forms instance. Its
/// `validate()` resolves only these values (plus the two additions below), not the
/// whole repair record that was loaded from `fault/getLatestRepairByFaultId`.
let repairSubmissionKeys = ["faultType", "spend", "startTime", "endTime", "repairUsers", "content", "albums"]

/// Body of `POST repair/handleRepair`: the registered form values, then `repairId`
/// and the unchanged `erpRepairMaterials` array (every material/outbound object kept).
func repairSubmissionPayload(_ data: Fields, repairID: JSONValue) -> Fields {
    var payload: Fields = [:]
    for key in repairSubmissionKeys {
        if let value = data[key] { payload[key] = value }
    }
    payload["repairId"] = repairID
    payload["erpRepairMaterials"] = data["erpRepairMaterials"] ?? .array([])
    return payload
}

/// `assignees` for workflow submissions, built like the original
/// assignees-setting component: ASSIGN/SUPERVISOR nodes map their variable to the
/// chosen user name (countersign nodes: comma separated), CANDIDATE_USERS pass through.
func assigneesPayload(nodes: [Fields], selections: [String: [String]]) -> Fields {
    var assignments: Fields = [:]
    for node in nodes {
        let key = node.text("variableName"), names = selections[key, default: []]
        if ["ASSIGN", "SUPERVISOR"].contains(node.text("assignType")) {
            assignments[key] = .string(isCountersign(node) ? names.joined(separator: ",") : names.first ?? "")
        } else if node.text("assignType") == "CANDIDATE_USERS" {
            assignments[key] = node["assignees"] ?? .array(names.map(JSONValue.string))
        }
    }
    return assignments
}

/// Message for the first next-node approver that is still missing, or nil when every
/// required node has someone. A failed approver lookup must never be treated as "none needed".
func missingAssigneeMessage(nodes: [Fields], selections: [String: [String]]) -> String? {
    for node in nodes where ["ASSIGN", "SUPERVISOR"].contains(node.text("assignType")) {
        let count = selections[node.text("variableName"), default: []].count
        let name = node.text("taskName").isEmpty ? "下一节点" : node.text("taskName")
        if count == 0 { return "请选择\(name)的审批人" }
        if isCountersign(node) && count < 2 { return "\(name)为会签节点，请至少选择两位审批人" }
    }
    return nil
}

/// Body of `POST process/commitTask`. `assignees` is only sent for the "agree" condition.
func commitTaskPayload(task: JSONValue, condition: String, comment: String, assignees: Fields?, taskData: JSONValue = .null) -> Fields {
    var payload: Fields = [
        "taskId": task["id"],
        "processInstanceId": task["processInstanceId"],
        "condition": .string(condition),
        "comment": .string(comment),
        "taskData": taskData
    ]
    if let assignees { payload["assignees"] = .object(assignees) }
    return payload
}
