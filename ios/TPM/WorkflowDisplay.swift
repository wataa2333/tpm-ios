import Foundation

/// Process definition variables describe roles, not literal user names.
func workflowParticipantLabel(_ raw: String) -> String {
    let roles = ["${initiator}": "发起人", "initiator": "发起人", "REPAIR_USER": "维修人员"]
    return raw.components(separatedBy: CharacterSet(charactersIn: ",，;；"))
        .compactMap { part -> String? in
            let value = part.trimmingCharacters(in: .whitespacesAndNewlines)
            if let role = roles[value] { return role }
            guard !value.isEmpty, !value.contains("${"), !value.contains("#{"),
                  value.range(of: "^[A-Z][A-Z_0-9]+$", options: .regularExpression) == nil,
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
