import SwiftUI
import PhotosUI

@main
struct TPMApp: App {
    @StateObject private var store = DemoStore()
    @StateObject private var session = LiveSession()
    @StateObject private var navigation = BottomNavigationModel()
    var body: some Scene {
        WindowGroup { PreviewRouter().environmentObject(store).environmentObject(session).environmentObject(navigation) }
    }
}

struct PreviewRouter: View {
    var body: some View {
        #if DEBUG
        let args = ProcessInfo.processInfo.arguments
        if let index = args.firstIndex(of: "--screen"), args.indices.contains(index + 1) {
            PreviewScreen(name: args[index + 1])
        } else { AuthenticatedRoot() }
        #else
        AuthenticatedRoot()
        #endif
    }
}

enum FaultState: String, CaseIterable, Identifiable {
    case reported = "待受理", working = "待完成", verifying = "待确认", finished = "已完成"
    var id: String { rawValue }
    var color: Color {
        switch self { case .reported: .orange; case .working: .blue; case .verifying: .purple; case .finished: .green }
    }
    var symbol: String {
        switch self { case .reported: "tray"; case .working: "wrench.and.screwdriver"; case .verifying: "checkmark.bubble"; case .finished: "checkmark.circle" }
    }

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
            Fault(id: "BX-202610\(15-offset)-00\(index+1)", asset: Asset.samples[(offset+index)%4],
                  description: ["主轴运行时出现异常振动，伴随间歇性异响。", "设备启动后压力不稳定，请检查运行状态。"][index],
                  reporter: ["张明", "李华"][index], time: offset == 0 ? "今天 09:\(index == 0 ? "30" : "15")" : "10月\(15-offset)日 14:20", state: state,
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
            .font(.caption).foregroundStyle(state.color).fixedSize()
    }
}

struct MainTabs: View {
    @State private var selection: Int
    init(selection: Int = 0) { _selection = State(initialValue: selection) }
    var body: some View {
        TabView(selection: $selection) {
            Tab("主页", systemImage: "house", value: 0) { NavigationStack { HomeView() } }
            Tab("工作台", systemImage: "square.grid.2x2", value: 1) { NavigationStack { WorkspaceView() } }
            Tab("故障报修", systemImage: "wrench.and.screwdriver", value: 2) { NavigationStack { FaultListView() } }
            Tab("我的", systemImage: "person.crop.circle", value: 3) { NavigationStack { ProfileView() } }
        }
        .toolbar(.hidden, for: .tabBar)
        .safeAreaInset(edge: .bottom, spacing: 0) { BottomNavigation(selection: $selection, preview: true) }
    }
}

struct HomeView: View {
    @EnvironmentObject private var store: DemoStore
    var body: some View {
        List {
            Section {
                NavigationLink { FaultListView(initialState: .reported) } label: {
                    HStack {
                        Label("待受理", systemImage: "tray").foregroundStyle(.primary)
                        Spacer()
                        Text("\(store.faults.filter { $0.state == .reported }.count)").monospacedDigit().foregroundStyle(.secondary)
                    }
                }
            } footer: { DemoNotice() }
            Section("工单池") {
                NavigationLink { OrderPoolView(kind: "维修") } label: { PoolEntry(title: "维修工单池", symbol: "wrench.and.screwdriver", subtitle: "待接单的维修任务") }
                NavigationLink { OrderPoolView(kind: "保养") } label: { PoolEntry(title: "保养工单池", symbol: "oilcan", subtitle: "计划保养与维护任务") }
                NavigationLink { OrderPoolView(kind: "精密度检查") } label: { PoolEntry(title: "精密度检查工单池", symbol: "ruler", subtitle: "设备精度检查任务") }
            }
        }
        .navigationTitle("主页")
    }
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
    var body: some View {
        List {
            Section("资产管理") {
                NavigationLink { AssetListView() } label: {
                    PoolEntry(title: "资产台账", symbol: "list.clipboard", subtitle: "搜索编号、名称、型号，查看资产详情")
                }
            }
            Section("备品备件") {
                NavigationLink { OutboundListView() } label: { PoolEntry(title: "ERP 出库", symbol: "shippingbox", subtitle: "出库单查询与明细") }
                NavigationLink { StockListView() } label: { PoolEntry(title: "库存", symbol: "archivebox", subtitle: "物料数量、规格与库位") }
            }
            Section { DemoNotice() }
        }
        .navigationTitle("工作台")
    }
}

