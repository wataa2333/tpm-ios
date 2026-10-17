import Testing
import Foundation
@testable import TPM

// Account scope, batch selection and returned-order notes.

@Test func pickerPrefsMigrateFromTheOldScopeWithoutOverwriting() {
    let store = PickerPrefsStore(url: nil)
    var lists = PickerLists()
    lists.pins = [PickerEntry(key: "A", label: "甲", value: .object([:]))]
    store.save(lists, scope: "wang@一厂", source: .users)
    let person = PickerEntry(key: "A", label: "甲", value: .object(["userName": .string("A")]))
    store.setDefault(person, source: .users, scope: "wang@一厂", field: "fault.user")
    store.migrate(from: "wang@一厂", to: "id:7#1")
    #expect(store.lists(scope: "id:7#1", source: .users).pins.map(\.key) == ["A"])
    #expect(store.defaultChoice(scope: "id:7#1", field: "fault.user")?.entry.key == "A")
    #expect(store.lists(scope: "wang@一厂", source: .users).pins.isEmpty)
    // What the new scope already holds is kept.
    var newer = PickerLists()
    newer.pins = [PickerEntry(key: "B", label: "乙", value: .object([:]))]
    store.save(newer, scope: "id:7#1", source: .departments)
    store.save(lists, scope: "wang@一厂", source: .departments)
    store.migrate(from: "wang@一厂", to: "id:7#1")
    #expect(store.lists(scope: "id:7#1", source: .departments).pins.map(\.key) == ["B"])
}

@MainActor @Test func draftScopeUsesIdsAndSeparatesAccounts() {
    let tom = LiveSession(authentication: ["token": .string("t"), "id": .number(7), "name": .string("Tom"), "factoryId": .number(1)])
    let other = LiveSession(authentication: ["token": .string("t"), "id": .number(8), "name": .string("Tom"), "factoryId": .number(1)])
    #expect(tom.draftScope != other.draftScope)
    #expect(tom.draftScope.hasSuffix("#1"))
    let byName = LiveSession(authentication: ["token": .string("t"), "name": .string("Wang"), "factoryId": .string("2")])
    #expect(byName.accountKey == "wang")
}

@MainActor @Test func batchSelectionOnlyTakesOneStatusAndStopsAtTheLimit() {
    let batch = BatchSelection()
    batch.begin()
    func order(_ id: String, _ state: String) -> BatchItem { BatchItem(id: id, kind: .fault, name: id, reference: id, group: state) }
    batch.toggle(order("1", "SIGNED"))
    batch.toggle(order("2", "REPAIRED"))
    #expect(batch.items.map(\.id) == ["1"])
    #expect(batch.notice != nil)
    #expect(batch.action == .signIn)
    batch.toggle(order("1", "SIGNED"))
    #expect(batch.items.isEmpty)
    batch.selectAll((0..<150).map { order("\($0)", "RECEIVED") })
    #expect(batch.items.count == BatchSelection.limit)
    #expect(batch.action == .claim)
    batch.toggle(order("x", "FINISHED"))
    #expect(batch.items.count == BatchSelection.limit)
    batch.remove(ids: ["0", "1"])
    #expect(batch.items.count == BatchSelection.limit - 2)
    batch.end()
    #expect(!batch.active && batch.items.isEmpty)
}

@Test func batchActionsFollowTheStatus() {
    #expect(BatchAction.forGroup("REPORTED") == .accept)
    #expect(BatchAction.forGroup("RECEIVED") == .claim)
    #expect(BatchAction.forGroup("SIGNED") == .signIn)
    #expect(BatchAction.forGroup("REPAIRED") == .complete)
    #expect(BatchAction.forGroup("VERIFIED") == .approve)
    #expect(BatchAction.forGroup("FINISHED") == nil)
    #expect(BatchAction.accept.needsPreset && BatchAction.complete.needsPreset && !BatchAction.signIn.needsPreset)
}

@Test func batchPresetNeedsWhatTheStepNeeds() {
    var preset = BatchPreset()
    #expect(!preset.isComplete(for: .accept))
    preset.faultType = .object(["typeId": .number(1), "typeName": .string("机械")])
    #expect(!preset.isComplete(for: .accept))
    preset.group = .object(["groupId": .number(3)])
    #expect(preset.isComplete(for: .accept))
    #expect(!preset.isComplete(for: .complete))
    preset.content = "已更换轴承"
    #expect(preset.isComplete(for: .complete))
    preset.durationMinutes = 0
    #expect(!preset.isComplete(for: .complete))
}

@Test func sentBackOrderIsMarkedFromItsOwnFields() {
    let returned: JSONValue = .object(["faultState": .string("REPORTED"), "returnOrder": .bool(true)])
    #expect(faultReturnKind(returned) == "退单")
    #expect(faultStateLabel(returned) == "待受理（退单）")
    let rejected: JSONValue = .object(["faultState": .string("UNTREATED"), "returnOrder": .bool(true)])
    #expect(faultStateLabel(rejected) == "报修（驳回）")
    // Not flagged, or flagged but already past that step: plain state name.
    #expect(faultStateLabel(.object(["faultState": .string("REPORTED"), "returnOrder": .bool(false)])) == "待受理")
    #expect(faultStateLabel(.object(["faultState": .string("REPORTED")])) == "待受理")
    #expect(faultStateLabel(.object(["faultState": .string("SIGNED"), "returnOrder": .bool(true)])) == "待签到")
    #expect(faultMayHaveRepairNote(returned) && !faultMayHaveRepairNote(.object(["faultState": .string("SIGNED")])))
}

@Test func latestRepairIsTheFirstOfTheList() {
    let list: JSONValue = .array([.object(["content": .string("备件不到位"), "user": .object(["realName": .string("张三")])]), .object(["content": .string("旧")])])
    #expect(latestRepair(list)?["content"].text == "备件不到位")
    #expect(latestRepair(.array([])) == nil)
    #expect(latestRepair(.null) == nil)
}
