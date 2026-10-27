import SwiftUI

private actor PrecisionPeopleGate {
    static let shared = PrecisionPeopleGate()
    private var active = 0
    private var waiting: [CheckedContinuation<Void, Never>] = []
    func acquire() async {
        if active < 3 { active += 1; return }
        await withCheckedContinuation { waiting.append($0) }
    }
    func release() {
        if waiting.isEmpty { active -= 1 } else { waiting.removeFirst().resume() }
    }
}

@MainActor private func precisionUploaderNames(_ records: [JSONValue], source: LiveSession, check: () throws -> Void) async throws -> String {
    let accounts = Set(records.map { $0["createBy"].text }.filter { !$0.isEmpty }).sorted()
    var names: [String] = []
    for account in accounts {
        try check()
        do {
            let person = try await source.request("security/getUserByUserName", query: ["userName": account])
            try check(); names.append(person["realName"].text.isEmpty ? account : person["realName"].text)
        } catch is CancellationError { throw CancellationError() } catch { names.append(account) }
    }
    return names.isEmpty ? "未记录" : names.joined(separator: "、")
}

@MainActor private func precisionPeople(assetID: String, source: LiveSession, check: () throws -> Void) async throws -> String {
    var executions: [JSONValue] = [], offset = 0, expected: Int?, seen = Set<String>()
    repeat {
        try check()
        let page = try await source.page("precise/pagePrecise", offset: offset, filters: [filter("asset.assetId", assetID)], limit: 20)
        try check()
        guard case .array(let rows) = page["content"], let total = serverPageCount(page), expected == nil || expected == total,
              rows.count <= 20, !rows.isEmpty || offset >= total else { throw APIError(message: "检测工单分页异常") }
        expected = total
        for row in rows {
            let id = row["preciseId"].text
            guard row["asset"]["assetId"].text == assetID, !id.isEmpty, seen.insert(id).inserted else { throw APIError(message: "检测工单关联不符或分页重复") }
            let answer = try await source.request("precise/getPreciseOrdersByPreciseId", query: ["preciseId": id])
            try check()
            guard case .array(let values) = answer else { throw APIError(message: "检测执行记录格式异常") }
            executions += values.filter { managementDay($0["endTime"]) != nil }
        }
        offset += rows.count
    } while offset < (expected ?? 0)
    guard let latest = executions.max(by: { precisionExecutionDate($0["endTime"]) < precisionExecutionDate($1["endTime"]) }) else { return "工单检测人：暂无已完成检测记录" }
    return "工单检测人：" + (latest["user"].object.label.isEmpty ? "未记录" : latest["user"].object.label)
}

