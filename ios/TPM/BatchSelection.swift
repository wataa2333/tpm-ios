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
    /// The other way out of the step: a sent-back order ("退单") or a rejected one ("驳回").
    var rejectWord: String { self == .claim || self == .signIn || self == .complete ? "退单" : "驳回" }
    var rejectTitle: String { "批量" + rejectWord }
    var rejectSymbol: String { "arrow.uturn.backward" }

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

/// What the batch does to the selected orders: take them forward, or send them back with a reason.
enum BatchOperation: Equatable { case proceed, reject }

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

/// What the repair is answered with, once for the whole batch, exactly the fields the single screens ask for:
/// - accept ("受理"): the repair group and the date it should be done by; a repairer only to assign it to one;
/// - complete ("完成任务"): the repair's fault type, the work description and how long it took;
/// - every step that hands over to a next node the person must name: the approver of that node.
/// Claim, sign-in and plain approvals ask for nothing else.
struct BatchMaterial: Equatable, Identifiable {
    var material: JSONValue
    var quantity: Double = 1
    var id: String { material["id"].text }
}

/// An existing outbound line (picked from the outbound orders) that every order of the batch links to the same way.
struct BatchOutboundLine: Equatable, Identifiable {
    var line: JSONValue
    var quantity: Double = 1
    var id: String { outboundLineIdentity(line) }
}

struct BatchPreset: Equatable {
    /// Complete only: the fault type of the repair result. (Accepting keeps each order's own fault type.)
    var faultType: JSONValue = .null
    /// Accept: the repair group and the date the repair should be done by, and optionally one repairer.
    var group: JSONValue = .null
    var estimated: JSONValue = .string(defaultEstimatedTime())
    var assignUser: JSONValue = .null
    /// Complete: the work description, and how long the work took in minutes (empty: counted from the sign-in).
    var content = ""
    var durationMinutes: Int?
    /// The next nodes whose approver is chosen by the person, and the chosen user names per node variable.
    var approverNodes: [Fields] = []
    var approvers: [String: [String]] = [:]
    /// The reason written on every order that is sent back or rejected.
    var comment = ""
    /// Complete: ERP material taken for every order, the same material and quantity each. Each order gets its
    /// own outbound order (linked to the repair), made with these answers and the ERP form's own defaults.
    var materials: [BatchMaterial] = []
    /// Complete: lines of existing outbound orders, used by every order in the same quantity.
    var outboundLines: [BatchOutboundLine] = []
    var erpData: Fields = [:]
    var erpProject: JSONValue = .null
    var erpRemark = ""
    var erpRecipient: JSONValue = .null
    var erpDept: JSONValue = .null
    var erpDivision: JSONValue = .null
    var erpNodes: [Fields] = []
    var erpApprovers: [String: [String]] = [:]

    /// Assigning a repairer ends the accept step in "指派", which names no approver.
    func needsApprovers(for action: BatchAction) -> Bool {
        !approverNodes.isEmpty && !(action == .accept && !assignUser.object.isEmpty)
    }

    func isComplete(for action: BatchAction, operation: BatchOperation = .proceed) -> Bool {
        if operation == .reject { return !comment.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
        switch action {
        case .accept:
            if estimated.text.isEmpty { return false }
        case .complete:
            if faultType.object.isEmpty || content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (durationMinutes ?? 1) < 1 { return false }
            if outboundLines.contains(where: { $0.quantity < 1 }) { return false }
            if !materials.isEmpty {
                if materials.contains(where: { $0.quantity < 1 }) { return false }
                if erpProject.object.isEmpty || erpRemark.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return false }
                if erpRecipient.object.isEmpty || erpDept.object.isEmpty || erpDivision.object.isEmpty { return false }
                if missingAssigneeMessage(nodes: erpNodes, selections: erpApprovers) != nil { return false }
            }
        default: break
        }
        if needsApprovers(for: action), missingAssigneeMessage(nodes: approverNodes, selections: approvers) != nil { return false }
        return true
    }
}

