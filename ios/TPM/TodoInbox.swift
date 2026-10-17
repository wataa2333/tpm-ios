import SwiftUI

struct TodoInboxView: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var store: DemoStore
    var preview = false
    @State private var rows: [JSONValue] = []
    @State private var query = ""
    @State private var loading = false
    @State private var loadingMore = false
    @State private var refreshing = false
    @State private var last = true
    @State private var total: Int?
    @StateObject private var gate = ReloadGate()
    @StateObject private var batch = BatchSelection()
    @State private var error: String?

    var body: some View {
        CardPage {
            LoadingRow(isPresented: (loading || gate.busy) && !refreshing, title: "正在加载待办")
            if preview {
                CardGroup {
                    ForEach(store.faults.filter { $0.state != .finished && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) }) { fault in FaultRow(fault: fault) }
                    ForEach(store.outbounds.filter { !$0.completed && (query.isEmpty || $0.material.name.localizedCaseInsensitiveContains(query)) }) { outbound in
                        WorkOrderCard(title: outbound.material.name + "领料", status: "待审批", reference: outbound.id, referenceTitle: "出库单号", time: outbound.date, summaryFacts: [("领用人", outbound.applicant), ("领用部门", outbound.department)], facts: [("物料名称", outbound.material.name)]) { OutboundDetailView(id: outbound.id) }
                    }
                }
                DemoNotice()
            } else {
                CardGroup {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, task in
                        let entry = batchItem(todo: task)
                        TodoCardRow(task: task, selectionMode: batch.active, selected: entry.map { batch.contains($0.id) } ?? false,
                                    selectable: entry.map { batch.isSelectable($0) } ?? false, batch: batch).equatable()
                    }
                    if rows.isEmpty && !loading && error == nil { CompactEmptyState(title: "暂无待办") }
                }.opacity((loading || gate.busy) && !loadingMore ? 0.4 : 1).animation(.easeInOut(duration: 0.4), value: loading || gate.busy)
                LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || loadingMore, loaded: rows.count, total: total) { await load(more: true) }
            }
        }
        .navigationTitle("我的待办")
        .modifier(ListTabVisibility())
        .searchPageChrome(largeTitle: true)
        .nativeSearchable(text: $query, prompt: "搜索待办名称")
        .reloadsToTop(on: gate.id("\(navigation.refresh)-\(query)"))
        .animation(.smooth(duration: 0.45), value: loading || gate.busy)
        .rootTopBar(preview: preview, busy: loading || loadingMore || gate.busy) { gate.request() }
        .modifier(TodoBatchHost(enabled: !preview, batch: batch) { rows.compactMap { batchItem(todo: $0) } })
        .errorDialog($error, retry: { Task { await load() } })
        .task(id: gate.id("\(navigation.refresh)-\(query)")) { await gate.run(key: "\(navigation.refresh)-\(query)", debounce: query.isEmpty ? nil : .milliseconds(350), load: { await load() }) }
        .refreshable { refreshing = true; await load(); refreshing = false }
        .onAppear {
            navigation.newDocument = .fault; navigation.hideTabs = false
            if navigation.pendingListRefresh { navigation.pendingListRefresh = false; navigation.refresh = UUID() }
        }
        .onDisappear { navigation.newDocument = nil }
    }

    private func load(more: Bool = false) async {
        guard !preview else { return }
        if more { loadingMore = true } else { loading = true }
        error = nil
        defer { loading = false; loadingMore = false }
        let snapshot = query
        do {
            var filters: [JSONValue] = []
            if !query.isEmpty { filters.append(filter("processInstanceName", query, "like")) }
            let page = try await session.page("process/pageUserTodoTask", offset: more ? rows.count : 0, filters: filters)
            try Task.checkCancellation()
            guard snapshot == query else { return }
            rows = more ? rows + page["content"].array : page["content"].array
            last = page["last"].bool
            total = serverPageCount(page)
            if query.isEmpty, !more, let total { navigation.todoCount = total }
        } catch is CancellationError {}
        catch let urlError as URLError where urlError.code == .cancelled {}
        catch { self.error = error.localizedDescription }
    }
}

