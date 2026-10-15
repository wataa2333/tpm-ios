import SwiftUI

struct SearchPageChrome: ViewModifier {
    var isSearching = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .contentMargins(.top, 8, for: .scrollContent)
            .listSectionSpacing(.compact)
            .scrollContentBackground(.hidden)
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(Color(uiColor: .systemGroupedBackground), for: .navigationBar)
            .toolbarBackgroundVisibility(.visible, for: .navigationBar)
            .animation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25), value: isSearching)
    }
}

extension View {
    func searchPageChrome(isSearching: Bool = false) -> some View {
        modifier(SearchPageChrome(isSearching: isSearching))
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
    var body: some View {
        ZStack {
            Color.black.opacity(0.22).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView().controlSize(.large)
                Text(title).font(.subheadline)
            }.padding(24).background(.regularMaterial, in: .rect(cornerRadius: 22))
        }.accessibilityAddTraits(.updatesFrequently)
    }
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

struct NativeTabBar: View {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var selection: Int
    var preview = false
    var body: some View {
        BottomNavigation(selection: $selection, preview: preview)
    }
}

struct WorkOrderStatusStrip: View {
    @Binding var selection: Int
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency
    private var animation: Animation { reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.28) }
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(Array(FaultState.allCases.enumerated()), id: \.offset) { index, state in
                        Button {
                            withAnimation(animation) { selection = index }
                        } label: {
                            HStack(spacing: 7) {
                                Image(systemName: state.symbol).font(.system(size: 19, weight: .semibold))
                                    .frame(width: 24)
                                if selection == index {
                                    Text(state.rawValue).font(.subheadline.weight(.semibold))
                                        .lineLimit(1).transition(.opacity)
                                }
                            }
                            .foregroundStyle(selection == index ? Color(uiColor: .systemBackground) : Color.secondary)
                            .padding(.horizontal, selection == index ? 16 : 10)
                            .frame(minHeight: 44)
                            .background(selection == index ? Color.primary : Color(uiColor: .tertiarySystemFill), in: .capsule)
                            .contentShape(.capsule)
                        }
                        .buttonStyle(.plain).id(index)
                        .accessibilityLabel(state.rawValue)
                        .accessibilityAddTraits(selection == index ? [.isSelected] : [])
                    }
                }.padding(.horizontal, 16).padding(.vertical, 8)
            }
            .scrollIndicators(.hidden)
            .onAppear { proxy.scrollTo(selection, anchor: .center) }
            .onChange(of: selection) { _, index in withAnimation(animation) { proxy.scrollTo(index, anchor: .center) } }
        }
        .background {
            if reduceTransparency { Color(uiColor: .systemGroupedBackground) }
            else { Rectangle().fill(.regularMaterial) }
        }
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
            }.scrollIndicators(.hidden)
                .onChange(of: selection) { _, index in withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25)) { proxy.scrollTo(index, anchor: .center) } }
                .onAppear { proxy.scrollTo(selection, anchor: .center) }
        }
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background {
            if reduceTransparency {
                Color(uiColor: .systemBackground)
            } else {
                Rectangle().fill(.regularMaterial)
            }
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
