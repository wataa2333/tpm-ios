import SwiftUI

enum PreviewIdentity {
    static let department: JSONValue = .object(["deptId": .number(1), "deptName": .string("示例设备部")])
    static let user: JSONValue = .object(["userId": .number(1), "userName": .string("demo"), "realName": .string("示例用户"), "mobile": .string("13800000000"), "depts": .array([department])])
}

struct SignInPreview: View {
    let asset: Asset
    var onCompleted: (() -> Void)? = nil
    @State private var signed = false
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        List {
            Section("待签到设备") {
                Text(asset.name).animatedTextValue(asset.name).font(AppFont.headline)
                LabeledContent("机台号") { Text(asset.id).animatedTextValue(asset.id) }
                LabeledContent("资产编码") { Text("FA-" + asset.id).animatedTextValue("FA-" + asset.id) }
                LabeledContent("地址") { Text(asset.location).animatedTextValue(asset.location) }
                LabeledContent("维修人") { Text("示例用户").animatedTextValue("示例用户") }
                LabeledContent("签到状态") { Text(signed ? "已签到" : "待签到").animatedTextValue(signed ? "已签到" : "待签到") }
            }
            Section { NavigationLink { WorkflowNodesView(processID: "", preview: true) } label: { Label("处理流程", systemImage: "point.3.connected.trianglepath.dotted") } }
            Section { DemoNotice() }
        }
        .navigationTitle("任务签到").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .safeAreaInset(edge: .bottom) {
            HStack {
                Spacer()
                BottomAction(title: signed ? "已签到" : "签到", symbol: "checkmark.circle") { signed = true; onCompleted?(); if onCompleted != nil { dismiss() } }.disabled(signed)
            }.bottomBarPadding()

        }
    }
}

