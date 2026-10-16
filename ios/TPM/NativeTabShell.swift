import SwiftUI

/// Keep the system tab bar responsible for glass rendering, hit testing, selection tracking and
/// device safe areas.
///
/// The "new" action is the system's detached circular tab (`role: .search`): the standalone round
/// icon the system draws at the trailing end of the tab bar, on the same row as the tabs. It is not
/// a page. Choosing it restores the previous tab at once and opens the new-document sheet, so it
/// hides and shows together with the tab bar.
struct NativeTabShell<Home: View, Todo: View, Workspace: View, Mine: View>: View {
    @Binding var selection: Int
    var preview = false
    @ViewBuilder let home: () -> Home
    @ViewBuilder let todo: () -> Todo
    @ViewBuilder let workspace: () -> Workspace
    @ViewBuilder let mine: () -> Mine
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var creating: WorkflowKind?
    private static var newTab: Int { 4 }
    private var newTitle: String { navigation.newDocument == .erp ? "新建领料单" : "新建报修" }

    var body: some View {
        TabView(selection: $selection) {
            Tab("主页", systemImage: "house", value: 0) { home() }
            Tab("我的待办", systemImage: "checklist", value: 2) { todo() }
            Tab("工作台", systemImage: "square.grid.2x2", value: 1) { workspace() }
            Tab("我的", systemImage: "person.crop.circle", value: 3) { mine() }
            Tab(newTitle, systemImage: "square.and.pencil", value: Self.newTab, role: .search) { Color.clear }
        }
        .onChange(of: selection) { old, new in
            if new == Self.newTab {
                selection = old
                navigation.searchReset = UUID()
                navigation.presentingDocument = true
                creating = navigation.newDocument ?? .fault
            } else {
                navigation.searchReset = UUID()
                navigation.hideTabs = false
            }
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
