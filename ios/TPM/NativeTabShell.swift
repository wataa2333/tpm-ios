import SwiftUI

/// Keep the system tab bar responsible for glass rendering, hit testing,
/// selection tracking and device safe areas.
struct NativeTabShell<Content: View>: View {
    @Binding var selection: Int
    var preview = false
    @ViewBuilder let content: () -> Content
    @EnvironmentObject private var navigation: BottomNavigationModel

    var body: some View {
        TabView(selection: $selection) { content() }
            .onChange(of: selection) {
                navigation.searchReset = UUID()
                navigation.hideTabs = false
            }
    }
}

/// A round glass "new" button at the bottom-trailing corner, like the compose button in
/// the system Notes app. It lives in the bottom safe area of each tab's navigation stack,
/// so it sits above the system tab bar, and it disappears together with the tab bar.
struct NewDocumentButton: ViewModifier {
    var preview = false
    @EnvironmentObject private var navigation: BottomNavigationModel
    @State private var creating: WorkflowKind?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var visible: Bool { !navigation.hideTabs && !navigation.keyboardVisible }
    private var kind: WorkflowKind { navigation.newDocument ?? .fault }

    func body(content: Content) -> some View {
        content
            .safeAreaInset(edge: .bottom, alignment: .trailing, spacing: 0) {
                Group {
                    if visible {
                        Button {
                            navigation.searchReset = UUID()
                            navigation.presentingDocument = true
                            creating = kind
                        } label: {
                            Image(systemName: "square.and.pencil")
                                .font(.title2.weight(.semibold))
                                .frame(width: 52, height: 52)
                        }
                        .buttonStyle(.glass).buttonBorderShape(.circle)
                        .accessibilityLabel(kind == .erp ? "新建领料单" : "新建报修")
                        .padding(.trailing, 20).padding(.bottom, 8)
                        .transition(.opacity)
                    }
                }
                .animation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.25), value: visible)
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

extension View {
    func newDocumentButton(preview: Bool = false) -> some View {
        modifier(NewDocumentButton(preview: preview))
    }
}
