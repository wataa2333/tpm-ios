import SwiftUI
import PhotosUI

@main
struct TPMApp: App {
    @StateObject private var store = DemoStore()
    @StateObject private var session = LiveSession()
    @StateObject private var navigation = BottomNavigationModel()
    var body: some Scene {
        WindowGroup {
            GeometryReader { geometry in
                PreviewRouter().modifier(KeyboardTabVisibility())
                    .environment(\.loadingViewport, geometry.frame(in: .global))
                    .environment(\.bottomSafeArea, geometry.safeAreaInsets.bottom)
                    .environmentObject(store).environmentObject(session).environmentObject(navigation)
                    .labeledContentStyle(SafeFactsStyle())
            }
        }
    }
}

struct PreviewRouter: View {
    var body: some View {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--screen"), args.indices.contains(index + 1) {
            PreviewScreen(name: args[index + 1]).environment(\.previewReferences, true)
        } else { AuthenticatedRoot() }
        #else
        AuthenticatedRoot()
        #endif
    }
}

enum FaultState: String, CaseIterable, Identifiable {
    case draft = "报修", reported = "待受理", received = "待抢单", signed = "待签到", working = "待完成", verifying = "待确认", resolved = "待复线", finished = "已完结"
    var id: String { rawValue }
    var actionTitle: String {
        switch self { case .draft: "提交报修"; case .reported: "受理报修"; case .received: "领取工单"; case .signed: "签到"; case .working: "维修处理"; case .verifying: "确认完成"; case .resolved: "复线确认"; case .finished: "已完结" }
    }
    var code: String { ["UNTREATED", "REPORTED", "RECEIVED", "SIGNED", "REPAIRED", "VERIFIED", "RESOLVED", "FINISHED"][FaultState.allCases.firstIndex(of: self)!] }
    var color: Color { switch self { case .draft: .purple; case .reported: .orange; case .received: .blue; case .signed: .indigo; case .working: .teal; case .verifying: .pink; case .resolved: .mint; case .finished: .green } }
    var selectedForeground: Color { switch self { case .reported, .working, .resolved, .finished: .black; default: .white } }
    var symbol: String { switch self { case .draft: "square.and.pencil"; case .reported: "tray"; case .received: "hand.raised"; case .signed: "location"; case .working: "wrench.and.screwdriver"; case .verifying: "checkmark.bubble"; case .resolved: "arrow.triangle.2.circlepath"; case .finished: "checkmark.circle" } }
}

struct Asset: Identifiable, Hashable {
    let id: String
    let name: String
    let model: String
    let location: String
    let department: String
    let owner: String
    let state: String
    static let samples = [
        Asset(id: "CNC-008", name: "立式加工中心", model: "VMC-850", location: "一车间 · A 区", department: "生产一部", owner: "张明", state: "维修中"),
        Asset(id: "KY-003", name: "螺杆空压机", model: "GA-37", location: "动力房", department: "设备部", owner: "王伟", state: "在用"),
        Asset(id: "CNC-012", name: "数控车床", model: "CK-6150", location: "二车间 · B 区", department: "生产二部", owner: "李华", state: "在用"),
        Asset(id: "JC-021", name: "三坐标测量机", model: "CMM-686", location: "品质实验室", department: "品质部", owner: "陈静", state: "在用")
    ]
}

struct Fault: Identifiable {
    let id: String
    let asset: Asset
    let description: String
    let reporter: String
    let time: String
    var state: FaultState
    var records: [String]
    var name: String { asset.name + "异常报修" }
    var result: String = ""
    var photoCount: Int = 0
}

