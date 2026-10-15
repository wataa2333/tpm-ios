import SwiftUI

func bottomBarExtension(safeAreaInset: CGFloat) -> CGFloat {
    min(12, max(0, safeAreaInset - 20))
}

@MainActor
final class BottomNavigationModel: ObservableObject {
    @Published var newDocument: WorkflowKind?
    @Published var hideTabs = false
    @Published var keyboardVisible = false
    @Published var refresh = UUID()
    @Published var searchReset = UUID()
    @Published var presentingDocument = false
}

