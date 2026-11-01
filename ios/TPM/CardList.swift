import SwiftUI

/// The system list owns cell reuse, separators, section layout and keyboard avoidance.
struct CardPage<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @State private var pulled = false
    var body: some View {
        List { content() }
            .listStyle(.insetGrouped)
            .reportsPulledDown($pulled)
            .environment(\.pulledDown, pulled)
            .scrollDismissesKeyboard(.interactively)
    }
}

struct CardGroup<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View { Section { content() } }
}