struct PrecisionPeopleLabel: View {
    let assetID: String
    var refreshID: Date? = nil
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @State private var text = "工单检测人：读取中…"
    private var context: String { session.scopeRevision.uuidString + queries.revision.uuidString }
    var body: some View {
        Text(text).animatedTextValue(text).font(AppFont.precisionText(.caption, size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            .task(id: context + (refreshID?.description ?? "")) {
                let key = context
                text = "工单检测人：读取中…"
                await PrecisionPeopleGate.shared.acquire()
                defer { Task { await PrecisionPeopleGate.shared.release() } }
                do {
                    try Task.checkCancellation()
                    let source = try await queries.source(for: session)
                    let result = try await precisionPeople(assetID: assetID, source: source) {
                        try Task.checkCancellation(); guard key == context else { throw CancellationError() }
                    }
                    text = result
                } catch is CancellationError { } catch { if key == context { text = "工单检测人：暂未读取到" } }
            }
    }
}

struct PrecisionDeviceScreen: View {
    let asset: JSONValue
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @State private var page = 0
    @State private var total: Int?
    @State private var orders: [JSONValue] = []
    @State private var executions: [JSONValue] = []
    @State private var records: [JSONValue] = []
    @State private var selectedOrder = ""
    @State private var selectedExecution = ""
    @State private var uploader = ""
    @State private var pageLoading = false
    @State private var executionLoading = false
    @State private var recordLoading = false
    @State private var error: String?
    @State private var retry = 0
    private var context: String { session.scopeRevision.uuidString + queries.revision.uuidString }
    private var pageContext: String { context + ":\(page):\(retry)" }
    private var executionContext: String { pageContext + ":" + selectedOrder }
    private var recordContext: String { executionContext + ":" + selectedExecution }
    private var execution: JSONValue { executions.first { $0["orderId"].text == selectedExecution } ?? .null }
    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 14) {
                PrecisionPanel {
                    Text(asset["addressCode"].text.isEmpty ? asset["assetCode"].text : asset["addressCode"].text).animatedTextValue(asset["addressCode"].text.isEmpty ? asset["assetCode"].text : asset["addressCode"].text).font(AppFont.title2.bold())
                    Text(asset["assetName"].text).animatedTextValue(asset["assetName"].text); Text(asset["assetCode"].text).animatedTextValue(asset["assetCode"].text).font(AppFont.caption).foregroundStyle(.secondary)
                    Text("查询：" + queries.label(operator: session)).animatedTextValue("查询：" + queries.label(operator: session)).font(AppFont.caption)
                    Button("重新读取") { retry += 1 }.disabled(pageLoading || executionLoading || recordLoading)
                    if pageLoading || executionLoading || recordLoading { ProgressView("正在读取检测记录") }
                    if let error { Text(error).animatedTextValue(error).font(AppFont.subheadline).foregroundStyle(.red) }
                }
                if !orders.isEmpty {
                    PrecisionPanel {
                        Text("检测工单").font(AppFont.headline)
                        Picker("选择工单", selection: $selectedOrder) {
                            ForEach(Array(orders.enumerated()), id: \.offset) { _, order in
                                Text(readableDate(order["planStartDate"]) + " · " + order["sn"].text).animatedTextValue(readableDate(order["planStartDate"]) + " · " + order["sn"].text).tag(order["preciseId"].text)
                            }
                        }
                        HStack {
                            Button("上一页") { page -= 1 }.disabled(page == 0 || pageLoading)
                            Spacer(); Text("第\(page + 1)页 · 共\(total ?? 0)单").animatedTextValue("第\(page + 1)页 · 共\(total ?? 0)单").font(AppFont.caption); Spacer()
                            Button("下一页") { page += 1 }.disabled(pageLoading || (page + 1) * 20 >= (total ?? 0))
                        }
                        if !executions.isEmpty {
                            Picker("检测执行记录", selection: $selectedExecution) {
                                ForEach(Array(executions.enumerated()), id: \.offset) { _, row in
                                    Text(readableDate(row["endTime"]) + " · " + row["user"].object.label).animatedTextValue(readableDate(row["endTime"]) + " · " + row["user"].object.label).tag(row["orderId"].text)
                                }
                            }
                            Text("检测人：" + (execution["user"].object.label.isEmpty ? "未记录" : execution["user"].object.label)).animatedTextValue("检测人：" + (execution["user"].object.label.isEmpty ? "未记录" : execution["user"].object.label))
                            Text("上传人：" + (uploader.isEmpty ? "读取中…" : uploader)).animatedTextValue("上传人：" + (uploader.isEmpty ? "读取中…" : uploader)).font(AppFont.subheadline)
                        } else if !executionLoading { Text("该工单暂无检测执行记录").font(AppFont.subheadline) }
                    }
                } else if !pageLoading && error == nil {
                    PrecisionPanel { Text("当前查询账号未返回该设备的检测工单，不能从汇总等级还原逐项数据。") }
                }
                ForEach(Array(records.enumerated()), id: \.offset) { _, record in
                    PrecisionPanel {
                        let title = record["itemName"].text.isEmpty ? record["preciseItem"]["itemName"].text : record["itemName"].text
                        Text(title).animatedTextValue(title).font(AppFont.headline).fixedSize(horizontal: false, vertical: true)
                        Text("精度判定：" + (record["preciseResult"].text.isEmpty ? "未记录" : record["preciseResult"].text)).animatedTextValue("精度判定：" + (record["preciseResult"].text.isEmpty ? "未记录" : record["preciseResult"].text))
                        Text("记录值：" + (record["description"].text.isEmpty ? "未记录" : record["description"].text)).animatedTextValue("记录值：" + (record["description"].text.isEmpty ? "未记录" : record["description"].text)).textSelection(.enabled)
                        if record["executeResult"].text != "EXECUTED" { Text("执行状态：" + record["executeResult"].text).animatedTextValue("执行状态：" + record["executeResult"].text).font(AppFont.caption).foregroundStyle(.secondary) }
                    }
                }
            }.padding(16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("逐项精度数据").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .task(id: pageContext) { await loadPage() }
        .task(id: executionContext) { await loadExecutions() }
        .task(id: recordContext) { await loadRecords() }
    }
    @MainActor private func loadPage() async {
        let key = pageContext
        orders = []; executions = []; records = []; selectedOrder = ""; selectedExecution = ""; error = nil; total = nil; pageLoading = true
        defer { if key == pageContext { pageLoading = false } }
        do {
            let source = try await queries.source(for: session)
            let answer = try await source.page("precise/pagePrecise", offset: page * 20, filters: [filter("asset.assetId", asset["assetId"].text)], limit: 20)
            try Task.checkCancellation(); guard key == pageContext else { return }
            guard case .array(let rows) = answer["content"], rows.allSatisfy({ $0["asset"]["assetId"].text == asset["assetId"].text && !$0["preciseId"].text.isEmpty }),
                  Set(rows.map { $0["preciseId"].text }).count == rows.count, let count = serverPageCount(answer) else { throw APIError(message: "检测工单分页或设备关联异常") }
            total = count; orders = rows; selectedOrder = rows.first?["preciseId"].text ?? ""
        } catch is CancellationError { } catch { if key == pageContext { self.error = error.localizedDescription } }
    }
    @MainActor private func loadExecutions() async {
        let key = executionContext
        executions = []; records = []; selectedExecution = ""; uploader = ""; executionLoading = false
        guard !selectedOrder.isEmpty else { return }
        executionLoading = true; error = nil
        defer { if key == executionContext { executionLoading = false } }
        do {
            let source = try await queries.source(for: session)
            let answer = try await source.request("precise/getPreciseOrdersByPreciseId", query: ["preciseId": selectedOrder])
            try Task.checkCancellation(); guard key == executionContext else { return }
            guard case .array(let rows) = answer, rows.allSatisfy({ !$0["orderId"].text.isEmpty }), Set(rows.map { $0["orderId"].text }).count == rows.count else { throw APIError(message: "检测执行记录格式异常") }
            executions = rows.sorted { precisionExecutionDate($0["endTime"]) > precisionExecutionDate($1["endTime"]) }
            selectedExecution = executions.first?["orderId"].text ?? ""
        } catch is CancellationError { } catch { if key == executionContext { self.error = error.localizedDescription } }
    }
    @MainActor private func loadRecords() async {
        let key = recordContext
        records = []; uploader = ""; recordLoading = false
        guard !selectedExecution.isEmpty else { return }
        recordLoading = true; error = nil
        defer { if key == recordContext { recordLoading = false } }
        do {
            let source = try await queries.source(for: session)
            let answer = try await source.request("precise/getPreciseRecordsByPreciseId", query: ["preciseId": selectedExecution])
            try Task.checkCancellation(); guard key == recordContext else { return }
            guard case .array(let rows) = answer else { throw APIError(message: "逐项检测记录格式异常") }
            records = rows.sorted { $0["preciseItem"]["sortOrder"].number < $1["preciseItem"]["sortOrder"].number }
            let names = try await precisionUploaderNames(rows, source: source) {
                try Task.checkCancellation(); guard key == recordContext else { throw CancellationError() }
            }
            uploader = names
        } catch is CancellationError { } catch { if key == recordContext { self.error = error.localizedDescription } }
    }
}
