import SwiftUI

// Multi-select and batch handling. A list (the repair list, the todo list) can enter a selection mode: a
// circle appears on every card, tapping the circle selects it, tapping the card opens a read-only peek at the
// order in a sheet, so the selection and the loaded list stay exactly as they were. Only orders of one
// status can be selected together (the batch does the same thing to each of them), at most 100. The batch
// then runs in the foreground, one order after the other, and reports on a progress sheet.

// MARK: - What can be done

enum BatchAction: String, Identifiable {
    case accept, claim, signIn, complete, approve
    var id: String { rawValue }
    var title: String {
        switch self {
        case .accept: "批量受理"
        case .claim: "批量接单"
        case .signIn: "批量签到"
        case .complete: "批量完成"
        case .approve: "批量审批"
        }
    }
    var symbol: String {
        switch self {
        case .accept: "checkmark.seal"
        case .claim: "hand.raised"
        case .signIn: "mappin.and.ellipse"
        case .complete: "wrench.and.screwdriver"
        case .approve: "checkmark.circle"
        }
    }
    /// Accepting and completing need the same answers for every order, filled in once.
    var needsPreset: Bool { self == .accept || self == .complete }

    /// The one batch action that fits orders of a status, or nil when that status has none.
    static func forGroup(_ group: String) -> BatchAction? {
        switch group {
        case "REPORTED": .accept
        case "RECEIVED": .claim
        case "SIGNED": .signIn
        case "REPAIRED": .complete
        case "VERIFIED", "RESOLVED": .approve
        case "ERP:PENDING", "ERP:APPROVED", "ERP:EXECUTING", "ERP:CONFIRM": .approve
        default: nil
        }
    }
}

/// The status an order is in, as the batch tells statuses apart: the fault state, or "ERP:" and the approval state.
func batchGroup(kind: WorkflowKind, faultState: String, approvalState: String) -> String {
    kind == .fault ? faultState : "ERP:" + approvalState
}
func batchGroupName(_ group: String) -> String {
    group.hasPrefix("ERP:") ? "ERP " + approvalStateName(String(group.dropFirst(4))) : faultStateName(group)
}

/// One order that can be selected.
struct BatchItem: Identifiable, Equatable {
    let id: String
    let kind: WorkflowKind
    let name: String
    let reference: String
    let group: String
}

func batchItem(kind: WorkflowKind, item: JSONValue) -> BatchItem {
    BatchItem(id: item[kind.idKey].text, kind: kind,
              name: kind == .fault ? item["faultName"].text : erpCardTitle(item),
              reference: kind == .fault ? item["asset"]["assetCode"].text : item["outNumber"].text,
              group: batchGroup(kind: kind, faultState: item["faultState"].text, approvalState: item["approvalState"].text))
}
/// A todo task carries its order in `data`; the order's id is the task's business key.
func batchItem(todo task: JSONValue) -> BatchItem? {
    guard let kind = todoTaskKind(task), !task["suspended"].bool, !task["businessKey"].text.isEmpty else { return nil }
    let data = task["data"]
    return BatchItem(id: task["businessKey"].text, kind: kind,
                     name: todoTitle(task),
                     reference: kind == .fault ? data["asset"]["assetCode"].text : data["outNumber"].text,
                     group: batchGroup(kind: kind, faultState: data["faultState"].text, approvalState: data["approvalState"].text))
}

// MARK: - The selection

@MainActor
final class BatchSelection: ObservableObject {
    static let limit = 100
    @Published private(set) var active = false
    @Published private(set) var items: [BatchItem] = []
    /// Said in a short toast: why a card cannot be selected.
    @Published var notice: String?
    /// The order being looked at in the peek sheet. Showing it changes nothing about the selection or the list.
    @Published var peeking: BatchItem?

    var group: String? { items.first?.group }
    var action: BatchAction? { group.flatMap(BatchAction.forGroup) }
    var groupTitle: String { group.map(batchGroupName) ?? "点左侧圆圈选择单据" }

