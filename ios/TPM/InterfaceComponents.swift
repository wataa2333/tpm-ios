import SwiftUI

struct SearchPageChrome: ViewModifier {
    var isSearching = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        content
            .contentMargins(.top, 8, for: .scrollContent)
            .contentMargins(.bottom, 16, for: .scrollContent)
            .listSectionSpacing(.compact)
            .scrollContentBackground(.hidden)
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
    }
}

extension View {
    func searchPageChrome(isSearching: Bool = false) -> some View {
        modifier(SearchPageChrome(isSearching: isSearching))
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

struct KeyboardTabVisibility: ViewModifier {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillShowNotification)) { notification in
                let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
                withAnimation(.easeInOut(duration: reduceMotion ? 0.12 : duration)) { navigation.keyboardVisible = true }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { notification in
                let duration = notification.userInfo?[UIResponder.keyboardAnimationDurationUserInfoKey] as? Double ?? 0.25
                withAnimation(.easeInOut(duration: reduceMotion ? 0.12 : duration)) { navigation.keyboardVisible = false }
            }
    }
}

extension EnvironmentValues {
    @Entry var previewReferences = false
    @Entry var loadingViewport = CGRect.zero
    @Entry var bottomSafeArea: CGFloat = 0
    @Entry var documentSearch = false
    @Entry var closeWorkOrder: (() -> Void)? = nil
}

struct BottomAction: View {
    let title: String
    let symbol: String
    var color: Color = .blue
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

struct LoadingOverlay: View {
    let title: String
    @Environment(\.loadingViewport) private var viewport
    var body: some View {
        GeometryReader { geometry in
        ZStack(alignment: .topLeading) {
            Color.black.opacity(0.22).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView().controlSize(.large)
                Text(title).font(.subheadline)
            }.padding(24).background(.regularMaterial, in: RoundedRectangle(cornerRadius: 24, style: .continuous))
                .position(loadingIndicatorCenter(container: geometry.frame(in: .global), viewport: viewport))
        }.accessibilityAddTraits(.updatesFrequently)
        }
    }
}

func loadingIndicatorCenter(container: CGRect, viewport: CGRect) -> CGPoint {
    let center = viewport.isEmpty ? container.midY : viewport.midY
    let localY = center - container.minY
    return CGPoint(x: container.width / 2, y: localY > 0 && localY < container.height ? localY : container.height / 2)
}

struct AnimatedLoadingOverlay: View {
    let isPresented: Bool
    let title: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        ZStack {
            if isPresented { LoadingOverlay(title: title).transition(.opacity) }
        }
        .allowsHitTesting(isPresented)
        .animation(.easeInOut(duration: reduceMotion ? 0.12 : 0.2), value: isPresented)
    }
}

struct WorkOrderStatusStrip: View {
    @Binding var selection: Int
    var counts: [String: Int] = [:]
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    private var animation: Animation { reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.28) }
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 20) {
                    ForEach(Array(FaultState.allCases.enumerated()), id: \.offset) { index, state in
                        Button {
                            selection = index
                        } label: {
                            VStack(spacing: 6) {
                            HStack(spacing: 5) {
                                Text(state.rawValue).font(.subheadline.weight(.semibold)).lineLimit(1)
                                Text(counts[state.code].map(String.init) ?? "…")
                                    .font(.caption.weight(.semibold)).monospacedDigit()
                            }
                            .foregroundStyle(selection == index ? state.color : Color.primary)
                            Rectangle().fill(selection == index ? state.color : .clear).frame(height: 3)
                            }.fixedSize(horizontal: true, vertical: false).frame(minHeight: 44)
                            .contentShape(.rect)
                        }
                        .buttonStyle(.plain).id(index)
                        .accessibilityLabel(state.rawValue)
                        .accessibilityValue(counts[state.code].map { "\($0) 单" } ?? "数量尚未获取")
                        .accessibilityAddTraits(selection == index ? [.isSelected] : [])
                    }
                }.padding(.horizontal, 16).padding(.vertical, 4)
            }
            .scrollIndicators(.hidden)
            .contentMargins(.all, 0, for: .scrollContent)
            .onAppear { proxy.scrollTo(selection, anchor: .center) }
            .onChange(of: selection) { _, index in withAnimation(animation) { proxy.scrollTo(index, anchor: .center) } }
        }
        .frame(height: 52).fixedSize(horizontal: false, vertical: true).layoutPriority(1)
        .background(Color(uiColor: .systemGroupedBackground))
        .overlay(alignment: .bottom) { Divider() }
        .sensoryFeedback(.selection, trigger: selection)
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
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selectionAnimation
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 24) {
                    ForEach(titles.indices, id: \.self) { index in
                        Button { withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25)) { selection = index } } label: {
                            VStack(spacing: 10) {
                                Text(titles[index]).font(.body.weight(selection == index ? .semibold : .regular))
                                    .foregroundStyle(selection == index ? Color.accentColor : Color.secondary)
                                ZStack {
                                    Color.clear
                                    if selection == index { Capsule().fill(Color.accentColor).matchedGeometryEffect(id: "underline", in: selectionAnimation) }
                                }.frame(height: 3)
                            }.fixedSize(horizontal: true, vertical: false).padding(.top, 12)
                        }.buttonStyle(.plain).id(index)
                            .accessibilityAddTraits(selection == index ? [.isSelected] : [])
                    }
                }.padding(.horizontal, 20)
            }.scrollIndicators(.hidden).contentMargins(.all, 0, for: .scrollContent)
                .onChange(of: selection) { _, index in withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25)) { proxy.scrollTo(index, anchor: .center) } }
                .onAppear { proxy.scrollTo(selection, anchor: .center) }
        }
        .frame(height: 54)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background {
            Color(uiColor: .systemGroupedBackground)
        }
        .overlay(alignment: .bottom) { Divider() }
        .clipped()
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
