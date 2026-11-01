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
            Tab("主页", systemImage: "house", value: 0) { home() }
            Tab("我的待办", systemImage: "checklist", value: 2) { todo() }
                .badge(navigation.todoCount)
            Tab("看板", systemImage: "square.grid.2x2", value: 1) { workspace() }
            Tab(mineTitle, systemImage: "person.crop.circle", value: 3) { mine() }
        }
        .newDocumentButton(preview: preview)
        .onChange(of: selection) {
            navigation.searchReset = UUID()
            navigation.hideTabs = false
        }
    }
}

/// The system tab accessory owns placement. Search and selection use their own system controls.
struct NewDocumentButton: ViewModifier {
    var preview = false
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var creating: WorkflowKind?
    private var kind: WorkflowKind { navigation.newDocument ?? .fault }

    func body(content: Content) -> some View {
        content
            .tabViewBottomAccessory(isEnabled: !navigation.hideTabs && !navigation.selectingBatch && !navigation.searchActive && !navigation.presentingDocument) {
                Button {
                    navigation.searchReset = UUID()
                    navigation.presentingDocument = true
                    creating = kind
                } label: {
                    Label(kind == .erp ? "新建领料单" : "新建报修", systemImage: "plus")
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(kind == .erp ? "新建领料单" : "新建报修")
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
