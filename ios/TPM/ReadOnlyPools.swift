import SwiftUI

enum ReadOnlyPoolKind: String {
    case maintenance = "maintain", precision = "precise"
    var title: String { self == .maintenance ? "保养工单池" : "精密度检查工单池" }
    var page: String { rawValue + (self == .maintenance ? "/pageMaintain" : "/pagePrecise") }
    var nameKey: String { rawValue + "Name" }
    var stateKey: String { rawValue + "State" }
    /// "待接单" is the same situation as a repair order waiting to be grabbed.
    var color: Color { FaultState.received.color }
    /// Orders waiting to be taken: the original pools default to the RECEIVED state.
    var waitingFilters: [JSONValue] { [filter(stateKey, "RECEIVED")] }
}

/// The original pools default to RECEIVED. These entries read server records;
/// maintenance/precision processing remains outside the current repair scope.
struct ReadOnlyPoolView: View {
    let kind: ReadOnlyPoolKind
    @EnvironmentObject private var session: LiveSession
    @State private var rows: [JSONValue] = []
    @State private var query = ""
    @State private var byAsset = false
    @State private var searching = false
    @State private var loading = true
    @State private var refreshing = false
    @State private var moreLoading = false
    @State private var last = true
    @State private var total: Int?
    @State private var error: String?
    var body: some View {
        List {
            LoadingRow(isPresented: loading && !refreshing, title: "正在加载工单")
            if searching {
                Picker("搜索分类", selection: $byAsset) {
                    Text("工单名称").tag(false)
                    Text("资产编码").tag(true)
                }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                WorkOrderCard(title: row[kind.nameKey].text, status: "待接单", color: kind.color,
                    reference: row["asset"]["assetCode"].text,
                    time: readableDate(row["planStartDate"]), facts: facts(row)) {
                    List {
                        Section("工单信息") {
                            LabeledContent("工单名称", value: row[kind.nameKey].text)
                            LabeledContent("状态", value: "待接单")
                            ForEach(Array(facts(row).enumerated()), id: \.offset) { _, fact in
                                LabeledContent(fact.0, value: fact.1)
                            }
                        }
                    }
                    .navigationTitle(kind.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
                }
            }
            if !loading && rows.isEmpty && error == nil { CompactEmptyState(title: "暂无待接单工单") }
            LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || moreLoading, loaded: rows.count, total: total) { await load(more: true) }
        }
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: byAsset ? "搜索资产编码" : "搜索工单名称", onSubmit: { Task { await load() } })
        .navigationTitle(kind.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .reloadsToTop(on: "\(byAsset)-\(query)")
        .errorDialog($error, retry: { Task { await load() } })
        .task(id: "\(byAsset)-\(query)") { await load() }
        .refreshable { refreshing = true; await load(); refreshing = false }
    }
    private func facts(_ row: JSONValue) -> [(String, String)] {
        [("单号", row["sn"].text), ("资产编码", row["asset"]["assetCode"].text),
         ("资产名称", row["asset"]["assetName"].text), ("机台号", row["asset"]["addressCode"].text),
         ("地址", row["asset"]["address"].text), ("申请人", row["applyUser"].object.label),
         ("受理部门", row["dept"].object.label), ("受理组别", row["userGroup"].object.label),
         ("受理人", row["user"].object.label), ("计划开始", readableDate(row["planStartDate"])),
         ("计划结束", readableDate(row["planEndDate"]))].filter { !$0.1.isEmpty }
    }
    private func load(more: Bool = false) async {
        if more { moreLoading = true } else { loading = true }
        defer { loading = false; moreLoading = false }
        error = nil
        let snapshot = "\(byAsset)-\(query)"
        do {
            var filters = kind.waitingFilters
            if !query.isEmpty { filters.append(filter(byAsset ? "asset.assetCode" : kind.nameKey, query, "like")) }
            let page = try await session.page(kind.page, offset: more ? rows.count : 0, filters: filters)
            try Task.checkCancellation()
            guard snapshot == "\(byAsset)-\(query)" else { return }
            rows = more ? rows + page["content"].array : page["content"].array
            last = page["last"].bool
            total = serverPageCount(page)
        } catch is CancellationError { }
        catch let urlError as URLError where urlError.code == .cancelled { }
        catch { self.error = error.localizedDescription }
    }
}
