import SwiftUI

enum AppFont {
    static let largeTitle = Font.largeTitle
    static let title = Font.title
    static let title2 = Font.title2
    static let title3 = Font.title3
    static let headline = Font.headline
    static let subheadline = Font.subheadline
    static let body = Font.body
    static let callout = Font.callout
    static let footnote = Font.footnote
    static let caption = Font.caption
    static let caption2 = Font.caption2

    static func precisionText(_ style: Font.TextStyle) -> Font {
        .system(style, design: .default, weight: .light)
    }

    static let precisionGrade = Font.system(size: 64, weight: .light)
}
