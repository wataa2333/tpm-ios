import SwiftUI

func bottomBarExtension(safeAreaInset: CGFloat) -> CGFloat {
    min(12, max(0, safeAreaInset - 20))
}

@MainActor
final class BottomNavigationModel: ObservableObject {
    @Published var newDocument: WorkflowKind?
    @Published var hideTabs = false
    @Published var refresh = UUID()
    @Published var searchReset = UUID()
    @Published var presentingDocument = false
    /// An order moved to its next step while its detail is open. The list behind must not
    /// reload yet (the row may leave the filtered list and take the detail with it); it
    /// applies this when it reappears.
    @Published var pendingListRefresh = false
    /// Preview-only: orders advanced while their detail is open, applied when the list reappears.
    @Published var pendingAdvance: [String: FaultState] = [:]
    /// Open todo tasks of the signed-in account: shown as the badge on the "我的待办" tab.
    @Published var todoCount = 0
}