    func begin() { active = true }
    func end() { active = false; items = []; peeking = nil }
    func clear() { items = [] }
    func contains(_ id: String) -> Bool { items.contains { $0.id == id } }
    /// Whether the circle of this card works: its status has a batch action and matches what is already selected.
    func isSelectable(_ item: BatchItem) -> Bool {
        guard BatchAction.forGroup(item.group) != nil else { return false }
        if let group { return group == item.group }
        return true
    }
    func toggle(_ item: BatchItem) {
        if let index = items.firstIndex(where: { $0.id == item.id }) { items.remove(at: index); return }
        guard BatchAction.forGroup(item.group) != nil else { notice = "「" + batchGroupName(item.group) + "」的单据暂不支持批量处理"; return }
        if let group, group != item.group {
            notice = "只能选择同一种状态的单据：已选「" + batchGroupName(group) + "」，不能再选「" + batchGroupName(item.group) + "」"
            return
        }
        guard items.count < Self.limit else { notice = "一次最多选择 \(Self.limit) 张单据"; return }
        items.append(item)
    }
    /// Selects the loaded orders of the status already chosen (or of the first order that can be selected).
    func selectAll(_ candidates: [BatchItem]) {
        guard let target = group ?? candidates.first(where: { BatchAction.forGroup($0.group) != nil })?.group else { notice = "没有可批量处理的单据"; return }
        var full = false
        for candidate in candidates where candidate.group == target && !contains(candidate.id) {
            if items.count >= Self.limit { full = true; break }
            items.append(candidate)
        }
        if full { notice = "一次最多选择 \(Self.limit) 张单据，已选满" }
    }
    func remove(ids: Set<String>) { items.removeAll { ids.contains($0.id) } }
}

// MARK: - The answers shared by every order of a batch

struct BatchPreset: Equatable {
    var faultType: JSONValue = .null
    /// Accept: the repair group and the date the repair should be done by, and optionally one repairer.
    var group: JSONValue = .null
    var estimated: JSONValue = .string(defaultEstimatedTime())
    var assignUser: JSONValue = .null
    /// Complete: the work description, and how long the work took in minutes (empty: counted from the sign-in).
    var content = ""
    var durationMinutes: Int?

    func isComplete(for action: BatchAction) -> Bool {
        guard !faultType.object.isEmpty else { return false }
        switch action {
        case .accept: return !group.object.isEmpty && !estimated.text.isEmpty
        case .complete: return !content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && (durationMinutes ?? 1) >= 1
        default: return true
        }
    }
}

/// The date a repair should be done by when nobody says otherwise: the end of today, as in the single form.
func defaultEstimatedTime(now: Date = Date()) -> String {
    serverDate(Calendar.current.date(bySettingHour: 23, minute: 59, second: 59, of: now) ?? now)
}

// MARK: - The run

struct BatchFailure: Identifiable, Equatable {
    let id: String
    let name: String
    let reference: String
    let message: String
}

@MainActor
final class BatchRun: ObservableObject, Identifiable {
    let id = UUID()
    let action: BatchAction
    let items: [BatchItem]
    let preset: BatchPreset
    @Published private(set) var processed = 0
    @Published private(set) var succeeded: [String] = []
    @Published private(set) var failures: [BatchFailure] = []
    @Published private(set) var current: BatchItem?
    @Published private(set) var running = false
    @Published private(set) var finished = false
    /// Set when the run ended before its last order, and why.
    @Published private(set) var endedEarly: String?
    private var stopRequested = false

    init(action: BatchAction, items: [BatchItem], preset: BatchPreset) {
        self.action = action; self.items = items; self.preset = preset
    }
    var total: Int { items.count }
    var remaining: Int { total - processed }

    func stop() { stopRequested = true }

    /// One order at a time, in the foreground: the server does not take orders handled side by side.
    func start(session: LiveSession) async {
        guard !running, !finished else { return }
        running = true
        UIApplication.shared.isIdleTimerDisabled = true
        defer { UIApplication.shared.isIdleTimerDisabled = false; current = nil; running = false; finished = true }
        for item in items {
            if stopRequested { endedEarly = "已停止，还有 \(remaining) 张没有处理。"; break }
            if Task.isCancelled { endedEarly = "处理被中断，还有 \(remaining) 张没有处理。"; break }
            guard session.signedIn else { endedEarly = "登录已失效，还有 \(remaining) 张没有处理，请重新登录。"; break }
            current = item
            do {
                try await BatchProcessor.process(item, action: action, preset: preset, session: session)
                succeeded.append(item.id)
            } catch {
                if isCancellation(error) { endedEarly = "账号或厂区已切换，还有 \(remaining) 张没有处理。"; break }
                failures.append(BatchFailure(id: item.id, name: item.name, reference: item.reference, message: error.localizedDescription))
            }
            processed += 1
        }
    }
}

// MARK: - The screens

/// What a card needs to take part in selection.
struct CardSelecting {
    var selected: Bool
    /// False when the card's status cannot be selected together with what is already selected.
    var enabled: Bool
    var toggle: () -> Void
    var peek: () -> Void
}

