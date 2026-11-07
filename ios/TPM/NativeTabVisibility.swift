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
        controller.updateVisibility(hidden, animated: !context.transaction.disablesAnimations)
    }
    final class VisibilityController: UIViewController {
        var hidden = false
        private var appeared = false

        func updateVisibility(_ hidden: Bool, animated: Bool) {
            guard self.hidden != hidden else { return }
            self.hidden = hidden
            // Initial SwiftUI updates can arrive before the navigation push starts.
            // Let UIKit's appearance callbacks start the transition for an arriving page.
            if appeared { applyVisibility(animated: animated) }
        }

        func applyVisibility(animated: Bool) {
            guard let tabs = tabBarController, tabs.isTabBarHidden != hidden else { return }
            tabs.setTabBarHidden(hidden, animated: animated && !UIAccessibility.isReduceMotionEnabled)
        }
        override func viewWillAppear(_ animated: Bool) {
            super.viewWillAppear(animated)
            applyVisibility(animated: transitionCoordinator?.isAnimated ?? animated)
        }
        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            appeared = false
            // A back swipe that is let go half way brings this screen back: it asserts its own state again,
            // because the screen behind has already asked for the opposite while the swipe was running.
            transitionCoordinator?.notifyWhenInteractionChanges { [weak self] context in
                if context.isCancelled { self?.applyVisibility(animated: true) }
            }
        }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            appeared = true
            applyVisibility(animated: animated)
        }
    }
}
