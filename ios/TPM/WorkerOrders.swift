import SwiftUI

// "人员处理中": for every person, how many orders are waiting for them to sign in or are being repaired,
// and the orders themselves. It is the order search grouped by executor: the orders of the two states are
// read page by page with the same list query the order pages use, then grouped by who took them.

/// One person's orders in progress.
struct WorkerLoad: Identifiable, Hashable {
    let name: String
    let userName: String
    var signed: [JSONValue]
    var working: [JSONValue]
    var id: String { userName.isEmpty ? name : userName }
    var count: Int { signed.count + working.count }
    /// Sort key: the name in pinyin, so people are listed A to Z. Worked out once, not on every sort or redraw.
    let pinyin: String
    init(name: String, userName: String, signed: [JSONValue], working: [JSONValue]) {
        self.name = name; self.userName = userName; self.signed = signed; self.working = working
        self.pinyin = pinyinKey(name)
    }
    /// A–Z, or "#" for a name that does not start with a letter in pinyin.
    var initial: String {
        guard let first = pinyin.uppercased().first, first.isASCII, first.isLetter else { return "#" }
        return String(first)
    }
}

/// Hanzi to toneless pinyin without spaces ("张三" becomes "zhangsan"); other text is left as it is.
func pinyinKey(_ text: String) -> String {
    let latin = text.applyingTransform(.mandarinToLatin, reverse: false) ?? text
    let plain = latin.applyingTransform(.stripDiacritics, reverse: false) ?? latin
    return plain.lowercased().filter { !$0.isWhitespace }
}

/// Groups the orders waiting to be signed in and the orders being repaired by their executor and
/// sorts the people A to Z by pinyin. An order without an executor is not listed under anyone.
func groupWorkers(signed: [JSONValue], working: [JSONValue]) -> [WorkerLoad] {
    var people: [String: WorkerLoad] = [:]
    func add(_ order: JSONValue, signedIn: Bool) {
        let who = order["repairUser"]
        let name = who["realName"].text.trimmingCharacters(in: .whitespacesAndNewlines)
        let userName = who["userName"].text
        guard !name.isEmpty || !userName.isEmpty else { return }
        let key = userName.isEmpty ? name : userName
        var entry = people[key] ?? WorkerLoad(name: name.isEmpty ? userName : name, userName: userName, signed: [], working: [])
        if signedIn { entry.signed.append(order) } else { entry.working.append(order) }
        people[key] = entry
    }
    signed.forEach { add($0, signedIn: true) }
    working.forEach { add($0, signedIn: false) }
    return people.values.sorted { a, b in a.pinyin == b.pinyin ? a.name < b.name : a.pinyin < b.pinyin }
}

/// Reads the orders waiting for sign-in and those being repaired, 100 at a time, and groups them by
/// executor. After every page it reports the people found so far, so the first people appear as soon as
/// the first page is in and the rest follow, instead of the whole section waiting for the last page.
@MainActor
final class WorkerOrderReader {
    private var signed: [JSONValue] = []
    private var working: [JSONValue] = []

    func read(session: LiveSession, progress: (([WorkerLoad], Int) -> Void)? = nil) async throws -> [WorkerLoad] {
        for (state, isSigned) in [("SIGNED", true), ("REPAIRED", false)] {
            var offset = 0
            // Capped against a runaway list.
            while offset < 3000 {
                let page = try await session.page("fault/pageAssetFault", offset: offset, filters: [filter("faultState", state)], limit: 100)
                let rows = page["content"].array
                if isSigned { signed += rows } else { working += rows }
                offset += rows.count
                if let progress { progress(groupWorkers(signed: signed, working: working), signed.count + working.count) }
                if page["last"].bool || rows.isEmpty { break }
            }
        }
        return groupWorkers(signed: signed, working: working)
    }
}

/// Where the people section is in its reading, so it is always clear whether more is still arriving.
enum WorkerStatus: Equatable {
    case idle
    case reading(Int)
    case done(Date, orders: Int, people: Int)
    case failed
}

/// The section heading with its status on the right: a spinner and the number of orders read so far while
/// it is still reading, and a clear "完成" with the time once the last page is in.
struct WorkerSectionHeader: View {
    let status: WorkerStatus
    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            Text("人员处理中").font(.title3.weight(.semibold)).foregroundStyle(.primary)
            Spacer(minLength: 8)
            statusView.font(.footnote).foregroundStyle(.secondary).lineLimit(1).minimumScaleFactor(0.8)
        }
        .padding(.top, 8).accessibilityElement(children: .combine).accessibilityAddTraits(.isHeader)
        .animation(.smooth(duration: 0.4), value: status)
    }
    @ViewBuilder private var statusView: some View {
        switch status {
        case .idle: EmptyView()
        case .reading(let n):
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text("读取中 · 已读 \(n) 单").contentTransition(.numericText())
            }
        case .done(let date, let orders, let people):
            HStack(spacing: 5) {
                Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                Text("已刷新完成 · \(people) 人 \(orders) 单 · ") + Text(date, format: .dateTime.hour().minute().second())
            }
        case .failed:
            HStack(spacing: 5) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(.orange)
                Text("读取失败，请点右上角刷新")
            }
        }
    }
}

/// Letter headings between the people, as in a contacts list.
struct LetterHeading: View {
    let letter: String
    var body: some View {
        Text(letter).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 4).accessibilityAddTraits(.isHeader)
    }
}

/// The orders of one person: those waiting for the sign-in and those being repaired.
struct WorkerOrdersView: View {
    let worker: WorkerLoad
    var body: some View {
        CardPage {
            if !worker.signed.isEmpty {
                section("待签到", worker.signed)
            }
            if !worker.working.isEmpty {
                section("待完成", worker.working)
            }
            if worker.count == 0 { CompactEmptyState(title: "暂无处理中的工单") }
        }
        .navigationTitle(worker.name).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .modifier(ListTabVisibility())
    }
    @ViewBuilder private func section(_ title: String, _ orders: [JSONValue]) -> some View {
        Text("\(title) · \(orders.count)").font(.title3.weight(.semibold))
            .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16)
        CardGroup {
            ForEach(Array(orders.enumerated()), id: \.offset) { _, item in
                LiveOrderRow(kind: .fault, item: item).equatable()
            }
        }
    }
}
