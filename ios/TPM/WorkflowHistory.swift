import SwiftUI

// The process history ("审批流程") in the order and wording of the original client: time and how long
// ago, who handled it (real names, looked up from the user name), the action taken, the node, the
// comments and how long it took.

// MARK: Ordering and wording

/// A running user task can be reported twice; keep one, preferring the copy that carries comments.
func uniqueRunningUserTasks(_ nodes: [JSONValue]) -> [JSONValue] {
    var result: [JSONValue] = []
    for node in nodes {
        guard node["activityType"].text == "userTask", node["endTime"] == .null else { result.append(node); continue }
        guard let index = result.firstIndex(where: { other in
            other["activityType"].text == "userTask" && other["endTime"] == .null &&
            (other["taskId"].text == node["taskId"].text || other["activityId"].text == node["activityId"].text || other["activityName"].text == node["activityName"].text)
        }) else { result.append(node); continue }
        if result[index]["comments"].array.isEmpty && !node["comments"].array.isEmpty { result[index] = node }
    }
    return result
}

private func historySortWeight(_ node: JSONValue) -> Int {
    let type = node["activityType"].text, id = node["activityId"].text
    if type == "startEvent" || id == "START" { return 0 }
    if id == "ACT_INITIATOR" { return 1 }
    if type == "userTask" && node["endTime"] != .null { return 2 }
    if ["transfer", "delegate", "delegateResolve"].contains(type) { return 3 }
    if type == "userTask" { return 4 }
    if type == "endEvent" || id == "END" { return 9 }
    return 5
}

func orderedHistory(_ raw: [JSONValue]) -> [JSONValue] {
    uniqueRunningUserTasks(raw).sorted { a, b in
        let ta = a["sortTime"] == .null ? Double.greatestFiniteMagnitude : a["sortTime"].number
        let tb = b["sortTime"] == .null ? Double.greatestFiniteMagnitude : b["sortTime"].number
        return ta != tb ? ta < tb : historySortWeight(a) < historySortWeight(b)
    }
}

func historyTimePrefix(_ node: JSONValue) -> String {
    if !node["displayTimePrefix"].text.isEmpty { return node["displayTimePrefix"].text }
    switch node["activityType"].text {
    case "transfer": return "转办"
    case "delegate": return "委派"
    case "delegateResolve": return "委派完成"
    case "userTask": return node["endTime"] == .null ? "当前" : "完成"
    default: return "完成"
    }
}

func historyTimeText(_ node: JSONValue) -> String {
    for key in ["displayTimeText", "endTimeText", "startTimeText"] where !node[key].text.isEmpty { return node[key].text }
    for key in ["endTime", "startTime"] where node[key] != .null { return readableDate(node[key]) }
    return ""
}

/// "9天前" style text for a "yyyy-MM-dd HH:mm" time.
func relativeTimeText(_ time: String, now: Date = Date()) -> String {
    guard !time.isEmpty else { return "" }
    let date = parsedDate(.string(time))
    let formatter = RelativeDateTimeFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.unitsStyle = .short
    return formatter.localizedString(for: date, relativeTo: now)
}

func historyDuration(_ millis: Double) -> String {
    let days = Int(millis / 86_400_000), hours = Int(millis.truncatingRemainder(dividingBy: 86_400_000) / 3_600_000)
    let minutes = Int(millis.truncatingRemainder(dividingBy: 3_600_000) / 60_000)
    let seconds = millis.truncatingRemainder(dividingBy: 60_000) / 1000
    let secondText = seconds > 0 ? String(format: "%g", (seconds * 1000).rounded() / 1000) + "秒" : "00秒"
    return (days > 0 ? "\(days)天" : "") + (hours > 0 ? "\(hours)时" : "") + (minutes > 0 ? "\(minutes)分" : "") + secondText
}

/// Names written as a list, an array or "[a, b]" text.
func splitUserNames(_ value: JSONValue) -> [String] {
    if !value.array.isEmpty { return value.array.map { $0.text.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty } }
    let text = value.text.replacingOccurrences(of: "[", with: "").replacingOccurrences(of: "]", with: "")
    return text.split(whereSeparator: { ",，、;；".contains($0) || $0.isWhitespace }).map(String.init).filter { !$0.isEmpty }
}

