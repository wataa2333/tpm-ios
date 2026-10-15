import SwiftUI

enum WorkflowKind: String, Identifiable {
    case fault = "ASSET_FAULT", erp = "ERP_OUTBOUND"
    var id: String { rawValue }
    var title: String { self == .fault ? "故障报修" : "ERP 出库" }
    var page: String { self == .fault ? "fault/pageAssetFault" : "erpOutbound/pageErpOutbound" }
    var idKey: String { self == .fault ? "faultId" : "outboundId" }
    var detailPath: String { self == .fault ? "fault/getAssetFaultDetailById" : "erpOutbound/getErpOutboundById" }
}

struct AuthenticatedRoot: View {
    @EnvironmentObject private var session: LiveSession
    var body: some View { if session.signedIn { LiveTabs() } else { NativeLoginView() } }
}
struct LiveTabs: View {
    @EnvironmentObject private var session: LiveSession
    var body: some View {
        TabView {
            Tab("主页", systemImage: "house") {
                NavigationStack {
                    List {
                        NavigationLink("待受理") { LiveWorkflowList(kind: .fault, initialState: "REPORTED") }
                        Section("工单池") {
                            NavigationLink("维修工单池") { LiveWorkflowList(kind: .fault, initialState: "RECEIVED") }
                            NavigationLink("保养工单池") { OrderPoolView(kind: "保养") }
                            NavigationLink("精密度检查工单池") { OrderPoolView(kind: "精密度检查") }
                        }
                    }.navigationTitle("主页")
                }
            }
            Tab("工作台", systemImage: "square.grid.2x2") {
                NavigationStack {
                    List {
                        Section("资产管理") { NavigationLink("资产台账") { LiveCatalogView(source: .assets) } }
                        Section("备品备件") {
                            NavigationLink("ERP 出库") { LiveWorkflowList(kind: .erp) }
                            NavigationLink("库存") { LiveCatalogView(source: .materials) }
                        }
                    }.navigationTitle("工作台")
                }
            }
            Tab("故障报修", systemImage: "wrench.and.screwdriver") { NavigationStack { LiveWorkflowList(kind: .fault) } }
            Tab("我的", systemImage: "person.crop.circle") {
                NavigationStack {
                    List {
                        Section { LabeledContent("账号", value: session.authentication.text("name")); LabeledContent("姓名", value: session.authentication.text("fullName")) }
                        Section { Button("退出登录", role: .destructive) { session.signOut() } }
                    }.navigationTitle("我的")
                }
            }
        }
    }
}

struct LiveWorkflowList: View {
    @EnvironmentObject private var session: LiveSession
    let kind: WorkflowKind
    @State private var state: String
    @State private var rows: [JSONValue] = []
    @State private var query = ""
    @State private var loading = true
    @State private var error: String?
    @State private var last = true
    @State private var adding = false
    @State private var loadingMore = false
    init(kind: WorkflowKind, initialState: String = "REPORTED") { self.kind = kind; _state = State(initialValue: initialState) }
    var body: some View {
        List {
            if kind == .fault {
                Section {
                    Picker("状态", selection: $state) {
                        Text("待受理").tag("REPORTED"); Text("待完成").tag("REPAIRED"); Text("待确认").tag("VERIFIED"); Text("已完成").tag("FINISHED")
                        if !["REPORTED", "REPAIRED", "VERIFIED", "FINISHED"].contains(state) { Text(faultStateName(state)).tag(state) }
                    }.pickerStyle(.segmented)
                }
            }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, item in
                NavigationLink { LiveWorkflowDetail(kind: kind, id: item[kind.idKey].text) } label: {
                    VStack(alignment: .leading, spacing: 7) {
                        Text(kind == .fault ? item["faultName"].text : item["outNumber"].text).font(.headline)
                        if kind == .fault { Text("\(item["asset"]["assetName"].text) · \(item["asset"]["assetCode"].text)").font(.subheadline) }
                        Text(kind == .fault ? faultStateName(item["faultState"].text) : item["approvalState"].text).font(.caption).foregroundStyle(.secondary)
                        Text(item["sn"].text).font(.caption).foregroundStyle(.secondary)
                    }.padding(.vertical, 4)
                }
            }
            if !last { Button { Task { await reload(more: true) } } label: { HStack { Text("加载更多"); if loadingMore { ProgressView() } } }.disabled(loadingMore) }
            if let error { Section { Text(error).foregroundStyle(.red); Button("重试") { Task { await reload() } } } }
            if !loading && rows.isEmpty && error == nil { ContentUnavailableView("暂无单据", systemImage: "tray") }
        }
        .overlay { if loading && rows.isEmpty { ProgressView("正在加载…") } }
        .navigationTitle(kind.title)
        .searchable(text: $query, prompt: kind == .fault ? "搜索报修名称" : "搜索出库单号")
        .onSubmit(of: .search) { Task { await reload() } }
        .onChange(of: state) { Task { await reload() } }
        .refreshable { await reload() }
        .task { await reload() }
        .toolbar {
            if kind == .fault {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("其他状态", systemImage: "line.3.horizontal.decrease") {
                        ForEach(["UNTREATED", "RECEIVED", "SIGNED", "RESOLVED", ""], id: \.self) { value in Button(faultStateName(value)) { state = value } }
                    }
                }
            }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) { Button("新建", systemImage: "plus") { adding = true }.labelStyle(.iconOnly).buttonStyle(.glassProminent).accessibilityLabel(kind == .fault ? "新建报修" : "新建领料单") }
        }
        .sheet(isPresented: $adding, onDismiss: { Task { await reload() } }) {
            NavigationStack { if kind == .fault { OriginalFaultForm() } else { OriginalERPForm() } }
        }
    }
    private func reload(more: Bool = false) async {
        if more { loadingMore = true } else { loading = true }
        error = nil
        defer { loading = false; loadingMore = false }
        do {
            var filters: [JSONValue] = []
            if kind == .fault && !state.isEmpty { filters.append(filter("faultState", state)) }
            if !query.isEmpty { filters.append(filter(kind == .fault ? "faultName" : "outNumber", query, "like")) }
            let data = try await session.page(kind.page, offset: more ? rows.count : 0, filters: filters)
            rows = more ? rows + data["content"].array : data["content"].array
            last = data["last"].bool
        } catch { self.error = error.localizedDescription }
    }
}
func faultStateName(_ state: String) -> String {
    ["UNTREATED": "报修", "REPORTED": "待受理", "RECEIVED": "待抢单", "SIGNED": "待签到", "REPAIRED": "待完成", "VERIFIED": "待确认", "RESOLVED": "待复线", "FINISHED": "已完结", "": "全部"][state] ?? state
}

