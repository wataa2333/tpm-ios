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
    /// The machine number, shown after the reference on the same line: "EWPT-01-017-06H4 · B-F08".
    var machine = ""
    let time: String
    var summaryFacts: [(String, String)] = []
    /// Lines in the warning colour under the summary: why the order came back ("退单原因：…").
    var statusNote: [(String, String)] = []
    let facts: [(String, String)]
    /// Set while the list is in selection mode: a circle selects the card, the card itself opens a peek.
    var selecting: CardSelecting? = nil
    @ViewBuilder let destination: () -> Destination
    @State private var expanded = false
    @State private var opening = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Only the status word takes the state's colour; titles, links and buttons use the theme colour.
    private var emphasis: Color { FaultState.allCases.first { $0.rawValue == status }?.color ?? color }
    private var theme: Color { .accentColor }
    /// In selection mode the card never navigates: tapping it shows the order in a sheet instead.
    private func open() { if let selecting { selecting.peek() } else { opening = true } }
    var body: some View {
        // One uniform 8pt rhythm for every line, with no system disclosure padding. Expanding grows the
        // row downward with a short smooth animation while the extra lines fade in; collapsing reverses it.
        HStack(alignment: .top, spacing: 0) {
            if let selecting {
                Button(action: selecting.toggle) {
                    Image(systemName: selecting.selected ? "checkmark.circle.fill" : "circle")
                        .font(.title2).foregroundStyle(selecting.selected ? Color.accentColor : Color.secondary)
                        .frame(width: 44, height: 44).contentShape(.rect)
                }
                .buttonStyle(.borderless)
                .opacity(selecting.enabled ? 1 : 0.35)
                .accessibilityLabel(selecting.selected ? "已选择" : "未选择")
                .accessibilityIdentifier("order-select-" + reference)
                .transition(.opacity)
            }
            cardBody
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: selecting != nil)
        .listRowInsets(EdgeInsets())
        .navigationDestination(isPresented: $opening) {
            destination().environment(\.closeWorkOrder, { opening = false })
                .modifier(DetailTabVisibility())
        }
    }
    private var cardBody: some View {
        VStack(alignment: .leading, spacing: RowMetrics.spacing) {
            Button { open() } label: {
                HStack(alignment: .top, spacing: 12) {
                    WrappingContent { Text(title).font(.headline).foregroundStyle(theme) }
                    if !status.isEmpty { Text(status).font(.subheadline.weight(.semibold)).foregroundStyle(emphasis).frame(maxWidth: 96, alignment: .trailing).fixedSize(horizontal: false, vertical: true) }
                }
            }.buttonStyle(.borderless).navigationLinkIndicatorVisibility(.hidden)
                .accessibilityIdentifier("order-title-" + reference)
            WrappingContent { Text((reference.isEmpty ? "—" : reference) + (machine.isEmpty ? "" : " · " + machine)).font(.subheadline).foregroundStyle(.secondary) }
                .accessibilityLabel(referenceTitle + "：" + reference + (machine.isEmpty ? "" : "，机台号：" + machine))
                .accessibilityIdentifier("order-reference-" + reference)
            ForEach(Array(summaryFacts.enumerated()), id: \.offset) { _, fact in
                WrappingContent { Text(fact.0 + "：" + (fact.1.isEmpty ? "—" : fact.1)).font(.subheadline).foregroundStyle(.secondary) }
            }
            ForEach(Array(statusNote.enumerated()), id: \.offset) { _, fact in
                WrappingContent { Text(fact.0 + "：" + (fact.1.isEmpty ? "—" : fact.1)).font(.subheadline).foregroundStyle(.orange) }
                    .accessibilityIdentifier("order-note-" + reference)
            }
            VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text(time).font(.subheadline).foregroundStyle(.secondary)
                    .accessibilityIdentifier("order-time-" + reference)
                Spacer(minLength: 0)
                Button { withAnimation(reduceMotion ? nil : .smooth(duration: 0.5)) { expanded.toggle() } } label: {
                    HStack(spacing: 4) {
                        Text(expanded ? "收起详情" : "展开详情").font(.subheadline.weight(.medium))
                        Image(systemName: "chevron.down").font(.caption.weight(.semibold)).rotationEffect(.degrees(expanded ? 180 : 0)).animation(reduceMotion ? nil : .smooth(duration: 0.5), value: expanded)
                    }.frame(minHeight: 24)
                }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).controlSize(.small)
                    .accessibilityLabel(expanded ? "收起详情" : "展开详情")
                    .accessibilityIdentifier("order-expand-" + reference)
            }
            if expanded {
                VStack(spacing: 10) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                        ReadableFact(title: fact.0, value: fact.1)
                    }
                    Button { open() } label: {
                        Label(selecting == nil ? "打开工单" : "查看工单", systemImage: "arrow.up.right.square").font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 32)
                    }.buttonStyle(.borderedProminent).buttonBorderShape(.capsule).controlSize(.regular)
                        .accessibilityIdentifier("order-open-" + reference)
                }
                .padding(.top, RowMetrics.spacing)
                .transition(.opacity)
            }
            }
        }
        .padding(selecting == nil ? RowMetrics.insets : EdgeInsets(top: RowMetrics.insets.top, leading: 0, bottom: RowMetrics.insets.bottom, trailing: RowMetrics.insets.trailing))
        .frame(maxWidth: .infinity, alignment: .leading)
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

/// A detail screen always wants the tab bar hidden, whatever the screens around it do.
struct DetailTabVisibility: ViewModifier {
    @EnvironmentObject private var navigation: BottomNavigationModel
    /// A peek sheet has no tab bar of its own and must not change the one under it.
    @Environment(\.isPeeking) private var peeking
    func body(content: Content) -> some View {
        if peeking { content }
        else {
            content.background { NativeTabVisibility(hidden: true).frame(width: 0, height: 0) }
                .onAppear { navigation.hideTabs = true }
        }
    }
}

/// The arriving screen owns visibility; departures never restore a bar over
/// another detail during a push/pop transition.
struct ListTabVisibility: ViewModifier {
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        content.background { NativeTabVisibility(hidden: false).frame(width: 0, height: 0) }
            .onAppear { navigation.hideTabs = false }
    }
}
