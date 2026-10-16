import SwiftUI

struct TodoInboxView: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var store: DemoStore
    var preview = false
    @State private var rows: [JSONValue] = []
    @State private var query = ""
    @State private var category = ""
    @State private var loading = false
    @State private var last = true
    @State private var error: String?
    private let categories = ["全部", "故障报修", "ERP 出库"]
    private let keys = ["", "ASSET_FAULT", "ERP_OUTBOUND"]

    var body: some View {
        List {
                if preview {
                    Section {
                        if category != "ERP_OUTBOUND" {
                            ForEach(store.faults.filter { $0.state != .finished && (query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)) }) { fault in FaultRow(fault: fault) }
                        }
                        if category != "ASSET_FAULT" {
                            ForEach(store.outbounds.filter { !$0.completed && (query.isEmpty || $0.material.name.localizedCaseInsensitiveContains(query)) }) { outbound in
                                WorkOrderCard(title: outbound.material.name + "领料", status: "待审批", reference: outbound.id, referenceTitle: "出库单号", time: outbound.date, summaryFacts: [("领用人", outbound.applicant), ("领用部门", outbound.department)], facts: [("物料名称", outbound.material.name)]) { OutboundDetailView(id: outbound.id) }
                            }
                        }
                    } footer: { DemoNotice() }
                } else {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, task in
                        let data = task["data"]
                        let kind = taskKind(task)
                        WorkOrderCard(title: title(task), status: task["suspended"].bool ? "挂起" : task["name"].text, color: kind == .erp ? approvalDisplayColor(data["approvalState"].text) : faultDisplayColor(data["faultState"].text),
                            reference: kind == .fault ? data["asset"]["assetCode"].text : data["outNumber"].text,
                            referenceTitle: kind == .fault ? "资产编码" : "出库单号", time: readableDate(task["createTime"]),
                            facts: [("流程类型", kind?.title ?? "其他流程"), ("当前节点", task["name"].text)] + (kind == .fault ? faultCardFacts(data) : erpCardFacts(data))) {
                            if let kind {
                                LiveWorkflowDetail(kind: kind, id: task["businessKey"].text)
                            } else {
                                List {
                                    LabeledContent("流程类型", value: "其他流程")
                                    LabeledContent("当前节点", value: task["name"].text)
                                    Text("此类流程暂未开放处理").foregroundStyle(.secondary)
                                }.navigationTitle("待办详情")
                            }
                        }
                    }
                    if !last { Button("加载更多") { Task { await load(more: true) } }.disabled(loading) }
                    if rows.isEmpty && !loading && error == nil { CompactEmptyState(title: "暂无待办") }
                    if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
                }
            }
        .safeAreaInset(edge: .top, spacing: 0) {
            HorizontalCategories(titles: categories, selection: Binding(get: { keys.firstIndex(of: category) ?? 0 }, set: { category = keys[$0] }))
        }
        .navigationTitle("我的待办")
        .searchPageChrome(largeTitle: true)
        .nativeSearchable(text: $query, prompt: "搜索待办名称")
        .task(id: "\(navigation.refresh)-\(category)-\(query)") { await load() }
        .refreshable { await load() }
        .overlay { AnimatedLoadingOverlay(isPresented: loading, title: "正在加载待办…") }
        .onAppear {
            navigation.newDocument = .fault; navigation.hideTabs = false
            if navigation.pendingListRefresh { navigation.pendingListRefresh = false; navigation.refresh = UUID() }
        }
        .onDisappear { navigation.newDocument = nil }
    }

    private func taskKind(_ task: JSONValue) -> WorkflowKind? {
        WorkflowKind.allSupported.first { task["processDefinitionKey"].text.hasPrefix($0.rawValue) }
    }
    private func title(_ task: JSONValue) -> String {
        let data = task["data"]
        if taskKind(task) == .fault && !data["faultName"].text.isEmpty { return data["faultName"].text }
        if taskKind(task) == .erp && !data["remark"].text.isEmpty { return data["remark"].text }
        return task["processInstanceName"].text
    }
    private func load(more: Bool = false) async {
        guard !preview else { return }
        loading = true; error = nil
        defer { loading = false }
        do {
            var filters: [JSONValue] = []
            if !category.isEmpty { filters.append(filter("processDefinitionKey", category, "startLike")) }
            if !query.isEmpty { filters.append(filter("processInstanceName", query, "like")) }
            let page = try await session.page("process/pageUserTodoTask", offset: more ? rows.count : 0, filters: filters)
            try Task.checkCancellation()
            rows = more ? rows + page["content"].array : page["content"].array
            last = page["last"].bool
        } catch is CancellationError {} catch { self.error = error.localizedDescription }
    }
}

private extension WorkflowKind {
    static let allSupported: [WorkflowKind] = [.fault, .erp]
}
