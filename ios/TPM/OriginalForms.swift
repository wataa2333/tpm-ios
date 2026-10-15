import SwiftUI
import PhotosUI
import UIKit

@MainActor
final class OriginalFormModel: ObservableObject {
    @Published var data: Fields = [:]
    @Published var assignees: [Fields] = []
    @Published var selections: [String: [String]] = [:]
    @Published var loading = true
    @Published var saving = false
    @Published var error: String?
    @Published var ready = false
    let kind: String
    init(kind: String) { self.kind = kind }
    func load(_ session: LiveSession, existing: Fields? = nil, referenceSn: String? = nil) async {
        loading = true; ready = false; error = nil
        defer { loading = false }
        do {
            if let existing {
                data = existing
                if kind == "ERP_OUTBOUND" {
                    data["erpMaterialOutbounds"] = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": existing.text("outboundId")])
                }
            }
            else {
                let user = try await session.request("user/getUserDeptByUserId", query: ["userId": session.authentication.text("id")])
                data = ["assignees": .object([:])]
                if kind == "ASSET_FAULT" {
                    data["applyUser"] = user
                    data["applyDept"] = user["depts"].array.first ?? .null
                    data["userPhone"] = user["mobile"]
                    data["faultTime"] = .string(serverDate(Date()))
                    data["albums"] = .array([])
                } else {
                    data["user"] = user; data["recipientUser"] = user
                    data["dept"] = user["depts"].array.first ?? .null
                    data["applyDate"] = .string(serverDate(Date(), dateOnly: true))
                    data["outboundTime"] = .string(serverDate(Date()))
                    data["erpMaterialOutbounds"] = .array([])
                    if let referenceSn { data["referenceSn"] = .string(referenceSn) }
                }
            }
            let result = try await session.request("workflow/getAssigneeElementsByProcessCategory", query: ["processCategory": kind])
            assignees = result.array.map(\.object)
            var existingAssignments = data["assignees"]?.object ?? [:]
            if let raw = data["assignees"]?.text.data(using: .utf8), let decoded = try? JSONDecoder().decode(JSONValue.self, from: raw) { existingAssignments = decoded.object }
            for node in assignees {
                let key = node.text("variableName")
                let raw = existingAssignments[key] ?? node["assignees"] ?? .null
                selections[key] = usernames(raw)
            }
            ready = true
        } catch { self.error = error.localizedDescription }
    }
    func validationError() -> String? {
        let fields = kind == "ASSET_FAULT"
            ? [("faultName", "报修名称"), ("asset", "报修资产"), ("faultTime", "故障时间"), ("applyUser", "故障申请人"), ("applyDept", "申请部门"), ("dept", "受理部门"), ("user", "维修受理人"), ("userPhone", "报修人联系电话"), ("userGroup", "维修班组"), ("division", "ERP记账部门")]
            : [("user", "申请人"), ("applyDate", "申请日期"), ("projectCode", "项目号"), ("recipientUser", "领用人"), ("dept", "领用部门"), ("division", "ERP记账部门"), ("outboundTime", "出库时间"), ("remark", "用途")]
        for (key, label) in fields {
            let value = data[key] ?? .null
            if value == .null || (value.object.isEmpty && value.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) { return "请填写或选择\(label)" }
        }
        for node in assignees where ["ASSIGN", "SUPERVISOR"].contains(node.text("assignType")) {
            let count = selections[node.text("variableName"), default: []].count
            if count == 0 { return "请选择\(node.text("taskName"))审批人" }
            if isCountersign(node) && count < 2 { return "\(node.text("taskName"))请至少选择两位会签审批人" }
        }
        if kind == "ERP_OUTBOUND" {
            let lines = data["erpMaterialOutbounds"]?.array ?? []
            if lines.isEmpty { return "请选择出库物料" }
            for line in lines {
                let quantity = line["entryQuantity"].number == 0 ? 1 : line["entryQuantity"].number
                if quantity < 0 || quantity > line["material"]["onhandQty"].number { return "\(line["material"]["materialDesc"].text)：申请数量不能大于库存数量" }
            }
        }
        return nil
    }
    func payload(committed: Bool) -> Fields {
        var result = data
        result["committed"] = .bool(committed)
        var assignments: Fields = [:]
        for node in assignees {
            let key = node.text("variableName"), names = selections[key, default: []]
            if ["ASSIGN", "SUPERVISOR"].contains(node.text("assignType")) {
                assignments[key] = .string(isCountersign(node) ? names.joined(separator: ",") : names.first ?? "")
            } else if node.text("assignType") == "CANDIDATE_USERS" { assignments[key] = node["assignees"] ?? .array(names.map(JSONValue.string)) }
        }
        result["assignees"] = .object(assignments)
        if kind == "ERP_OUTBOUND" {
            result["erpMaterialOutbounds"] = .array((result["erpMaterialOutbounds"]?.array ?? []).map { value in
                var line = value.object
                if line["entryQuantity"]?.number == 0 { line["entryQuantity"] = .number(1) }
                return .object(line)
            })
        }
        return result
    }
    func addMaterial(_ material: JSONValue) {
        var lines = data["erpMaterialOutbounds"]?.array ?? []
        guard !lines.contains(where: { $0["material"]["id"] == material["id"] }) else { error = "已选择此出库物料"; return }
        lines.append(.object(["material": material, "id": .object(["imaterialId": material["id"], "outboundId": data["outboundId"] ?? .null]), "entryQuantity": .number(1)]))
        data["erpMaterialOutbounds"] = .array(lines)
    }
}

