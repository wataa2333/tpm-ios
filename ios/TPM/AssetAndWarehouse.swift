import SwiftUI

enum PreviewIdentity {
    static let department: JSONValue = .object(["deptId": .number(1), "deptName": .string("示例设备部")])
    static let user: JSONValue = .object(["userId": .number(1), "userName": .string("demo"), "realName": .string("示例用户"), "mobile": .string("13800000000"), "depts": .array([department])])
}

struct SignInPreview: View {
    let asset: Asset
    var body: some View {
        List {
            Section("待签到设备") {
                Text(asset.name).font(.headline)
                LabeledContent("机台号", value: asset.id)
                LabeledContent("资产编码", value: "FA-" + asset.id)
                LabeledContent("地址", value: asset.location)
                LabeledContent("维修人", value: "示例用户")
                LabeledContent("签到时间", value: "未签到")
            }
            Section { NavigationLink { WorkflowNodesView(processID: "", preview: true) } label: { Label("处理流程", systemImage: "point.3.connected.trianglepath.dotted") } }
            Section { Text("原 App 提供二维码及 RFID 签到。当前仅展示页面，未执行签到。").font(.footnote).foregroundStyle(.secondary) }
        }
        .navigationTitle("任务签到").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            HStack {
                BottomAction(title: "RFID 签到", symbol: "wave.3.right", color: .orange) { }
                Spacer()
                BottomAction(title: "二维码签到", symbol: "qrcode.viewfinder") { }
            }.padding(.horizontal, 20).padding(.vertical, 8).disabled(true)
        }
    }
}