/// Gives a list the selection mode: the toolbar button, the bar with the count and the action, the preset
/// sheet, the progress sheet and the peek sheet. The list passes the orders it has loaded.
struct BatchHost: ViewModifier {
    @ObservedObject var batch: BatchSelection
    let candidates: () -> [BatchItem]
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var presetting: BatchAction?
    @State private var pendingRun: BatchRun?
    @State private var run: BatchRun?
    @State private var finishing: BatchRun?

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if batch.active { Button("取消") { batch.end() }.accessibilityIdentifier("batch-cancel") }
                    else { Button("选择") { batch.begin() }.accessibilityIdentifier("batch-select") }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if batch.active {
                    BatchBar(batch: batch, onSelectAll: { batch.selectAll(candidates()) }, onRun: begin)
                        .transition(.opacity)
                }
            }
            .animation(.smooth(duration: 0.3), value: batch.active)
            .onChange(of: batch.active) { _, active in navigation.selectingBatch = active }
            .onDisappear { if batch.active { batch.end() }; navigation.selectingBatch = false }
            .toast($batch.notice)
            .sheet(item: $presetting, onDismiss: {
                if let started = pendingRun { pendingRun = nil; finishing = started; run = started }
            }) { action in
                BatchPresetSheet(action: action, count: batch.items.count) { preset in
                    pendingRun = BatchRun(action: action, items: batch.items, preset: preset)
                    presetting = nil
                }
            }
            .sheet(item: $run, onDismiss: finishRun) { run in
                BatchRunSheet(run: run)
            }
            .sheet(item: $batch.peeking) { item in
                BatchPeekSheet(item: item)
            }
    }

    private func begin() {
        guard let action = batch.action, !batch.items.isEmpty else { return }
        if action.needsPreset { presetting = action }
        else {
            let started = BatchRun(action: action, items: batch.items, preset: BatchPreset())
            finishing = started; run = started
        }
    }
    /// The orders that went through leave the selection; the ones that failed stay selected for another try.
    private func finishRun() {
        guard let done = finishing else { return }
        finishing = nil
        batch.remove(ids: Set(done.succeeded))
        if batch.items.isEmpty { batch.end() }
        if !done.succeeded.isEmpty { navigation.refresh = UUID() }
    }
}

extension View {
    func batchHost(_ batch: BatchSelection, candidates: @escaping () -> [BatchItem]) -> some View {
        modifier(BatchHost(batch: batch, candidates: candidates))
    }
}

/// The bar at the bottom of a list in selection mode.
struct BatchBar: View {
    @ObservedObject var batch: BatchSelection
    let onSelectAll: () -> Void
    let onRun: () -> Void
    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("已选 \(batch.items.count)/\(BatchSelection.limit)").font(.headline).monospacedDigit()
                Text(batch.groupTitle).font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Menu {
                Button("全选已加载的单据", systemImage: "checkmark.circle", action: onSelectAll)
                Button("清空已选", systemImage: "circle", role: .destructive) { batch.clear() }
            } label: { Text("全选").font(.subheadline.weight(.medium)).padding(.horizontal, 6).frame(minHeight: 28) }
                .buttonStyle(.glass).accessibilityIdentifier("batch-select-all")
            if let action = batch.action {
                Button(action: onRun) {
                    Label(action.title, systemImage: action.symbol).font(.headline).padding(.horizontal, 6).frame(minHeight: 28)
                }
                .buttonStyle(.glassProminent).disabled(batch.items.isEmpty)
                .accessibilityIdentifier("batch-run")
            }
        }
        .bottomBarPadding()
    }
}

/// The answers every order of the batch gets. Chosen once, applied to each order as the single form would.
struct BatchPresetSheet: View {
    let action: BatchAction
    let count: Int
    let start: (BatchPreset) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var preset = BatchPreset()
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ReferenceField(title: "维修故障类型", source: .faultTypes, value: $preset.faultType, required: true)
                    if action == .accept {
                        ReferenceField(title: "维修班组", source: .groups, value: $preset.group, required: true)
                        ServerDateField(title: "预计完成时间", value: $preset.estimated, required: true)
                        ReferenceField(title: "维修人员（选填）", source: .users, value: $preset.assignUser)
                    }
                } header: { RequiredFormHeader(title: action == .accept ? "受理预设方案" : "完成预设方案") } footer: {
                    Text(action == .accept
                         ? "选好后，所选 \(count) 张单据都按这个方案受理。不选维修人员就是派单，选了就按“指派”处理。"
                         : "选好后，所选 \(count) 张单据都按这个方案提交维修结果；每张单据原有的维修领料不变，下一步审批人用你设好的默认人。")
                }
                if action == .complete {
                    Section { EntryField(placeholder: "请输入工作说明", text: $preset.content, limit: 255, axis: .vertical, lines: 3...8) } header: { RequiredLabel(title: "工作说明") }
                    Section {
                        HStack {
                            Text("耗时（分钟）")
                            Spacer()
                            TextField("留空则从签到时间算起", value: $preset.durationMinutes, format: .number).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        }
                    } footer: { Text("填写后，每张单据的维修开始时间 = 现在往前推这么多分钟，结束时间为现在。") }
                }
            }
            .navigationTitle(action.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button { start(preset) } label: {
                    Text("开始处理 \(count) 张").font(.headline).frame(maxWidth: .infinity, minHeight: 28)
                }
                .buttonStyle(.glassProminent).controlSize(.large)
                .disabled(!preset.isComplete(for: action))
                .padding(.horizontal, 20).padding(.vertical, 8)
                .accessibilityIdentifier("batch-start")
            }
            .keyboardManaged()
        }
        .presentationDragIndicator(.visible)
    }
}

