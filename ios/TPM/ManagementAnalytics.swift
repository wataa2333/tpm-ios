import Foundation
import SwiftUI
import CryptoKit

enum ManagementSettings { static let enabledKey = "developer.managementDashboard" }

enum ManagedKind: String, CaseIterable, Identifiable, Sendable, Codable {
    case repair, maintenance, precision
    var id: String { rawValue }
    var title: String { switch self { case .repair: "维修"; case .maintenance: "保养"; case .precision: "精密度检查" } }
    var page: String { switch self { case .repair: "fault/pageAssetFault"; case .maintenance: "maintain/pageMaintain"; case .precision: "precise/pagePrecise" } }
    var idField: String { switch self { case .repair: "faultId"; case .maintenance: "maintainId"; case .precision: "preciseId" } }
    var nameField: String { switch self { case .repair: "faultName"; case .maintenance: "maintainName"; case .precision: "preciseName" } }
    var stateField: String { switch self { case .repair: "faultState"; case .maintenance: "maintainState"; case .precision: "preciseState" } }
    var workingState: String { switch self { case .repair: "REPAIRED"; case .maintenance: "MAINTAINED"; case .precision: "PRECISED" } }
    var executorField: String { switch self { case .repair: "repairUser"; case .maintenance: "maintainUser"; case .precision: "user" } }
    var endField: String { self == .repair ? "faultTime" : "endTime" }
}

struct ManagedWindow: Equatable, Codable {
    let from: String
    let to: String
    let dayCount: Int
    var requiresLargeFetchConfirmation: Bool { dayCount > 7 }
    init(days: Int, now: Date = Date(), calendar: Calendar = .current) {
        var businessCalendar = Calendar(identifier: .gregorian)
        businessCalendar.timeZone = calendar.timeZone
        let today = businessCalendar.startOfDay(for: now)
        let start = businessCalendar.date(byAdding: .day, value: -(days - 1), to: today) ?? today
        self.init(range: DateRange(from: start, to: today), calendar: businessCalendar)
    }
    init(range: DateRange, calendar: Calendar = .current) {
        var businessCalendar = Calendar(identifier: .gregorian)
        businessCalendar.timeZone = calendar.timeZone
        let start = businessCalendar.startOfDay(for: min(range.from, range.to))
        let end = businessCalendar.startOfDay(for: max(range.from, range.to))
        let formatter = DateFormatter()
        formatter.calendar = businessCalendar; formatter.timeZone = calendar.timeZone
        formatter.locale = Locale(identifier: "en_US_POSIX"); formatter.dateFormat = "yyyy-MM-dd"
        from = formatter.string(from: start); to = formatter.string(from: end)
        dayCount = (businessCalendar.dateComponents([.day], from: start, to: end).day ?? 0) + 1
    }
    func contains(_ value: JSONValue) -> Bool {
        guard let day = managementDay(value) else { return false }
        return day >= from && day <= to
    }
}

/// Reject missing/invalid dates, rather than using parsedDate's form-editing fallback to today's date.
func managementDay(_ value: JSONValue) -> String? {
    let parts: [Int]
    if value.array.count >= 3 {
        let values = value.array.prefix(3).map(\.number)
        guard values.allSatisfy({ $0.isFinite && $0.rounded() == $0 && $0 >= 0 && $0 <= 9999 }) else { return nil }
        parts = values.map(Int.init)
    } else {
        let text = String(value.text.prefix(10))
        let values = text.split(separator: "-", omittingEmptySubsequences: false)
        guard text.count == 10, values.count == 3, values[0].count == 4, values[1].count == 2, values[2].count == 2,
              let year = Int(values[0]), let month = Int(values[1]), let day = Int(values[2]) else { return nil }
        parts = [year, month, day]
    }
    guard parts[0] > 0, (1...12).contains(parts[1]), (1...31).contains(parts[2]) else { return nil }
    let calendar = Calendar(identifier: .gregorian)
    guard let date = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])) else { return nil }
    let components = calendar.dateComponents([.year, .month, .day], from: date)
    guard components.year == parts[0], components.month == parts[1], components.day == parts[2] else { return nil }
    return String(format: "%04d-%02d-%02d", parts[0], parts[1], parts[2])
}

