import AppKit

// Vector construction: three lotus petals form a quiet, upward triangle.
let size = 1024
let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 3, hasAlpha: false, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
NSColor(calibratedRed: 0.96, green: 0.98, blue: 1, alpha: 1).setFill()
NSRect(x: 0, y: 0, width: size, height: size).fill()

func petal(_ points: [(CGFloat, CGFloat)], from: NSColor, to: NSColor) {
    let path = NSBezierPath()
    path.move(to: NSPoint(x: points[0].0, y: points[0].1))
    path.curve(to: NSPoint(x: points[3].0, y: points[3].1),
               controlPoint1: NSPoint(x: points[1].0, y: points[1].1),
               controlPoint2: NSPoint(x: points[2].0, y: points[2].1))
    path.curve(to: NSPoint(x: points[0].0, y: points[0].1),
               controlPoint1: NSPoint(x: points[4].0, y: points[4].1),
               controlPoint2: NSPoint(x: points[5].0, y: points[5].1))
    path.close()
    NSGradient(starting: from, ending: to)!.draw(in: path, angle: 70)
}
let blue = NSColor(calibratedRed: 0.26, green: 0.46, blue: 0.80, alpha: 1)
let teal = NSColor(calibratedRed: 0.28, green: 0.66, blue: 0.72, alpha: 1)
let violet = NSColor(calibratedRed: 0.48, green: 0.48, blue: 0.81, alpha: 1)
petal([(220, 465), (350, 460), (430, 385), (492, 260), (332, 262), (230, 312)], from: blue, to: teal)
petal([(804, 465), (674, 460), (594, 385), (532, 260), (692, 262), (794, 312)], from: violet, to: blue)
petal([(512, 794), (374, 590), (380, 422), (512, 294), (644, 422), (650, 590)], from: blue, to: teal)
NSGraphicsContext.restoreGraphicsState()
let directory = "ios/TPM/Assets.xcassets/AppIcon.appiconset"
try FileManager.default.createDirectory(atPath: directory, withIntermediateDirectories: true)
let data = bitmap.representation(using: .png, properties: [:])!
try data.write(to: URL(fileURLWithPath: directory + "/AppIcon.png"))
try FileManager.default.createDirectory(atPath: "build/screenshots", withIntermediateDirectories: true)
try data.write(to: URL(fileURLWithPath: "build/screenshots/00-app-icon.png"))
