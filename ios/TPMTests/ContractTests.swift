import Testing
import Foundation
@testable import TPM

@Test func floatingBarPreservesHomeIndicatorClearanceAcrossSafeAreas() {
    for inset in [CGFloat(0), 10, 20, 21, 34, 48] {
        let extensionAmount = bottomBarExtension(safeAreaInset: inset)
        #expect(extensionAmount >= 0)
        #expect(extensionAmount <= 12)
        #expect(inset - extensionAmount >= min(inset, 20))
    }
}

@Test @MainActor func receiverDepartmentAppliesConfiguredDefaultAndClearsPreviousReceiver() {
    let form = OriginalFormModel(kind: "ASSET_FAULT")
    let user = JSONValue.object(["userId": .number(12), "realName": .string("默认受理人")])
    let dept = JSONValue.object(["deptId": .number(7), "acceptUser": user])
    form.selectReceiverDepartment(dept)
    #expect(form.data["dept"] == dept)
    #expect(form.data["user"] == user)
    form.selectReceiverDepartment(.object(["deptId": .number(8)]))
    #expect(form.data["user"] == .null)
}

@Test func productionPoolsUseOriginalReadEndpointsAndStateKeys() {
    #expect(ReadOnlyPoolKind.maintenance.page == "maintain/pageMaintain")
    #expect(ReadOnlyPoolKind.precision.page == "precise/pagePrecise")
    #expect(ReadOnlyPoolKind.precision.stateKey == "preciseState")
    #expect(ReadOnlyPoolKind.maintenance.nameKey == "maintainName")
}


@Test func statusCountUsesServerTotalRatherThanLoadedRows() {
    let page = JSONValue.object(["totalElements": .number(103), "content": .array([.object([:])])])
    #expect(serverPageCount(page) == 103)
    #expect(serverPageCount(.object(["content": .array([])])) == nil)
    #expect(serverPageCount(.object(["totalElements": .number(0)])) == 0)
}

@Test func processRolesDoNotLeakRawExpressions() {
    #expect(workflowParticipantLabel("${initiator}") == "发起人")
    #expect(workflowParticipantLabel("REPAIR_USER") == "维修人员")
    #expect(workflowParticipantLabel("${unknownRule}") == "")
    #expect(workflowParticipantLabel("张三,REPAIR_USER") == "张三、维修人员")
}

@Test func assetUseStateUsesOriginalChineseLabels() {
    #expect(assetDisplayValue(.string("IN_USE"), key: "useState") == "在用")
    #expect(assetDisplayValue(.string("UN_USE"), key: "useState") == "闲置")
    #expect(assetDisplayValue(.string("EWPT-01"), key: "assetCode") == "EWPT-01")
}

@Test func assetRepairSummaryUsesItsKnownAssetCode() {
    let repair = JSONValue.object(["faultName": .string("维修记录")])
    let asset = JSONValue.object(["assetCode": .string("EWPT-01")])
    #expect(assetRepairRecord(repair, asset: asset)["asset"]["assetCode"].text == "EWPT-01")
}

@Test @MainActor func erpKeepsMultipleMaterialLinesAndSaveMode() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    form.addMaterial(.object(["id": .number(11), "onhandQty": .number(8)]))
    form.addMaterial(.object(["id": .number(22), "onhandQty": .number(5)]))
    let payload = form.payload(committed: false)
    #expect(payload["committed"] == .bool(false))
    #expect(payload["erpMaterialOutbounds"]?.array.count == 2)
    #expect(payload["erpMaterialOutbounds"]?.array[1]["id"]["imaterialId"] == .number(22))
    #expect(payload["urgency"] == nil)
}

@Test @MainActor func erpRejectsDuplicateMaterialWithoutLosingExistingLines() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    let material = JSONValue.object(["id": .number(11), "onhandQty": .number(8)])
    form.addMaterial(material); form.addMaterial(material)
    #expect(form.data["erpMaterialOutbounds"]?.array.count == 1)
    #expect(form.error != nil)
}

