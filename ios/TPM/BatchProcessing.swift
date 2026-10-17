import SwiftUI

// Handles one order of a batch with the same requests the single screens make, in the same order: read the
// order again, check that it is still at the step the batch was started for, check the permission, read the
// conditions, read the next approvers, commit. Re-reading first is how an order that somebody else handled
// meanwhile (or that this account handled on another device) is caught before anything is written.

struct BatchError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// The person chose "skip" in a problem dialog: the order is left alone and counted as skipped, not failed.
struct BatchSkipped: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// The person chose "stop" in a problem dialog: the run ends here, this order is not counted as handled.
struct BatchStopped: Error {}

/// Something the person has to decide while a batch runs (not enough stock, the ERP order could not be made, ...).
struct BatchProblem: Identifiable {
    let id = UUID()
    let order: BatchItem
    let title: String
    let message: String
}

enum BatchChoice: Equatable {
    /// Leave this order alone; it is listed as failed with the reason.
    case skip
    /// Complete the order's repair without taking any material for it.
    case withoutMaterials
    /// End the whole batch here.
    case stop
}

typealias BatchAsk = @MainActor (BatchProblem) async -> BatchChoice

func isRejectCondition(_ value: JSONValue) -> Bool {
    isReturnCondition(value) || value["name"].text.contains("驳回") || value["name"].text.contains("拒绝") || value["value"].text == "reject"
}

@MainActor
enum BatchProcessor {
    static func process(_ item: BatchItem, action: BatchAction, operation: BatchOperation, preset: BatchPreset, session: LiveSession, ask: BatchAsk) async throws {
        let detail: JSONValue
        do { detail = try await session.request(item.kind.detailPath, query: [item.kind.idKey: item.id]) }
        catch { throw wrap("读取单据", error) }
        try ensureStillAtStep(detail, item: item)
        var task = detail["taskInfo"]
        guard !task["id"].text.isEmpty else { throw BatchError(message: "这张单据现在没有待处理的任务，可能已被他人处理。") }
        if item.kind == .fault, task["category"].text != item.group {
            throw BatchError(message: "这张单据的任务已从「" + batchGroupName(item.group) + "」往下走了，可能已被他人或你在其他设备上处理。")
        }
        var note = ""
        if action == .complete && operation == .proceed {
            let saved = try await saveRepair(item: item, detail: detail, preset: preset, session: session, ask: ask)
            task = saved.task; note = saved.note
        }
        do {
            try await commit(task: task, item: item, action: action, operation: operation, preset: preset, detail: detail, session: session)
        } catch let error as BatchError {
            throw BatchError(message: error.message + note)
        }
    }

    /// Server and network failures keep their stage and server text; a cancelled request stays a cancellation.
    private static func wrap(_ stage: String, _ error: Error) -> Error {
        isCancellation(error) ? error : BatchError(message: stageFailureMessage(stage, error))
    }

    private static func ensureStillAtStep(_ detail: JSONValue, item: BatchItem) throws {
        let now = batchGroup(kind: item.kind, faultState: detail["faultState"].text, approvalState: detail["approvalState"].text)
        guard now == item.group else {
            throw BatchError(message: "单据状态已由「" + batchGroupName(item.group) + "」变为「" + batchGroupName(now) + "」，可能已被他人或你在其他设备上处理。")
        }
    }