private func managedIdentifier(_ value: JSONValue) -> String {
    switch value {
    case .string(let text):
        let result = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return result.contains("${") || result.contains("#{") ? "" : result
    case .number(let number): return number.isFinite && number >= 0 ? value.text : ""
    default: return ""
    }
}

struct ManagedDocument: Identifiable, Equatable, Sendable, Codable {
    let kind: ManagedKind
    let businessID: String
    let title: String
    let number: String
    let state: String
    let account: String
    let personID: String
    let personName: String
    let identityConflict: Bool
    let end: String
    var id: String { kind.rawValue + ":" + businessID }
    var stateTitle: String {
        if state == kind.workingState { return "待完成" }
        return switch state {
        case "SIGNED": "待签到"
        case "VERIFIED": "已提交处理／待确认"
        case "RESOLVED": "已提交处理／待复线"
        case "FINISHED": "已提交处理／已完结"
        default: state
        }
    }
}

func managedDocument(kind: ManagedKind, row: JSONValue, state: String, window: ManagedWindow?) -> ManagedDocument? {
    let businessID = managedIdentifier(row[kind.idField])
    guard !businessID.isEmpty, row[kind.stateField].text == state else { return nil }
    if let window, !window.contains(row[kind.endField]) { return nil }
    let executor = row[kind.executorField]
    let firstID = managedIdentifier(executor["id"]), secondID = managedIdentifier(executor["userId"])
    return ManagedDocument(kind: kind, businessID: businessID,
                           title: row[kind.nameField].text.isEmpty ? kind.title + "工单" : row[kind.nameField].text,
                           number: row["sn"].text, state: state, account: managedIdentifier(executor["userName"]),
                           personID: firstID.isEmpty ? secondID : firstID, personName: executor["realName"].text,
                           identityConflict: !firstID.isEmpty && !secondID.isEmpty && firstID != secondID,
                           end: managementDay(row[kind.endField]) ?? "")
}

/// No client total cap. A response is committed only after its detail checks finish.
struct ManagedSlice: Identifiable, Codable {
    let kind: ManagedKind
    let state: String
    var id: String { kind.rawValue + ":" + state }
    var offset = 0
    var expected: Int?
    var documents: [String: ManagedDocument] = [:]
    var reasons = Set<String>()
    var finished = false
    var complete = false
    var failed = false
    private var previousPage: [JSONValue]?
    var pageSize = 500
    var requestLimit: Int { pageSize }
    init(kind: ManagedKind, state: String) { self.kind = kind; self.state = state }
    func filters(window: ManagedWindow?) -> [JSONValue] {
        var result = [filter(kind.stateField, state)]
        if let window {
            result.append(.object(["field": .string(kind.endField), "operator": .string("dateBetween"),
                                   "logic": .string("and"), "value": .array([.string(window.from), .string(window.to)])]))
        }
        return result
    }
    mutating func consume(_ page: JSONValue, requested: Int, window: ManagedWindow?) throws {
        guard requested > 0, case .array(let raw) = page["content"] else {
            throw APIError(message: "分页响应格式或游标无效")
        }
        if page["totalElements"] != .null {
            guard case .number(let total) = page["totalElements"], total.isFinite, total >= 0,
                  total.rounded() == total, total < Double(Int.max) else {
                throw APIError(message: "服务端分页总数无效")
            }
            expected = Int(total)
        }
        guard raw.count <= requested else { throw APIError(message: "服务端返回超过请求数量") }
        let repeatedPage = previousPage == raw
        previousPage = raw
        for row in raw {
            let businessID = managedIdentifier(row[kind.idField])
            guard !businessID.isEmpty else { reasons.insert("业务编号缺失"); continue }
            let key = kind.rawValue + ":" + businessID
            // Historical results use the last observed row, including removal from the range.
            if window != nil {
                documents.removeValue(forKey: key)
                let actualState = row[kind.stateField].text
                guard !actualState.isEmpty, managementDay(row[kind.endField]) != nil else {
                    reasons.insert("工单状态或故障发现日期缺失"); continue
                }
                if ["VERIFIED", "RESOLVED", "FINISHED"].contains(actualState),
                   let doc = managedDocument(kind: kind, row: row, state: actualState, window: window) {
                    documents[key] = doc
                }
            } else if let doc = managedDocument(kind: kind, row: row, state: state, window: nil) {
                documents[key] = doc
            } else { reasons.insert("详情状态变化或字段缺失，请重新读取") }
        }
        offset += raw.count
        let last: Bool?
        if case .bool(let value) = page["last"] { last = value }
        else if let expected { last = offset >= expected }
        else { last = nil }
        if last == true { finished = true }
        else if raw.isEmpty || repeatedPage {
            reasons.insert("分页未前进，已停止，请重新读取"); finished = true
        } else if last == nil && raw.count < requested {
            reasons.insert("服务端未提供可信的分页结束标记"); finished = true
        }
        complete = finished && last == true && reasons.isEmpty
    }
}

