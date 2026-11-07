import SwiftUI

struct NativeTabShell<Home: View, Todo: View, Workspace: View, Mine: View>: View {
    @Binding var selection: Int
    var preview = false
    var mineTitle = "我的"
    @ViewBuilder let home: () -> Home
    @ViewBuilder let todo: () -> Todo
    @ViewBuilder let workspace: () -> Workspace
    @ViewBuilder let mine: () -> Mine
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var previousTab = 0
    @State private var reportFormID = UUID()
    private let reportTab = 4

    var body: some View {
        TabView(selection: $selection) {
            Tab("主页", systemImage: "house", value: 0) { NavigationStack { home() } }
            Tab("我的待办", systemImage: "checklist", value: 2) { NavigationStack { todo() } }
                .badge(navigation.todoCount)
            Tab("新建报修", systemImage: "plus.circle", value: reportTab) {
                NavigationStack {
                    OriginalFaultForm(preview: preview, onClose: closeReport, isActive: selection == reportTab)
                        .id(reportFormID)
                        .modifier(ListTabVisibility())
                }
            }
            Tab("看板", systemImage: "square.grid.2x2", value: 1) { NavigationStack { workspace() } }
            Tab(mineTitle, systemImage: "person.crop.circle", value: 3) { NavigationStack { mine() } }
        }
        .onChange(of: selection) { old, new in
            if new == reportTab { previousTab = old }
            navigation.searchReset = UUID()
            navigation.hideTabs = false
        }
    }

    private func closeReport() {
        endEditing()
        reportFormID = UUID()
        navigation.refresh = UUID()
        if selection == reportTab { selection = previousTab }
    }
}