struct LiveWorkflowDetail: View {
    @EnvironmentObject private var session: LiveSession
    let kind: WorkflowKind
    let id: String
    @State private var detail: JSONValue = .null
    @State private var materials: [JSONValue] = []
    @State private var taskState: JSONValue = .null
    @State private var loading = true
    @State private var error: String?
    @State private var editing = false
    @State private var processing = false
    private var task: JSONValue { detail["taskInfo"] }
    var body: some View {
        Group {
            if detail == .null { LoadingFailure(error: error) { Task { await load() } } }
            else {
                List {
                    if kind == .fault {
                        Section("故障报修工单信息") {
                            LabeledContent("报修名称", value: detail["faultName"].text)
                            LabeledContent("单号", value: detail["sn"].text)
                            LabeledContent("状态", value: faultStateName(detail["faultState"].text))
                            LabeledContent("故障时间", value: readableDate(detail["faultTime"]))
                            NavigationLink { LiveRecordFacts(record: detail["asset"], title: "资产详情", fields: assetFields) } label: { LabeledContent("报修资产", value: detail["asset"]["assetName"].text) }
                            LabeledContent("故障申请人", value: detail["applyUser"].object.label)
                            LabeledContent("申请部门", value: detail["applyDept"].object.label)
                            LabeledContent("受理部门", value: detail["dept"].object.label)
                            LabeledContent("维修受理人", value: detail["user"].object.label)
                            LabeledContent("报修人联系电话", value: detail["userPhone"].text)
                            LabeledContent("维修班组", value: detail["userGroup"].object.label)
                            LabeledContent("ERP记账部门", value: detail["division"].object.label)
                        }
                        if !detail["faultDesc"].text.isEmpty { Section("故障描述") { Text(detail["faultDesc"].text) } }
                        if !detail["albums"].array.isEmpty { Section("现场照片") { ForEach(Array(detail["albums"].array.enumerated()), id: \.offset) { _, photo in AttachmentRow(photo: photo) } } }
                    } else {
                        Section("ERP出库详情") {
                            ForEach([("outNumber", "出库单号"), ("sn", "单号"), ("user.realName", "申请人"), ("applyDate", "申请日期"), ("projectCode", "项目号"), ("projectDesc", "项目名称"), ("referenceSn", "关联单号"), ("recipientUser.realName", "领用人"), ("dept.deptName", "领用部门"), ("division.divisionDesc", "ERP记账部门"), ("outboundTime", "出库时间")], id: \.0) { key, label in LabeledContent(label, value: readableDate(detail.at(key))) }
                        }
                        Section("出库物料 · \(materials.count) 项") {
                            ForEach(Array(materials.enumerated()), id: \.offset) { _, line in
                                DisclosureGroup(line["material"]["materialDesc"].text) {
                                    MaterialFacts(material: line["material"])
                                    LabeledContent("申请数量", value: line["entryQuantity"].text)
                                    LabeledContent("实发数量", value: line["actualQuantity"].text)
                                }
                            }
                        }
                        Section("用途") { Text(detail["remark"].text) }
                    }
                    if !detail["processInstanceId"].text.isEmpty {
                        Section { NavigationLink("处理流程", systemImage: "point.3.connected.trianglepath.dotted") { WorkflowNodesView(processID: detail["processInstanceId"].text) } }
                    }
                    if let error { Section { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } } }
                }
                .refreshable { await load() }
                .overlay { if loading { ProgressView("正在刷新…").padding().background(.regularMaterial, in: .capsule) } }
            }
        }
        .navigationTitle(kind == .fault ? "报修详情" : "出库详情").navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .toolbar {
            if canEdit {
                ToolbarItem(placement: .topBarTrailing) { Button("编辑") { editing = true } }
            }
            if !task["id"].text.isEmpty && (taskState["canSubmit"].bool || taskState["canDelegate"].bool || taskState["canResolveDelegate"].bool) {
                ToolbarSpacer(.flexible, placement: .bottomBar)
                ToolbarItem(placement: .bottomBar) { Button(task["name"].text) { processing = true }.buttonStyle(.glassProminent).disabled(loading) }
            }
        }
        .sheet(isPresented: $editing, onDismiss: { Task { await load() } }) {
            NavigationStack { if kind == .fault { OriginalFaultForm(existing: detail.object) } else { OriginalERPForm(existing: detail.object) } }
        }
        .sheet(isPresented: $processing, onDismiss: { Task { await load() } }) {
            NavigationStack {
                if kind == .fault && task["category"].text == "REPAIRED" { OriginalRepairForm(faultID: id, task: task) }
                else { WorkflowTaskView(task: task, kind: kind) }
            }
        }
    }
    private var canEdit: Bool {
        kind == .fault ? detail["faultState"].text == "UNTREATED" && !task["id"].text.isEmpty : detail["processInstanceId"].text.isEmpty && detail["createBy"].text == session.authentication.text("name")
    }
    private func load() async {
        loading = true; error = nil
        defer { loading = false }
        do {
            detail = try await session.request(kind.detailPath, query: [kind.idKey: id])
            if kind == .erp { materials = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": id]).array }
            if !detail["processInstanceId"].text.isEmpty { taskState = try await session.request("workflow/getWorkflowTaskState", query: ["processInstanceId": detail["processInstanceId"].text]) }
        } catch { self.error = error.localizedDescription }
    }
}

