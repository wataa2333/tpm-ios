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
    var body: some View {
        // Switching factory rebuilds the pages for the new factory. They are not cut over: the old ones
        // dim while the request runs, then fade out as the new ones fade in.
        ZStack {
            if session.signedIn {
                LiveTabs().id(session.scopeRevision)
                    .transition(.opacity)
                    .opacity(session.switchingFactory ? 0.35 : 1)
            } else {
                NativeLoginView().transition(.opacity)
            }
        }
        .interfaceMotion(session.scopeRevision, duration: 0.7)
        .interfaceMotion(session.switchingFactory, duration: 0.4)
        .interfaceMotion(session.signedIn, duration: 0.45)
        // Pins, defaults and drafts saved under the old "name@factory name" scope move to the id-based one.
        .task(id: session.scopeRevision) { session.migrateLegacyScope() }
    }
}

struct LiveTabs: View {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var session: LiveSession
    @State private var selection = 0
    var body: some View {
        NativeTabShell(selection: $selection, mineTitle: accountTitle) {
            DashboardHome()
        } todo: {
            TodoInboxView()
        } workspace: {
            DashboardWorkspace()
        } mine: {
            ProfileTab()
        }
        // Only when something changed (a task was finished, a manual refresh): the todo page itself keeps the
        // number current whenever it loads, so switching tabs asks for nothing.
        .task(id: navigation.refresh) { await refreshTodoCount() }
    }
    /// The last tab carries the account's own name, as on the profile page.
    private var accountTitle: String {
        let full = session.authentication.text("fullName")
        return full.isEmpty ? (session.authentication.text("name").isEmpty ? "我的" : session.authentication.text("name")) : full
    }
    /// One tiny request: only the total of the open todo tasks is needed for the badge.
    private func refreshTodoCount() async {
        do {
            let page = try await session.page("process/pageUserTodoTask", limit: 1)
            try Task.checkCancellation()
            if let count = serverPageCount(page) { navigation.todoCount = count }
        } catch {}
    }
}
struct LiveWorkflowList: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @EnvironmentObject private var navigation: BottomNavigationModel
    let kind: WorkflowKind
    private let independent: Bool
    @State private var state: String
    @State private var rows: [JSONValue] = []
    @State private var query = ""
    /// Quick-query result pages search only what was confirmed (Return), never every keystroke.
    @State private var committed = ""
    @State private var searchField: FaultSearchField = .name
    @State private var searching = false
    @State private var loading = true
    @State private var refreshing = false
    @State private var error: String?
    @State private var last = true
    @State private var adding = false
    @State private var loadingMore = false
    @State private var total: Int?
    @State private var dateRange: DateRange?
    @State private var choosingDates = false
    @State private var stateCounts: [String: Int] = [:]
    @StateObject private var gate = ReloadGate()
    /// Selection mode lives here, in the list, so opening a peek or a batch sheet never reloads or resets it.
    @StateObject private var batch = BatchSelection()
    private let customTitle: String?
    private let initialQuery: String
    /// `initialQuery` and `initialField` open the list already searching, as the quick-query cards do.
    init(kind: WorkflowKind, initialState: String = "RECEIVED", initialQuery: String = "", initialField: FaultSearchField = .name, title: String? = nil, independent: Bool = false) {
        self.kind = kind
        self.independent = independent
        self.customTitle = title
        self.initialQuery = initialQuery
        _state = State(initialValue: initialState)
        _query = State(initialValue: initialQuery)
        _committed = State(initialValue: initialQuery)
        _searchField = State(initialValue: initialField)
    }
    private var dateKey: String { dateRange?.key ?? "" }
    /// A page opened from a quick query: it lists every order that matches, whatever its status, so it has no
    /// status strip and no per-status counts.
    private var searchMode: Bool { !initialQuery.isEmpty }
    /// A quick-query page is titled by what it is searching for now ("机台号 A-F08"), following each confirmed
    /// search and the chosen search field; other pages keep their own title.
    private var pageTitle: String {
        if searchMode { return searchField.rawValue + " " + committed }
        return query == initialQuery ? (customTitle ?? kind.title) : kind.title
    }
    private var activeQuery: String { searchMode ? committed : query }
    private var usesQueryAccount: Bool { independent || searchMode }
    private var readContext: String { usesQueryAccount ? queries.revision.uuidString : "operator" }
    private func commitQuery() {
        let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { query = committed } else { query = text; committed = text }
    }
    /// The search words and the date range, as filters shared by the list and the per-state counts.
    private var searchFilters: [JSONValue] {
        var filters: [JSONValue] = []
        if !activeQuery.isEmpty { filters.append(filter(kind == .fault ? searchField.key : "outNumber", activeQuery, "like")) }
        if kind == .fault, let dateRange { filters.append(dateRangeFilter("faultTime", dateRange)) }
        return filters
    }
    var body: some View {
        CardPage {
            if usesQueryAccount {
                Text("查询：" + queries.label(operator: session) + " · 操作：" + session.authentication.text("name")).animatedTextValue("查询：" + queries.label(operator: session) + " · 操作：" + session.authentication.text("name"))
                    .font(AppFont.caption).foregroundStyle(.secondary).padding(.horizontal, 16)
            }
            if kind == .fault && !searchMode { WorkOrderStatusStrip(selection: Binding(get: { FaultState.allCases.firstIndex { $0.code == state } ?? -1 }, set: { state = FaultState.allCases[$0].code }), counts: stateCounts) }
            if let activeRange = dateRange { DateRangeChip(range: activeRange) { self.dateRange = nil } }
            LoadingRow(isPresented: (loading || gate.busy) && !refreshing, title: "正在加载工单")
            if kind == .fault && searching { CardGroup { Picker("搜索分类", selection: $searchField) { ForEach(FaultSearchField.allCases) { Text($0.rawValue).animatedTextValue($0.rawValue).tag($0) } }.padding(.horizontal, 16).padding(.vertical, 6) } }
            CardGroup {
                ForEach(Array(rows.enumerated()), id: \.offset) { _, item in
                    let entry = batchItem(kind: kind, item: item)
                    LiveOrderRow(kind: kind, item: item, usesQueryAccount: usesQueryAccount, selectionMode: batch.active, selected: batch.contains(entry.id), selectable: batch.isSelectable(entry), batch: batch).equatable()
                }
                if !loading && rows.isEmpty && error == nil { CompactEmptyState(title: "暂无相关单据") }
            }.opacity((loading || gate.busy) && !loadingMore ? 0.4 : 1).interfaceMotion(loading || gate.busy, duration: 0.4)
            LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || loadingMore, loaded: rows.count, total: total) { await reload(more: true) }
        }
        .navigationTitle(pageTitle).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: kind == .fault ? "搜索" + searchField.rawValue : "搜索出库单号",
                          collapsesOnSubmit: searchMode, onSubmit: { if searchMode { commitQuery() } })

        .refreshable { refreshing = true; await reload(); await reloadCounts(); refreshing = false }
        .reloadsToTop(on: gate.id("\(navigation.refresh)-\(state)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)"))
        .interfaceMotion(loading || gate.busy, duration: 0.45)
        .toolbar {
            if kind == .fault {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { choosingDates = true } label: { Image(systemName: dateRange == nil ? "calendar" : "calendar.badge.checkmark") }
                        .accessibilityLabel("按日期筛选").accessibilityIdentifier("date-range-button")
                }
            }
        }
        .refreshButton(busy: loading || loadingMore || gate.busy) { gate.request() }
        .batchHost(batch) { rows.map { batchItem(kind: kind, item: $0) } }
        .environment(\.queryReading, usesQueryAccount)
        .onChange(of: state) { batch.clear() }
        .onChange(of: readContext) { rows = []; stateCounts = [:]; total = nil; last = true; batch.end() }
        .sheet(isPresented: $choosingDates) { DateRangeSheet(range: $dateRange) }
        .errorDialog($error, retry: { Task { await reload() } })
        .task(id: gate.id("\(navigation.refresh)-\(state)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)")) { await gate.run(key: "\(navigation.refresh)-\(state)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)", debounce: searchMode || activeQuery.isEmpty ? nil : .milliseconds(350), load: { await reload() }) }
        .task(id: gate.id("counts-\(navigation.refresh)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)")) { await gate.run("counts", key: "counts-\(navigation.refresh)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)", debounce: searchMode || activeQuery.isEmpty ? nil : .milliseconds(350), load: { await reloadCounts() }) }
        .onAppear {
            navigation.newDocument = kind; navigation.hideTabs = false
            if navigation.pendingListRefresh { navigation.pendingListRefresh = false; navigation.refresh = UUID() }
        }
        .onDisappear { if navigation.newDocument == kind { navigation.newDocument = nil } }
        .sheet(isPresented: $adding, onDismiss: { Task { await reload() } }) {
            NavigationStack { if kind == .fault { OriginalFaultForm() } else { OriginalERPForm() } }
        }
    }
    private func reload(more: Bool = false) async {
        if more { loadingMore = true } else { loading = true }
        error = nil
        defer { loading = false; loadingMore = false }
        // A page for a filter the user has already left must never be appended to the new list.
        let snapshot = "\(state)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)"
        do {
            var filters: [JSONValue] = []
            if kind == .fault && !state.isEmpty { filters.append(filter("faultState", state)) }
            filters += searchFilters
            let source = usesQueryAccount ? try await queries.source(for: session) : session
            let data = try await source.page(kind.page, offset: more ? rows.count : 0, filters: filters)
            try Task.checkCancellation()
            guard snapshot == "\(state)-\(searchField.rawValue)-\(activeQuery)-\(dateKey)-\(readContext)" else { return }
            rows = more ? rows + data["content"].array : data["content"].array
            last = data["last"].bool
            total = serverPageCount(data)
            if kind == .fault, let count = total { stateCounts[state] = count }
        } catch is CancellationError {}
        catch let urlError as URLError where urlError.code == .cancelled {}
        catch { self.error = error.localizedDescription }
    }
    private func reloadCounts() async {
        guard kind == .fault, !searchMode else { return }
        let searchFilters = self.searchFilters
        let api: LiveSession
        do { api = usesQueryAccount ? try await queries.source(for: session) : session }
        catch { return }
        await withTaskGroup(of: (String, Int?).self) { group in
            // The state being shown gets its number from the list's own answer, so it is not asked for twice.
            for stage in FaultState.allCases where !["UNTREATED", "FINISHED", state].contains(stage.code) {
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
/// One order card of a workflow list. It compares by value, so the page's loading, search and refresh state
/// changes do not rebuild the hundreds of cards that did not change.
struct LiveOrderRow: View, Equatable {
    let kind: WorkflowKind
    let item: JSONValue
    var usesQueryAccount = false
    /// Selection mode of the list this card is in; the closures live in `batch`, only these values are compared.
    var selectionMode = false
    var selected = false
    var selectable = true
    var batch: BatchSelection? = nil
    @EnvironmentObject private var session: LiveSession
    nonisolated static func == (a: LiveOrderRow, b: LiveOrderRow) -> Bool {
        a.kind == b.kind && a.item == b.item && a.usesQueryAccount == b.usesQueryAccount && a.selectionMode == b.selectionMode && a.selected == b.selected && a.selectable == b.selectable
    }
    var body: some View {
        let entry = batchItem(kind: kind, item: item)
        WorkOrderCard(title: kind == .fault ? item["faultName"].text : erpCardTitle(item),
            status: kind == .fault ? faultStateLabel(item) : approvalStateName(item["approvalState"].text),
            color: kind == .fault ? faultDisplayColor(item["faultState"].text) : approvalDisplayColor(item["approvalState"].text),
            reference: kind == .fault ? item["asset"]["assetCode"].text : item["outNumber"].text,
            referenceTitle: kind == .fault ? "资产编码" : "出库单号",
            machine: kind == .fault ? item["asset"]["addressCode"].text : "",
            time: readableDate(item[kind == .fault ? "faultTime" : "applyDate"]),
            summaryFacts: kind == .fault ? [] : [("领用人", item["recipientUser"]["realName"].text), ("领用部门", item["dept"]["deptName"].text)],
            statusNote: [],
            facts: (kind == .fault ? faultCardFacts(item) : erpCardFacts(item)),
            selecting: selectionMode ? batch.map { batch in
                CardSelecting(selected: selected, enabled: selectable, toggle: { batch.toggle(entry) }, peek: { batch.peeking = entry })
            } : nil, reportAsset: kind == .fault ? item["asset"] : nil) {
            LiveWorkflowDetail(kind: kind, id: item[kind.idKey].text, usesQueryAccount: usesQueryAccount)
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

/// Task identity, server permissions and available return branch always describe the same load.
struct DetailActionSnapshot: Equatable {
    var task: JSONValue = .null
    var permissions: JSONValue = .null
    var canReturn = false
    var hasActions: Bool {
        !task["id"].text.isEmpty && (permissions["canSubmit"].bool || permissions["canDelegate"].bool || permissions["canResolveDelegate"].bool)
    }
}

struct LiveWorkflowDetail: View {
    @Environment(\.closeWorkOrder) private var closeWorkOrder
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var returnToList = false
    @StateObject private var gate = ReloadGate()
    private func completed() {
        // Hide the old actions behind the sheet before its dismissal reveals the detail.
        var transaction = Transaction(); transaction.disablesAnimations = true
        withTransaction(transaction) { returnToList = true }
        processing = false; returning = false; editing = false
        navigation.refresh = UUID()
    }
    /// Signing in only moves the order to its next step: stay on this order, reload it so the
    /// next action appears, and let the list refresh behind. One back gesture then returns to
    /// the list instead of to a stale "待签到" page.
    private func signCompleted() {
        var transaction = Transaction(); transaction.disablesAnimations = true
        withTransaction(transaction) { suppressActions = true }
        processing = false
        navigation.pendingListRefresh = true
    }
    private func taskSheetClosed() {
        if returnToList {
            if let closeWorkOrder { closeWorkOrder() } else { dismiss() }
        }
        else if suppressActions && operationSession != nil {
            resetForOperationAccount()
            operationSession = nil
            gate.request()
        } else { Task { await load() } }
    }
    @EnvironmentObject private var owner: LiveSession
    @State private var operationSession: LiveSession?
    @State private var choosingOperationAccount = false
    private var session: LiveSession { operationSession ?? owner }
    private var detailKey: String {
        session.scopeRevision.uuidString + (usesQueryAccount ? queries.revision.uuidString : "")
    }
    @EnvironmentObject private var queries: QueryAccountModel
    let kind: WorkflowKind
    let id: String
    var usesQueryAccount = false
    @State private var detail: JSONValue = .null
    @State private var materials: [JSONValue] = []
    @State private var actions = DetailActionSnapshot()
    @State private var suppressActions = false
    @State private var detailLoadID = UUID()
    @State private var sourceSession: LiveSession?
    @State private var operatorDetail: JSONValue = .null
    @State private var operationNotice: String?
    @State private var loading = true
    @State private var error: String?
    @State private var editing = false
    @State private var processing = false
    @State private var returning = false
    @State private var repairResult: JSONValue = .null
    @State private var explanationRevision = UUID()
    @Environment(\.isPeeking) private var peeking
    @ObservedObject private var directory = UserDirectory.shared
    private var task: JSONValue { actions.task }
    private var actionInteractionAllowed: Bool {
        !loading && !gate.busy && !returnToList && !suppressActions && !editing && !processing && !returning
    }
    var body: some View {
        Group {
            if detail == .null { LoadingFailure(error: error) { Task { await load() } } }
            else {
                List {
                    if let operationSession, !usesQueryAccount {
                        Section { LabeledContent("本次操作账号", value: operationSession.authentication.text("name")) }
                    }
                    if usesQueryAccount {
                        Section {
                            Text("查询：" + queries.label(operator: owner) + " · 操作：" + session.authentication.text("name")).animatedTextValue("查询：" + queries.label(operator: owner) + " · 操作：" + session.authentication.text("name")).font(AppFont.caption).foregroundStyle(.secondary)
                            if let operationNotice {
                                Label(operationNotice, systemImage: "lock.fill").animatedTextValue(operationNotice).font(AppFont.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    if kind == .fault {
                        Section("故障报修工单信息") {
                            LabeledContent("报修名称") { Text(detail["faultName"].text).animatedTextValue(detail["faultName"].text) }
                            LabeledContent("单号") { Text(detail["sn"].text).animatedTextValue(detail["sn"].text) }
                            HStack { Text("状态"); Spacer(); Text(faultStateName(detail["faultState"].text)).animatedTextValue(faultStateName(detail["faultState"].text)).font(AppFont.body.weight(.semibold)).foregroundStyle(faultDisplayColor(detail["faultState"].text)) }
                            LabeledContent("故障时间") { Text(readableDate(detail["faultTime"])).animatedTextValue(readableDate(detail["faultTime"])) }
                            NavigationLink { AssetRecordDetail(record: detail["asset"]) } label: { LabeledContent("报修资产") { Text(detail["asset"]["assetName"].text).animatedTextValue(detail["asset"]["assetName"].text) } }
                            LabeledContent("故障申请人") { Text(detail["applyUser"].object.label).animatedTextValue(detail["applyUser"].object.label) }
                            LabeledContent("申请部门") { Text(detail["applyDept"].object.label).animatedTextValue(detail["applyDept"].object.label) }
                            LabeledContent("受理部门") { Text(detail["dept"].object.label).animatedTextValue(detail["dept"].object.label) }
                            LabeledContent("维修受理人") { Text(detail["user"].object.label).animatedTextValue(detail["user"].object.label) }
                            LabeledContent("报修人联系电话") { Text(detail["userPhone"].text).animatedTextValue(detail["userPhone"].text) }
                            LabeledContent("维修班组") { Text(detail["userGroup"].object.label).animatedTextValue(detail["userGroup"].object.label) }
                            LabeledContent("ERP记账部门") { Text(detail["division"].object.label).animatedTextValue(detail["division"].object.label) }
                        }
                        if let sentBack = faultReturnKind(detail) { ReturnNoteSection(kind: sentBack, repair: repairResult, fault: detail).environmentObject(sourceSession ?? session).id(explanationRevision) }
                        if !detail["faultDesc"].text.isEmpty { Section("故障描述") { Text(detail["faultDesc"].text).animatedTextValue(detail["faultDesc"].text).textSelection(.enabled).contextMenu { Button("复制", systemImage: "doc.on.doc") { copyToPasteboard(detail["faultDesc"].text) } } } }
                        if !detail["albums"].array.isEmpty { Section("现场照片") { ForEach(Array(detail["albums"].array.enumerated()), id: \.offset) { _, photo in AttachmentRow(photo: photo) } } }
                        if repairResult != .null, faultReturnKind(detail) == nil { RepairResultSection(repair: repairResult) }
                    } else {
                        Section("ERP出库详情") {
                            ForEach([("outNumber", "出库单号"), ("sn", "单号"), ("user.realName", "申请人"), ("applyDate", "申请日期"), ("projectCode", "项目号"), ("projectDesc", "项目名称"), ("referenceSn", "关联单号"), ("recipientUser.realName", "领用人"), ("dept.deptName", "领用部门"), ("division.divisionDesc", "ERP记账部门"), ("outboundTime", "出库时间")], id: \.0) { key, label in LabeledContent(label) { Text(readableDate(detail.at(key))).animatedTextValue(readableDate(detail.at(key))) } }
                        }
                        Section("出库物料 · \(materials.count) 项") {
                            ForEach(Array(materials.enumerated()), id: \.offset) { _, line in
                                DisclosureGroup(line["material"]["materialDesc"].text) {
                                    MaterialFacts(material: line["material"])
                                    LabeledContent("申请数量") { Text(materialQuantity(line["entryQuantity"].text, material: line["material"])).animatedTextValue(materialQuantity(line["entryQuantity"].text, material: line["material"])) }
                                    LabeledContent("实发数量") { Text(materialQuantity(line["actualQuantity"].text, material: line["material"])).animatedTextValue(materialQuantity(line["actualQuantity"].text, material: line["material"])) }
                                }
                            }
                        }.animatedTextValue("出库物料 · \(materials.count) 项")
                        Section("用途") { Text(detail["remark"].text).animatedTextValue(detail["remark"].text) }
                    }
                    if !detail["processInstanceId"].text.isEmpty {
                        Section {
                            NavigationLink { WorkflowNodesView(processID: detail["processInstanceId"].text).environmentObject(sourceSession ?? session) } label: {
                                Label("处理流程", systemImage: "point.3.connected.trianglepath.dotted")
                            }
                        }
                    }
                }
                .refreshable { await load() }
                .reloadsToTop(on: gate.token)
            }
        }
        .textSelection(.enabled).navigationTitle(kind == .fault ? "报修详情" : "出库详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .errorDialog($error, retry: { Task { await load() } })
        .modifier(DetailTabVisibility())
        .task(id: gate.id(detailKey)) { await gate.run(key: detailKey) { await load() } }
        .sheet(isPresented: $choosingOperationAccount) {
            OperationAccountPicker(owner: owner, operationSession: Binding(get: { operationSession }, set: { selected in
                resetForOperationAccount()
                operationSession = selected
                gate.request()
            }))
        }
        .onChange(of: queries.revision) {
            if usesQueryAccount { detail = .null; operatorDetail = .null; actions = DetailActionSnapshot(); sourceSession = nil; operationNotice = nil }
        }
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                if !peeking {
                    Button("本次操作账号", systemImage: operationSession == nil ? "person.crop.circle" : "person.crop.circle.badge.checkmark") {
                        choosingOperationAccount = true
                    }
                    .labelStyle(.iconOnly)
                    .accessibilityValue(session.authentication.text("name"))
                    .disabled(loading || editing || processing || returning || returnToList)
                }
                if canEdit && !peeking { Button("编辑") { editing = true }.disabled(loading || gate.busy || returnToList || suppressActions) }
                RefreshButton(busy: loading || gate.busy) { gate.request() }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if !peeking && actions.hasActions {
                    HStack {
                        if actions.canReturn {
                            BottomAction(title: "退单", symbol: "arrow.uturn.backward", color: .orange) {
                                guard actionInteractionAllowed else { return }
                                returning = true
                            }.transition(.opacity)
                        }
                        Spacer()
                        BottomAction(title: taskActionTitle(task, kind: kind), symbol: "arrow.right.circle") {
                            guard actionInteractionAllowed else { return }
                            processing = true
                        }
                    }
                    // Block stale actions without toggling the glass button's disabled tint on every refresh.
                    // Preserve the inset while dismissing the sheet / popping the detail.
                    .opacity(returnToList || suppressActions ? 0 : 1)
                    .allowsHitTesting(actionInteractionAllowed)
                    .accessibilityHidden(!actionInteractionAllowed)
                    .transition(.opacity)
                }
                if kind == .fault && !peeking && !loading && !returnToList && !suppressActions {
                    AssetReportButton(asset: detail["asset"], screenEdge: true)
                }
            }
            .bottomBarPadding()
            .interfaceMotion(actions)
            .interfaceMotion(suppressActions)
        }
        .sheet(isPresented: $returning, onDismiss: taskSheetClosed) { NavigationStack { WorkflowTaskView(task: task, kind: kind, previewReturn: true, onCompleted: completed) }.environmentObject(session) }
        .sheet(isPresented: $editing, onDismiss: taskSheetClosed) {
            NavigationStack { if kind == .fault { OriginalFaultForm(existing: (usesQueryAccount ? operatorDetail : detail).object, onSaved: completed) } else { OriginalERPForm(existing: detail.object, onSaved: { _ in completed() }) } }.environmentObject(session)
        }
        .sheet(isPresented: $processing, onDismiss: taskSheetClosed) {
            NavigationStack {
                if kind == .fault && task["category"].text == "REPAIRED" { OriginalRepairForm(faultID: id, task: task, onCompleted: completed) }
                else { WorkflowTaskView(task: task, kind: kind, onCompleted: kind == .fault && task["category"].text == "SIGNED" ? signCompleted : completed) }
            }.environmentObject(session)
        }
    }
    private func resetForOperationAccount() {
        detailLoadID = UUID(); actions = DetailActionSnapshot(); operatorDetail = .null
        detail = .null; materials = []; repairResult = .null; sourceSession = nil
        loading = true; error = nil
    }
    private var canEdit: Bool {
        if usesQueryAccount && (operatorDetail == .null || operationNotice != nil) { return false }
        return kind == .fault ? detail["faultState"].text == "UNTREATED" && !task["id"].text.isEmpty : detail["processInstanceId"].text.isEmpty && detail["createBy"].text == session.authentication.text("name")
    }
    private func load() async {
        let run = UUID(), scope = session.scopeRevision, queryRevision = queries.revision
        detailLoadID = run
        loading = true; error = nil; operationNotice = nil; actions = DetailActionSnapshot(); operatorDetail = .null
        defer { if detailLoadID == run { loading = false } }
        do {
            let source = usesQueryAccount ? try await queries.source(for: owner) : session
            let loadedDetail = try await source.request(kind.detailPath, query: [kind.idKey: id])
            try Task.checkCancellation()
            guard !usesQueryAccount || queryRevision == queries.revision else { return }
            sourceSession = source
            guard detailLoadID == run, scope == session.scopeRevision, !returnToList else { return }
            detail = loadedDetail
            explanationRevision = UUID()
            if source !== session {
                if kind == .erp { materials = try await source.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": id]).array }
                if kind == .fault { Task { await loadExplanations(record: loadedDetail, run: run, scope: scope) } }
                do {
                    let access = try await operatorOrderAccess(session: session, kind: kind, id: id, viewed: loadedDetail)
                    try Task.checkCancellation()
                    guard detailLoadID == run, scope == session.scopeRevision, queryRevision == queries.revision, !returnToList else { return }
                    operatorDetail = access.detail; actions = access.actions; operationNotice = access.notice; suppressActions = false
                } catch {
                    guard !isCancellation(error), detailLoadID == run, scope == session.scopeRevision, queryRevision == queries.revision else { return }
                    actions = DetailActionSnapshot()
                    if (error as? APIError)?.status == 403 {
                        operationNotice = "操作账号 " + session.authentication.text("name") + " 没有此单据的操作权限"
                    } else { operationNotice = "操作账号权限核对失败，已停用操作。请刷新重试：" + error.localizedDescription }
                }
                return
            }
            operatorDetail = loadedDetail
            // The materials and the task state both only need what the detail just said: ask for them together.
            let api = session, isERP = kind == .erp, orderID = id, instance = orderProcessID(loadedDetail)
            let loadedTask = loadedDetail["taskInfo"]
            async let lines: [JSONValue] = isERP ? api.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": orderID]).array : []
            async let state: JSONValue? = instance.isEmpty ? nil : api.request("workflow/getWorkflowTaskState", query: ["processInstanceId": instance])
            let (loadedLines, loadedState) = try await (lines, state)
            let permissions = loadedState ?? .null
            var canReturn = false
            if kind == .fault && !loadedTask["id"].text.isEmpty && permissions["canSubmit"].bool {
                let form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": loadedTask["id"].text])
                canReturn = form["flowConditions"].array.contains { isReturnCondition($0) }
            }
            try Task.checkCancellation()
            guard detailLoadID == run, scope == session.scopeRevision, !returnToList else { return }
            if kind == .erp { materials = loadedLines }
            actions = DetailActionSnapshot(task: loadedTask, permissions: permissions, canReturn: canReturn)
            suppressActions = false
            // The extras never hold the page up: it is shown as soon as the order itself is loaded.
            if kind == .fault { Task { await loadExplanations(record: loadedDetail, run: run, scope: scope) } }
        } catch {
            guard detailLoadID == run, !returnToList, !isCancellation(error),
                  scope == session.scopeRevision || (error as? APIError)?.status == 401 else { return }
            actions = DetailActionSnapshot()
            self.error = error.localizedDescription
        }
    }
    /// The repairer's note and the solution: one light request (`repair/getRepairsByFaultId`, as the original
    /// detail page makes), sent after the page is already on screen and only for states that can have a repair.
    /// When it cannot be read the page simply does without. The process history is never read for this.
    private func loadExplanations(record: JSONValue, run: UUID, scope: UUID) async {
        guard faultMayHaveRepairNote(record) else { repairResult = .null; return }
        let answer = try? await (sourceSession ?? session).request("repair/getRepairsByFaultId", query: ["faultId": id])
        guard !Task.isCancelled, detailLoadID == run, scope == session.scopeRevision, !returnToList else { return }
        if let answer, let repair = latestRepair(answer), !repair["user"].object.isEmpty || !repair["content"].text.isEmpty { repairResult = repair } else { repairResult = .null }
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
    var body: some View { List { ForEach(fields, id: \.0) { key, label in if record.at(key) != .null { LabeledContent(label) { Text(readableDate(record.at(key))).animatedTextValue(readableDate(record.at(key))) } } } }.textSelection(.enabled).navigationTitle(title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome().modifier(DetailTabVisibility()) }
}
struct AttachmentRow: View {
    let photo: JSONValue
    var body: some View {
        if let url = URL(string: photo["url"].text) {
            Link(destination: url) { Label(photo["name"].text.isEmpty ? "查看照片" : photo["name"].text, systemImage: "photo").animatedTextValue(photo["name"].text.isEmpty ? "查看照片" : photo["name"].text) }
        }
    }
}
struct LiveCatalogView: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    let source: ReferenceSource
    @State private var query = ""
    @State private var byCode = false
    @State private var searching = false
    @State private var rows: [JSONValue] = []
    @State private var loading = true
    @State private var refreshing = false
    @State private var loadingMore = false
    @State private var error: String?
    @State private var last = true
    @State private var total: Int?
    @StateObject private var gate = ReloadGate()
    var body: some View {
        List {
            Section { LoadingRow(isPresented: (loading || gate.busy) && !refreshing, title: "正在加载") }
            if searching { Section { Picker("搜索方式", selection: $byCode) { Text("按名称").tag(false); Text("按编号").tag(true) }.pickerStyle(.segmented).labelsHidden() } }
            Section {
            ForEach(Array(rows.enumerated()), id: \.offset) { _, record in
                NavigationLink {
                    if source == .assets { AssetRecordDetail(independent: true, record: record) }
                    else { List { Section(record["materialDesc"].text) { MaterialFacts(material: record); LabeledContent("库存数量") { Text(materialQuantity(record["onhandQty"].text, material: record)).animatedTextValue(materialQuantity(record["onhandQty"].text, material: record)) } }.animatedTextValue(record["materialDesc"].text) }.textSelection(.enabled).navigationTitle("库存详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome().modifier(DetailTabVisibility()) }
                } label: { VStack(alignment: .leading, spacing: RowMetrics.spacing) {
                    WrappingContent { Text(record.object.label).animatedTextValue(record.object.label).font(AppFont.headline) }
                    WrappingContent { Text(record[source == .assets ? "assetCode" : "materialCode"].text).animatedTextValue(record[source == .assets ? "assetCode" : "materialCode"].text).font(AppFont.subheadline).foregroundStyle(.secondary) }
                    if source != .assets {
                        // As in the original app: organisation, batch, sub-inventory and locator, then the time of the record at the foot.
                        let facts = [record["erpTpmCompany"]["organizationName"].text, record["lot"].text, record["subInv"].text, record["locatorCode"].text].filter { !$0.isEmpty }.joined(separator: " · ")
                        if !facts.isEmpty { WrappingContent { Text(facts).animatedTextValue(facts).font(AppFont.footnote).foregroundStyle(.secondary) } }
                        if !readableDate(record["updateTime"]).isEmpty { Text("入库 " + readableDate(record["updateTime"])).animatedTextValue("入库 " + readableDate(record["updateTime"])).font(AppFont.footnote).foregroundStyle(.secondary) }
                    }
                } }
                .uniformRow()
                .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
            }
            }.opacity((loading || gate.busy) && !loadingMore ? 0.4 : 1).interfaceMotion(loading || gate.busy, duration: 0.4)
            Section { LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || loadingMore, loaded: rows.count, total: total) { await load(more: true) } }
        }
        .navigationTitle(source == .assets ? "资产台账" : "库存")
        .modifier(ListTabVisibility())
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: byCode ? "搜索编号" : "搜索名称")
        .onChange(of: queries.revision) { rows = []; total = nil; gate.request() }
        .onChange(of: byCode) { Task { await load() } }
        .reloadsToTop(on: gate.id("\(byCode)-\(query)"))
        .interfaceMotion(loading || gate.busy, duration: 0.45)
        .refreshButton(busy: loading || loadingMore || gate.busy) { gate.request() }
        .errorDialog($error, retry: { Task { await load() } })
        .task(id: gate.id("\(byCode)-\(query)")) { await gate.run(key: "\(byCode)-\(query)", debounce: query.isEmpty ? nil : .milliseconds(350), load: { await load() }) }
        .refreshable { refreshing = true; await load(); refreshing = false }
    }
    private func load(more: Bool = false) async {
        if more { loadingMore = true } else { loading = true }
        error = nil; defer { loading = false; loadingMore = false }
        let snapshot = "\(byCode)-\(query)"
        do {
            let field = byCode ? (source == .assets ? "assetCode" : "materialCode") : source.searchField
            let api = try await queries.source(for: session)
            let result = try await api.page(source.pagePath!, offset: more ? rows.count : 0, filters: query.isEmpty ? [] : [filter(field, query, "like")])
            try Task.checkCancellation()
            guard snapshot == "\(byCode)-\(query)" else { return }
            rows = more ? rows + result["content"].array : result["content"].array; last = result["last"].bool; total = serverPageCount(result)
        } catch is CancellationError {}
        catch let urlError as URLError where urlError.code == .cancelled {}
        catch { self.error = error.localizedDescription }
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
    @ObservedObject private var directory = UserDirectory.shared
    @StateObject private var gate = ReloadGate()
    var body: some View {
        List {
            LoadingRow(isPresented: loading || gate.busy, title: "正在加载流程")
            if !history.isEmpty {
                Section("处理记录") {
                    ForEach(Array(history.enumerated()), id: \.offset) { _, node in
                        HistoryNodeRow(node: node, directory: directory)
                    }
                }
            }
            if !definition.isEmpty {
            Section("流程节点") {
                ForEach(Array(definition.enumerated()), id: \.offset) { _, node in
                    VStack(alignment: .leading, spacing: 5) {
                        Text(node["taskName"].text).animatedTextValue(node["taskName"].text)
                        let records = history.filter { $0["activityId"].text == node["taskKey"].text }
                        Text(records.isEmpty ? "尚无处理记录" : records.contains { $0["endTime"] == .null } ? "当前节点" : "已有处理记录").animatedTextValue(records.isEmpty ? "尚无处理记录" : records.contains { $0["endTime"] == .null } ? "当前节点" : "已有处理记录").font(AppFont.subheadline).foregroundStyle(.secondary)
                        let participant = workflowParticipantLabel(node["assignees"].text)
                        if !participant.isEmpty { Text(participant).animatedTextValue(participant).font(AppFont.subheadline).foregroundStyle(.secondary) }
                    }
                }
            }
            }
            if !loading && history.isEmpty && definition.isEmpty && error == nil { CompactEmptyState(title: "暂无流程记录") }
            if preview { Text("流程结构演示，不是真实工单记录").font(AppFont.footnote).foregroundStyle(.secondary) }
        }
        .textSelection(.enabled).navigationTitle("处理流程").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .errorDialog($error, retry: { Task { await load() } })
        .modifier(DetailTabVisibility())
        .reloadsToTop(on: gate.token)
        .task(id: gate.id("flow")) { await gate.run(key: "flow") { await load() } }
        .refreshable { await load() }
        .interfaceMotion(loading || gate.busy, duration: 0.45)
        .refreshButton(busy: loading || gate.busy) { gate.request() }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        if preview {
            definition = ["报修", "受理", "抢单", "签到", "维修", "确认", "复线", "完结"].enumerated().map { .object(["taskName": .string($0.element), "taskKey": .string("DEMO-\($0.offset)")]) }
            history = [.object(["activityName": .string("报修"), "activityId": .string("DEMO-0"), "activityType": .string("userTask"), "assignee": .string("demo1"), "condition": .string("提交"), "endTime": .string("2026-10-15 09:30"), "displayTimePrefix": .string("完成"), "displayTimeText": .string("2026-10-15 09:30"), "durationInMillis": .number(211), "comments": .array([.object(["message": .string("设备异响，请尽快处理")])])]), .object(["activityName": .string("受理"), "activityId": .string("DEMO-1"), "activityType": .string("userTask"), "assignee": .string("demo2"), "endTime": .null, "displayTimePrefix": .string("当前"), "displayTimeText": .string("2026-10-15 09:35")])]
            return
        }
        do {
            // The two lists do not depend on each other: ask for both at once.
            let api = session, instance = processID
            async let loadedHistory = api.request("workflow/getWorkflowHistoryNodesByProcessInstanceId", query: ["processInstanceId": instance]).array
            async let loadedDefinition = api.request("workflow/getAssigneeElementsByProcessInstanceId", query: ["processInstanceId": instance]).array
            let (nodes, steps) = try await (loadedHistory, loadedDefinition)
            history = orderedHistory(nodes)
            definition = steps
            // Real names are filled in as soon as they arrive; until then the user name shows.
            await directory.resolve(historyUserNames(history), session: session)
        } catch { if !isCancellation(error) { self.error = error.localizedDescription } }
    }
}

func faultCardFacts(_ item: JSONValue) -> [(String, String)] {
    [("报修名称", item["faultName"].text), ("资产编码", item["asset"]["assetCode"].text), ("资产名称", item["asset"]["assetName"].text), ("地址", item["asset"]["address"].text), ("报修人", item["applyUser"]["realName"].text), ("报修时间", readableDate(item["faultTime"])), ("受理组别", item["userGroup"]["groupName"].text), ("受理部门", item["dept"]["deptName"].text), ("受理人", item["user"]["realName"].text), ("故障描述", item["faultDesc"].text)]
}
