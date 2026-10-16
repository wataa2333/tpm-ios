import SwiftUI

/// Decides when a page loads its data. A page loads when it is first shown, when what it shows
/// changes (another filter, a search, a tab), when the user's own write changed the data, or when the
/// user taps the refresh button. Coming back from a detail page is none of these, so nothing reloads.
@MainActor
final class ReloadGate: ObservableObject {
    /// Changes on every manual refresh; pages put it into their task id and scroll trigger.
    @Published private(set) var token = UUID()
    private var loaded: [String: String] = [:]
    private var manualPending = false

    /// The refresh button.
    func request() {
        manualPending = true
        token = UUID()
    }

    /// Runs `load` unless this exact request was already loaded. After a manual refresh the main
    /// load waits a moment so the list can finish scrolling to the top and the loading row can
    /// open there. A load that was interrupted (the page was left mid-load) is not remembered, so
    /// it runs again on return: that is finishing the load, not refreshing it.
    func run(_ slot: String = "main", key: String, load: () async -> Void) async {
        let full = key + "|" + token.uuidString
        guard loaded[slot] != full else { return }
        if slot == "main" && manualPending {
            manualPending = false
            try? await Task.sleep(for: .milliseconds(300))
            if Task.isCancelled { return }
        }
        await load()
        if !Task.isCancelled { loaded[slot] = full }
    }

    /// Task id for `.task(id:)`: changes with the page's own key and with each manual refresh.
    func id(_ key: String) -> String { key + "|" + token.uuidString }
}

/// The refresh button every refreshable page keeps in the top-right corner.
struct RefreshButton: View {
    let busy: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: "arrow.clockwise")
        }
        .disabled(busy)
        .accessibilityLabel("刷新")
        .accessibilityIdentifier("refresh-button")
    }
}

extension View {
    /// Adds the refresh button to the navigation bar's trailing side. Pages with other trailing
    /// buttons add them in their own toolbar items, so they sit in one row.
    func refreshButton(busy: Bool, action: @escaping () -> Void) -> some View {
        toolbar { ToolbarItem(placement: .topBarTrailing) { RefreshButton(busy: busy, action: action) } }
    }
}

extension View {
    /// The top-right group of the four root tabs: refresh button (when the tab has something to
    /// refresh) and the factory menu. Navigation bars cannot animate from one tab's items to another's,
    /// so every root tab shows the same group in the same place; switching tabs then changes nothing
    /// up there. A tab without data passes no `busy`, which leaves out only the refresh button.
    func rootTopBar(preview: Bool = false, busy: Bool? = nil, refresh: @escaping () -> Void = {}) -> some View {
        toolbar {
            if let busy { ToolbarItem(placement: .topBarTrailing) { RefreshButton(busy: busy, action: refresh) } }
            ToolbarItem(placement: .topBarTrailing) { if preview { PreviewFactoryButton() } else { FactorySwitcherButton() } }
        }
    }
}