struct Material: Identifiable, Hashable {
    let id: String
    let name: String
    let specification: String
    let quantity: Int
    let unit: String
    let shelf: String
    static let samples = [
        Material(id: "WL-10028", name: "深沟球轴承", specification: "6205-2RS", quantity: 24, unit: "个", shelf: "备件仓 · A-03-02"),
        Material(id: "WL-10046", name: "空气滤芯", specification: "GA-37 配套", quantity: 8, unit: "件", shelf: "备件仓 · B-01-03"),
        Material(id: "WL-10112", name: "导轨润滑油", specification: "ISO VG 68 · 18L", quantity: 6, unit: "桶", shelf: "油品仓 · C-02"),
        Material(id: "WL-10207", name: "同步带", specification: "HTD 5M-450", quantity: 12, unit: "条", shelf: "备件仓 · A-05-01")
    ]
}

struct Outbound: Identifiable {
    let id: String
    let department: String
    let applicant: String
    let material: Material
    let quantity: Int
    let date: String
    var completed: Bool
}

@MainActor
final class DemoStore: ObservableObject {
    @Published var faults: [Fault] = FaultState.allCases.enumerated().flatMap { offset, state in
        (0..<2).map { index in
            Fault(id: "BX-202610\(16-offset)-00\(index+1)", asset: Asset.samples[index],
                  description: ["主轴运行时出现异常振动，伴随间歇性异响。", "设备启动后压力不稳定，请检查运行状态。"][index],
                  reporter: ["张明", "李华"][index], time: offset == 0 ? "今天 09:\(index == 0 ? "30" : "15")" : "10月\(16-offset)日 14:20", state: state,
                  records: ["报修申请已提交"] + (offset > 0 ? ["设备部已受理"] : []) + (offset > 1 ? ["维修结果已提交，等待确认"] : []) + (offset > 2 ? ["报修人已确认完成"] : []),
                  result: offset > 1 ? "检查并更换磨损部件，试运行正常。" : "")
        }
    }
    @Published var outbounds: [Outbound] = [
        .init(id: "CK-20261015-001", department: "生产一部", applicant: "张明", material: Material.samples[0], quantity: 2, date: "今天 10:15", completed: false),
        .init(id: "CK-20261015-002", department: "设备部", applicant: "王伟", material: Material.samples[1], quantity: 1, date: "今天 09:10", completed: false),
        .init(id: "CK-20261014-008", department: "生产二部", applicant: "李华", material: Material.samples[2], quantity: 1, date: "昨天 15:30", completed: true)
    ]
    @Published var claimedOrders: Set<String> = []

}

struct DemoNotice: View {
    var body: some View { Text("模拟数据 · 操作仅保存在本次运行中").font(.footnote).foregroundStyle(.secondary) }
}

struct StatusLabel: View {
    let state: FaultState
    var body: some View {
        HStack(spacing: 5) { Image(systemName: state.symbol); Text(state.rawValue) }
            .font(.subheadline.weight(.medium)).foregroundStyle(state.color).fixedSize()
    }
}

struct MainTabs: View {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var selection: Int
    var previewFaultList = false
    init(selection: Int = 0, previewFaultList: Bool = false) { _selection = State(initialValue: selection); self.previewFaultList = previewFaultList }
    var body: some View {
        NativeTabShell(selection: $selection, preview: true) {
            NavigationStack { HomeView() }.tabItem { Label("主页", systemImage: "house") }.tag(0)
            NavigationStack {
                if previewFaultList { FaultListView() } else { TodoInboxView(preview: true) }
            }
            .tabItem { Label("我的待办", systemImage: "checklist") }.tag(2)
            NavigationStack { WorkspaceView() }.tabItem { Label("工作台", systemImage: "square.grid.2x2") }.tag(1)
            NavigationStack { ProfileView() }.tabItem { Label("我的", systemImage: "person.crop.circle") }.tag(3)
        }
    }
}

struct HomeView: View {
    var body: some View { DashboardHome(preview: true) }
}

struct PoolEntry: View {
    let title: String
    let symbol: String
    let subtitle: String
    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 5) {
                Text(title)
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
            }.padding(.vertical, 6)
        } icon: { Image(systemName: symbol).foregroundStyle(.tint).frame(width: 26) }
    }
}

