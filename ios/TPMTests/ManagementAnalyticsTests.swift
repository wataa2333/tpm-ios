import Testing
import Foundation
@testable import TPM

private func managementRow(_ id: String, kind: ManagedKind = .repair, state: String = "SIGNED", account: String = "account-a", userID: String = "", name: String = "同名用户", end: String = "2026-10-21 12:00:00") -> JSONValue {
    var fields: Fields = [kind.idField: .string(id), kind.nameField: .string("工单 " + id), kind.stateField: .string(state),
             kind.executorField: .object(["userName": .string(account), "userId": .string(userID), "realName": .string(name)]),
             kind.endField: .string(end), "applyUser": .object(["userName": .string("reporter")])]
    if kind != .precision { fields["user"] = .object(["userName": .string("receiver")]) }
    return .object(fields)
}

private func managementPage(_ rows: [JSONValue], last: Bool, total: Int? = nil) -> JSONValue {
    var fields: Fields = ["content": .array(rows), "last": .bool(last)]
    if let total { fields["totalElements"] = .number(Double(total)) }
    return .object(fields)
}

@Test func managementKeepsSameNamesSeparateAndCountsBusinessDocumentsOnce() throws {
    let a = try #require(managedDocument(kind: .repair, row: managementRow("1"), state: "SIGNED", window: nil))
    let b = try #require(managedDocument(kind: .maintenance, row: managementRow("1", kind: .maintenance, state: "MAINTAINED", account: "account-b"), state: "MAINTAINED", window: nil))
    let report = managedReport([a, a, b])
    #expect(report.people.count == 2)
    #expect(report.count == 2)
    #expect(Set(report.people.map(\.id)) == ["account:account-a", "account:account-b"])
    #expect(report.people.reduce(0) { $0 + $1.signed + $1.working } == 2)
}

@Test func managementUsesOnlyExecutorAndQuarantinesConflictingIdentityAliases() throws {
    let unknown = try #require(managedDocument(kind: .repair, row: managementRow("1", account: "", name: "只有姓名"), state: "SIGNED", window: nil))
    #expect(managedReport([unknown]).people.first?.id == ManagedReport.unknown)
    let a = try #require(managedDocument(kind: .repair, row: managementRow("2", userID: "7"), state: "SIGNED", window: nil))
    let idOnly = try #require(managedDocument(kind: .maintenance, row: managementRow("3", kind: .maintenance, state: "SIGNED", account: "", userID: "7"), state: "SIGNED", window: nil))
    #expect(managedReport([a, idOnly]).people.count == 1)
    let conflict = try #require(managedDocument(kind: .repair, row: managementRow("4", account: "other-account", userID: "7"), state: "SIGNED", window: nil))
    let report = managedReport([a, idOnly, conflict])
    #expect(report.people.count == 1)
    #expect(report.people.first?.id == ManagedReport.unknown)
    #expect(report.identityUnclear)
}

@Test func managementExcludesBusinessDocumentsSeenInConflictingStates() throws {
    let signed = try #require(managedDocument(kind: .repair, row: managementRow("1"), state: "SIGNED", window: nil))
    let working = try #require(managedDocument(kind: .repair, row: managementRow("1", state: "REPAIRED"), state: "REPAIRED", window: nil))
    let report = managedReport([signed, working])
    #expect(report.count == 0)
    #expect(report.duplicateConflicts)
}

