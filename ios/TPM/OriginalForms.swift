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
    private var loadRevision = UUID()
    let kind: String
    init(kind: String) { self.kind = kind }
    func selectReceiverDepartment(_ department: JSONValue) {
        var updated = data
        updated["dept"] = department
        updated["user"] = department["acceptUser"]
        data = updated
    }
    func load(_ session: LiveSession, existing: Fields? = nil, referenceSn: String? = nil) async {
        guard !Task.isCancelled else { return }
        let revision = UUID()
        loadRevision = revision
        loading = true; ready = false; error = nil
        defer { if loadRevision == revision { loading = false } }
        do {
            if let existing {
                data = existing
                if kind == "ERP_OUTBOUND" {
                    let materials = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": existing.text("outboundId")])
                    guard loadRevision == revision, !Task.isCancelled else { return }
                    data["erpMaterialOutbounds"] = materials
                }
            }
            else {
                let user = try await session.request("user/getUserDeptByUserId", query: ["userId": session.authentication.text("id")])
                guard loadRevision == revision, !Task.isCancelled else { return }
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
            guard loadRevision == revision, !Task.isCancelled else { return }
            assignees = result.array.map(\.object)
            selections = [:]
            var existingAssignments = data["assignees"]?.object ?? [:]
            if let raw = data["assignees"]?.text.data(using: .utf8), let decoded = try? JSONDecoder().decode(JSONValue.self, from: raw) { existingAssignments = decoded.object }
            for node in assignees {
                let key = node.text("variableName")
                let raw = existingAssignments[key] ?? node["assignees"] ?? .null
                selections[key] = usernames(raw)
            }
            ready = true
        } catch {
            guard loadRevision == revision, !Task.isCancelled, !isCancellation(error) else { return }
            self.error = error.localizedDescription
        }
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
        result["assignees"] = .object(assigneesPayload(nodes: assignees, selections: selections))
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
        if lines.contains(where: { $0["material"]["id"] == material["id"] }) {
            lines.removeAll { $0["material"]["id"] == material["id"] }
            data["erpMaterialOutbounds"] = .array(lines); return
        }
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
    /// What a list the server searches can be searched by. The outbound lines (picked for a repair's ERP
    /// material) also take the material number, the recipient's name and the outbound number, as the original does.
    var searchModes: [PickerSearch] {
        switch self {
        case .assets, .materials: [.name, .code]
        case .outboundMaterials: [.name, .code, .recipient, .outNumber]
        default: []
        }
    }
}

enum PickerSearch: String, Identifiable, Hashable {
    case name, code, recipient, outNumber
    var id: String { rawValue }
    func label(for source: ReferenceSource) -> String {
        switch self {
        case .name: source == .assets ? "名称" : "物料名称"
        case .code: source == .assets ? "资产编码" : "料号"
        case .recipient: "领用人"
        case .outNumber: "出库单号"
        }
    }
    func field(for source: ReferenceSource) -> String {
        switch (self, source) {
        case (.name, _): return source.searchField
        case (.code, .assets): return "assetCode"
        case (.code, .materials): return "materialCode"
        case (.code, _): return "material.materialCode"
        case (.recipient, _): return "outbound.recipientUser.realName"
        case (.outNumber, _): return "outbound.outNumber"
        }
    }
}

struct ReferencePicker: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let source: ReferenceSource
    var multiple = false
    var selected: [String] = []
    var preview = false
    /// The form field this page fills ("fault.dept"): lets "设为默认" remember a choice for it.
    var defaultField: String? = nil
    let choose: (JSONValue) -> Void
    @StateObject private var menu = ReferenceMenuModel()
    @State private var values: [JSONValue] = []
    @State private var query = ""
    @State private var searchMode: PickerSearch = .name
    @State private var searching = false
    @State private var userMode = 0
    @State private var userGroups: [JSONValue] = []
    @State private var loading = true
    @State private var loadingMore = false
    @State private var rawOffset = 0
    @State private var loadGeneration = UUID()
    @State private var pagingNotice: String?
    @State private var selecting = false
    @State private var last = true
    @State private var error: String?
    @State private var picked = Set<String>()
    private func identity(_ v: JSONValue) -> String {
        if [.users, .workflowUsers].contains(source) { return v["userName"].text }
        // An outbound material line is identified by (outboundId, materialId); its "id" is an object whose
        // text is empty, which made every row share one identity and tick together.
        if source == .outboundMaterials { return outboundLineIdentity(v) }
        return referenceKey(source, v)
    }
    @State private var hideUsedMaterials = true
    @State private var notice: String?
    private func available(_ v: JSONValue) -> Double { v["actualQuantity"].number - v["usedQuantity"].number }
    private var visible: [JSONValue] {
        let values = source == .outboundMaterials && hideUsedMaterials ? self.values.filter { available($0) > 0 } : self.values
        return (!preview && source.pagePath != nil) || query.isEmpty ? values : [.faultTypes, .departments].contains(source) ? matchingFaultTypes(values, query: query) : values.filter { $0.encodedString.localizedCaseInsensitiveContains(query) }
    }
    var body: some View {
        List {
            if source == .outboundMaterials { Toggle("隐藏已领用完物料", isOn: $hideUsedMaterials) }
            if source == .users {
                Section {
                    Picker("人员分类", selection: $userMode) {
                        Text("按姓名").tag(0)
                        Text("按班组").tag(1)
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Color.clear)
                    .listRowSeparator(.hidden)
                }
            }
            if !source.searchModes.isEmpty {
                // Always there for the lists the server searches, so the ways of searching are never hidden.
                Section { Picker("搜索方式", selection: $searchMode) { ForEach(source.searchModes) { Text($0.label(for: source)).animatedTextValue($0.label(for: source)).tag($0) } }.pickerStyle(.segmented).labelsHidden().listRowBackground(Color.clear).listRowSeparator(.hidden) }
            }
            if menu.enabled, query.isEmpty, !menu.pins.isEmpty {
                Section { ForEach(menu.pins) { entry in optionRow(entry.value) } } header: { Text("置顶 · \(menu.pins.count)/\(PickerLists.pinLimit)").animatedTextValue("置顶 · \(menu.pins.count)/\(PickerLists.pinLimit)") }
            }
            if menu.enabled, query.isEmpty, !menu.recents.isEmpty {
                Section { ForEach(menu.recents) { entry in optionRow(entry.value) } } header: { Text("最近使用") }
            }
            Section {
            if source == .faultTypes {
                FaultTypeTree(nodes: visible) { value in Task { await select(value) } }
            } else if source == .departments {
                DepartmentTree(nodes: visible) { value in Task { await select(value) } }
            } else if source == .users && userMode == 1 {
                ForEach(Array(userGroups.enumerated()), id: \.offset) { _, group in
                    let users = group["users"].array.filter { $0["enabled"] != .bool(false) && (query.isEmpty || group["groupName"].text.localizedCaseInsensitiveContains(query) || ($0["realName"].text + $0["userName"].text).localizedCaseInsensitiveContains(query)) }
                    if !users.isEmpty {
                        DisclosureGroup {
                            ForEach(Array(users.enumerated()), id: \.offset) { _, user in
                                Button { Task { await select(user) } } label: { HStack { WrappingContent { Text(user.object.label).animatedTextValue(user.object.label).foregroundStyle(Color.primary) }; ReferenceBadges(value: user) }.frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect) }.buttonStyle(.plain)
                                    .referenceMenu(for: user)
                            }
                        } label: { WrappingContent { Text(group["groupName"].text).animatedTextValue(group["groupName"].text) } }
                    }
                }
            } else {
                ForEach(visible.map { PickerEntry(key: identity($0), label: $0.object.label, value: $0) }) { entry in optionRow(entry.value) }
                    .interfaceMotion(visible.map(identity))
            }
            } header: { if menu.enabled, query.isEmpty, !menu.pins.isEmpty || !menu.recents.isEmpty { Text("全部") } }
            if let message = pagingNotice ?? error {
                VStack(alignment: .leading, spacing: 8) {
                    Text(message).animatedTextValue(message).font(AppFont.footnote).foregroundStyle(.secondary)
                    Button("重新加载") { Task { await load() } }.disabled(loading || loadingMore)
                }.listRowBackground(Color.clear)
            }
            if source.pagePath != nil && !loading && pagingNotice == nil {
                LoadMoreFooter(hasMore: !last, failed: error != nil, loading: loading || loadingMore, loaded: values.count) { await load(more: true) }
            }
            if !loading && visible.isEmpty && error == nil { ContentUnavailableView("暂无匹配内容", systemImage: "magnifyingglass") }
        }
        .navigationTitle(source.title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .reloadsToTop(on: "\(query)-\(searchMode.rawValue)-\(userMode)")
        .errorDialog($notice, title: "不能选择", clearsOnClose: true)
        .errorDialog(Binding(get: { menu.notice }, set: { menu.notice = $0 }), title: "不能置顶", clearsOnClose: true)
        .environmentObject(menu)
        .searchPageChrome(isSearching: searching)
        // Only lists the server searches are asked again. The others are filtered here from what is already loaded.
        .nativeSearchable(text: $query, isPresented: $searching, prompt: source.searchModes.isEmpty ? "搜索" : "搜索" + searchMode.label(for: source), onSubmit: { if source.pagePath != nil || source == .workflowUsers { Task { await load() } } })
        .task {
            picked = Set(selected)
            // Materials are picked by quantity, not by preference: no menu there. The demo screens keep their
            // pins in memory and never record "recent" choices, so they behave the same on every launch.
            if !menu.enabled, ![.materials, .outboundMaterials].contains(source) {
                menu.configure(source: source, defaultField: defaultField, allowsRecents: !multiple && !preview,
                               scope: preview ? "preview" : session.draftScope, store: preview ? .preview : .shared)
            }
            await load()
        }
        .onChange(of: searchMode) { if source.pagePath != nil { Task { await load() } } }
        // The groups are only needed once "按班组" is chosen.
        .task(id: userMode) { await loadUserGroupsIfNeeded() }
        .toolbar {
            if loading || selecting {
                ToolbarItem(placement: .cancellationAction) { ProgressView().accessibilityLabel("正在加载") }
            }
            if multiple { ToolbarItem(placement: .confirmationAction) { Button("完成") { finishPicking() } } }
        }
        // The top "完成" is replaced by the search bar while searching, so multi-select also keeps a
        // bottom button that is always there. It says how many are ticked.
        .safeAreaInset(edge: .bottom, spacing: 0) {
            if multiple {
                Button { finishPicking() } label: {
                    Text(picked.isEmpty ? "完成" : "完成（已选 \(picked.count) 项）").font(AppFont.headline).frame(maxWidth: .infinity, minHeight: 28)
                        .contentTransition(.numericText()).interfaceMotion(picked.count, duration: 0.2)
                }
                .buttonStyle(.glassProminent).controlSize(.large)
                .padding(.horizontal, 20).padding(.vertical, 8)
                .accessibilityIdentifier("picker-done")
            }
        }
        .sensoryFeedback(.selection, trigger: picked.count)
    }
    /// One choosable row, with its tags, and the menu for swipe and long press.
    @ViewBuilder private func optionRow(_ value: JSONValue) -> some View {
        Button { Task { await select(value) } } label: {
            HStack {
                VStack(alignment: .leading, spacing: RowMetrics.spacing) {
                    HStack(spacing: 8) {
                        WrappingContent { Text(label(value)).animatedTextValue(label(value)).foregroundStyle(Color.primary) }
                        ReferenceBadges(value: value)
                    }
                    ForEach(Array(subtitleLines(value).enumerated()), id: \.offset) { _, line in
                        WrappingContent { Text(line).animatedTextValue(line).font(AppFont.subheadline).foregroundStyle(source == .outboundMaterials && available(value) < 1 ? Color.red : Color.secondary) }
                    }
                }
                Spacer()
                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                    .frame(width: 22)
                    .opacity(picked.contains(identity(value)) ? 1 : 0)
                    .accessibilityHidden(true)
                    .interfaceMotion(picked.contains(identity(value)), duration: 0.2)
            }
            // The empty space to the right of the text takes the tap too, not only the words.
            .frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect)
        }.buttonStyle(.plain).uniformRow()
            .accessibilityAddTraits(picked.contains(identity(value)) ? .isSelected : [])
            .referenceMenu(for: value)
    }
    private func finishPicking() { endEditing(); searching = false; dismiss() }
    private func label(_ value: JSONValue) -> String {
        source == .outboundMaterials ? value["material"].object.label : value.object.label
    }
    private func subtitleLines(_ v: JSONValue) -> [String] {
        if source == .materials { return [[v["materialCode"].text, "库存 \(materialQuantity(v["onhandQty"].text, material: v))", v["lot"].text, v["subInv"].text, v["locatorCode"].text].filter { !$0.isEmpty }.joined(separator: " · ")] }
        if source == .outboundMaterials {
            let outbound = v["outbound"]
            let people = [("申请人", outbound["user"]["realName"].text), ("领用人", outbound["recipientUser"]["realName"].text)].filter { !$0.1.isEmpty }.map { $0.0 + "：" + $0.1 }
            let left = available(v)
            return [[outbound["outNumber"].text, v["material"]["materialCode"].text].filter { !$0.isEmpty }.joined(separator: " · "),
                    people.joined(separator: "　"),
                    "可用 \(materialQuantity(String(left), material: v["material"]))" + (left < 1 ? "（不足 1，不能选择）" : "") + (outbound["applyDate"].text.isEmpty ? "" : " · 申请日期 " + readableDate(outbound["applyDate"]))]
                .filter { !$0.isEmpty }
        }
        let line = [v["assetCode"].text, v["addressCode"].text, v["userName"].text, v["projectCode"].text, v["divisionName"].text].filter { !$0.isEmpty }.joined(separator: " · ")
        return line.isEmpty ? [] : [line]
    }
    private func select(_ value: JSONValue) async {
        guard !selecting else { return }
        // Deselect before resolving or checking stock: a selected line must always be removable.
        if multiple && picked.contains(identity(value)) {
            choose(value)
            picked.remove(identity(value))
            return
        }
        if preview {
            choose(value)
            if multiple { picked.insert(identity(value)) } else { searching = false; dismiss() }
            return
        }
        if source == .outboundMaterials {
            if available(value) < 1 { notice = "这条出库物料的可用数量小于 1，不能选择。"; return }
        }
        selecting = true
        defer { selecting = false }
        do {
            let result = try await resolveReference(source, value, session: session)
            if !multiple { menu.record(value) }
            choose(result)
            if multiple {
                if [.materials, .outboundMaterials].contains(source) { picked.insert(identity(value)) }
                else if picked.contains(identity(value)) { picked.remove(identity(value)) }
                else { picked.insert(identity(value)) }
            } else { searching = false; dismiss() }
        } catch { self.error = error.localizedDescription }
    }
    private func load(more: Bool = false) async {
        if more && (loading || loadingMore || last) { return }
        let token = more ? loadGeneration : UUID()
        if !more { loadGeneration = token; rawOffset = 0 }
        if more { loadingMore = true } else { loading = true; loadingMore = false }
        let scope = session.scopeRevision
        error = nil; pagingNotice = nil
        defer { if token == loadGeneration { loading = false; loadingMore = false } }
        func check() throws {
            try Task.checkCancellation()
            guard token == loadGeneration, scope == session.scopeRevision else { throw CancellationError() }
        }
        if preview { values = PreviewReferenceData.values(source); userGroups = [.object(["groupName": .string("设备维修组"), "users": .array(PreviewReferenceData.values(.users))])]; last = true; return }
        do {
            let response: JSONValue
            if let path = source.pagePath {
                var filters: [JSONValue] = []
                if source == .materials { filters.append(.object(["field": .string("onhandQty"), "operator": .string("gt"), "value": .number(0)])) }
                if !query.isEmpty {
                    filters.append(filter(searchMode.field(for: source), query, "like"))
                }
                var firstPage = !more
                repeat {
                    try check()
                    let offset = firstPage ? 0 : rawOffset
                    let page: JSONValue
                    if source == .outboundMaterials {
                        page = try await session.request(path, body: .object(["offset": .number(Double(offset)), "limit": .number(20), "sort": .string("outbound.applyDate"), "order": .string("desc"), "filters": .array(filters)]))
                    } else {
                        page = try await session.page(path, offset: offset, filters: filters)
                    }
                    try check()
                    guard case .array(let rows) = page["content"] else { throw APIError(message: "物料分页格式异常") }
                    let base = firstPage ? [] : values
                    let combined = source == .outboundMaterials ? uniqueOutboundLines(base + rows) : base + rows
                    let fresh = combined.count - base.count
                    values = combined
                    rawOffset = offset + rows.count
                    last = page["last"].bool
                    if rows.isEmpty || (source == .outboundMaterials && fresh == 0 && !rows.isEmpty) {
                        last = true
                        if !rows.isEmpty { pagingNotice = "后续分页重复，已暂停加载；保留当前物料和选择，结果可能不完整。可缩小搜索范围。" }
                    }
                    firstPage = false
                } while source == .outboundMaterials && hideUsedMaterials && !last && visible.isEmpty

            } else {
                switch source {
                case .users:
                    response = try await session.request("security/getSelectableUsersByFactory")
                case .workflowUsers: response = try await session.request("workflow/searchTransferUsers", query: ["keyword": query, "size": "20"])
                case .departments: response = try await session.request("dept/getDeptSimpleTree")
                case .divisions: response = try await session.request("erp/getAllDivisionByFactoryId")
                case .groups: response = try await session.request("user/getEnabledUserGroupsByGroupType", query: ["groupType": "REPAIR"])
                case .projects: response = try await session.request("erpOutbound/getProjectByCompanyCode")
                case .faultTypes: response = try await session.request("faultType/getFaultTypeTree", query: ["enabled": "true"])
                default: response = .array([])
                }
                try check()
                values = response.array.filter { source != .users || $0["enabled"] != .bool(false) }
            }
        } catch { if token == loadGeneration && !isCancellation(error) { self.error = error.localizedDescription } }
    }
    private func loadUserGroupsIfNeeded() async {
        guard !preview, source == .users, userMode == 1, userGroups.isEmpty else { return }
        do { userGroups = try await session.request("user/getUserGroupsByFactoryId").array }
        catch { if !isCancellation(error) { self.error = error.localizedDescription } }
    }
}