/// What the preset sheet opens with: the approver nodes read from the first selected order.
struct BatchPresetRequest: Identifiable {
    let id = UUID()
    let action: BatchAction
    let operation: BatchOperation
    let kind: WorkflowKind
    let nodes: [Fields]
    let selections: [String: [String]]
    /// "批量完成" only: the ERP order form as it opens, when it could be read.
    let erp: (data: Fields, nodes: [Fields], selections: [String: [String]])?
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
    let operation: BatchOperation
    let items: [BatchItem]
    let preset: BatchPreset
    @Published private(set) var processed = 0
    @Published private(set) var succeeded: [String] = []
    @Published private(set) var failures: [BatchFailure] = []
    /// Orders the person chose to skip when a problem came up (not enough material, ...).
    @Published private(set) var skipped: [BatchFailure] = []
    @Published private(set) var current: BatchItem?
    @Published private(set) var running = false
    @Published private(set) var finished = false
    /// Set when the run ended before its last order, and why.
    @Published private(set) var endedEarly: String?
    /// A question for the person (not enough stock, ...). The run waits for the answer.
    @Published private(set) var problem: BatchProblem?
    private var waiting: CheckedContinuation<BatchChoice, Never>?
    /// "后面遇到同样的情况都这样处理".
    private var standing: BatchChoice?
    private var stopRequested = false

    init(action: BatchAction, operation: BatchOperation, items: [BatchItem], preset: BatchPreset) {
        self.action = action; self.operation = operation; self.items = items; self.preset = preset
    }
    var title: String { operation == .reject ? action.rejectTitle : action.title }
    var total: Int { items.count }
    var remaining: Int { total - processed }

    func stop() {
        stopRequested = true
        if waiting != nil { answer(.stop, applyToRest: false) }
    }

