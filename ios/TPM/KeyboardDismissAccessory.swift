import UIKit

/// An input accessory belongs to the focused editor, so sheet/navigation changes do not
/// depend on a SwiftUI page catching the keyboard's will-show notification in time.
@MainActor final class KeyboardDismissAccessory: NSObject {
    static let shared = KeyboardDismissAccessory()
    private var started = false

    func start() {
        guard !started else { return }
        started = true
        for name in [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification] {
            NotificationCenter.default.addObserver(self, selector: #selector(editingBegan(_:)), name: name, object: nil)
        }
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardShown), name: UIResponder.keyboardDidShowNotification, object: nil)
    }

    @objc private func editingBegan(_ notification: Notification) {
        guard let editor = notification.object as? UIView, editor.isFirstResponder else { return }
        install(on: editor)
        // SwiftUI can finish configuring the editor in the same run-loop turn.
        DispatchQueue.main.async { [weak self, weak editor] in
            guard let editor, editor.isFirstResponder else { return }
            self?.install(on: editor)
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
        let dismiss = UIBarButtonItem(title: "收起键盘", style: .done, target: self, action: #selector(closeKeyboard))
        dismiss.accessibilityIdentifier = "keyboard-dismiss"
        toolbar.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil), dismiss]
        if let field = editor as? UITextField { field.inputAccessoryView = toolbar }
        else if let text = editor as? UITextView { text.inputAccessoryView = toolbar }
        editor.reloadInputViews()
    }

    @objc private func keyboardShown() {
        func focused(in view: UIView) -> UIView? {
            if view.isFirstResponder { return view }
            for child in view.subviews { if let result = focused(in: child) { return result } }
            return nil
        }
        for scene in UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }) {
            for window in scene.windows where window.isKeyWindow {
                if let editor = focused(in: window) { install(on: editor) }
            }
        }
    }

    @objc private func closeKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}
