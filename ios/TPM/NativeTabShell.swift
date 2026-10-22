import SwiftUI

/// Keep the system tab bar responsible for glass rendering, hit testing, selection tracking and
/// device safe areas. It has the four real tabs only: "new" is not a tab.
struct NativeTabShell<Home: View, Todo: View, Workspace: View, Mine: View>: View {
    @Binding var selection: Int
    var preview = false
    /// The label of the last tab: the account name once signed in.
    var mineTitle = "我的"
    @ViewBuilder let home: () -> Home
    @ViewBuilder let todo: () -> Todo
    @ViewBuilder let workspace: () -> Workspace
    @ViewBuilder let mine: () -> Mine
    @EnvironmentObject private var navigation: BottomNavigationModel

    var body: some View {
        TabView(selection: $selection) {
            Tab("主页", systemImage: "house", value: 0) { home().newDocumentButton(preview: preview) }
            Tab("我的待办", systemImage: "checklist", value: 2) { todo().newDocumentButton(preview: preview) }
                .badge(navigation.todoCount)
            Tab("看板", systemImage: "square.grid.2x2", value: 1) { workspace().newDocumentButton(preview: preview) }
            Tab(mineTitle, systemImage: "person.crop.circle", value: 3) { mine().newDocumentButton(preview: preview) }
        }
        .onChange(of: selection) {
            navigation.searchReset = UUID()
            navigation.hideTabs = false
        }
    }
}

/// The "new" button, built the way the system Reminders app builds its "+": one round, blue,
/// prominent glass button floating at the bottom-right corner, apart from everything else. It sits
/// in the bottom safe area, so it stays above the tab bar, and it goes away with the tab bar on
/// detail pages. The form opens as the normal sheet, so dragging it down shows the page you came from.
struct NewDocumentButton: ViewModifier {
    var preview = false
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var creating: WorkflowKind?
    private var kind: WorkflowKind { navigation.newDocument ?? .fault }

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, alignment: .trailing, spacing: 0) {
                Group {
                    if !navigation.hideTabs && !navigation.selectingBatch {
                        Button {
                            navigation.searchReset = UUID()
                            navigation.presentingDocument = true
                            creating = kind
                        } label: {
                            Image(systemName: "plus").font(.title2.weight(.semibold)).frame(width: 44, height: 44)
                        }
                        .buttonStyle(.glassProminent).buttonBorderShape(.circle)
                        .accessibilityLabel(kind == .erp ? "新建领料单" : "新建报修")
                        .padding(.trailing, 20).padding(.bottom, 8)
                        .transition(.opacity)
                    }
                }
                .interfaceMotion(navigation.hideTabs || navigation.selectingBatch)
            }
            .sheet(item: $creating, onDismiss: {
                navigation.presentingDocument = false
                navigation.refresh = UUID()
            }) { kind in
                NavigationStack {
                    if kind == .fault { OriginalFaultForm(preview: preview) }
                    else { OriginalERPForm(preview: preview) }
                }.presentationDragIndicator(.visible)
            }
    }
}

extension View {
    func newDocumentButton(preview: Bool = false) -> some View {
        modifier(NewDocumentButton(preview: preview))
    }
}