@Test @MainActor func erpValidatesEachLineAgainstItsOwnStock() {
    let form = OriginalFormModel(kind: "ERP_OUTBOUND")
    form.data = ["user": .object(["userId": .number(1)]), "applyDate": .string("2026-10-15"), "projectCode": .string("P-1"), "recipientUser": .object(["userId": .number(1)]), "dept": .object(["deptId": .number(1)]), "division": .object(["divisionId": .number(1)]), "outboundTime": .string("2026-10-15 09:30"), "remark": .string("设备维修")]
    form.addMaterial(.object(["id": .number(11), "onhandQty": .number(2)]))
    form.addMaterial(.object(["id": .number(22), "onhandQty": .number(0.5)]))
    #expect(form.validationError()?.contains("库存数量") == true)
}

@Test func backendJSONPreservesObjectIDsAndNulls() throws {
    let body = Data("{\"material\":{\"id\":12345},\"outboundId\":null,\"committed\":false}".utf8)
    let record = try JSONDecoder().decode(JSONValue.self, from: body)
    let roundTrip = try JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(record))
    #expect(roundTrip == record)
    #expect(roundTrip["material"]["id"].text == "12345")
}

@Test func missingMaterialUnitUsesPCSAndExistingUnitIsKept() {
    #expect(materialUnit(.object([:])) == "PCS")
    #expect(materialUnit(.object(["uom": .object(["uomDesc": .string("米")])])) == "米")
    #expect(materialQuantity("2", material: .object([:])) == "2 PCS")
}

@Test func faultStagesFollowOriginalWorkflowOrder() {
    #expect(FaultState.allCases.map(\.code) == ["UNTREATED", "REPORTED", "RECEIVED", "SIGNED", "REPAIRED", "VERIFIED", "RESOLVED", "FINISHED"])
    #expect(FaultSearchField.assetCode.key == "asset.assetCode")
    #expect(FaultSearchField.machine.key == "asset.addressCode")
    #expect(FaultSearchField.reporter.key == "applyUser.realName")
}

@Test func returnActionMustBeNamedByOriginalServer() {
    #expect(isReturnCondition(.object(["name": .string("退单"), "value": .string("reject")])))
    #expect(!isReturnCondition(.object(["name": .string("不同意"), "value": .string("reject")])))
}

@Test func textLimitPreservesUnicodeAndOriginalUTF16Limit() {
    #expect(limitedText(String(repeating: "中", count: 65), limit: 64).utf16.count == 64)
    #expect(limitedText("A😀B", limit: 2) == "A")
    #expect(limitedText("A😀B", limit: 3) == "A😀")
}

@Test func filteringHierarchicalChoicesKeepsTheParent() {
    let values = PreviewReferenceData.values(.faultTypes)
    let result = matchingFaultTypes(values, query: "轴承")
    #expect(result.count == 1)
    #expect(result[0]["children"].array.count == 1)
    #expect(result[0]["children"].array[0]["typeName"].text == "轴承磨损")
}

@Test func repairSavePayloadKeepsOnlyOriginalFormFieldsAndAllMaterialDetail() {
    let payload = repairSubmissionPayload(ContractFixtures.repairRecord, repairID: .number(88))
    #expect(Set(payload.keys) == ContractFixtures.repairBodyKeys)
    #expect(payload["assetFault"] == nil)
    #expect(payload["signTime"] == nil)
    #expect(payload["erpRepairMaterials"]?.array.first == ContractFixtures.materialLine)
    var sparse = ContractFixtures.repairRecord
    sparse.removeValue(forKey: "albums"); sparse.removeValue(forKey: "erpRepairMaterials")
    let minimal = repairSubmissionPayload(sparse, repairID: .number(88))
    #expect(minimal["albums"] == nil)
    #expect(minimal["erpRepairMaterials"] == .array([]))
}

@Test func missingNextNodeApproverBlocksSubmission() {
    let nodes = [ContractFixtures.confirmNode]
    #expect(missingAssigneeMessage(nodes: nodes, selections: [:])?.contains("【维修】确认") == true)
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureRepairConfirmUser": []]) != nil)
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureRepairConfirmUser": ["a"]]) == nil)
    var none = ContractFixtures.confirmNode
    none["assignType"] = .string("NONE")
    #expect(missingAssigneeMessage(nodes: [none], selections: [:]) == nil)
}

@Test func countersignNodeNeedsTwoApproversAndJoinsThemWithCommas() {
    let nodes = [ContractFixtures.countersignNode]
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureCountersignUsers": ["a"]]) != nil)
    #expect(missingAssigneeMessage(nodes: nodes, selections: ["fixtureCountersignUsers": ["a", "b"]]) == nil)
    #expect(assigneesPayload(nodes: nodes, selections: ["fixtureCountersignUsers": ["a", "b"]])["fixtureCountersignUsers"] == .string("a,b"))
}