func serverDate(_ date: Date, dateOnly: Bool = false) -> String {
    let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = dateOnly ? "yyyy-MM-dd" : "yyyy-MM-dd HH:mm"
    return formatter.string(from: date)
}
func parsedDate(_ value: JSONValue) -> Date {
    if value.array.count >= 5 {
        let v = value.array.map { Int($0.number) }
        return Calendar.current.date(from: DateComponents(year: v[0], month: v[1], day: v[2], hour: v[3], minute: v[4], second: v.count > 5 ? v[5] : 0)) ?? Date()
    }
    let formatter = DateFormatter(); formatter.locale = Locale(identifier: "en_US_POSIX")
    for format in ["yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"] { formatter.dateFormat = format; if let d = formatter.date(from: value.text) { return d } }
    if let d = ISO8601DateFormatter().date(from: value.text) { return d }
    return Date()
}
func usernames(_ value: JSONValue) -> [String] {
    if !value.array.isEmpty { return value.array.map { $0.object.isEmpty ? $0.text : $0["userName"].text }.filter { !$0.isEmpty } }
    return value.text.replacingOccurrences(of: "[", with: "").replacingOccurrences(of: "]", with: "").replacingOccurrences(of: "\"", with: "").split(whereSeparator: { ",，;； ".contains($0) }).map(String.init)
}
func isCountersign(_ node: Fields) -> Bool {
    ["countersign", "countersignTask", "countersignEnabled", "multiInstance"].contains { node[$0]?.bool == true }
        || ["COUNTERSIGN", "ALL", "ANY"].contains(node.text("approvalMode").uppercased())
        || node.text("documentation").contains("会签节点") || node.text("description").contains("会签节点")
}

enum ReferenceSource: String, Identifiable {
    case assets, users, workflowUsers, departments, divisions, groups, projects, materials, faultTypes, outboundMaterials
    var id: String { rawValue }
    var title: String {
        switch self { case .assets: "选择资产"; case .users, .workflowUsers: "选择人员"; case .departments: "选择部门"; case .divisions: "ERP记账部门"; case .groups: "选择维修班组"; case .projects: "选择项目"; case .materials: "选择出库物料"; case .faultTypes: "选择故障类型"; case .outboundMaterials: "选择已出库物料" }
    }
    var pagePath: String? {
        switch self { case .assets: "asset/pageAsset"; case .materials: "erpOutbound/pageErpMaterial"; case .outboundMaterials: "erpOutbound/pageErpMaterialOutbound"; default: nil }
    }
    var searchField: String {
        switch self { case .assets: "assetName"; case .materials: "materialDesc"; case .outboundMaterials: "material.materialDesc"; default: "" }
    }
}

