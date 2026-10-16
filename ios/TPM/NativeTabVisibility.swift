import SwiftUI
import UIKit

/// The appearing UIKit child owns tab visibility. Do not restore it from a
/// disappearing detail: another detail may be arriving at the same time.
struct NativeTabVisibility: UIViewControllerRepresentable {
    let hidden: Bool
    func makeUIViewController(context: Context) -> VisibilityController {
        let controller = VisibilityController()
        controller.hidden = hidden
        return controller
    }
    func updateUIViewController(_ controller: VisibilityController, context: Context) {
        controller.hidden = hidden
        controller.applyVisibility(animated: !context.transaction.disablesAnimations)
    }
    final class VisibilityController: UIViewController {
        var hidden = false
        func applyVisibility(animated: Bool) {
            guard let tabs = tabBarController, tabs.isTabBarHidden != hidden else { return }
            tabs.setTabBarHidden(hidden, animated: animated && !UIAccessibility.isReduceMotionEnabled)
        }
        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            applyVisibility(animated: animated)
        }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            applyVisibility(animated: animated)
        }
    }
}