/// Who handled (or has to handle) a history entry, as user names. Process expressions such as
/// `${initiator}` are not names and are turned into their role word or dropped.
func historyAssigneeNames(_ node: JSONValue) -> [String] {
    var names: [String] = []
    for key in ["assignee", "assignees", "candidateUsers", "candidateUserIds"] where node[key] != .null && !splitUserNames(node[key]).isEmpty {
        names = splitUserNames(node[key]); break
    }
    if names.isEmpty && isCountersignHistory(node) {
        let pending = splitUserNames(node["countersignPendingAssignees"] == .null ? node["pendingAssignees"] : node["countersignPendingAssignees"])
        names = pending.isEmpty ? countersignNames(node, "all") : pending
    }
    return names.compactMap { name in
        if name.contains("${") || name.contains("#{") { return workflowParticipantLabel(name).isEmpty ? nil : workflowParticipantLabel(name) }
        return name
    }
}

func isCountersignHistory(_ node: JSONValue) -> Bool {
    node["countersignTask"].bool || node["countersign"].bool || node["multiInstance"].bool || !node["countersignStatusText"].text.isEmpty
        || !node["countersignCollectionVariable"].text.isEmpty || node["countersignTotal"].number > 0 || !countersignNames(node, "all").isEmpty
}

func countersignNames(_ node: JSONValue, _ kind: String) -> [String] {
    func first(_ keys: [String]) -> [String] {
        for key in keys where node[key] != .null { let names = splitUserNames(node[key]); if !names.isEmpty { return names } }
        return []
    }
    switch kind {
    case "completed": return first(["countersignCompletedAssignees", "completedAssignees"])
    case "pending": return first(["countersignPendingAssignees", "pendingAssignees"])
    default: return first(["countersignAssignees", "countersignAllAssignees", "countersignRuntimeAssignees"])
    }
}

/// Every user name that appears in the history, so real names can be looked up once each.
func historyUserNames(_ nodes: [JSONValue]) -> [String] {
    var seen = Set<String>(), result: [String] = []
    for node in nodes {
        for name in historyAssigneeNames(node) + countersignNames(node, "all") + countersignNames(node, "completed") + countersignNames(node, "pending") where seen.insert(name).inserted {
            result.append(name)
        }
    }
    return result
}

func showsConditionTag(_ node: JSONValue) -> Bool {
    let condition = node["condition"].text
    if condition.isEmpty { return false }
    let type = node["activityType"].text
    if ["transfer", "delegate", "delegateResolve"].contains(type) { return false }
    if type == "userTask" && node["endTime"] == .null && condition == "提交" { return false }
    return !["转办记录", "委派记录", "委派完成"].contains { condition.hasPrefix($0) }
}

func conditionColor(_ condition: String) -> Color {
    switch condition {
    case "流程发起": .cyan
    case "流程结束": .orange
    case "提交", "通过", "同意": .green
    case "拒绝", "驳回": .red
    default: .secondary
    }
}

func activityTypeColor(_ type: String) -> Color {
    switch type {
    case "transfer": .orange
    case "delegate": .purple
    case "delegateResolve": .teal
    case "startEvent": .cyan
    case "endEvent": .red
    case "userTask": .green
    default: .gray
    }
}

func activityTypeText(_ type: String) -> String {
    switch type { case "transfer": "流程转办"; case "delegate": "流程委派"; case "delegateResolve": "委派完成"; case "": "流程节点"; default: type }
}

func commentTypeText(_ comment: JSONValue) -> String {
    switch comment["type"].text { case "transfer": "转办记录"; case "delegate": "委派记录"; case "delegateResolve": "委派完成"; default: "意见说明" }
}

// MARK: Real names

