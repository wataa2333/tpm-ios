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
            HStack(alignment: .top) {
                if let count {
                    // One line for up to seven digits with their separators: it shrinks instead of wrapping.
                    Text("\(count)").font(.system(size: 30, weight: .bold, design: .rounded)).monospacedDigit()
                        .lineLimit(1).minimumScaleFactor(0.45).layoutPriority(1)
                        .foregroundStyle(.primary)
                        .contentTransition(.numericText()).animation(.smooth(duration: 0.55), value: count)
                        .accessibilityIdentifier("dashboard-count-" + title)
                } else if subtitle.isEmpty {
                    // Keeps the height steady while the number is still on its way.
                    Text("0").font(.system(size: 30, weight: .bold, design: .rounded)).hidden()
                }
                Spacer(minLength: 8)
                Image(systemName: symbol).font(.title2.weight(.semibold)).foregroundStyle(tint)
            }
            Spacer(minLength: 6)
            Text(title).font(largeTitle ? .title2.weight(.bold) : .headline).foregroundStyle(.primary).multilineTextAlignment(.leading)
            if !subtitle.isEmpty { Text(subtitle).font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.leading) }
        }
        .padding(16)
        .frame(maxWidth: .infinity, minHeight: wide ? wideHeight : smallHeight, alignment: .leading)
        .background {
            GeometryReader { proxy in
                ZStack {
                    Color(uiColor: .secondarySystemGroupedBackground)
                    LinearGradient(colors: [tint.opacity(0.30), tint.opacity(0.08)], startPoint: .topLeading, endPoint: .bottomTrailing)
                    DashboardWave(offset: 0.15 + CGFloat(seed % 3) * 0.1, mirrored: seed % 2 == 0).fill(tint.opacity(0.10))
                    DashboardWave(offset: 0.40, mirrored: seed % 2 == 1).fill(tint.opacity(0.07))
                    Circle().fill(tint.opacity(0.14)).frame(width: proxy.size.height * 1.1)
                        .offset(x: proxy.size.width * (seed % 2 == 0 ? 0.42 : 0.30), y: -proxy.size.height * 0.45)
                }
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 24, style: .continuous))
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

