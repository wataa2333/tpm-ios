import SwiftUI

enum FaultTemplateField: String, CaseIterable, Identifiable {
    case faultName, asset, applyUser, applyDept, dept, user, userPhone, userGroup, division, faultDesc

    var id: String { rawValue }
    var title: String {
        switch self {
        case .faultName: "报修名称"
        case .asset: "报修资产"
        case .applyUser: "故障申请人"
        case .applyDept: "申请部门"
        case .dept: "受理部门"
        case .user: "维修受理人"
        case .userPhone: "报修人联系电话"
        case .userGroup: "维修班组"
        case .division: "ERP记账部门"
        case .faultDesc: "故障描述"
        }
    }
    var source: ReferenceSource? {
        switch self {
        case .asset: .assets
        case .applyUser, .user: .users
        case .applyDept, .dept: .departments
        case .userGroup: .groups
        case .division: .divisions
        default: nil
        }
    }
}

struct FaultReportTemplate: Codable, Hashable, Identifiable {
    var id = UUID().uuidString
    var name = ""
    var faultName = ""
    var description = ""
    var asset: JSONValue = .null
    var preset = false

    var fields: Fields? = nil

    var values: Fields {
        if let fields { return fields }
        var legacy: Fields = [:]
        if !faultName.isEmpty { legacy["faultName"] = .string(faultName) }
        if !description.isEmpty { legacy["faultDesc"] = .string(description) }
        if asset != .null { legacy["asset"] = asset }
        return legacy
    }

    static let blank = FaultReportTemplate(id: "blank", name: "空白报修")
    static let presets = [
        FaultReportTemplate(id: "parts-repair", name: "配件维修", preset: true),
        FaultReportTemplate(id: "facilities-repair", name: "厂务维修", preset: true)
    ]
    static let retiredPresets: Set<String> = ["startup", "noise", "leak"]
}

struct FaultReportTemplatePage: View {
    let preview: Bool
    let isActive: Bool
    var initialAsset: JSONValue?
    private let legacyKey: String
    @EnvironmentObject private var navigation: BottomNavigationModel
    @AppStorage private var storage: Data
    @State private var chosen: FaultReportTemplate?
    @State private var editing: FaultReportTemplate?

    init(scope: String, preview: Bool, isActive: Bool, initialAsset: JSONValue? = nil) {
        self.preview = preview
        self.isActive = isActive
        self.initialAsset = initialAsset
        legacyKey = "fault-report-templates." + scope
        _storage = AppStorage(wrappedValue: Data(), "fault-report-templates.v2." + scope)
    }

    private var templates: [FaultReportTemplate] {
        let decoder = JSONDecoder()
        if let saved = try? decoder.decode([FaultReportTemplate].self, from: storage) { return saved }
        let oldData = UserDefaults.standard.data(forKey: legacyKey) ?? Data()
        let previous = (try? decoder.decode([FaultReportTemplate].self, from: oldData)) ?? []
        return FaultReportTemplate.presets + previous.filter { !FaultReportTemplate.retiredPresets.contains($0.id) }
    }

    var body: some View {
        List {
            if let initialAsset {
                Section { Text([initialAsset["assetCode"].text, initialAsset["assetName"].text].filter { !$0.isEmpty }.joined(separator: " · ")) }
            }
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
        .modifier(TemplateTabVisibility(fromAsset: initialAsset != nil))
        .navigationDestination(item: $chosen) { template in
            OriginalFaultForm(preview: preview, initialAsset: initialAsset ?? template.values["asset"],
                              onSaved: { navigation.refresh = UUID() }, isActive: isActive, template: template)
                .navigationBarBackButtonHidden()
                .modifier(TemplateTabVisibility(fromAsset: initialAsset != nil))
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

private struct TemplateTabVisibility: ViewModifier {
    let fromAsset: Bool
    func body(content: Content) -> some View {
        if fromAsset { content.modifier(DetailTabVisibility()) }
        else { content.modifier(ListTabVisibility()) }
    }
}

private struct FaultReportTemplateEditor: View {
    @Environment(\.dismiss) private var dismiss
    @State private var template: FaultReportTemplate
    @State private var fields: Fields
    @State private var checked: Set<FaultTemplateField>
    let existing: Bool
    let save: (FaultReportTemplate) -> Void

    init(template: FaultReportTemplate, existing: Bool, save: @escaping (FaultReportTemplate) -> Void) {
        _template = State(initialValue: template)
        _fields = State(initialValue: template.values)
        _checked = State(initialValue: Set(FaultTemplateField.allCases.filter { template.values[$0.rawValue] != nil }))
        self.existing = existing
        self.save = save
    }

    private var name: String { template.name.trimmingCharacters(in: .whitespacesAndNewlines) }
    private var canSave: Bool {
        !name.isEmpty && checked.allSatisfy { field in
            guard let value = fields[field.rawValue] else { return false }
            return field.source == nil ? !value.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty : !value.object.isEmpty
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("模板名称") {
                    EntryField(placeholder: "请输入模板名称", text: $template.name, limit: 64)
                }
                Section("勾选需要预填的字段") {
                    ForEach(FaultTemplateField.allCases) { field in
                        Button {
                            if !checked.insert(field).inserted { checked.remove(field) }
                        } label: {
                            Label(field.title, systemImage: checked.contains(field) ? "checkmark.square.fill" : "square")
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(checked.contains(field) ? [.isSelected] : [])
                        if checked.contains(field) { input(field) }
                    }
                }
            }
            .navigationTitle(existing ? "编辑模板" : "新建模板").navigationBarTitleDisplayMode(.inline)
            .keyboardManaged()
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("保存模板") {
                        template.name = name
                        template.fields = fields.filter { key, _ in checked.contains { $0.rawValue == key } }
                        template.faultName = ""; template.description = ""; template.asset = .null
                        save(template)
                        dismiss()
                    }.disabled(!canSave)
                }
            }
        }
    }

    @ViewBuilder private func input(_ field: FaultTemplateField) -> some View {
        if let source = field.source {
            ReferenceField(title: "选择" + field.title, source: source, value: Binding(
                get: { fields[field.rawValue] ?? .null }, set: { fields[field.rawValue] = $0 }
            ))
        } else {
            let text = Binding(get: { fields[field.rawValue]?.text ?? "" }, set: { fields[field.rawValue] = .string($0) })
            switch field {
            case .faultDesc:
                EntryField(placeholder: "故障描述", text: text, limit: 255, axis: .vertical, lines: 3...6)
            case .userPhone:
                EntryField(placeholder: "报修人联系电话", text: text, limit: 32, keyboard: .phonePad)
            default:
                EntryField(placeholder: field.title, text: text, limit: 64)
            }
        }
    }
}
