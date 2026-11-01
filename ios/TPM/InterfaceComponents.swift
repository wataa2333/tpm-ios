import SwiftUI

/// Shared stale-data treatment for analytics dashboards and their drill-down pages.
struct PreviousDataNotice: View {
    var date: Date?
    var message = "请刷新获取最新数据。"
    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 4) {
                Text("上次读取的数据，非最新").font(AppFont.subheadline.weight(.semibold))
                if let date { Text(date.formatted(date: .numeric, time: .shortened)).font(AppFont.caption) }
                Text(message).font(AppFont.caption)
            }
        } icon: { Image(systemName: "clock.arrow.circlepath").foregroundStyle(.orange) }
        .frame(maxWidth: .infinity, alignment: .leading).padding(12)
        .background(Color.orange.opacity(0.12), in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
    }
}

struct SearchPageChrome: ViewModifier {
    var isSearching = false
    /// Tab root pages use the large title; pushed pages keep the inline one.
    var largeTitle = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        content
            .contentMargins(.top, 8, for: .scrollContent)
            .contentMargins(.bottom, 16, for: .scrollContent)
            .listSectionSpacing(.compact)
            .scrollContentBackground(.hidden)
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationBarTitleDisplayMode(largeTitle ? .large : .inline).nativeNavigationChrome()
    }
}

extension View {
    func searchPageChrome(isSearching: Bool = false, largeTitle: Bool = false) -> some View {
        modifier(SearchPageChrome(isSearching: isSearching, largeTitle: largeTitle))
    }
    func initialLoadingContent(_ hidden: Bool) -> some View {
        modifier(InitialLoadingContent(hidden: hidden))
    }
}

struct InitialLoadingContent: ViewModifier {
    let hidden: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content.opacity(hidden ? 0 : 1)
            .accessibilityHidden(hidden)
            .allowsHitTesting(!hidden)

    }
}

extension EnvironmentValues {
    @Entry var previewReferences = false
    @Entry var closeWorkOrder: (() -> Void)? = nil
}

struct BottomAction: View {
    let title: String
    let symbol: String
    var color: Color = .accentColor
    var busy = false
    let action: () -> Void
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        Button(action: action) {
            if busy { ProgressView().accessibilityLabel(title) }
            else { Label(title, systemImage: symbol) }
        }
        .buttonStyle(.borderedProminent).tint(color)
        .disabled(busy)
        .accessibilityLabel(title)

    }
}

/// Last row of a paged list. When it scrolls into view it loads the next page by itself, with the
/// system spinner; there is no "load more" button. It re-arms after every page (`loaded` changes),
/// so a short first page that leaves it on screen keeps loading until the screen is full. After a
/// failed page it does not retry on its own (the page shows its own error and retry button).
struct LoadMoreFooter: View {
    let hasMore: Bool
    var failed = false
    var loading = false
    let loaded: Int
    var total: Int? = nil
    let loadMore: () async -> Void
    var body: some View {
        Group {
            if hasMore && !failed {
                HStack(spacing: 10) { ProgressView(); Text("正在加载更多…").font(AppFont.subheadline).foregroundStyle(.secondary) }
                    .frame(maxWidth: .infinity)
                    .task(id: loaded) { if !loading { await loadMore() } }
                    .accessibilityElement(children: .combine)
            } else if loaded > 0 && !hasMore {
                Text(total.map { "共 \($0) 单" } ?? "已显示全部 \(loaded) 条")
                    .font(AppFont.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
            }
        }
        .listRowBackground(Color.clear).listRowSeparator(.hidden)
    }
}

/// Pull-down style loading: a plain system spinner, never a full-screen cover.
/// Lists show `LoadingRow` as a row at the top; forms and other pages use this small
/// top-centred spinner. Neither blocks the page or hides the navigation bars.
struct AnimatedLoadingOverlay: View {
    let isPresented: Bool
    let title: String
    var body: some View {
        VStack(spacing: 0) {
            if isPresented {
                ProgressView().padding(.top, 14)
                    .accessibilityLabel(title)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .allowsHitTesting(false)
    }
}

/// First row of a list while it loads. Hidden when the system pull-to-refresh spinner is
/// already showing (`isPresented` is false then), so there are never two spinners.
struct LoadingRow: View {
    let isPresented: Bool
    var title = "正在加载"
    @Environment(\.pulledDown) private var pulledDown
    var body: some View {
        // A full-width container fixes the centre before fading in; no positional/move transition.
        // Removing the branch also removes its padding instead of leaving an invisible loading row.
        Group {
            if isPresented {
                HStack {
                    Spacer(minLength: 0)
                    ProgressView().accessibilityLabel(title)
                    Spacer(minLength: 0)
                }
                .frame(maxWidth: .infinity)
                .opacity(pulledDown ? 0 : 1)
                .padding(.vertical, 10)
                .clipped()
                .listRowBackground(Color.clear).listRowSeparator(.hidden)
            }
        }
    }
}

/// A native picker keeps all categories reachable at large text sizes.
struct ChipStrip: View {
    struct Item: Identifiable { let id: Int; let title: String; var count: Int? = nil; var color: Color = .accentColor; var showsCount = false }
    let items: [Item]
    @Binding var selection: Int
    var body: some View {
        Picker("分类", selection: $selection) {
            ForEach(items) { item in
                Text(item.title + (item.showsCount ? "（" + (item.count.map(String.init) ?? "…") + "）" : ""))
                    .tag(item.id)
            }
        }
        .pickerStyle(.menu)
        .accessibilityIdentifier("category-picker")
        .accessibilityValue(items.first { $0.id == selection }.map { item in
            item.title + (item.showsCount ? "，" + (item.count.map { "\($0) 单" } ?? "数量尚未获取") : "")
        } ?? "未选择")
    }
}

struct WorkOrderStatusStrip: View {
    @Binding var selection: Int
    var counts: [String: Int] = [:]
    var body: some View {
        // The server returns no total for orders being reported or already finished, so those two show none.
        ChipStrip(items: FaultState.allCases.enumerated().map { index, state in
            .init(id: index, title: state.rawValue, count: counts[state.code], color: state.color, showsCount: !["UNTREATED", "FINISHED"].contains(state.code))
        }, selection: $selection)
    }
}

struct CompactEmptyState: View {
    var title = "暂无相关报修"
    var body: some View {
        ContentUnavailableView(title, systemImage: "tray", description: Text("可切换状态或调整搜索条件"))
    }
}

struct HorizontalCategories: View {
    let titles: [String]
    @Binding var selection: Int
    var body: some View {
        ChipStrip(items: titles.indices.map { .init(id: $0, title: titles[$0]) }, selection: $selection)
    }
}

enum FaultSearchField: String, CaseIterable, Identifiable {
    case name = "报修名称", number = "任务号", machine = "机台号", assetCode = "资产编码", assetName = "资产名称", address = "地址", reporter = "报修人", group = "维修班组", repairer = "执行人"
    var id: String { rawValue }
    var key: String {
        switch self {
        case .name: "faultName"; case .number: "sn"; case .machine: "asset.addressCode"; case .assetCode: "asset.assetCode"; case .assetName: "asset.assetName"; case .address: "asset.address"; case .reporter: "applyUser.realName"; case .group: "userGroup.groupName"; case .repairer: "repairUser.realName"
        }
    }
}
