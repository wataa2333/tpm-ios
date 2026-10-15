import SwiftUI
import UIKit

/// A page-owned UIKit search field, without a navigation search controller that
/// can migrate into the next page's toolbar during dismissal.
@MainActor
private struct NativeSearchInput: UIViewRepresentable {
    @Binding var text: String
    @Binding var active: Bool
    let prompt: String
    let submit: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(self) }
    func makeUIView(context: Context) -> UISearchBar {
        let bar = UISearchBar()
        bar.searchBarStyle = .minimal
        bar.backgroundColor = .clear
        bar.searchTextField.backgroundColor = .clear
        bar.searchTextField.returnKeyType = .search
        bar.searchTextField.accessibilityIdentifier = "page-search-field"
        bar.delegate = context.coordinator
        return bar
    }
    func updateUIView(_ bar: UISearchBar, context: Context) {
        context.coordinator.parent = self
        if bar.text != text { bar.text = text }
        bar.placeholder = prompt
        if !active && bar.searchTextField.isFirstResponder { bar.endEditing(true) }
    }
    static func dismantleUIView(_ bar: UISearchBar, coordinator: Coordinator) {
        bar.delegate = nil
        bar.endEditing(true)
    }
    @MainActor final class Coordinator: NSObject, UISearchBarDelegate {
        var parent: NativeSearchInput
        init(_ parent: NativeSearchInput) { self.parent = parent }
        func searchBarTextDidBeginEditing(_ searchBar: UISearchBar) { parent.active = true }
        func searchBar(_ searchBar: UISearchBar, textDidChange searchText: String) { parent.text = searchText }
        func searchBarSearchButtonClicked(_ searchBar: UISearchBar) {
            parent.submit()
            searchBar.endEditing(true)
        }
    }
}

private struct PageSearch: ViewModifier {
    @Binding var text: String
    var presented: Binding<Bool>?
    let prompt: String
    let submit: () -> Void
    @State private var localActive = false
    @EnvironmentObject private var navigation: BottomNavigationModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        let active = presented ?? $localActive
        content.safeAreaInset(edge: .top, spacing: 0) {
            if !navigation.presentingDocument {
            HStack(spacing: 10) {
                NativeSearchInput(text: $text, active: active, prompt: prompt, submit: submit)
                    .frame(height: 44)
                if active.wrappedValue {
                    Button {
                        active.wrappedValue = false
                        text = ""
                        submit()
                    } label: { Image(systemName: "xmark").frame(width: 24, height: 24) }
                        .buttonStyle(.glass).buttonBorderShape(.circle)
                        .accessibilityLabel("取消搜索")
                        .transition(.opacity)
                }
            }.padding(.horizontal, 16).padding(.vertical, 8)
                .background(Color(uiColor: .systemGroupedBackground))
                .animation(reduceMotion ? .easeOut(duration: 0.12) : .snappy(duration: 0.24), value: active.wrappedValue)
            }
        }
        .onDisappear { active.wrappedValue = false }
        .onChange(of: navigation.searchReset) { active.wrappedValue = false }
    }
}

extension View {
    func nativeSearchable(text: Binding<String>, isPresented: Binding<Bool>? = nil, prompt: String, onSubmit: @escaping () -> Void = {}) -> some View {
        modifier(PageSearch(text: text, presented: isPresented, prompt: prompt, submit: onSubmit))
    }
    func nativeNavigationChrome() -> some View {
        toolbarBackgroundVisibility(.automatic, for: .navigationBar)
    }
}
