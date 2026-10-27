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

/// A fresh, abstract dashboard card: a soft wash of one hue over the system card colour, with a few
/// translucent shapes. The hue is a system colour, so the card adapts to light and dark mode by itself.
/// Count top-left, icon top-right, title bottom-left.
struct DashboardCard: View {
    let title: String
    let symbol: String
    let tint: Color
    /// Number of orders behind the card; nil while it is being fetched or not available.
    var count: Int? = nil
    /// Only for cards without a count (workspace entries).
    var subtitle = ""
    var wide = false
    /// A bigger title, for a card that shows no count (the title is then the whole card).
    var largeTitle = false
    var seed = 0
    @ScaledMetric private var smallHeight = 112.0
    @ScaledMetric private var wideHeight = 104.0
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .center) {
                // Keep the same text identity from unknown → known; a hidden zero only reserves space.
                Text(count.map(String.init) ?? "0").font(.custom("GoogleSans-Bold", size: 30, relativeTo: .title)).monospacedDigit()
                    .lineLimit(1).minimumScaleFactor(0.45).layoutPriority(1)
                    .foregroundStyle(.primary)
                    .opacity(count == nil ? 0 : 1)
                    .contentTransition(.numericText()).interfaceMotion(count, duration: 0.55)
                    .accessibilityHidden(count == nil)
                    .accessibilityIdentifier("dashboard-count-" + title)
                Spacer(minLength: 8)
                Image(systemName: symbol).font(AppFont.title2.weight(.semibold)).foregroundStyle(tint)
            }
            Spacer(minLength: 6)
            Text(title).animatedTextValue(title).font(largeTitle ? AppFont.title2.weight(.bold) : AppFont.headline).foregroundStyle(.primary).multilineTextAlignment(.leading)
            if !subtitle.isEmpty { Text(subtitle).animatedTextValue(subtitle).font(AppFont.caption).foregroundStyle(.secondary).multilineTextAlignment(.leading) }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: wide ? wideHeight : smallHeight, alignment: .leading)
        .background { DashboardBackground(tint: tint, seed: seed) }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
        .accessibilityElement(children: .combine)
    }
}

/// The soft wash of one hue with a few translucent shapes, behind every dashboard card.
struct DashboardBackground: View {
    let tint: Color
    var seed = 0
    var body: some View {
        GeometryReader { proxy in
            ZStack {
                Color(uiColor: .secondarySystemGroupedBackground)
                LinearGradient(colors: [tint.opacity(0.38), tint.opacity(0.22), tint.opacity(0.10)], startPoint: .topLeading, endPoint: .bottomTrailing)
                DashboardWave(offset: 0.15 + CGFloat(seed % 3) * 0.1, mirrored: seed % 2 == 0).fill(tint.opacity(0.10))
                DashboardWave(offset: 0.40, mirrored: seed % 2 == 1).fill(tint.opacity(0.07))
                Circle().fill(tint.opacity(0.22)).frame(width: proxy.size.height * 1.1)
                    .offset(x: proxy.size.width * (seed % 2 == 0 ? 0.42 : 0.30), y: -proxy.size.height * 0.45)
            }
        }
    }
}

