import SwiftUI

/// One rhythm for every list row in the app: 12pt above and below, 16pt at the sides, 8pt between lines.
enum RowMetrics {
    static let spacing: CGFloat = 8
    static let insets = EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16)
}

extension View {
    func uniformRow() -> some View { listRowInsets(RowMetrics.insets) }
}

struct WorkOrderCard<Destination: View>: View {
    let title: String
    var status = ""
    var color: Color = .blue
    let reference: String
    var referenceTitle = "资产编码"
    let time: String
    var summaryFacts: [(String, String)] = []
    let facts: [(String, String)]
    @ViewBuilder let destination: () -> Destination
    @State private var expanded = false
    @State private var opening = false
    /// Only the status word takes the state's colour; titles, links and buttons use the theme colour.
    private var emphasis: Color { FaultState.allCases.first { $0.rawValue == status }?.color ?? color }
    private var theme: Color { .accentColor }
    var body: some View {
        // One uniform 8pt rhythm for every line; no system disclosure padding, no animation:
        // expanding simply extends the row downward.
        VStack(alignment: .leading, spacing: RowMetrics.spacing) {
            Button { opening = true } label: {
                HStack(alignment: .top, spacing: 12) {
                    WrappingContent { Text(title).font(.headline).foregroundStyle(theme) }
                    if !status.isEmpty { Text(status).font(.subheadline.weight(.semibold)).foregroundStyle(emphasis).frame(maxWidth: 96, alignment: .trailing).fixedSize(horizontal: false, vertical: true) }
                }
            }.buttonStyle(.borderless).navigationLinkIndicatorVisibility(.hidden)
                .accessibilityIdentifier("order-title-" + reference)
            WrappingContent { Text(reference.isEmpty ? "—" : reference).font(.subheadline).foregroundStyle(.secondary) }
                .accessibilityLabel(referenceTitle + "：" + reference)
                .accessibilityIdentifier("order-reference-" + reference)
            ForEach(Array(summaryFacts.enumerated()), id: \.offset) { _, fact in
                WrappingContent { Text(fact.0 + "：" + (fact.1.isEmpty ? "—" : fact.1)).font(.subheadline).foregroundStyle(.secondary) }
            }
            HStack(spacing: 12) {
                Text(time).font(.subheadline).foregroundStyle(.secondary)
                    .accessibilityIdentifier("order-time-" + reference)
                Spacer(minLength: 0)
                Button { expanded.toggle() } label: {
                    HStack(spacing: 4) {
                        Text(expanded ? "收起详情" : "展开详情").font(.subheadline.weight(.medium))
                        Image(systemName: expanded ? "chevron.up" : "chevron.down").font(.caption.weight(.semibold))
                    }.foregroundStyle(theme).frame(minHeight: 24).contentShape(.rect)
                }.buttonStyle(.borderless)
                    .accessibilityLabel(expanded ? "收起详情" : "展开详情")
                    .accessibilityIdentifier("order-expand-" + reference)
            }
            if expanded {
                VStack(spacing: 10) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                        ReadableFact(title: fact.0, value: fact.1)
                    }
                }
                .accessibilityElement(children: .contain)
                Button { opening = true } label: {
                    Label("打开工单", systemImage: "arrow.up.right.square")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(theme)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }.buttonStyle(.borderless)
                    .accessibilityIdentifier("order-open-" + reference)
            }
        }
        .uniformRow()
        .navigationDestination(isPresented: $opening) {
            destination().environment(\.closeWorkOrder, { opening = false })
                .modifier(DetailTabVisibility())
        }
    }
}

struct ReadableFact: View {
    let title: String
    let value: String
    @State private var shown = false
    var body: some View {
        // Fixed title column, left-aligned values: every row of every card lines up the same way.
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text(title).foregroundStyle(.secondary).frame(width: 100, alignment: .leading)
            WrappingContent(alignment: .leading) { Text(value.isEmpty ? "—" : value) }
        }.font(.subheadline).contentShape(.rect)
            .accessibilityElement(children: .contain)
            .onTapGesture { shown = true }
            .contextMenu { if !value.isEmpty { Button("复制", systemImage: "doc.on.doc") { copyToPasteboard(value) } } }
            .accessibilityHint("轻点查看完整内容，长按可复制")
            .sheet(isPresented: $shown) { FullTextSheet(title: title, value: value) }
    }
}

func copyToPasteboard(_ text: String) {
    UIPasteboard.general.string = text
}

/// The box that shows one field's full text. It is only as tall as its text (never a half-screen
/// slab), grows to a larger detent only for long text, and always offers a one-tap copy button.
struct FullTextSheet: View {
    let title: String
    let value: String
    @Environment(\.dismiss) private var dismiss
    @State private var contentHeight: CGFloat = 120
    @State private var copied = false
    private var text: String { value.isEmpty ? "—" : value }
    var body: some View {
        NavigationStack {
            ScrollView {
                Text(text).textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading).padding(20)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
            }
            .navigationTitle(title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { CopyButton(text: value) }
            }
        }
        // Navigation bar plus text, within sensible bounds; very long text scrolls or expands.
        .presentationDetents([.height(min(max(contentHeight + 76, 150), 420)), .large])
        .presentationDragIndicator(.visible)
    }
}

/// One-tap copy button: copies the text and says so for a moment. Used wherever a text box appears.
struct CopyButton: View {
    let text: String
    var title = "复制"
    @State private var copied = false
    var body: some View {
        Button {
            copyToPasteboard(text)
            copied = true
            Task { try? await Task.sleep(for: .seconds(1.5)); copied = false }
        } label: {
            Label(copied ? "已复制" : title, systemImage: copied ? "checkmark" : "doc.on.doc")
        }
        .disabled(text.isEmpty)
        .sensoryFeedback(.success, trigger: copied) { _, new in new }
        .accessibilityIdentifier("copy-button")
    }
}

struct SafeFactsStyle: LabeledContentStyle {
    func makeBody(configuration: Configuration) -> some View {
        // The label keeps its natural width on one line ("维修故障类型", "请选择" never break);
        // only long values wrap, in the space that is left.
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            configuration.label.lineLimit(1).fixedSize(horizontal: true, vertical: false).layoutPriority(1)
            configuration.content.lineLimit(nil).fixedSize(horizontal: false, vertical: true)
                .multilineTextAlignment(.trailing).frame(maxWidth: .infinity, alignment: .trailing)
        }
    }
}

func limitedText(_ value: String, limit: Int) -> String {
    var result = "", count = 0
    for character in value {
        let width = String(character).utf16.count
        if count + width > limit { break }
        result.append(character); count += width
    }
    return result
}

struct CharacterCount: View {
    let text: String
    let limit: Int
    var body: some View { Text("\(text.utf16.count)/\(limit) 字符").font(.footnote).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .trailing) }
}

struct DetailTabVisibility: ViewModifier {
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        content.background { NativeTabVisibility(hidden: navigation.hideTabs).frame(width: 0, height: 0) }
            .onAppear { navigation.hideTabs = true }
    }
}

/// The arriving screen owns visibility; departures never restore a bar over
/// another detail during a push/pop transition.
struct ListTabVisibility: ViewModifier {
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        content.background { NativeTabVisibility(hidden: navigation.hideTabs).frame(width: 0, height: 0) }
            .onAppear { navigation.hideTabs = false }
    }
}