@Test func commitPayloadOnlyCarriesApproversForAgree() {
    let agree = commitTaskPayload(task: ContractFixtures.repairTask, condition: "agree", comment: "好",
                                  assignees: assigneesPayload(nodes: [ContractFixtures.confirmNode], selections: ["fixtureRepairConfirmUser": ["zhang"]]))
    #expect(agree["assignees"]?["fixtureRepairConfirmUser"] == .string("zhang"))
    #expect(agree["taskData"] == .null)
    let reject = commitTaskPayload(task: ContractFixtures.repairTask, condition: "reject", comment: "退", assignees: nil)
    #expect(reject["assignees"] == nil)
    #expect(Set(reject.keys) == ["taskId", "processInstanceId", "condition", "comment", "taskData"])
}

@Test func serverErrorSummaryIsOneSafeLine() {
    #expect(serverErrorSummary(Data("{\"message\":\"保存失败\"}".utf8)) == "保存失败")
    #expect(serverErrorSummary(Data("<html>oops</html>".utf8)) == "")
    #expect(serverErrorSummary(Data()) == "")
    #expect(serverErrorSummary(Data("第一行\n第二行 stack".utf8)) == "第一行")
    #expect(!serverErrorSummary(Data("{\"msg\":\"token=abc123 failed\"}".utf8)).contains("abc123"))
    #expect(serverErrorSummary(Data(String(repeating: "长", count: 400).utf8)).count <= 160)
}

@Test func requestLabelShowsMethodAndPathOnly() {
    var request = URLRequest(url: URL(string: "http://example.invalid:8855/rest/repair/handleRepair?taskId=secret")!)
    request.httpMethod = "POST"
    #expect(requestLabel(request) == "POST repair/handleRepair")
}

@Test func employeeNumbersAreKeptAsProcessHandlers() {
    #expect(workflowParticipantLabel("C0070034") == "C0070034")
    #expect(workflowParticipantLabel("IN_USE") == "")
}

@Test func historyShowsWhoHandledEachNodeAndHowLongItTook() {
    let node = JSONValue.object(["activityType": .string("userTask"), "assignee": .string("C0070034"), "condition": .string("抢单"), "endTime": .string("2026-10-09 14:54"), "durationInMillis": .number(133_760_197)])
    #expect(historyAssigneeNames(node) == ["C0070034"])
    #expect(showsConditionTag(node))
    #expect(historyTimePrefix(node) == "完成")
    #expect(historyDuration(133_760_197) == "1天13时9分20.197秒")
    let running = JSONValue.object(["activityType": .string("userTask"), "condition": .string("提交"), "endTime": .null])
    #expect(!showsConditionTag(running))
    #expect(historyTimePrefix(running) == "当前")
    #expect(historyAssigneeNames(.object(["assignee": .string("${initiator}")])) == ["发起人"])
}

@Test func historyListsEveryHandlerOnceAndKeepsOrder() {
    let nodes: [JSONValue] = [
        .object(["activityId": .string("b"), "activityType": .string("userTask"), "assignee": .string("u2"), "endTime": .string("t"), "sortTime": .number(2)]),
        .object(["activityId": .string("a"), "activityType": .string("startEvent"), "assignee": .string("u1"), "endTime": .string("t"), "sortTime": .number(1)]),
        .object(["activityId": .string("c"), "activityType": .string("userTask"), "assignee": .string("u2"), "endTime": .null])
    ]
    #expect(orderedHistory(nodes).map { $0["activityId"].text } == ["a", "b", "c"])
    #expect(historyUserNames(orderedHistory(nodes)) == ["u1", "u2"])
}

@Test @MainActor func returningToAPageDoesNotReloadButTheRefreshButtonDoes() async {
    let gate = ReloadGate()
    var loads = 0
    await gate.run(key: "list") { loads += 1 }
    #expect(loads == 1)
    // The page reappears after a detail page (its task restarts with the same key): nothing loads.
    await gate.run(key: "list") { loads += 1 }
    #expect(loads == 1)
    // Another filter is a different request.
    await gate.run(key: "list-other") { loads += 1 }
    #expect(loads == 2)
    // The refresh button loads again, even for the same key.
    gate.request()
    await gate.run(key: "list") { loads += 1 }
    #expect(loads == 3)
    await gate.run(key: "list") { loads += 1 }
    #expect(loads == 3)
}