struct ReferencePicker: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let source: ReferenceSource
    var multiple = false
    var selected: [String] = []
    var preview = false
    let choose: (JSONValue) -> Void
    @State private var values: [JSONValue] = []
    @State private var query = ""
    @State private var searchByCode = false
    @State private var loading = true
    @State private var loadingMore = false
    @State private var selecting = false
    @State private var last = true
    @State private var error: String?
    @State private var picked = Set<String>()
    private func identity(_ v: JSONValue) -> String { [.users, .workflowUsers].contains(source) ? v["userName"].text : v["id"].text }
    private var visible: [JSONValue] {
        (!preview && source.pagePath != nil) || query.isEmpty ? values : values.filter { $0.encodedString.localizedCaseInsensitiveContains(query) }
    }
    var body: some View {
        List {
            if source.pagePath != nil {
                Section { Toggle("按编号搜索", isOn: $searchByCode) }
            }
            ForEach(Array(visible.enumerated()), id: \.offset) { _, value in
                Button { Task { await select(value) } } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(label(value)).foregroundStyle(.primary)
                            let detail = subtitle(value)
                            if !detail.isEmpty { Text(detail).font(.subheadline).foregroundStyle(.secondary) }
                        }
                        Spacer()
                        if picked.contains(identity(value)) { Image(systemName: "checkmark") }
                    }
                }
            }
            if !last && source.pagePath != nil {
                Button { Task { await load(more: true) } } label: { HStack { Text("加载更多"); if loadingMore { ProgressView() } } }.disabled(loadingMore)
            }
            if let error { Section { Text(error).foregroundStyle(.red); Button("重试") { Task { await load() } } } }
            if !loading && visible.isEmpty && error == nil { ContentUnavailableView("暂无匹配内容", systemImage: "magnifyingglass") }
        }
        .overlay { if loading || selecting { LoadingOverlay(title: "正在加载…") } }
        .navigationTitle(source.title).navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索")
        .task { picked = Set(selected); await load() }
        .onSubmit(of: .search) { Task { await load() } }
        .onChange(of: searchByCode) { Task { await load() } }
        .toolbar { if multiple { ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } } } }
    }
    private func label(_ value: JSONValue) -> String {
        source == .outboundMaterials ? value["material"].object.label : value.object.label
    }
    private func subtitle(_ v: JSONValue) -> String {
        if source == .materials { return [v["materialCode"].text, "库存 \(materialQuantity(v["onhandQty"].text, material: v))", v["lot"].text, v["subInv"].text, v["locatorCode"].text].filter { !$0.isEmpty }.joined(separator: " · ") }
        if source == .outboundMaterials { return [v["outbound"]["outNumber"].text, v["material"]["materialCode"].text, "可用 \(materialQuantity(String(v["actualQuantity"].number-v["usedQuantity"].number), material: v["material"]))"].joined(separator: " · ") }
        return [v["assetCode"].text, v["addressCode"].text, v["userName"].text, v["projectCode"].text, v["divisionName"].text].filter { !$0.isEmpty }.joined(separator: " · ")
    }
    private func select(_ value: JSONValue) async {
        if preview { choose(value); if !multiple { dismiss() }; return }
        guard !selecting else { return }
        selecting = true
        defer { selecting = false }
        do {
            let result: JSONValue
            if source == .departments { result = try await session.request("dept/getDeptById", query: ["deptId": value["value"].text]) }
            else if source == .users { result = try await session.request("user/getUserDeptByUserId", query: ["userId": value["userId"].text]) }
            else if source == .faultTypes { result = try await session.request("faultType/getFaultTypeById", query: ["typeId": value["key"].text]) }
            else { result = value }
            choose(result)
            if multiple {
                if [.materials, .outboundMaterials].contains(source) { picked.insert(identity(value)) }
                else if picked.contains(identity(value)) { picked.remove(identity(value)) }
                else { picked.insert(identity(value)) }
            } else { dismiss() }
        } catch { self.error = error.localizedDescription }
    }
    private func load(more: Bool = false) async {
        if more { loadingMore = true } else { loading = true }
        error = nil
        defer { loading = false; loadingMore = false }
        if preview { values = PreviewReferenceData.values(source); last = true; return }
        do {
            let response: JSONValue
            if let path = source.pagePath {
                var filters: [JSONValue] = []
                if source == .materials { filters.append(.object(["field": .string("onhandQty"), "operator": .string("gt"), "value": .number(0)])) }
                if !query.isEmpty {
                    let field = searchByCode ? (source == .assets ? "assetCode" : source == .materials ? "materialCode" : "material.materialCode") : source.searchField
                    filters.append(filter(field, query, "like"))
                }
                response = try await session.page(path, offset: more ? values.count : 0, filters: filters)
                let rows = response["content"].array
                values = more ? values + rows : rows
                last = response["last"].bool
            } else {
                switch source {
                case .users: response = try await session.request("user/getEnabledUsers")
                case .workflowUsers: response = try await session.request("workflow/searchTransferUsers", query: ["keyword": query, "size": "20"])
                case .departments: response = try await session.request("dept/getDeptSimpleTree")
                case .divisions: response = try await session.request("erp/getAllDivisionByFactoryId")
                case .groups: response = try await session.request("user/getEnabledUserGroupsByGroupType", query: ["groupType": "REPAIR"])
                case .projects: response = try await session.request("erpOutbound/getProjectByCompanyCode")
                case .faultTypes: response = try await session.request("faultType/getFaultTypeTree", query: ["enabled": "true"])
                default: response = .array([])
                }
                func flatten(_ rows: [JSONValue]) -> [JSONValue] {
                    rows.flatMap { item -> [JSONValue] in
                        let children = flatten(item["children"].array)
                        return (item["disabled"].bool || item["value"].text == "root" || (source == .faultTypes && item["type"].text != "FaultType") ? [] : [item]) + children
                    }
                }
                values = [.departments, .faultTypes].contains(source) ? flatten(response.array) : response.array
            }
        } catch { self.error = error.localizedDescription }
    }
}

