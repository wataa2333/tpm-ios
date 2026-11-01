import SwiftUI

/// The system owns keyboard placement, safe areas and dismissal animation.
/// Install once per input page, never on individual fields or the app window.
extension View {
    func nativeKeyboardToolbar() -> some View {
        toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("完成", systemImage: "keyboard.chevron.compact.down", action: endEditing)
                    .accessibilityLabel("收起键盘")
                    .accessibilityIdentifier("keyboard-dismiss")
            }
        }
    }
}
