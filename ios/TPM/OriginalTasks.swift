import SwiftUI

struct OriginalRepairForm: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let faultID: String
    let task: JSONValue
    var preview = false
    @State private var data: Fields = [:]
    @State private var loading = true
    @State private var saving = false
    @State private var photoUploading = false
    @State private var error: String?
    @State private var choosing = false
    @State private var creatingERP = false
    @State private var choosingAssistants = false
    @State private var workflow = false
    @State private var confirming = false
    @State private var submitAfterSave = false
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { data[key] ?? .null }, set: { data[key] = $0 }) }
    private func text(_ key: String) -> Binding<String> { Binding(get: { data.text(key) }, set: { data[key] = .string($0) }) }
    private func useQuantity(_ index: Int) -> Binding<Double> { Binding(get: { data["erpRepairMaterials"]!.array[index]["useNum"].number }, set: { quantity in var rows = data["erpRepairMaterials"]!.array; var row = rows[index].object; row["useNum"] = .number(quantity); rows[index] = .object(row); data["erpRepairMaterials"] = .array(rows) }) }
    var body: some View {
        Form {
            Section("维修任务信息") {
                ReferenceField(title: "维修故障类型", source: .faultTypes, value: field("faultType"))
                HStack { Text("维修花费"); Spacer(); TextField("0", value: Binding(get: { data["spend"]?.number ?? 0 }, set: { data["spend"] = .number($0) }), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                ServerDateField(title: "维修开始时间", value: field("startTime"))
                ServerDateField(title: "维修结束时间", value: field("endTime"))
                Button { choosingAssistants = true } label: { LabeledContent("辅助维修人", value: (data["repairUsers"]?.array ?? []).map { $0["realName"].text }.joined(separator: "、")) }
            }
            Section("ERP 出库") {
                Button("新建出库单", systemImage: "plus") { creatingERP = true }.disabled(preview)
                Button("添加出库物料", systemImage: "shippingbox") { choosing = true }.disabled(preview)
                ForEach(Array((data["erpRepairMaterials"]?.array ?? []).enumerated()), id: \.offset) { index, row in
                    DisclosureGroup(row["material"]["materialDesc"].text) {
                        LabeledContent("出库单号", value: row["outbound"]["outNumber"].text)
                        LabeledContent("领用人", value: row["outbound"]["recipientUser"]["realName"].text)
                        LabeledContent("申请数量", value: row["materialOutbound"]["entryQuantity"].text)
                        LabeledContent("实发数量", value: row["materialOutbound"]["actualQuantity"].text)
                        LabeledContent("剩余数量", value: String(row["materialOutbound"]["actualQuantity"].number - row["materialOutbound"]["usedQuantity"].number))
                        HStack { Text("使用数量"); Spacer(); TextField("数量", value: useQuantity(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                        Button("删除明细", role: .destructive) { var rows = data["erpRepairMaterials"]?.array ?? []; rows.remove(at: index); data["erpRepairMaterials"] = .array(rows) }
                    }
                }
            }
            Section("工作说明") { TextField("请输入说明", text: text("content"), axis: .vertical).lineLimit(3...8).onChange(of: data.text("content")) { _, value in if value.count > 255 { data["content"] = .string(String(value.prefix(255))) } } }
            Section("图片") { OriginalPhotosField(albums: field("albums"), directory: "asset_fault", preview: preview, uploading: $photoUploading) }
            if let error { Section { Text(error).foregroundStyle(.red); if data.isEmpty { Button("重试") { Task { await load() } } } } }
            if preview { Section { Text("原包字段预览，未连接服务器").font(.footnote).foregroundStyle(.secondary) } }
        }
        .disabled(loading || saving)
        .overlay { if loading { ProgressView("正在加载维修任务…") } }
        .navigationTitle(task["name"].text.isEmpty ? "处理故障维修" : task["name"].text).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(saving || photoUploading) }
            ToolbarItem(placement: .bottomBar) { Button("保存") { prepare(false) }.disabled(loading || saving || photoUploading || preview || data.isEmpty) }
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) { Button { prepare(true) } label: { HStack { if saving { ProgressView() }; Text("提交") } }.buttonStyle(.glassProminent).disabled(loading || saving || photoUploading || preview || data.isEmpty) }
        }
        .task { await load() }
        .sheet(isPresented: $choosing) { NavigationStack { ReferencePicker(source: .outboundMaterials, multiple: true) { addMaterial($0) } } }
        .sheet(isPresented: $choosingAssistants) {
            NavigationStack { ReferencePicker(source: .users, multiple: true, selected: (data["repairUsers"]?.array ?? []).map { $0["userName"].text }) { user in
                var people = data["repairUsers"]?.array ?? []
                if people.contains(where: { $0["userName"] == user["userName"] }) { people.removeAll { $0["userName"] == user["userName"] } }
                else { people.append(user) }
                data["repairUsers"] = .array(people)
            } }
        }
        .sheet(isPresented: $creatingERP) {
            NavigationStack { OriginalERPForm(referenceSn: data.text("assetFault.sn")) { result in Task {
                do { let rows = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": result["outboundId"].text]); for row in rows.array { addMaterial(row, allowUnissued: true) } }
                catch { self.error = error.localizedDescription }
            } } }
        }
        .sheet(isPresented: $workflow, onDismiss: { Task { await load() } }) { NavigationStack { WorkflowTaskView(task: task, kind: .fault, onCompleted: { dismiss() }) } }
        .confirmationDialog(submitAfterSave ? "保存维修结果并继续提交审批？" : "保存维修结果到原系统？", isPresented: $confirming, titleVisibility: .visible) { Button(submitAfterSave ? "继续" : "保存") { Task { await save() } } }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        if preview { data = ["startTime": .string("2026-10-15 09:30"), "endTime": .string("2026-10-15 10:30"), "albums": .array([]), "erpRepairMaterials": .array([])]; return }
        do {
            let repair = try await session.request("fault/getLatestRepairByFaultId", query: ["faultId": faultID])
            data = repair.object
            if data["spend"] == nil { data["spend"] = .number(0) }
            data["startTime"] = .string(serverDate(parsedDate(repair["signTime"] == .null ? repair["createTime"] : repair["signTime"])))
            data["endTime"] = .string(serverDate(Date()))
            data["erpRepairMaterials"] = try await session.request("erpOutbound/getErpRepairMaterialsByRepairId", query: ["repairId": repair["repairId"].text])
        } catch { self.error = error.localizedDescription }
    }
    private func addMaterial(_ material: JSONValue, allowUnissued: Bool = false) {
        guard !material["material"].object.isEmpty else { error = "此出库ERP物料信息丢失，请与ERP联系"; return }
        if !allowUnissued && material["actualQuantity"].number - material["usedQuantity"].number < 1 { error = "库存小于1，不能选择"; return }
        var rows = data["erpRepairMaterials"]?.array ?? []
        guard !rows.contains(where: { $0["material"]["id"] == material["id"]["materialId"] && $0["outbound"]["outboundId"] == material["id"]["outboundId"] }) else { error = "已选择此出库物料"; return }
        rows.append(.object(["material": material["material"], "outbound": material["outbound"], "materialOutbound": material, "useNum": .number(1)]))
        data["erpRepairMaterials"] = .array(rows)
    }
    private func prepare(_ submit: Bool) {
        guard data["faultType"] != nil, data["faultType"] != .null else { error = "请选择故障类型"; return }
        if data.text("content").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { error = "请输入说明"; return }
        if parsedDate(data["startTime"] ?? .null) > parsedDate(data["endTime"] ?? .null) { error = "开始时间不能晚于结束时间"; return }
        for line in data["erpRepairMaterials"]?.array ?? [] {
            if line["useNum"].number < 1 || line["useNum"].number > line["materialOutbound"]["actualQuantity"].number - line["materialOutbound"]["usedQuantity"].number { error = "使用数量超出可用范围"; return }
        }
        submitAfterSave = submit; confirming = true
    }
    private func save() async {
        guard !preview, !saving else { return }
        saving = true; error = nil; defer { saving = false }
        do {
            _ = try await session.request("repair/handleRepair", body: .object(data))
            if submitAfterSave { workflow = true } else { dismiss() }
        } catch { self.error = error.localizedDescription }
    }
}

struct WorkflowTaskView: View {
    @EnvironmentObject private var session: LiveSession
    @Environment(\.dismiss) private var dismiss
    let task: JSONValue
    let kind: WorkflowKind
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
    @State private var confirming = false
    @State private var error: String?
    @State private var handoff: String?
    private var needsFaultData: Bool { kind == .fault && ["REPORTED", "RECEIVED"].contains(task["category"].text) }
    private var needsERPData: Bool { kind == .erp && ["EXECUTING", "CONFIRM"].contains(task["category"].text) }
    private func field(_ key: String) -> Binding<JSONValue> { Binding(get: { taskData[key] ?? .null }, set: { taskData[key] = $0 }) }
    private func actual(_ i: Int) -> Binding<Double> { Binding(get: { materials[i]["actualQuantity"].number }, set: { number in var line = materials[i].object; line["actualQuantity"] = .number(number); materials[i] = .object(line) }) }
    var body: some View {
        Form {
            Section {
                Picker("操作类型", selection: $condition) { ForEach(Array(conditions.enumerated()), id: \.offset) { _, value in Text(value["name"].text).tag(value["value"].text) } }
            }
            if needsFaultData && ["agree", "assign", "appoint"].contains(condition) {
                Section("维修安排") {
                    if ["assign", "appoint"].contains(condition) { ReferenceField(title: "维修人员", source: .users, value: field("assignUser")) }
                    if task["category"].text == "REPORTED" {
                        Picker("维修途径", selection: Binding(get: { taskData.text("repairWay") }, set: { taskData["repairWay"] = .string($0) })) { Text("自修").tag("SELF"); Text("外协").tag("OUTSIDE") }
                        ReferenceField(title: "维修班组", source: .groups, value: field("userGroup")) { group in taskData["groupId"] = group["groupId"] }
                        ServerDateField(title: "预计完成时间", value: field("estimatedCompleteTime"))
                        if taskData.text("repairWay") == "OUTSIDE" { Text("外协附加申请尚未核对完成，当前不能提交该分支。").foregroundStyle(.secondary) }
                    }
                }
            }
            if needsERPData {
                Section("出库物料") {
                    ForEach(Array(materials.enumerated()), id: \.offset) { index, line in
                        DisclosureGroup(line["material"]["materialDesc"].text) {
                            MaterialFacts(material: line["material"])
                            LabeledContent("申请数量", value: line["entryQuantity"].text)
                            HStack { Text("实发数量"); Spacer(); TextField("数量", value: actual(index), format: .number).keyboardType(.decimalPad).multilineTextAlignment(.trailing) }
                        }
                    }
                }
            }
            if condition == "agree" { Section("审批人") { AssigneesFields(elements: assignments.assignees, selections: $assignments.selections) } }
            Section("意见") { TextField("请输入意见", text: $comment, axis: .vertical).lineLimit(3...8).onChange(of: comment) { _, value in if value.count > 255 { comment = String(value.prefix(255)) } } }
            if let error { Section { Text(error).foregroundStyle(.red); Button("重新加载") { Task { await load() } } } }
        }
        .disabled(loading || submitting)
        .overlay { if loading { ProgressView("正在加载当前节点…") } }
        .navigationTitle(task["name"].text).navigationBarTitleDisplayMode(.inline)
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
            ToolbarSpacer(.flexible, placement: .bottomBar)
            ToolbarItem(placement: .bottomBar) { Button { confirming = true } label: { HStack { if submitting { ProgressView() }; Text("提交") } }.buttonStyle(.glassProminent).disabled(!state["canSubmit"].bool || loading || submitting || condition.isEmpty || (needsFaultData && taskData.text("repairWay") == "OUTSIDE")) }
        }
        .task { await load() }
        .onChange(of: condition) { _, _ in Task { await loadAssignments() } }
        .confirmationDialog("提交当前流程节点？", isPresented: $confirming, titleVisibility: .visible) { Button("提交") { Task { await submit() } } }
        .sheet(isPresented: Binding(get: { handoff != nil }, set: { if !$0 { handoff = nil } })) {
            NavigationStack { TaskHandoffView(taskID: state["taskId"].text, operation: handoff ?? "transfer") { onCompleted?(); dismiss() } }
        }
    }
    private func load() async {
        loading = true; error = nil; defer { loading = false }
        do {
            state = try await session.request("workflow/getWorkflowTaskState", query: ["processInstanceId": task["processInstanceId"].text])
            let form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": task["id"].text])
            conditions = form["flowConditions"].array
            condition = conditions.first?["value"].text ?? ""
            if needsFaultData {
                let fault = try await session.request("fault/getAssetFaultDetailById", query: ["faultId": task["businessKey"].text])
                taskData = ["repairWay": .string("SELF"), "faultType": fault["faultType"], "groupId": fault["userGroup"]["groupId"], "userGroup": fault["userGroup"], "estimatedCompleteTime": .string(serverDate(Calendar.current.date(bySettingHour: 23, minute: 59, second: 59, of: Date())!))]
            }
            if needsERPData { materials = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": task["businessKey"].text]).array }
            try await fetchAssignments()
        } catch { self.error = error.localizedDescription }
    }
    private func fetchAssignments() async throws {
        guard condition == "agree" else { assignments.assignees = []; assignments.selections = [:]; return }
        let values = try await session.request("workflow/getAssigneeElementsByProcessCategory", query: ["processCategory": kind.rawValue, "processInstanceId": task["processInstanceId"].text, "taskKey": task["taskDefinitionKey"].text])
        assignments.assignees = values.array.map(\.object)
        for node in assignments.assignees { assignments.selections[node.text("variableName")] = usernames(node["assignees"] ?? .null) }
    }
    private func loadAssignments() async { do { try await fetchAssignments() } catch { self.error = error.localizedDescription } }
    private func submit() async {
        guard !submitting, state["canSubmit"].bool else { return }
        submitting = true; error = nil; defer { submitting = false }
        do {
            if condition == "agree" {
                for node in assignments.assignees where ["ASSIGN", "SUPERVISOR"].contains(node.text("assignType")) {
                    let count = assignments.selections[node.text("variableName"), default: []].count
                    if count == 0 || (isCountersign(node) && count < 2) { throw APIError(message: "请选择\(node.text("taskName"))的审批人，会签节点至少两人") }
                }
            }
            var payload: Fields = ["taskId": task["id"], "processInstanceId": task["processInstanceId"], "condition": .string(condition), "comment": .string(comment), "taskData": .null]
            if condition == "agree" { payload["assignees"] = assignments.payload(committed: true)["assignees"] }
            if needsFaultData {
                if ["assign", "appoint"].contains(condition) {
                    guard !taskData.text("assignUser.userName").isEmpty else { throw APIError(message: "请选择维修人员") }
                    taskData["assignUserName"] = taskData.value("assignUser.userName")
                } else if taskData.text("groupId").isEmpty || taskData.text("estimatedCompleteTime").isEmpty { throw APIError(message: "请选择维修班组和预计完成时间") }
                taskData["repairman"] = session.authentication["name"]
                taskData["repairmanPhone"] = .null
                taskData["userGroup"] = .object(["groupId": taskData["groupId"] ?? .null])
                payload["taskData"] = .string(JSONValue.object(taskData).encodedString)
            }
            if needsERPData {
                for line in materials where line["actualQuantity"].number < 1 || line["actualQuantity"].number > line["entryQuantity"].number { throw APIError(message: "实发数量应在 1 与申请数量之间") }
                payload["taskData"] = .string(JSONValue.array(materials).encodedString)
            }
            _ = try await session.request("process/commitTask", body: .object(payload))
            onCompleted?(); dismiss()
        } catch { self.error = error.localizedDescription }
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
    @State private var confirming = false
    private var title: String { operation == "transfer" ? "流程转办" : operation == "delegate" ? "流程委派" : "委派完成" }
    var body: some View {
        Form {
            if operation != "resolve" { ReferenceField(title: operation == "transfer" ? "转办人" : "委派给", source: .workflowUsers, value: $user) }
            Section(operation == "resolve" ? "处理说明" : "原因") { TextField("请输入", text: $reason, axis: .vertical).lineLimit(3...8) }
            if let error { Text(error).foregroundStyle(.red) }
        }
        .navigationTitle(title).navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("取消") { dismiss() }.disabled(saving) }
            ToolbarItem(placement: .confirmationAction) { Button { confirming = true } label: { HStack { if saving { ProgressView() }; Text("提交") } }.disabled(saving || reason.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || (operation != "resolve" && user["userName"].text.isEmpty)) }
        }
        .confirmationDialog("确认\(title)？", isPresented: $confirming, titleVisibility: .visible) { Button("提交") { Task { await save() } } }
    }
    private func save() async {
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
