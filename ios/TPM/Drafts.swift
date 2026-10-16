import SwiftUI

// The draft box: a repair report that was filled in but not saved or submitted to the server is kept on this
// phone, per account and per factory, so closing the form never loses the typing. A draft is only ever
// local: the form's "保存" writes one, only "提交" talks to the server. Drafts hold what the form
// holds (names, references, uploaded photo references), never passwords or tokens.

struct FaultDraft: Codable, Identifiable, Equatable {
    var id = UUID()
    var savedAt: Date
    var data: Fields
    var selections: [String: [String]]
    /// The order this draft belongs to: nil for a new report that has no number yet, otherwise the
    /// id of the existing order being edited. A draft is only ever offered to the form of its own order.
    var orderID: String? = nil
    /// The order's number, for showing which order a draft belongs to.
    var orderSN: String? = nil

    /// A draft is named by when it was last edited, to the minute: "2026-10-16 14:32". Saving it again
    /// moves the time, and so the title, forward.
    var title: String { Self.titleFormat(savedAt) }
    static func titleFormat(_ date: Date, calendar: Calendar = .current) -> String {
        let formatter = DateFormatter()
        formatter.calendar = calendar
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }
    /// What the draft is about, as far as it was filled in: the report name, else the asset, else the description.
    var subtitle: String {
        let prefix = (orderSN ?? "").isEmpty ? "" : "单号 " + (orderSN ?? "") + " · "
        return prefix + summary
    }
    private var summary: String {
        for value in [data.text("faultName"), data.text("asset.assetName"), data.text("faultDesc")] {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
        return "还没有填写内容"
    }
}

/// Whether the form holds anything the person typed or chose. The values the form fills in by itself
/// (the applicant, phone number and fault time) do not count, or every opened form would ask to be saved.
func faultFormHasContent(_ data: Fields) -> Bool {
    for key in ["faultName", "faultDesc"] where !data.text(key).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return true }
    for key in ["asset", "dept", "user", "userGroup", "division"] where !(data[key]?.object.isEmpty ?? true) { return true }
    return !(data["albums"]?.array.isEmpty ?? true)
}

struct DraftStore {
    var url: URL
    static let shared = DraftStore(url: {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first ?? FileManager.default.temporaryDirectory
        return base.appendingPathComponent("Drafts", isDirectory: true).appendingPathComponent("fault-drafts.json")
    }())

    private func readAll() -> [String: [FaultDraft]] {
        guard let data = try? Data(contentsOf: url) else { return [:] }
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode([String: [FaultDraft]].self, from: data)) ?? [:]
    }
    private func writeAll(_ all: [String: [FaultDraft]]) throws {
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try encoder.encode(all).write(to: url, options: [.atomic, .completeFileProtection])
    }

    /// Which drafts a form or a list may see. A form only ever gets the drafts of its own order.
    enum Target: Equatable {
        case any
        case newReport
        case order(String)
        func matches(_ draft: FaultDraft) -> Bool {
            switch self {
            case .any: true
            case .newReport: draft.orderID == nil
            case .order(let id): draft.orderID == id
            }
        }
    }
    /// Newest first.
    func drafts(scope: String, for target: Target = .any) -> [FaultDraft] {
        (readAll()[scope] ?? []).filter(target.matches).sorted { $0.savedAt > $1.savedAt }
    }
    /// Saves a new draft, or replaces the one with the same id.
    func save(_ draft: FaultDraft, scope: String) throws {
        var all = readAll()
        var list = all[scope] ?? []
        if let index = list.firstIndex(where: { $0.id == draft.id }) { list[index] = draft } else { list.append(draft) }
        all[scope] = list
        try writeAll(all)
    }
    func remove(id: UUID, scope: String) {
        var all = readAll()
        guard var list = all[scope], list.contains(where: { $0.id == id }) else { return }
        list.removeAll { $0.id == id }
        all[scope] = list.isEmpty ? nil : list
        try? writeAll(all)
    }
}

extension LiveSession {
    /// Drafts belong to one account in one factory, like the orders they will become.
    var draftScope: String { authentication.text("name") + "@" + currentFactoryName }
}

/// The list of drafts. Choosing one hands it to `onPick`; swiping removes it.
struct DraftBoxView: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    var dismissOnPick = false
    var target: DraftStore.Target = .any
    var store = DraftStore.shared
    let onPick: (FaultDraft) -> Void
    @State private var drafts: [FaultDraft] = []
    var body: some View {
        List {
            if drafts.isEmpty {
                VStack(spacing: 10) {
                    Image(systemName: "tray").font(.system(size: 28)).foregroundStyle(.secondary)
                    Text("草稿箱是空的").font(.headline)
                    Text("填写报修时点“取消”，可以把没填完的内容存成草稿").font(.subheadline).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }.frame(maxWidth: .infinity).padding(.vertical, 28).listRowBackground(Color.clear)
            }
            ForEach(drafts) { draft in
                Button {
                    if dismissOnPick { dismiss() }
                    onPick(draft)
                } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(draft.title).font(.headline).foregroundStyle(Color.accentColor)
                        Text(draft.subtitle).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                    }.frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect)
                }
                .buttonStyle(.plain)
                .swipeActions { Button("删除", role: .destructive) { store.remove(id: draft.id, scope: session.draftScope); reload() } }
                .accessibilityIdentifier("draft-" + draft.id.uuidString)
            }
        }
        .navigationTitle("草稿箱").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .onAppear { reload() }
    }
    private func reload() { drafts = store.drafts(scope: session.draftScope, for: target) }
}