struct WorkspaceView: View {
    var body: some View { DashboardWorkspace(preview: true) }
}

struct FaultListView: View {
    @EnvironmentObject private var store: DemoStore
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var state: FaultState
    @State private var searchField: FaultSearchField = .name
    @State private var searching = false
    @State private var query = ""
    @State private var creating = false
    init(initialState: FaultState = .received) { _state = State(initialValue: initialState) }
    private var rows: [Fault] {
        store.faults.filter { fault in
            let value: String
            switch searchField {
            case .name: value = fault.name
            case .number: value = fault.id
            case .machine: value = fault.asset.id
            case .assetCode: value = "FA-" + fault.asset.id
            case .assetName: value = fault.asset.name
            case .address: value = fault.asset.location
            case .reporter: value = fault.reporter
            case .group: value = "设备维修组"
            case .repairer: value = "示例维修人"
            }
            return fault.state == state && (query.isEmpty || value.localizedCaseInsensitiveContains(query))
        }
    }
    var body: some View {
        VStack(spacing: 0) {
            WorkOrderStatusStrip(selection: Binding(get: { FaultState.allCases.firstIndex(of: state)! }, set: { state = FaultState.allCases[$0] }), counts: Dictionary(uniqueKeysWithValues: FaultState.allCases.map { stage in
                (stage.code, store.faults.filter { $0.state == stage }.count)
            }))
        List {
            if searching { Section {
                Picker("搜索分类", selection: $searchField) { ForEach(FaultSearchField.allCases) { Text($0.rawValue).tag($0) } }
            } }
            Section {
                ForEach(rows) { fault in
                    FaultRow(fault: fault)
                        .navigationLinkIndicatorVisibility(.hidden)
                        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                        .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
                }
                if rows.isEmpty { CompactEmptyState() }
            } header: {
                HStack { Text(state.rawValue); Spacer(); Text("\(rows.count) 单").monospacedDigit() }
            }
        }
        .simultaneousGesture(DragGesture(minimumDistance: 45).onEnded { value in
            guard abs(value.translation.width) > abs(value.translation.height) * 1.5 else { return }
            let index = FaultState.allCases.firstIndex(of: state)!
            state = FaultState.allCases[min(max(index + (value.translation.width < 0 ? 1 : -1), 0), FaultState.allCases.count - 1)]
        })
        }
        .navigationTitle("故障报修").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .searchPageChrome(isSearching: searching)
        .nativeSearchable(text: $query, isPresented: $searching, prompt: "搜索" + searchField.rawValue)
        // Reassert the list chrome whenever a pushed detail/workflow returns.
        // NavigationStack may keep the list alive during the push, so relying
        // on the departure of the detail screen can leave the bar hidden or
        // the keyboard state latched after a nested pop.
        .onAppear {
            navigation.newDocument = .fault
            navigation.keyboardVisible = false
            navigation.hideTabs = false
        }
        .onDisappear { if navigation.newDocument == .fault { navigation.newDocument = nil } }
        .sheet(isPresented: $creating) { NavigationStack { NewFaultView() } }
    }
}

struct FaultRow: View {
    let fault: Fault
    var body: some View {
        WorkOrderCard(title: fault.name, status: fault.state.rawValue, color: fault.state.color, reference: "FA-" + fault.asset.id, time: fault.time,
            facts: [("报修名称", fault.name), ("资产编码", "FA-" + fault.asset.id), ("资产名称", fault.asset.name), ("机台号", fault.asset.id), ("地址", fault.asset.location), ("报修人", fault.reporter), ("报修时间", fault.time), ("受理组别", "设备维修二组"), ("受理部门", "示例设备部"), ("受理人", "示例用户"), ("故障描述", fault.description)]) {
            FaultDetailView(id: fault.id)
        }
    }
}