struct AssetRecordDetail: View {
    @EnvironmentObject private var session: LiveSession
    let record: JSONValue
    var preview = false
    var initialTab = "详情"
    private let tabs = ["详情", "履历", "图片", "保养", "精密度检查", "巡检", "维修", "盘点"]
    @State private var tab = 0
    @State private var detail: JSONValue = .null
    @State private var rows: [JSONValue] = []
    @State private var loading = false
    @State private var error: String?
    private var asset: JSONValue { detail == .null ? record : detail }
    var body: some View {
        List {
            Section {
                Text(asset["assetName"].text).font(.headline)
                Text(asset["assetCode"].text).font(.subheadline).foregroundStyle(.secondary)
            }
            if tab == 0 {
                Section("基本信息") { ForEach(assetFields, id: \.0) { key, label in LabeledContent(label, value: readableDate(asset.at(key)).isEmpty ? "—" : readableDate(asset.at(key))) } }
            } else if tab == 6 {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    NavigationLink {
                        if preview { FaultDetailView(id: "BX-20261015-001") }
                        else { LiveWorkflowDetail(kind: .fault, id: row["faultId"].text) }
                    } label: {
                        VStack(alignment: .leading, spacing: 10) {
                            HStack(alignment: .top) { Text(row["faultName"].text).font(.headline); Spacer(); Text(faultStateName(row["faultState"].text)).font(.subheadline.weight(.medium)).foregroundStyle(.orange) }
                            ForEach([("asset.assetCode", "资产编号"), ("asset.assetName", "资产名称"), ("asset.addressCode", "机台号"), ("asset.address", "地址"), ("faultTime", "故障时间"), ("applyUser.realName", "故障申请人"), ("userGroup.groupName", "维修班组"), ("user.realName", "维修受理人"), ("dept.deptName", "受理部门")], id: \.0) { key, title in
                                HStack(alignment: .top) { Text(title).foregroundStyle(.secondary); Spacer(minLength: 16); Text(readableDate(row.at(key))).multilineTextAlignment(.trailing) }.font(.subheadline)
                            }
                        }.padding(.vertical, 8)
                    }
                }
            } else if tab == 2 {
                ForEach(Array(asset["albums"].array.enumerated()), id: \.offset) { _, photo in AttachmentRow(photo: photo) }
            } else {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                    VStack(alignment: .leading, spacing: 8) {
                        Text(row["taskName"].text.isEmpty ? row.object.label : row["taskName"].text).font(.headline)
                        Text(readableDate(row["createTime"])).font(.subheadline).foregroundStyle(.secondary)
                        if !row["content"].text.isEmpty { Text(row["content"].text).font(.subheadline) }
                    }.padding(.vertical, 6)
                }
            }
            if !loading && error == nil && tab != 0 && rows.isEmpty && (tab != 2 || asset["albums"].array.isEmpty) { CompactEmptyState(title: "暂无" + tabs[tab] + "记录") }
            if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
            if preview { Section { DemoNotice() } }
        }
        .navigationTitle("资产详情").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .top, spacing: 0) { HorizontalCategories(titles: tabs, selection: $tab) }
        .overlay { if loading { LoadingOverlay(title: "正在加载" + tabs[tab] + "…") } }
        .onAppear { tab = tabs.firstIndex(of: initialTab) ?? 0 }
        .task(id: tab) { await load() }
    }
    private func load() async {
        loading = true; error = nil; rows = []; defer { loading = false }
        if preview {
            detail = record
            if tab == 6 { rows = [.object(["faultId": .string("DEMO"), "faultName": .string("主轴异常报修"), "faultState": .string("REPORTED"), "asset": record, "faultTime": .string("2026-10-16 09:30"), "applyUser": PreviewIdentity.user, "userGroup": .object(["groupName": .string("设备维修二组")]), "user": PreviewIdentity.user, "dept": PreviewIdentity.department])] }
            return
        }
        do {
            if detail == .null { detail = try await session.request("asset/getAssetDetailById", query: ["assetId": record["assetId"].text]) }
            let paths = [3: "maintain/pageMaintain", 4: "precise/pagePrecise", 5: "inspect/pageInspect", 6: "fault/pageSimpleAssetFault", 7: "inventory/pageInventoryRecord"]
            if tab == 1 { rows = try await session.request("asset/getAssetResumeByAsset", query: ["assetId": record["assetId"].text], body: .null).array }
            else if let path = paths[tab] {
                let result = try await session.request(path, body: .object(["offset": .number(0), "limit": .number(20), "sort": .string(tab == 6 ? "faultTime" : "createTime"), "order": .string("desc"), "filters": .array([filter("asset.assetId", record["assetId"].text)])]))
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
    @State private var error: String?
    @State private var query = ""
    @State private var searchField = "material.materialName"
    @State private var last = true
    var body: some View {
        List {
            Section {
                Picker("搜索分类", selection: $searchField) { Text("物料名称").tag("material.materialName"); Text("物料编号").tag("material.materialNum"); Text("规格型号").tag("material.materialModel") }
                NavigationLink { WarehouseReceiptsView(preview: preview) } label: { Label("入库记录", systemImage: "tray.and.arrow.down") }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                NavigationLink { WarehouseStockFacts(row: row) } label: {
                    VStack(alignment: .leading, spacing: 8) {
                        HStack { Text(row["material"]["materialName"].text).font(.headline); Spacer(); Text(quantity(row)).font(.subheadline.weight(.medium)) }
                        Text("编号：" + row["material"]["materialNum"].text).font(.subheadline).foregroundStyle(.secondary)
                        Text("仓库：" + row["warehouse"]["warehouseName"].text).font(.subheadline).foregroundStyle(.secondary)
                        Text("记录时间：" + readableDate(row["createTime"])).font(.subheadline).foregroundStyle(.secondary)
                    }.padding(.vertical, 6)
                }
            }
            if !loading && rows.isEmpty && error == nil { CompactEmptyState(title: "暂无库存记录") }
            if !last { Button("加载更多") { Task { await load(more: true) } }.disabled(loading) }
            if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
            if preview { DemoNotice() }
        }
        .navigationTitle("仓库库存").navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索" + (searchField == "material.materialNum" ? "物料编号" : searchField == "material.materialModel" ? "规格型号" : "物料名称"))
        .task(id: searchField + query) { await load() }
        .refreshable { await load() }
        .overlay { if loading { LoadingOverlay(title: "正在加载库存…") } }
    }
    private func quantity(_ row: JSONValue) -> String { row["amount"].text + " " + (row["material"]["unit"].text.isEmpty ? "PCS" : row["material"]["unit"].text) }
    private func load(more: Bool = false) async {
        loading = true; error = nil; defer { loading = false }
        if preview { rows = PreviewWarehouse.rows.filter { query.isEmpty || $0.at(searchField).text.localizedCaseInsensitiveContains(query) }; return }
        do {
            let result = try await session.page("material/pageMaterialInfo", offset: more ? rows.count : 0, filters: query.isEmpty ? [] : [filter(searchField, query, "like")])
            try Task.checkCancellation(); rows = more ? rows + result["content"].array : result["content"].array; last = result["last"].bool
        } catch is CancellationError { } catch { self.error = error.localizedDescription }
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
            Section("物料信息") { ForEach([("material.materialName", "名称"), ("material.materialNum", "物料编号"), ("material.materialModel", "规格型号"), ("material.brand.brandName", "品牌"), ("material.materialCategory.categoryName", "类别"), ("warehouse.warehouseName", "仓库"), ("place", "库位"), ("amount", "库存数量"), ("averagePrice", "平均价格"), ("inventoryLowerLimit", "库存下限"), ("inventoryLimit", "库存上限"), ("createTime", "记录时间")], id: \.0) { key, title in LabeledContent(title, value: value(key)) } }
            LabeledContent("单位", value: row["material"]["unit"].text.isEmpty ? "PCS" : row["material"]["unit"].text)
        }.navigationTitle("仓库库存详情").navigationBarTitleDisplayMode(.inline)
    }
}

struct WarehouseReceiptsView: View {
    @EnvironmentObject private var session: LiveSession
    var preview = false
    @State private var rows: [JSONValue] = []
    @State private var loading = false
    @State private var error: String?
    var body: some View {
        List {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                Section(row["entryNumber"].text) {
                    LabeledContent("入库时间", value: readableDate(row["warehousingTime"]))
                    LabeledContent("入库仓库", value: row["warehouse"]["warehouseName"].text)
                    LabeledContent("入库人", value: row["user"]["realName"].text)
                    LabeledContent("申请日期", value: readableDate(row["applyDate"]))
                }
            }
            if let error { Text(error).foregroundStyle(.red) }
            if preview { DemoNotice() }
        }.navigationTitle("入库记录").navigationBarTitleDisplayMode(.inline)
            .overlay { if loading { LoadingOverlay(title: "正在加载入库记录…") } }
            .task {
                if preview { rows = [.object(["entryNumber": .string("RK-DEMO-001"), "warehousingTime": .string("2026-10-15 14:30"), "applyDate": .string("2026-10-15"), "warehouse": .object(["warehouseName": .string("备件仓")]), "user": PreviewIdentity.user])]; return }
                loading = true; defer { loading = false }
                do { rows = try await session.page("warehouse/pageWarehousing")["content"].array } catch { self.error = error.localizedDescription }
            }
    }
}

enum PreviewWarehouse {
    static let rows: [JSONValue] = [.object(["material": .object(["materialName": .string("深沟球轴承"), "materialNum": .string("WL-10028"), "materialModel": .string("6205-2RS"), "unit": .string("PCS")]), "amount": .number(24), "warehouse": .object(["warehouseName": .string("备件仓")]), "place": .string("A-03-02"), "createTime": .string("2026-10-15 14:30")])]
}