struct RequiredLabel: View {
    let title: String
    var body: some View {
        HStack(spacing: 3) { Text(title); Text("＊").foregroundStyle(.red) }
            .accessibilityElement(children: .ignore).accessibilityLabel(title + "，必填")
    }
}

struct RequiredFieldsNotice: View {
    var body: some View {
        HStack(spacing: 3) { Text("＊").foregroundStyle(.red); Text("为必填项") }
            .font(.footnote).foregroundStyle(.secondary)
    }
}

struct RequiredFormHeader: View {
    let title: String
    var body: some View {
        HStack { Text(title); Spacer(); RequiredFieldsNotice().textCase(nil) }
    }
}

struct ReferenceField: View {
    let title: String
    let source: ReferenceSource
    @Binding var value: JSONValue
    var required = false
    var selected: ((JSONValue) -> Void)? = nil
    var body: some View {
        NavigationLink {
            ReferencePicker(source: source) { result in value = result; selected?(result) }
        } label: {
            LabeledContent { Text(value.object.isEmpty ? "请选择" : value.object.label) }
            label: { if required { RequiredLabel(title: title) } else { Text(title) } }
        }
        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
    }
}

struct ServerDateField: View {
    let title: String
    @Binding var value: JSONValue
    var dateOnly = false
    var required = false
    var body: some View {
        DatePicker(selection: Binding(get: { parsedDate(value) }, set: { value = .string(serverDate($0, dateOnly: dateOnly)) }), displayedComponents: dateOnly ? [.date] : [.date, .hourAndMinute]) {
            if required { RequiredLabel(title: title) } else { Text(title) }
        }
        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
    }
}

struct AssigneesFields: View {
    let elements: [Fields]
    @Binding var selections: [String: [String]]
    var body: some View {
        ForEach(Array(elements.enumerated()), id: \.offset) { _, element in
            if ["ASSIGN", "SUPERVISOR"].contains(element.text("assignType")) {
                let key = element.text("variableName")
                NavigationLink {
                    ReferencePicker(source: .users, multiple: isCountersign(element) || element["assigneeMultiple"]?.bool == true, selected: selections[key, default: []]) { user in
                        let name = user["userName"].text
                        if isCountersign(element) || element["assigneeMultiple"]?.bool == true {
                            if selections[key, default: []].contains(name) { selections[key]?.removeAll { $0 == name } }
                            else { selections[key, default: []].append(name) }
                        } else { selections[key] = [name] }
                    }
                } label: {
                    LabeledContent { Text(selections[key, default: []].isEmpty ? "请选择审批人" : selections[key, default: []].joined(separator: "、")) }
                    label: { RequiredLabel(title: element.text("taskName")) }
                }
                .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
                .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
            }
        }
    }
}

