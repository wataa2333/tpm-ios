import UIKit
import SwiftUI

/// An input accessory belongs to the focused editor, so sheet/navigation changes do not
/// depend on a SwiftUI page catching the keyboard's will-show notification in time.
@MainActor final class KeyboardDismissAccessory: NSObject, ObservableObject {
    static let shared = KeyboardDismissAccessory()
    private var started = false
    private weak var focusedEditor: UIView?
    @Published private(set) var keyboardVisible = false

    func start() {
        guard !started else { return }
        started = true
        for name in [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(editingBegan(_:)), name: name, object: nil)
        }
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillChangeFrameNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged(_:)), name: UIResponder.keyboardWillHideNotification, object: nil)
    }

    @objc private func editingBegan(_ notification: Notification) {
        guard let editor = notification.object as? UIView, editor.isFirstResponder else { return }
        focusedEditor = editor
        // Install once after SwiftUI configures the editor. Never reload from keyboard-show
        // notifications: reloading can generate another show and repeated layout work.
        DispatchQueue.main.async { [weak self, weak editor] in
            guard let editor, editor.isFirstResponder else { return }
            guard let self, self.focusedEditor === editor else { return }
            self.install(on: editor)
        }
    }

    private func install(on editor: UIView) {
        let current: UIView?
        if let field = editor as? UITextField { current = field.inputAccessoryView }
        else if let text = editor as? UITextView { current = text.inputAccessoryView }
        else { return }
        if current?.accessibilityIdentifier == "keyboard-dismiss-accessory" { return }
        let toolbar = UIToolbar(frame: CGRect(x: 0, y: 0, width: editor.window?.bounds.width ?? 320, height: 44))
        toolbar.autoresizingMask = [.flexibleWidth]
        toolbar.accessibilityIdentifier = "keyboard-dismiss-accessory"
        let dismiss = UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(closeKeyboard))
        dismiss.accessibilityIdentifier = "keyboard-dismiss"
        dismiss.accessibilityLabel = "收起键盘"
        toolbar.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil), dismiss]
        if let field = editor as? UITextField { field.inputAccessoryView = toolbar }
        else if let text = editor as? UITextView { text.inputAccessoryView = toolbar }
        editor.reloadInputViews()
    }

    @objc private func keyboardChanged(_ notification: Notification) {
        let visible = KeyboardMotionChange(notification).visible
        guard keyboardVisible != visible else { return }
        keyboardVisible = visible
    }

    @objc private func closeKeyboard() {
        let window = focusedEditor?.window
        focusedEditor?.resignFirstResponder()
        window?.endEditing(true)
        if window == nil || focusedEditor?.isFirstResponder == true { endEditing() }
    }
}
