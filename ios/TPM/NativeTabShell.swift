import SwiftUI

/// Keep the system tab bar responsible for glass rendering, hit testing,
/// selection tracking and device safe areas.
struct NativeTabShell<Content: View>: View {
    @Binding var selection: Int
    var preview = false
    @ViewBuilder let content: () -> Content
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var creating: WorkflowKind?

    var body: some View {
        TabView(selection: $selection) { content() }
            .toolbar(navigation.hideTabs || navigation.keyboardVisible ? .hidden : .visible, for: .tabBar)
            .tabViewBottomAccessory(isEnabled: !navigation.hideTabs && !navigation.keyboardVisible) {
                HStack {
                    Spacer()
                    Button {
                        navigation.searchReset = UUID()
                        navigation.presentingDocument = true
                        creating = navigation.newDocument ?? .fault
                    } label: {
                        Label(navigation.newDocument == .erp ? "新建领料单" : "新建报修", systemImage: "plus")
                            .font(.body.weight(.semibold)).padding(.vertical, 8)
                    }
                    .padding(.trailing, 16)
                }
            }
            .onChange(of: selection) {
                navigation.searchReset = UUID()
                navigation.hideTabs = false
            }
            .sheet(item: $creating, onDismiss: {
                navigation.presentingDocument = false
                navigation.refresh = UUID()
            }) { kind in
                NavigationStack {
                    if kind == .fault { OriginalFaultForm(preview: preview) }
                    else { OriginalERPForm(preview: preview) }
                }.environment(\.documentSearch, true)
                    .presentationDragIndicator(.visible)
            }
    }
}