    /// "完成": the repair result is saved first (`repair/handleRepair`), then the node is committed, like the single form.
    private static func saveRepair(item: BatchItem, detail: JSONValue, preset: BatchPreset, session: LiveSession, ask: BatchAsk) async throws -> (task: JSONValue, note: String) {
        let repair: JSONValue
        do { repair = try await session.request("fault/getLatestRepairByFaultId", query: ["faultId": item.id]) }
        catch { throw wrap("读取维修记录", error) }
        guard !repair["repairId"].text.isEmpty else { throw BatchError(message: "没有取得维修记录编号，请打开这张单据单独处理。") }
        var data = repair.object
        data["faultType"] = preset.faultType
        data["content"] = .string(preset.content)
        if data["spend"] == nil { data["spend"] = .number(0) }
        let now = Date()
        var start = preset.durationMinutes.map { now.addingTimeInterval(-Double($0) * 60) }
            ?? parsedDate(repair["signTime"] == .null ? repair["createTime"] : repair["signTime"])
        if start > now { start = now }
        data["startTime"] = .string(serverDate(start))
        data["endTime"] = .string(serverDate(now))
        var lines: [JSONValue]
        do { lines = try await session.request("erpOutbound/getErpRepairMaterialsByRepairId", query: ["repairId": repair["repairId"].text]).array }
        catch { throw wrap("读取维修领料", error) }
        for line in lines {
            let available = line["materialOutbound"]["actualQuantity"].number - line["materialOutbound"]["usedQuantity"].number
            if line["useNum"].number < 1 || line["useNum"].number > available { throw BatchError(message: "维修领料的使用数量超出可用范围，请打开这张单据单独处理。") }
        }
        var note = ""
        var takesMaterial = true
        if !preset.outboundLines.isEmpty {
            if let linked = try await linkOutbound(item: item, preset: preset, existing: lines, session: session, ask: ask) { lines += linked }
            else { takesMaterial = false }
        }
        if takesMaterial && !preset.materials.isEmpty {
            if let made = try await takeMaterials(item: item, detail: detail, preset: preset, session: session, ask: ask) {
                lines += made.rows
                note = "（已创建出库单 " + made.outNumber + "，请到 ERP 出库里核对）"
            }
        }
        data["erpRepairMaterials"] = .array(lines)
        do { _ = try await session.request("repair/handleRepair", body: .object(repairSubmissionPayload(data, repairID: repair["repairId"]))) }
        catch { throw BatchError(message: stageFailureMessage("保存维修结果", error) + note) }
        let latest = repair["assetFault"]["taskInfo"]
        return (latest["id"].text.isEmpty ? detail["taskInfo"] : latest, note)
    }

    // MARK: existing outbound lines for one order

    /// Links the preset outbound lines to this order's repair, each in the preset quantity, after checking what
    /// is left of each right now (earlier orders of this batch use it up). Nil means "complete without material".
    private static func linkOutbound(item: BatchItem, preset: BatchPreset, existing: [JSONValue], session: LiveSession, ask: BatchAsk) async throws -> [JSONValue]? {
        func decide(_ title: String, _ message: String) async throws -> [JSONValue]? {
            switch await ask(BatchProblem(order: item, title: title, message: message)) {
            case .withoutMaterials: return nil
            case .stop: throw BatchStopped()
            case .skip: throw BatchSkipped(message: message)
            }
        }
        var rows: [JSONValue] = []
        var problems: [String] = []
        for want in preset.outboundLines {
            let number = want.line["outbound"]["outNumber"].text
            let page: JSONValue
            do {
                page = try await session.page("erpOutbound/pageErpMaterialOutbound",
                                              filters: [filter("outbound.outNumber", number, "like"), filter("material.materialCode", want.line["material"]["materialCode"].text, "like")], limit: 50)
            } catch {
                if isCancellation(error) { throw error }
                return try await decide("读取出库物料失败", stageFailureMessage("读取出库物料", error))
            }
            let name = want.line["material"]["materialDesc"].text + "（" + number + "）"
            guard let current = page["content"].array.first(where: { outboundLineIdentity($0) == want.id }) else {
                problems.append(name + "：这条出库物料已经不存在"); continue
            }
            let available = current["actualQuantity"].number - current["usedQuantity"].number
            if available < want.quantity { problems.append(name + "：可用 " + quantityText(available) + "，需要 " + quantityText(want.quantity)); continue }
            if existing.contains(where: { outboundLineIdentity($0["materialOutbound"]) == want.id }) { problems.append(name + "：这张单已经关联过这条出库物料"); continue }
            rows.append(.object(["material": current["material"], "outbound": current["outbound"], "materialOutbound": current, "useNum": .number(want.quantity)]))
        }
        if !problems.isEmpty { return try await decide("出库物料不够", problems.joined(separator: "\n")) }
        return rows
    }

