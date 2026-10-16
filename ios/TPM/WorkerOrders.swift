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
    /// Sort key: the name in pinyin, so people are listed A to Z.
    var pinyin: String { pinyinKey(name) }
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

/// All orders in one state, read 100 at a time. Stops at the last page, with a cap against runaway lists.
func fetchAllOrders(session: LiveSession, state: String) async throws -> [JSONValue] {
    var orders: [JSONValue] = []
    for _ in 0..<30 {
        let page = try await session.page("fault/pageAssetFault", offset: orders.count, filters: [filter("faultState", state)], limit: 100)
        orders += page["content"].array
        if page["last"].bool || page["content"].array.isEmpty { break }
    }
    return orders
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