struct FaultDetailView: View {
    @EnvironmentObject private var store: DemoStore
    let id: String
    @State private var processing = false
    @State private var returning = false
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var navigation: BottomNavigationModel
    private func completed() { processing = false; returning = false; navigation.refresh = UUID(); dismiss() }
    private var fault: Fault? { store.faults.first { $0.id == id } }
    var body: some View {
        Group {
            if let fault {
                List {
                    Section {
                        HStack { Text("当前状态"); Spacer(); StatusLabel(state: fault.state) }
                        LabeledContent("报修编号", value: fault.id)
                        LabeledContent("报修人", value: fault.reporter)
                        LabeledContent("报修时间", value: fault.time)
                    }
                    Section("设备") {
                        NavigationLink { AssetDetailView(asset: fault.asset) } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                Text(fault.name).font(.headline)
                                Text("\(fault.asset.id) · \(fault.asset.location)").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Section("故障描述") { Text(fault.description).textSelection(.enabled) }
                    if !fault.result.isEmpty { Section("维修结果") { Text(fault.result) } }
                    Section("附件") { Text(fault.photoCount == 0 ? "暂无附件" : "已选择 \(fault.photoCount) 张照片（演示，不上传）").foregroundStyle(.secondary) }
                    Section { NavigationLink { WorkflowNodesView(processID: "", preview: true) } label: { Label("处理流程", systemImage: "point.3.connected.trianglepath.dotted") } }
                    Section("处理记录") {
                        ForEach(Array(fault.records.enumerated()), id: \.offset) { index, record in
                            Label(record, systemImage: index == fault.records.count - 1 ? "circle.inset.filled" : "circle").font(.subheadline)
                        }
                    }
                    Section { DemoNotice() }
                }
                .safeAreaInset(edge: .bottom) {
                    HStack {
                        if [.received, .signed, .working].contains(fault.state) { BottomAction(title: "退单", symbol: "arrow.uturn.backward", color: .orange) { returning = true } }
                        Spacer()
                        if fault.state != .finished {
                            BottomAction(title: fault.state.actionTitle, symbol: fault.state.symbol) { processing = true }
                        }
                    }.padding(.horizontal, 20).padding(.vertical, 8)
                }
                .sheet(isPresented: $processing) {
                    NavigationStack {
                        if fault.state == .signed { SignInPreview(asset: fault.asset, onCompleted: completed) }
                        else if fault.state == .working { OriginalRepairForm(faultID: id, task: .object(["name": .string("维修处理")]), preview: true, onCompleted: completed) }
                        else { WorkflowTaskView(task: .object(["name": .string(fault.state.actionTitle), "category": .string(fault.state.code)]), kind: .fault, preview: true, onCompleted: completed) }
                    }
                }
                .sheet(isPresented: $returning) { NavigationStack { WorkflowTaskView(task: .object(["name": .string("退单"), "category": .string(fault.state.code)]), kind: .fault, preview: true, previewReturn: true, onCompleted: completed) } }

            } else { ContentUnavailableView("单据不存在", systemImage: "doc.questionmark") }
        }
        .navigationTitle("报修详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
    }
}

struct FaultProcessingView: View {
    let id: String
    var body: some View { OriginalRepairForm(faultID: id, task: .object(["name": .string("维修处理")]), preview: true) }
}

struct NewFaultView: View {
    init(prefilled: Bool = false) {}
    var body: some View { OriginalFaultForm(preview: true) }
}

struct AssetListView: View {
    @State private var query = ""
    var body: some View {
        List {
            Section {
                ForEach(Asset.samples.filter { query.isEmpty || ($0.id + $0.name + $0.model + $0.location).localizedCaseInsensitiveContains(query) }) { asset in
                    NavigationLink { AssetDetailView(asset: asset) } label: { AssetRow(asset: asset) }
                }
            } footer: { DemoNotice() }
        }
        .navigationTitle("资产台账").searchPageChrome()
        .modifier(ListTabVisibility())
        .nativeSearchable(text: $query, prompt: "资产编号、名称、型号或位置")
        .overlay { if !query.isEmpty && !Asset.samples.contains(where: { ($0.id + $0.name + $0.model + $0.location).localizedCaseInsensitiveContains(query) }) { ContentUnavailableView.search(text: query) } }
    }
}

struct AssetRow: View {
    let asset: Asset
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            WrappingContent { Text(asset.name).font(.headline) }
            WrappingContent { Text("\(asset.id) · \(asset.model)").font(.subheadline).foregroundStyle(.secondary) }
            WrappingContent { Text(asset.location).font(.subheadline).foregroundStyle(.secondary) }
        }.padding(.vertical, 4)
    }
}