struct FaultListView: View {
    @EnvironmentObject private var store: DemoStore
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var state: FaultState
    @State private var query = ""
    @State private var creating = false
    init(initialState: FaultState = .reported) { _state = State(initialValue: initialState) }
    private var rows: [Fault] {
        store.faults.filter { $0.state == state && (query.isEmpty || ($0.asset.name + $0.asset.id + $0.id + $0.description).localizedCaseInsensitiveContains(query)) }
    }
    var body: some View {
        List {
            Section {
                Picker("报修状态", selection: $state) {
                    ForEach(FaultState.allCases) { Text($0.rawValue).tag($0) }
                }.pickerStyle(.segmented).listRowInsets(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 8))
            } footer: { DemoNotice() }
            Section {
                ForEach(rows) { fault in
                    NavigationLink { FaultDetailView(id: fault.id) } label: { FaultRow(fault: fault) }
                        .navigationLinkIndicatorVisibility(.hidden)
                        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                        .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
                }
                if rows.isEmpty { ContentUnavailableView("暂无相关报修", systemImage: state.symbol, description: Text("可以切换状态或调整搜索内容。")) }
            } header: {
                HStack { Text(state.rawValue); Spacer(); Text("\(rows.count) 单").monospacedDigit() }
            }
        }
        .navigationTitle("故障报修")
        .searchable(text: $query, prompt: "搜索设备、编号或故障")
        .onAppear { navigation.newDocument = .fault }
        .onDisappear { if navigation.newDocument == .fault { navigation.newDocument = nil } }
        .sheet(isPresented: $creating) { NavigationStack { NewFaultView() } }
    }
}

struct FaultRow: View {
    let fault: Fault
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline) {
                Text(fault.asset.name).font(.headline)
                Spacer()
                StatusLabel(state: fault.state)
            }
            Text(fault.description).font(.subheadline).lineLimit(2)
            Text("\(fault.asset.id) · \(fault.asset.location)").font(.caption).foregroundStyle(.secondary)
            HStack { Text(fault.reporter); Spacer(); Text(fault.time) }.font(.caption).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
    }
}

struct FaultDetailView: View {
    @EnvironmentObject private var store: DemoStore
    let id: String
    @State private var processing = false
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
                                Text(fault.asset.name).font(.headline)
                                Text("\(fault.asset.id) · \(fault.asset.location)").font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                    Section("故障描述") { Text(fault.description).textSelection(.enabled) }
                    if !fault.result.isEmpty { Section("维修结果") { Text(fault.result) } }
                    Section("附件") { Text(fault.photoCount == 0 ? "暂无附件" : "已选择 \(fault.photoCount) 张照片（演示，不上传）").foregroundStyle(.secondary) }
                    Section("处理记录") {
                        ForEach(Array(fault.records.enumerated()), id: \.offset) { index, record in
                            Label(record, systemImage: index == fault.records.count - 1 ? "circle.inset.filled" : "circle").font(.subheadline)
                        }
                    }
                    Section { DemoNotice() }
                }
                .toolbar {
                    ToolbarSpacer(.flexible, placement: .bottomBar)
                    ToolbarItem(placement: .bottomBar) { Button("维修处理") { processing = true }.buttonStyle(.glassProminent) }
                }
                .sheet(isPresented: $processing) { NavigationStack { FaultProcessingView(id: id) } }
            } else { ContentUnavailableView("单据不存在", systemImage: "doc.questionmark") }
        }
        .navigationTitle("报修详情").navigationBarTitleDisplayMode(.inline)
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
        .navigationTitle("资产台账").searchable(text: $query, prompt: "资产编号、名称、型号或位置")
        .overlay { if !query.isEmpty && !Asset.samples.contains(where: { ($0.id + $0.name + $0.model + $0.location).localizedCaseInsensitiveContains(query) }) { ContentUnavailableView.search(text: query) } }
    }
}

struct AssetRow: View {
    let asset: Asset
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(asset.name).font(.headline)
            Text("\(asset.id) · \(asset.model)").font(.subheadline).foregroundStyle(.secondary)
            Text(asset.location).font(.caption).foregroundStyle(.secondary)
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
        .navigationTitle("选择设备").navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "搜索设备名称或编号")
    }
}

