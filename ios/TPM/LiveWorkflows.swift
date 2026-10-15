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
    var body: some View { if session.signedIn { LiveTabs().id(session.scopeRevision) } else { NativeLoginView() } }
}
struct LiveTabs: View {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var session: LiveSession
    @State private var selection = 0
    var body: some View {
        NativeTabShell(selection: $selection) {
            NavigationStack { DashboardHome() }.tabItem { Label("主页", systemImage: "house") }.tag(0)
            NavigationStack { TodoInboxView() }.tabItem { Label("我的待办", systemImage: "checklist") }.tag(2)
            NavigationStack { DashboardWorkspace() }.tabItem { Label("工作台", systemImage: "square.grid.2x2") }.tag(1)
                NavigationStack {
                    List {
                        Section { LabeledContent("账号", value: session.authentication.text("name")); LabeledContent("姓名", value: session.authentication.text("fullName")) }
                        Section { LabeledContent("当前厂区", value: session.currentFactoryName) }
                        Section { Button("退出登录", role: .destructive) { session.signOut() } }
                    }.navigationTitle("我的").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
                }
                .tabItem { Label("我的", systemImage: "person.crop.circle") }.tag(3)
        }
    }
}
struct LiveWorkflowList: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    let kind: WorkflowKind
    @State private var state: String
    @State private var rows: [JSONValue] = []
    @State private var query = ""
    @State private var searchField: FaultSearchField = .name
    @State private var searching = false
    @State private var loading = true
    @State private var error: String?
    @State private var last = true
    @State private var adding = false
    @State private var loadingMore = false
    @State private var stateCounts: [String: Int] = [:]
    init(kind: WorkflowKind, initialState: String = "RECEIVED") { self.kind = kind; _state = State(initialValue: initialState) }
    var body: some View {
        VStack(spacing: 0) {
            if kind == .fault { WorkOrderStatusStrip(selection: Binding(get: { FaultState.allCases.firstIndex { $0.code == state } ?? 2 }, set: { state = FaultState.allCases[$0].code }), counts: stateCounts) }
        List {
            if kind == .fault && searching { Section { Picker("搜索分类", selection: $searchField) { ForEach(FaultSearchField.allCases) { Text($0.rawValue).tag($0) } } } }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, item in
                WorkOrderCard(title: kind == .fault ? item["faultName"].text : erpCardTitle(item),
                    status: kind == .fault ? faultStateName(item["faultState"].text) : approvalStateName(item["approvalState"].text),
                    color: kind == .fault ? faultDisplayColor(item["faultState"].text) : approvalDisplayColor(item["approvalState"].text),
                    reference: kind == .fault ? item["asset"]["assetCode"].text : item["outNumber"].text,
                    referenceTitle: kind == .fault ? "资产编码" : "出库单号",
                    time: readableDate(item[kind == .fault ? "faultTime" : "applyDate"]),
                    summaryFacts: kind == .fault ? [] : [("领用人", item["recipientUser"]["realName"].text), ("领用部门", item["dept"]["deptName"].text)],
                    facts: kind == .fault ? faultCardFacts(item) : erpCardFacts(item)) {
                    LiveWorkflowDetail(kind: kind, id: item[kind.idKey].text)
                }

                .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
            }
            if !last { Button { Task { await reload(more: true) } } label: { HStack { Text("加载更多"); if loadingMore { ProgressView() } } }.disabled(loadingMore) }
            if let error { Section { Text(error).foregroundStyle(.red); Button("重试") { Task { await reload() } } } }
            if !loading && rows.isEmpty && error == nil { CompactEmptyState(title: "暂无相关单据") }
        }
        .simultaneousGesture(DragGesture(minimumDistance: 45).onEnded { value in
            guard kind == .fault, abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
            let index = FaultState.allCases.firstIndex { $0.code == state } ?? 2
            state = FaultState.allCases[min(max(index + (value.translation.width < 0 ? 1 : -1), 0), 7)].code
        })
        }
        .overlay { AnimatedLoadingOverlay(isPresented: loading, title: "正在加载工单…") }
        .navigationTitle(kind.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: kind == .fault ? "搜索" + searchField.rawValue : "搜索出库单号", onSubmit: { Task { await reload() } })
        
        .refreshable { await reload(); await reloadCounts() }
        .task(id: "\(navigation.refresh)-\(state)-\(searchField.rawValue)-\(query)") { await reload() }
        .task(id: "counts-\(navigation.refresh)-\(searchField.rawValue)-\(query)") { await reloadCounts() }
        .onAppear { navigation.newDocument = kind; navigation.hideTabs = false }
        .onDisappear { if navigation.newDocument == kind { navigation.newDocument = nil } }
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
            if !query.isEmpty { filters.append(filter(kind == .fault ? searchField.key : "outNumber", query, "like")) }
            let data = try await session.page(kind.page, offset: more ? rows.count : 0, filters: filters)
            try Task.checkCancellation()
            rows = more ? rows + data["content"].array : data["content"].array
            last = data["last"].bool
            if kind == .fault, let count = serverPageCount(data) { stateCounts[state] = count }
        } catch { self.error = error.localizedDescription }
    }
    private func reloadCounts() async {
        guard kind == .fault else { return }
        stateCounts = [:]
        let searchFilters = query.isEmpty ? [] : [filter(searchField.key, query, "like")]
        let api = session
        await withTaskGroup(of: (String, Int?).self) { group in
            for stage in FaultState.allCases {
                let code = stage.code
                group.addTask {
                    do {
                        let response = try await api.page("fault/pageAssetFault", filters: searchFilters + [filter("faultState", code)], limit: 1)
                        return (code, serverPageCount(response))
                    } catch { return (code, nil) }
                }
            }
            for await (code, count) in group {
                guard !Task.isCancelled else { return }
                if let count { stateCounts[code] = count }
            }
        }
    }
}
func faultStateName(_ state: String) -> String {
    ["UNTREATED": "报修", "REPORTED": "待受理", "RECEIVED": "待抢单", "SIGNED": "待签到", "REPAIRED": "待完成", "VERIFIED": "待确认", "RESOLVED": "待复线", "FINISHED": "已完结", "": "全部"][state] ?? state
}

