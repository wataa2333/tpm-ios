import SwiftUI

// Order cards live in a ScrollView, not in a List. A List sizes its rows in UIKit, on its own schedule, so
// a card that grows by animation makes the cell and its content move out of step. In a ScrollView the
// layout is SwiftUI's own: the card above stays put, the new lines open downwards and every card behind
// is pushed along in the same animation, and the other way round when it closes.

/// A page of cards: a scroll view with the usual grouped background, spaced like an inset list.
struct CardPage<Content: View>: View {
    @ViewBuilder let content: () -> Content
    @State private var pulled = false
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 16) { content() }
                .padding(.top, 8).padding(.bottom, 24)
                .environment(\.pulledDown, pulled)
        }
        .reportsPulledDown($pulled)
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
    }
}

/// Rows in one rounded white card with a divider between them, like a list section. The rows are
/// created lazily as they scroll into view.
struct CardGroup<Content: View>: View {
    @ViewBuilder let content: () -> Content
    var body: some View {
        LazyVStack(spacing: 0) {
            Group(subviews: content()) { subviews in
                ForEach(subviews.indices, id: \.self) { index in
                    if index > 0 { Divider().padding(.leading, 16) }
                    subviews[index]
                }
            }
        }
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 26, style: .continuous))
        .padding(.horizontal, 16)
    }
}
