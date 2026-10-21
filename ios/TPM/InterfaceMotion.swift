import SwiftUI
import UIKit

/// Keep layout interpolation and its fade in the same transaction. Reduce Motion suppresses movement.
private struct InterfaceMotion<Value: Equatable>: ViewModifier {
    let value: Value
    var duration: Double
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content.animation(reduceMotion ? nil : .smooth(duration: duration), value: value)
    }
}

extension View {
    func interfaceMotion<Value: Equatable>(_ value: Value, duration: Double = 0.3) -> some View {
        modifier(InterfaceMotion(value: value, duration: duration))
    }
}

/// Both the keyboard close row and the action bar use the system's timing instead of competing clocks.
struct KeyboardMotionChange {
    let visible: Bool
    let duration: Double
    let curve: Int
    @MainActor init(_ notification: Notification) {
        let info = notification.userInfo ?? [:]
        duration = (info[UIResponder.keyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        curve = (info[UIResponder.keyboardAnimationCurveUserInfoKey] as? NSNumber)?.intValue ?? 0
        let window = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows).first { $0.isKeyWindow }
        if notification.name == UIResponder.keyboardWillHideNotification {
            visible = false
        } else if let frame = info[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect, let window {
            let bounds = window.screen.bounds
            visible = frame.minY < bounds.maxY && frame.maxY >= bounds.maxY - 1 && frame.width >= bounds.width * 0.9
        } else { visible = false }
    }
    func animation(reduceMotion: Bool) -> Animation? {
        guard !reduceMotion, duration > 0 else { return nil }
        switch curve {
        case 1: return .easeIn(duration: duration)
        case 2: return .easeOut(duration: duration)
        case 3: return .linear(duration: duration)
        default: return .easeInOut(duration: duration)
        }
    }
}
