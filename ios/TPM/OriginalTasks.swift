import SwiftUI

struct OriginalRepairForm: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let faultID: String
    let task: JSONValue
    var preview = false
    var onCompleted: (() -> Void)? = nil
    @State private var data: Fields = [:]
    @State private var loading = true
    @State private var saving = false
    @State private var photoUploading = false
    @State private var error: String?
    @State private var choosing = false
    @State private var creatingERP = false
    @State private var choosingAssistants = false
    @State private var workflow = false
    @State private var workflowCompleted = false
    @State private var submitAfterSave = false
    @State private var draftID: UUID?
    @State private var toast: String?
    @State private var showValidation = false
    /// True when the alert reports a failed server call rather than a form validation problem.
    @State private var failedOnServer = false
    /// The original opens the next step from the freshly loaded `repair.assetFault.taskInfo`,
    /// not from the possibly older task held by the detail page.
    @State private var latestTask: JSONValue = .null
    private var workflowTask: JSONValue { latestTask["id"].text.isEmpty ? task : latestTask }
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { data[key] ?? .null }, set: { data[key] = $0 }) }
    private func text(_ key: String) -> Binding<String> { Binding(get: { data.text(key) }, set: { data[key] = .string($0) }) }
    private func useQuantity(_ index: Int) -> Binding<Double> { Binding(get: { data["erpRepairMaterials"]!.array[index]["useNum"].number }, set: { quantity in var rows = data["erpRepairMaterials"]!.array; var row = rows[index].object; row["useNum"] = .number(cappedMaterialQuantity(quantity, maximum: (row["materialOutbound"]?["actualQuantity"].number ?? 0) - (row["materialOutbound"]?["usedQuantity"].number ?? 0))); rows[index] = .object(row); data["erpRepairMaterials"] = .array(rows) }) }
    var body: some View {
        Form {
            Section {
                ReferenceField(title: "维修故障类型", source: .faultTypes, value: field("faultType"), required: true)
                HStack { Text("维修花费"); Spacer(); TextField("0", value: Binding(get: { data["spend"]?.number ?? 0 }, set: { data["spend"] = .number($0) }), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                ServerDateField(title: "维修开始时间", value: field("startTime"), required: true)
                ServerDateField(title: "维修结束时间", value: field("endTime"), required: true)
                Button { choosingAssistants = true } label: { LabeledContent("辅助维修人", value: (data["repairUsers"]?.array ?? []).map { $0["realName"].text }.joined(separator: "、")) }
            } header: { RequiredFormHeader(title: "维修任务信息") }
            Section("ERP 出库") {
                FullRowButton("新建出库单", systemImage: "plus") { creatingERP = true }
                FullRowButton("添加出库物料", systemImage: "shippingbox") { choosing = true }.disabled(preview)
                ForEach(Array((data["erpRepairMaterials"]?.array ?? []).enumerated()), id: \.offset) { index, row in
                    DisclosureGroup {
                        LabeledContent("出库单号", value: row["outbound"]["outNumber"].text)
                        LabeledContent("领用人", value: row["outbound"]["recipientUser"]["realName"].text)
                        LabeledContent("申请数量", value: materialQuantity(row["materialOutbound"]["entryQuantity"].text, material: row["material"]))
                        LabeledContent("实发数量", value: materialQuantity(row["materialOutbound"]["actualQuantity"].text, material: row["material"]))
                        LabeledContent("剩余数量", value: materialQuantity(String(row["materialOutbound"]["actualQuantity"].number - row["materialOutbound"]["usedQuantity"].number), material: row["material"]))
                        HStack { RequiredLabel(title: "使用数量"); Spacer(); TextField("数量", value: useQuantity(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing); Text(materialUnit(row["material"])).foregroundStyle(.secondary) }
                        Button("删除明细", role: .destructive) { var rows = data["erpRepairMaterials"]?.array ?? []; rows.remove(at: index); data["erpRepairMaterials"] = .array(rows) }
                    } label: {
                        VStack(alignment: .leading, spacing: 5) {
                            Text(row["material"]["materialDesc"].text)
                            Text("料号：" + repairMaterialCode(row)).font(AppFont.subheadline).foregroundStyle(.secondary)
                            Text("使用数量：\(materialQuantity(row["useNum"].text, material: row["material"]))").font(AppFont.subheadline).foregroundStyle(.secondary)
                        }
                    }
                }
            }
            Section { EntryField(placeholder: "请输入说明", text: text("content"), limit: 255, axis: .vertical, lines: 3...8) } header: { RequiredLabel(title: "工作说明") }
            Section("图片") { OriginalPhotosField(albums: field("albums"), directory: "asset_fault", preview: preview, uploading: $photoUploading) }
            if preview { Section { Text("原包字段预览，未连接服务器").font(AppFont.footnote).foregroundStyle(.secondary) } }
        }
        .disabled(loading || saving)
        .initialLoadingContent(loading && data.isEmpty)
        .overlay { AnimatedLoadingOverlay(isPresented: loading || saving, title: saving ? "正在保存维修结果…" : "正在加载维修任务…") }
        .navigationTitle(task["name"].text.isEmpty ? "处理故障维修" : task["name"].text).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(saving || photoUploading) }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            DocumentActions(busy: saving, submitting: submitAfterSave,
                            disabled: loading || photoUploading || data.isEmpty,
                            // "保存" keeps what is typed on this phone: nothing is sent and nothing is required.
                            save: { keepDraft() }, submit: { prepare(true) })
                .opacity(workflowCompleted ? 0 : 1)
                .allowsHitTesting(!workflowCompleted)
                .accessibilityHidden(workflowCompleted)
        }
        .toast($toast)
        .task { if data.isEmpty { await load(); applyDraft() } }
        .sheet(isPresented: $choosing) { NavigationStack { ReferencePicker(source: .outboundMaterials, multiple: true, selected: (data["erpRepairMaterials"]?.array ?? []).map { outboundLineIdentity($0["materialOutbound"]) }) { addMaterial($0) } } }
        .sheet(isPresented: $choosingAssistants) {
            NavigationStack { ReferencePicker(source: .users, multiple: true, selected: (data["repairUsers"]?.array ?? []).map { $0["userName"].text }) { user in
                var people = data["repairUsers"]?.array ?? []
                if people.contains(where: { $0["userName"] == user["userName"] }) { people.removeAll { $0["userName"] == user["userName"] } }
                else { people.append(user) }
                data["repairUsers"] = .array(people)
            } }
        }
        .sheet(isPresented: $creatingERP) {
            NavigationStack { OriginalERPForm(referenceSn: data.text("assetFault.sn"), preview: preview) { result in Task {
                do { let rows = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": result["outboundId"].text]); for row in rows.array { addMaterial(row, allowUnissued: true) } }
                catch { self.error = error.localizedDescription }
            } } }
        }
        .sheet(isPresented: $workflow, onDismiss: {
            if workflowCompleted {
                // First let the workflow sheet finish closing, then close its parent once.
                if let onCompleted { onCompleted() } else { dismiss() }
            } else { Task { await load(silent: true) } }
        }) {
            NavigationStack {
                WorkflowTaskView(task: workflowTask, kind: .fault, onCompleted: {
                    workflowCompleted = true
                })
            }
        }
        .keyboardManaged()
        .errorDialog($error, title: failedOnServer ? "保存未完成" : "请检查表单", clearsOnClose: true, retry: data.isEmpty ? { Task { await load() } } : nil)
    }
    /// `silent`: a refresh behind a closed sheet, with no spinner and no dimming of the form.
    private func load(silent: Bool = false) async {
        if !silent { loading = true }
        error = nil; defer { loading = false }
        if preview {
            data = ["startTime": .string("2026-10-15 09:30"), "endTime": .string("2026-10-15 10:30"), "albums": .array([]), "erpRepairMaterials": .array(PreviewBusinessData.repairMaterials), "assetFault": .object(["sn": .string("BX-DEMO-001")]), "faultType": .object(["typeName": .string("示例故障类型")])]
            return
        }
        do {
            let repair = try await session.request("fault/getLatestRepairByFaultId", query: ["faultId": faultID])
            data = repair.object
            latestTask = repair["assetFault"]["taskInfo"]
            if data["spend"] == nil { data["spend"] = .number(0) }
            data["startTime"] = .string(serverDate(parsedDate(repair["signTime"] == .null ? repair["createTime"] : repair["signTime"])))
            data["endTime"] = .string(serverDate(Date()))
            data["erpRepairMaterials"] = try await session.request("erpOutbound/getErpRepairMaterialsByRepairId", query: ["repairId": repair["repairId"].text])
        } catch { self.error = stageFailureMessage("加载维修任务", error) }
    }
    private func addMaterial(_ material: JSONValue, allowUnissued: Bool = false) {
        var rows = data["erpRepairMaterials"]?.array ?? []
        let identity = outboundLineIdentity(material)
        if rows.contains(where: { outboundLineIdentity($0["materialOutbound"]) == identity }) {
            if !allowUnissued {
                rows.removeAll { outboundLineIdentity($0["materialOutbound"]) == identity }
                data["erpRepairMaterials"] = .array(rows)
            }
            return
        }
        guard !material["material"].object.isEmpty else { error = "此出库ERP物料信息丢失，请与ERP联系"; return }
        if !allowUnissued && material["actualQuantity"].number - material["usedQuantity"].number < 1 { error = "库存小于1，不能选择"; return }
        rows.append(.object(["material": material["material"], "outbound": material["outbound"], "materialOutbound": material, "useNum": .number(1)]))
        data["erpRepairMaterials"] = .array(rows)
    }
    /// What this form holds that is worth keeping: the registered repair fields and the ERP lines.
    private var draftKeys: [String] { repairSubmissionKeys + ["erpRepairMaterials"] }
    private func keepDraft() {
        guard !preview, !loading, !data.isEmpty else { return }
        var kept: Fields = [:]
        for key in draftKeys { if let value = data[key] { kept[key] = value } }
        let draft = FaultDraft(id: draftID ?? UUID(), savedAt: Date(), data: kept, selections: [:], orderID: faultID, orderSN: data.text("assetFault.sn"))
        do { try DraftStore.shared.save(draft, scope: session.repairDraftScope); draftID = draft.id; dismiss() }
        catch { failedOnServer = false; self.error = "草稿没有保存成功：" + error.localizedDescription }
    }
    /// A draft of this order's repair result is put back over the freshly loaded record.
    private func applyDraft() {
        guard !preview, !data.isEmpty, !faultID.isEmpty,
              let draft = DraftStore.shared.drafts(scope: session.repairDraftScope, for: .order(faultID)).first else { return }
        for key in draftKeys { if let value = draft.data[key] { data[key] = value } }
        draftID = draft.id
        toast = "已加载上次保存的草稿（" + draft.title + "）"
    }
    private func prepare(_ submit: Bool) {
        data["erpRepairMaterials"] = .array((data["erpRepairMaterials"]?.array ?? []).map { value in
            var row = value.object
            row["useNum"] = .number(cappedMaterialQuantity(value["useNum"].number, maximum: value["materialOutbound"]["actualQuantity"].number - value["materialOutbound"]["usedQuantity"].number))
            return .object(row)
        })
        failedOnServer = false
        showValidation = true
        guard data["faultType"] != nil, data["faultType"] != .null else { error = "请选择故障类型"; return }
        if data.text("content").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { error = "请输入说明"; return }
        if (data["spend"]?.number ?? 0) < 0 || (data["spend"]?.number ?? 0) > 1e18 { error = "维修花费超出允许范围"; return }
        if parsedDate(data["startTime"] ?? .null) > parsedDate(data["endTime"] ?? .null) { error = "开始时间不能晚于结束时间"; return }
        if parsedDate(data["startTime"] ?? .null) > Date() || parsedDate(data["endTime"] ?? .null) > Date() { error = "维修时间不能晚于当前时间"; return }
        for line in data["erpRepairMaterials"]?.array ?? [] {
            if line["useNum"].number < 1 || line["useNum"].number > line["materialOutbound"]["actualQuantity"].number - line["materialOutbound"]["usedQuantity"].number { error = "使用数量超出可用范围"; return }
        }
        showValidation = false; submitAfterSave = submit; Task { await save() }
    }
    private func save() async {
        guard !preview, !saving else { return }
        guard let repairID = data["repairId"], repairID != .null else {
            error = "保存维修结果失败：没有取得维修记录编号，请关闭后重新打开此任务"
            failedOnServer = true; showValidation = true
            return
        }
        saving = true; error = nil; defer { saving = false }
        do {
            // Same body as the original client: registered form fields + repairId + ERP lines,
            // not the whole record returned by getLatestRepairByFaultId.
            _ = try await session.request("repair/handleRepair", body: .object(repairSubmissionPayload(data, repairID: repairID)))
            // The server has it now: the local copy has done its job.
            if let draftID { DraftStore.shared.remove(id: draftID, scope: session.repairDraftScope); self.draftID = nil }
            if submitAfterSave { workflowCompleted = false; workflow = true } else { onCompleted?(); dismiss() }
        } catch {
            self.error = stageFailureMessage("保存维修结果", error)
            failedOnServer = true; showValidation = true
        }
    }
}

struct WorkflowTaskView: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let task: JSONValue
    let kind: WorkflowKind
    var preview = false
    var previewReturn = false
    var previewCondition = ""
    var onCompleted: (() -> Void)? = nil
    @StateObject private var assignments = OriginalFormModel(kind: "ASSET_FAULT")
    @State private var state: JSONValue = .null
    @State private var conditions: [JSONValue] = []
    @State private var condition = ""
    @State private var comment = ""
    @State private var taskData: Fields = [:]
    @State private var materials: [JSONValue] = []
    @State private var loading = true
    @State private var submitting = false
    @State private var error: String?
    /// Shown in an alert: a missing next-node approver, or a failed server call with its stage.
    @State private var failure: String?
    @State private var handoff: String?
    @State private var ready = false
    @State private var autoSubmitAttempted = false
    @State private var assignmentsReady = false
    @State private var assignmentsLoading = false
    @State private var assignmentRequest = UUID()
    private var isReturning: Bool { conditions.contains { $0["value"].text == condition && isReturnCondition($0) } }
    private var isRejecting: Bool { conditions.contains { $0["value"].text == condition && ($0["name"].text.contains("驳回") || $0["name"].text.contains("拒绝")) } }
    private var needsFaultData: Bool { kind == .fault && ["REPORTED", "RECEIVED"].contains(task["category"].text) }
    private var needsERPData: Bool { kind == .erp && ["EXECUTING", "CONFIRM"].contains(task["category"].text) }
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { taskData[key] ?? .null }, set: { taskData[key] = $0 }) }
    private func actual(_ i: Int) -> Binding<Double> { Binding(get: { materials[i]["actualQuantity"].number }, set: { number in var line = materials[i].object; line["actualQuantity"] = .number(number); materials[i] = .object(line) }) }
    var body: some View {
        Form {
            if kind == .fault && task["category"].text == "SIGNED" {
                Section("签到设备") {
                    LabeledContent("资产名称", value: taskData.text("asset.assetName"))
                    LabeledContent("资产编码", value: taskData.text("asset.assetCode"))
                    LabeledContent("地址", value: taskData.text("asset.address"))
                    LabeledContent("签到状态", value: "待签到")
                }
            }
            Section {
                Picker(selection: $condition) { ForEach(Array(conditions.enumerated()), id: \.offset) { _, value in Text(value["name"].text).tag(value["value"].text) } } label: { RequiredLabel(title: "操作类型") }
            } header: { RequiredFieldsNotice() }
            if needsFaultData && ["agree", "assign", "appoint"].contains(condition) {
                Section("维修安排") {
                    if ["assign", "appoint"].contains(condition) { ReferenceField(title: "维修人员", source: .users, value: field("assignUser"), required: true) }
                    if task["category"].text == "REPORTED" {
                        LabeledContent("维修途径", value: "自修")
                        ReferenceField(title: "维修班组", source: .groups, value: field("userGroup"), required: true) { group in taskData["groupId"] = group["groupId"] }
                        ServerDateField(title: "预计完成时间", value: field("estimatedCompleteTime"), required: true)
                    }
                }
            }
            if needsERPData {
                Section("出库物料") {
                    ForEach(Array(materials.enumerated()), id: \.offset) { index, line in
                        DisclosureGroup(line["material"]["materialDesc"].text) {
                            MaterialFacts(material: line["material"])
                            LabeledContent("申请数量", value: materialQuantity(line["entryQuantity"].text, material: line["material"]))
                            HStack { RequiredLabel(title: "实发数量"); Spacer(); TextField("数量", value: actual(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing); Text(materialUnit(line["material"])).foregroundStyle(.secondary) }
                        }
                    }
                }
            }
            if condition == "agree" && (assignmentsLoading || !assignments.assignees.isEmpty) { Section("审批人") {
                if assignmentsLoading { ProgressView("正在加载审批人…") }
                AssigneesFields(elements: assignments.assignees, selections: $assignments.selections, defaultPrefix: approverDefaultPrefix)
            } }
            Section(isReturning ? "退单原因" : isRejecting ? "驳回意见" : "意见") { EntryField(placeholder: isReturning ? "请输入退单原因" : isRejecting ? "请输入驳回意见" : "请输入意见", text: $comment, limit: 255, axis: .vertical, lines: 3...8) }
            if preview { Section { Text("流程页面预览，使用示例数据，不执行真实提交。").font(AppFont.footnote).foregroundStyle(.secondary) } }
        }
        .interfaceMotion(condition)
        .interfaceMotion(assignmentsLoading)
        .disabled(loading || submitting)
        .initialLoadingContent(loading && state == .null)
        .overlay { AnimatedLoadingOverlay(isPresented: loading || submitting, title: submitting ? "正在提交…" : "正在加载当前节点…") }
        .navigationTitle(taskActionTitle(task, kind: kind)).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(submitting) }
            if state["canDelegate"].bool || state["canResolveDelegate"].bool {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu("更多", systemImage: "ellipsis") {
                        if state["canDelegate"].bool { Button("转办") { handoff = "transfer" }; Button("委派") { handoff = "delegate" } }
                        if state["canResolveDelegate"].bool { Button("委派完成") { handoff = "resolve" } }
                    }.disabled(submitting || loading)
                }
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            DocumentActions(busy: submitting, submitting: true,
                            disabled: !state["canSubmit"].bool || !ready || !assignmentsReady || assignmentsLoading || loading || condition.isEmpty,
                            submitTitle: task["category"].text == "SIGNED" && condition == "agree" ? "签到" : "提交",
                            submit: { requestSubmit() })
        }
        .task { if !ready { await load() }; await submitDefaultIfComplete() }
        .onChange(of: condition) { _, _ in
            guard ready, !loading, !submitting else { return }
            assignmentsReady = false
            Task { await loadAssignments() }
        }
        .keyboardManaged()
        .noticeDialog("提交未完成", message: failure ?? "", isPresented: Binding(get: { failure != nil }, set: { if !$0 { failure = nil } }))
        .errorDialog($error, title: "提交未完成", retry: ready ? nil : { Task { await load() } })
        .sheet(isPresented: Binding(get: { handoff != nil }, set: { if !$0 { handoff = nil } })) {
            NavigationStack { TaskHandoffView(taskID: state["taskId"].text, operation: handoff ?? "transfer") { onCompleted?(); dismiss() } }
        }
    }
    private func load() async {
        loading = true; ready = false; assignmentsReady = false; error = nil; defer { loading = false }
        if preview {
            state = .object(["canSubmit": .bool(true)])
            let category = task["category"].text
            let choices: [(String, String)]
            switch category {
            case "UNTREATED", "SUBMIT": choices = [("提交", "agree"), ("关单", "close")]
            case "REPORTED": choices = [("派单", "agree"), ("驳回", "reject"), ("关单", "close"), ("指派", "assign")]
            case "RECEIVED": choices = [("抢单", "agree"), ("退单", "reject")]
            case "SIGNED": choices = [("签到", "agree"), ("退单", "reject")]
            case "REPAIRED": choices = [("提交任务", "agree"), ("退单", "reject")]
            case "VERIFIED": choices = [("确认通过", "agree"), ("驳回", "reject")]
            case "RESOLVED": choices = [("复线通过", "agree"), ("驳回", "reject")]
            default: choices = [("通过", "agree"), ("驳回", "reject")]
            }
            conditions = choices.map { .object(["name": .string($0.0), "value": .string($0.1)]) }
            condition = !previewCondition.isEmpty ? previewCondition : previewReturn ? "reject" : "agree"
            taskData = ["asset": .object(["assetName": .string("立式加工中心"), "assetCode": .string("FA-CNC-008"), "address": .string("一车间 A 区")]), "userGroup": .object(["groupName": .string("设备维修组")]), "groupId": .number(1), "estimatedCompleteTime": .string("2026-10-16 23:59")]
            if kind == .erp { materials = PreviewBusinessData.erpLines }
            ready = true; assignmentsReady = true
            return
        }
        do {
            state = try await session.request("workflow/getWorkflowTaskState", query: ["processInstanceId": task["processInstanceId"].text])
            let form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": task["id"].text])
            conditions = form["flowConditions"].array
            condition = (previewReturn ? conditions.first(where: { isReturnCondition($0) }) : (conditions.first(where: { $0["value"].text == "agree" }) ?? conditions.first))?["value"].text ?? ""
            if needsFaultData || (kind == .fault && task["category"].text == "SIGNED") {
                let fault = try await session.request("fault/getAssetFaultDetailById", query: ["faultId": task["businessKey"].text])
                taskData = ["asset": fault["asset"], "repairWay": .string("SELF"), "faultType": fault["faultType"], "groupId": fault["userGroup"]["groupId"], "userGroup": fault["userGroup"], "estimatedCompleteTime": .string(serverDate(Calendar.current.date(bySettingHour: 23, minute: 59, second: 59, of: Date())!))]
            }
            if needsERPData { materials = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": task["businessKey"].text]).array }
            try await fetchAssignments()
            ready = true
        } catch { self.error = stageFailureMessage("加载当前节点", error) }
    }
    /// Defaults for the next approver are kept per kind of order ("维修完成" and "领料完成" each have their own).
    private var approverDefaultPrefix: String { "assignee.task." + kind.rawValue }
    private func fetchAssignments() async throws {
        if preview {
            // Sanitized fixture of the original "完成任务" window: next node 【维修】确认, user-selected assignee.
            assignments.assignees = task["category"].text == "REPAIRED" && condition == "agree" ? [PreviewBusinessData.repairConfirmNode] : []
            assignments.selections = [:]
            assignments.applyApproverDefaults(prefix: approverDefaultPrefix, scope: "preview", store: .preview)
            assignmentsReady = true
            return
        }
        let request = UUID(); assignmentRequest = request
        assignmentsLoading = true; assignmentsReady = false
        defer { if assignmentRequest == request { assignmentsLoading = false } }
        guard condition == "agree" else { assignments.assignees = []; assignments.selections = [:]; assignmentsReady = true; return }
        let values = try await session.request("workflow/getAssigneeElementsByProcessCategory", query: ["processCategory": kind.rawValue, "processInstanceId": task["processInstanceId"].text, "taskKey": task["taskDefinitionKey"].text])
        guard assignmentRequest == request, condition == "agree" else { return }
        assignments.assignees = values.array.map(\.object)
        assignments.selections = [:]
        for node in assignments.assignees { assignments.selections[node.text("variableName")] = usernames(node["assignees"] ?? .null) }
        // The approver the person set as the default for this step wins over the process's own suggestion.
        assignments.applyApproverDefaults(prefix: approverDefaultPrefix, scope: session.draftScope, store: .shared)
        assignmentsReady = true
    }
    // A failed approver lookup keeps the submit button disabled (assignmentsReady stays false);
    // it is never interpreted as "this node needs no approver".
    private func loadAssignments() async { do { try await fetchAssignments() } catch { self.error = stageFailureMessage("加载下一节点审批人", error) } }
    private var missingAssignee: String? {
        condition == "agree" ? missingAssigneeMessage(nodes: assignments.assignees, selections: assignments.selections) : nil
    }
    private func submitDefaultIfComplete() async {
        guard !preview, !previewReturn, !autoSubmitAttempted, ready, !loading,
              assignmentsReady, !assignmentsLoading, state["canSubmit"].bool, !condition.isEmpty else { return }
        autoSubmitAttempted = true
        guard !isReturning, !isRejecting, missingAssignee == nil else { return }
        if needsFaultData && ["agree", "assign", "appoint"].contains(condition) {
            if ["assign", "appoint"].contains(condition) {
                guard !taskData.text("assignUser.userName").isEmpty else { return }
            } else {
                guard !taskData.text("groupId").isEmpty, !taskData.text("estimatedCompleteTime").isEmpty else { return }
            }
        }
        if needsERPData && materials.contains(where: { $0["actualQuantity"].number < 1 || $0["actualQuantity"].number > $0["entryQuantity"].number }) { return }
        await submit()
    }
    private func requestSubmit() {
        if let message = missingAssignee { failure = message; return }
        Task { await submit() }
    }
    private func submit() async {
        if let message = missingAssignee { failure = message; return }
        if preview { onCompleted?(); dismiss(); return }
        guard !submitting, ready, assignmentsReady, !assignmentsLoading, state["canSubmit"].bool else { return }
        submitting = true; error = nil; defer { submitting = false }
        do {
            var payload = commitTaskPayload(task: task, condition: condition, comment: comment,
                                            assignees: condition == "agree" ? assigneesPayload(nodes: assignments.assignees, selections: assignments.selections) : nil)
            if needsFaultData && ["agree", "assign", "appoint"].contains(condition) {
                if ["assign", "appoint"].contains(condition) {
                    guard !taskData.text("assignUser.userName").isEmpty else { throw APIError(message: "请选择维修人员") }
                    taskData["assignUserName"] = taskData.value("assignUser.userName")
                } else if taskData.text("groupId").isEmpty || taskData.text("estimatedCompleteTime").isEmpty { throw APIError(message: "请选择维修班组和预计完成时间") }
                taskData["repairman"] = session.authentication["name"]
                taskData["repairmanPhone"] = .null
                taskData["userGroup"] = .object(["groupId": taskData["groupId"] ?? .null])
                var originalTaskData = taskData
                originalTaskData.removeValue(forKey: "asset")
                payload["taskData"] = .string(JSONValue.object(originalTaskData).encodedString)
            }
            if needsERPData {
                for line in materials where line["actualQuantity"].number < 1 || line["actualQuantity"].number > line["entryQuantity"].number { throw APIError(message: "实发数量应在 1 与申请数量之间") }
                payload["taskData"] = .string(JSONValue.array(materials).encodedString)
            }
            _ = try await session.request("process/commitTask", body: .object(payload))
            onCompleted?(); dismiss()
        } catch {
            self.error = stageFailureMessage("提交流程节点", error)
        }
    }
}