    func ask(_ question: BatchProblem) async -> BatchChoice {
        if let standing { return standing }
        problem = question
        return await withCheckedContinuation { waiting = $0 }
    }
    func answer(_ choice: BatchChoice, applyToRest: Bool) {
        if applyToRest && choice != .stop { standing = choice }
        problem = nil
        waiting?.resume(returning: choice)
        waiting = nil
    }

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
                try await BatchProcessor.process(item, action: action, operation: operation, preset: preset, session: session, ask: { [self] in await ask($0) })
                succeeded.append(item.id)
            } catch let skip as BatchSkipped {
                skipped.append(BatchFailure(id: item.id, name: item.name, reference: item.reference, message: skip.message))
            } catch is BatchStopped {
                endedEarly = "已停止，还有 \(remaining) 张没有处理。"; break
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
    @EnvironmentObject private var owner: LiveSession
    @State private var operationSession: LiveSession?
    @State private var choosingOperationAccount = false
    private var session: LiveSession { operationSession ?? owner }
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var presetting: BatchPresetRequest?
    @State private var preparing = false
    @State private var pendingRun: BatchRun?
    @State private var run: BatchRun?
    @State private var finishing: BatchRun?

    func body(content: Content) -> some View {
        content
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    if batch.active { Button("取消") { batch.end() }.disabled(preparing).accessibilityIdentifier("batch-cancel") }
                    else {
                        Button("多选", systemImage: "checkmark.circle") { batch.begin() }
                            .labelStyle(.iconOnly)
                            .accessibilityIdentifier("batch-select")
                    }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                if batch.active {
                    BatchBar(batch: batch, busy: preparing, onSelectAll: { batch.selectAll(candidates()) }, onRun: { begin($0) }) {
                        Button("本次操作账号", systemImage: operationSession == nil ? "person.crop.circle" : "person.crop.circle.badge.checkmark") {
                            choosingOperationAccount = true
                        }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.glass)
                        .accessibilityValue(session.authentication.text("name"))
                        .disabled(preparing || presetting != nil || run != nil)
                    }
                        .transition(.opacity)
                }
            }
            .interfaceMotion(batch.active)
            .onChange(of: batch.active) { _, active in
                navigation.selectingBatch = active
                if !active { operationSession = nil }
            }
            .onDisappear { if batch.active { batch.end() }; navigation.selectingBatch = false; operationSession = nil }
            .sheet(isPresented: $choosingOperationAccount) {
                OperationAccountPicker(owner: owner, operationSession: $operationSession)
            }
            .toast($batch.notice)
            .sheet(item: $presetting, onDismiss: {
                if let started = pendingRun { pendingRun = nil; finishing = started; run = started }
            }) { request in
                BatchPresetSheet(request: request, count: batch.items.count) { preset in
                    pendingRun = BatchRun(action: request.action, operation: request.operation, items: batch.items, preset: preset)
                    presetting = nil
                }.environmentObject(session)
            }
            .sheet(item: $run, onDismiss: finishRun) { run in
                BatchRunSheet(run: run).environmentObject(session)
            }
            .sheet(item: $batch.peeking) { item in
                BatchPeekSheet(item: item)
            }
    }

    /// Reads, from the first selected order, who the next node's approver has to be, then opens the preset
    /// sheet when anything has to be answered (accept and complete always; the others only if an approver
    /// is missing). Otherwise the run starts at once.
    private func begin(_ operation: BatchOperation) {
        guard let action = batch.action, let first = batch.items.first, !preparing else { return }
        // Sending back needs only the reason: no approvers, no other answers.
        if operation == .reject {
            presetting = BatchPresetRequest(action: action, operation: .reject, kind: first.kind, nodes: [], selections: [:], erp: nil)
            return
        }
        preparing = true
        let session = session
        let items = batch.items
        let ownerScope = owner.scopeRevision
        Task {
            defer { preparing = false }
            let setup: (nodes: [Fields], selections: [String: [String]])
            do { setup = try await BatchProcessor.approverSetup(first, session: session) }
            catch {
                if !isCancellation(error) { batch.notice = "读取审批人失败：" + error.localizedDescription }
                return
            }
            let erp = action == .complete ? await BatchProcessor.erpSetup(session: session) : nil
            guard batch.active, items == batch.items, session === self.session,
                  ownerScope == owner.scopeRevision else { return }
            let choosable = setup.nodes.filter { ["ASSIGN", "SUPERVISOR"].contains($0.text("assignType")) }
            if action.needsPreset || missingAssigneeMessage(nodes: choosable, selections: setup.selections) != nil {
                presetting = BatchPresetRequest(action: action, operation: .proceed, kind: first.kind, nodes: choosable, selections: setup.selections, erp: erp)
            } else {
                let started = BatchRun(action: action, operation: .proceed, items: batch.items, preset: BatchPreset())
                finishing = started; run = started
            }
        }
    }
    /// The orders that went through leave the selection; the ones that failed stay selected for another try.
    private func finishRun() {
        guard let done = finishing else { return }
        finishing = nil
        operationSession = nil
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

/// The bar at the bottom of a list in selection mode. It sits above the tab bar, on its own bar material:
/// the count and the status on one line, the action as a full-width button below.
struct BatchBar<AccountAction: View>: View {
    @ObservedObject var batch: BatchSelection
    var busy = false
    let onSelectAll: () -> Void
    let onRun: (BatchOperation) -> Void
    @ViewBuilder let accountAction: () -> AccountAction
    var body: some View {
        VStack(spacing: 10) {
            HStack(spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("已选 \(batch.items.count)/\(BatchSelection.limit)").font(AppFont.headline).monospacedDigit()
                        .contentTransition(.numericText())
                    Text(batch.groupTitle).animatedTextValue(batch.groupTitle).font(AppFont.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                accountAction()
                Menu {
                    Button("全选已加载的单据", systemImage: "checkmark.circle", action: onSelectAll)
                    Button("清空已选", systemImage: "circle", role: .destructive) { batch.clear() }
                } label: { Text("全选").font(AppFont.subheadline.weight(.medium)).padding(.horizontal, 8).frame(minHeight: 28) }
                    .buttonStyle(.glass).accessibilityIdentifier("batch-select-all")
            }
            if let action = batch.action {
                HStack(spacing: 10) {
                    Button { onRun(.reject) } label: {
                        Label(action.rejectTitle, systemImage: action.rejectSymbol).animatedTextValue(action.rejectTitle).font(AppFont.headline).lineLimit(1).frame(maxWidth: .infinity, minHeight: 28)
                    }
                    .buttonStyle(.glass).tint(.red).controlSize(.large)
                    .disabled(batch.items.isEmpty || busy)
                    .accessibilityIdentifier("batch-reject")
                    Button { onRun(.proceed) } label: {
                        HStack(spacing: 8) {
                            Group { if busy { ProgressView() } else { Image(systemName: action.symbol) } }
                                .frame(width: 20, height: 20)
                                .interfaceMotion(busy, duration: 0.2)
                            Text(action.title).animatedTextValue(action.title).lineLimit(1)
                        }
                        .font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28)
                    }
                    .buttonStyle(.glassProminent).controlSize(.large)
                    .disabled(batch.items.isEmpty || busy)
                    .accessibilityIdentifier("batch-run")
                }
                .transition(.opacity)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(maxWidth: .infinity)
        .background(.bar)
        .overlay(alignment: .top) { Divider() }
        .interfaceMotion(batch.action)
        .interfaceMotion(batch.items.count, duration: 0.2)
    }
}

/// The answers every order of the batch gets. Chosen once, applied to each order as the single form would.
struct BatchPresetSheet: View {
    let request: BatchPresetRequest
    let count: Int
    let start: (BatchPreset) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var preset: BatchPreset
    @State private var choosingMaterial = false
    @State private var choosingOutbound = false
    private var action: BatchAction { request.action }
    private var operation: BatchOperation { request.operation }

    init(request: BatchPresetRequest, count: Int, start: @escaping (BatchPreset) -> Void) {
        self.request = request; self.count = count; self.start = start
        var initial = BatchPreset()
        initial.approverNodes = request.nodes
        initial.approvers = request.selections
        if let erp = request.erp {
            initial.erpData = erp.data
            initial.erpRecipient = erp.data["recipientUser"] ?? .null
            initial.erpDept = erp.data["dept"] ?? .null
            initial.erpDivision = erp.data["division"] ?? .null
            initial.erpNodes = erp.nodes
            initial.erpApprovers = erp.selections
        }
        _preset = State(initialValue: initial)
    }

    private func quantity(_ index: Int) -> Binding<Double> {
        Binding(get: { preset.materials[index].quantity }, set: { preset.materials[index].quantity = $0 })
    }
    private func outboundQuantity(_ index: Int) -> Binding<Double> {
        Binding(get: { preset.outboundLines[index].quantity }, set: { preset.outboundLines[index].quantity = $0 })
    }
    private func addOutbound(_ line: JSONValue) {
        if preset.outboundLines.contains(where: { $0.id == outboundLineIdentity(line) }) {
            preset.outboundLines.removeAll { $0.id == outboundLineIdentity(line) }; return
        }
        preset.outboundLines.append(BatchOutboundLine(line: line))
    }
    private func addMaterial(_ material: JSONValue) {
        if preset.materials.contains(where: { $0.material["id"] == material["id"] }) {
            preset.materials.removeAll { $0.material["id"] == material["id"] }; return
        }
        preset.materials.append(BatchMaterial(material: material))
    }

    var body: some View {
        NavigationStack {
            Form {
                if operation == .reject {
                    Section {
                        EntryField(placeholder: "请输入" + action.rejectWord + "原因", text: $preset.comment, limit: 255, axis: .vertical, lines: 3...8)
                    } header: { RequiredLabel(title: action.rejectWord + "原因") } footer: {
                        Text("所选 \(count) 张单据都会" + action.rejectWord + "，并写上这条原因。").animatedTextValue("所选 \(count) 张单据都会" + action.rejectWord + "，并写上这条原因。")
                    }
                } else {
                    proceedSections
                }
            }
            .navigationTitle(operation == .reject ? action.rejectTitle : action.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } } }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                Button(role: operation == .reject ? .destructive : nil) { start(preset) } label: {
                    Text("开始处理 \(count) 张").animatedTextValue("开始处理 \(count) 张").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28)
                }
                .buttonStyle(.glassProminent).controlSize(.large)
                .tint(operation == .reject ? .red : .accentColor)
                .disabled(!preset.isComplete(for: action, operation: operation))
                .padding(.horizontal, 20).padding(.vertical, 8)
                .accessibilityIdentifier("batch-start")
            }
            .keyboardManaged()
            .sheet(isPresented: $choosingOutbound) { NavigationStack { ReferencePicker(source: .outboundMaterials, multiple: true, selected: preset.outboundLines.map(\.id)) { addOutbound($0) } } }
            .sheet(isPresented: $choosingMaterial) { NavigationStack { ReferencePicker(source: .materials, multiple: true, selected: preset.materials.map(\.id)) { addMaterial($0) } } }
        }
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder private var proceedSections: some View {
        if action == .accept {
            Section {
                ReferenceField(title: "维修班组（不选则用各单自带）", source: .groups, value: $preset.group, required: false)
                ServerDateField(title: "预计完成时间", value: $preset.estimated, required: true)
                ReferenceField(title: "维修人员（选填）", source: .users, value: $preset.assignUser)
            } header: { RequiredFormHeader(title: "受理方案") } footer: {
                Text("所选 \(count) 张单据都按这个方案受理，各单的故障类型不变。不选维修人员就是派单，选了就按“指派”处理。").animatedTextValue("所选 \(count) 张单据都按这个方案受理，各单的故障类型不变。不选维修人员就是派单，选了就按“指派”处理。")
            }
        }
        if action == .complete {
            Section {
                ReferenceField(title: "维修故障类型", source: .faultTypes, value: $preset.faultType, required: true)
            } header: { RequiredFormHeader(title: "维修结果") } footer: {
                Text("所选 \(count) 张单据都按这个方案提交维修结果；每张单据原有的维修领料不变。").animatedTextValue("所选 \(count) 张单据都按这个方案提交维修结果；每张单据原有的维修领料不变。")
            }
            Section { EntryField(placeholder: "请输入工作说明", text: $preset.content, limit: 255, axis: .vertical, lines: 3...8) } header: { RequiredLabel(title: "工作说明") }
            Section {
                HStack {
                    Text("耗时（分钟）")
                    Spacer()
                    TextField("留空则从签到时间算起", value: $preset.durationMinutes, format: .number).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                }
            } footer: { Text("填写后，每张单据的维修开始时间 = 现在往前推这么多分钟，结束时间为现在。") }
            outboundSection
            erpSections
        }
        if preset.needsApprovers(for: action) {
            Section {
                AssigneesFields(elements: preset.approverNodes, selections: $preset.approvers, defaultPrefix: "assignee.task." + request.kind.rawValue)
            } header: { Text("审批人") } footer: {
                Text("下一步的审批人，所选单据都用这里选的人。")
            }
        }
    }

    /// Existing outbound lines: every order links the same lines, each in the same quantity. When a line has
    /// less left than that, the run asks what to do for that order.
    @ViewBuilder private var outboundSection: some View {
        Section {
            FullRowButton("添加已出库物料", systemImage: "shippingbox") { choosingOutbound = true }
            ForEach(Array(preset.outboundLines.enumerated()), id: \.element.id) { index, entry in
                let left = entry.line["actualQuantity"].number - entry.line["usedQuantity"].number
                VStack(alignment: .leading, spacing: RowMetrics.spacing) {
                    WrappingContent { Text(entry.line["material"]["materialDesc"].text).animatedTextValue(entry.line["material"]["materialDesc"].text).font(AppFont.headline) }
                    WrappingContent { Text([entry.line["outbound"]["outNumber"].text, entry.line["material"]["materialCode"].text, "现可用 " + materialQuantity(String(left), material: entry.line["material"])].filter { !$0.isEmpty }.joined(separator: " · ")).animatedTextValue([entry.line["outbound"]["outNumber"].text, entry.line["material"]["materialCode"].text, "现可用 " + materialQuantity(String(left), material: entry.line["material"])].filter { !$0.isEmpty }.joined(separator: " · ")).font(AppFont.subheadline).foregroundStyle(.secondary) }
                    HStack { Text("每单使用数量"); Spacer(); TextField("数量", value: outboundQuantity(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 70); Text(materialUnit(entry.line["material"])).animatedTextValue(materialUnit(entry.line["material"])).foregroundStyle(.secondary) }
                }
                .swipeActions { Button("删除", role: .destructive) { preset.outboundLines.remove(at: index) } }
            }
        } header: { Text("关联已有出库物料（选填）") } footer: {
            Text("所选每张单据都使用这些出库物料、同样的数量。可用数量不够时会弹窗，让你选择跳过这张单、不领料继续或停止。")
        }
    }

    /// Every order takes the same material in the same quantity: each gets its own ERP outbound order.
    @ViewBuilder private var erpSections: some View {
        if request.erp == nil {
            Section { Text("没能读取 ERP 领料设置，这次不能批量领料。").font(AppFont.footnote).foregroundStyle(.secondary) } header: { Text("ERP 领料") }
        } else {
            Section {
                FullRowButton("添加领料物料", systemImage: "plus") { choosingMaterial = true }
                ForEach(Array(preset.materials.enumerated()), id: \.element.id) { index, line in
                    VStack(alignment: .leading, spacing: RowMetrics.spacing) {
                        WrappingContent { Text(line.material["materialDesc"].text).animatedTextValue(line.material["materialDesc"].text).font(AppFont.headline) }
                        WrappingContent { Text([line.material["materialCode"].text, "库存 " + materialQuantity(line.material["onhandQty"].text, material: line.material)].filter { !$0.isEmpty }.joined(separator: " · ")).animatedTextValue([line.material["materialCode"].text, "库存 " + materialQuantity(line.material["onhandQty"].text, material: line.material)].filter { !$0.isEmpty }.joined(separator: " · ")).font(AppFont.subheadline).foregroundStyle(.secondary) }
                        HStack { Text("每单数量"); Spacer(); TextField("数量", value: quantity(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 70); Text(materialUnit(line.material)).animatedTextValue(materialUnit(line.material)).foregroundStyle(.secondary) }
                    }
                    .swipeActions { Button("删除", role: .destructive) { preset.materials.remove(at: index) } }
                }
            } header: { Text("ERP 领料（选填）") } footer: {
                Text("所选每张单据都领用这些物料、同样的数量：为每张单据各建一张出库单并关联到维修。处理中发现账上物料不够或其他问题，会弹窗让你选择跳过、不领料继续或停止。")
            }
            if !preset.materials.isEmpty {
                Section {
                    ReferenceField(title: "项目号", source: .projects, value: $preset.erpProject, required: true)
                    ReferenceField(title: "领用人", source: .users, value: $preset.erpRecipient, required: true, defaultField: "erp.recipientUser")
                    ReferenceField(title: "领用部门", source: .departments, value: $preset.erpDept, required: true, defaultField: "erp.dept")
                    ReferenceField(title: "ERP记账部门", source: .divisions, value: $preset.erpDivision, required: true, defaultField: "erp.division")
                    EntryField(placeholder: "请输入用途", text: $preset.erpRemark, limit: 140, axis: .vertical, lines: 2...4)
                } header: { RequiredFormHeader(title: "领料单信息") }
                if preset.erpNodes.contains(where: { ["ASSIGN", "SUPERVISOR"].contains($0.text("assignType")) }) {
                    Section { AssigneesFields(elements: preset.erpNodes, selections: $preset.erpApprovers, defaultPrefix: "assignee.erp") } header: { Text("领料单审批人") }
                }
            }
        }
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
                            Text(headline).animatedTextValue(headline).font(AppFont.subheadline.weight(.medium))
                            Spacer()
                            Text("\(run.processed)/\(run.total)").font(AppFont.subheadline).monospacedDigit().foregroundStyle(.secondary)
                                .contentTransition(.numericText()).interfaceMotion(run.processed)
                        }
                    }.padding(.vertical, 4)
                    if let current = run.current {
                        LabeledContent("正在处理") { Text(current.name + (current.reference.isEmpty ? "" : " · " + current.reference)).animatedTextValue(current.name + (current.reference.isEmpty ? "" : " · " + current.reference)) }
                    }
                    if let early = run.endedEarly { Text(early).animatedTextValue(early).font(AppFont.subheadline).foregroundStyle(.orange) }
                }
                Section("统计") {
                    LabeledContent("全部") { Text("\(run.total)").animatedTextValue("\(run.total)") }
                    LabeledContent("已处理") { Text("\(run.processed)").monospacedDigit().contentTransition(.numericText()).interfaceMotion(run.processed) }
                    LabeledContent("完成") { Text("\(run.succeeded.count)").foregroundStyle(.green).monospacedDigit().contentTransition(.numericText()).interfaceMotion(run.succeeded.count) }
                    LabeledContent("跳过") { Text("\(run.skipped.count)").foregroundStyle(run.skipped.isEmpty ? Color.secondary : Color.orange).monospacedDigit().contentTransition(.numericText()).interfaceMotion(run.skipped.count) }
                    LabeledContent("失败") { Text("\(run.failures.count)").foregroundStyle(run.failures.isEmpty ? Color.secondary : Color.red).monospacedDigit().contentTransition(.numericText()).interfaceMotion(run.failures.count) }
                }
                if !run.skipped.isEmpty {
                    Section("跳过的单据") {
                        ForEach(run.skipped) { skip in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(skip.name).animatedTextValue(skip.name).font(AppFont.headline)
                                if !skip.reference.isEmpty { Text(skip.reference).animatedTextValue(skip.reference).font(AppFont.subheadline).foregroundStyle(.secondary) }
                                Text(skip.message).animatedTextValue(skip.message).font(AppFont.subheadline).foregroundStyle(.orange).textSelection(.enabled)
                            }
                        }
                    }
                }
                if !run.failures.isEmpty {
                    Section("失败的单据") {
                        ForEach(run.failures) { failure in
                            VStack(alignment: .leading, spacing: 6) {
                                Text(failure.name).animatedTextValue(failure.name).font(AppFont.headline)
                                if !failure.reference.isEmpty { Text(failure.reference).animatedTextValue(failure.reference).font(AppFont.subheadline).foregroundStyle(.secondary) }
                                Text(failure.message).animatedTextValue(failure.message).font(AppFont.subheadline).foregroundStyle(.red).textSelection(.enabled)
                            }
                        }
                    }
                }
            }
            .interfaceMotion(run.running)
            .interfaceMotion(run.skipped.count)
            .interfaceMotion(run.failures.count)
            .navigationTitle(run.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .safeAreaInset(edge: .bottom, spacing: 0) {
                HStack(spacing: 12) {
                    if run.running {
                        Button { run.stop() } label: { Text("停止").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
                            .buttonStyle(.glass).controlSize(.large).accessibilityIdentifier("batch-stop")
                            .transition(.opacity)
                    }
                    Button { dismiss() } label: { Text("完成").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
                        .buttonStyle(.glassProminent).controlSize(.large).disabled(run.running)
                        .accessibilityIdentifier("batch-done")
                }
                .padding(.horizontal, 20).padding(.vertical, 8)
                .interfaceMotion(run.running)
            }
        }
        .interactiveDismissDisabled(true)
        .presentationDragIndicator(.hidden)
        .sheet(item: Binding(get: { run.problem }, set: { _ in })) { problem in
            BatchProblemSheet(problem: problem) { choice, rest in run.answer(choice, applyToRest: rest) }
        }
        .task { await run.start(session: session) }
    }
    private var headline: String {
        if run.running { return "正在处理…" }
        if run.endedEarly != nil { return "已结束" }
        let parts = [run.skipped.isEmpty ? nil : "\(run.skipped.count) 张跳过", run.failures.isEmpty ? nil : "\(run.failures.count) 张失败"].compactMap { $0 }
        return parts.isEmpty ? "全部处理完成" : "处理完成，有 " + parts.joined(separator: "、")
    }
}