struct AssetPickerView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var selection: Asset?
    @State private var query = ""
    var body: some View {
        List(Asset.samples.filter { query.isEmpty || ($0.id + $0.name + $0.model).localizedCaseInsensitiveContains(query) }) { asset in
            Button { selection = asset; dismiss() } label: {
                HStack { AssetRow(asset: asset).foregroundStyle(.primary); Spacer(); if selection == asset { Image(systemName: "checkmark") } }
            }
        }
        .navigationTitle("选择设备").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .searchPageChrome()
        .nativeSearchable(text: $query, prompt: "搜索设备名称或编号")
    }
}

struct AssetDetailView: View {
    let asset: Asset
    var initialTab = "详情"
    var body: some View {
        AssetRecordDetail(record: .object(["assetId": .number(1), "assetName": .string(asset.name), "assetCode": .string("FA-" + asset.id), "addressCode": .string(asset.id), "address": .string(asset.location), "model": .string(asset.model), "dept": PreviewIdentity.department, "user": PreviewIdentity.user]), preview: true, initialTab: initialTab)
    }
}

struct OrderPoolView: View {
    @EnvironmentObject private var store: DemoStore
    let kind: String
    @State private var query = ""
    var body: some View {
        List {
            Section {
                ForEach(Asset.samples.prefix(3).filter { query.isEmpty || ($0.name + $0.id).localizedCaseInsensitiveContains(query) }) { asset in
                    WorkOrderCard(title: "\(asset.name)\(kind)", status: store.claimedOrders.contains(kind + asset.id) ? "已领取" : "待接单", reference: "FA-" + asset.id, time: "今天 10:00", facts: [("任务类型", kind), ("机台号", asset.id), ("地址", asset.location), ("受理组别", "设备维修组"), ("受理部门", asset.department)]) { OrderDetailView(kind: kind, asset: asset) }

                }
            } footer: { DemoNotice() }
        }
        .navigationTitle("\(kind)工单池").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .searchPageChrome()
        .nativeSearchable(text: $query, prompt: "搜索设备或资产编号")
    }
}

struct OrderDetailView: View {
    @EnvironmentObject private var store: DemoStore
    let kind: String
    let asset: Asset
    @State private var confirming = false
    private var claimed: Bool { store.claimedOrders.contains(kind + asset.id) }
    var body: some View {
        List {
            Section {
                LabeledContent("任务类型", value: kind)
                LabeledContent("状态", value: claimed ? "已领取（模拟）" : "待接单")
                LabeledContent("计划日期", value: "2026-10-15")
                NavigationLink { AssetDetailView(asset: asset) } label: { LabeledContent("设备", value: asset.name) }
            }
            Section("任务内容") {
                Text(kind == "维修" ? "检查设备异常振动和运行异响，记录故障原因与处理结果。" : kind == "保养" ? "检查润滑状态、清洁过滤部件，并记录试运行情况。" : "检查定位精度、重复定位精度，记录测量结果。")
            }
            Section { DemoNotice() }
        }
        .navigationTitle("工单详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
        .safeAreaInset(edge: .bottom) { HStack { Spacer(); BottomAction(title: claimed ? "已领取" : "领取工单", symbol: "hand.raised") { confirming = true }.disabled(claimed) }.padding(.horizontal, 20).padding(.vertical, 8) }
        .confirmationDialog("领取这张模拟工单？", isPresented: $confirming, titleVisibility: .visible) { Button("领取模拟工单") { store.claimedOrders.insert(kind + asset.id) } }
    }
}

struct StockListView: View {
    @State private var query = ""
    private var rows: [Material] { Material.samples.filter { query.isEmpty || ($0.id + $0.name + $0.specification + $0.shelf).localizedCaseInsensitiveContains(query) } }
    var body: some View {
        List {
            Section {
                ForEach(rows) { material in
                    NavigationLink { MaterialDetailView(material: material) } label: {
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 6) {
                                Text(material.name).font(.headline)
                                Text(material.specification).font(.subheadline).foregroundStyle(.secondary)
                                Text(material.shelf).font(.subheadline).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(material.quantity) \(material.unit)").font(.subheadline.monospacedDigit())
                        }.padding(.vertical, 5)
                    }
                }
                if rows.isEmpty { ContentUnavailableView.search(text: query) }
            } footer: { DemoNotice() }
        }
        .navigationTitle("库存").searchPageChrome()
        .modifier(ListTabVisibility())
        .nativeSearchable(text: $query, prompt: "物料编号、名称、规格或库位")
    }
}

