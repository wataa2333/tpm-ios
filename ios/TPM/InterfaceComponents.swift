import SwiftUI

extension EnvironmentValues {
    @Entry var previewReferences = false
}

struct BottomAction: View {
    let title: String
    let symbol: String
    var color: Color = .blue
    var busy = false
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            VStack(spacing: 5) {
                Group { if busy { ProgressView() } else { Image(systemName: symbol).font(.system(size: 28, weight: .medium)) } }
                    .frame(width: 58, height: 48)
                    .foregroundStyle(color)
                Text(title).font(.subheadline.weight(.medium)).foregroundStyle(.primary)
            }.padding(.horizontal, 8).padding(.vertical, 6)
        }
        .buttonStyle(.glass).buttonBorderShape(.roundedRectangle(radius: 24))
        .accessibilityLabel(title)
    }
}

struct LoadingOverlay: View {
    let title: String
    var body: some View {
        ZStack {
            Color.black.opacity(0.22).ignoresSafeArea()
            VStack(spacing: 12) {
                ProgressView().controlSize(.large)
                Text(title).font(.subheadline)
            }.padding(24).background(.regularMaterial, in: .rect(cornerRadius: 22))
        }.accessibilityAddTraits(.updatesFrequently)
    }
}

struct CompactEmptyState: View {
    var title = "暂无相关报修"
    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "tray").font(.system(size: 28)).foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text("可切换状态或调整搜索条件").font(.subheadline).foregroundStyle(.secondary)
        }.frame(maxWidth: .infinity).padding(.vertical, 28)
    }
}

struct HorizontalCategories: View {
    let titles: [String]
    @Binding var selection: Int
    var body: some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal) {
                HStack(spacing: 24) {
                    ForEach(titles.indices, id: \.self) { index in
                        Button { withAnimation { selection = index } } label: {
                            VStack(spacing: 10) {
                                Text(titles[index]).font(.body.weight(selection == index ? .semibold : .regular))
                                    .foregroundStyle(selection == index ? Color.accentColor : Color.secondary)
                                Capsule().fill(selection == index ? Color.accentColor : Color.clear).frame(height: 3)
                            }.fixedSize(horizontal: true, vertical: false).padding(.top, 12)
                        }.buttonStyle(.plain).id(index)
                            .accessibilityAddTraits(selection == index ? [.isSelected] : [])
                    }
                }.padding(.horizontal, 20)
            }.scrollIndicators(.hidden)
                .onChange(of: selection) { _, index in withAnimation { proxy.scrollTo(index, anchor: .center) } }
                .onAppear { proxy.scrollTo(selection, anchor: .center) }
        }.padding(.bottom, 8)
    }
}

enum FaultSearchField: String, CaseIterable, Identifiable {
    case name = "报修名称", number = "任务号", machine = "机台号", assetCode = "资产编码", assetName = "资产名称", address = "地址", reporter = "报修人", group = "维修班组", repairer = "执行人"
    var id: String { rawValue }
    var key: String {
        switch self {
        case .name: "faultName"; case .number: "sn"; case .machine: "asset.addressCode"; case .assetCode: "asset.assetCode"; case .assetName: "asset.assetName"; case .address: "asset.address"; case .reporter: "applyUser.realName"; case .group: "userGroup.groupName"; case .repairer: "repairUser.realName"
        }
    }
}