struct RequiredLabel: View {
    let title: String
    var body: some View {
        HStack(spacing: 3) { Text(title).animatedTextValue(title); Text("＊").foregroundStyle(.red) }
            .accessibilityElement(children: .ignore).accessibilityLabel(title + "，必填")
    }
}

struct RequiredFieldsNotice: View {
    var body: some View {
        HStack(spacing: 3) { Text("＊").foregroundStyle(.red); Text("为必填项") }
            .font(AppFont.footnote).foregroundStyle(.secondary)
    }
}

struct RequiredFormHeader: View {
    let title: String
    var body: some View {
        HStack { Text(title).animatedTextValue(title); Spacer(); RequiredFieldsNotice().textCase(nil) }
    }
}

struct ReferenceField: View {
    @Environment(\.previewReferences) private var preview
    let title: String
    let source: ReferenceSource
    @Binding var value: JSONValue
    var required = false
    /// Names the field for "设为默认" ("fault.dept"); leave out where a default makes no sense.
    var defaultField: String? = nil
    var selected: ((JSONValue) -> Void)? = nil
    var body: some View {
        NavigationLink {
            ReferencePicker(source: source, preview: preview, defaultField: defaultField) { result in value = result; selected?(result) }
        } label: {
            LabeledContent { Text(value.object.isEmpty ? "请选择" : value.object.label).animatedTextValue(value.object.isEmpty ? "请选择" : value.object.label) }
            label: { if required { RequiredLabel(title: title) } else { Text(title).animatedTextValue(title) } }
        }
        .accessibilityIdentifier("reference-" + title)
        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
    }
}