/// The selection mode of the todo list. In the demo screens it is left out.
private struct TodoBatchHost: ViewModifier {
    let enabled: Bool
    let batch: BatchSelection
    let candidates: () -> [BatchItem]
    func body(content: Content) -> some View {
        if enabled { content.batchHost(batch, candidates: candidates) } else { content }
    }
}

/// One todo card. It compares by value, so the page's own state changes do not rebuild every card.
struct TodoCardRow: View, Equatable {
    let task: JSONValue
    var selectionMode = false
    var selected = false
    var selectable = true
    var batch: BatchSelection? = nil
    @EnvironmentObject private var session: LiveSession
    nonisolated static func == (a: TodoCardRow, b: TodoCardRow) -> Bool {
        a.task == b.task && a.selectionMode == b.selectionMode && a.selected == b.selected && a.selectable == b.selectable
    }
    var body: some View {
        let data = task["data"]
        let kind = todoTaskKind(task)
        let entry = batchItem(todo: task)
        WorkOrderCard(title: todoTitle(task), status: todoStatusName(task, kind: kind, data: data), color: kind == .erp ? approvalDisplayColor(data["approvalState"].text) : faultDisplayColor(data["faultState"].text),
            reference: kind == .fault ? data["asset"]["assetCode"].text : data["outNumber"].text,
            referenceTitle: kind == .fault ? "资产编码" : "出库单号",
            machine: kind == .fault ? data["asset"]["addressCode"].text : "", time: readableDate(task["createTime"]),
            statusNote: [],
            facts: [("流程类型", kind?.title ?? "其他流程"), ("当前节点", task["name"].text)] + (kind == .fault ? faultCardFacts(data) : erpCardFacts(data)),
            selecting: selectionMode ? batch.map { batch in
                CardSelecting(selected: selected, enabled: selectable,
                              toggle: { if let entry { batch.toggle(entry) } else { batch.notice = "这类待办暂不支持批量处理" } },
                              peek: { if let entry { batch.peeking = entry } else { batch.notice = "此类流程暂未开放查看" } })
            } : nil) {
            if let kind {
                LiveWorkflowDetail(kind: kind, id: task["businessKey"].text)
            } else {
                List {
                    LabeledContent("流程类型", value: "其他流程")
                    LabeledContent("当前节点", value: task["name"].text)
                    Text("此类流程暂未开放处理").foregroundStyle(.secondary)
                }.navigationTitle("待办详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            }
        }
    }
}

func todoTaskKind(_ task: JSONValue) -> WorkflowKind? {
    WorkflowKind.allSupported.first { task["processDefinitionKey"].text.hasPrefix($0.rawValue) }
}
/// The card's status is the order's business state ("待完成", "待签到"), not the name of the
/// workflow node ("完成任务"); the node name stays in the card's expanded "当前节点" line.
func todoStatusName(_ task: JSONValue, kind: WorkflowKind?, data: JSONValue) -> String {
    if task["suspended"].bool { return "挂起" }
    let named = kind == .erp ? approvalStateName(data["approvalState"].text) : faultStateLabel(data)
    return named.isEmpty || named == "状态待核对" || named == "全部" ? task["name"].text : named
}
func todoTitle(_ task: JSONValue) -> String {
    let data = task["data"]
    if todoTaskKind(task) == .fault && !data["faultName"].text.isEmpty { return data["faultName"].text }
    if todoTaskKind(task) == .erp && !data["remark"].text.isEmpty { return data["remark"].text }
    return task["processInstanceName"].text
}

extension WorkflowKind {
    static let allSupported: [WorkflowKind] = [.fault, .erp]
}
