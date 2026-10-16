import SwiftUI

private struct DashboardPressStyle: ButtonStyle {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.97 : 1)
            .opacity(configuration.isPressed ? 0.9 : 1)
            .animation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.2), value: configuration.isPressed)
    }
}

struct DashboardCard: View {
    let title: String
    let subtitle: String
    let symbol: String
    let palette: Int
    /// Number of orders behind the card; nil while it is still being fetched or unavailable.
    var count: Int? = nil
    var countLabel = ""
    @ScaledMetric private var height = 170.0
    private var colors: [Color] {
        switch palette {
        case 0: [Color(red: 0.08, green: 0.31, blue: 0.58), Color(red: 0.04, green: 0.59, blue: 0.65)]
        case 1: [Color(red: 0.24, green: 0.16, blue: 0.60), Color(red: 0.58, green: 0.27, blue: 0.74)]
        case 2: [Color(red: 0.04, green: 0.37, blue: 0.30), Color(red: 0.17, green: 0.61, blue: 0.40)]
        case 3: [Color(red: 0.61, green: 0.24, blue: 0.18), Color(red: 0.83, green: 0.43, blue: 0.24)]
        case 4: [Color(red: 0.10, green: 0.23, blue: 0.59), Color(red: 0.22, green: 0.48, blue: 0.81)]
        default: [Color(red: 0.33, green: 0.18, blue: 0.50), Color(red: 0.64, green: 0.29, blue: 0.54)]
        }
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                Image(systemName: symbol).font(.system(size: 27, weight: .medium))
                    .frame(width: 40, height: 40, alignment: .leading)
                Spacer(minLength: 8)
                if let count {
                    Text("\(count)").font(.system(.title, design: .rounded, weight: .bold)).monospacedDigit()
                        .accessibilityIdentifier("dashboard-count-" + title)
                }
            }
            Spacer(minLength: 14)
            Text(title).font(.headline).lineLimit(nil).multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Text(count == nil || countLabel.isEmpty ? subtitle : countLabel).font(.caption).foregroundStyle(.white.opacity(0.88))
                .lineLimit(nil).multilineTextAlignment(.leading)
        }
        .foregroundStyle(.white)
        .padding(16).frame(maxWidth: .infinity, minHeight: height, alignment: .leading)
        .background {
            GeometryReader { proxy in
                ZStack {
                    LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing)
                    ForEach(0..<3) { layer in
                        DashboardWave(offset: CGFloat(layer) * 0.18, mirrored: palette % 2 == 0)
                            .fill(.white.opacity(0.06 + Double(layer) * 0.025))
                    }
                    Circle().fill(.white.opacity(0.06))
                        .frame(width: proxy.size.width * 1.2)
                        .offset(x: proxy.size.width * 0.45, y: -proxy.size.height * 0.3)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 28, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

private struct DashboardWave: Shape {
    var offset: CGFloat
    var mirrored: Bool
    func path(in rect: CGRect) -> Path {
        let w = rect.width, h = rect.height
        return Path { path in
            path.move(to: CGPoint(x: 0, y: h * (0.25 + offset)))
            path.addCurve(to: CGPoint(x: w, y: h * (0.6 + offset)),
                control1: CGPoint(x: w * 0.3, y: h * (mirrored ? 1.1 : -0.2)),
                control2: CGPoint(x: w * 0.7, y: h * (mirrored ? -0.1 : 1.1)))
            path.addLine(to: CGPoint(x: w, y: h))
            path.addLine(to: CGPoint(x: 0, y: h))
            path.closeSubpath()
        }
    }
}

struct DashboardHome: View {
    var preview = false
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var store: DemoStore
    /// Server totals per card: "pending", "pool", "maintenance", "precision".
    @State private var counts: [String: Int] = [:]
    @StateObject private var gate = ReloadGate()
    @State private var loadingCounts = false
    @State private var pulling = false
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 16)]
    private func count(_ key: String) -> Int? {
        guard preview else { return counts[key] }
        switch key {
        case "pending": return store.faults.filter { $0.state == .reported }.count
        case "pool": return store.faults.filter { $0.state == .received }.count
        default: return nil
        }
    }
    var body: some View {
        ScrollView {
            if loadingCounts && !pulling { ProgressView().frame(maxWidth: .infinity).padding(.top, 12).transition(.opacity) }
            LazyVGrid(columns: columns, spacing: 16) {
                NavigationLink {
                    if preview { FaultListView(initialState: .reported) } else { LiveWorkflowList(kind: .fault, initialState: "REPORTED") }
                } label: { DashboardCard(title: "待受理", subtitle: "查看与受理报修", symbol: "tray", palette: 3, count: count("pending"), countLabel: "个待受理") }
                NavigationLink {
                    if preview { OrderPoolView(kind: "维修") } else { LiveWorkflowList(kind: .fault, initialState: "RECEIVED") }
                } label: { DashboardCard(title: "维修工单池", subtitle: "查看并领取工单", symbol: "wrench.and.screwdriver", palette: 0, count: count("pool"), countLabel: "个待接单") }
                NavigationLink {
                    if preview { OrderPoolView(kind: "保养") } else { ReadOnlyPoolView(kind: .maintenance) }
                } label: {
                    DashboardCard(title: "保养工单池", subtitle: "保养任务", symbol: "oilcan", palette: 2, count: count("maintenance"), countLabel: "个待接单")
                }
                NavigationLink {
                    if preview { OrderPoolView(kind: "精密度检查") } else { ReadOnlyPoolView(kind: .precision) }
                } label: {
                    DashboardCard(title: "精密度检查工单池", subtitle: "精密度检查任务", symbol: "ruler", palette: 1, count: count("precision"), countLabel: "个待接单")
                }
            }.buttonStyle(DashboardPressStyle()).padding(20)
            if preview { DemoNotice().padding(.horizontal, 20) }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .contentMargins(.bottom, 104, for: .scrollContent)
        .navigationTitle("主页").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .reloadsToTop(on: gate.id("\(navigation.refresh)"))
        .task(id: gate.id("\(navigation.refresh)")) { await gate.run(key: "\(navigation.refresh)") { await loadCounts() } }
        .refreshable { pulling = true; await loadCounts(); pulling = false }
        .animation(.snappy(duration: 0.3), value: loadingCounts)
        // One row: the refresh button next to the factory menu.
        .toolbar {
            if !preview { ToolbarItem(placement: .topBarTrailing) { RefreshButton(busy: loadingCounts) { gate.request() } } }
            ToolbarItem(placement: .topBarTrailing) { if preview { PreviewFactoryButton() } else { FactorySwitcherButton() } }
        }
    }
    /// One request per card, each asking for a single row: only `totalElements` is used.
    private func loadCounts() async {
        guard !preview else { return }
        loadingCounts = true; defer { loadingCounts = false }
        let api = session
        let queries: [(String, String, [JSONValue])] = [
            ("pending", "fault/pageAssetFault", [filter("faultState", "REPORTED")]),
            ("pool", "fault/pageAssetFault", [filter("faultState", "RECEIVED")]),
            ("maintenance", ReadOnlyPoolKind.maintenance.page, ReadOnlyPoolKind.maintenance.waitingFilters),
            ("precision", ReadOnlyPoolKind.precision.page, ReadOnlyPoolKind.precision.waitingFilters)
        ]
        await withTaskGroup(of: (String, Int?).self) { group in
            for (key, path, filters) in queries {
                group.addTask {
                    do { return (key, serverPageCount(try await api.page(path, filters: filters, limit: 1))) }
                    catch { return (key, nil) }
                }
            }
            for await (key, value) in group {
                guard !Task.isCancelled else { return }
                if let value { counts[key] = value }
            }
        }
    }
}

struct DashboardWorkspace: View {
    var preview = false
    private let columns = [GridItem(.adaptive(minimum: 150), spacing: 16)]
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                NavigationLink {
                    if preview { AssetListView() } else { LiveCatalogView(source: .assets) }
                } label: { DashboardCard(title: "资产台账", subtitle: "搜索与查看资产详情", symbol: "list.clipboard", palette: 4) }
                LazyVGrid(columns: columns, spacing: 16) {
                NavigationLink {
                    if preview { OutboundListView() } else { LiveWorkflowList(kind: .erp) }
                } label: { DashboardCard(title: "ERP 出库", subtitle: "领料申请与出库明细", symbol: "shippingbox", palette: 1) }
                NavigationLink {
                    if preview { StockListView() } else { LiveCatalogView(source: .materials) }
                } label: { DashboardCard(title: "ERP 库存", subtitle: "物料、数量与库位", symbol: "square.stack.3d.up", palette: 0) }
                }
            }.buttonStyle(DashboardPressStyle()).padding(20)
            if preview { DemoNotice().padding(.horizontal, 20) }
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .contentMargins(.bottom, 104, for: .scrollContent)
        .navigationTitle("工作台").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
    }
}