struct ServerDateField: View {
    let title: String
    @Binding var value: JSONValue
    var dateOnly = false
    var required = false
    private var date: Binding<Date> {
        Binding(get: { parsedDate(value) }, set: { value = .string(serverDate($0, dateOnly: dateOnly)) })
    }
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if required { RequiredLabel(title: title) } else { Text(title).animatedTextValue(title) }
            HStack(spacing: 8) {
                DatePicker("日期", selection: date, displayedComponents: [.date])
                    .labelsHidden().datePickerStyle(.compact)
                    .accessibilityIdentifier("date-value-" + title)
                if !dateOnly {
                    DatePicker("时间", selection: date, displayedComponents: [.hourAndMinute])
                        .labelsHidden().datePickerStyle(.compact)
                        .accessibilityIdentifier("time-value-" + title)
                }
                Spacer(minLength: 0)
            }.environment(\.locale, Locale(identifier: "zh_CN"))
        }.padding(.vertical, 4)
        .alignmentGuide(.listRowSeparatorLeading) { $0[.leading] }
        .alignmentGuide(.listRowSeparatorTrailing) { $0[.trailing] }
    }
}

struct AssigneesFields: View {
    @Environment(\.previewReferences) private var preview
    let elements: [Fields]
    @Binding var selections: [String: [String]]
    /// Makes "设为默认" available on each approver field ("assignee.fault" + the node's variable name).
    var defaultPrefix: String? = nil
    var body: some View {
        ForEach(Array(elements.enumerated()), id: \.offset) { _, element in
            if ["ASSIGN", "SUPERVISOR"].contains(element.text("assignType")) {
                let key = element.text("variableName")
                NavigationLink {
                    ReferencePicker(source: .users, multiple: isCountersign(element) || element["assigneeMultiple"]?.bool == true, selected: selections[key, default: []], preview: preview, defaultField: allowsApproverDefault(element) ? defaultPrefix.map { $0 + "." + key } : nil) { user in
                        let name = user["userName"].text
                        if isCountersign(element) || element["assigneeMultiple"]?.bool == true {
                            if selections[key, default: []].contains(name) { selections[key]?.removeAll { $0 == name } }
                            else { selections[key, default: []].append(name) }
                        } else { selections[key] = [name] }
                    }
                } label: {
                    LabeledContent { Text(selections[key, default: []].isEmpty ? "请选择审批人" : selections[key, default: []].joined(separator: "、")).animatedTextValue(selections[key, default: []].isEmpty ? "请选择审批人" : selections[key, default: []].joined(separator: "、")) }
                    label: { RequiredLabel(title: element.text("taskName")) }
                }
                .accessibilityIdentifier("assignee-" + key)
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
    @State private var showingDrafts = false
    @State private var draftID: UUID?
    @State private var draftCount = 0
    @State private var toast: String?
    @State private var loadedData: Fields = [:]
    @State private var loadedSelections: [String: [String]] = [:]
    var existing: Fields? = nil
    var preview = false
    /// A draft to continue: applied over the freshly loaded form.
    var draft: FaultDraft? = nil
    var initialAsset: JSONValue? = nil
    var onSaved: (() -> Void)? = nil
    var isActive = true
    var template: FaultReportTemplate? = nil
    @State private var loadAttempt = UUID()
    @State private var initialized = false
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { model.data[key] ?? .null }, set: { model.data[key] = $0 }) }
    private func apply(_ draft: FaultDraft, automatic: Bool = false) {
        model.data = draft.data
        for (key, value) in draft.selections { model.selections[key] = value }
        draftID = draft.id
        toast = (automatic ? "已加载上次保存的草稿（" : "已加载草稿（") + draft.title + "）"
    }
    /// A draft belongs to one order: the existing order being edited, or "a new report" when there is none.
    private var orderID: String? { existing.map { $0.text("faultId") } }
    private var draftTarget: DraftStore.Target {
        guard let orderID else { return .newReport }
        return orderID.isEmpty ? .order("") : .order(orderID)
    }
    /// An order without an id cannot be told apart from another, so it gets no drafts at all.
    private var draftsAllowed: Bool { existing == nil || !(orderID ?? "").isEmpty }
    /// Whether there is something worth keeping: anything typed into a new report, any change to an existing order.
    private var worthKeeping: Bool {
        guard draftsAllowed, !preview else { return false }
        if existing != nil || template != nil {
            return model.data != loadedData || model.selections != loadedSelections
        }
        return faultFormHasContent(model.data)
    }
    private func keepDraft() {
        let draft = FaultDraft(id: draftID ?? UUID(), savedAt: Date(), data: model.data, selections: model.selections,
                               orderID: orderID, orderSN: existing?.text("sn"))
        do { try DraftStore.shared.save(draft, scope: session.draftScope); dismiss() }
        catch { model.error = "草稿没有保存成功：" + error.localizedDescription }
    }
    /// Closing keeps entered content locally without an extra confirmation.
    private func close() {
        if worthKeeping { keepDraft() } else { dismiss() }
    }
    private func text(_ key: String) -> Binding<String> { Binding(get: { model.data.text(key) }, set: { model.data[key] = .string($0) }) }
    var body: some View {
        Form {
            Section {
                VStack(alignment: .leading, spacing: 8) {
                    RequiredLabel(title: "报修名称")
                    EntryField(placeholder: "请输入报修名称", text: text("faultName"), limit: 64)
                }.fullSeparator()
                ReferenceField(title: "报修资产", source: .assets, value: field("asset"), required: true)
                if let asset = model.data["asset"], !asset["assetCode"].text.isEmpty { LabeledContent("资产编码") { Text(asset["assetCode"].text).animatedTextValue(asset["assetCode"].text) } }
                ServerDateField(title: "故障时间", value: field("faultTime"), required: true)
            } header: { RequiredFormHeader(title: "故障报修") }
            Section("申请与受理") {
                ReferenceField(title: "故障申请人", source: .users, value: field("applyUser"), required: true, defaultField: "fault.applyUser") { user in
                    model.data["applyDept"] = user["depts"].array.first ?? .null
                }
                ReferenceField(title: "申请部门", source: .departments, value: field("applyDept"), required: true, defaultField: "fault.applyDept")
                ReferenceField(title: "受理部门", source: .departments, value: field("dept"), required: true, defaultField: "fault.dept") { model.selectReceiverDepartment($0) }
                ReferenceField(title: "维修受理人", source: .users, value: field("user"), required: true, defaultField: "fault.user") { user in
                    if let department = user["depts"].array.first { model.data["dept"] = department }
                }
                if !(model.data["dept"]?.object.isEmpty ?? true) && (model.data["user"]?.object.isEmpty ?? true) {
                    Text("该部门未设置默认受理人，请手动选择维修受理人").font(AppFont.footnote).foregroundStyle(.secondary)
                }
                VStack(alignment: .leading, spacing: 8) { RequiredLabel(title: "报修人联系电话"); EntryField(placeholder: "请输入联系电话", text: text("userPhone"), limit: 32, keyboard: .phonePad) }.fullSeparator()
                ReferenceField(title: "维修班组", source: .groups, value: field("userGroup"), required: true, defaultField: "fault.userGroup")
                ReferenceField(title: "ERP记账部门", source: .divisions, value: field("division"), required: true, defaultField: "fault.division")
            }
            if model.assignees.contains(where: { ["ASSIGN", "SUPERVISOR"].contains($0.text("assignType")) }) {
                Section("审批人") { AssigneesFields(elements: model.assignees, selections: $model.selections, defaultPrefix: existing == nil ? "assignee.fault" : nil) }
            }
            Section("故障描述") { EntryField(placeholder: "请输入故障描述", text: text("faultDesc"), limit: 255, axis: .vertical, lines: 3...6) }
            Section("现场照片") { OriginalPhotosField(albums: field("albums"), directory: "asset_fault", preview: preview, uploading: $photoUploading) }
            if preview { Section { Text("原包字段预览，未连接服务器").font(AppFont.footnote).foregroundStyle(.secondary) } }
        }
        .disabled(!initialized || model.loading || model.saving)
        .initialLoadingContent(!initialized)
        .overlay {
            if !initialized, !model.loading, let error = model.error {
                LoadingFailure(error: error) { loadAttempt = UUID() }
            } else {
                AnimatedLoadingOverlay(isPresented: !initialized || model.loading || model.saving, title: model.saving ? "正在提交报修…" : "正在加载表单…")
            }
        }
        .navigationTitle(existing == nil ? "故障报修" : "编辑报修").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { close() }.disabled(model.saving || photoUploading) }
            if draftsAllowed {
                ToolbarItem(placement: .confirmationAction) {
                    Button { showingDrafts = true } label: { Label(draftCount > 0 ? "草稿箱 (\(draftCount))" : "草稿箱", systemImage: "tray.full").animatedTextValue(draftCount > 0 ? "草稿箱 (\(draftCount))" : "草稿箱") }
                        .disabled(model.saving || photoUploading).accessibilityIdentifier("draft-box-button")
                }
            }
        }
        .toast($toast)
        .interactiveDismissDisabled(worthKeeping)
        .sheet(isPresented: $showingDrafts, onDismiss: { draftCount = DraftStore.shared.drafts(scope: session.draftScope, for: draftTarget).count }) {
            NavigationStack {
                DraftBoxView(dismissOnPick: true, target: draftTarget) { picked in apply(picked) }
                    .toolbar { ToolbarItem(placement: .cancellationAction) { Button("完成") { showingDrafts = false } } }
            }.presentationDetents([.medium, .large])
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            DocumentActions(busy: model.saving, submitting: confirming == true,
                            disabled: !initialized || model.loading || photoUploading,
                            // "保存" keeps a draft on this phone: nothing is sent to the server and nothing is required.
                            // Only "提交" writes to the server, and it needs the form complete.
                            save: { if draftsAllowed { keepDraft() } else { prepare(false) } }, submit: { prepare(true) })
        }
        .task(id: isActive ? loadAttempt : nil) {
            guard isActive, !initialized else { return }
            if preview { model.loading = false; model.ready = true; model.data = ["faultTime": .string("2026-10-16 09:30"), "albums": .array([]), "applyUser": PreviewIdentity.user, "applyDept": PreviewIdentity.department, "userPhone": .string("13800000000")] }
            else {
                await model.load(session, existing: existing)
                guard model.ready, !Task.isCancelled else { return }
                if existing == nil { await model.applyDefaults(session) }
                guard !Task.isCancelled else { return }
                loadedData = model.data; loadedSelections = model.selections
                // The form opens with what was saved last time for the same order (a new report: the last
                // new report). A draft chosen on purpose wins; a draft of another order is never offered.
                if let initialAsset, existing == nil, model.ready {
                    model.loading = true
                    defer { model.loading = false }
                    do {
                        let id = initialAsset["assetId"].text, code = initialAsset["assetCode"].text
                        guard !id.isEmpty, !code.isEmpty else { throw APIError(message: "资产信息不完整，请手动选择报修资产") }
                        let page = try await session.page("asset/pageAsset", filters: [filter("assetId", id)], limit: 2)
                        try Task.checkCancellation()
                        let matches = page["content"].array.filter { $0["assetId"].text == id && $0["assetCode"].text == code }
                        guard matches.count == 1 else { throw APIError(message: "当前登录账号无法核对该资产，请手动选择报修资产") }
                        model.data["asset"] = matches[0]
                    } catch {
                        guard !Task.isCancelled, !isCancellation(error) else { return }
                        model.error = error.localizedDescription
                    }
                }
                if draftsAllowed, model.ready, initialAsset == nil, template == nil {
                    if let draft, draftTarget.matches(draft) { apply(draft) }
                    else if let last = DraftStore.shared.drafts(scope: session.draftScope, for: draftTarget).first { apply(last, automatic: true) }
                }
            }
            guard !Task.isCancelled else { return }
            if let template {
                if !template.faultName.isEmpty { model.data["faultName"] = .string(template.faultName) }
                if !template.description.isEmpty { model.data["faultDesc"] = .string(template.description) }
                if preview, template.asset != .null { model.data["asset"] = template.asset }
                loadedData = model.data
                loadedSelections = model.selections
            }
            draftCount = DraftStore.shared.drafts(scope: session.draftScope, for: draftTarget).count
            initialized = true
        }
        .keyboardManaged(showsToolbar: false)
        .errorDialog(Binding(
            get: { isActive && initialized ? model.error : nil },
            set: { if isActive && initialized { model.error = $0 } }
        ), clearsOnClose: true)
    }
    private func prepare(_ committed: Bool) {
        if let error = model.validationError() { model.error = error; showValidation = true; return }
        // No "are you sure" dialog: pressing 提交/保存 is the confirmation.
        confirming = committed
        Task { await save() }
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
            // The report is on the server now: the draft it came from has done its job.
            if let draftID { DraftStore.shared.remove(id: draftID, scope: session.draftScope) }
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
                Text(image["name"].text.isEmpty ? "照片 \(index+1)" : image["name"].text).animatedTextValue(image["name"].text.isEmpty ? "照片 \(index+1)" : image["name"].text)
                Spacer()
                Button("删除", role: .destructive) { var values = albums.array; values.remove(at: index); albums = .array(values) }
            }
        }
        PhotosPicker(selection: $selected, maxSelectionCount: max(0, 9-albums.array.count), matching: .images) {
            Label("选择现场照片", systemImage: "photo.badge.plus")
        }
        .fullRow()
        .disabled(uploading || preview || albums.array.count >= 9)
        .onChange(of: selected) { Task { await upload() } }
        .errorDialog($error, title: "照片上传失败", clearsOnClose: true)
        if uploading { ProgressView("正在上传照片…") }
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
    @State private var showValidation = false
    @State private var draftID: UUID?
    @State private var toast: String?
    var existing: Fields? = nil
    var referenceSn: String? = nil
    var preview = false
    var onSaved: ((JSONValue) -> Void)? = nil
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { model.data[key] ?? .null }, set: { model.data[key] = $0 }) }
    private func text(_ key: String) -> Binding<String> { Binding(get: { model.data.text(key) }, set: { model.data[key] = .string($0) }) }
    private func quantity(_ index: Int) -> Binding<Double> {
        Binding(get: { (model.data["erpMaterialOutbounds"]?.array ?? [])[index]["entryQuantity"].number }, set: { number in
            var rows = model.data["erpMaterialOutbounds"]?.array ?? []; var line = rows[index].object
            line["entryQuantity"] = .number(cappedMaterialQuantity(number, maximum: line["material"]?["onhandQty"].number ?? 0)); rows[index] = .object(line); model.data["erpMaterialOutbounds"] = .array(rows)
        })
    }
    var body: some View {
        Form {
            Section {
                ReferenceField(title: "申请人", source: .users, value: field("user"), required: true, defaultField: "erp.user")
                ServerDateField(title: "申请日期", value: field("applyDate"), dateOnly: true, required: true)
                ReferenceField(title: "项目号", source: .projects, value: field("project"), required: true) { project in model.data["projectCode"] = project["projectCode"]; model.data["projectDesc"] = project["projectName"] }
                LabeledContent("关联单号") { Text(model.data.text("referenceSn")).animatedTextValue(model.data.text("referenceSn")) }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("关联单号")
                    .accessibilityValue(model.data.text("referenceSn"))
                    .accessibilityIdentifier("erp-reference-sn")
                ReferenceField(title: "领用人", source: .users, value: field("recipientUser"), required: true, defaultField: "erp.recipientUser")
                ReferenceField(title: "领用部门", source: .departments, value: field("dept"), required: true, defaultField: "erp.dept")
                ReferenceField(title: "ERP记账部门", source: .divisions, value: field("division"), required: true, defaultField: "erp.division")
                ServerDateField(title: "出库时间", value: field("outboundTime"), required: true)
            } header: { RequiredFormHeader(title: "领料申请") }
            Section {
                FullRowButton("添加出库物料", systemImage: "plus") { selecting = true }.disabled(preview)
                ForEach(Array((model.data["erpMaterialOutbounds"]?.array ?? []).enumerated()), id: \.offset) { index, line in
                    DisclosureGroup {
                        MaterialFacts(material: line["material"])
                        LabeledContent("库存数量") { Text(materialQuantity(line["material"]["onhandQty"].text, material: line["material"])).animatedTextValue(materialQuantity(line["material"]["onhandQty"].text, material: line["material"])) }
                        HStack { RequiredLabel(title: "申请数量"); Spacer(); TextField("数量", value: quantity(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing).frame(minWidth: 70); Text(materialUnit(line["material"])).animatedTextValue(materialUnit(line["material"])).foregroundStyle(.secondary) }
                        Button("删除明细", role: .destructive) { var rows = model.data["erpMaterialOutbounds"]?.array ?? []; rows.remove(at: index); model.data["erpMaterialOutbounds"] = .array(rows) }
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(line["material"]["materialDesc"].text).animatedTextValue(line["material"]["materialDesc"].text)
                            Text(line["material"]["materialCode"].text).animatedTextValue(line["material"]["materialCode"].text).font(AppFont.subheadline).foregroundStyle(.secondary)
                            Text("申请数量：\(materialQuantity(line["entryQuantity"].text, material: line["material"]))").animatedTextValue("申请数量：\(materialQuantity(line["entryQuantity"].text, material: line["material"]))").font(AppFont.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
            } header: { RequiredLabel(title: "出库物料 · \(model.data["erpMaterialOutbounds"]?.array.count ?? 0) 项") }
            Section { EntryField(placeholder: "请输入用途", text: text("remark"), limit: 140, axis: .vertical, lines: 3...6) } header: { RequiredLabel(title: "用途") }
            if model.assignees.contains(where: { ["ASSIGN", "SUPERVISOR"].contains($0.text("assignType")) }) {
                Section("审批人") { AssigneesFields(elements: model.assignees, selections: $model.selections, defaultPrefix: existing == nil ? "assignee.erp" : nil) }
            }
            if preview { Section { Text("原包字段预览，未连接服务器").font(AppFont.footnote).foregroundStyle(.secondary) } }
        }
        .disabled(model.loading || model.saving)
        .initialLoadingContent(model.loading && !model.ready)
        .overlay { AnimatedLoadingOverlay(isPresented: model.loading || model.saving, title: model.saving ? "正在提交领料申请…" : "正在加载领料申请…") }
        .navigationTitle("ERP 出库").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(model.saving) }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            DocumentActions(busy: model.saving, submitting: committed,
                            disabled: !model.ready,
                            save: { keepDraft() }, submit: { prepare(true) })
        }
        .toast($toast)
        .sheet(isPresented: $selecting) { NavigationStack { ReferencePicker(source: .materials, multiple: true, selected: (model.data["erpMaterialOutbounds"]?.array ?? []).map { $0["material"]["id"].text }) { model.addMaterial($0) } } }
        .task {
            guard !model.ready else { return }
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
                if existing == nil, model.ready { await model.applyDefaults(session) }
                if let existing { model.data["project"] = .object(["projectCode": existing["projectCode"] ?? .null, "projectName": existing["projectDesc"] ?? .null]) }
                if model.ready { applyDraft() }
            }
        }
        .keyboardManaged(showsToolbar: false)
        .errorDialog($model.error, title: model.ready ? "提示" : "加载失败", clearsOnClose: true, retry: model.ready ? nil : { Task { await model.load(session, existing: existing, referenceSn: referenceSn) } })
    }
    private func prepare(_ value: Bool) {
        model.data["erpMaterialOutbounds"] = .array((model.data["erpMaterialOutbounds"]?.array ?? []).map { value in
            var row = value.object
            row["entryQuantity"] = .number(cappedMaterialQuantity(value["entryQuantity"].number, maximum: value["material"]["onhandQty"].number))
            return .object(row)
        })
        if let error = model.validationError() { model.error = error; showValidation = true; return }
        committed = value; Task { await save() }
    }
    /// "保存" keeps the form on this phone: nothing is sent to the server and nothing has to be filled in.
    private var erpDraftTarget: DraftStore.Target { existing == nil ? .newReport : .order(existing?.text("outboundId") ?? "") }
    private var erpDraftsAllowed: Bool { existing == nil || !(existing?.text("outboundId") ?? "").isEmpty }
    private func keepDraft() {
        guard !preview, model.ready, erpDraftsAllowed else { return }
        let draft = FaultDraft(id: draftID ?? UUID(), savedAt: Date(), data: model.data, selections: model.selections,
                               orderID: existing == nil ? nil : existing?.text("outboundId"), orderSN: existing?.text("outNumber"))
        do { try DraftStore.shared.save(draft, scope: session.erpDraftScope); draftID = draft.id; dismiss() }
        catch { model.error = "草稿没有保存成功：" + error.localizedDescription }
    }
    private func applyDraft() {
        guard !preview, erpDraftsAllowed, let draft = DraftStore.shared.drafts(scope: session.erpDraftScope, for: erpDraftTarget).first else { return }
        model.data = draft.data
        for (key, value) in draft.selections { model.selections[key] = value }
        draftID = draft.id
        toast = "已加载上次保存的草稿（" + draft.title + "）"
    }
    private func save() async {
        guard !preview, !model.saving else { return }
        model.saving = true; model.error = nil
        defer { model.saving = false }
        do {
            var payload = model.payload(committed: committed); payload.removeValue(forKey: "project")
            let result = try await session.request(existing == nil ? "erpOutbound/addErpOutbound" : "erpOutbound/updateErpOutbound", body: .object(payload))
            onSaved?(result)
            if let draftID { DraftStore.shared.remove(id: draftID, scope: session.erpDraftScope) }
            dismiss()
        } catch { model.error = error.localizedDescription }
    }
}