struct MaterialDetailView: View {
    let material: Material
    var body: some View {
        List {
            Section {
                Text(material.name).font(.title2.bold())
                LabeledContent("物料编号", value: material.id)
                LabeledContent("规格型号", value: material.specification)
            }
            Section("库存信息") {
                LabeledContent("库存数量", value: "\(material.quantity) \(material.unit)")
                LabeledContent("库位", value: material.shelf)
                LabeledContent("计量单位", value: material.unit)
            }
            Section { DemoNotice() }
        }
        .navigationTitle("库存详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(DetailTabVisibility())
    }
}

struct OutboundListView: View {
    @EnvironmentObject private var store: DemoStore
    @State private var completed = false
    @State private var query = ""
    private var rows: [Outbound] { store.outbounds.filter { $0.completed == completed && (query.isEmpty || ($0.id + $0.material.name + $0.department).localizedCaseInsensitiveContains(query)) } }
    var body: some View {
        List {
            Section {
                Picker("出库状态", selection: $completed) { Text("待出库").tag(false); Text("已出库").tag(true) }.pickerStyle(.segmented)
            }
            Section {
                ForEach(rows) { outbound in
                    WorkOrderCard(title: outbound.material.name + "领料", status: outbound.completed ? "已出库" : "待出库", reference: outbound.id, referenceTitle: "出库单号", time: outbound.date, summaryFacts: [("领用人", outbound.applicant), ("领用部门", outbound.department)], facts: [("物料名称", outbound.material.name), ("申请数量", "\(outbound.quantity) \(outbound.material.unit)")]) { OutboundDetailView(id: outbound.id) }

                }
                if rows.isEmpty { ContentUnavailableView("暂无出库单", systemImage: "shippingbox") }
            } footer: { DemoNotice() }
        }
        .navigationTitle("ERP 出库").searchPageChrome()
        .modifier(ListTabVisibility())
        .nativeSearchable(text: $query, prompt: "搜索出库单、物料或部门")
    }
}

struct OutboundDetailView: View {
    @EnvironmentObject private var store: DemoStore
    let id: String
    @State private var confirming = false
    var body: some View {
        if let item = store.outbounds.first(where: { $0.id == id }) {
            List {
                Section {
                    LabeledContent("出库单号", value: item.id)
                    LabeledContent("状态", value: item.completed ? "已出库" : "待出库")
                    LabeledContent("领用部门", value: item.department)
                    LabeledContent("领用人", value: item.applicant)
                    LabeledContent("申请时间", value: item.date)
                }
                Section("出库明细") {
                    NavigationLink { MaterialDetailView(material: item.material) } label: {
                        VStack(alignment: .leading, spacing: 5) { Text(item.material.name).font(.headline); Text(item.material.specification).font(.subheadline).foregroundStyle(.secondary) }
                    }
                    LabeledContent("申请数量", value: "\(item.quantity) \(item.material.unit)")
                    LabeledContent("出库库位", value: item.material.shelf)
                }
                Section { DemoNotice() }
            }
            .navigationTitle("出库详情").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .modifier(DetailTabVisibility())
            .safeAreaInset(edge: .bottom) {
                if !item.completed { HStack { Spacer(); BottomAction(title: "确认出库", symbol: "checkmark.circle") { confirming = true } }.padding(.horizontal, 20).padding(.vertical, 8) }
            }
            .confirmationDialog("确认本地模拟出库？", isPresented: $confirming, titleVisibility: .visible) {
                Button("确认模拟出库") { if let i = store.outbounds.firstIndex(where: { $0.id == id }) { store.outbounds[i].completed = true } }
            }
        } else { ContentUnavailableView("单据不存在", systemImage: "doc.questionmark") }
    }
}

struct ProfileView: View {
    var body: some View {
        List {
            Section { Label("个人演示", systemImage: "person.crop.circle"); LabeledContent("当前环境", value: "界面预览") }
            Section { LabeledContent("当前厂区", value: "示例一厂") }
            Section("应用") { LabeledContent("版本", value: "0.5.0"); LabeledContent("数据来源", value: "模拟数据") }
            Section { Text("账号、工厂和业务权限将在对接原服务器后显示。").foregroundStyle(.secondary) }
        }
        .navigationTitle("我的").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
    }
}

struct PreviewScreen: View {
    let name: String
    var body: some View {
        switch name {
        case "home": MainTabs(selection: 0)
        case "workspace": MainTabs(selection: 1)
        case "faults": MainTabs(selection: 2, previewFaultList: true)
        case "todo": MainTabs(selection: 2)
        case "profile": MainTabs(selection: 3)
        case "draft": NavigationStack { FaultListView(initialState: .draft) }
        case "reported": NavigationStack { FaultListView(initialState: .reported) }
        case "signed": NavigationStack { FaultListView(initialState: .signed) }
        case "resolved": NavigationStack { FaultListView(initialState: .resolved) }
        case "sign-in": NavigationStack { SignInPreview(asset: Asset.samples[0]) }
        case "return-order": NavigationStack { WorkflowTaskView(task: .object(["name": .string("退单"), "category": .string("SIGNED")]), kind: .fault, preview: true, previewReturn: true) }
        case "claim-order": NavigationStack { WorkflowTaskView(task: .object(["name": .string("领取工单"), "category": .string("RECEIVED")]), kind: .fault, preview: true) }
        case "accept-order": NavigationStack { WorkflowTaskView(task: .object(["name": .string("受理报修"), "category": .string("REPORTED")]), kind: .fault, preview: true) }
        case "reject-accept": NavigationStack { WorkflowTaskView(task: .object(["name": .string("受理报修"), "category": .string("REPORTED")]), kind: .fault, preview: true, previewCondition: "reject") }
        case "resolved-detail": NavigationStack { FaultDetailView(id: "BX-20261010-001") }
        case "draft-detail": NavigationStack { FaultDetailView(id: "BX-20261016-001") }
        case "confirm-order": NavigationStack { WorkflowTaskView(task: .object(["name": .string("确认完成"), "category": .string("VERIFIED")]), kind: .fault, preview: true) }
        case "reject-confirm": NavigationStack { WorkflowTaskView(task: .object(["name": .string("确认完成"), "category": .string("VERIFIED")]), kind: .fault, preview: true, previewCondition: "reject") }
        case "resolve-order": NavigationStack { WorkflowTaskView(task: .object(["name": .string("复线确认"), "category": .string("RESOLVED")]), kind: .fault, preview: true) }
        case "reject-resolve": NavigationStack { WorkflowTaskView(task: .object(["name": .string("复线确认"), "category": .string("RESOLVED")]), kind: .fault, preview: true, previewCondition: "reject") }
        case "draft-task": NavigationStack { WorkflowTaskView(task: .object(["name": .string("报修提交"), "category": .string("UNTREATED")]), kind: .fault, preview: true) }
        case "erp-approval": NavigationStack { WorkflowTaskView(task: .object(["name": .string("ERP 出库审批"), "category": .string("APPROVED")]), kind: .erp, preview: true) }
        case "erp-confirm": NavigationStack { WorkflowTaskView(task: .object(["name": .string("ERP 出库确认"), "category": .string("CONFIRM")]), kind: .erp, preview: true) }
        case "asset-repairs": NavigationStack { AssetDetailView(asset: Asset.samples[0], initialTab: "维修") }
        case "working": NavigationStack { FaultListView(initialState: .working) }
        case "verifying": NavigationStack { FaultListView(initialState: .verifying) }
        case "finished": NavigationStack { FaultListView(initialState: .finished) }
        case "new-fault": NavigationStack { OriginalFaultForm(preview: true) }
        case "erp-form": NavigationStack { OriginalERPForm(preview: true) }
        case "workflow": NavigationStack { WorkflowNodesView(processID: "", preview: true) }
        case "loading": ZStack { NavigationStack { FaultListView() }; LoadingOverlay(title: "正在加载工单…") }
        case "factories": NavigationStack { FactorySwitchView(preview: true) }
        case "login": NativeLoginView()
        case "asset-picker": NavigationStack { ReferencePicker(source: .assets, preview: true) { _ in } }
        case "user-picker": NavigationStack { ReferencePicker(source: .users, preview: true) { _ in } }
        case "dept-picker": NavigationStack { ReferencePicker(source: .departments, preview: true) { _ in } }
        case "group-picker": NavigationStack { ReferencePicker(source: .groups, preview: true) { _ in } }
        case "division-picker": NavigationStack { ReferencePicker(source: .divisions, preview: true) { _ in } }
        case "project-picker": NavigationStack { ReferencePicker(source: .projects, preview: true) { _ in } }
        case "material-picker": NavigationStack { ReferencePicker(source: .materials, multiple: true, preview: true) { _ in } }
        case "fault-type-picker": NavigationStack { ReferencePicker(source: .faultTypes, preview: true) { _ in } }
        case "outbound-material-picker": NavigationStack { ReferencePicker(source: .outboundMaterials, multiple: true, preview: true) { _ in } }
        case "fault-detail": NavigationStack { FaultDetailView(id: "BX-20261015-001") }
        case "repair-result": NavigationStack { FaultDetailView(id: "BX-20261011-001") }
        case "processing": NavigationStack { FaultProcessingView(id: "BX-20261014-001") }
        case "assets": NavigationStack { AssetListView() }
        case "asset-detail": NavigationStack { AssetDetailView(asset: Asset.samples[0]) }
        case "stock": NavigationStack { WarehouseStockView(preview: true) }
        case "stock-detail": NavigationStack { WarehouseStockFacts(row: PreviewWarehouse.rows[0]) }
        case "warehouse-receipts": NavigationStack { WarehouseReceiptsView(preview: true) }
        case "outbound": NavigationStack { OutboundListView() }
        case "outbound-detail": NavigationStack { OutboundDetailView(id: "CK-20261015-001") }
        case "repair-pool": NavigationStack { OrderPoolView(kind: "维修") }
        case "maintenance-pool": NavigationStack { OrderPoolView(kind: "保养") }
        case "precision-pool": NavigationStack { OrderPoolView(kind: "精密度检查") }
        case "order-detail": NavigationStack { OrderDetailView(kind: "保养", asset: Asset.samples[0]) }
        default: MainTabs()
        }
    }
}

#Preview { MainTabs().environmentObject(DemoStore()).environmentObject(LiveSession()).environmentObject(BottomNavigationModel()) }