struct OriginalFaultForm: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = OriginalFormModel(kind: "ASSET_FAULT")
    @State private var confirming: Bool? = nil
    @State private var showConfirmation = false
    @State private var showValidation = false
    @State private var photoUploading = false
    var existing: Fields? = nil
    var preview = false
    var onSaved: (() -> Void)? = nil
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { model.data[key] ?? .null }, set: { model.data[key] = $0 }) }
    private func text(_ key: String) -> Binding<String> { Binding(get: { model.data.text(key) }, set: { model.data[key] = .string($0) }) }
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    RequiredLabel(title: "报修名称")
                    TextField("请输入报修名称", text: text("faultName")).onChange(of: model.data.text("faultName")) { _, value in if value.count > 64 { model.data["faultName"] = .string(String(value.prefix(64))) } }
                }
                ReferenceField(title: "报修资产", source: .assets, value: field("asset"), required: true)
                ServerDateField(title: "故障时间", value: field("faultTime"), required: true)
            } header: { RequiredFormHeader(title: "故障报修") }
            Section("申请与受理") {
                ReferenceField(title: "故障申请人", source: .users, value: field("applyUser"), required: true) { user in
                    model.data["applyDept"] = user["depts"].array.first ?? .null
                }
                ReferenceField(title: "申请部门", source: .departments, value: field("applyDept"), required: true)
                ReferenceField(title: "受理部门", source: .departments, value: field("dept"), required: true) { dept in model.data["user"] = dept["acceptUser"] }
                ReferenceField(title: "维修受理人", source: .users, value: field("user"), required: true)
                VStack(alignment: .leading, spacing: 8) { RequiredLabel(title: "报修人联系电话"); TextField("请输入联系电话", text: text("userPhone")).keyboardType(.phonePad) }
                ReferenceField(title: "维修班组", source: .groups, value: field("userGroup"), required: true)
                ReferenceField(title: "ERP记账部门", source: .divisions, value: field("division"), required: true)
            }
            if model.assignees.contains(where: { ["ASSIGN", "SUPERVISOR"].contains($0.text("assignType")) }) {
                Section("审批人") { AssigneesFields(elements: model.assignees, selections: $model.selections) }
            }
            Section("故障描述") { TextField("请输入故障描述", text: text("faultDesc"), axis: .vertical).lineLimit(3...6).onChange(of: model.data.text("faultDesc")) { _, value in if value.count > 255 { model.data["faultDesc"] = .string(String(value.prefix(255))) } } }
            Section("现场照片") { OriginalPhotosField(albums: field("albums"), directory: "asset_fault", preview: preview, uploading: $photoUploading) }
            if let error = model.error { Section { Text(error).foregroundStyle(.red); if !model.ready && !model.loading { Button("重新加载") { Task { await model.load(session, existing: existing) } } } } }
            if preview { Section { Text("原包字段预览，未连接服务器").font(.footnote).foregroundStyle(.secondary) } }
        }
        .disabled(model.loading || model.saving)
        .overlay { if model.loading || model.saving { LoadingOverlay(title: model.saving ? "正在提交报修…" : "正在加载表单…") } }
        .navigationTitle(existing == nil ? "故障报修" : "编辑报修").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(model.saving || photoUploading) }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            DocumentActions(busy: model.saving, submitting: confirming == true,
                            disabled: !model.ready || photoUploading,
                            save: { prepare(false) }, submit: { prepare(true) })
        }
        .task {
            if preview { model.loading = false; model.ready = true; model.data = ["faultTime": .string("2026-10-16 09:30"), "albums": .array([]), "applyUser": PreviewIdentity.user, "applyDept": PreviewIdentity.department, "userPhone": .string("13800000000")] }
            else { await model.load(session, existing: existing) }
        }
        .confirmationDialog(confirming == true ? "提交报修单到原系统？" : "保存报修单到原系统？", isPresented: $showConfirmation, titleVisibility: .visible) {
            Button(confirming == true ? "提交" : "保存") { Task { await save() } }
        }
        .alert("请检查表单", isPresented: $showValidation) { Button("好", role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
    }
    private func prepare(_ committed: Bool) {
        if let error = model.validationError() { model.error = error; showValidation = true; return }
        confirming = committed; showConfirmation = true
    }
    private func save() async {
        guard !preview, !model.saving else { return }
        model.saving = true; model.error = nil
        defer { model.saving = false }
        do {
            let path = existing == nil ? "fault/addAssetFault" : "fault/updateAssetFault"
            var payload = model.payload(committed: confirming == true)
            if existing != nil { payload["taskInfo"] = .null }
            _ = try await session.request(path, body: .object(payload))
            onSaved?(); dismiss()
        } catch { model.error = error.localizedDescription }
    }
}