struct ManagedPerson: Identifiable, Codable {
    let id: String
    let name: String
    let identity: String
    var documents: [ManagedDocument]
    var savedSigned: Int?
    var savedWorking: Int?
    var savedProcessed: Int?
    var savedDays: [String: Int]?
    var signed: Int { savedSigned ?? documents.filter { $0.state == "SIGNED" }.count }
    var working: Int { savedWorking ?? documents.filter { $0.state == $0.kind.workingState }.count }
    var processed: Int { savedProcessed ?? (documents.count - signed - working) }
    var summary: ManagedPerson {
        var result = self
        result.savedSigned = signed; result.savedWorking = working; result.savedProcessed = processed
        if savedDays == nil {
            result.savedDays = Dictionary(grouping: documents.filter { ["VERIFIED", "RESOLVED", "FINISHED"].contains($0.state) }, by: \.end).mapValues { $0.count }
        }
        result.documents = []
        return result
    }
}

struct ManagedReport: Codable {
    var people: [ManagedPerson] = []
    var duplicateConflicts = false
    var identityUnclear = false
    var count: Int { people.reduce(0) { $0 + $1.signed + $1.working + $1.processed } }
    var summary: ManagedReport {
        var result = self; result.people = people.map(\.summary); return result
    }
    static let unknown = "unknown-executor"
}

/// Names never identify a person. Resolve account/ID aliases only when the evidence is one-to-one.
func managedReport(_ input: [ManagedDocument]) -> ManagedReport {
    var unique: [String: ManagedDocument] = [:], conflicted = Set<String>()
    for document in input {
        if let previous = unique[document.id], previous != document { conflicted.insert(document.id) }
        else { unique[document.id] = document }
    }
    let documents = unique.values.filter { !conflicted.contains($0.id) }
    var usersByID: [String: Set<String>] = [:], idsByUser: [String: Set<String>] = [:]
    for doc in documents where !doc.identityConflict && !doc.account.isEmpty && !doc.personID.isEmpty {
        usersByID[doc.personID, default: []].insert(doc.account)
        idsByUser[doc.account, default: []].insert(doc.personID)
    }
    var people: [String: ManagedPerson] = [:]
    for doc in documents {
        var account = doc.account
        let conflictingAlias = (idsByUser[account] ?? []).contains { (usersByID[$0]?.count ?? 0) > 1 }
        let conflict = doc.identityConflict || conflictingAlias || (usersByID[doc.personID]?.count ?? 0) > 1 || (idsByUser[account]?.count ?? 0) > 1
        if account.isEmpty, let aliases = usersByID[doc.personID], aliases.count == 1 { account = aliases.first ?? "" }
        let ambiguous = conflict || (idsByUser[account]?.count ?? 0) > 1
        let key = ambiguous ? ManagedReport.unknown : !account.isEmpty ? "account:" + account : !doc.personID.isEmpty ? "id:" + doc.personID : ManagedReport.unknown
        if people[key] == nil {
            people[key] = ManagedPerson(id: key,
                name: key == ManagedReport.unknown ? "执行人未明确" : doc.personName.isEmpty ? (!account.isEmpty ? account : "用户 " + doc.personID) : doc.personName,
                identity: key == ManagedReport.unknown ? "缺少唯一身份或账号/ID存在冲突" : !account.isEmpty ? "账号：" + account : "ID：" + doc.personID,
                documents: [])
        }
        people[key]?.documents.append(doc)
    }
    var report = ManagedReport()
    report.people = people.values.sorted { a, b in
        if a.signed + a.working != b.signed + b.working { return a.signed + a.working > b.signed + b.working }
        if a.processed != b.processed { return a.processed > b.processed }
        return a.name == b.name ? a.id < b.id : a.name < b.name
    }
    report.duplicateConflicts = !conflicted.isEmpty
    report.identityUnclear = people[ManagedReport.unknown] != nil
    return report
}

