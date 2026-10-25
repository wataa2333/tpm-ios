import SwiftUI
import UIKit

/// Bundled Latin glyphs; Core Text falls back to the system font for Chinese.
enum AppFont {
    @MainActor static func configureNativeControls() {
        func native(_ name: String, size: CGFloat, style: UIFont.TextStyle) -> UIFont {
            guard let font = UIFont(name: name, size: size) else {
                assertionFailure("Bundled font not registered: " + name)
                return UIFont.preferredFont(forTextStyle: style)
            }
            return UIFontMetrics(forTextStyle: style).scaledFont(for: font)
        }
        let title = native("GoogleSans-SemiBold", size: 17, style: .headline)
        let large = native("GoogleSans-Bold", size: 34, style: .largeTitle)
        UINavigationBar.appearance().titleTextAttributes = [.font: title]
        UINavigationBar.appearance().largeTitleTextAttributes = [.font: large]
        let button = native("GoogleSans-Medium", size: 17, style: .body)
        for state in [UIControl.State.normal, .highlighted, .disabled, .selected] {
            UIBarButtonItem.appearance().setTitleTextAttributes([.font: button], for: state)
            UITabBarItem.appearance().setTitleTextAttributes([.font: native("GoogleSans-Medium", size: 10, style: .caption2)], for: state)
            UISegmentedControl.appearance().setTitleTextAttributes([.font: native("GoogleSans-Medium", size: 13, style: .footnote)], for: state)
        }
    }
    static func text(_ style: Font.TextStyle, size: CGFloat, weight: Font.Weight = .regular) -> Font {
        let name = weight == .bold ? "GoogleSans-Bold" : weight == .semibold ? "GoogleSans-SemiBold" : "GoogleSans-Regular"
        return .custom(name, size: size, relativeTo: style)
    }
    static let largeTitle = text(.largeTitle, size: 34)
    static let title = text(.title, size: 28)
    static let title2 = text(.title2, size: 22)
    static let title3 = text(.title3, size: 20)
    static let headline = text(.headline, size: 17, weight: .semibold)
    static let subheadline = text(.subheadline, size: 15)
    static let body = text(.body, size: 17)
    static let callout = text(.callout, size: 16)
    static let footnote = text(.footnote, size: 13)
    static let caption = text(.caption, size: 12)
    static let caption2 = text(.caption2, size: 11)
    static func precisionText(_ style: Font.TextStyle, size: CGFloat) -> Font {
        .custom("GoogleSansFlex-Light", size: size, relativeTo: style).weight(.light)
    }
    static let precisionGrade = Font.custom("GoogleSansFlex-Light", size: 64, relativeTo: .largeTitle)
}