struct OriginalPhotosField: View {
    @EnvironmentObject private var session: LiveSession
    @Binding var albums: JSONValue
    let directory: String
    var preview = false
    @State private var selected: [PhotosPickerItem] = []
    @Binding var uploading: Bool
    @State private var error: String?
    var body: some View {
        ForEach(Array(albums.array.enumerated()), id: \.offset) { index, image in
            HStack {
                Text(image["name"].text.isEmpty ? "照片 \(index+1)" : image["name"].text)
                Spacer()
                Button("删除", role: .destructive) { var values = albums.array; values.remove(at: index); albums = .array(values) }
            }
        }
        PhotosPicker(selection: $selected, maxSelectionCount: max(0, 9-albums.array.count), matching: .images) { Label("选择现场照片", systemImage: "photo.badge.plus") }.disabled(uploading || preview || albums.array.count >= 9)
        .onChange(of: selected) { Task { await upload() } }
        if uploading { ProgressView("正在上传照片…") }
        if let error { Text(error).foregroundStyle(.red) }
    }
    private func upload() async {
        guard !preview, !selected.isEmpty, !uploading else { return }
        uploading = true; error = nil
        defer { uploading = false; selected = [] }
        do {
            for item in selected {
                guard let data = try await item.loadTransferable(type: Data.self), let image = UIImage(data: data), let jpeg = image.jpegData(compressionQuality: 0.9) else { throw APIError(message: "无法读取照片") }
                let uploaded = try await session.uploadPhoto(jpeg, directory: directory)
                albums = .array(albums.array + [uploaded])
            }
        } catch { self.error = error.localizedDescription }
    }
}