func readableDate(_ v: JSONValue) -> String {
    let numbers = v.array.map(\.text)
    if numbers.count >= 5 { return "\(numbers[0])-\(numbers[1])-\(numbers[2]) \(numbers[3]):\(numbers[4])" }
    return v.text
}
let assetFields = [("assetCode", "资产编号"), ("assetName", "资产名称"), ("model", "规格型号"), ("addressCode", "机台号"), ("address", "地址"), ("dept.deptName", "使用部门"), ("user.realName", "责任人"), ("assetCategory.categoryName", "资产类别"), ("useState", "使用状态"), ("manufacturer", "制造商"), ("oldAssetCode", "旧资产编号")]
struct LiveRecordFacts: View {
    let record: JSONValue
    let title: String
    let fields: [(String, String)]
    var body: some View { List { ForEach(fields, id: \.0) { key, label in if record.at(key) != .null { LabeledContent(label, value: readableDate(record.at(key))) } } }.navigationTitle(title).navigationBarTitleDisplayMode(.inline) }
}
struct AttachmentRow: View {
    let photo: JSONValue
    var body: some View {
        if let url = URL(string: photo["url"].text) {
            Link(destination: url) { Label(photo["name"].text.isEmpty ? "查看照片" : photo["name"].text, systemImage: "photo") }
        }
    }
}
struct LiveCatalogView: View {
    @EnvironmentObject private var session: LiveSession
    let source: ReferenceSource
    @State private var query = ""
    @State private var byCode = false
    @State private var rows: [JSONValue] = []
    @State private var loading = true
    @State private var error: String?
    @State private var last = true
    var body: some View {
        List {
            Section { Toggle("按编号搜索", isOn: $byCode) }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, record in
                NavigationLink {
                    if source == .assets { LiveRecordFacts(record: record, title: "资产详情", fields: assetFields) }
                    else { List { Section(record["materialDesc"].text) { MaterialFacts(material: record); LabeledContent("库存数量", value: record["onhandQty"].text) } }.navigationTitle("库存详情") }
                } label: { VStack(alignment: .leading, spacing: 5) { Text(record.object.label).font(.headline); Text(record[source == .assets ? "assetCode" : "materialCode"].text).font(.subheadline).foregroundStyle(.secondary) } }
            }
            if !last { Button("加载更多") { Task { await load(more: true) } }.disabled(loading) }
            if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
        }
        .overlay { if loading { ProgressView("正在加载…") } }
        .navigationTitle(source == .assets ? "资产台账" : "库存")
        .searchable(text: $query, prompt: byCode ? "搜索编号" : "搜索名称")
        .onSubmit(of: .search) { Task { await load() } }
        .onChange(of: byCode) { Task { await load() } }
        .task { await load() }.refreshable { await load() }
    }
    private func load(more: Bool = false) async {
        loading = true; error = nil; defer { loading = false }
        do {
            let field = byCode ? (source == .assets ? "assetCode" : "materialCode") : source.searchField
            let result = try await session.page(source.pagePath!, offset: more ? rows.count : 0, filters: query.isEmpty ? [] : [filter(field, query, "like")])
            rows = more ? rows + result["content"].array : result["content"].array; last = result["last"].bool
        } catch { self.error = error.localizedDescription }
    }
}

