import SwiftUI

/// The active input page declares the toolbar; SwiftUI owns its keyboard lifecycle.
/// No runtime accessory replacement, input-view reload or keyboard notification state.
extension View {
    func keyboardDismissToolbar() -> some View {
        toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("收起键盘", action: endEditing)
                    .buttonStyle(.glass)
                    .buttonBorderShape(.capsule)
                    .accessibilityIdentifier("keyboard-dismiss")
                Spacer()
            }
        }
    }
}
