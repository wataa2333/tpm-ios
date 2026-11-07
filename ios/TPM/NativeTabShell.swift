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
    @State private var creating: WorkflowKind?
    private let newDocumentTab = 4
    private var kind: WorkflowKind { navigation.newDocument ?? .fault }

    private var tabSelection: Binding<Int> {
        Binding(get: { selection }, set: { tab in
            if tab == newDocumentTab {
                guard creating == nil, !navigation.hideTabs, !navigation.selectingBatch else { return }
                navigation.searchReset = UUID()
                navigation.presentingDocument = true
                creating = kind
            } else {
                selection = tab
            }
        })
    }

    var body: some View {
        TabView(selection: tabSelection) {
            Tab("主页", systemImage: "house", value: 0) { tabStack(0, content: home) }
            Tab("我的待办", systemImage: "checklist", value: 2) { tabStack(2, content: todo) }
                .badge(navigation.todoCount)
            Tab(kind == .erp ? "新建领料" : "新建报修", systemImage: "plus.circle", value: newDocumentTab) {
                Color.clear
            }
            Tab("看板", systemImage: "square.grid.2x2", value: 1) { tabStack(1, content: workspace) }
            Tab(mineTitle, systemImage: "person.crop.circle", value: 3) { tabStack(3, content: mine) }
        }
        .onChange(of: selection) {
            navigation.searchReset = UUID()
            navigation.hideTabs = false
        }
        .sheet(isPresented: presenting(.erp), onDismiss: documentClosed) {
            NavigationStack { OriginalERPForm(preview: preview) }.presentationDragIndicator(.visible)
        }
    }

    private func tabStack<Content: View>(_ tab: Int, @ViewBuilder content: () -> Content) -> some View {
        NavigationStack {
            content()
                .navigationDestination(isPresented: Binding(
                    get: { selection == tab && creating == .fault },
                    set: { presented in
                        if !presented && selection == tab && creating == .fault {
                            creating = nil
                            documentClosed()
                        }
                    }
                )) {
                    OriginalFaultForm(preview: preview)
                        .navigationBarBackButtonHidden()
                        .modifier(DetailTabVisibility())
                }
        }
    }

    private func presenting(_ kind: WorkflowKind) -> Binding<Bool> {
        Binding(get: { creating == kind }, set: { presented in
            if !presented && creating == kind { creating = nil }
        })
    }

    private func documentClosed() {
        navigation.presentingDocument = false
        navigation.refresh = UUID()
    }
}