@Test @MainActor func aLoadThatWasInterruptedIsFinishedOnReturn() async {
    let gate = ReloadGate()
    var loads = 0
    let task = Task { @MainActor in
        await gate.run(key: "list") {
            loads += 1
            try? await Task.sleep(for: .seconds(5))
        }
    }
    try? await Task.sleep(for: .milliseconds(50))
    task.cancel()
    await task.value
    #expect(loads == 1)
    await gate.run(key: "list") { loads += 1 }
    #expect(loads == 2)
}

@Test func rememberedAccountsLiveInTheKeychainAndNeverShowTheirPassword() {
    let name = "test.fixture." + UUID().uuidString
    defer { AccountVault.remove(username: name) }
    try? AccountVault.save(username: name, password: "secret-1", displayName: "测试用户")
    #expect(AccountVault.password(for: name) == "secret-1")
    let listed = AccountVault.list().first { $0.username == name }
    #expect(listed?.displayName == "测试用户")
    // Saving again replaces the password and keeps one entry.
    try? AccountVault.save(username: name, password: "secret-2", displayName: "测试用户")
    #expect(AccountVault.password(for: name) == "secret-2")
    #expect(AccountVault.list().filter { $0.username == name }.count == 1)
    AccountVault.remove(username: name)
    #expect(AccountVault.password(for: name) == nil)
}

@Test func dateRangeFilterUsesTheOriginalDateBetweenEnvelope() {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = TimeZone(identifier: "Asia/Shanghai")!
    let from = calendar.date(from: DateComponents(year: 2026, month: 10, day: 1))!
    let to = calendar.date(from: DateComponents(year: 2026, month: 10, day: 16))!
    #expect(DateRange(from: from, to: to).serverDays(calendar: calendar) == ["2026-10-01", "2026-10-16"])
    // Picked in the wrong order, the earlier day still goes first.
    #expect(DateRange(from: to, to: from).serverDays(calendar: calendar) == ["2026-10-01", "2026-10-16"])
    let filter = dateRangeFilter("faultTime", DateRange(from: from, to: to))
    #expect(filter["field"].text == "faultTime")
    #expect(filter["operator"].text == "dateBetween")
    #expect(filter["logic"].text == "and")
    #expect(filter["value"].array.map(\.text).count == 2)
    let last7 = DateRange.lastDays(7, now: to, calendar: calendar)
    #expect(last7.serverDays(calendar: calendar) == ["2026-10-09", "2026-10-16"])
    #expect(DateRange.thisMonth(now: to, calendar: calendar).serverDays(calendar: calendar) == ["2026-10-01", "2026-10-16"])
}

@Test func draftsAreKeptPerAccountAndFactoryAndCanBeRemoved() {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("drafts-" + UUID().uuidString).appendingPathComponent("fault-drafts.json")
    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
    let store = DraftStore(url: url)
    let first = FaultDraft(savedAt: Date(timeIntervalSince1970: 100), data: ["faultName": .string("主轴异常"), "asset": .object(["assetName": .string("台群CNC")])], selections: ["a": ["u1"]])
    let second = FaultDraft(savedAt: Date(timeIntervalSince1970: 200), data: [:], selections: [:])
    try? store.save(first, scope: "wang@一厂")
    try? store.save(second, scope: "wang@一厂")
    try? store.save(second, scope: "li@二厂")
    let list = store.drafts(scope: "wang@一厂")
    #expect(list.map(\.id) == [second.id, first.id])
    // The title is the time of the last edit; what the draft is about goes under it.
    #expect(list.last?.title == FaultDraft.titleFormat(Date(timeIntervalSince1970: 100)))
    #expect(list.last?.title.count == 16 && list.last?.title.contains(":") == true)
    #expect(list.last?.subtitle == "主轴异常")
    #expect(list.first?.subtitle == "还没有填写内容")
    #expect(list.last?.selections == ["a": ["u1"]])
    // Saving the same draft again replaces it.
    var changed = first; changed.data["faultName"] = .string("改名")
    try? store.save(changed, scope: "wang@一厂")
    #expect(store.drafts(scope: "wang@一厂").count == 2)
    store.remove(id: first.id, scope: "wang@一厂")
    #expect(store.drafts(scope: "wang@一厂").map(\.id) == [second.id])
    #expect(store.drafts(scope: "li@二厂").count == 1)
    #expect(store.drafts(scope: "nobody@一厂").isEmpty)
}

