import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers

// Render vector lotus petals without requiring a window server or AppKit.
let size = 1024
let space = CGColorSpaceCreateDeviceRGB()
let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
    bytesPerRow: size * 4, space: space, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
context.setFillColor(CGColor(red: 0.96, green: 0.98, blue: 1, alpha: 1))
context.fill(CGRect(x: 0, y: 0, width: size, height: size))
func petal(_ points: [(CGFloat, CGFloat)], from: CGColor, to: CGColor) {
    let path = CGMutablePath()
    path.move(to: CGPoint(x: points[0].0, y: points[0].1))
    path.addCurve(to: CGPoint(x: points[3].0, y: points[3].1),
                  control1: CGPoint(x: points[1].0, y: points[1].1),
                  control2: CGPoint(x: points[2].0, y: points[2].1))
    path.addCurve(to: CGPoint(x: points[0].0, y: points[0].1),
                  control1: CGPoint(x: points[4].0, y: points[4].1),
                  control2: CGPoint(x: points[5].0, y: points[5].1))
    path.closeSubpath()
    context.saveGState()
    context.addPath(path)
    context.clip()
    let gradient = CGGradient(colorsSpace: space, colors: [from, to] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient, start: CGPoint(x: 300, y: 260), end: CGPoint(x: 650, y: 800), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    context.restoreGState()
}
let blue = CGColor(red: 0.26, green: 0.46, blue: 0.80, alpha: 1)
let teal = CGColor(red: 0.28, green: 0.66, blue: 0.72, alpha: 1)
let violet = CGColor(red: 0.48, green: 0.48, blue: 0.81, alpha: 1)
petal([(220, 465), (350, 460), (430, 385), (492, 260), (332, 262), (230, 312)], from: blue, to: teal)
petal([(804, 465), (674, 460), (594, 385), (532, 260), (692, 262), (794, 312)], from: violet, to: blue)
petal([(512, 794), (374, 590), (380, 422), (512, 294), (644, 422), (650, 590)], from: blue, to: teal)
let image = context.makeImage()!
for path in ["ios/TPM/Assets.xcassets/AppIcon.appiconset/AppIcon.png", "build/screenshots/00-app-icon.png"] {
    let url = URL(fileURLWithPath: path)
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    let destination = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { fatalError("Cannot write icon PNG") }
}

