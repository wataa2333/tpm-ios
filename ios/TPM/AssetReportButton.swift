import SwiftUI

/// Opens a new operator-owned form. The reference contains no source order or workflow fields.
struct AssetReportButton: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.previewReferences) private var preview
    let asset: JSONValue
    var screenEdge = false
    var compact = false
    private var reference: JSONValue { .object(["assetId": asset["assetId"], "assetCode": asset["assetCode"], "assetName": asset["assetName"]]) }
    var body: some View {
        if !asset["assetId"].text.isEmpty && !asset["assetCode"].text.isEmpty {
            let button = NavigationLink {
                FaultReportTemplatePage(scope: preview ? "preview" : session.draftScope, preview: preview,
                                        isActive: true, initialAsset: reference)
                    .environment(\.queryReading, false)
            } label: {
                Text(compact ? "使用此资产编码报修" : "使用此资产编码进行新建报修")
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }.accessibilityLabel("使用此资产编码进行新建报修")
                .accessibilityIdentifier("report-with-asset")
                .navigationLinkIndicatorVisibility(.hidden)
            if screenEdge {
                button.buttonStyle(AssetReportDockStyle())
            } else {
                button.buttonStyle(.bordered)
            }
        }
    }
}

/// Resolves the corner radius from the enclosing screen rather than using a fixed button radius.
private struct AssetReportDockStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 12).padding(.vertical, 4)
            .foregroundStyle(.tint)
            .glassEffect(.regular.interactive(), in: ConcentricRectangle(corners: .concentric(minimum: 16), isUniform: true))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}