struct AssetDetailView: View {
    let asset: Asset
    var body: some View {
        List {
            Section {
                Text(asset.name).font(.title2.bold())
                LabeledContent("资产编号", value: asset.id)
                LabeledContent("使用状态", value: asset.state)
                LabeledContent("规格型号", value: asset.model)
            }
            Section("使用信息") {
                LabeledContent("设备位置", value: asset.location)
                LabeledContent("使用部门", value: asset.department)
                LabeledContent("责任人", value: asset.owner)
            }
            Section("设备资料") {
                LabeledContent("资产类别", value: "生产设备")
                LabeledContent("启用日期", value: "2023-06-12")
            }
            Section { DemoNotice() }
        }
        .navigationTitle("资产详情").navigationBarTitleDisplayMode(.inline)
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
                    NavigationLink { OrderDetailView(kind: kind, asset: asset) } label: {
                        VStack(alignment: .leading, spacing: 7) {
                            Text("\(asset.name)\(kind)").font(.headline)
                            Text("\(asset.id) · \(asset.location)").font(.subheadline).foregroundStyle(.secondary)
                            Text(store.claimedOrders.contains(kind + asset.id) ? "已领取（模拟）" : "待接单 · 今天 10:00").font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 5)
                    }
                }
            } footer: { DemoNotice() }
        }
        .navigationTitle("\(kind)工单池").navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "搜索设备或资产编号")
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
            Section { Button(claimed ? "已领取（模拟）" : "领取工单") { confirming = true }.disabled(claimed) } footer: { DemoNotice() }
        }
        .navigationTitle("工单详情").navigationBarTitleDisplayMode(.inline)
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
                                Text(material.shelf).font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Text("\(material.quantity) \(material.unit)").font(.subheadline.monospacedDigit())
                        }.padding(.vertical, 5)
                    }
                }
                if rows.isEmpty { ContentUnavailableView.search(text: query) }
            } footer: { DemoNotice() }
        }
        .navigationTitle("库存").searchable(text: $query, prompt: "物料编号、名称、规格或库位")
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
        .navigationTitle("库存详情").navigationBarTitleDisplayMode(.inline)
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
                    NavigationLink { OutboundDetailView(id: outbound.id) } label: {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(outbound.material.name).font(.headline)
                            Text("\(outbound.id) · \(outbound.quantity) \(outbound.material.unit)").font(.subheadline).foregroundStyle(.secondary)
                            Text("\(outbound.department) · \(outbound.applicant) · \(outbound.date)").font(.caption).foregroundStyle(.secondary)
                        }.padding(.vertical, 5)
                    }
                }
                if rows.isEmpty { ContentUnavailableView("暂无出库单", systemImage: "shippingbox") }
            } footer: { DemoNotice() }
        }
        .navigationTitle("ERP 出库").searchable(text: $query, prompt: "搜索出库单、物料或部门")
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
                if !item.completed {
                    Section { Button("确认出库（模拟）") { confirming = true } } footer: { Text("仅改变演示单据状态，不扣减库存，不连接 ERP。") }
                }
                Section { DemoNotice() }
            }
            .navigationTitle("出库详情").navigationBarTitleDisplayMode(.inline)
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
            Section("应用") { LabeledContent("版本", value: "0.2.0"); LabeledContent("数据来源", value: "模拟数据") }
            Section { Text("账号、工厂和业务权限将在对接原服务器后显示。").foregroundStyle(.secondary) }
        }
        .navigationTitle("我的")
    }
}

struct PreviewScreen: View {
    let name: String
    var body: some View {
        switch name {
        case "home": MainTabs(selection: 0)
        case "workspace": MainTabs(selection: 1)
        case "faults": MainTabs(selection: 2)
        case "profile": MainTabs(selection: 3)
        case "working": NavigationStack { FaultListView(initialState: .working) }
        case "verifying": NavigationStack { FaultListView(initialState: .verifying) }
        case "finished": NavigationStack { FaultListView(initialState: .finished) }
        case "new-fault": NavigationStack { OriginalFaultForm(preview: true) }
        case "erp-form": NavigationStack { OriginalERPForm(preview: true) }
        case "workflow": NavigationStack { WorkflowNodesView(processID: "", preview: true) }
        case "loading": ProgressView("正在加载…")
        case "factories": NavigationStack { FactorySwitchView(preview: true) }
        case "fault-detail": NavigationStack { FaultDetailView(id: "BX-20261015-001") }
        case "repair-result": NavigationStack { FaultDetailView(id: "BX-20261013-001") }
        case "processing": NavigationStack { FaultProcessingView(id: "BX-20261014-001") }
        case "assets": NavigationStack { AssetListView() }
        case "asset-detail": NavigationStack { AssetDetailView(asset: Asset.samples[0]) }
        case "stock": NavigationStack { StockListView() }
        case "stock-detail": NavigationStack { MaterialDetailView(material: Material.samples[0]) }
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