/// A read-only look at one order from inside the selection. Opening and closing it changes nothing behind it.
struct BatchPeekSheet: View {
    let item: BatchItem
    @Environment(\.queryReading) private var queryReading
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            LiveWorkflowDetail(kind: item.kind, id: item.id, usesQueryAccount: queryReading)
                .environment(\.isPeeking, true)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() }.accessibilityIdentifier("peek-done") } }
        }
        .presentationDragIndicator(.visible)
    }
}

extension EnvironmentValues {
    /// True inside the peek sheet: the order can be read there, not changed.
    @Entry var isPeeking = false
    /// Carries only the read routing flag; the operator LiveSession is never replaced.
    @Entry var queryReading = false
}

/// A question in the middle of a run. It cannot be swiped away: the run is waiting for an answer.
struct BatchProblemSheet: View {
    let problem: BatchProblem
    let answer: (BatchChoice, Bool) -> Void
    @State private var applyToRest = false
    var body: some View {
        VStack(spacing: 18) {
            VStack(spacing: 8) {
                Text(problem.title).animatedTextValue(problem.title).font(AppFont.title3.weight(.semibold))
                Text(problem.order.name + (problem.order.reference.isEmpty ? "" : " · " + problem.order.reference)).animatedTextValue(problem.order.name + (problem.order.reference.isEmpty ? "" : " · " + problem.order.reference)).font(AppFont.subheadline).foregroundStyle(.secondary)
                ScrollView { Text(problem.message).animatedTextValue(problem.message).font(AppFont.body).multilineTextAlignment(.center).textSelection(.enabled).frame(maxWidth: .infinity) }
                    .frame(maxHeight: 220)
            }.multilineTextAlignment(.center)
            Toggle("后面遇到同样的情况，都这样处理", isOn: $applyToRest).font(AppFont.subheadline)
            VStack(spacing: 10) {
                Button { answer(.withoutMaterials, applyToRest) } label: { Text("不领料，继续完成这张单").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
                    .buttonStyle(.glassProminent).controlSize(.large).accessibilityIdentifier("problem-without")
                Button { answer(.skip, applyToRest) } label: { Text("跳过这张单").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
                    .buttonStyle(.glass).controlSize(.large).accessibilityIdentifier("problem-skip")
                Button(role: .destructive) { answer(.stop, false) } label: { Text("停止批量处理").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28) }
                    .buttonStyle(.glass).controlSize(.large).accessibilityIdentifier("problem-stop")
            }
        }
        .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 16)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.hidden)
        .interactiveDismissDisabled(true)
    }
}
