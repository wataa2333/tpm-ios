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

    /// The tab that was selected before the form was opened; it is selected again when the form closes.
    @State private var returnTab = 0

    var body: some View {
        TabView(selection: $selection) {
            Tab("主页", systemImage: "house", value: 0) { home() }
            Tab("我的待办", systemImage: "checklist", value: 2) { todo() }
            Tab("工作台", systemImage: "square.grid.2x2", value: 1) { workspace() }
            Tab("我的", systemImage: "person.crop.circle", value: 3) { mine() }
            // Choosing it really selects it, so the tab bar's glass bubble moves there once, like for any
            // tab, and stays while the form is open; closing the form moves it back in one animation.
            Tab(newTitle, systemImage: "square.and.pencil", value: Self.newTab, role: .search) {
                Color(uiColor: .systemGroupedBackground).ignoresSafeArea()
            }
        }
        .onChange(of: selection) { old, new in
            navigation.searchReset = UUID()
            if new == Self.newTab {
                returnTab = old == Self.newTab ? returnTab : old
                navigation.presentingDocument = true
                creating = navigation.newDocument ?? .fault
            } else {
                navigation.hideTabs = false
            }
        }
        .sheet(item: $creating, onDismiss: {
            navigation.presentingDocument = false
            navigation.refresh = UUID()
            selection = returnTab
        }) { kind in
            NavigationStack {
                if kind == .fault { OriginalFaultForm(preview: preview) }
                else { OriginalERPForm(preview: preview) }
            }.presentationDragIndicator(.visible)
        }
    }
}
