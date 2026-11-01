import SwiftUI

struct ManagementPeoplePicker: View {
    let people: [ManagedPerson]
    let initial: Set<String>
    let initialTotal: Bool
    let apply: (Set<String>, Bool) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var selected = Set<String>()
    @State private var total = true
    @State private var search = ""
    private var matching: [ManagedPerson] {
        let text = search.trimmingCharacters(in: .whitespacesAndNewlines)
        return people.filter { text.isEmpty || ($0.name + $0.identity).localizedCaseInsensitiveContains(text) }
    }
    var body: some View {
        NavigationStack {
            List {
                Section {
                    Toggle("全部合计（含身份未明确）", isOn: $total)
                    HStack {
                        Button("全选搜索结果") { selected.formUnion(matching.map(\.id)) }
                        Spacer()
                        Button("清空人员") { selected.removeAll() }
                    }
                    Text("已选\(selected.count)人 / 共\(people.count)人").font(AppFont.caption).foregroundStyle(.secondary)
                }
                Section("对比人员") {
                    ForEach(matching) { person in
                        Button {
                            if selected.contains(person.id) { selected.remove(person.id) } else { selected.insert(person.id) }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(person.name).foregroundStyle(.primary)
                                    Text(person.identity).font(AppFont.caption).foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: selected.contains(person.id) ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(selected.contains(person.id) ? Color.accentColor : Color.secondary)
                            }.frame(minHeight: 44).contentShape(.rect)
                        }.buttonStyle(.plain)
                        .accessibilityAddTraits(selected.contains(person.id) ? .isSelected : [])
                    }
                    if matching.isEmpty { Text("没有匹配的人员").foregroundStyle(.secondary) }
                }
            }
            .searchable(text: $search, prompt: "搜索姓名或账号")
            .navigationTitle("选择对比人员").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("完成") { apply(selected, total); dismiss() } }
            }
            .onAppear { selected = initial.intersection(Set(people.map(\.id))); total = initialTotal }
        }.presentationDetents([.large]).presentationDragIndicator(.visible)
    }
}
