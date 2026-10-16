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
    @State private var loadingMore = false
    @State private var refreshing = false
    @State private var last = true
    @State private var total: Int?
    @State private var counts: [String: Int] = [:]
    @State private var error: String?
    private let categories = ["全部", "故障报修", "ERP 出库"]
    private let keys = ["", "ASSET_FAULT", "ERP_OUTBOUND"]

    var body: some View {
        List {
            ChipStrip(items: categories.indices.map { .init(id: $0, title: categories[$0], count: counts[keys[$0]], showsCount: !preview) },
                      selection: Binding(get: { keys.firstIndex(of: category) ?? 0 }, set: { category = keys[$0] }))
            LoadingRow(isPresented: loading && !refreshing, title: "正在加载待办")
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
                        WorkOrderCard(title: title(task), status: statusName(task, kind: kind, data: data), color: kind == .erp ? approvalDisplayColor(data["approvalState"].text) : faultDisplayColor(data["faultState"].text),
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
                                }.navigationTitle("待办详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
                            }
                        }
                    }
                    if rows.isEmpty && !loading && error == nil { CompactEmptyState(title: "暂无待办") }
                    LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || loadingMore, loaded: rows.count, total: total) { await load(more: true) }
                }
            }
        .navigationTitle("我的待办")
        .searchPageChrome(largeTitle: true)
        .nativeSearchable(text: $query, prompt: "搜索待办名称")
        .reloadsToTop(on: "\(navigation.refresh)-\(category)-\(query)")
        .errorDialog($error, retry: { Task { await load() } })
        .task(id: "\(navigation.refresh)-\(category)-\(query)") { await load() }
        .task(id: "counts-\(navigation.refresh)-\(query)") { await loadCounts() }
        .refreshable { refreshing = true; await load(); await loadCounts(); refreshing = false }
        .onAppear {
            navigation.newDocument = .fault; navigation.hideTabs = false
            if navigation.pendingListRefresh { navigation.pendingListRefresh = false; navigation.refresh = UUID() }
        }
        .onDisappear { navigation.newDocument = nil }
    }

    private func taskKind(_ task: JSONValue) -> WorkflowKind? {
        WorkflowKind.allSupported.first { task["processDefinitionKey"].text.hasPrefix($0.rawValue) }
    }
    /// The card's status is the order's business state ("待完成", "待签到"), not the name of the
    /// workflow node ("完成任务"); the node name stays in the card's expanded "当前节点" line.
    private func statusName(_ task: JSONValue, kind: WorkflowKind?, data: JSONValue) -> String {
        if task["suspended"].bool { return "挂起" }
        let named = kind == .erp ? approvalStateName(data["approvalState"].text) : faultStateName(data["faultState"].text)
        return named.isEmpty || named == "状态待核对" || named == "全部" ? task["name"].text : named
    }
    private func title(_ task: JSONValue) -> String {
        let data = task["data"]
        if taskKind(task) == .fault && !data["faultName"].text.isEmpty { return data["faultName"].text }
        if taskKind(task) == .erp && !data["remark"].text.isEmpty { return data["remark"].text }
        return task["processInstanceName"].text
    }
    private func load(more: Bool = false) async {
        guard !preview else { return }
        if more { loadingMore = true } else { loading = true }
        error = nil
        defer { loading = false; loadingMore = false }
        let snapshot = "\(category)-\(query)"
        do {
            let page = try await session.page("process/pageUserTodoTask", offset: more ? rows.count : 0, filters: filters(category))
            try Task.checkCancellation()
            guard snapshot == "\(category)-\(query)" else { return }
            rows = more ? rows + page["content"].array : page["content"].array
            last = page["last"].bool
            total = serverPageCount(page)
            if let total { counts[category] = total }
        } catch is CancellationError {}
        catch let urlError as URLError where urlError.code == .cancelled {}
        catch { self.error = error.localizedDescription }
    }
    private func filters(_ key: String) -> [JSONValue] {
        var result: [JSONValue] = []
        if !key.isEmpty { result.append(filter("processDefinitionKey", key, "startLike")) }
        if !query.isEmpty { result.append(filter("processInstanceName", query, "like")) }
        return result
    }
    /// Server totals for every category, so each tab shows how many todos it holds.
    private func loadCounts() async {
        guard !preview else { return }
        let api = session, all = keys.map { filters($0) }
        await withTaskGroup(of: (String, Int?).self) { group in
            for (index, key) in keys.enumerated() {
                let body = all[index]
                group.addTask {
                    do { return (key, serverPageCount(try await api.page("process/pageUserTodoTask", filters: body, limit: 1))) }
                    catch { return (key, nil) }
                }
            }
            for await (key, count) in group {
                guard !Task.isCancelled else { return }
                if let count { counts[key] = count }
            }
        }
    }
}

private extension WorkflowKind {
    static let allSupported: [WorkflowKind] = [.fault, .erp]
}
