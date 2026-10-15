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
    }
    final class VisibilityController: UIViewController {
        var hidden = false
        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            tabBarController?.setTabBarHidden(hidden, animated: animated && !UIAccessibility.isReduceMotionEnabled)
        }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            tabBarController?.setTabBarHidden(hidden, animated: animated && !UIAccessibility.isReduceMotionEnabled)
        }
    }
}
