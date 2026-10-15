import SwiftUI

func bottomBarExtension(safeAreaInset: CGFloat) -> CGFloat {
    min(12, max(0, safeAreaInset - 20))
}

@MainActor
final class BottomNavigationModel: ObservableObject {
    @Published var newDocument: WorkflowKind?
    @Published var hideTabs = false
    @Published var keyboardVisible = false
    @Published var refresh = UUID()
    @Published var searchReset = UUID()
}

struct BottomNavigation: View {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @Binding var selection: Int
    var preview = false
    @State private var creating: WorkflowKind?
    @Namespace private var tabSelection
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.bottomSafeArea) private var bottomSafeArea
    private let tabs = [("主页", "house"), ("我的待办", "checklist"), ("工作台", "square.grid.2x2"), ("我的", "person.crop.circle")]
    var body: some View {
        Group {
        if !navigation.hideTabs && !navigation.keyboardVisible {
        GlassEffectContainer(spacing: 12) {
            HStack(spacing: 12) {
                HStack(spacing: 0) {
                    ForEach(tabs.indices, id: \.self) { position in
                        let index = [0, 2, 1, 3][position]
                        Button {
                            navigation.searchReset = UUID()
                            withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .spring(response: 0.38, dampingFraction: 0.72)) { selection = index }
                        } label: {
                            VStack(spacing: 3) {
                                Image(systemName: tabs[position].1).font(.system(size: 20))
                                    .scaleEffect(selection == index && !reduceMotion ? 1.06 : 1)
                                Text(tabs[position].0).font(.system(size: 11, weight: .medium)).lineLimit(1)
                            }
                            .foregroundStyle(selection == index ? Color.accentColor : Color.primary)
                            .frame(maxWidth: .infinity, minHeight: 50)
                            .background {
                                if selection == index {
                                    Capsule().fill(Color.primary.opacity(0.07))
                                        .matchedGeometryEffect(id: "selection", in: tabSelection)
                                }
                            }
                        }
                        .buttonStyle(.plain).accessibilityLabel(tabs[position].0)
                        .accessibilityAddTraits(selection == index ? [.isSelected] : [])
                    }
                }.padding(4).glassEffect(.regular.interactive(), in: .capsule)
                let kind = navigation.newDocument ?? .fault
                    Button {
                        navigation.searchReset = UUID()
                        Task { @MainActor in await Task.yield(); creating = kind }
                    } label: {
                        Image(systemName: "plus").font(.system(size: 22, weight: .regular))
                            .foregroundStyle(.primary).frame(width: 30, height: 30)
                    }
                    .buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                    .accessibilityLabel(kind == .fault ? "新建报修" : "新建领料单")
                    .transition(.opacity)
            }
        }
        // The hosting safeAreaInset already reserves the home indicator area.
        // Extend into that area only when the device has enough inset; never
        // shift below the screen on a phone/orientation without a home inset.
        .padding(.horizontal, 16).padding(.top, 4)
        .padding(.bottom, -bottomBarExtension(safeAreaInset: bottomSafeArea))
        .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
        }
        }
        .sensoryFeedback(.selection, trigger: selection)
        .sensoryFeedback(.impact(weight: .light), trigger: creating?.id)
        .animation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25), value: navigation.hideTabs)
        .sheet(item: $creating, onDismiss: { navigation.refresh = UUID() }) { kind in
            NavigationStack {
                if kind == .fault { OriginalFaultForm(preview: preview) }
                else { OriginalERPForm(preview: preview) }
            }
            .presentationDragIndicator(.visible)
        }
    }
}
