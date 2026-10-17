import SwiftUI

// Sent-back orders, as the original app shows them. The fault itself carries the flag `returnOrder`
// ("是否退单"): a flagged order that is back at "待受理" was returned by the repairer ("退单"), one that is back
// at "报修" was rejected by the acceptor ("驳回"). The card label needs nothing but the order's own fields, so
// lists make no extra request. The repairer's note is the `content` of the order's latest repair record
// (`repair/getRepairsByFaultId`, the same call the original detail page makes), and the person who did it is
// that record's `user` ("执行人"). The process history is never read for any of this.

private func isFlagged(_ value: JSONValue) -> Bool {
    value.bool || ["true", "1"].contains(value.text.lowercased())
}

/// "退单" or "驳回" when the fault was sent back and is still at the step it was sent back to.
func faultReturnKind(_ fault: JSONValue) -> String? {
    guard isFlagged(fault["returnOrder"]) else { return nil }
    switch fault["faultState"].text {
    case "REPORTED": return "退单"
    case "UNTREATED": return "驳回"
    default: return nil
    }
}

/// "待受理（退单）", "报修（驳回）", or just the state's name.
func faultStateLabel(_ fault: JSONValue) -> String {
    let name = faultStateName(fault["faultState"].text)
    return faultReturnKind(fault).map { name + "（" + $0 + "）" } ?? name
}

/// The latest repair record of a fault, from the answer of `repair/getRepairsByFaultId`.
func latestRepair(_ answer: JSONValue) -> JSONValue? {
    let list = answer.array.isEmpty ? answer["content"].array : answer.array
    return list.first.flatMap { $0.object.isEmpty ? nil : $0 }
}

/// Whether the repair record is worth a request for this state: only states that follow a repair, or the
/// state a repairer's 退单 returns to, can have something to show.
func faultMayHaveRepairNote(_ fault: JSONValue) -> Bool {
    ["REPAIRED", "VERIFIED", "RESOLVED", "FINISHED"].contains(fault["faultState"].text) || faultReturnKind(fault) != nil
}

/// The process instance an order or todo item belongs to.
func orderProcessID(_ item: JSONValue) -> String {
    let direct = item["processInstanceId"].text
    return direct.isEmpty ? item["taskInfo"]["processInstanceId"].text : direct
}

/// What a detail page says about a sent-back order: who, and why.
struct ReturnNoteSection: View {
    let kind: String
    let repair: JSONValue
    let fault: JSONValue
    private var person: String {
        // 退单 is the repairer's: the record's executor. A 驳回 comes from the acceptor.
        kind == "退单" ? repair["user"]["realName"].text : fault["user"]["realName"].text
    }
    private var reason: String {
        kind == "退单" ? repair["content"].text : fault["ignoreReason"].text
    }
    var body: some View {
        Section(kind == "退单" ? "维修退单说明" : "驳回说明") {
            LabeledContent(kind == "退单" ? "退单人" : "处理人", value: person.isEmpty ? "—" : person)
            if !readableDate(repair["signTime"]).isEmpty && kind == "退单" { LabeledContent("签到时间", value: readableDate(repair["signTime"])) }
            VStack(alignment: .leading, spacing: 6) {
                Text(kind + "原因").font(.subheadline).foregroundStyle(.secondary)
                Text(reason.isEmpty ? "未填写" : reason).textSelection(.enabled)
            }
            .contextMenu { if !reason.isEmpty { Button("复制", systemImage: "doc.on.doc") { copyToPasteboard(reason) } } }
        }
    }
}

/// The repairer's work description of the latest repair ("解决方案") with who did it.
struct RepairResultSection: View {
    let repair: JSONValue
    var body: some View {
        Section("维修解决方案") {
            if !repair["user"]["realName"].text.isEmpty { LabeledContent("执行人", value: repair["user"]["realName"].text) }
            let solution = repair["content"].text
            Text(solution.isEmpty ? "未填写" : solution).textSelection(.enabled)
                .contextMenu { if !solution.isEmpty { Button("复制", systemImage: "doc.on.doc") { copyToPasteboard(solution) } } }
        }
    }
}