struct DocumentActions: View {
    @State private var keyboardPresented = false
    let busy: Bool
    let submitting: Bool
    let disabled: Bool
    var save: (() -> Void)? = nil
    var submitTitle = "提交"
    let submit: () -> Void
    var body: some View {
        HStack(alignment: .bottom) {
            if let save { BottomAction(title: "保存", symbol: "tray.and.arrow.down", color: Color(red: 0.92, green: 0.73, blue: 0.24), busy: busy && !submitting, action: save) }
            Spacer()
            BottomAction(title: submitTitle, symbol: "checkmark.circle", color: .blue, busy: busy && submitting, action: submit)
        }
        .disabled(busy || disabled)
        .overlay(alignment: .bottom) {
            // Only opacity changes. The footer's height and both action buttons remain
            // stable; a second keyboard toolbar must not overlap this action row.
            Button("收起键盘", action: endEditing)
                .buttonStyle(.glass).buttonBorderShape(.capsule)
                .accessibilityIdentifier("keyboard-dismiss")
                .opacity(keyboardPresented ? 1 : 0)
                .allowsHitTesting(keyboardPresented)
                .accessibilityHidden(!keyboardPresented)
        }
        // Keep the action row alive while SwiftUI moves the keyboard safe area.
        // Fixed spacing avoids a second keyboard-driven padding animation.
        .padding(.horizontal, 20).padding(.vertical, 8)
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillChangeFrameNotification)) { notification in
            keyboardPresented = KeyboardMotionChange(notification).visible
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidShowNotification)) { notification in
            keyboardPresented = KeyboardMotionChange(notification).visible
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardWillHideNotification)) { _ in
            keyboardPresented = false
        }
        .onReceive(NotificationCenter.default.publisher(for: UIResponder.keyboardDidHideNotification)) { _ in
            keyboardPresented = false
        }
    }
}