@Test func onlyWhatThePersonTypedMakesAFormWorthAsking() {
    // What the form fills in by itself must not count.
    #expect(!faultFormHasContent(["applyUser": .object(["realName": .string("甲")]), "userPhone": .string("138"), "faultTime": .string("2026-10-16 09:00"), "albums": .array([])]))
    #expect(faultFormHasContent(["faultName": .string(" 报警 ")]))
    #expect(!faultFormHasContent(["faultName": .string("   ")]))
    #expect(faultFormHasContent(["asset": .object(["assetId": .number(1)])]))
    #expect(faultFormHasContent(["albums": .array([.object(["url": .string("x")])])]))
}

@Test func aDraftIsOnlyOfferedToTheFormOfItsOwnOrder() {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("drafts-" + UUID().uuidString).appendingPathComponent("fault-drafts.json")
    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
    let store = DraftStore(url: url)
    let forA = FaultDraft(savedAt: Date(timeIntervalSince1970: 300), data: ["faultName": .string("A单")], selections: [:], orderID: "A-1", orderSN: "BX-A")
    let forB = FaultDraft(savedAt: Date(timeIntervalSince1970: 400), data: ["faultName": .string("B单")], selections: [:], orderID: "B-1", orderSN: "BX-B")
    let fresh = FaultDraft(savedAt: Date(timeIntervalSince1970: 100), data: ["faultName": .string("新报修")], selections: [:])
    for draft in [forA, forB, fresh] { try? store.save(draft, scope: "wang@一厂") }
    #expect(store.drafts(scope: "wang@一厂", for: .order("A-1")).map(\.id) == [forA.id])
    #expect(store.drafts(scope: "wang@一厂", for: .order("B-1")).map(\.id) == [forB.id])
    // A new report never gets the draft of an existing order, and the other way round.
    #expect(store.drafts(scope: "wang@一厂", for: .newReport).map(\.id) == [fresh.id])
    #expect(store.drafts(scope: "wang@一厂", for: .order("C-1")).isEmpty)
    #expect(store.drafts(scope: "wang@一厂", for: .order("")).isEmpty)
    #expect(store.drafts(scope: "wang@一厂").count == 3)
    #expect(forA.subtitle == "单号 BX-A · A单")
}