/// The progress of a run: how many orders there are, how many are done, how many went through and how many
/// failed. Failures are only reported here. The "完成" button is always on screen; it works once the run is over.
struct BatchRunSheet: View {
    @ObservedObject var run: BatchRun
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            List {
                Section {
                    VStack(alignment: .leading, spacing: 10) {
                        ProgressView(value: Double(run.processed), total: Double(max(run.total, 1)))
                            .accessibilityIdentifier("batch-progress")
                        HStack {
                            Text(headline).font(.subheadline.weight(.medium))
                            Spacer()
                            Text("\(run.processed)/\(run.total)").font(.subheadline).monospacedDigit().foregroundStyle(.secondary)
                        }
                    }.padding(.vertical, 4)
                    if let current = run.current {
                        LabeledContent("正在处理", value: current.name + (current.reference.isEmpty ? "" : " · " + current.reference))
                    }
                    if let early = run.endedEarly { Text(early).font(.subheadline).foregroundStyle(.orange) }
                }
                Section("统计") {
                    LabeledContent("全部", value: "\(run.total)")
                    LabeledContent("已处理", value: "\(run.processed)")
                    LabeledContent("完成") { Text("\(run.succeeded.count)").foregroundStyle(.green) }
                    LabeledContent("失败") { Text("\(run.failures.count)").foregroundStyle(run.failures.isEmpty ? Color.secondary : Color.red) }
                }
                if !run.failures.isEmpty {
                    Section("失败的单据") {
                        ForEach(run.failures) { failure in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(failure.name).font(.headline)
                                if !failure.reference.isEmpty { Text(failure.reference).font(.subheadline).foregroundStyle(.secondary) }
                                Text(failure.message).font(.subheadline).foregroundStyle(.red).textSelection(.enabled)
                            }
                            .contextMenu { Button("复制", systemImage: "doc.on.doc") { copyToPasteboard(failure.name + "：" + failure.message) } }
                        }
                    }
                }
            }
            .navigationTitle(run.action.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .safeAreaInset(edge: .bottom, spacing: 0) {
                HStack(spacing: 12) {
                    if run.running {
                        Button { run.stop() } label: { Text("停止").font(.headline).frame(maxWidth: .infinity, minHeight: 28) }
                            .buttonStyle(.glass).controlSize(.large).accessibilityIdentifier("batch-stop")
                    }
                    Button { dismiss() } label: { Text("完成").font(.headline).frame(maxWidth: .infinity, minHeight: 28) }
                        .buttonStyle(.glassProminent).controlSize(.large).disabled(run.running)
                        .accessibilityIdentifier("batch-done")
                }
                .padding(.horizontal, 20).padding(.vertical, 8)
            }
        }
        .interactiveDismissDisabled(true)
        .presentationDragIndicator(.hidden)
        .task { await run.start(session: session) }
    }
    private var headline: String {
        if run.running { return "正在处理…" }
        if run.endedEarly != nil { return "已结束" }
        return run.failures.isEmpty ? "全部处理完成" : "处理完成，有 \(run.failures.count) 张失败"
    }
}

/// A read-only look at one order from inside the selection. Opening and closing it changes nothing behind it.
struct BatchPeekSheet: View {
    let item: BatchItem
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            LiveWorkflowDetail(kind: item.kind, id: item.id)
                .environment(\.isPeeking, true)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() }.accessibilityIdentifier("peek-done") } }
        }
        .presentationDragIndicator(.visible)
    }
}

extension EnvironmentValues {
    /// True inside the peek sheet: the order can be read there, not changed.
    @Entry var isPeeking = false
}
