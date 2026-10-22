import SwiftUI
import UIKit

enum AppAccent: String, CaseIterable, Identifiable {
    case system, sky, pink, purple, red, yellow, indigo, orange, lightGreen, green, teal, cyan, deepPurple, gray
    var id: String { rawValue }
    var title: String {
        switch self {
        case .system: "系统默认"
        case .sky: "胖次蓝"
        case .pink: "哔哩哔哩粉"
        case .purple: "基佬紫"
        case .red: "傲娇红"
        case .yellow: "咸蛋黄"
        case .indigo: "靛蓝"
        case .orange: "橙色"
        case .lightGreen: "浅绿"
        case .green: "绿色"
        case .teal: "青绿"
        case .cyan: "青色"
        case .deepPurple: "深紫"
        case .gray: "灰色"
        }
    }
    var rgb: UInt32? {
        switch self {
        case .system: nil
        case .sky: 0x039BE5
        case .pink: 0xFF6699
        case .purple: 0x8E24AA
        case .red: 0xE53935
        case .yellow: 0xFFB300
        case .indigo: 0x3949AB
        case .orange: 0xFB8C00
        case .lightGreen: 0x7CB342
        case .green: 0x43A047
        case .teal: 0x00897B
        case .cyan: 0x00ACC1
        case .deepPurple: 0x5E35B1
        case .gray: 0x757575
        }
    }
    var color: Color {
        guard let rgb else { return Color(uiColor: .systemBlue) }
        return Color(.sRGB, red: Double((rgb >> 16) & 255) / 255,
                     green: Double((rgb >> 8) & 255) / 255, blue: Double(rgb & 255) / 255, opacity: 1)
    }
    static let defaultsKey = "appearance.accent"
}

struct AppearanceSettings: View {
    @AppStorage(AppAccent.defaultsKey) private var selected = AppAccent.system.rawValue
    var body: some View {
        List {
            Section("主题强调色") {
                ForEach(AppAccent.allCases) { accent in
                    Button { selected = accent.rawValue } label: {
                        HStack {
                            Circle().fill(accent.color).frame(width: 22, height: 22)
                            Text(accent.title).foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: "checkmark").foregroundStyle(accent.color)
                                .frame(width: 22).opacity(selected == accent.rawValue ? 1 : 0)
                                .accessibilityHidden(true)
                                .interfaceMotion(selected == accent.rawValue, duration: 0.2)
                        }.contentShape(.rect)
                    }.buttonStyle(.plain)
                        .accessibilityAddTraits(selected == accent.rawValue ? .isSelected : [])
                }
            }
        }
        .navigationTitle("外观").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
    }
}

struct DeveloperAccessSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var password = ""
    @State private var unlocked = false
    @State private var incorrect = false
    @AppStorage(ManagementSettings.enabledKey) private var managementEnabled = false
    @AppStorage(ManagedReadSettings.experimentalKey) private var experimental = false
    @AppStorage(ManagedReadSettings.pageKey) private var pageSize = 500
    @AppStorage(ManagedReadSettings.concurrencyKey) private var concurrency = 8
    var body: some View {
        NavigationStack {
            Form {
                if unlocked {
                    Section {
                        Toggle("管理看板", isOn: $managementEnabled)
                    } footer: { Text("开启后在工作台显示管理看板入口，不改变服务器权限。") }
                    Section("管理读取档位") {
                        Toggle("实验读取档位", isOn: $experimental)
                        if experimental {
                            Picker("每页数量", selection: $pageSize) {
                                ForEach([50, 100, 200, 500], id: \.self) { Text("\($0) 单").tag($0) }
                            }
                            Picker("详情并发", selection: $concurrency) {
                                ForEach([1, 3, 5, 8], id: \.self) { Text("\($0) 单").tag($0) }
                            }
                        }
                        Text("默认每页 500、详情并发 8。档位仅对下次新查询生效；服务端最大每页数量未实测。").font(.caption).foregroundStyle(.secondary)
                    }
                    Section("应用信息") {
                        LabeledContent("版本", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "—")
                        LabeledContent("构建", value: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "—")
                    }
                } else {
                    Section("开发者验证") {
                        SecureField("密码", text: $password).textContentType(.password)
                            .submitLabel(.go).onSubmit(unlock)
                        if incorrect { Text("密码不正确").foregroundStyle(.red) }
                        Button("进入", action: unlock)
                    }
                }
            }
            .interfaceMotion(unlocked).interfaceMotion(experimental).interfaceMotion(incorrect)
            .onChange(of: experimental) { _, value in if !value { pageSize = 500; concurrency = 8 } }
            .navigationTitle(unlocked ? "开发者选项" : "开发者验证").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { dismiss() } } }
        }
    }
    private func unlock() {
        unlocked = password == "1331"
        incorrect = !unlocked
        password = ""
    }
}