struct AssetRecordDetail: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    var independent = false
    let record: JSONValue
    var preview = false
    var initialTab = "详情"
    private let tabs = ["详情", "履历", "图片", "保养", "精密度检查", "巡检", "维修", "盘点"]
    @State private var tab = 0
    @State private var detail: JSONValue = .null
    @State private var rows: [JSONValue] = []
    @State private var loading = false
    @State private var error: String?
    @StateObject private var gate = ReloadGate()
    private var asset: JSONValue { detail == .null ? record : detail }
    var body: some View {
        // A card page, not a List: the repair cards here expand in place, and in a ScrollView the card above
        // stays put while the extra lines open downwards and the cards behind slide along in one animation.
        CardPage {
            HorizontalCategories(titles: tabs, selection: $tab)
            LoadingRow(isPresented: loading || gate.busy, title: "正在加载" + tabs[tab])
            CardGroup {
                VStack(alignment: .leading, spacing: RowMetrics.spacing) {
                    WrappingContent { Text(asset["assetName"].text).animatedTextValue(asset["assetName"].text).font(AppFont.headline) }
                    WrappingContent { Text(asset["assetCode"].text).animatedTextValue(asset["assetCode"].text).font(AppFont.subheadline).foregroundStyle(.secondary) }
                }.frame(maxWidth: .infinity, alignment: .leading).padding(RowMetrics.insets)
            }
            if tab == 0 {
                Text("基本信息").font(AppFont.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 32)
                CardGroup {
                    ForEach(assetFields, id: \.0) { key, label in
                        LabeledContent(label) { Text(assetDisplayValue(asset.at(key), key: key)).animatedTextValue(assetDisplayValue(asset.at(key), key: key)) }.padding(RowMetrics.insets)
                    }
                }
            } else if tab == 6 {
                CardGroup {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        let repair = assetRepairRecord(row, asset: asset)
                        WorkOrderCard(title: repair["faultName"].text, status: faultStateName(repair["faultState"].text), reference: repair["asset"]["assetCode"].text, machine: repair["asset"]["addressCode"].text, time: readableDate(repair["faultTime"]), facts: faultCardFacts(repair)) {
                            if preview { FaultDetailView(id: "BX-20261015-001") }
                            else { LiveWorkflowDetail(kind: .fault, id: row["faultId"].text) }
                        }
                    }
                }.opacity(loading || gate.busy ? 0.4 : 1).interfaceMotion(loading || gate.busy, duration: 0.4)
            } else if tab == 2 {
                if !asset["albums"].array.isEmpty {
                    CardGroup {
                        ForEach(Array(asset["albums"].array.enumerated()), id: \.offset) { _, photo in
                            AttachmentRow(photo: photo).frame(maxWidth: .infinity, alignment: .leading).padding(RowMetrics.insets)
                        }
                    }
                }
            } else if !rows.isEmpty {
                CardGroup {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(row["taskName"].text.isEmpty ? row.object.label : row["taskName"].text).animatedTextValue(row["taskName"].text.isEmpty ? row.object.label : row["taskName"].text).font(AppFont.headline)
                            Text(readableDate(row["createTime"])).animatedTextValue(readableDate(row["createTime"])).font(AppFont.subheadline).foregroundStyle(.secondary)
                            if !row["content"].text.isEmpty { Text(row["content"].text).animatedTextValue(row["content"].text).font(AppFont.subheadline) }
                        }.frame(maxWidth: .infinity, alignment: .leading).padding(RowMetrics.insets)
                    }
                }
            }
            if !loading && error == nil && tab != 0 && rows.isEmpty && (tab != 2 || asset["albums"].array.isEmpty) { CardGroup { CompactEmptyState(title: "暂无" + tabs[tab] + "记录") } }
            if preview { DemoNotice().padding(.horizontal, 32).frame(maxWidth: .infinity, alignment: .leading) }
        }
        .searchPageChrome()
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if !preview { AssetReportButton(asset: asset, screenEdge: true).bottomBarPadding() }
        }
        .textSelection(.enabled).navigationTitle("资产详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .onAppear { tab = tabs.firstIndex(of: initialTab) ?? 0 }
        .task(id: gate.id("\(tab)")) { await gate.run(key: "\(tab)") { await load() } }
        .refreshButton(busy: loading || gate.busy) { gate.request() }
        .interfaceMotion(loading || gate.busy, duration: 0.45)
        .errorDialog($error, retry: { Task { await load() } })
        .onChange(of: queries.revision) { if independent { detail = .null; rows = []; gate.request() } }
        .reloadsToTop(on: gate.id("\(tab)"))
    }
    private func load() async {
        loading = true; error = nil; rows = []; defer { loading = false }
        if preview {
            detail = record
            if tab == 6 { rows = [.object(["faultId": .string("DEMO"), "faultName": .string("主轴异常报修"), "faultState": .string("REPORTED"), "asset": record, "faultTime": .string("2026-10-16 09:30"), "applyUser": PreviewIdentity.user, "userGroup": .object(["groupName": .string("设备维修二组")]), "user": PreviewIdentity.user, "dept": PreviewIdentity.department])] }
            return
        }
        do {
            let api = independent ? try await queries.source(for: session) : session
            if detail == .null { detail = try await api.request("asset/getAssetDetailById", query: ["assetId": record["assetId"].text]) }
            let paths = [3: "maintain/pageMaintain", 4: "precise/pagePrecise", 5: "inspect/pageInspect", 6: "fault/pageSimpleAssetFault", 7: "inventory/pageInventoryRecord"]
            if tab == 1 { rows = try await api.request("asset/getAssetResumeByAsset", query: ["assetId": record["assetId"].text], body: .null).array }
            else if let path = paths[tab] {
                let result = try await api.request(path, body: .object(["offset": .number(0), "limit": .number(20), "sort": .string(tab == 6 ? "faultTime" : "createTime"), "order": .string("desc"), "filters": .array([filter("asset.assetId", record["assetId"].text)])]))
                try Task.checkCancellation(); rows = result["content"].array
            }
        } catch is CancellationError { } catch { self.error = error.localizedDescription }
    }
}

