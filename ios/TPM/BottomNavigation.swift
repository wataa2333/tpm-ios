import SwiftUI

@MainActor
final class BottomNavigationModel: ObservableObject {
    @Published var newDocument: WorkflowKind?
    @Published var hideTabs = false
    @Published var keyboardVisible = false
    @Published var refresh = UUID()
}

struct BottomNavigation: View {
    @EnvironmentObject private var navigation: BottomNavigationModel
    @Binding var selection: Int
    var preview = false
    @State private var creating: WorkflowKind?
    @Namespace private var tabSelection
    @Namespace private var documentTransition
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let tabs = [("我的待办", "checklist"), ("主页", "house"), ("工作台", "square.grid.2x2"), ("我的", "person.crop.circle")]
    var body: some View {
        Group {
        if !navigation.hideTabs && !navigation.keyboardVisible {
        GlassEffectContainer(spacing: 12) {
            HStack(spacing: 12) {
                HStack(spacing: 0) {
                    ForEach(tabs.indices, id: \.self) { position in
                        let index = [2, 0, 1, 3][position]
                        Button { withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25)) { selection = index } } label: {
                            VStack(spacing: 3) {
                                Image(systemName: tabs[position].1).font(.system(size: 20))
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
                    Button { creating = kind } label: {
                        Image(systemName: "plus").font(.system(size: 22, weight: .regular))
                            .foregroundStyle(.primary).frame(width: 30, height: 30)
                    }
                    .buttonStyle(.glass).buttonBorderShape(.circle).controlSize(.large)
                    .glassEffectID("new-document", in: documentTransition)
                    .matchedTransitionSource(id: "new-document", in: documentTransition)
                    .accessibilityLabel(kind == .fault ? "新建报修" : "新建领料单")
                    .transition(reduceMotion ? .opacity : .scale(scale: 0.85).combined(with: .opacity))
            }
        }
        .padding(.horizontal, 16).padding(.top, 8).padding(.bottom, 8)
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
            .navigationTransition(.zoom(sourceID: "new-document", in: documentTransition))
        }
    }
}
