import SwiftUI

struct WrappingContent<Content: View>: View {
    var alignment: Alignment = .leading
    @ViewBuilder let content: () -> Content
    var body: some View {
        content().lineLimit(nil).fixedSize(horizontal: false, vertical: true)
            .multilineTextAlignment(alignment == .trailing ? .trailing : .leading)
            .frame(maxWidth: .infinity, alignment: alignment)
    }
}

struct ExpandingDetails<Content: View>: View {
    let expanded: Bool
    @ViewBuilder let content: () -> Content
    @State private var height: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        content().frame(maxWidth: .infinity, alignment: .topLeading)
            .fixedSize(horizontal: false, vertical: true)
            .transaction { $0.animation = nil }
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height = $0 }
            .frame(height: expanded ? height : 0, alignment: .top)
            .clipped().opacity(expanded ? 1 : 0)
            .allowsHitTesting(expanded).accessibilityHidden(!expanded)
            .animation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.28), value: expanded)
            .animation(reduceMotion ? nil : .smooth(duration: 0.28), value: height)
    }
}
