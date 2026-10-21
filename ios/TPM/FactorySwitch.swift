import SwiftUI

/// Toolbar menu for the account's factories. Choosing another factory switches at once;
/// there is no full-screen chooser and no confirmation step.
struct PreviewFactoryButton: View {
    @State private var current = "示例一厂"
    private let factories = ["示例一厂", "示例二厂"]
    var body: some View {
        Menu {
            ForEach(factories, id: \.self) { name in
                Button { current = name } label: {
                    if name == current { Label(name, systemImage: "checkmark") } else { Text(name) }
                }.disabled(name == current)
            }
        } label: { FactoryNameLabel(name: current) }
            .accessibilityLabel("切换厂区，当前" + current)
    }
}

struct FactorySwitcherButton: View {
    @EnvironmentObject private var session: LiveSession
    @State private var switching = false
    @State private var failure: String?
    var body: some View {
        Menu {
            ForEach(Array(session.factories.enumerated()), id: \.offset) { _, factory in
                let id = factory["factoryId"].text, name = factory["factoryName"].text
                Button { Task { await change(to: id) } } label: {
                    if id == session.currentFactoryID { Label(name, systemImage: "checkmark") } else { Text(name) }
                }.disabled(id == session.currentFactoryID)
            }
        } label: { FactoryNameLabel(name: session.currentFactoryName) }
            .disabled(switching)
            .accessibilityLabel("切换厂区，当前" + session.currentFactoryName)
            // Keep the list current; the stored one comes from sign-in time.
            .task { try? await session.refreshFactories() }
            .noticeDialog("切换厂区失败", message: failure ?? "", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } }))
    }
    private func change(to id: String) async {
        guard !switching else { return }
        switching = true
        defer { switching = false }
        do { try await session.changeFactory(to: id) }
        catch is CancellationError {}
        catch { failure = error.localizedDescription }
    }
}

/// Natural width, wrapping onto a second line for long names instead of a fixed-width slot.
private struct FactoryNameLabel: View {
    let name: String
    var body: some View {
        HStack(spacing: 4) {
            Text(name).lineLimit(2).multilineTextAlignment(.trailing).fixedSize(horizontal: false, vertical: true)
            Image(systemName: "chevron.up.chevron.down").font(.caption2)
        }.font(.subheadline).frame(maxWidth: 200, alignment: .trailing)
    }
}
