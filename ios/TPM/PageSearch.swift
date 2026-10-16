import SwiftUI

/// One system search field for every searchable page: the standard navigation-bar
/// drawer, so its position and look are identical everywhere and the navigation bar
/// keeps the system material.
private struct PageSearch: ViewModifier {
    @Binding var text: String
    var presented: Binding<Bool>?
    let prompt: String
    let submit: () -> Void
    @State private var localActive = false
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        let active = presented ?? $localActive
        content
            .searchable(text: $text, isPresented: active, placement: .navigationBarDrawer(displayMode: .always), prompt: prompt)
            .onSubmit(of: .search) { submit() }
            // Cancelling clears the text; reload so lists that only refresh on submit follow.
            .onChange(of: active.wrappedValue) { _, isActive in if !isActive { submit() } }
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
