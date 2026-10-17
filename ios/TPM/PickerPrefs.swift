import SwiftUI

// Pinned, recent and default choices for the selection pages (people, departments, assets, ...), kept on this
// phone per account and factory, like the drafts. Nothing here is sent to the server and nothing secret is
// stored: an entry is the row the server listed, so choosing it runs the same lookup as tapping the row.

struct PickerEntry: Codable, Equatable, Identifiable {
    var key: String
    var label: String
    var value: JSONValue
    var id: String { key }
}

struct PickerLists: Codable, Equatable {
    /// Newest pin first. At most `PickerLists.pinLimit`.
    var pins: [PickerEntry] = []
    /// Newest first. More are kept than shown, because the ones that are pinned are not repeated.
    var recents: [PickerEntry] = []
    static let pinLimit = 10
    static let recentShown = 3
    static let recentKept = 8
}

private struct StoredDefault: Codable, Equatable {
    var source: String
    var entry: PickerEntry
}

private struct PickerFile: Codable, Equatable {
    var lists: [String: PickerLists] = [:]
    var defaults: [String: StoredDefault] = [:]
}

/// With a `url` it persists to that file; without one it only lives as long as the app run (the demo screens).
final class PickerPrefsStore: @unchecked Sendable {
    private let url: URL?
    private let lock = NSLock()
    private var file: PickerFile

    init(url: URL?) {
        self.url = url
        if let url, let data = try? Data(contentsOf: url), let decoded = try? JSONDecoder().decode(PickerFile.self, from: data) { file = decoded }
        else { file = PickerFile() }
    }
    static let shared = PickerPrefsStore(url: {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Drafts", isDirectory: true).appendingPathComponent("picker-prefs.json")
    }())
    static let preview = PickerPrefsStore(url: nil)

    private func persist() {
        guard let url else { return }
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? JSONEncoder().encode(file).write(to: url, options: [.atomic, .completeFileProtection])
    }
    private func listKey(_ scope: String, _ source: ReferenceSource) -> String { scope + "|" + source.rawValue }
    private func defaultKey(_ scope: String, _ field: String) -> String { scope + "|" + field }

    func lists(scope: String, source: ReferenceSource) -> PickerLists {
        lock.lock(); defer { lock.unlock() }
        return file.lists[listKey(scope, source)] ?? PickerLists()
    }
    func save(_ lists: PickerLists, scope: String, source: ReferenceSource) {
        lock.lock(); defer { lock.unlock() }
        file.lists[listKey(scope, source)] = lists
        persist()
    }
    func defaultChoice(scope: String, field: String) -> (source: ReferenceSource, entry: PickerEntry)? {
        lock.lock(); defer { lock.unlock() }
        guard let stored = file.defaults[defaultKey(scope, field)], let source = ReferenceSource(rawValue: stored.source) else { return nil }
        return (source, stored.entry)
    }
    func setDefault(_ entry: PickerEntry?, source: ReferenceSource, scope: String, field: String) {
        lock.lock(); defer { lock.unlock() }
        file.defaults[defaultKey(scope, field)] = entry.map { StoredDefault(source: source.rawValue, entry: $0) }
        persist()
    }
    /// Moves pins, recents and defaults saved under an older scope name to the current one. What the current
    /// scope already holds wins; nothing is overwritten.
    func migrate(from old: String, to new: String) {
        guard old != new else { return }
        lock.lock(); defer { lock.unlock() }
        var changed = false
        for (key, value) in file.lists where key.hasPrefix(old + "|") {
            let target = new + String(key.dropFirst(old.count))
            if file.lists[target] == nil { file.lists[target] = value }
            file.lists[key] = nil; changed = true
        }
        for (key, value) in file.defaults where key.hasPrefix(old + "|") {
            let target = new + String(key.dropFirst(old.count))
            if file.defaults[target] == nil { file.defaults[target] = value }
            file.defaults[key] = nil; changed = true
        }
        if changed { persist() }
    }
}

/// The pure rules, so they can be tested without a screen.
enum PickerRules {
    /// Pins a new entry at the top. Returns false when the ten places are taken.
    static func pin(_ entry: PickerEntry, in lists: inout PickerLists) -> Bool {
        if lists.pins.contains(where: { $0.key == entry.key }) { return true }
        guard lists.pins.count < PickerLists.pinLimit else { return false }
        lists.pins.insert(entry, at: 0)
        return true
    }
    static func unpin(key: String, in lists: inout PickerLists) { lists.pins.removeAll { $0.key == key } }
    /// A chosen entry moves to the front of the recent ones.
    static func record(_ entry: PickerEntry, in lists: inout PickerLists) {
        lists.recents.removeAll { $0.key == entry.key }
        lists.recents.insert(entry, at: 0)
        if lists.recents.count > PickerLists.recentKept { lists.recents = Array(lists.recents.prefix(PickerLists.recentKept)) }
    }
    /// The recent choices to show: not already pinned, at most three. Nothing at all when there are none.
    static func shownRecents(_ lists: PickerLists) -> [PickerEntry] {
        let pinned = Set(lists.pins.map(\.key))
        return Array(lists.recents.filter { !pinned.contains($0.key) }.prefix(PickerLists.recentShown))
    }
}

