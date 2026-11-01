import Testing
import UIKit
@testable import TPM

@Test @MainActor func formActionsWaitUntilKeyboardDismissalCompletes() {
    let center = NotificationCenter()
    let keyboard = KeyboardDismissAccessory(notificationCenter: center)
    keyboard.start()
    center.post(name: UIResponder.keyboardDidShowNotification, object: nil)
    #expect(keyboard.keyboardVisible)

    center.post(name: UIResponder.keyboardWillHideNotification, object: nil)
    #expect(keyboard.keyboardVisible)
    center.post(name: UIResponder.keyboardWillChangeFrameNotification, object: nil,
                userInfo: [UIResponder.keyboardFrameEndUserInfoKey: CGRect(x: 0, y: 10_000, width: 400, height: 300)])
    #expect(keyboard.keyboardVisible)

    center.post(name: UIResponder.keyboardDidHideNotification, object: nil)
    #expect(!keyboard.keyboardVisible)
}

@Test @MainActor func cancelledKeyboardDismissalKeepsFormActionsHidden() {
    let center = NotificationCenter()
    let keyboard = KeyboardDismissAccessory(notificationCenter: center)
    keyboard.start()
    for _ in 0..<3 {
        center.post(name: UIResponder.keyboardDidShowNotification, object: nil)
        center.post(name: UIResponder.keyboardWillHideNotification, object: nil)
        // The keyboard returned instead of completing the interactive dismissal.
        center.post(name: UIResponder.keyboardDidShowNotification, object: nil)
        #expect(keyboard.keyboardVisible)
    }
    center.post(name: UIResponder.keyboardDidHideNotification, object: nil)
    #expect(!keyboard.keyboardVisible)
}
