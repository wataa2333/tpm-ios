import SwiftUI

/// Opens a new operator-owned form. The reference contains no source order or workflow fields.
struct AssetReportButton: View {
    let asset: JSONValue
    @State private var presenting = false
    private var reference: JSONValue { .object(["assetId": asset["assetId"], "assetCode": asset["assetCode"], "assetName": asset["assetName"]]) }
    var body: some View {
        if !asset["assetId"].text.isEmpty && !asset["assetCode"].text.isEmpty {
            Button { presenting = true } label: {
                Label("使用此资产编码新建报修", systemImage: "plus.circle")
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity).padding(.vertical, 8)
            }.buttonStyle(.glass).buttonBorderShape(.roundedRectangle(radius: 16))
                .accessibilityIdentifier("report-with-asset")
                .sheet(isPresented: $presenting) {
                    NavigationStack { OriginalFaultForm(initialAsset: reference) }
                        .environment(\.queryReading, false)
                }
        }
    }
}
