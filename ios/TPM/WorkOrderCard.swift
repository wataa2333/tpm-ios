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
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            NavigationLink { destination().modifier(DetailTabVisibility()) } label: {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(title).font(.headline).foregroundStyle(.primary).lineLimit(1).truncationMode(.tail).frame(maxWidth: .infinity, alignment: .leading)
                    if !status.isEmpty { Text(status).font(.subheadline.weight(.medium)).foregroundStyle(color).lineLimit(1) }
                }
            }.buttonStyle(.plain).navigationLinkIndicatorVisibility(.hidden)
            Text(reference.isEmpty ? "—" : reference)
                .font(.subheadline).foregroundStyle(.secondary)
                .lineLimit(1).truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityLabel(referenceTitle + "：" + reference)
            ForEach(Array(summaryFacts.enumerated()), id: \.offset) { _, fact in
                Text(fact.0 + "：" + (fact.1.isEmpty ? "—" : fact.1))
                    .font(.subheadline).foregroundStyle(.secondary)
                    .lineLimit(1).truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(alignment: .center, spacing: 16) {
                Text(time).font(.subheadline).foregroundStyle(.secondary)
                    .lineLimit(1).truncationMode(.tail)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button {
                    withAnimation(reduceMotion ? .easeOut(duration: 0.12) : .smooth(duration: 0.24)) { expanded.toggle() }
                } label: {
                    HStack(spacing: 6) {
                        Text(expanded ? "收起详情" : "展开详情")
                        Image(systemName: expanded ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.semibold))
                    }
                    .font(.subheadline.weight(.medium)).foregroundStyle(.blue)
                    .fixedSize(horizontal: true, vertical: false)
                    .frame(minHeight: 44).contentShape(.rect)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("order-expand-" + reference)
                .accessibilityValue(expanded ? "已展开" : "已收起")
            }
            if expanded {
                VStack(spacing: 12) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                        ReadableFact(title: fact.0, value: fact.1)
                    }
                    NavigationLink { destination().modifier(DetailTabVisibility()) } label: { Text("打开工单") }.buttonStyle(.plain).font(.subheadline)
                }.padding(.top, 4)
                    .transition(.opacity)
            }
        }.buttonStyle(.plain).padding(.vertical, 2)
            .listRowInsets(EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16))
    }
}

struct ReadableFact: View {
    let title: String
    let value: String
    @State private var shown = false
    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            Text(title).foregroundStyle(.secondary).lineLimit(1)
            Spacer(minLength: 0)
            Text(value.isEmpty ? "—" : value).lineLimit(1).truncationMode(.tail).multilineTextAlignment(.trailing)
        }.font(.subheadline).contentShape(.rect)
            .onTapGesture { shown = true }
            .accessibilityHint("轻点查看完整内容")
            .sheet(isPresented: $shown) {
                NavigationStack {
                    ScrollView { Text(value.isEmpty ? "—" : value).lineLimit(nil).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading).padding(20) }
                        .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
                        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("完成") { shown = false } } }
                }.presentationDetents([.medium, .large])
            }
    }
}

struct SafeFactsStyle: LabeledContentStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 16) {
            configuration.label.lineLimit(1)
            Spacer(minLength: 0)
            configuration.content.lineLimit(1).truncationMode(.tail).multilineTextAlignment(.trailing)
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
        content.onAppear { navigation.hideTabs = true }
    }
}

/// The arriving screen owns visibility; departures never restore a bar over
/// another detail during a push/pop transition.
struct ListTabVisibility: ViewModifier {
    @EnvironmentObject private var navigation: BottomNavigationModel
    func body(content: Content) -> some View {
        content.onAppear { navigation.hideTabs = false }
    }
}
