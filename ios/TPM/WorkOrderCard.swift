import SwiftUI

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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private var emphasis: Color { FaultState.allCases.first { $0.rawValue == status }?.color ?? color }
    /// Expanding animates the row height (and the open button) as one natural stretch.
    private var expansion: Binding<Bool> {
        Binding(get: { expanded }, set: { value in
            withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .snappy(duration: 0.32)) { expanded = value }
        })
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button { opening = true } label: {
                HStack(alignment: .top, spacing: 12) {
                    WrappingContent { Text(title).font(.headline).foregroundStyle(.primary) }
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
            DisclosureGroup(isExpanded: expansion) {
                VStack(spacing: 10) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                        ReadableFact(title: fact.0, value: fact.1)
                    }
                }.padding(.top, 4)
                    .accessibilityElement(children: .contain)
            } label: {
                HStack(spacing: 12) {
                    Text(time).font(.subheadline).foregroundStyle(.secondary)
                        .accessibilityIdentifier("order-time-" + reference)
                    Spacer(minLength: 0)
                    Text(expanded ? "收起详情" : "展开详情")
                        .font(.subheadline.weight(.medium)).foregroundStyle(emphasis)
                }.frame(minHeight: 32)
            }
            .tint(emphasis)
            if expanded {
                Button { opening = true } label: {
                    Label("打开工单", systemImage: "arrow.up.right.square")
                        .font(.subheadline.weight(.semibold)).foregroundStyle(emphasis)
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                }.buttonStyle(.borderless)
                    .accessibilityIdentifier("order-open-" + reference)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }.buttonStyle(.plain)
            .listRowInsets(EdgeInsets(top: 10, leading: 16, bottom: 10, trailing: 16))
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
            .accessibilityHint("轻点查看完整内容")
            .sheet(isPresented: $shown) {
                NavigationStack {
                    ScrollView { Text(value.isEmpty ? "—" : value).lineLimit(nil).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(20) }
                        .navigationTitle(title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { shown = false } } }
                }.presentationDetents([.medium, .large])
            }
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