/// What identifies a row of a selection page, whatever the page lists.
func referenceKey(_ source: ReferenceSource, _ value: JSONValue) -> String {
    let keys: [String]
    switch source {
    case .users, .workflowUsers: keys = ["userName", "userId"]
    case .departments: keys = ["value", "deptId", "id"]
    case .faultTypes: keys = ["key", "typeId", "id"]
    case .assets: keys = ["assetId", "assetCode", "id"]
    case .groups: keys = ["groupId", "id"]
    case .divisions: keys = ["divisionId", "id", "divisionCode"]
    case .projects: keys = ["projectCode", "projectId", "id"]
    case .materials, .outboundMaterials: keys = ["id"]
    }
    for key in keys { let text = value[key].text; if !text.isEmpty { return text } }
    return value.object.label
}

/// Looks the chosen row up the way the original app does, so the form gets the full object (a person with
/// their departments, a department with its default assignee, ...). Used by the selection page and by defaults.
func resolveReference(_ source: ReferenceSource, _ value: JSONValue, session: LiveSession) async throws -> JSONValue {
    switch source {
    case .departments: return try await session.request("dept/getDeptById", query: ["deptId": value["value"].text])
    case .users: return try await session.request("user/getUserDeptByUserId", query: ["userId": value["userId"].text])
    case .faultTypes: return try await session.request("faultType/getFaultTypeById", query: ["typeId": value["key"].text])
    default: return value
    }
}

/// The state behind one selection page's menu: the pins, the recents, the default of its field.
@MainActor
final class ReferenceMenuModel: ObservableObject {
    private(set) var source: ReferenceSource = .users
    /// The form field this page fills ("fault.dept"); nil where a default makes no sense (approvers, many people).
    private(set) var defaultField: String?
    private(set) var allowsRecents = false
    private(set) var scope = ""
    private var store: PickerPrefsStore = .preview
    /// False until the page has said which list it shows (and for lists that take no menu).
    @Published private(set) var enabled = false
    @Published private(set) var lists = PickerLists()
    @Published private(set) var defaultEntry: PickerEntry?
    /// Set when the ten pin places are full, for the page to show.
    @Published var notice: String?

    /// Says which list the page shows. Reading the stored pins, recents and default happens here.
    func configure(source: ReferenceSource, defaultField: String?, allowsRecents: Bool, scope: String, store: PickerPrefsStore) {
        self.source = source; self.defaultField = defaultField; self.allowsRecents = allowsRecents; self.scope = scope; self.store = store
        lists = store.lists(scope: scope, source: source)
        defaultEntry = defaultField.flatMap { store.defaultChoice(scope: scope, field: $0)?.entry }
        enabled = true
    }
    var pins: [PickerEntry] { lists.pins }
    var recents: [PickerEntry] { allowsRecents ? PickerRules.shownRecents(lists) : [] }
    var supportsDefault: Bool { defaultField != nil }

    func entry(_ value: JSONValue) -> PickerEntry {
        PickerEntry(key: referenceKey(source, value), label: value.object.label, value: value)
    }
    func isPinned(_ value: JSONValue) -> Bool { lists.pins.contains { $0.key == referenceKey(source, value) } }
    func isDefault(_ value: JSONValue) -> Bool { defaultEntry?.key == referenceKey(source, value) }

    func togglePin(_ value: JSONValue) {
        var updated = lists
        if isPinned(value) { PickerRules.unpin(key: referenceKey(source, value), in: &updated) }
        else if !PickerRules.pin(entry(value), in: &updated) { notice = "最多只能置顶 \(PickerLists.pinLimit) 项，请先取消一个置顶。"; return }
        lists = updated
        store.save(updated, scope: scope, source: source)
    }
    func toggleDefault(_ value: JSONValue) {
        guard let defaultField else { return }
        let next: PickerEntry? = isDefault(value) ? nil : entry(value)
        defaultEntry = next
        store.setDefault(next, source: source, scope: scope, field: defaultField)
    }
    func record(_ value: JSONValue) {
        guard enabled, allowsRecents else { return }
        var updated = lists
        PickerRules.record(entry(value), in: &updated)
        lists = updated
        store.save(updated, scope: scope, source: source)
    }
}