// Labels from the original app's abstractApprove.approvalState dictionary.
func approvalStateName(_ state: String) -> String {
    ["PENDING": "待审批", "EXECUTING": "审批中", "REFUSE": "拒绝", "AGREE": "同意", "FINISHED": "完成", "SUBMIT": "待提交", "APPROVED": "待审批", "CONFIRM": "待确认"][state] ?? "状态待核对"
}

func erpCardTitle(_ item: JSONValue) -> String {
    let purpose = item["remark"].text.trimmingCharacters(in: .whitespacesAndNewlines)
    return purpose.isEmpty ? "ERP 领料申请" : purpose
}

func erpCardFacts(_ item: JSONValue) -> [(String, String)] {
    [("申请人", item["user"]["realName"].text), ("项目号", item["projectCode"].text), ("项目名称", item["projectDesc"].text), ("关联单号", item["referenceSn"].text), ("ERP记账部门", item["division"]["divisionDesc"].text), ("出库时间", readableDate(item["outboundTime"]))]
        .filter { !$0.1.isEmpty }
}

struct LiveWorkflowDetail: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var navigation: BottomNavigationModel
    private func completed() { processing = false; returning = false; editing = false; navigation.refresh = UUID(); dismiss() }
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
    @State private var returning = false
    @State private var returnAvailable = false
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
                            HStack { Text("状态"); Spacer(); Text(faultStateName(detail["faultState"].text)).font(.body.weight(.semibold)).foregroundStyle(faultDisplayColor(detail["faultState"].text)) }
                            LabeledContent("故障时间", value: readableDate(detail["faultTime"]))
                            NavigationLink { AssetRecordDetail(record: detail["asset"]) } label: { LabeledContent("报修资产", value: detail["asset"]["assetName"].text) }
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
                                    LabeledContent("申请数量", value: materialQuantity(line["entryQuantity"].text, material: line["material"]))
                                    LabeledContent("实发数量", value: materialQuantity(line["actualQuantity"].text, material: line["material"]))
                                }
                            }
                        }
                        Section("用途") { Text(detail["remark"].text) }
                    }
                    if !detail["processInstanceId"].text.isEmpty {
                        Section {
                            NavigationLink { WorkflowNodesView(processID: detail["processInstanceId"].text) } label: {
                                Label("处理流程", systemImage: "point.3.connected.trianglepath.dotted")
                            }
                        }
                    }
                    if let error { Section { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } } }
                }
                .refreshable { await load() }
                .overlay { AnimatedLoadingOverlay(isPresented: loading, title: "正在刷新…") }
            }
        }
        .navigationTitle(kind == .fault ? "报修详情" : "出库详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .task { await load() }
        .toolbar {
            if canEdit {
                ToolbarItem(placement: .topBarTrailing) { Button("编辑") { editing = true } }
            }
        }
        .safeAreaInset(edge: .bottom) {
            if !task["id"].text.isEmpty && (taskState["canSubmit"].bool || taskState["canDelegate"].bool || taskState["canResolveDelegate"].bool) {
                HStack { if returnAvailable { BottomAction(title: "退单", symbol: "arrow.uturn.backward", color: .orange) { returning = true }.disabled(loading) }; Spacer(); BottomAction(title: task["name"].text, symbol: "arrow.right.circle") { processing = true }.disabled(loading) }.padding(.horizontal, 20).padding(.vertical, 8)
            }
        }
        .sheet(isPresented: $returning, onDismiss: { Task { await load() } }) { NavigationStack { WorkflowTaskView(task: task, kind: kind, previewReturn: true, onCompleted: completed) } }
        .sheet(isPresented: $editing, onDismiss: { Task { await load() } }) {
            NavigationStack { if kind == .fault { OriginalFaultForm(existing: detail.object, onSaved: completed) } else { OriginalERPForm(existing: detail.object, onSaved: { _ in completed() }) } }
        }
        .sheet(isPresented: $processing, onDismiss: { Task { await load() } }) {
            NavigationStack {
                if kind == .fault && task["category"].text == "REPAIRED" { OriginalRepairForm(faultID: id, task: task, onCompleted: completed) }
                else { WorkflowTaskView(task: task, kind: kind, onCompleted: completed) }
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
            returnAvailable = false
            if kind == .fault && !task["id"].text.isEmpty && taskState["canSubmit"].bool {
                let form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": task["id"].text])
                returnAvailable = form["flowConditions"].array.contains { isReturnCondition($0) }
            }
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
    var body: some View { List { ForEach(fields, id: \.0) { key, label in if record.at(key) != .null { LabeledContent(label, value: readableDate(record.at(key))) } } }.navigationTitle(title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome().modifier(DetailTabVisibility()) }
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
    @State private var searching = false
    @State private var rows: [JSONValue] = []
    @State private var loading = true
    @State private var error: String?
    @State private var last = true
    var body: some View {
        List {
            if searching { Section { Toggle("按编号搜索", isOn: $byCode) } }
            ForEach(Array(rows.enumerated()), id: \.offset) { _, record in
                NavigationLink {
                    if source == .assets { AssetRecordDetail(record: record) }
                    else { List { Section(record["materialDesc"].text) { MaterialFacts(material: record); LabeledContent("库存数量", value: materialQuantity(record["onhandQty"].text, material: record)) } }.navigationTitle("库存详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome().modifier(DetailTabVisibility()) }
                } label: { VStack(alignment: .leading, spacing: 5) {
                    WrappingContent { Text(record.object.label).font(.headline) }
                    WrappingContent { Text(record[source == .assets ? "assetCode" : "materialCode"].text).font(.subheadline).foregroundStyle(.secondary) }
                } }
                .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
            }
            if !last { Button("加载更多") { Task { await load(more: true) } }.disabled(loading) }
            if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
        }
        .overlay { AnimatedLoadingOverlay(isPresented: loading, title: "正在加载…") }
        .navigationTitle(source == .assets ? "资产台账" : "库存")
        .modifier(ListTabVisibility())
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: byCode ? "搜索编号" : "搜索名称", onSubmit: { Task { await load() } })
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
            if !history.isEmpty {
            Section("处理记录") {
                ForEach(Array(history.enumerated()), id: \.offset) { _, node in
                    DisclosureGroup {
                        let assignee = workflowParticipantLabel(node["assignee"].text)
                        if !assignee.isEmpty { LabeledContent("处理人", value: assignee) }
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
                                Text("\(node["displayTimePrefix"].text) \(node["displayTimeText"].text)").font(.subheadline).foregroundStyle(.secondary)
                            }
                        } icon: { Image(systemName: node["endTime"] == .null && node["activityType"].text == "userTask" ? "circle.inset.filled" : "checkmark.circle").foregroundStyle(.tint) }
                    }
                }
            }
            }
            if !definition.isEmpty {
            Section("流程节点") {
                ForEach(Array(definition.enumerated()), id: \.offset) { _, node in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(node["taskName"].text)
                        let records = history.filter { $0["activityId"].text == node["taskKey"].text }
                        Text(records.isEmpty ? "尚无处理记录" : records.contains { $0["endTime"] == .null } ? "当前节点" : "已有处理记录").font(.subheadline).foregroundStyle(.secondary)
                        let participant = workflowParticipantLabel(node["assignees"].text)
                        if !participant.isEmpty { Text(participant).font(.subheadline).foregroundStyle(.secondary) }
                    }
                }
            }
            }
            if !loading && history.isEmpty && definition.isEmpty && error == nil { CompactEmptyState(title: "暂无流程记录") }
            if let error { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } }
            if preview { Text("流程结构演示，不是真实工单记录").font(.footnote).foregroundStyle(.secondary) }
        }
        .initialLoadingContent(loading && history.isEmpty && definition.isEmpty)
        .overlay { AnimatedLoadingOverlay(isPresented: loading, title: "正在加载流程…") }
        .navigationTitle("处理流程").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .task { await load() }.refreshable { await load() }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        if preview {
            definition = ["报修", "受理", "抢单", "签到", "维修", "确认", "复线", "完结"].enumerated().map { .object(["taskName": .string($0.element), "taskKey": .string("DEMO-\($0.offset)")]) }
            history = [.object(["activityName": .string("报修"), "activityId": .string("DEMO-0"), "activityType": .string("userTask"), "endTime": .string("2026-10-15 09:30"), "displayTimePrefix": .string("完成"), "displayTimeText": .string("2026-10-15 09:30")]), .object(["activityName": .string("受理"), "activityId": .string("DEMO-1"), "activityType": .string("userTask"), "endTime": .null, "displayTimePrefix": .string("当前"), "displayTimeText": .string("2026-10-15 09:35")])]
            return
        }
        do {
            let loadedHistory = try await session.request("workflow/getWorkflowHistoryNodesByProcessInstanceId", query: ["processInstanceId": processID]).array
            let loadedDefinition = try await session.request("workflow/getAssigneeElementsByProcessInstanceId", query: ["processInstanceId": processID]).array
            history = loadedHistory
            definition = loadedDefinition
        } catch { self.error = error.localizedDescription }
    }
}

func faultCardFacts(_ item: JSONValue) -> [(String, String)] {
    [("报修名称", item["faultName"].text), ("资产编码", item["asset"]["assetCode"].text), ("资产名称", item["asset"]["assetName"].text), ("机台号", item["asset"]["addressCode"].text), ("地址", item["asset"]["address"].text), ("报修人", item["applyUser"]["realName"].text), ("报修时间", readableDate(item["faultTime"])), ("受理组别", item["userGroup"]["groupName"].text), ("受理部门", item["dept"]["deptName"].text), ("受理人", item["user"]["realName"].text), ("故障描述", item["faultDesc"].text)]
}
