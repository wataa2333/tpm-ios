import SwiftUI

struct FaultReportTemplate: Codable, Hashable, Identifiable {
    var id = UUID().uuidString
    var name = ""
    var faultName = ""
    var description = ""
    var asset: JSONValue = .null
    var preset = false

    static let blank = FaultReportTemplate(id: "blank", name: "空白报修")
    static let presets = [
        FaultReportTemplate(id: "startup", name: "设备无法启动", faultName: "设备无法启动",
                            description: "故障现象：\n报警代码：", preset: true),
        FaultReportTemplate(id: "noise", name: "设备运行异响", faultName: "设备运行异响",
                            description: "异响位置：\n出现时机：", preset: true),
        FaultReportTemplate(id: "leak", name: "设备漏油漏液", faultName: "设备漏油漏液",
                            description: "泄漏位置：\n泄漏情况：", preset: true)
    ]
}

struct FaultReportTemplatePage: View {
    let preview: Bool
    let isActive: Bool
    @EnvironmentObject private var navigation: BottomNavigationModel
    @AppStorage private var storage: Data
    @State private var chosen: FaultReportTemplate?
    @State private var editing: FaultReportTemplate?

    init(scope: String, preview: Bool, isActive: Bool) {
        self.preview = preview
        self.isActive = isActive
        _storage = AppStorage(wrappedValue: Data(), "fault-report-templates." + scope)
    }

    private var templates: [FaultReportTemplate] {
        (try? JSONDecoder().decode([FaultReportTemplate].self, from: storage)) ?? FaultReportTemplate.presets
    }

    var body: some View {
        List {
            Section {
                Button("空白报修", systemImage: "square.and.pencil") { chosen = .blank }
                    .accessibilityIdentifier("blank-fault-report")
            }
            let presets = templates.filter(\.preset)
            if !presets.isEmpty {
                Section("预设模板") { ForEach(presets) { templateRow($0) } }
            }
            Section("自定义模板") {
                ForEach(templates.filter { !$0.preset }) { templateRow($0) }
                Button("新建模板", systemImage: "plus") { editing = FaultReportTemplate() }
                    .accessibilityIdentifier("new-fault-template")
            }
        }
        .navigationTitle("报修模板").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .navigationDestination(item: $chosen) { template in
            OriginalFaultForm(preview: preview, initialAsset: template.asset == .null ? nil : template.asset,
                              onSaved: { navigation.refresh = UUID() }, isActive: isActive, template: template)
                .navigationBarBackButtonHidden()
                .modifier(ListTabVisibility())
        }
        .sheet(item: $editing) { template in
            FaultReportTemplateEditor(template: template, existing: templates.contains { $0.id == template.id }) { saved in
                var next = templates
                if let index = next.firstIndex(where: { $0.id == saved.id }) { next[index] = saved }
                else { next.append(saved) }
                store(next)
            }
        }
    }

    private func templateRow(_ template: FaultReportTemplate) -> some View {
        HStack(spacing: 8) {
            Button { chosen = template } label: {
                Text(template.name)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(.rect)
            }.buttonStyle(.plain)
            Button { editing = template } label: {
                Image(systemName: "pencil").frame(width: 44, height: 44).contentShape(.rect)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel("编辑模板 " + template.name)
            Button(role: .destructive) {
                store(templates.filter { $0.id != template.id })
            } label: {
                Image(systemName: "trash").frame(width: 44, height: 44).contentShape(.rect)
            }
            .buttonStyle(.borderless)
            .foregroundStyle(.red)
            .accessibilityLabel("删除模板 " + template.name)
        }
    }

    private func store(_ templates: [FaultReportTemplate]) {
        if let encoded = try? JSONEncoder().encode(templates) { storage = encoded }
    }
}

private struct FaultReportTemplateEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State var template: FaultReportTemplate
    let existing: Bool
    let save: (FaultReportTemplate) -> Void

    private var name: String { template.name.trimmingCharacters(in: .whitespacesAndNewlines) }

    var body: some View {
        NavigationStack {
            Form {
                Section("模板名称") {
                    EntryField(placeholder: "请输入模板名称", text: $template.name, limit: 64)
                }
                Section("预填内容") {
                    EntryField(placeholder: "报修名称", text: $template.faultName, limit: 64)
                    ReferenceField(title: "报修资产", source: .assets, value: $template.asset)
                    if template.asset != .null {
                        Button("清除预设资产") { template.asset = .null }
                    }
                    EntryField(placeholder: "故障描述", text: $template.description, limit: 255,
                               axis: .vertical, lines: 3...6)
                }
            }
            .navigationTitle(existing ? "编辑模板" : "新建模板").navigationBarTitleDisplayMode(.inline)
            .keyboardManaged()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存模板") {
                        template.name = name
                        save(template)
                        dismiss()
                    }.disabled(name.isEmpty)
                }
            }
        }
    }
}