@Test func managementHistoricalWindowUsesExactlySevenCalendarDaysAndOriginalEndFields() throws {
    var calendar = Calendar(identifier: .gregorian); calendar.timeZone = TimeZone(secondsFromGMT: 0)!
    let now = try #require(calendar.date(from: DateComponents(year: 2026, month: 10, day: 21, hour: 12)))
    let window = ManagedWindow(days: 7, now: now, calendar: calendar)
    #expect(window.from == "2026-10-15")
    #expect(window.to == "2026-10-21")
    for kind in ManagedKind.allCases {
        for state in ["VERIFIED", "RESOLVED", "FINISHED"] {
            #expect(managedDocument(kind: kind, row: managementRow("1", kind: kind, state: state, end: "2026-10-15 00:00:00"), state: state, window: window) != nil)
            #expect(managedDocument(kind: kind, row: managementRow("1", kind: kind, state: state, end: "2026-10-14 23:59:59"), state: state, window: window) == nil)
            #expect(managedDocument(kind: kind, row: managementRow("1", kind: kind, state: state, end: "2026-10-22 00:00:00"), state: state, window: window) == nil)
            #expect(managedDocument(kind: kind, row: managementRow("1", kind: kind, state: state, end: "2026-02-31 12:00:00"), state: state, window: window) == nil)
            #expect(managedDocument(kind: kind, row: managementRow("1", kind: kind, state: state, end: ""), state: state, window: window) == nil)
            let filters = ManagedSlice(kind: kind, state: state).filters(window: window)
            #expect(filters.first?["field"].text == kind.stateField)
            #expect(filters.last?["field"].text == kind.endField)
            #expect(filters.last?["operator"].text == "dateBetween")
            #expect(filters.last?["value"].array.map(\.text) == [window.from, window.to])
        }
    }
}

@Test func managementOrdinaryDuplicatesAndChangingTotalsRemainComplete() throws {
    var repeated = ManagedSlice(kind: .repair, state: "SIGNED")
    try repeated.consume(managementPage([managementRow("1")], last: false), requested: 100, window: nil)
    try repeated.consume(managementPage([managementRow("1")], last: true), requested: 100, window: nil)
    #expect(repeated.offset == 2)
    #expect(repeated.documents.count == 1)
    #expect(repeated.complete)
    var changed = ManagedSlice(kind: .repair, state: "SIGNED")
    try changed.consume(managementPage([managementRow("1")], last: false, total: 3), requested: 100, window: nil)
    try changed.consume(managementPage([managementRow("2")], last: true, total: 2), requested: 100, window: nil)
    #expect(changed.finished && changed.complete)
    #expect(changed.reasons.isEmpty)
}

@Test func managementHasNoTotalCapAndFlagsBadServerFilters() throws {
    var slice = ManagedSlice(kind: .repair, state: "SIGNED")
    for page in 0..<20 {
        let rows = (0..<100).map { managementRow(String(page * 100 + $0)) }
        try slice.consume(managementPage(rows, last: false, total: 2001), requested: slice.requestLimit, window: nil)
    }
    #expect(slice.offset == 2000)
    #expect(!slice.finished && !slice.complete)
    #expect(slice.documents.count == 2000)
    try slice.consume(managementPage([managementRow("2000")], last: true, total: 2001), requested: slice.requestLimit, window: nil)
    #expect(slice.offset == 2001)
    #expect(slice.documents.count == 2001)
    #expect(slice.complete)
    var wrongState = ManagedSlice(kind: .repair, state: "SIGNED")
    try wrongState.consume(managementPage([managementRow("1", state: "REPAIRED")], last: true), requested: 100, window: nil)
    #expect(wrongState.documents.isEmpty)
    #expect(!wrongState.complete)
    var noMetadata = ManagedSlice(kind: .repair, state: "SIGNED")
    try noMetadata.consume(.object(["content": .array([managementRow("1")])]), requested: 100, window: nil)
    #expect(noMetadata.finished && !noMetadata.complete)
}

@MainActor private final class ManagementPageGate {
    var continuation: CheckedContinuation<JSONValue, Error>?
    func wait() async throws -> JSONValue { try await withCheckedThrowingContinuation { continuation = $0 } }
    func finish(_ page: JSONValue) { continuation?.resume(returning: page); continuation = nil }
}

