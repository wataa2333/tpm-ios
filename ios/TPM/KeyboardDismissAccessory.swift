import UIKit
import SwiftUI

/// An input accessory belongs to the focused editor, so sheet/navigation changes do not
/// depend on a SwiftUI page catching the keyboard's will-show notification in time.
@MainActor final class KeyboardDismissAccessory: NSObject, ObservableObject {
    static let shared = KeyboardDismissAccessory()
    private var started = false
    private weak var focusedEditor: UIView?
    private let notificationCenter: NotificationCenter
    @Published private(set) var keyboardVisible = false

    init(notificationCenter: NotificationCenter = .default) {
        self.notificationCenter = notificationCenter
        super.init()
    }

    func start() {
        guard !started else { return }
        started = true
        for name in [UITextField.textDidBeginEditingNotification, UITextView.textDidBeginEditingNotification] {
            notificationCenter.addObserver(self, selector: #selector(editingBegan(_:)), name: name, object: nil)
        }
        for name in [UIResponder.keyboardWillChangeFrameNotification, UIResponder.keyboardWillHideNotification,
                     UIResponder.keyboardDidShowNotification, UIResponder.keyboardDidHideNotification] {
            notificationCenter.addObserver(self, selector: #selector(keyboardChanged(_:)), name: name, object: nil)
        }
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
        var configuration = UIButton.Configuration.glass()
        configuration.title = "收起键盘"
        configuration.cornerStyle = .capsule
        configuration.contentInsets = NSDirectionalEdgeInsets(top: 8, leading: 18, bottom: 8, trailing: 18)
        let button = UIButton(configuration: configuration, primaryAction: nil)
        button.accessibilityIdentifier = "keyboard-dismiss"
        button.accessibilityLabel = "收起键盘"
        button.addTarget(self, action: #selector(closeKeyboard), for: .touchUpInside)
        let dismiss = UIBarButtonItem(customView: button)
        dismiss.hidesSharedBackground = true
        toolbar.items = [UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil), dismiss, UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil)]
        if let field = editor as? UITextField { field.inputAccessoryView = toolbar }
        else if let text = editor as? UITextView { text.inputAccessoryView = toolbar }
        editor.reloadInputViews()
    }

    @objc private func keyboardChanged(_ notification: Notification) {
        // Keep form actions out of the keyboard's closing animation. A will-hide or
        // offscreen will-change frame is only an intention and can be cancelled by a drag.
        if notification.name == UIResponder.keyboardWillHideNotification { return }
        let visible: Bool
        if notification.name == UIResponder.keyboardDidHideNotification { visible = false }
        else if notification.name == UIResponder.keyboardDidShowNotification { visible = true }
        else {
            visible = KeyboardMotionChange(notification).visible
            if notification.name == UIResponder.keyboardWillChangeFrameNotification && !visible { return }
        }
        guard keyboardVisible != visible else { return }
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) { keyboardVisible = visible }
    }

    @objc private func closeKeyboard() {
        // An accepted resignation already starts UIKit's animation. Do not immediately
        // request dismissal again through the window and the application responder chain.
        if let editor = focusedEditor, editor.isFirstResponder, editor.resignFirstResponder() { return }
        if let window = focusedEditor?.window { window.endEditing(true) }
        else { endEditing() }
    }
}