struct WarehouseStockView: View {
    @EnvironmentObject private var session: LiveSession
    var preview = false
    @State private var rows: [JSONValue] = []
    @State private var loading = false
    @State private var loadingMore = false
    @State private var error: String?
    @State private var query = ""
    @State private var searchField = "material.materialName"
    @StateObject private var gate = ReloadGate()
    @State private var searching = false
    @State private var last = true
    @State private var total: Int?
    var body: some View {
        List {
            Section { LoadingRow(isPresented: (loading || gate.busy) && !loadingMore, title: "正在加载库存") }
            Section {
                if searching { Picker("搜索分类", selection: $searchField) { Text("物料名称").tag("material.materialName"); Text("物料编号").tag("material.materialNum"); Text("规格型号").tag("material.materialModel") } }
                NavigationLink { WarehouseReceiptsView(preview: preview) } label: { Label("入库记录", systemImage: "tray.and.arrow.down") }
            }
            Section {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                NavigationLink { WarehouseStockFacts(row: row) } label: {
                    VStack(alignment: .leading, spacing: RowMetrics.spacing) {
                        HStack { Text(row["material"]["materialName"].text).animatedTextValue(row["material"]["materialName"].text).font(AppFont.headline); Spacer(); Text(quantity(row)).animatedTextValue(quantity(row)).font(AppFont.subheadline.weight(.medium)) }
                        Text("编号：" + row["material"]["materialNum"].text).animatedTextValue("编号：" + row["material"]["materialNum"].text).font(AppFont.subheadline).foregroundStyle(.secondary)
                        Text("仓库：" + row["warehouse"]["warehouseName"].text).animatedTextValue("仓库：" + row["warehouse"]["warehouseName"].text).font(AppFont.subheadline).foregroundStyle(.secondary)
                        Text("记录时间：" + readableDate(row["createTime"])).animatedTextValue("记录时间：" + readableDate(row["createTime"])).font(AppFont.subheadline).foregroundStyle(.secondary)
                    }
                }.uniformRow()
            }
            if !loading && rows.isEmpty && error == nil { CompactEmptyState(title: "暂无库存记录") }
            }.opacity((loading || gate.busy) && !loadingMore ? 0.4 : 1).interfaceMotion(loading || gate.busy, duration: 0.4)
            Section { LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || loadingMore, loaded: rows.count, total: total) { await load(more: true) } }
            if preview { DemoNotice() }
        }
        .navigationTitle("仓库库存").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: "搜索" + (searchField == "material.materialNum" ? "物料编号" : searchField == "material.materialModel" ? "规格型号" : "物料名称"))
        .task(id: gate.id(searchField + query)) { await gate.run(key: searchField + query, debounce: query.isEmpty ? nil : .milliseconds(350), load: { await load() }) }
        .reloadsToTop(on: gate.id(searchField + query))
        .interfaceMotion(loading || gate.busy, duration: 0.45)
        .refreshButton(busy: loading || loadingMore || gate.busy) { gate.request() }
        .errorDialog($error, retry: { Task { await load() } })
        .refreshable { await load() }
    }
    private func quantity(_ row: JSONValue) -> String { row["amount"].text + " " + (row["material"]["unit"].text.isEmpty ? "PCS" : row["material"]["unit"].text) }
    private func load(more: Bool = false) async {
        if more { loadingMore = true } else { loading = true }
        error = nil; defer { loading = false; loadingMore = false }
        let snapshot = searchField + query
        if preview { rows = PreviewWarehouse.rows.filter { query.isEmpty || $0.at(searchField).text.localizedCaseInsensitiveContains(query) }; return }
        do {
            let result = try await session.page("material/pageMaterialInfo", offset: more ? rows.count : 0, filters: query.isEmpty ? [] : [filter(searchField, query, "like")])
            try Task.checkCancellation()
            guard snapshot == searchField + query else { return }
            rows = more ? rows + result["content"].array : result["content"].array; last = result["last"].bool; total = serverPageCount(result)
        } catch is CancellationError { }
        catch let urlError as URLError where urlError.code == .cancelled { }
        catch { self.error = error.localizedDescription }
    }
}

