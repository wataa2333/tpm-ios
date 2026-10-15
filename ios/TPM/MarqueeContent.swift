import SwiftUI

/// A single accessible view, clipped to its field. Only overflowing content moves.
struct MarqueeContent<Content: View>: View {
    var alignment: Alignment = .leading
    @ViewBuilder let content: () -> Content
    @State private var measured = CGSize.zero
    @State private var started = Date()
    @State private var visible = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        GeometryReader { geometry in
            let distance = max(0, measured.width - geometry.size.width)
            TimelineView(.animation(minimumInterval: 1.0 / 30, paused: distance < 1 || reduceMotion || !visible || scenePhase != .active)) { timeline in
                content().lineLimit(1).fixedSize(horizontal: true, vertical: false)
                    .offset(x: distance > 1 && !reduceMotion ? -marqueeOffset(elapsed: timeline.date.timeIntervalSince(started), distance: distance) : 0)
                    .frame(width: geometry.size.width, alignment: distance > 1 ? .leading : alignment)
            }
        }
        .frame(height: max(20, measured.height))
        .frame(maxWidth: .infinity)
        .clipped()
        .contentShape(.rect)
        .background {
            content().lineLimit(1).fixedSize(horizontal: true, vertical: false)
                .hidden().accessibilityHidden(true)
                .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                    if measured != size { measured = size; started = Date() }
                }
        }
        .onAppear { visible = true; started = Date() }
        .onDisappear { visible = false }
    }
}

func marqueeOffset(elapsed: TimeInterval, distance: CGFloat) -> CGFloat {
    guard distance > 0 else { return 0 }
    let pause = 1.4
    let travel = Double(distance) / 26
    let phase = max(0, elapsed).truncatingRemainder(dividingBy: 2 * (pause + travel))
    if phase <= pause { return 0 }
    if phase <= pause + travel { return CGFloat((phase - pause) / travel) * distance }
    if phase <= 2 * pause + travel { return distance }
    return distance * CGFloat(1 - (phase - 2 * pause - travel) / travel)
}