@Test func pinsAreLimitedToTenAndRecentsShowThreeWithoutRepeatingPins() {
    func entry(_ n: Int) -> PickerEntry { PickerEntry(key: "k\(n)", label: "项\(n)", value: .object(["userName": .string("k\(n)")])) }
    var lists = PickerLists()
    for n in 1...10 { #expect(PickerRules.pin(entry(n), in: &lists)) }
    #expect(lists.pins.count == 10)
    // The eleventh is refused; pinning one that is already pinned is harmless.
    #expect(!PickerRules.pin(entry(11), in: &lists))
    #expect(PickerRules.pin(entry(3), in: &lists))
    #expect(lists.pins.count == 10)
    // The newest pin is on top, and unpinning frees a place.
    #expect(lists.pins.first?.key == "k10")
    PickerRules.unpin(key: "k5", in: &lists)
    #expect(PickerRules.pin(entry(11), in: &lists) && lists.pins.first?.key == "k11")

    var recent = PickerLists()
    #expect(PickerRules.shownRecents(recent).isEmpty)
    for n in [1, 2, 3, 4, 2] { PickerRules.record(entry(n), in: &recent) }
    #expect(PickerRules.shownRecents(recent).map(\.key) == ["k2", "k4", "k3"])
    // What is pinned is not repeated among the recent ones; the next recent one takes its place.
    _ = PickerRules.pin(entry(2), in: &recent)
    #expect(PickerRules.shownRecents(recent).map(\.key) == ["k4", "k3", "k1"])
}

@Test func pinsRecentsAndDefaultsArePerAccountAndFactoryAndSurviveRestart() {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("prefs-" + UUID().uuidString).appendingPathComponent("picker-prefs.json")
    defer { try? FileManager.default.removeItem(at: url.deletingLastPathComponent()) }
    let person = PickerEntry(key: "A0095970", label: "柯龙杰", value: .object(["userName": .string("A0095970"), "realName": .string("柯龙杰")]))
    let store = PickerPrefsStore(url: url)
    var lists = PickerLists(); _ = PickerRules.pin(person, in: &lists)
    store.save(lists, scope: "wang@一厂", source: .users)
    store.setDefault(person, source: .users, scope: "wang@一厂", field: "fault.user")
    // A new store reads the same file: the choices are still there after the app is closed and opened.
    let reopened = PickerPrefsStore(url: url)
    #expect(reopened.lists(scope: "wang@一厂", source: .users).pins.map(\.key) == ["A0095970"])
    #expect(reopened.defaultChoice(scope: "wang@一厂", field: "fault.user")?.entry.label == "柯龙杰")
    #expect(reopened.defaultChoice(scope: "wang@一厂", field: "fault.user")?.source == .users)
    // Another account, another factory, another kind of list: nothing leaks across.
    #expect(reopened.lists(scope: "li@一厂", source: .users).pins.isEmpty)
    #expect(reopened.lists(scope: "wang@二厂", source: .users).pins.isEmpty)
    #expect(reopened.lists(scope: "wang@一厂", source: .departments).pins.isEmpty)
    #expect(reopened.defaultChoice(scope: "wang@一厂", field: "fault.applyUser") == nil)
    reopened.setDefault(nil, source: .users, scope: "wang@一厂", field: "fault.user")
    #expect(reopened.defaultChoice(scope: "wang@一厂", field: "fault.user") == nil)
    // A store without a file keeps its choices in memory only.
    let memory = PickerPrefsStore(url: nil)
    memory.save(lists, scope: "x", source: .users)
    #expect(memory.lists(scope: "x", source: .users).pins.count == 1)
    #expect(PickerPrefsStore(url: nil).lists(scope: "x", source: .users).pins.isEmpty)
}

@Test func aDefaultApproverIsOnlyAllowedWhereThePersonChoosesTheApprover() {
    #expect(allowsApproverDefault(["variableName": .string("confirmUser"), "assignees": .null]))
    #expect(allowsApproverDefault(["variableName": .string("confirmUser"), "assignees": .array([])]))
    #expect(allowsApproverDefault(["variableName": .string("confirmUser"), "assignees": .string("")]))
    #expect(!allowsApproverDefault(["variableName": .string("confirmUser"), "assignees": .array([.object(["userName": .string("A001")])])]))
    #expect(!allowsApproverDefault(["variableName": .string("confirmUser"), "assignees": .string("[\"A001\"]")]))
    // A saved default is ignored for a step the process fills in itself.
    let model = OriginalFormModel(kind: "ASSET_FAULT")
    model.assignees = [["variableName": .string("suggested"), "assignType": .string("ASSIGN"), "assignees": .array([.object(["userName": .string("S1")])])],
                       ["variableName": .string("chosen"), "assignType": .string("ASSIGN"), "assignees": .null]]
    model.selections = ["suggested": ["S1"], "chosen": []]
    let store = PickerPrefsStore(url: nil)
    let person = PickerEntry(key: "D1", label: "默认人", value: .object(["userName": .string("D1")]))
    store.setDefault(person, source: .users, scope: "s", field: "assignee.fault.suggested")
    store.setDefault(person, source: .users, scope: "s", field: "assignee.fault.chosen")
    model.applyApproverDefaults(prefix: "assignee.fault", scope: "s", store: store)
    #expect(model.selections["suggested"] == ["S1"])
    #expect(model.selections["chosen"] == ["D1"])
}

@Test func everyKindOfRowHasAStableKey() {
    #expect(referenceKey(.users, .object(["userName": .string("C0342038"), "userId": .number(5)])) == "C0342038")
    #expect(referenceKey(.departments, .object(["value": .number(7), "label": .string("设备科")])) == "7")
    #expect(referenceKey(.faultTypes, .object(["key": .string("12")])) == "12")
    #expect(referenceKey(.assets, .object(["assetId": .number(3), "assetCode": .string("EWPT-1")])) == "3")
    #expect(referenceKey(.groups, .object(["groupId": .number(2)])) == "2")
    // Without any id the name still tells rows apart.
    #expect(referenceKey(.divisions, .object(["divisionDesc": .string("记账部")])) == "记账部")
}
