import SwiftUI

// Sent-back orders, as the original app shows them. The fault itself carries the flag `returnOrder`
// ("是否退单"): a flagged order that is back at "待受理" was returned by the repairer ("退单"), one that is back
// at "报修" was rejected by the acceptor ("驳回"). The card label needs nothing but the order's own fields, so
// lists make no extra request. The repairer's note is the `content` of the order's latest repair record
// (`repair/getRepairsByFaultId`). The return actor is read separately from completed workflow history;
// a repair record's user is never evidence of who returned the order.

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
        fault["user"]["realName"].text
    }
    private var reason: String {
        kind == "退单" ? repair["content"].text : fault["ignoreReason"].text
    }
    var body: some View {
        Section(kind == "退单" ? "维修退单说明" : "驳回说明") {
            if kind == "退单" { ReturnActorRow(processID: orderProcessID(fault)) }
            else { LabeledContent("处理人", value: person.isEmpty ? "—" : person) }
            if !readableDate(repair["signTime"]).isEmpty && kind == "退单" { LabeledContent("签到时间", value: readableDate(repair["signTime"])) }
            VStack(alignment: .leading, spacing: 6) {
                Text(kind + "原因").font(.subheadline).foregroundStyle(.secondary)
                Text(reason.isEmpty ? "未填写" : reason).textSelection(.enabled)
            }
            .contextMenu { if !reason.isEmpty { Button("复制", systemImage: "doc.on.doc") { copyToPasteboard(reason) } } }
        }
    }
}

/// Use only the newest completed return task. Missing actors on it must not fall back to an older task.
func latestReturnActors(_ nodes: [JSONValue]) -> [String] {
    let returned = nodes.filter {
        $0["activityType"].text == "userTask" && $0["endTime"] != .null && !$0["endTime"].text.isEmpty &&
        ($0["condition"].text.contains("退单") || $0["condition"].text.contains("返单"))
    }
    let dated = returned.compactMap { node -> (JSONValue, Double)? in
        returnActionTime(node).map { (node, $0) }
    }
    // Unknown ordering must not make an older actor appear to be the latest one.
    guard dated.count == returned.count, let newest = dated.map({ $0.1 }).max() else { return [] }
    let latestNodes = dated.filter { $0.1 == newest }
    guard latestNodes.count == 1, let latest = latestNodes.first?.0 else { return [] }
    let assignee = splitUserNames(latest["assignee"])
    let actors = assignee.isEmpty ? splitUserNames(latest["assignees"]) : assignee
    var seen = Set<String>()
    return actors.filter {
        !$0.contains("${") && !$0.contains("#{") && !["initiator", "REPAIR_USER"].contains($0) && seen.insert($0).inserted
    }
}

private func returnActionTime(_ node: JSONValue) -> Double? {
    if case .number(let value) = node["sortTime"], value.isFinite, value > 0 { return value }
    let end = node["endTime"]
    if case .number(let value) = end, value.isFinite, value > 0 { return value > 100_000_000_000 ? value : value * 1000 }
    if end.array.count >= 5 {
        let v = end.array.map { Int($0.number) }
        guard let date = Calendar.current.date(from: DateComponents(year: v[0], month: v[1], day: v[2], hour: v[3], minute: v[4], second: v.count > 5 ? v[5] : 0)) else { return nil }
        return date.timeIntervalSince1970 * 1000
    }
    let iso = ISO8601DateFormatter()
    if let date = iso.date(from: end.text) { return date.timeIntervalSince1970 * 1000 }
    iso.formatOptions.insert(.withFractionalSeconds)
    if let date = iso.date(from: end.text) { return date.timeIntervalSince1970 * 1000 }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.isLenient = false
    for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm"] {
        formatter.dateFormat = format
        if let date = formatter.date(from: end.text) { return date.timeIntervalSince1970 * 1000 }
    }
    return nil
}

struct ReturnActorRow: View {
    let processID: String
    @EnvironmentObject private var session: LiveSession
    @State private var retry = 0
    @State private var loading = true
    @State private var names = ""
    @State private var nameLookupIncomplete = false
    @State private var failure: String?
    @State private var requestID = UUID()
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if loading {
                HStack { Text("退单人"); Spacer(); ProgressView(); Text("加载中...").foregroundStyle(.secondary) }
            } else if let failure {
                Text("退单人读取失败：" + failure).font(.subheadline).foregroundStyle(.secondary)
                Button("重试") { retry += 1 }
            } else {
                LabeledContent("退单人", value: names.isEmpty ? "未找到最新已完成退单动作的实际执行人" : names)
                if nameLookupIncomplete { Button("重试读取姓名") { retry += 1 } }
            }
        }
        .interfaceMotion(loading)
        .interfaceMotion(failure != nil)
        .task(id: "\(session.scopeRevision)-\(processID)-\(retry)") { await load() }
    }
    @MainActor private func load() async {
        let run = UUID(), scope = session.scopeRevision
        requestID = run; loading = true; failure = nil; names = ""; nameLookupIncomplete = false
        defer { if requestID == run { loading = false } }
        guard !processID.isEmpty else { names = "缺少流程编号，无法取得退单人"; return }
        do {
            let answer = try await session.request("workflow/getWorkflowHistoryNodesByProcessInstanceId", query: ["processInstanceId": processID])
            guard case .array(let nodes) = answer else { throw APIError(message: "流程历史响应格式无法识别") }
            let actors = latestReturnActors(nodes)
            var labels: [String] = []
            var incomplete = false
            for actor in actors {
                let user = try? await session.request("security/getUserByUserName", query: ["userName": actor])
                let name = user?["realName"].text ?? ""
                if name.isEmpty { incomplete = true }
                labels.append(name.isEmpty ? actor + "（姓名未取得）" : name + "（" + actor + "）")
            }
            try Task.checkCancellation()
            guard requestID == run, session.scopeRevision == scope else { return }
            names = labels.joined(separator: "、")
            nameLookupIncomplete = incomplete
        } catch {
            guard requestID == run, session.scopeRevision == scope, !isCancellation(error) else { return }
            failure = error.localizedDescription
        }
    }
}

func repairMaterialCode(_ row: JSONValue) -> String {
    for path in ["material.materialCode", "materialOutbound.material.materialCode"] {
        let code = row.at(path).text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !code.isEmpty { return code }
    }
    return "未提供"
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