/// A wide card with a text box and a "查询" button, for looking orders up by a part of what is written
/// on them. Pressing the keyboard's search key does the same as the button.
struct QuickQueryCard: View {
    let title: String
    let symbol: String
    let tint: Color
    let placeholder: String
    var seed = 0
    let search: (String) -> Void
    @State private var text = ""
    @FocusState private var focused: Bool
    private var trimmed: String { text.trimmingCharacters(in: .whitespacesAndNewlines) }
    /// The words go to the result page and the box empties, so coming back to look up another machine
    /// starts from a blank box and never from the last search.
    private func go() { guard !trimmed.isEmpty else { return }; let words = trimmed; focused = false; text = ""; search(words) }
    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .center) {
                Text(title).animatedTextValue(title).font(AppFont.headline).foregroundStyle(.primary)
                Spacer(minLength: 8)
                Image(systemName: symbol).font(AppFont.title2.weight(.semibold)).foregroundStyle(tint)
            }
            HStack(spacing: 10) {
                TextField(placeholder, text: $text)
                    .focused($focused)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .submitLabel(.search).onSubmit(go)
                    .padding(.horizontal, 14).padding(.vertical, 11)
                    .background(.background.opacity(0.75), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    .accessibilityLabel(title)
                Button("查询", action: go)
                    .buttonStyle(.glassProminent).controlSize(.large)
                    .disabled(trimmed.isEmpty)
                    .accessibilityIdentifier("quick-query-" + title)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background { DashboardBackground(tint: tint, seed: seed) }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

/// Where a quick query goes: the order list opened with the search already filled in.
struct QuickQuery: Identifiable, Hashable {
    let id = UUID()
    let field: FaultSearchField
    let text: String
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

/// A plain text heading between groups of cards.
private struct DashboardHeading: View {
    let title: String
    var body: some View {
        Text(title).animatedTextValue(title).font(AppFont.title3.weight(.semibold)).foregroundStyle(.primary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 8).accessibilityAddTraits(.isHeader)
    }
}

struct DashboardHome: View {
    var preview = false
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var store: DemoStore
    /// Server totals per card: "all", a state code, "maintenance" or "precision".
    @State private var counts: [String: Int] = [:]
    @StateObject private var gate = ReloadGate()
    @State private var loadingCounts = false
    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    /// The repair-order cards: title, state code, symbol and the matching preview state.
    private let states: [(title: String, code: String, symbol: String, preview: FaultState)] = [
        ("待受理", "REPORTED", "tray", .reported), ("待抢单", "RECEIVED", "hand.raised", .received),
        ("待签到", "SIGNED", "location", .signed), ("待完成", "REPAIRED", "wrench.and.screwdriver", .working),
        ("待确认", "VERIFIED", "checkmark.bubble", .verifying), ("已完结", "FINISHED", "checkmark.circle", .finished)
    ]
    private func count(_ key: String, preview state: FaultState? = nil) -> Int? {
        guard preview else { return counts[key] }
        if key == "all" || key == "FINISHED" { return nil }
        return state.map { s in store.faults.filter { $0.state == s }.count }
    }
    var body: some View {
        // The same scroll container as the todo page, which does not shake when pulled down.
        CardPage {
            VStack(spacing: 12) {
                DashboardHeading(title: "维修工单")
                NavigationLink {
                    if preview { FaultListView(initialState: .received) } else { LiveWorkflowList(kind: .fault, initialState: "") }
                } label: { DashboardCard(title: "所有工单", symbol: "list.bullet.rectangle", tint: .blue, wide: true, largeTitle: true, seed: 0) }
                LazyVGrid(columns: columns, spacing: 12) {
                    ForEach(Array(states.enumerated()), id: \.offset) { index, item in
                        NavigationLink {
                            if preview { FaultListView(initialState: item.preview) } else { LiveWorkflowList(kind: .fault, initialState: item.code) }
                        } label: { DashboardCard(title: item.title, symbol: item.symbol, tint: faultDisplayColor(item.code), count: item.code == "FINISHED" ? nil : count(item.code, preview: item.preview), seed: index + 1) }
                    }
                }
                DashboardHeading(title: "保养工单")
                LazyVGrid(columns: columns, spacing: 12) {
                    NavigationLink {
                        if preview { OrderPoolView(kind: "保养") } else { ReadOnlyPoolView(kind: .maintenance) }
                    } label: { DashboardCard(title: "保养工单池", symbol: "oilcan", tint: .mint, count: count("maintenance"), seed: 7) }
                    NavigationLink {
                        if preview { OrderPoolView(kind: "精密度检查") } else { ReadOnlyPoolView(kind: .precision) }
                    } label: { DashboardCard(title: "精密度检查工单池", symbol: "ruler", tint: .indigo, count: count("precision"), seed: 8) }
                }
                if preview { DemoNotice() }
            }
            .buttonStyle(DashboardPressStyle())
            // Room under the last card for the floating "+" button.
            .padding(.horizontal, 16).padding(.bottom, 80)
        }
        // The loading spinner floats over the page instead of sitting in it: it is for the first load and the
        // refresh button, takes no room, and so can never move the content or fight the pull-down gesture.
        // Pulling down shows the system spinner only and changes nothing on this page until the numbers arrive.
        .overlay(alignment: .top) {
            ProgressView().padding(.top, 6).opacity(loadingCounts || gate.busy ? 1 : 0).allowsHitTesting(false)
                .interfaceMotion(loadingCounts || gate.busy)
        }
        .navigationTitle("主页").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .reloadsToTop(on: gate.id("\(navigation.refresh)"))
        .task(id: gate.id("\(navigation.refresh)")) { await gate.run(key: "\(navigation.refresh)") { loadingCounts = true; await loadCounts(); loadingCounts = false } }
        .refreshable { await loadCounts() }
        .rootTopBar(preview: preview, busy: loadingCounts || gate.busy) { gate.request() }
    }
    /// One request per card, each asking for a single row: only `totalElements` is used.
    private func loadCounts() async {
        guard !preview else { return }
        let api = session
        // "所有工单" and "已完结" show no count: the server returns no total for them.
        var queries: [(String, String, [JSONValue])] = []
        queries += states.filter { $0.code != "FINISHED" }.map { ($0.code, "fault/pageAssetFault", [filter("faultState", $0.code)]) }
        queries += [("maintenance", ReadOnlyPoolKind.maintenance.page, ReadOnlyPoolKind.maintenance.waitingFilters),
                    ("precision", ReadOnlyPoolKind.precision.page, ReadOnlyPoolKind.precision.waitingFilters)]
        var fresh: [String: Int] = [:]
        await withTaskGroup(of: (String, Int?).self) { group in
            for (key, path, filters) in queries {
                group.addTask {
                    do { return (key, serverPageCount(try await api.page(path, filters: filters, limit: 1))) }
                    catch { return (key, nil) }
                }
            }
            for await (key, value) in group { if let value { fresh[key] = value } }
        }
        // One update for the whole page, so the cards change together and the page is not redrawn six times.
        guard !Task.isCancelled else { return }
        counts.merge(fresh) { _, new in new }
    }
}

struct DashboardWorkspace: View {
    var preview = false
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var store: DemoStore
    /// Server totals per card: "assets", "outbound" or "stock".
    @State private var counts: [String: Int] = [:]
    @StateObject private var gate = ReloadGate()
    @State private var loadingCounts = false
    @State private var route: QuickQuery?
    @AppStorage(ManagementSettings.enabledKey) private var managementEnabled = false
    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    private func count(_ key: String) -> Int? {
        guard preview else { return counts[key] }
        switch key {
        case "assets": return Asset.samples.count
        case "outbound": return store.outbounds.count
        default: return Material.samples.count
        }
    }
    var body: some View {
        // The same scroll container as the todo page, which does not shake when pulled down.
        CardPage {
            VStack(spacing: 12) {
                DashboardHeading(title: "资产")
                NavigationLink {
                    if preview { AssetListView() } else { LiveCatalogView(source: .assets) }
                } label: { DashboardCard(title: "资产台账", symbol: "list.clipboard", tint: .blue, count: count("assets"), wide: true, seed: 1) }
                DashboardHeading(title: "ERP")
                LazyVGrid(columns: columns, spacing: 12) {
                    NavigationLink {
                        if preview { OutboundListView() } else { LiveWorkflowList(kind: .erp, independent: true) }
                    } label: { DashboardCard(title: "ERP 出库", symbol: "shippingbox", tint: .orange, count: count("outbound"), seed: 2) }
                    NavigationLink {
                        if preview { StockListView() } else { LiveCatalogView(source: .materials) }
                    } label: { DashboardCard(title: "ERP 库存", symbol: "square.stack.3d.up", tint: Color(red: 0.18, green: 0.58, blue: 0.29), count: count("stock"), seed: 3) }
                }
                DashboardHeading(title: "快速查询维修")
                QuickQueryCard(title: "按机台号查询", symbol: "gearshape.2", tint: .purple, placeholder: "输入机台号", seed: 4) { route = QuickQuery(field: .machine, text: $0) }
                QuickQueryCard(title: "按资产编码查询", symbol: "barcode", tint: Color(red: 0.02, green: 0.64, blue: 0.80), placeholder: "输入编码的尾号或中间一段", seed: 5) { route = QuickQuery(field: .assetCode, text: $0) }
                if !preview {
                    DashboardHeading(title: "精密度看板")
                    NavigationLink { PrecisionDashboardScreen() } label: {
                        DashboardCard(title: "精密度检查看板", symbol: "scope", tint: Color(red: 0.80, green: 0.56, blue: 0.06), subtitle: "017CNC覆盖率 · 等级与逐项数值", wide: true, seed: 7)
                    }
                }
                if managementEnabled && !preview {
                    DashboardHeading(title: "管理看板")
                    NavigationLink { ManagementScreen() } label: {
                        DashboardCard(title: "人员与工单分析", symbol: "person.3.sequence", tint: Color(red: 0.87, green: 0.23, blue: 0.44), subtitle: "维修待处理 · 处理统计与趋势", wide: true, seed: 6)
                    }
                }
                if preview { DemoNotice() }
            }
            .buttonStyle(DashboardPressStyle())
            // Room under the last card for the floating "+" button.
            .padding(.horizontal, 16).padding(.bottom, 80)
        }
        .scrollDismissesKeyboard(.interactively)
        .navigationDestination(item: $route) { query in
            if preview { FaultListView(initialState: .received) }
            // A new identity per query: every query opens a fresh page (own list, search, filters, scroll),
            // so nothing of an earlier query is carried over into it.
            else { LiveWorkflowList(kind: .fault, initialState: "", initialQuery: query.text, initialField: query.field, title: query.field == .machine ? "机台号 " + query.text : "资产编码 " + query.text).id(query.id) }
        }
        // The loading spinner floats over the page instead of sitting in it: it is for the first load and the
        // refresh button, takes no room, and so can never move the content or fight the pull-down gesture.
        // Pulling down shows the system spinner only and changes nothing on this page until the numbers arrive.
        .overlay(alignment: .top) {
            ProgressView().padding(.top, 6).opacity(loadingCounts || gate.busy ? 1 : 0).allowsHitTesting(false)
                .interfaceMotion(loadingCounts || gate.busy)
        }
        .onChange(of: queries.revision) { counts = [:]; gate.request() }
        .navigationTitle("看板").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        // Loads by itself only the first time, when nothing is shown yet. After that it is manual only:
        // the refresh button or pulling the page down.
        .reloadsToTop(on: gate.id("workspace"))
        .task(id: gate.id("workspace")) { await gate.run(key: "workspace") { loadingCounts = true; await loadCounts(); loadingCounts = false } }
        .refreshable { await loadCounts() }
        .rootTopBar(preview: preview, busy: loadingCounts || gate.busy) { gate.request() }
    }
    /// One request per card asking for a single row: only `totalElements` is used.
    private func loadCounts() async {
        guard !preview else { return }
        guard let api = try? await queries.source(for: session) else { return }
        let queries: [(String, String, [JSONValue])] = [
            ("assets", "asset/pageAsset", []),
            ("outbound", WorkflowKind.erp.page, []),
            ("stock", "erpOutbound/pageErpMaterial", [.object(["field": .string("onhandQty"), "operator": .string("gt"), "value": .number(0)])])
        ]
        var fresh: [String: Int] = [:]
        await withTaskGroup(of: (String, Int?).self) { group in
            for (key, path, filters) in queries {
                group.addTask {
                    do { return (key, serverPageCount(try await api.page(path, filters: filters, limit: 1))) }
                    catch { return (key, nil) }
                }
            }
            for await (key, value) in group { if let value { fresh[key] = value } }
        }
        guard !Task.isCancelled else { return }
        counts.merge(fresh) { _, new in new }
        if counts.isEmpty { gate.markIncomplete("main") }
    }
}