struct OriginalERPForm: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    @StateObject private var model = OriginalFormModel(kind: "ERP_OUTBOUND")
    @State private var selecting = false
    @State private var committed = false
    @State private var confirming = false
    @State private var showValidation = false
    var existing: Fields? = nil
    var referenceSn: String? = nil
    var preview = false
    var onSaved: ((JSONValue) -> Void)? = nil
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { model.data[key] ?? .null }, set: { model.data[key] = $0 }) }
    private func text(_ key: String) -> Binding<String> { Binding(get: { model.data.text(key) }, set: { model.data[key] = .string($0) }) }
    private func quantity(_ index: Int) -> Binding<Double> {
        Binding(get: { (model.data["erpMaterialOutbounds"]?.array ?? [])[index]["entryQuantity"].number }, set: { number in
            var rows = model.data["erpMaterialOutbounds"]?.array ?? []; var line = rows[index].object
            line["entryQuantity"] = .number(number); rows[index] = .object(line); model.data["erpMaterialOutbounds"] = .array(rows)
        })
    }
    var body: some View {
        Form {
            Section {
                ReferenceField(title: "申请人", source: .users, value: field("user"), required: true)
                ServerDateField(title: "申请日期", value: field("applyDate"), dateOnly: true, required: true)
                ReferenceField(title: "项目号", source: .projects, value: field("project"), required: true) { project in model.data["projectCode"] = project["projectCode"]; model.data["projectDesc"] = project["projectName"] }
                LabeledContent("关联单号", value: model.data.text("referenceSn"))
                ReferenceField(title: "领用人", source: .users, value: field("recipientUser"), required: true)
                ReferenceField(title: "领用部门", source: .departments, value: field("dept"), required: true)
                ReferenceField(title: "ERP记账部门", source: .divisions, value: field("division"), required: true)
                ServerDateField(title: "出库时间", value: field("outboundTime"), required: true)
            } header: { RequiredFormHeader(title: "领料申请") }
            Section {
                Button("添加出库物料", systemImage: "plus") { selecting = true }.disabled(preview)
                ForEach(Array((model.data["erpMaterialOutbounds"]?.array ?? []).enumerated()), id: \.offset) { index, line in
                    DisclosureGroup {
                        MaterialFacts(material: line["material"])
                        LabeledContent("库存数量", value: materialQuantity(line["material"]["onhandQty"].text, material: line["material"]))
                        HStack { RequiredLabel(title: "申请数量"); Spacer(); TextField("数量", value: quantity(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 70); Text(materialUnit(line["material"])).foregroundStyle(.secondary) }
                        Button("删除明细", role: .destructive) { var rows = model.data["erpMaterialOutbounds"]?.array ?? []; rows.remove(at: index); model.data["erpMaterialOutbounds"] = .array(rows) }
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(line["material"]["materialDesc"].text)
                            Text(line["material"]["materialCode"].text).font(.subheadline).foregroundStyle(.secondary)
                            Text("申请数量：\(materialQuantity(line["entryQuantity"].text, material: line["material"]))").font(.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { RequiredLabel(title: "出库物料 · \(model.data["erpMaterialOutbounds"]?.array.count ?? 0) 项") }
            Section { TextField("请输入用途", text: text("remark"), axis: .vertical).lineLimit(3...6) } header: { RequiredLabel(title: "用途") }
            if model.assignees.contains(where: { ["ASSIGN", "SUPERVISOR"].contains($0.text("assignType")) }) {
                Section("审批人") { AssigneesFields(elements: model.assignees, selections: $model.selections) }
            }
            if let error = model.error { Section { Text(error).foregroundStyle(.red); if !model.ready && !model.loading { Button("重试") { Task { await model.load(session, existing: existing, referenceSn: referenceSn) } } } } }
            if preview { Section { Text("原包字段预览，未连接服务器").font(.footnote).foregroundStyle(.secondary) } }
        }
        .disabled(model.loading || model.saving)
        .overlay { if model.loading || model.saving { LoadingOverlay(title: model.saving ? "正在提交领料申请…" : "正在加载领料申请…") } }
        .navigationTitle("ERP 出库").navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(model.saving) }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            DocumentActions(busy: model.saving, submitting: committed,
                            disabled: !model.ready,
                            save: { prepare(false) }, submit: { prepare(true) })
        }
        .sheet(isPresented: $selecting) { NavigationStack { ReferencePicker(source: .materials, multiple: true) { model.addMaterial($0) } } }
        .task {
            if preview {
                model.loading = false; model.ready = true
                model.data = ["applyDate": .string("2026-10-15"), "outboundTime": .string("2026-10-15 09:30"), "erpMaterialOutbounds": .array([
                    .object(["material": .object(["id": .number(1), "materialCode": .string("WL-10028"), "materialDesc": .string("深沟球轴承"), "onhandQty": .number(24), "lot": .string("LOT-01"), "subInv": .string("备件仓")]), "entryQuantity": .number(2)]),
                    .object(["material": .object(["id": .number(2), "materialCode": .string("WL-10046"), "materialDesc": .string("空气滤芯"), "onhandQty": .number(8), "lot": .string("LOT-02"), "subInv": .string("备件仓")]), "entryQuantity": .number(1)])])]
                model.data["user"] = PreviewIdentity.user; model.data["recipientUser"] = PreviewIdentity.user; model.data["dept"] = PreviewIdentity.department
                model.data["erpMaterialOutbounds"] = .array(PreviewBusinessData.erpLines)
                if let referenceSn { model.data["referenceSn"] = .string(referenceSn) }
            } else {
                await model.load(session, existing: existing, referenceSn: referenceSn)
                if let existing { model.data["project"] = .object(["projectCode": existing["projectCode"] ?? .null, "projectName": existing["projectDesc"] ?? .null]) }
            }
        }
        .confirmationDialog(committed ? "提交 ERP 领料单到原系统？" : "保存 ERP 领料单到原系统？", isPresented: $confirming, titleVisibility: .visible) { Button(committed ? "提交" : "保存") { Task { await save() } } }
        .alert("请检查表单", isPresented: $showValidation) { Button("好", role: .cancel) { model.error = nil } } message: { Text(model.error ?? "") }
    }
    private func prepare(_ value: Bool) { if let error = model.validationError() { model.error = error; showValidation = true; return }; committed = value; confirming = true }
    private func save() async {
        guard !preview, !model.saving else { return }
        model.saving = true; model.error = nil
        defer { model.saving = false }
        do {
            var payload = model.payload(committed: committed); payload.removeValue(forKey: "project")
            let result = try await session.request(existing == nil ? "erpOutbound/addErpOutbound" : "erpOutbound/updateErpOutbound", body: .object(payload))
            onSaved?(result); dismiss()
        } catch { model.error = error.localizedDescription }
    }
}

struct DocumentActions: View {
    let busy: Bool
    let submitting: Bool
    let disabled: Bool
    var save: (() -> Void)? = nil
    let submit: () -> Void
    var body: some View {
        HStack(alignment: .center) {
            if let save { BottomAction(title: "保存", symbol: "tray.and.arrow.down", color: Color(red: 0.92, green: 0.73, blue: 0.24), busy: busy && !submitting, action: save) }
            Spacer()
            BottomAction(title: "提交", symbol: "checkmark.circle", color: .blue, busy: busy && submitting, action: submit)
        }
        .disabled(busy || disabled)
        .padding(.horizontal, 20).padding(.vertical, 8)
    }
}

struct MaterialFacts: View {
    let material: JSONValue
    var body: some View {
        LabeledContent("物料编码", value: material["materialCode"].text)
        LabeledContent("ERP库存组织", value: material["erpTpmCompany"]["organizationName"].text)
        LabeledContent("批次", value: material["lot"].text)
        LabeledContent("子库", value: material["subInv"].text)
        LabeledContent("货位名称", value: material["locatorCode"].text)
        LabeledContent("货位描述", value: material["locatorDesc"].text)
        LabeledContent("单位", value: materialUnit(material))
    }
}

func materialUnit(_ material: JSONValue) -> String {
    for path in ["uom.uomDesc", "uom.uomCode", "uomDesc", "uomCode"] {
        let unit = material.at(path).text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !unit.isEmpty { return unit }
    }
    return "PCS"
}

func materialQuantity(_ quantity: String, material: JSONValue) -> String {
    quantity + " " + materialUnit(material)
}

enum PreviewReferenceData {
    static func values(_ source: ReferenceSource) -> [JSONValue] {
        switch source {
        case .assets:
            return ["立式加工中心", "螺杆空压机", "数控车床"].enumerated().map { .object(["id": .number(Double($0.offset+1)), "assetName": .string($0.element), "assetCode": .string("ZC-DEMO-00\($0.offset+1)")]) }
        case .users, .workflowUsers:
            return ["示例维修人", "示例申请人", "示例审批人"].enumerated().map { .object(["userId": .number(Double($0.offset+1)), "userName": .string("demo\($0.offset+1)"), "realName": .string($0.element)]) }
        case .departments:
            return ["示例设备部", "示例生产部"].enumerated().map { .object(["value": .number(Double($0.offset+1)), "deptName": .string($0.element)]) }
        case .groups:
            return [.object(["groupId": .number(1), "groupName": .string("示例维修班组")])]
        case .divisions:
            return [.object(["divisionId": .number(1), "divisionDesc": .string("示例 ERP 记账部门")])]
        case .projects:
            return [.object(["projectCode": .string("P-DEMO-001"), "projectName": .string("示例维修项目")])]
        case .materials:
            return PreviewBusinessData.erpLines.map { $0["material"] }
        case .faultTypes:
            return ["机械故障", "电气故障"].enumerated().map { .object(["key": .string(String($0.offset+1)), "typeName": .string($0.element)]) }
        case .outboundMaterials:
            return PreviewBusinessData.repairMaterials.map { var line = $0["materialOutbound"].object; line["outbound"] = $0["outbound"]; return .object(line) }
        }
    }
}

enum PreviewBusinessData {
    static let erpLines: [JSONValue] = [
        .object(["material": .object(["id": .number(1), "materialCode": .string("WL-10028"), "materialDesc": .string("深沟球轴承"), "onhandQty": .number(24), "uom": .object(["uomDesc": .string("个")]), "lot": .string("LOT-01"), "subInv": .string("备件仓")]), "entryQuantity": .number(2)]),
        .object(["material": .object(["id": .number(2), "materialCode": .string("WL-10046"), "materialDesc": .string("空气滤芯"), "onhandQty": .number(8), "uom": .object(["uomDesc": .string("件")]), "lot": .string("LOT-02"), "subInv": .string("备件仓")]), "entryQuantity": .number(1)])
    ]
    static var repairMaterials: [JSONValue] {
        erpLines.map { line in
            var outboundLine = line.object
            outboundLine["actualQuantity"] = line["entryQuantity"]
            outboundLine["usedQuantity"] = .number(0)
            return .object(["material": line["material"], "materialOutbound": .object(outboundLine), "outbound": .object(["outNumber": .string("CK-DEMO-001"), "recipientUser": .object(["realName": .string("示例维修人")])]), "useNum": line["entryQuantity"]])
        }
    }
}
