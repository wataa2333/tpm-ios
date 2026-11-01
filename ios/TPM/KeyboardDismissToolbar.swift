import SwiftUI

/// The active input page declares the toolbar; SwiftUI owns its keyboard lifecycle.
/// No runtime accessory replacement, input-view reload or keyboard notification state.
extension View {
    func keyboardDismissToolbar(enabled: Bool = true) -> some View {
        toolbar {
            if enabled {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("收起键盘", action: endEditing)
                    // Toolbar items already have a system glass background.
                    .buttonStyle(.automatic)
                    .buttonBorderShape(.capsule)
                    .accessibilityIdentifier("keyboard-dismiss")
                Spacer()
            }
            }
        }
    }
}