struct MaterialFacts: View {
    let material: JSONValue
    var body: some View {
        LabeledContent("物料编码") { Text(material["materialCode"].text).animatedTextValue(material["materialCode"].text) }
        LabeledContent("ERP库存组织") { Text(material["erpTpmCompany"]["organizationName"].text).animatedTextValue(material["erpTpmCompany"]["organizationName"].text) }
        LabeledContent("批次") { Text(material["lot"].text).animatedTextValue(material["lot"].text) }
        LabeledContent("子库") { Text(material["subInv"].text).animatedTextValue(material["subInv"].text) }
        LabeledContent("货位名称") { Text(material["locatorCode"].text).animatedTextValue(material["locatorCode"].text) }
        LabeledContent("货位描述") { Text(material["locatorDesc"].text).animatedTextValue(material["locatorDesc"].text) }
        LabeledContent("单位") { Text(materialUnit(material)).animatedTextValue(materialUnit(material)) }
    }
}

func materialUnit(_ material: JSONValue) -> String {
    for path in ["uom.uomDesc", "uom.uomCode", "uomDesc", "uomCode"] {
        let unit = material.at(path).text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !unit.isEmpty { return unit }
    }
    return "PCS"
}

func cappedMaterialQuantity(_ quantity: Double, maximum: Double) -> Double {
    min(quantity, max(0, maximum))
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
            return [.object(["value": .string("root"), "label": .string("示例厂区"), "children": .array([.object(["value": .number(1), "label": .string("设备部"), "children": .array([.object(["value": .number(2), "deptName": .string("示例设备维修科")])])]), .object(["value": .number(3), "deptName": .string("示例生产部"), "acceptUser": .object(["userId": .number(91), "realName": .string("示例默认受理人")])])])])]
        case .groups:
            return [.object(["groupId": .number(1), "groupName": .string("示例维修班组")])]
        case .divisions:
            return [.object(["divisionId": .number(1), "divisionDesc": .string("示例 ERP 记账部门")])]
        case .projects:
            return [.object(["projectCode": .string("P-DEMO-001"), "projectName": .string("示例维修项目")])]
        case .materials:
            return PreviewBusinessData.erpLines.map { $0["material"] }
        case .faultTypes:
            return [.object(["key": .string("category-1"), "label": .string("机械故障"), "type": .string("FaultTypeCategory"), "children": .array([.object(["key": .string("1"), "typeName": .string("轴承磨损"), "type": .string("FaultType")]), .object(["key": .string("2"), "typeName": .string("传动异常"), "type": .string("FaultType")])])]), .object(["key": .string("category-2"), "label": .string("电气故障"), "type": .string("FaultTypeCategory"), "children": .array([.object(["key": .string("3"), "typeName": .string("驱动器报警"), "type": .string("FaultType")])])])]
        case .outboundMaterials:
            return PreviewBusinessData.repairMaterials.map { var line = $0["materialOutbound"].object; line["outbound"] = $0["outbound"]; line["id"] = .object(["outboundId": .number(1), "materialId": $0["material"]["id"]]); return .object(line) }
        }
    }
}

enum PreviewBusinessData {
    /// Sanitized stand-in for the next node of the repair task ("完成任务" window):
    /// 【维修】确认, assigned by choosing a user. Names are fictional, not server variables.
    static let repairConfirmNode: Fields = [
        "taskName": .string("【维修】确认"), "variableName": .string("demoRepairConfirmUser"),
        "assignType": .string("ASSIGN"), "assignees": .null
    ]
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

/// Identity of one outbound material line: the outbound order plus the material.
func outboundLineIdentity(_ line: JSONValue) -> String {
    "\(line["id"]["outboundId"].text)-\(line["id"]["materialId"].text)"
}

/// Keep distinct materials from the same outbound order; merge only identical line keys.
func uniqueOutboundLines(_ rows: [JSONValue]) -> [JSONValue] {
    var result: [JSONValue] = []
    var indices: [String: Int] = [:]
    for row in rows {
        let key = outboundLineIdentity(row)
        if key == "-" { result.append(row); continue }
        if let index = indices[key] { result[index] = row }
        else { indices[key] = result.count; result.append(row) }
    }
    return result
}