/// user name → real name, asked of the server once and kept for the session (as the original does).
@MainActor
final class UserDirectory: ObservableObject {
    /// One directory for the whole signed-in session, so a name looked up once is not asked for again by the next page.
    static let shared = UserDirectory()
    @Published private(set) var realNames: [String: String] = [:]
    func clear() { realNames = [:] }
    func name(_ userName: String) -> String { realNames[userName].flatMap { $0.isEmpty ? nil : $0 } ?? userName }
    func resolve(_ userNames: [String], session: LiveSession) async {
        let missing = userNames.filter { realNames[$0] == nil }
        guard !missing.isEmpty else { return }
        await withTaskGroup(of: (String, String?).self) { group in
            for userName in missing {
                group.addTask {
                    do { return (userName, try await session.request("security/getUserByUserName", query: ["userName": userName])["realName"].text) }
                    catch { return (userName, nil) }
                }
            }
            for await (userName, realName) in group {
                // A failed lookup shows the user name; it is not cached so the next load tries again.
                if let realName, !realName.isEmpty { realNames[userName] = realName }
            }
        }
    }
}

// MARK: Views

struct HistoryChip: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text).font(AppFont.caption).foregroundStyle(color)
    }
}

struct HistoryNodeRow: View {
    let node: JSONValue
    @ObservedObject var directory: UserDirectory
    private var type: String { node["activityType"].text }
    private var time: String { historyTimeText(node) }
    private func names(_ list: [String]) -> String { list.map { directory.name($0) }.joined(separator: "、") }
    var body: some View {
        VStack(alignment: .leading, spacing: RowMetrics.spacing) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Circle().fill(activityTypeColor(type)).frame(width: 10, height: 10)
                Text("\(historyTimePrefix(node))：\(time.isEmpty ? "等待处理" : time)").font(AppFont.subheadline.weight(.semibold))
                if !time.isEmpty { Text("（\(relativeTimeText(time))）").font(AppFont.footnote).foregroundStyle(.secondary) }
            }
            let assignees = historyAssigneeNames(node)
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 4) {
                    Image(systemName: "person.fill").font(AppFont.caption).foregroundStyle(.secondary)
                    if assignees.isEmpty { Text("无").foregroundStyle(.secondary) } else { Text(names(assignees)) }
                }.font(AppFont.subheadline)
                if showsConditionTag(node) { HistoryChip(text: node["condition"].text, color: conditionColor(node["condition"].text)) }
                HistoryChip(text: node["activityName"].text.isEmpty ? activityTypeText(type) : node["activityName"].text, color: activityTypeColor(type))
            }
            if isCountersignHistory(node) {
                HStack(spacing: 8) {
                    HistoryChip(text: "会签", color: node["countersignPending"].number > 0 ? .orange : .green)
                    if !node["countersignStatusText"].text.isEmpty { Text(node["countersignStatusText"].text).font(AppFont.footnote).foregroundStyle(.secondary) }
                }
                ForEach([("会签人员", countersignNames(node, "all")), ("已处理", countersignNames(node, "completed")), ("待处理", countersignNames(node, "pending"))], id: \.0) { label, list in
                    if !list.isEmpty { Text("\(label)：\(names(list))").font(AppFont.footnote).foregroundStyle(.secondary) }
                }
            }
            let comments = node["comments"].array
            ForEach(Array(comments.enumerated()), id: \.offset) { _, comment in
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    HistoryChip(text: commentTypeText(comment), color: comment["type"].text == "transfer" ? .orange : comment["type"].text == "delegate" ? .purple : comment["type"].text == "delegateResolve" ? .teal : .accentColor)
                    Text(comment["fullMessage"].text.isEmpty ? comment["message"].text : comment["fullMessage"].text).font(AppFont.subheadline).textSelection(.enabled)
                }
            }
            if comments.isEmpty && type == "userTask" && node["endTime"] != .null { Text("意见说明：无").font(AppFont.subheadline).foregroundStyle(.secondary) }
            if node["durationInMillis"].number > 0 { Text("耗时：" + historyDuration(node["durationInMillis"].number)).font(AppFont.footnote).foregroundStyle(.secondary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .uniformRow()
        .accessibilityElement(children: .contain)
    }
}
