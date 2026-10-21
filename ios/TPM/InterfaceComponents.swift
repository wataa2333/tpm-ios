import SwiftUI

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
            .animation(.easeOut(duration: reduceMotion ? 0.12 : 0.2), value: hidden)
    }
}

extension EnvironmentValues {
    @Entry var previewReferences = false
    @Entry var bottomSafeArea: CGFloat = 0
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
            VStack(spacing: 5) {
                Group { if busy { ProgressView() } else { Image(systemName: symbol).font(.system(size: 28, weight: .medium)) } }
                    .frame(width: 58, height: 48)
                    .foregroundStyle(color)
                Text(title).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
            }.padding(.horizontal, 8).padding(.vertical, 6)
        }
        .buttonStyle(.glass).buttonBorderShape(.roundedRectangle(radius: 24))
        .accessibilityLabel(title)
        .animation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.2), value: busy)
    }
}

/// Scrolls the list or scroll view of the page it is attached to back to its top whenever `trigger`
/// changes (a manual refresh, another filter, a search, a changed tab). It finds the page's real
/// scroll view in UIKit and animates it, which works for List and ScrollView alike. Loading further
/// pages does not change `trigger`, so it never moves the list.
struct ReloadsToTop<Trigger: Equatable>: ViewModifier {
    let trigger: Trigger
    func body(content: Content) -> some View {
        content.background(ScrollToTopAnchor(trigger: trigger).frame(width: 0, height: 0).allowsHitTesting(false))
    }
}

private struct ScrollToTopAnchor<Trigger: Equatable>: UIViewRepresentable {
    let trigger: Trigger
    func makeCoordinator() -> Coordinator { Coordinator() }
    func makeUIView(context: Context) -> UIView { UIView() }
    func updateUIView(_ view: UIView, context: Context) {
        defer { context.coordinator.last = trigger }
        guard let last = context.coordinator.last, last != trigger else { return }
        // After the update has been laid out, so the scroll view exists and has its final insets.
        DispatchQueue.main.async { [weak view] in
            guard let view, let scroll = Self.scrollView(near: view) else { return }
            scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: -scroll.adjustedContentInset.top), animated: !UIAccessibility.isReduceMotionEnabled)
        }
    }
    final class Coordinator { var last: Trigger? }

    /// The scroll view that belongs to the same page: searched upward from the anchor, level by level.
    private static func scrollView(near view: UIView) -> UIScrollView? {
        var ancestor: UIView? = view.superview
        var depth = 0
        while let current = ancestor, depth < 8 {
            if let found = firstScrollView(in: current, excluding: view) { return found }
            ancestor = current.superview; depth += 1
        }
        return nil
    }
    private static func firstScrollView(in root: UIView, excluding: UIView) -> UIScrollView? {
        var queue: [UIView] = [root]
        while !queue.isEmpty {
            let current = queue.removeFirst()
            if let scroll = current as? UIScrollView, scroll.isScrollEnabled, scroll.window != nil, scroll.bounds.height > 100,
               scroll.contentSize.width <= scroll.bounds.width + 1 || scroll is UICollectionView { return scroll }
            queue.append(contentsOf: current.subviews.filter { $0 !== excluding })
        }
        return nil
    }
}

extension View {
    func reloadsToTop<Trigger: Equatable>(on trigger: Trigger) -> some View {
        modifier(ReloadsToTop(trigger: trigger))
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
                HStack(spacing: 10) { ProgressView(); Text("正在加载更多…").font(.subheadline).foregroundStyle(.secondary) }
                    .frame(maxWidth: .infinity)
                    .task(id: loaded) { if !loading { await loadMore() } }
                    .accessibilityElement(children: .combine)
                    .transition(.opacity)
            } else if loaded > 0 && !hasMore {
                Text(total.map { "共 \($0) 单" } ?? "已显示全部 \(loaded) 条")
                    .font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity)
                    .contentTransition(.numericText()).transition(.opacity)
            }
        }
        .listRowBackground(Color.clear).listRowSeparator(.hidden)
        .interfaceMotion(hasMore)
        .interfaceMotion(failed)
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
                    .transition(.opacity)
            }
            Spacer(minLength: 0)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .allowsHitTesting(false)
        .interfaceMotion(isPresented)
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
                .transition(.opacity)
            }
        }
        .interfaceMotion(isPresented)
    }
}

/// One horizontal row of choices, used for order states, todo categories and asset tabs.
/// It is a plain fixed-height row placed as the first row of a list, so it scrolls with the
/// content and can never overlap the large title, the search field or the refresh control.
struct ChipStrip: View {
    struct Item: Identifiable { let id: Int; let title: String; var count: Int? = nil; var color: Color = .accentColor; var showsCount = false }
    let items: [Item]
    @Binding var selection: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 22) {
                    ForEach(items) { item in
                        Button { selection = item.id } label: {
                            VStack(spacing: 6) {
                                HStack(spacing: 5) {
                                    Text(item.title).font(.subheadline.weight(selection == item.id ? .semibold : .regular)).lineLimit(1)
                                    if item.showsCount {
                                        Text(item.count.map(String.init) ?? "…").font(.caption.weight(.semibold)).monospacedDigit()
                                            .contentTransition(.numericText()).interfaceMotion(item.count, duration: 0.5)
                                    }
                                }
                                .foregroundStyle(selection == item.id ? Color.primary : Color.secondary)
                                Rectangle().fill(selection == item.id ? item.color : .clear).frame(height: 3)
                            }.fixedSize().frame(minHeight: 44).contentShape(.rect)
                        }
                        .buttonStyle(.plain).id(item.id)
                        .accessibilityLabel(item.title)
                        .accessibilityValue(item.showsCount ? (item.count.map { "\($0) 单" } ?? "数量尚未获取") : "")
                        .accessibilityAddTraits(selection == item.id ? [.isSelected] : [])
                    }
                }.padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .onAppear { proxy.scrollTo(selection, anchor: .center) }
            .onChange(of: selection) { _, index in withAnimation(reduceMotion ? nil : .smooth(duration: 0.4)) { proxy.scrollTo(index, anchor: .center) } }
        }
        .frame(height: 48)
        .sensoryFeedback(.selection, trigger: selection)
        .listRowInsets(EdgeInsets()).listRowBackground(Color.clear).listRowSeparator(.hidden)
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
        VStack(spacing: 10) {
            Image(systemName: "tray").font(.system(size: 28)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text("可切换状态或调整搜索条件").font(.subheadline).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity).padding(.vertical, 28)
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