struct WarehouseStockFacts: View {
    let row: JSONValue
    private func value(_ key: String) -> String {
        let text = readableDate(row.at(key))
        guard !text.isEmpty else { return "—" }
        if ["amount", "inventoryLowerLimit", "inventoryLimit"].contains(key) {
            return text + " " + (row["material"]["unit"].text.isEmpty ? "PCS" : row["material"]["unit"].text)
        }
        return text
    }
    var body: some View {
        List {
            Section("物料信息") { ForEach([("material.materialName", "名称"), ("material.materialNum", "物料编号"), ("material.materialModel", "规格型号"), ("material.brand.brandName", "品牌"), ("material.materialCategory.categoryName", "类别"), ("warehouse.warehouseName", "仓库"), ("place", "库位"), ("amount", "库存数量"), ("averagePrice", "平均价格"), ("inventoryLowerLimit", "库存下限"), ("inventoryLimit", "库存上限"), ("createTime", "记录时间")], id: \.0) { key, title in LabeledContent(title) { Text(value(key)).animatedTextValue(value(key)) } } }
            LabeledContent("单位") { Text(row["material"]["unit"].text.isEmpty ? "PCS" : row["material"]["unit"].text).animatedTextValue(row["material"]["unit"].text.isEmpty ? "PCS" : row["material"]["unit"].text) }
        }.navigationTitle("仓库库存详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
    }
}

struct WarehouseReceiptsView: View {
    @EnvironmentObject private var session: LiveSession
    var preview = false
    @State private var rows: [JSONValue] = []
    @State private var loading = false
    @State private var error: String?
    @StateObject private var gate = ReloadGate()
    var body: some View {
        List {
            LoadingRow(isPresented: loading || gate.busy, title: "正在加载入库记录")
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                Section(row["entryNumber"].text) {
                    LabeledContent("入库时间") { Text(readableDate(row["warehousingTime"])).animatedTextValue(readableDate(row["warehousingTime"])) }
                    LabeledContent("入库仓库") { Text(row["warehouse"]["warehouseName"].text).animatedTextValue(row["warehouse"]["warehouseName"].text) }
                    LabeledContent("入库人") { Text(row["user"]["realName"].text).animatedTextValue(row["user"]["realName"].text) }
                    LabeledContent("申请日期") { Text(readableDate(row["applyDate"])).animatedTextValue(readableDate(row["applyDate"])) }
                }.animatedTextValue(row["entryNumber"].text)
            }
            if preview { DemoNotice() }
        }.navigationTitle("入库记录").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .errorDialog($error, clearsOnClose: true)
            .reloadsToTop(on: gate.token)
            .interfaceMotion(loading || gate.busy, duration: 0.45)
            .refreshButton(busy: loading || gate.busy) { gate.request() }
            .task(id: gate.id("receipts")) { await gate.run(key: "receipts") {
                if preview { rows = [.object(["entryNumber": .string("RK-DEMO-001"), "warehousingTime": .string("2026-10-15 14:30"), "applyDate": .string("2026-10-15"), "warehouse": .object(["warehouseName": .string("备件仓")]), "user": PreviewIdentity.user])]; return }
                loading = true; defer { loading = false }
                do { rows = try await session.page("warehouse/pageWarehousing")["content"].array } catch { self.error = error.localizedDescription }
            } }
    }
}

enum PreviewWarehouse {
    static let rows: [JSONValue] = [.object(["material": .object(["materialName": .string("深沟球轴承"), "materialNum": .string("WL-10028"), "materialModel": .string("6205-2RS"), "unit": .string("PCS")]), "amount": .number(24), "warehouse": .object(["warehouseName": .string("备件仓")]), "place": .string("A-03-02"), "createTime": .string("2026-10-15 14:30")])]
}
