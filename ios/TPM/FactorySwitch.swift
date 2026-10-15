import SwiftUI

struct FactorySwitcherButton: View {
    @EnvironmentObject private var session: LiveSession
    @State private var presented = false
    var body: some View {
        Button { presented = true } label: {
            HStack(spacing: 5) {
                Text(session.currentFactoryName).lineLimit(1)
                Image(systemName: "chevron.up.chevron.down").font(.caption)
            }.font(.subheadline)
        }
        .accessibilityLabel("切换厂区，当前" + session.currentFactoryName)
        .sheet(isPresented: $presented) { NavigationStack { FactorySwitchView() } }
    }
}

struct FactorySwitchView: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    var preview = false
    @State private var selection = ""
    @State private var loading = true
    @State private var ready = false
    @State private var switching = false
    @State private var confirming = false
    @State private var error: String?
    private var factories: [JSONValue] {
        preview ? [
            .object(["factoryId": .number(1), "factoryName": .string("示例一厂")]),
            .object(["factoryId": .number(2), "factoryName": .string("示例二厂")])
        ] : session.factories
    }
    private var currentID: String { preview ? "1" : session.currentFactoryID }
    private var selectedName: String { factories.first { $0["factoryId"].text == selection }?["factoryName"].text ?? "" }
    var body: some View {
        List {
            Section("可访问的厂区") {
                ForEach(factories, id: \.self) { factory in
                    Button { selection = factory["factoryId"].text } label: {
                        HStack(spacing: 10) {
                            Text(factory["factoryName"].text).foregroundStyle(.primary)
                            if factory["factoryId"].text == currentID { Text("当前").font(.caption).foregroundStyle(.secondary) }
                            Spacer(minLength: 12)
                            if selection == factory["factoryId"].text { Image(systemName: "checkmark").foregroundStyle(.tint) }
                        }
                    }
                    .accessibilityLabel(factory["factoryName"].text)
                    .accessibilityValue(selection == factory["factoryId"].text ? "已选择" : "未选择")
                    .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                    .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
                }
            }
            if let error { Section { Text(error).foregroundStyle(.red); Button("重新加载") { Task { await load() } } } }
            if preview { Text("厂区切换界面预览，使用示例厂区，不会切换真实账号。").font(.footnote).foregroundStyle(.secondary) }
        }
        .disabled(loading || switching)
        .overlay { if loading { ProgressView("正在加载厂区…") } }
        .navigationTitle("切换厂区").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(switching) }
            ToolbarItem(placement: .confirmationAction) {
                Button { confirming = true } label: {
                    HStack { if switching { ProgressView() }; Text("切换") }
                }.disabled(!ready || loading || switching || selection.isEmpty || selection == currentID)
            }
        }
        .interactiveDismissDisabled(switching)
        .task { selection = currentID; await load() }
        .confirmationDialog("切换到\(selectedName)？", isPresented: $confirming, titleVisibility: .visible) {
            Button("切换厂区") { Task { await change() } }
        } message: { Text("切换后将重新加载该厂区的单据，未保存的表单会关闭。") }
    }
    private func load() async {
        loading = true; ready = false; error = nil
        defer { loading = false }
        if preview { ready = true; return }
        do { try await session.refreshFactories(); ready = true }
        catch { self.error = error.localizedDescription }
    }
    private func change() async {
        guard !preview, !switching, ready else { return }
        switching = true; error = nil
        defer { switching = false }
        do { try await session.changeFactory(to: selection); dismiss() }
        catch { self.error = error.localizedDescription }
    }
}