@MainActor final class ManagementAnalyticsModel: ObservableObject {
    private let historicalMode: Bool?
    init(historical: Bool? = nil) { historicalMode = historical }
    private var cacheSuffix: String {
        historicalMode.map { $0 ? ".history.summary.json" : ".pending.summary.json" } ?? ".summary.json"
    }
    private struct SliceSummary: Codable {
        let kind: ManagedKind
        let state: String
        let offset: Int
        let expected: Int?
        let reasons: Set<String>
        let finished: Bool
        let complete: Bool
        let failed: Bool
        let pageSize: Int
        init(_ slice: ManagedSlice) {
            kind = slice.kind; state = slice.state; offset = slice.offset; expected = slice.expected
            reasons = slice.reasons; finished = slice.finished; complete = slice.complete
            failed = slice.failed; pageSize = slice.pageSize
        }
        var slice: ManagedSlice {
            var result = ManagedSlice(kind: kind, state: state)
            result.offset = offset; result.expected = expected; result.reasons = reasons
            result.finished = finished; result.complete = complete; result.failed = failed; result.pageSize = pageSize
            return result
        }
    }
    private struct Snapshot: Codable {
        let version: Int
        let savedAt: Date
        let slices: [SliceSummary]
        let report: ManagedReport
        let window: ManagedWindow?
        let concurrency: Int
        let elapsedSeconds: TimeInterval?
    }
    typealias PageReader = @MainActor (ManagedSlice, ManagedWindow?) async throws -> JSONValue
    @Published private(set) var slices: [ManagedSlice] = []
    @Published private(set) var report = ManagedReport()
    @Published private(set) var running = false
    @Published private(set) var paused = false
    @Published private(set) var estimating = false
    @Published private(set) var detailChecked = 0
    @Published private(set) var detailExpected = 0
    @Published private(set) var estimatedCount: Int?
    @Published private(set) var window: ManagedWindow?
    @Published private(set) var cachedAt: Date?
    @Published private(set) var showingPrevious = false
    @Published private(set) var summaryOnly = false
    private var cacheURL: URL?
    /// Restore once per dashboard lifetime and account/factory scope, never on a child-page return.
    private var restoredScope: UUID?
    var cachedRange: DateRange? {
        guard let window else { return nil }
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        guard let from = formatter.date(from: window.from), let to = formatter.date(from: window.to) else { return nil }
        return DateRange(from: from, to: to)
    }
    func restorePrevious(session: LiveSession) {
        if let scope, scope != session.scopeRevision { clear() }
        guard restoredScope != session.scopeRevision else { return }
        restoredScope = session.scopeRevision
        guard slices.isEmpty else { return }
        let key = SHA256.hash(data: Data(session.draftScope.utf8)).map { String(format: "%02x", $0) }.joined()
        guard let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return }
        let directory = folder.appendingPathComponent("ManagementCache", isDirectory: true)
        // Retire the old document cache without decoding its potentially large contents.
        try? FileManager.default.removeItem(at: directory.appendingPathComponent(key + ".json"))
        let url = directory.appendingPathComponent(key + cacheSuffix)
        cacheURL = url
        let legacy = directory.appendingPathComponent(key + ".summary.json")
        guard let data = (try? Data(contentsOf: url)) ?? (try? Data(contentsOf: legacy)),
              let saved = try? JSONDecoder().decode(Snapshot.self, from: data),
              saved.version == 4 || (saved.window == nil && [2, 3].contains(saved.version)),
              historicalMode == nil || historicalMode == (saved.window != nil) else { return }
        slices = saved.slices.map(\.slice); window = saved.window; detailConcurrency = saved.concurrency
        report = saved.report; summaryOnly = saved.version == 2
        if !summaryOnly {
            let documents = report.people.flatMap(\.documents)
            guard Set(documents.map(\.id)).count == documents.count else { clear(); return }
            for index in slices.indices {
                slices[index].documents = Dictionary(uniqueKeysWithValues: documents.filter { $0.kind == slices[index].kind && $0.state == slices[index].state }.map { ($0.id, $0) })
            }
        }
        scope = session.scopeRevision; paused = hasUnfinished
        cachedAt = saved.savedAt; showingPrevious = true
        elapsedSeconds = saved.elapsedSeconds ?? 0
    }
    private func savePrevious() {
        guard !showingPrevious, let url = cacheURL, !slices.isEmpty,
              slices.contains(where: { $0.offset > 0 }) || slices.allSatisfy({ $0.complete }) else { return }
        let date = Date()
        let saved = Snapshot(version: 4, savedAt: date, slices: slices.map(SliceSummary.init), report: report, window: window, concurrency: detailConcurrency, elapsedSeconds: elapsedSeconds)
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            var folder = url.deletingLastPathComponent()
            var values = URLResourceValues(); values.isExcludedFromBackup = true
            try folder.setResourceValues(values)
            let data = try JSONEncoder().encode(saved)
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            cachedAt = date
        } catch { /* A cache failure must not discard the visible report. */ }
    }
    private(set) var scope: UUID?
    private(set) var detailConcurrency = 8
    private var generation = UUID()
    private var runner: Task<Void, Never>?
    @Published private(set) var elapsedSeconds: TimeInterval = 0
    private var timer: Task<Void, Never>?
    private var readStarted: TimeInterval?
    private var elapsedBeforeRead: TimeInterval = 0
    private func finishTiming() {
        if let readStarted { elapsedSeconds = elapsedBeforeRead + ProcessInfo.processInfo.systemUptime - readStarted }
        readStarted = nil; timer?.cancel(); timer = nil
    }

    var hasUnfinished: Bool { !showingPrevious && !summaryOnly && slices.contains { !$0.finished || $0.failed } }
    var canShowTrends: Bool {
        !running && !paused && !estimating && !slices.isEmpty
            && slices.allSatisfy { $0.complete && !$0.failed }
    }
    var incomplete: Bool {
        running || paused || slices.isEmpty || slices.contains { !$0.complete } || report.duplicateConflicts || report.identityUnclear
    }
    var status: String {
        if estimating { return "正在预估读取数量…" }
        if slices.isEmpty { return "尚未读取" }
        let read = slices.reduce(0) { $0 + $1.offset }
        let expected = slices.compactMap(\.expected).reduce(0, +)
        let progress = "已读取 \(read) 条" + (expected > 0 ? "／约 \(expected) 条" : "") + " · 有效 \(report.count) 单"
        if running { return "读取中／" + progress + (detailExpected > 0 ? " · 本页详情 \(detailChecked)/\(detailExpected)" : "") }
        if paused { return "已暂停／" + progress }
        return (incomplete ? "结果不完整／" : "本轮读取结束／") + progress
    }
    func clear() {
        finishTiming(); runner?.cancel(); runner = nil; generation = UUID()
        running = false; paused = false; estimating = false; estimatedCount = nil
        slices = []; report = ManagedReport(); window = nil; scope = nil; detailChecked = 0; detailExpected = 0
        cachedAt = nil; showingPrevious = false; cacheURL = nil
        summaryOnly = false; elapsedSeconds = 0
    }
    func pause() {
        finishTiming(); runner?.cancel(); runner = nil; generation = UUID(); running = false
        paused = hasUnfinished; estimating = false
        savePrevious()
    }
    /// Each state is estimated with limit=1; no detail requests until the user confirms.
    func estimate(session: LiveSession, historical: Bool, range: DateRange?) async throws -> Int {
        guard !running && !estimating else { throw APIError(message: "已有读取正在进行") }
        let token = UUID(), capturedScope = session.scopeRevision
        generation = token; estimating = true; estimatedCount = nil
        defer { if generation == token { estimating = false } }
        let selected = historical ? range.map { ManagedWindow(range: $0) } : nil
        if historical && selected == nil { throw APIError(message: "请先选择故障发现日期范围") }
        var count = 0
        for state in historical ? ["VERIFIED", "RESOLVED", "FINISHED"] : ["SIGNED", "REPAIRED"] {
            let slice = ManagedSlice(kind: .repair, state: state)
            let page = try await session.page(slice.kind.page, filters: slice.filters(window: selected), limit: 1)
            try Task.checkCancellation()
            guard generation == token && capturedScope == session.scopeRevision else { throw CancellationError() }
            guard case .number(let rawTotal) = page["totalElements"], rawTotal.rounded() == rawTotal,
                  let total = serverPageCount(page), total >= 0, total <= Int.max - count else {
                throw APIError(message: "服务器未提供有效预估总数，请重试")
            }
            count += total
        }
        estimatedCount = count
        return count
    }
    func start(session: LiveSession, days: Int?, restart: Bool = false, range: DateRange? = nil,
               pageSize: Int = 500, concurrency: Int = 8, pageReader: PageReader? = nil) {
        guard !running && !estimating else { return }
        // Bind persistence only for live reads, never offline layout fixtures.
        let key = SHA256.hash(data: Data(session.draftScope.utf8)).map { String(format: "%02x", $0) }.joined()
        let selected = range.map { ManagedWindow(range: $0) } ?? days.map { ManagedWindow(days: $0) }
        if restart || showingPrevious || summaryOnly || scope != session.scopeRevision || window != selected || slices.isEmpty {
            clear(); scope = session.scopeRevision; window = selected
            detailConcurrency = ManagedReadSettings.concurrencyLevels.contains(concurrency) ? concurrency : 8
            slices = (selected == nil ? ["SIGNED", "REPAIRED"] : ["VERIFIED", "RESOLVED", "FINISHED"]).map {
                var slice = ManagedSlice(kind: .repair, state: $0)
                slice.pageSize = ManagedReadSettings.pageSizes.contains(pageSize) ? pageSize : 500
                return slice
            }
        }
        for i in slices.indices where slices[i].failed {
            slices[i].failed = false; slices[i].finished = false
            slices[i].reasons = slices[i].reasons.filter { !$0.hasPrefix("读取失败：") }
        }
        if pageReader == nil, let folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first {
            cacheURL = folder.appendingPathComponent("ManagementCache", isDirectory: true).appendingPathComponent(key + cacheSuffix)
        }
        guard hasUnfinished else { return }
        showingPrevious = false
        running = true; paused = false
        elapsedBeforeRead = elapsedSeconds
        readStarted = ProcessInfo.processInfo.systemUptime
        timer = Task { [weak self] in
            while !Task.isCancelled {
                guard let self, let began = self.readStarted else { return }
                self.elapsedSeconds = self.elapsedBeforeRead + ProcessInfo.processInfo.systemUptime - began
                do { try await Task.sleep(for: .milliseconds(250)) } catch { return }
            }
        }
        let token = UUID(), capturedScope = session.scopeRevision
        let limitConcurrency = detailConcurrency
        let reader: PageReader = pageReader ?? { slice, window in
            let page = try await session.page(slice.kind.page, offset: slice.offset, filters: slice.filters(window: window), limit: slice.requestLimit)
            guard case .array(let rows) = page["content"], rows.count <= slice.requestLimit else {
                throw APIError(message: "服务端分页格式无效或返回超量")
            }
            guard window == nil else { return page }
            self.detailExpected = rows.count; self.detailChecked = 0
            let checked = try await resolveManagedExecutors(rows, session: session, concurrency: limitConcurrency) { count in
                if self.generation == token { self.detailChecked = count }
            }
            var fields = page.object; fields["content"] = .array(checked)
            return .object(fields)
        }
        generation = token
        runner = Task { [weak self] in
            guard let self else { return }
            await self.read(session: session, token: token, capturedScope: capturedScope, reader: reader)
        }
    }
    private func read(session: LiveSession, token: UUID, capturedScope: UUID, reader: PageReader) async {
        defer { if generation == token { finishTiming(); running = false; runner = nil; savePrevious() } }
        for index in slices.indices {
            while generation == token && !slices[index].finished {
                guard session.scopeRevision == capturedScope else { clear(); return }
                do {
                    try Task.checkCancellation()
                    var slice = slices[index]
                    let page = try await reader(slice, window)
                    try Task.checkCancellation()
                    guard generation == token else { return }
                    guard session.scopeRevision == capturedScope else { clear(); return }
                    try slice.consume(page, requested: slice.requestLimit, window: window)
                    // The last response wins across state cursors as well as within a cursor.
                    if window != nil {
                        let ids = Set(page["content"].array.map { "repair:" + managedIdentifier($0["faultId"]) })
                        for other in slices.indices where other != index {
                            for id in ids { slices[other].documents.removeValue(forKey: id) }
                        }
                    }
                    slices[index] = slice
                } catch {
                    guard generation == token else { return }
                    guard session.scopeRevision == capturedScope else { clear(); return }
                    if isCancellation(error) { paused = hasUnfinished; return }
                    slices[index].reasons.insert("读取失败：" + error.localizedDescription)
                    slices[index].finished = true; slices[index].complete = false; slices[index].failed = true
                }
                report = managedReport(slices.flatMap { Array($0.documents.values) })
                savePrevious()
            }
        }
    }
}

