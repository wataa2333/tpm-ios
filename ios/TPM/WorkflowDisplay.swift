import Foundation
import SwiftUI

/// The verb on a task's action button and page title. The server's node name ("待抢单",
/// "待签到") names a *state*; the button must say what pressing it does. Unknown categories
/// fall back to the node name, so nothing is invented for flows this app does not know.
func taskActionTitle(_ task: JSONValue, kind: WorkflowKind) -> String {
    let named = task["name"].text
    let verbs: [String: String]
    if kind == .fault {
        verbs = ["UNTREATED": "提交报修", "SUBMIT": "提交报修", "REPORTED": "受理派单", "RECEIVED": "抢单", "SIGNED": "签到",
                 "REPAIRED": "完成任务", "VERIFIED": "确认完成", "RESOLVED": "复线确认"]
    } else {
        verbs = ["SUBMIT": "提交申请", "APPROVED": "出库审批", "EXECUTING": "出库", "CONFIRM": "出库确认"]
    }
    return verbs[task["category"].text] ?? (named.isEmpty ? "处理" : named)
}

func faultDisplayColor(_ code: String) -> Color {
    FaultState.allCases.first { $0.code == code || $0.rawValue == code }?.color ?? .gray
}

/// ERP approval states use the same palette logic as repair states: one distinct system hue each.
func approvalDisplayColor(_ code: String) -> Color {
    switch code {
    case "PENDING", "APPROVED": .orange
    case "EXECUTING": .purple
    case "CONFIRM": .teal
    case "REFUSE": .red
    case "AGREE", "FINISHED": .green
    case "SUBMIT": .gray
    default: .gray
    }
}

func serverPageCount(_ page: JSONValue) -> Int? {
    guard case .number(let total) = page["totalElements"], total >= 0, total.isFinite, total < Double(Int.max) else { return nil }
    return Int(total)
}

/// Process definition variables describe roles, not literal user names.
func workflowParticipantLabel(_ raw: String) -> String {
    let roles = ["${initiator}": "发起人", "initiator": "发起人", "REPAIR_USER": "维修人员"]
    return raw.components(separatedBy: CharacterSet(charactersIn: ",，;；"))
        .compactMap { part -> String? in
            let value = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if let role = roles[value] { return role }
            guard !value.isEmpty, !value.contains("${"), !value.contains("#{"),
                  // Technical enums look like IN_USE or REPAIR_USER (underscore); employee numbers such as
                  // C0070034 are user names and must stay.
                  value.range(of: "^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$", options: .regularExpression) == nil,
                  value.range(of: "^[0-9]+$", options: .regularExpression) == nil else { return nil }
            return value
        }
        .joined(separator: "、")
}

func assetDisplayValue(_ value: JSONValue, key: String) -> String {
    let text = readableDate(value)
    guard !text.isEmpty else { return "—" }
    if key == "useState" {
        return ["IN_FACTORY": "入厂", "IN_USE": "在用", "UN_USE": "闲置", "SCRAP": "待报废", "SELLOFF": "已报废", "DISABLED": "禁用", "LEASE": "出租", "OTHER": "其它"][text] ?? "状态待核对"
    }
    return text
}

func assetRepairRecord(_ repair: JSONValue, asset: JSONValue) -> JSONValue {
    var record = repair.object
    var fields = asset.object
    for (key, value) in repair["asset"].object where value != .null && !value.text.isEmpty {
        fields[key] = value
    }
    record["asset"] = .object(fields)
    return .object(record)
}
