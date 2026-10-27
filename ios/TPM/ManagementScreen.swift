import SwiftUI

enum ManagedReadSettings {
    static let pageSizes = [50, 100, 200, 500, 1000, 2000]
    static let concurrencyLevels = [1, 3, 5, 8, 10, 12, 15, 20]
    static let experimentalKey = "developer.managementReadExperiment"
    static let pageKey = "developer.managementPageSize"
    static let concurrencyKey = "developer.managementConcurrency"
}

struct ManagementScreen: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @Environment(\.dismiss) private var dismiss
    @AppStorage(ManagementSettings.enabledKey) private var enabled = false
    @AppStorage(ManagedReadSettings.experimentalKey) private var experimental = false
    @AppStorage(ManagedReadSettings.pageKey) private var pageSize = 500
    @AppStorage(ManagedReadSettings.concurrencyKey) private var concurrency = 8
    @StateObject private var pendingModel = ManagementAnalyticsModel(historical: false)
    @StateObject private var historyModel = ManagementAnalyticsModel(historical: true)
    private var model: ManagementAnalyticsModel { historical ? historyModel : pendingModel }
    @State private var historical = false
    @State private var range: DateRange?
    @State private var choosingDates = false
    @State private var showNotes = false
    @State private var showIssues = false
    @State private var ascending = false
    @State private var showSigned = true
    @State private var showWorking = true
    @State private var queryError: String?
    @State private var estimateTask: Task<Void, Never>?
    @State private var sourceSession: LiveSession?
    private var readContext: String { session.scopeRevision.uuidString + queries.revision.uuidString }
    private var people: [ManagedPerson] {
        model.report.people.filter { historical || (showSigned && $0.signed > 0) || (showWorking && $0.working > 0) }
            .sorted { a, b in
                let ac = historical ? a.processed : (showSigned ? a.signed : 0) + (showWorking ? a.working : 0)
                let bc = historical ? b.processed : (showSigned ? b.signed : 0) + (showWorking ? b.working : 0)
                return ac == bc ? a.id < b.id : ascending ? ac < bc : ac > bc
            }
    }
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                if model.showingPrevious {
                    PreviousDataNotice(date: model.cachedAt, message: model.summaryOnly ? "旧版仅保存汇总，请重新查询。" : "请重新查询获取最新数据。")
                }
                queryControls
                if historical && model.window != nil && !model.slices.isEmpty {
                    NavigationLink {
                        ManagementTrendScreen(model: model)
                    } label: {
                        Label("折线图与统计表", systemImage: "chart.xyaxis.line")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }.buttonStyle(.borderedProminent)
                        .disabled(!model.canShowTrends)
                    if !model.canShowTrends {
                        Text("读取完成后可查看图表；暂停或失败时请先继续读取。").font(AppFont.caption).foregroundStyle(.secondary)
                    }
                }
                HStack {
                    Text("人员／已加载").font(AppFont.headline)
                    Spacer()
                    Button(ascending ? "数量升序" : "数量降序", systemImage: ascending ? "arrow.up" : "arrow.down") { ascending.toggle() }.animatedTextValue(ascending ? "数量升序" : "数量降序")
                        .font(AppFont.subheadline).buttonStyle(.bordered)
                }
                if !historical {
                    HStack {
                        ManagementChoice(title: "待签到", selected: showSigned) { showSigned.toggle() }
                        ManagementChoice(title: "待完成", selected: showWorking) { showWorking.toggle() }
                    }
                    Text("可同时选择两种状态；筛选只影响已加载人员与工单。").font(AppFont.caption).foregroundStyle(.secondary)
                }
                if people.isEmpty {
                    ContentUnavailableView(model.slices.isEmpty ? "尚未读取" : "没有匹配工单", systemImage: "person.2", description: Text(model.slices.isEmpty ? "按需读取当前厂区维修工单。" : "请检查状态筛选和读取情况。")).animatedTextValue(model.slices.isEmpty ? "尚未读取" : "没有匹配工单")
                        .interfaceMotion(model.slices.isEmpty)
                }
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                    ForEach(people) { person in
                        if model.summaryOnly {
                            Button { prepare() } label: { personCard(person) }.buttonStyle(.plain)
                        } else {
                            NavigationLink {
                                ManagedPersonOrders(model: model, personID: person.id, historical: historical, showSigned: showSigned, showWorking: showWorking)
                            } label: { personCard(person) }.buttonStyle(.plain)
                        }
                    }
                }
                DisclosureGroup("读取情况", isExpanded: $showIssues) {
                    VStack(alignment: .leading, spacing: 12) {
                        ForEach(model.slices) { slice in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(managedStateTitle(slice.state)).animatedTextValue(managedStateTitle(slice.state)).font(AppFont.subheadline.weight(.medium))
                                Text("读取 \(slice.offset) 条 · 有效 \(slice.documents.count) 单 · " + (slice.complete ? "结束" : "未完整读取")).animatedTextValue("读取 \(slice.offset) 条 · 有效 \(slice.documents.count) 单 · " + (slice.complete ? "结束" : "未完整读取")).font(AppFont.caption)
                                ForEach(slice.reasons.sorted(), id: \.self) { Text($0).animatedTextValue($0).font(AppFont.caption).foregroundStyle(.secondary) }
                            }
                        }
                        if model.report.identityUnclear { Text("缺少唯一身份或账号/ID冲突的记录单列为“执行人未明确”。").font(AppFont.caption) }
                        if model.report.duplicateConflicts { Text("当前待处理存在跨状态冲突，请重新读取。").font(AppFont.caption) }
                    }.frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8)
                }
                DisclosureGroup("统计口径", isExpanded: $showNotes) {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("仅维修，无客户端总量上限。当前待处理为 SIGNED 待签到、REPAIRED 待完成；逐单核对详情和维修任务第一条记录的执行人。")
                        Text("处理统计包含待确认 VERIFIED、待复线 RESOLVED 与已完结 FINISHED。按故障发现时间 faultTime 筛选，不代表完整绩效。")
                        Text("处理统计按业务编号计一次，后读记录覆盖前读；最新状态或日期离开范围会移除。全部合计包含身份未明确的工单，不随图表人员勾选变化。")
                        Text("暂停或失败时仅显示已加载数据。失败可从上次已提交分页继续；同一次读取的页大小与详情并发保持不变。统计不是实时一致性快照。")
                    }.font(AppFont.footnote).padding(.top, 8)
                }
            }.padding(16)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("维修管理分析").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .interfaceMotion(historical).interfaceMotion(ascending).interfaceMotion(showSigned).interfaceMotion(showWorking)
        .interfaceMotion(showIssues).interfaceMotion(showNotes).interfaceMotion(model.running)
        .sheet(isPresented: $choosingDates) {
            ManagementDateRangePicker(initial: range) { range = $0; model.clear() }
        }
        .errorDialog($queryError, title: "读取失败", clearsOnClose: true)
        .task(id: readContext) { await restorePrevious() }
        .onChange(of: historical) {
            cancelEstimate()
            pendingModel.pause(); historyModel.pause()
        }
        .onChange(of: session.scopeRevision) { cancelEstimate(); pendingModel.clear(); historyModel.clear(); sourceSession = nil }
        .onChange(of: queries.revision) { cancelEstimate(); pendingModel.clear(); historyModel.clear(); sourceSession = nil }
        .onChange(of: enabled) { _, value in if !value { cancelEstimate(); pendingModel.clear(); historyModel.clear(); dismiss() } }
        .onDisappear { cancelEstimate(); model.pause() }
    }
    private func restorePrevious() async {
        let context = readContext
        do {
            let source = try await queries.source(for: session)
            try Task.checkCancellation()
            guard context == readContext else { return }
            sourceSession = source
            pendingModel.restorePrevious(session: source)
            historyModel.restorePrevious(session: source)
            if range == nil { range = historyModel.cachedRange }
        } catch { if !isCancellation(error), context == readContext { queryError = error.localizedDescription } }
    }
    private var queryControls: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(session.currentFactoryName).animatedTextValue(session.currentFactoryName).font(AppFont.headline)
            Text("查询账号：" + queries.label(operator: session) + " · 操作账号：" + session.authentication.text("name")).animatedTextValue("查询账号：" + queries.label(operator: session) + " · 操作账号：" + session.authentication.text("name")).font(AppFont.footnote).foregroundStyle(.secondary)
            HStack {
                ManagementChoice(title: "当前待处理", selected: !historical) { historical = false }
                ManagementChoice(title: "处理统计", selected: historical) { historical = true }
            }
            if historical {
                Button { choosingDates = true } label: {
                    Label(range.map { let w = ManagedWindow(range: $0); return w.from + " 至 " + w.to } ?? "先选择故障发现日期", systemImage: "calendar").animatedTextValue(range.map { let w = ManagedWindow(range: $0); return w.from + " 至 " + w.to } ?? "先选择故障发现日期")
                        .font(AppFont.subheadline).frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }.buttonStyle(.bordered).disabled(model.running || model.estimating)
                Text("待确认＋待复线＋已完结 · 按故障发现时间").font(AppFont.caption).foregroundStyle(.secondary)
            }
            if sourceSession == nil { Button("重试连接查询账号") { Task { await restorePrevious() } }.buttonStyle(.bordered) }
            Text(model.status).animatedTextValue(model.status).font(AppFont.subheadline).foregroundStyle(.secondary).accessibilityIdentifier("management-status")
            if !model.slices.isEmpty {
                Text("接口查询 · 实际查询耗时 " + String(format: "%.1f", model.elapsedSeconds) + " 秒")
                    .monospacedDigit().transaction { $0.animation = nil }
                    .font(AppFont.caption).foregroundStyle(.secondary).monospacedDigit()
            }
            if model.running || model.estimating { ProgressView().frame(maxWidth: .infinity, alignment: .leading) }
            if model.running {
                Button("暂停读取", systemImage: "pause") { model.pause() }.buttonStyle(.bordered)
            } else {
                ViewThatFits(in: .horizontal) {
                    HStack { readButtons }
                    VStack(alignment: .leading) { readButtons }
                }.disabled(sourceSession == nil || model.estimating || historical && range == nil)
            }
            if let first = model.slices.first { Text("本次每页 \(first.pageSize) · 详情并发 \(model.detailConcurrency)").animatedTextValue("本次每页 \(first.pageSize) · 详情并发 \(model.detailConcurrency)").font(AppFont.caption).foregroundStyle(.secondary) }
        }.frame(maxWidth: .infinity, alignment: .leading).padding(16)
            .background(.background, in: RoundedRectangle(cornerRadius: 20))
    }
    @ViewBuilder private var readButtons: some View {
        if model.hasUnfinished { Button("继续／重试", systemImage: "play") { start(restart: false) }.buttonStyle(.borderedProminent) }
        Button(model.slices.isEmpty ? "预估并读取" : "重新读取", systemImage: "arrow.clockwise") { prepare() }.animatedTextValue(model.slices.isEmpty ? "预估并读取" : "重新读取").buttonStyle(.bordered)
    }
    private func personCard(_ person: ManagedPerson) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(person.name).animatedTextValue(person.name).font(AppFont.headline).foregroundStyle(.primary)
            Text(person.identity).animatedTextValue(person.identity).font(AppFont.caption).foregroundStyle(.secondary)
            if historical { Text("处理 \(person.processed) 单").animatedTextValue("处理 \(person.processed) 单").font(AppFont.subheadline).foregroundStyle(Color.accentColor) }
            else {
                if showSigned { Text("待签到 \(person.signed)").animatedTextValue("待签到 \(person.signed)").font(AppFont.subheadline).foregroundStyle(Color.accentColor) }
                if showWorking { Text("待完成 \(person.working)").animatedTextValue("待完成 \(person.working)").font(AppFont.subheadline).foregroundStyle(Color.accentColor) }
            }
        }.frame(maxWidth: .infinity, alignment: .topLeading).padding(12)
            .background(Color.accentColor.opacity(0.08), in: RoundedRectangle(cornerRadius: 16)).contentShape(.rect)
    }
    private func start(restart: Bool) {
        guard let sourceSession else { return }
        model.start(session: sourceSession, days: nil, restart: restart, range: historical ? range : nil,
                    pageSize: experimental ? pageSize : 500, concurrency: experimental ? concurrency : 8)
    }
    private func cancelEstimate() { estimateTask?.cancel(); estimateTask = nil }
    private func prepare() {
        estimateTask?.cancel()
        let context = readContext
        estimateTask = Task {
            do {
                let source = try await queries.source(for: session)
                try Task.checkCancellation()
                guard context == readContext else { return }
                if sourceSession !== source { model.clear(); sourceSession = source }
                _ = try await model.estimate(session: source, historical: historical, range: range)
                try Task.checkCancellation()
                start(restart: true)
            } catch { if !isCancellation(error) { queryError = error.localizedDescription } }
        }
    }
}