@Test @MainActor func managementPauseResumesFromLastCommittedOffsetAndIgnoresLatePage() async {
    let session = LiveSession(authentication: ["token": .string("TEST"), "factoryId": .string("TEST-FACTORY")])
    let model = ManagementAnalyticsModel(), gate = ManagementPageGate()
    model.start(session: session, days: nil, pageReader: { slice, _ in
        if slice.offset == 0 { return managementPage([managementRow("1")], last: false, total: 2) }
        return try await gate.wait()
    })
    for _ in 0..<1000 { if gate.continuation != nil { break }; await Task.yield() }
    #expect(gate.continuation != nil)
    model.pause()
    #expect(model.report.count == 1)
    #expect(model.paused && model.incomplete)
    gate.finish(managementPage([managementRow("late")], last: true, total: 2))
    var resumedOffsets: [Int] = []
    model.start(session: session, days: nil, pageReader: { slice, _ in
        if slice.kind == .repair && slice.state == "SIGNED" {
            resumedOffsets.append(slice.offset)
            return managementPage([managementRow("2")], last: true, total: 2)
        }
        return managementPage([], last: true, total: 0)
    })
    for _ in 0..<1000 { if !model.running { break }; await Task.yield() }
    #expect(!model.running)
    #expect(resumedOffsets == [1])
    #expect(model.report.count == 2)
    #expect(!model.incomplete)
    #expect(!model.report.people.flatMap(\.documents).contains { $0.businessID == "late" })
    model.clear()
}

@Test @MainActor func managementClearAndNewScopeCannotReceiveOldResults() async {
    let oldSession = LiveSession(authentication: ["token": .string("TEST-A")])
    let newSession = LiveSession(authentication: ["token": .string("TEST-B")])
    let model = ManagementAnalyticsModel(), gate = ManagementPageGate()
    model.start(session: oldSession, days: nil, pageReader: { _, _ in try await gate.wait() })
    for _ in 0..<1000 { if gate.continuation != nil { break }; await Task.yield() }
    #expect(gate.continuation != nil)
    model.clear()
    #expect(model.slices.isEmpty && model.report.count == 0)
    model.start(session: newSession, days: 30, pageReader: { _, _ in managementPage([], last: true, total: 0) })
    gate.finish(managementPage([managementRow("old-scope")], last: true))
    for _ in 0..<1000 { if !model.running { break }; await Task.yield() }
    #expect(model.scope == newSession.scopeRevision)
    #expect(model.slices.count == 2)
    #expect(model.report.count == 0)
    #expect(!model.incomplete)
    model.clear()
}

@Test @MainActor func managementFailedPageRetainsLoadedRecordsWithoutClaimingComplete() async {
    let session = LiveSession(authentication: ["token": .string("TEST")])
    let model = ManagementAnalyticsModel()
    model.start(session: session, days: nil, pageReader: { slice, _ in
        if slice.kind == .repair && slice.state == "SIGNED" {
            if slice.offset == 0 { return managementPage([managementRow("kept")], last: false, total: 2) }
            throw APIError(message: "模拟断网")
        }
        return managementPage([], last: true, total: 0)
    })
    for _ in 0..<1000 { if !model.running { break }; await Task.yield() }
    #expect(!model.running)
    #expect(model.report.count == 1)
    #expect(model.incomplete)
    #expect(model.slices.first?.complete == false)
    #expect(model.slices.first?.reasons.contains(where: { $0.contains("模拟断网") }) == true)
    model.clear()
}


@Test func managementHistoricalLastRowRemovesOrdersThatLeaveStateOrDateRange() throws {
    let window = ManagedWindow(days: 7)
    var slice = ManagedSlice(kind: .repair, state: "RESOLVED")
    let initial = managementRow("1", state: "RESOLVED", end: window.from)
    try slice.consume(managementPage([initial], last: false), requested: 500, window: window)
    #expect(slice.documents.count == 1)
    try slice.consume(managementPage([managementRow("1", state: "VERIFIED", end: window.from)], last: false), requested: 500, window: window)
    #expect(slice.documents.isEmpty)
    try slice.consume(managementPage([initial], last: false), requested: 500, window: window)
    try slice.consume(managementPage([managementRow("1", state: "FINISHED", end: "2000-01-01")], last: true), requested: 500, window: window)
    #expect(slice.documents.isEmpty)
    #expect(slice.complete)
}

