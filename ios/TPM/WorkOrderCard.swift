import SwiftUI

struct WorkOrderCard<Destination: View>: View {
    let title: String
    var status = ""
    var color: Color = .blue
    let reference: String
    var referenceTitle = "资产编码"
    let time: String
    let facts: [(String, String)]
    @ViewBuilder let destination: () -> Destination
    @State private var expanded = false
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            NavigationLink { destination().modifier(DetailTabVisibility()) } label: {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text(title).font(.headline).foregroundStyle(.primary).lineLimit(1).truncationMode(.tail).frame(maxWidth: .infinity, alignment: .leading)
                    if !status.isEmpty { Text(status).font(.subheadline.weight(.medium)).foregroundStyle(color).lineLimit(1) }
                }
            }.buttonStyle(.plain).navigationLinkIndicatorVisibility(.hidden)
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(reference.isEmpty ? "—" : reference).frame(maxWidth: .infinity, alignment: .leading)
                Text(time).frame(maxWidth: .infinity, alignment: .trailing)
            }.font(.subheadline).foregroundStyle(.secondary).lineLimit(1).truncationMode(.tail)
            DisclosureGroup(isExpanded: $expanded) {
                VStack(spacing: 12) {
                    ForEach(Array(facts.enumerated()), id: \.offset) { _, fact in
                        ReadableFact(title: fact.0, value: fact.1)
                    }
                    NavigationLink { destination().modifier(DetailTabVisibility()) } label: { Text("打开工单") }.buttonStyle(.plain).font(.subheadline)
                }.padding(.top, 12)
            } label: { Text(expanded ? "收起详情" : "展开详情").font(.subheadline) }
                .accessibilityIdentifier("order-expand-" + reference)
        }.buttonStyle(.plain).padding(.vertical, 8)
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
            .onDisappear { navigation.hideTabs = false }
    }
}