struct TaskHandoffView: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let taskID: String
    let operation: String
    let completed: () -> Void
    @State private var user: JSONValue = .null
    @State private var reason = ""
    @State private var saving = false
    @State private var error: String?
    private var title: String { operation == "transfer" ? "流程转办" : operation == "delegate" ? "流程委派" : "委派完成" }
    var body: some View {
        Form {
            if operation != "resolve" { ReferenceField(title: operation == "transfer" ? "转办人" : "委派给", source: .workflowUsers, value: $user) }
            Section(operation == "resolve" ? "处理说明" : "原因") { EntryField(placeholder: "请输入", text: $reason, axis: .vertical, lines: 3...8) }
        }
        .navigationTitle(title).navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(saving) }
        }
        .safeAreaInset(edge: .bottom) {
            DocumentActions(busy: saving, submitting: true, disabled: reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (operation != "resolve" && user["userName"].text.isEmpty), submit: { Task { await save() } })
        }
        .overlay { AnimatedLoadingOverlay(isPresented: saving, title: "正在提交…") }
        .keyboardManaged()
        .errorDialog($error, title: "提交未完成", clearsOnClose: true)
    }
    private func save() async {
        guard !saving else { return }
        saving = true; defer { saving = false }
        do {
            var body: Fields = ["taskId": .string(taskID)]
            let path: String
            if operation == "resolve" { path = "workflow/resolveDelegatedTask"; body["opinion"] = .string(reason) }
            else { path = operation == "transfer" ? "workflow/transferTask" : "workflow/delegateTask"; body[operation == "transfer" ? "transferTo" : "delegateTo"] = user["userName"]; body["reason"] = .string(reason) }
            _ = try await session.request(path, body: .object(body)); completed(); dismiss()
        } catch { self.error = error.localizedDescription }
    }
}

func isReturnCondition(_ value: JSONValue) -> Bool {
    value["name"].text.contains("退单") || value["name"].text.contains("返单")
}