@Test func managementExecutorUsesFirstRepairUserEvenBeforeSignIn() throws {
    let row = managementRow("1", account: "old-list-value")
    let detail = managementRow("1", account: "direct-value")
    let answer: JSONValue = .array([
        .object(["user": .object(["userName": .string("actual-executor"), "realName": .string("维修人")])]),
        .object(["user": .object(["userName": .string("older-executor")])])
    ])
    let checked = try managedExecutorRow(row, detail: detail, answer: answer)
    let document = try #require(managedDocument(kind: .repair, row: checked, state: "SIGNED", window: nil))
    #expect(document.account == "actual-executor")
    let noExecutor = managementRow("1", account: "", name: "")
    let unknown = try managedExecutorRow(row, detail: noExecutor, answer: .array([]))
    #expect(unknown["repairUser"]["userName"].text.isEmpty)
    #expect(unknown["user"]["userName"].text == "receiver")
}

@Test func managementExecutorRejectsWrongOwnerAndChangedState() {
    let row = managementRow("1")
    #expect(throws: APIError.self) { try managedExecutorRow(row, detail: managementRow("1", state: "REPAIRED"), answer: .array([])) }
    let wrongOwner: JSONValue = .array([.object(["assetFault": .object(["faultId": .string("other")])])])
    #expect(throws: APIError.self) { try managedExecutorRow(row, detail: row, answer: wrongOwner) }
}

@Test func managementNormalizesArrayDatesForTrendAndKeepsUnknownInTotal() throws {
    let window = ManagedWindow(range: DateRange(from: Date(timeIntervalSince1970: 0), to: Date(timeIntervalSince1970: 86400)))
    var fields = managementRow("1", state: "RESOLVED", account: "", name: "").object
    fields["repairEndTime"] = .array([.number(1970), .number(1), .number(1), .number(12), .number(0)])
    let doc = try #require(managedDocument(kind: .repair, row: .object(fields), state: "RESOLVED", window: window))
    #expect(doc.end == "1970-01-01")
    let report = managedReport([doc])
    #expect(report.people.first?.id == ManagedReport.unknown)
    let daily = managedTrendBuckets(report: report, window: window, grain: .day)
    let monthly = managedTrendBuckets(report: report, window: window, grain: .month)
    #expect(daily.reduce(0) { $0 + $1.total } == 1)
    #expect(monthly.reduce(0) { $0 + $1.total } == 1)
    #expect(daily.first?.counts[ManagedReport.unknown] == 1)
}

@Test @MainActor func managementRetryKeepsCommittedOffsetAndCapturedReadSettings() async {
    let session = LiveSession(authentication: ["token": .string("TEST")])
    let model = ManagementAnalyticsModel()
    model.start(session: session, days: nil, pageSize: 50, concurrency: 3, pageReader: { slice, _ in
        if slice.state == "SIGNED" {
            if slice.offset == 0 { return managementPage([managementRow("1")], last: false) }
            throw APIError(message: "模拟断网")
        }
        return managementPage([], last: true)
    })
    for _ in 0..<1000 { if !model.running { break }; await Task.yield() }
    #expect(model.hasUnfinished)
    #expect(model.report.count == 1)
    var offset: Int?
    model.start(session: session, days: nil, pageSize: 500, concurrency: 8, pageReader: { slice, _ in
        offset = slice.offset
        #expect(slice.requestLimit == 50)
        return managementPage([managementRow("2")], last: true)
    })
    for _ in 0..<1000 { if !model.running { break }; await Task.yield() }
    #expect(offset == 1)
    #expect(model.detailConcurrency == 3)
    #expect(model.report.count == 2)
    #expect(!model.incomplete)
    model.clear()
}

@Test @MainActor func managementHistoricalLastResponseWinsAcrossStates() async {
    let session = LiveSession(authentication: ["token": .string("TEST")])
    let model = ManagementAnalyticsModel()
    let window = ManagedWindow(days: 7)
    model.start(session: session, days: 7, pageReader: { slice, _ in
        let account = slice.state == "RESOLVED" ? "first" : "last"
        return managementPage([managementRow("1", state: slice.state, account: account, end: window.from)], last: true)
    })
    for _ in 0..<1000 { if !model.running { break }; await Task.yield() }
    #expect(model.report.count == 1)
    #expect(model.report.people.first?.id == "account:last")
    #expect(model.slices.map(\.state) == ["RESOLVED", "FINISHED"])
    #expect(model.slices.allSatisfy { $0.kind == .repair })
    #expect(!model.incomplete)
    model.clear()
}
