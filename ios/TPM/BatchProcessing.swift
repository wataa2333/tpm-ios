import SwiftUI

// Handles one order of a batch with the same requests the single screens make, in the same order: read the
// order again, check that it is still at the step the batch was started for, check the permission, read the
// conditions, read the next approvers, commit. Re-reading first is how an order that somebody else handled
// meanwhile (or that this account handled on another device) is caught before anything is written.

struct BatchError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

@MainActor
enum BatchProcessor {
    static func process(_ item: BatchItem, action: BatchAction, preset: BatchPreset, session: LiveSession) async throws {
        let detail: JSONValue
        do { detail = try await session.request(item.kind.detailPath, query: [item.kind.idKey: item.id]) }
        catch { throw wrap("读取单据", error) }
        try ensureStillAtStep(detail, item: item)
        var task = detail["taskInfo"]
        guard !task["id"].text.isEmpty else { throw BatchError(message: "这张单据现在没有待处理的任务，可能已被他人处理。") }
        if item.kind == .fault, task["category"].text != item.group {
            throw BatchError(message: "这张单据的任务已从「" + batchGroupName(item.group) + "」往下走了，可能已被他人或你在其他设备上处理。")
        }
        if action == .complete { task = try await saveRepair(item: item, detail: detail, preset: preset, session: session) }
        try await commit(task: task, item: item, action: action, preset: preset, detail: detail, session: session)
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
    private static func saveRepair(item: BatchItem, detail: JSONValue, preset: BatchPreset, session: LiveSession) async throws -> JSONValue {
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
        do { data["erpRepairMaterials"] = try await session.request("erpOutbound/getErpRepairMaterialsByRepairId", query: ["repairId": repair["repairId"].text]) }
        catch { throw wrap("读取维修领料", error) }
        for line in data["erpRepairMaterials"]?.array ?? [] {
            let available = line["materialOutbound"]["actualQuantity"].number - line["materialOutbound"]["usedQuantity"].number
            if line["useNum"].number < 1 || line["useNum"].number > available { throw BatchError(message: "维修领料的使用数量超出可用范围，请打开这张单据单独处理。") }
        }
        do { _ = try await session.request("repair/handleRepair", body: .object(repairSubmissionPayload(data, repairID: repair["repairId"]))) }
        catch { throw wrap("保存维修结果", error) }
        let latest = repair["assetFault"]["taskInfo"]
        return latest["id"].text.isEmpty ? detail["taskInfo"] : latest
    }

    private static func commit(task: JSONValue, item: BatchItem, action: BatchAction, preset: BatchPreset, detail: JSONValue, session: LiveSession) async throws {
        let state: JSONValue
        do { state = try await session.request("workflow/getWorkflowTaskState", query: ["processInstanceId": task["processInstanceId"].text]) }
        catch { throw wrap("读取处理权限", error) }
        guard state["canSubmit"].bool else { throw BatchError(message: "当前账号不能处理这一步，可能已被他人处理或已转办。") }
        let form: JSONValue
        do { form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": task["id"].text]) }
        catch { throw wrap("读取处理选项", error) }
        let assigning = action == .accept && !preset.assignUser.object.isEmpty
        let condition = assigning ? "assign" : "agree"
        guard form["flowConditions"].array.contains(where: { $0["value"].text == condition }) else {
            throw BatchError(message: "这一步没有「" + (assigning ? "指派" : "同意") + "」选项，请打开这张单据单独处理。")
        }
        var assignees: Fields?
        if condition == "agree" { assignees = try await nextApprovers(task: task, kind: item.kind, session: session) }
        var payload = commitTaskPayload(task: task, condition: condition, comment: "", assignees: assignees)
        if let extra = try await commitTaskData(task: task, item: item, action: action, assigning: assigning, preset: preset, detail: detail, session: session) {
            payload["taskData"] = extra
        }
        do { _ = try await session.request("process/commitTask", body: .object(payload)) }
        catch { throw wrap("提交流程节点", error) }
    }

    /// The approvers of the next node: what the process suggests, replaced by the person's own default where
    /// the step lets the person choose. A missing approver fails this order only.
    private static func nextApprovers(task: JSONValue, kind: WorkflowKind, session: LiveSession) async throws -> Fields {
        let values: JSONValue
        do {
            values = try await session.request("workflow/getAssigneeElementsByProcessCategory",
                                               query: ["processCategory": kind.rawValue, "processInstanceId": task["processInstanceId"].text, "taskKey": task["taskDefinitionKey"].text])
        } catch { throw wrap("读取下一节点审批人", error) }
        let model = OriginalFormModel(kind: "ASSET_FAULT")
        model.assignees = values.array.map(\.object)
        for node in model.assignees { model.selections[node.text("variableName")] = usernames(node["assignees"] ?? .null) }
        model.applyApproverDefaults(prefix: "assignee.task." + kind.rawValue, scope: session.draftScope, store: .shared)
        if let message = missingAssigneeMessage(nodes: model.assignees, selections: model.selections) {
            throw BatchError(message: message + "。请单独处理这张单据，或先把审批人设为默认。")
        }
        return assigneesPayload(nodes: model.assignees, selections: model.selections)
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
                "faultType": action == .accept ? preset.faultType : detail["faultType"],
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