/// A plain text heading between groups of cards.
private struct DashboardHeading: View {
    let title: String
    var body: some View {
        Text(title).font(.title3.weight(.semibold)).foregroundStyle(.primary)
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
    @State private var pulling = false
    @State private var pulledDown = false
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
        ScrollView {
            VStack(spacing: 12) {
                if (loadingCounts || gate.busy) && !pulling && !pulledDown { ProgressView().frame(maxWidth: .infinity).transition(.opacity) }
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
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
        .reportsPulledDown($pulledDown)
        .background(Color(uiColor: .systemGroupedBackground))
        .contentMargins(.bottom, 104, for: .scrollContent)
        .navigationTitle("主页").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .reloadsToTop(on: gate.id("\(navigation.refresh)"))
        .task(id: gate.id("\(navigation.refresh)")) { await gate.run(key: "\(navigation.refresh)") { await loadCounts() } }
        .refreshable { pulling = true; await loadCounts(); pulling = false }
        .animation(.smooth(duration: 0.45), value: loadingCounts || gate.busy)
        .rootTopBar(preview: preview, busy: loadingCounts || gate.busy) { gate.request() }
    }
    /// One request per card, each asking for a single row: only `totalElements` is used.
    private func loadCounts() async {
        guard !preview else { return }
        loadingCounts = true; defer { loadingCounts = false }
        let api = session
        // "所有工单" and "已完结" show no count: the server returns no total for them.
        var queries: [(String, String, [JSONValue])] = []
        queries += states.filter { $0.code != "FINISHED" }.map { ($0.code, "fault/pageAssetFault", [filter("faultState", $0.code)]) }
        queries += [("maintenance", ReadOnlyPoolKind.maintenance.page, ReadOnlyPoolKind.maintenance.waitingFilters),
                    ("precision", ReadOnlyPoolKind.precision.page, ReadOnlyPoolKind.precision.waitingFilters)]
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
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var navigation: BottomNavigationModel
    @EnvironmentObject private var store: DemoStore
    /// Server totals per card: "assets", "outbound" or "stock".
    @State private var counts: [String: Int] = [:]
    /// People with orders waiting for their sign-in or being repaired, A to Z by pinyin.
    @State private var workers: [WorkerLoad] = []
    @State private var workerStatus: WorkerStatus = .idle
    @StateObject private var gate = ReloadGate()
    @State private var loadingCounts = false
    @State private var pulling = false
    @State private var pulledDown = false
    private let columns = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]
    private var workerLetters: [(letter: String, people: [WorkerLoad])] {
        var groups: [(letter: String, people: [WorkerLoad])] = []
        for worker in workers {
            if let last = groups.last, last.letter == worker.initial { groups[groups.count - 1].people.append(worker) }
            else { groups.append((worker.initial, [worker])) }
        }
        return groups
    }
    private func count(_ key: String) -> Int? {
        guard preview else { return counts[key] }
        switch key {
        case "assets": return Asset.samples.count
        case "outbound": return store.outbounds.count
        default: return Material.samples.count
        }
    }
    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                if (loadingCounts || gate.busy) && !pulling && !pulledDown { ProgressView().frame(maxWidth: .infinity).transition(.opacity) }
                DashboardHeading(title: "资产")
                NavigationLink {
                    if preview { AssetListView() } else { LiveCatalogView(source: .assets) }
                } label: { DashboardCard(title: "资产台账", symbol: "list.clipboard", tint: .blue, count: count("assets"), wide: true, seed: 1) }
                DashboardHeading(title: "ERP")
                LazyVGrid(columns: columns, spacing: 12) {
                    NavigationLink {
                        if preview { OutboundListView() } else { LiveWorkflowList(kind: .erp) }
                    } label: { DashboardCard(title: "ERP 出库", symbol: "shippingbox", tint: .orange, count: count("outbound"), seed: 2) }
                    NavigationLink {
                        if preview { StockListView() } else { LiveCatalogView(source: .materials) }
                    } label: { DashboardCard(title: "ERP 库存", symbol: "square.stack.3d.up", tint: .teal, count: count("stock"), seed: 3) }
                }
                if workerStatus != .idle {
                    WorkerSectionHeader(status: workerStatus)
                    if workers.isEmpty, case .done = workerStatus { CompactEmptyState(title: "暂无处理中的工单") }
                    ForEach(workerLetters, id: \.letter) { group in
                        LetterHeading(letter: group.letter)
                        LazyVGrid(columns: columns, spacing: 12) {
                            ForEach(group.people) { person in
                                NavigationLink { WorkerOrdersView(worker: person) } label: {
                                    DashboardCard(title: person.name, symbol: "person.fill", tint: .indigo, count: person.count,
                                                  subtitle: "待签到 \(person.signed.count) · 待完成 \(person.working.count)", seed: person.name.unicodeScalars.reduce(0) { $0 + Int($1.value) } % 9)
                                }
                            }
                        }
                    }
                }
                if preview { DemoNotice() }
            }
            .buttonStyle(DashboardPressStyle())
            .padding(.horizontal, 16).padding(.vertical, 8)
        }
        .reportsPulledDown($pulledDown)
        .background(Color(uiColor: .systemGroupedBackground))
        .contentMargins(.bottom, 104, for: .scrollContent)
        .navigationTitle("工作台").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        // Loads by itself only the first time, when nothing is shown yet. After that it is manual only:
        // the refresh button or pulling the page down.
        .reloadsToTop(on: gate.id("workspace"))
        .task(id: gate.id("workspace")) { await gate.run(key: "workspace") { await loadCounts() } }
        .refreshable { pulling = true; await loadCounts(); pulling = false }
        .animation(.smooth(duration: 0.45), value: loadingCounts || gate.busy)
        .rootTopBar(preview: preview, busy: loadingCounts || gate.busy) { gate.request() }
    }
    /// One request per card asking for a single row: only `totalElements` is used.
    private func loadCounts() async {
        guard !preview else { return }
        loadingCounts = true; defer { loadingCounts = false }
        let api = session
        let queries: [(String, String, [JSONValue])] = [
            ("assets", "asset/pageAsset", []),
            ("outbound", WorkflowKind.erp.page, []),
            ("stock", "erpOutbound/pageErpMaterial", [.object(["field": .string("onhandQty"), "operator": .string("gt"), "value": .number(0)])])
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
        // People in progress: the orders of the two states, grouped by executor. The first time they
        // appear page by page; on a refresh the old people stay until the new list is complete, so the
        // numbers do not shrink and grow again.
        let reader = WorkerOrderReader()
        let firstLoad = workers.isEmpty
        workerStatus = .reading(0)
        do {
            let people = try await reader.read(session: api, progress: { found, read in
                if firstLoad { workers = found }
                workerStatus = .reading(read)
            })
            guard !Task.isCancelled else { workerStatus = .idle; return }
            workers = people
            workerStatus = .done(Date(), orders: people.reduce(0) { $0 + $1.count }, people: people.count)
        } catch {
            // A failed read keeps whatever is already shown, and says so.
            workerStatus = Task.isCancelled ? .idle : .failed
        }
        if counts.isEmpty && workers.isEmpty { gate.markIncomplete("main") }
    }
}