/// Read-only: the first repair record owns the executor, including SIGNED orders.
@MainActor func checkedManagedExecutor(_ row: JSONValue, session: LiveSession) async throws -> JSONValue {
    let id = managedIdentifier(row["faultId"])
    guard !id.isEmpty else { throw APIError(message: "待处理工单缺少编号") }
    let detail = try await session.request("fault/getAssetFaultDetailById", query: ["faultId": id])
    guard detail["faultId"].text == id, detail["faultState"].text == row["faultState"].text else {
        throw APIError(message: "详情编号或状态已变化，请重新读取")
    }
    let answer = try await session.request("repair/getRepairsByFaultId", query: ["faultId": id])
    return try managedExecutorRow(row, detail: detail, answer: answer)
}

func managedExecutorRow(_ row: JSONValue, detail: JSONValue, answer: JSONValue) throws -> JSONValue {
    let id = managedIdentifier(row["faultId"])
    guard !id.isEmpty, detail["faultId"].text == id, detail["faultState"].text == row["faultState"].text else {
        throw APIError(message: "详情编号或状态已变化，请重新读取")
    }
    let repair = latestRepair(answer) ?? .null
    let owner = repair["assetFault"]
    guard owner["faultId"].text.isEmpty || owner["faultId"].text == id else { throw APIError(message: "维修记录关联工单不符") }
    let task = detail["taskInfo"]["id"].text, repairTask = owner["taskInfo"]["id"].text
    guard task.isEmpty || repairTask.isEmpty || task == repairTask else { throw APIError(message: "维修任务已变化，请重新读取") }
    var result = detail.object
    let user = repair["user"]
    // A direct repairUser is valid; receiver/user, reporter and helpers never are fallbacks.
    result["repairUser"] = (!managedIdentifier(user["userName"]).isEmpty || !managedIdentifier(user["id"]).isEmpty || !managedIdentifier(user["userId"]).isEmpty) ? user : detail["repairUser"]
    return .object(result)
}

@MainActor private func resolveManagedExecutors(_ rows: [JSONValue], session: LiveSession, concurrency: Int, progress: @MainActor (Int) -> Void) async throws -> [JSONValue] {
    var result = rows
    for start in stride(from: 0, to: rows.count, by: concurrency) {
        try Task.checkCancellation()
        let end = min(start + concurrency, rows.count)
        let chunk = try await withThrowingTaskGroup(of: (Int, JSONValue).self) { group in
            for index in start..<end {
                let row = rows[index]
                group.addTask { (index, try await checkedManagedExecutor(row, session: session)) }
            }
            var values: [(Int, JSONValue)] = []
            for try await value in group { values.append(value) }
            return values
        }
        for (index, row) in chunk { result[index] = row }
        progress(end)
    }
    return result
}
