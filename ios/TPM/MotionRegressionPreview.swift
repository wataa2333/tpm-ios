#if DEBUG
import SwiftUI

/// Exercises the real batch bar with local selections; it has no API or workflow submission path.
struct MotionRegressionPreview: View {
    @StateObject private var selection = BatchSelection()
    private let sample = BatchItem(id: "MOTION-DEMO-1", kind: .fault, name: "示例工单", reference: "DEMO-1", group: "REPAIRED")
    private var reduced: Bool { ProcessInfo.processInfo.arguments.contains("--motion-reduced") }
    var body: some View {
        List {
            Section {
                Text("本地示例，不读取或提交业务数据。")
                Button("选择或取消示例工单") { selection.toggle(sample) }
                    .accessibilityIdentifier("motion-toggle-order")
            }
        }
        .navigationTitle("动画回归示例").navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            BatchBar(batch: selection, onSelectAll: { selection.selectAll([sample]) }, onRun: { _ in }) { EmptyView() }
                .accessibilityElement(children: .contain)
                .accessibilityIdentifier("motion-batch-bar")
        }
        .environment(\.accessibilityReduceMotion, reduced)
    }
}
#endif