struct ManagementChoice: View {
    let title: String
    let selected: Bool
    var fillWidth = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if selected { Image(systemName: "checkmark").font(AppFont.caption.weight(.bold)).transition(.opacity) }
                Text(title).animatedTextValue(title).font(AppFont.subheadline.weight(.medium)).fixedSize(horizontal: false, vertical: true)
            }.frame(maxWidth: fillWidth ? .infinity : nil, minHeight: 32).padding(.horizontal, 10).padding(.vertical, 4)
                .foregroundStyle(selected ? Color.white : Color.primary)
                .background(selected ? Color.accentColor : Color(uiColor: .tertiarySystemFill), in: RoundedRectangle(cornerRadius: 12))
        }.buttonStyle(.plain).accessibilityLabel(title).accessibilityAddTraits(selected ? .isSelected : []).interfaceMotion(selected, duration: 0.2)
    }
}

func managedStateTitle(_ state: String) -> String {
    ["SIGNED": "待签到", "REPAIRED": "待完成", "VERIFIED": "待确认", "RESOLVED": "待复线", "FINISHED": "已完结"][state] ?? state
}

private struct ManagedPersonOrders: View {
    @ObservedObject var model: ManagementAnalyticsModel
    let personID: String
    let historical: Bool
    var showSigned = true
    var showWorking = true
    @EnvironmentObject private var session: LiveSession
    @State private var state = ""
    private var person: ManagedPerson? { model.report.people.first { $0.id == personID } }
    private var rows: [ManagedDocument] {
        (person?.documents ?? []).filter { doc in
            (historical || showSigned && doc.state == "SIGNED" || showWorking && doc.state == "REPAIRED") && (state.isEmpty || doc.state == state)
        }.sorted { $0.id < $1.id }
    }
    var body: some View {
        List {
            Section {
                if model.showingPrevious { PreviousDataNotice(date: model.cachedAt, message: "请返回分析页重新查询获取最新数据。") }
                Text(person?.identity ?? "当前范围内没有此执行人的已加载记录。").animatedTextValue(person?.identity ?? "当前范围内没有此执行人的已加载记录。").font(AppFont.subheadline).foregroundStyle(.secondary)
                Text(model.status).animatedTextValue(model.status).font(AppFont.footnote).foregroundStyle(.secondary)
                Picker("状态", selection: $state) {
                    Text("全部已选状态").tag("")
                    if historical { Text("待确认").tag("VERIFIED"); Text("待复线").tag("RESOLVED"); Text("已完结").tag("FINISHED") }
                    else {
                        if showSigned { Text("待签到").tag("SIGNED") }
                        if showWorking { Text("待完成").tag("REPAIRED") }
                    }
                }
            }
            Section("仅已加载记录 · \(rows.count) 单") {
                ForEach(rows) { doc in
                    NavigationLink {
                        LiveWorkflowDetail(kind: .fault, id: doc.businessID, usesQueryAccount: true)
                    } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(doc.title).animatedTextValue(doc.title).font(AppFont.headline)
                            Text(managedStateTitle(doc.state)).animatedTextValue(managedStateTitle(doc.state)).font(AppFont.subheadline).foregroundStyle(.secondary)
                            Text(doc.number.isEmpty ? "业务编号：" + doc.businessID : "单号：" + doc.number).animatedTextValue(doc.number.isEmpty ? "业务编号：" + doc.businessID : "单号：" + doc.number).font(AppFont.caption).foregroundStyle(.secondary)
                            if historical { Text("维修结束：" + doc.end).animatedTextValue("维修结束：" + doc.end).font(AppFont.caption).foregroundStyle(.secondary) }
                        }.padding(.vertical, 3)
                    }
                }
                if rows.isEmpty { Text("已加载范围内没有匹配工单。").foregroundStyle(.secondary) }
            }.animatedTextValue("仅已加载记录 · \(rows.count) 单")
        }
        .navigationTitle(person?.name ?? "人员工单").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility()).interfaceMotion(state)
        .onChange(of: session.scopeRevision) { model.clear() }
    }
}