/// Swipe left (like the chat list in WeChat) or long press a row of a selection page for "置顶" / "设为默认".
private struct ReferenceMenu: ViewModifier {
    let value: JSONValue
    @EnvironmentObject private var menu: ReferenceMenuModel
    func body(content: Content) -> some View {
        if menu.enabled {
            content
                .swipeActions(edge: .trailing, allowsFullSwipe: false) { actions }
                .contextMenu { actions }
        } else { content }
    }
    /// The same two choices for the swipe and for the long press.
    @ViewBuilder private var actions: some View {
        Button { menu.togglePin(value) } label: {
            Label(menu.isPinned(value) ? "取消置顶" : "置顶", systemImage: menu.isPinned(value) ? "pin.slash" : "pin")
        }
        .tint(.blue).accessibilityIdentifier("menu-pin")
        if menu.supportsDefault {
            Button { menu.toggleDefault(value) } label: {
                Label(menu.isDefault(value) ? "取消默认" : "设为默认", systemImage: menu.isDefault(value) ? "star.slash" : "star")
            }
            .tint(.orange).accessibilityIdentifier("menu-default")
        }
    }
}

extension View {
    func referenceMenu(for value: JSONValue) -> some View { modifier(ReferenceMenu(value: value)) }
}

/// The small tags next to a row's name.
struct ReferenceBadges: View {
    let value: JSONValue
    @EnvironmentObject private var menu: ReferenceMenuModel
    var body: some View {
        if menu.enabled {
            HStack(spacing: 6) {
                if menu.isPinned(value) { Image(systemName: "pin.fill").font(.caption).foregroundStyle(Color.accentColor).accessibilityLabel("已置顶") }
                if menu.isDefault(value) { Text("默认").font(.caption2.weight(.semibold)).padding(.horizontal, 6).padding(.vertical, 2).background(Color.orange.opacity(0.18), in: .capsule).foregroundStyle(.orange) }
            }
        }
    }
}

/// A default approver only makes sense where the person chooses the approver. When the process itself
/// suggests approvers, the suggestion stands: no "设为默认" there, and a default saved earlier is not applied.
func allowsApproverDefault(_ node: Fields) -> Bool {
    usernames(node["assignees"] ?? .null).isEmpty
}

extension OriginalFormModel {
    /// Fields of a new form that the person has set a default for. The order matters: choosing a person
    /// fills their department, choosing a department fills its assignee, and the later, more specific
    /// default then wins over what the earlier one filled in.
    static func defaultFields(kind: String) -> [(field: String, dataKey: String, source: ReferenceSource)] {
        kind == "ASSET_FAULT"
            ? [("fault.applyUser", "applyUser", .users), ("fault.applyDept", "applyDept", .departments), ("fault.dept", "dept", .departments),
               ("fault.user", "user", .users), ("fault.userGroup", "userGroup", .groups), ("fault.division", "division", .divisions)]
            : [("erp.user", "user", .users), ("erp.recipientUser", "recipientUser", .users), ("erp.dept", "dept", .departments), ("erp.division", "division", .divisions)]
    }
    /// Puts the person's own defaults into a new form. A default that can no longer be found is skipped.
    /// Approvers: the default person is ticked for each approver step that has one. The server
    /// still decides who may approve; this only saves the choosing.
    func applyApproverDefaults(prefix: String, scope: String, store: PickerPrefsStore) {
        for node in assignees where ["ASSIGN", "SUPERVISOR"].contains(node.text("assignType")) {
            let key = node.text("variableName")
            guard allowsApproverDefault(node) else { continue }
            if let choice = store.defaultChoice(scope: scope, field: prefix + "." + key), !choice.entry.value["userName"].text.isEmpty {
                selections[key] = [choice.entry.value["userName"].text]
            }
        }
    }
    func applyDefaults(_ session: LiveSession, store: PickerPrefsStore = .shared) async {
        applyApproverDefaults(prefix: kind == "ASSET_FAULT" ? "assignee.fault" : "assignee.erp", scope: session.draftScope, store: store)
        for item in Self.defaultFields(kind: kind) {
            guard let choice = store.defaultChoice(scope: session.draftScope, field: item.field),
                  let result = try? await resolveReference(item.source, choice.entry.value, session: session) else { continue }
            if kind == "ASSET_FAULT" && item.dataKey == "dept" { selectReceiverDepartment(result) }
            else {
                data[item.dataKey] = result
                if kind == "ASSET_FAULT" && item.dataKey == "applyUser", let department = result["depts"].array.first { data["applyDept"] = department }
            }
        }
    }
}