    // MARK: ERP material for one order

    private static func describe(_ material: JSONValue) -> String {
        let name = material["materialDesc"].text
        return name.isEmpty ? material["materialCode"].text : name
    }

    private static func quantityText(_ value: Double) -> String {
        value == value.rounded() ? String(Int(value)) : String(value)
    }

    /// Makes this order's own ERP outbound order for the preset material and returns the lines to link to the
    /// repair. A problem (not enough stock, the order cannot be made) is put to the person: skip the order,
    /// complete it without material, or stop. Nil means "complete without material".
    private static func takeMaterials(item: BatchItem, detail: JSONValue, preset: BatchPreset, session: LiveSession, ask: BatchAsk) async throws -> (rows: [JSONValue], outNumber: String)? {
        func decide(_ title: String, _ message: String) async throws -> (rows: [JSONValue], outNumber: String)? {
            switch await ask(BatchProblem(order: item, title: title, message: message)) {
            case .withoutMaterials: return nil
            case .stop: throw BatchStopped()
            case .skip: throw BatchSkipped(message: message)
            }
        }
        // 1. The stock as it is now: earlier orders of this batch may have used some of it.
        var fresh: [JSONValue] = []
        var shortages: [String] = []
        for line in preset.materials {
            let code = line.material["materialCode"].text
            let page: JSONValue
            do { page = try await session.page("erpOutbound/pageErpMaterial", filters: [filter("materialCode", code, "eq")], limit: 100) }
            catch {
                if isCancellation(error) { throw error }
                return try await decide("读取库存失败", stageFailureMessage("读取库存", error))
            }
            guard let current = page["content"].array.first(where: { $0["id"] == line.material["id"] }) else {
                shortages.append(describe(line.material) + "：账上已经没有这条库存"); continue
            }
            let onhand = current["onhandQty"].number
            if onhand < line.quantity { shortages.append(describe(current) + "：库存 " + quantityText(onhand) + "，需要 " + quantityText(line.quantity)) }
            fresh.append(current)
        }
        if !shortages.isEmpty { return try await decide("物料不够", shortages.joined(separator: "\n")) }

        // 2. The outbound order, built and checked like the ERP form does.
        let now = Date()
        let model = OriginalFormModel(kind: "ERP_OUTBOUND")
        model.assignees = preset.erpNodes
        model.selections = preset.erpApprovers
        var data = preset.erpData
        data["recipientUser"] = preset.erpRecipient
        data["dept"] = preset.erpDept
        data["division"] = preset.erpDivision
        data["projectCode"] = preset.erpProject["projectCode"]
        data["projectDesc"] = preset.erpProject["projectName"]
        data["remark"] = .string(preset.erpRemark)
        data["referenceSn"] = detail["sn"]
        data["applyDate"] = .string(serverDate(now, dateOnly: true))
        data["outboundTime"] = .string(serverDate(now))
        data["erpMaterialOutbounds"] = .array(zip(preset.materials, fresh).map { line, material in
            .object(["material": material, "id": .object(["imaterialId": material["id"], "outboundId": .null]), "entryQuantity": .number(line.quantity)])
        })
        model.data = data
        if let message = model.validationError() { return try await decide("领料单不完整", message) }
        var payload = model.payload(committed: true)
        payload.removeValue(forKey: "project")
        let result: JSONValue
        do { result = try await session.request("erpOutbound/addErpOutbound", body: .object(payload)) }
        catch {
            if isCancellation(error) { throw error }
            return try await decide("创建 ERP 出库单失败", stageFailureMessage("创建 ERP 出库单", error))
        }

        // 3. Its lines, linked to the repair with the preset quantity.
        let outboundID = result["outboundId"].text
        guard !outboundID.isEmpty else { return try await decide("创建 ERP 出库单失败", "服务器没有返回出库单编号，请到 ERP 出库里核对是否已创建。") }
        let created: JSONValue
        do { created = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": outboundID]) }
        catch { throw BatchError(message: stageFailureMessage("读取新建的出库物料", error) + "（出库单 " + result["outNumber"].text + " 已创建）") }
        let rows: [JSONValue] = created.array.map { row in
            let wanted = zip(preset.materials, fresh).first { $1["id"] == row["material"]["id"] }?.0.quantity ?? 1
            return .object(["material": row["material"], "outbound": row["outbound"], "materialOutbound": row, "useNum": .number(wanted)])
        }
        return (rows, result["outNumber"].text)
    }

    // MARK: the node

    private static func commit(task: JSONValue, item: BatchItem, action: BatchAction, operation: BatchOperation, preset: BatchPreset, detail: JSONValue, session: LiveSession) async throws {
        let state: JSONValue
        do { state = try await session.request("workflow/getWorkflowTaskState", query: ["processInstanceId": task["processInstanceId"].text]) }
        catch { throw wrap("读取处理权限", error) }
        guard state["canSubmit"].bool else { throw BatchError(message: "当前账号不能处理这一步，可能已被他人处理或已转办。") }
        let form: JSONValue
        do { form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": task["id"].text]) }
        catch { throw wrap("读取处理选项", error) }
        let conditions = form["flowConditions"].array
        let rejecting = operation == .reject
        let assigning = !rejecting && action == .accept && !preset.assignUser.object.isEmpty
        let condition: String
        if rejecting {
            guard let found = conditions.first(where: isRejectCondition) else { throw BatchError(message: "这一步没有「" + action.rejectWord + "」选项，请打开这张单据单独处理。") }
            condition = found["value"].text
        } else {
            condition = assigning ? "assign" : "agree"
            guard conditions.contains(where: { $0["value"].text == condition }) else {
                throw BatchError(message: "这一步没有「" + (assigning ? "指派" : "同意") + "」选项，请打开这张单据单独处理。")
            }
        }
        var assignees: Fields?
        if condition == "agree" { assignees = try await nextApprovers(task: task, kind: item.kind, preset: preset, session: session) }
        var payload = commitTaskPayload(task: task, condition: condition, comment: rejecting ? preset.comment : "", assignees: assignees)
        if !rejecting, let extra = try await commitTaskData(task: task, item: item, action: action, assigning: assigning, preset: preset, detail: detail, session: session) {
            payload["taskData"] = extra
        }
        do { _ = try await session.request("process/commitTask", body: .object(payload)) }
        catch { throw wrap("提交流程节点", error) }
    }

    /// The approvers of the next node: what the process suggests, replaced by the person's own default where
    /// the step lets the person choose, and finally by what was picked for the whole batch. A node that still
    /// has nobody fails this order only.
    private static func nextApprovers(task: JSONValue, kind: WorkflowKind, preset: BatchPreset, session: LiveSession) async throws -> Fields {
        let model = try await approverModel(task: task, kind: kind, session: session)
        for node in model.assignees {
            let key = node.text("variableName")
            if let picked = preset.approvers[key], !picked.isEmpty { model.selections[key] = picked }
        }
        if let message = missingAssigneeMessage(nodes: model.assignees, selections: model.selections) {
            throw BatchError(message: message + "。请单独处理这张单据，或在批量方案里选好审批人。")
        }
        return assigneesPayload(nodes: model.assignees, selections: model.selections)
    }

    /// The next nodes of a task and who would be ticked for them before the person chooses anything.
    private static func approverModel(task: JSONValue, kind: WorkflowKind, session: LiveSession) async throws -> OriginalFormModel {
        let values: JSONValue
        do {
            values = try await session.request("workflow/getAssigneeElementsByProcessCategory",
                                               query: ["processCategory": kind.rawValue, "processInstanceId": task["processInstanceId"].text, "taskKey": task["taskDefinitionKey"].text])
        } catch { throw wrap("读取下一节点审批人", error) }
        let model = OriginalFormModel(kind: "ASSET_FAULT")
        model.assignees = values.array.map(\.object)
        for node in model.assignees { model.selections[node.text("variableName")] = usernames(node["assignees"] ?? .null) }
        model.applyApproverDefaults(prefix: "assignee.task." + kind.rawValue, scope: session.draftScope, store: .shared)
        return model
    }

    /// For the preset sheet: the approver nodes the first selected order leads to, with what is already ticked.
    /// Every order of one status leads to the same next node, so one order stands for all.
    static func approverSetup(_ item: BatchItem, session: LiveSession) async throws -> (nodes: [Fields], selections: [String: [String]]) {
        let detail: JSONValue
        do { detail = try await session.request(item.kind.detailPath, query: [item.kind.idKey: item.id]) }
        catch { throw wrap("读取单据", error) }
        let task = detail["taskInfo"]
        guard !task["id"].text.isEmpty else { return ([], [:]) }
        let model = try await approverModel(task: task, kind: item.kind, session: session)
        return (model.assignees, model.selections)
    }

    /// For the preset sheet of "批量完成": the ERP order form as it opens (the person's own details and the
    /// approvers of an outbound order), so the material can be set up once.
    static func erpSetup(session: LiveSession) async -> (data: Fields, nodes: [Fields], selections: [String: [String]])? {
        let model = OriginalFormModel(kind: "ERP_OUTBOUND")
        await model.load(session)
        guard model.ready else { return nil }
        await model.applyDefaults(session)
        return (model.data, model.assignees, model.selections)
    }

    /// The `taskData` the single screens send: the repair arrangement when a fault is accepted or claimed,
    /// the material lines when an ERP order is approved, nothing otherwise.
    private static func commitTaskData(task: JSONValue, item: BatchItem, action: BatchAction, assigning: Bool, preset: BatchPreset, detail: JSONValue, session: LiveSession) async throws -> JSONValue? {
        if item.kind == .fault, action == .accept || action == .claim {
            let group = action == .accept ? preset.group : detail["userGroup"]
            let groupID = group["groupId"]
            guard !groupID.text.isEmpty else { throw BatchError(message: "这张单据没有维修班组，请打开单独处理。") }
            var data: Fields = [
                "repairWay": .string("SELF"),
                "faultType": detail["faultType"],
                "groupId": groupID,
                "estimatedCompleteTime": .string(action == .accept && !preset.estimated.text.isEmpty ? preset.estimated.text : defaultEstimatedTime()),
                "repairman": session.authentication["name"] ?? .null,
                "repairmanPhone": .null,
                "userGroup": .object(["groupId": groupID])
            ]
            if assigning {
                data["assignUser"] = preset.assignUser
                data["assignUserName"] = preset.assignUser["userName"]
            }
            return .string(JSONValue.object(data).encodedString)
        }
        if item.kind == .erp, ["EXECUTING", "CONFIRM"].contains(task["category"].text) {
            let lines: JSONValue
            do { lines = try await session.request("erpOutbound/getErpMaterialOutboundsByOutboundId", query: ["outboundId": task["businessKey"].text]) }
            catch { throw wrap("读取出库物料", error) }
            for line in lines.array where line["actualQuantity"].number < 1 || line["actualQuantity"].number > line["entryQuantity"].number {
                throw BatchError(message: "实发数量应在 1 与申请数量之间，请打开这张单据填写实发数量。")
            }
            return .string(JSONValue.array(lines.array).encodedString)
        }
        return nil
    }
}