struct WorkflowNodesView: View {
    @EnvironmentObject private var session: LiveSession
    let processID: String
    var preview = false
    @State private var history: [JSONValue] = []
    @State private var definition: [JSONValue] = []
    @State private var loading = true
    @State private var error: String?
    var body: some View {
        List {
            Section("处理记录") {
                ForEach(Array(history.enumerated()), id: \.offset) { _, node in
                    DisclosureGroup {
                        if !node["assignee"].text.isEmpty { LabeledContent("处理人", value: node["assignee"].text) }
                        LabeledContent("开始时间", value: node["startTimeText"].text)
                        if !node["endTimeText"].text.isEmpty { LabeledContent("完成时间", value: node["endTimeText"].text) }
                        if !node["condition"].text.isEmpty { LabeledContent("处理结果", value: node["condition"].text) }
                        if !node["countersignStatusText"].text.isEmpty { Text(node["countersignStatusText"].text) }
                        ForEach(Array(node["comments"].array.enumerated()), id: \.offset) { _, comment in Text(comment["fullMessage"].text.isEmpty ? comment["message"].text : comment["fullMessage"].text) }
                        ForEach([("countersignCompletedAssignees", "已处理会签人"), ("countersignPendingAssignees", "待处理会签人")], id: \.0) { key, label in if !node[key].text.isEmpty { LabeledContent(label, value: node[key].text) } }
                    } label: {
                        Label {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(node["activityName"].text)
                                Text("\(node["displayTimePrefix"].text) \(node["displayTimeText"].text)").font(.caption).foregroundStyle(.secondary)
                            }
                        } icon: { Image(systemName: node["endTime"] == .null && node["activityType"].text == "userTask" ? "circle.inset.filled" : "checkmark.circle").foregroundStyle(.tint) }
                    }
                }
            }
            Section("流程节点") {
                ForEach(Array(definition.enumerated()), id: \.offset) { _, node in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(node["taskName"].text)
                        let records = history.filter { $0["activityId"].text == node["taskKey"].text }
                        Text(records.isEmpty ? "尚无处理记录" : records.contains { $0["endTime"] == .null } ? "当前节点" : "已有处理记录").font(.caption).foregroundStyle(.secondary)
                        if !node["assignees"].text.isEmpty { Text(node["assignees"].text).font(.caption).foregroundStyle(.secondary) }
                    }
                }
            }
            if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
            if preview { Text("流程结构演示，不是真实工单记录").font(.footnote).foregroundStyle(.secondary) }
        }
        .overlay { if loading { ProgressView("正在加载流程…") } }
        .navigationTitle("处理流程").navigationBarTitleDisplayMode(.inline)
        .task { await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        if preview {
            definition = ["报修", "受理", "维修", "确认", "复线"].enumerated().map { .object(["taskName": .string($0.element), "taskKey": .string("DEMO-\($0.offset)")]) }
            history = [.object(["activityName": .string("报修"), "activityId": .string("DEMO-0"), "activityType": .string("userTask"), "endTime": .string("2026-10-15 09:30"), "displayTimePrefix": .string("完成"), "displayTimeText": .string("2026-10-15 09:30")]), .object(["activityName": .string("受理"), "activityId": .string("DEMO-1"), "activityType": .string("userTask"), "endTime": .null, "displayTimePrefix": .string("当前"), "displayTimeText": .string("2026-10-15 09:35")])]
            return
        }
        do {
            history = try await session.request("workflow/getWorkflowHistoryNodesByProcessInstanceId", query: ["processInstanceId": processID]).array
            definition = try await session.request("workflow/getAssigneeElementsByProcessInstanceId", query: ["processInstanceId": processID]).array
        } catch { self.error = error.localizedDescription }
    }
}
