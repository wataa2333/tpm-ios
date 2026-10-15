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

