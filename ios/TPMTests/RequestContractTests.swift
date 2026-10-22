import Testing
import Foundation
@testable import TPM

/// Intercepts URLSession traffic so request method, path, headers and JSON can be asserted
/// without contacting any server. Used only by the serialized suite below.
final class StubURLProtocol: URLProtocol, @unchecked Sendable {
    struct Captured: Sendable {
        let method: String
        let path: String
        let authorization: String?
        let contentType: String?
        let body: Data
    }
    nonisolated(unsafe) static var responder: (@Sendable (URLRequest) -> (Int, Data))?
    nonisolated(unsafe) static var captured: [Captured] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let request = self.request
        Self.captured.append(Captured(method: request.httpMethod ?? "GET", path: request.url?.path ?? "",
                                      authorization: request.value(forHTTPHeaderField: "Authorization"),
                                      contentType: request.value(forHTTPHeaderField: "Content-Type"),
                                      body: Self.bodyData(request)))
        let (status, data) = Self.responder?(request) ?? (200, Data("null".utf8))
        let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    static func bodyData(_ request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

@Suite(.serialized) @MainActor
struct RequestContractTests {
    private func makeSession() -> LiveSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        StubURLProtocol.captured = []
        StubURLProtocol.responder = { _ in (200, Data("{}".utf8)) }
        return LiveSession(urlSession: URLSession(configuration: configuration),
                           authentication: ["token": .string("TEST-TOKEN"), "name": .string("tester")])
    }

    private func makeQuerySession() -> LiveSession {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        return LiveSession(urlSession: URLSession(configuration: configuration),
                           authentication: ["token": .string("READ-TOKEN"), "name": .string("reader"), "factoryId": .string("factory")], queryOnly: true)
    }

    @Test func precisionQueryReadsStayIsolatedFromBusinessWrites() async throws {
        _ = makeSession()
        let query = makeQuerySession()
        _ = try await query.request("precise/getPreciseAnalysis", body: precisionAnalysisBody())
        _ = try await query.page("asset/pageAsset", filters: [filter("assetId", "123")], limit: 2)
        _ = try await query.page("precise/pagePrecise", filters: [filter("asset.assetId", "123")])
        _ = try await query.request("precise/getPreciseOrdersByPreciseId", query: ["preciseId": "456"])
        _ = try await query.request("precise/getPreciseRecordsByPreciseId", query: ["preciseId": "789"])
        #expect(StubURLProtocol.captured.count == 5)
        #expect(StubURLProtocol.captured.allSatisfy { $0.authorization == "Bearer READ-TOKEN" })
        do { _ = try await query.request("precise/handlePrecise", body: .object([:])); Issue.record("Precision write was allowed") } catch { #expect(error is APIError) }
        do { _ = try await query.page("erpOutbound/pageErpOutbound"); Issue.record("Unselected ERP expansion was allowed") } catch { #expect(error is APIError) }
        #expect(StubURLProtocol.captured.count == 5)
    }

    @Test func precisionCoverageExcludesBrandFromBothNumeratorAndDenominator() async throws {
        let session = makeSession()
        func asset(_ id: String, _ brand: String) -> JSONValue {
            .object(["assetId": .string(id), "assetCode": .string(id), "brand": .object(["brandName": .string(brand)]),
                "assetCategory": .object(["categoryId": .string("017-id"), "categoryCode": .string("017"), "categoryName": .string("017CNC加工中心")]),
                "useState": .string("IN_USE"), "assetState": .string("NORMAL")])
        }
        let a = asset("1", "TAIKAN"), b = asset("2", "台群"), excluded = asset("3", "钶锐锶")
        let analysis: JSONValue = .array([.object(["preciseAnalysisList": .array([
            .object(["asset": a, "analysisResult": .string("RESULT_A"), "analysisDate": .string("2026-10-22")]),
            .object(["asset": excluded, "analysisResult": .string("RESULT_AA")])])])])
        let assets: JSONValue = .object(["content": .array([a, b, excluded]), "totalElements": .number(3), "last": .bool(true)])
        let analysisData = try JSONEncoder().encode(analysis), assetData = try JSONEncoder().encode(assets)
        StubURLProtocol.responder = { request in (200, request.url?.lastPathComponent == "getPreciseAnalysis" ? analysisData : assetData) }
        let snapshot = try await loadPrecisionSnapshot(source: session, check: {})
        #expect(snapshot.rows.count == 1)
        #expect(snapshot.total == 2)
        #expect(snapshot.brands?["台群"] == 2)
        #expect(snapshot.brands?["钶锐锶"] == nil)
        #expect(snapshot.gradeCount("A") == 1)
        #expect(precisionBrand("FANUC") == "发那科")
        #expect(precisionBrand(" 台群 ") == "台群")
        #expect(precisionExecutionDate(.array([.number(2026), .number(2), .number(9), .number(8), .number(5)])) == "2026-02-09 08:05")
    }

    @Test func independentQueryAndOperatorUseDifferentTokens() async throws {
        let operation = makeSession(), query = makeQuerySession()
        _ = try await query.page("fault/pageAssetFault", limit: 500)
        _ = try await operation.request("process/commitTask", body: .object(["taskId": .string("operator-task")]))
        #expect(StubURLProtocol.captured.count == 2)
        #expect(StubURLProtocol.captured[0].authorization == "Bearer READ-TOKEN")
        #expect(StubURLProtocol.captured[1].authorization == "Bearer TEST-TOKEN")
        #expect(operation.authentication.text("name") == "tester")
        #expect(query.authentication.text("name") == "reader")
    }

    @Test func queryCapabilityBlocksEveryBusinessWriteAndPermissionProbe() async {
        _ = makeSession()
        let query = makeQuerySession()
        for path in ["repair/handleRepair", "process/commitTask", "fault/handleAssetFault", "erpOutbound/handleErpOutbound", "workflow/getWorkflowTaskState", "security/changeFactory"] {
            do {
                _ = try await query.request(path, body: .object([:]))
                Issue.record("Query credential reached disallowed endpoint: " + path)
            } catch { #expect((error as? APIError)?.message.contains("当前登录账号") == true) }
        }
        do { _ = try await query.uploadPhoto(Data(), directory: "fixture"); Issue.record("Query upload was allowed") }
        catch { #expect(error is APIError) }
        #expect(StubURLProtocol.captured.isEmpty)
    }

    @Test func expiredQueryLoginDoesNotSignOutOperator() async {
        let operation = makeSession(), query = makeQuerySession()
        StubURLProtocol.responder = { _ in (401, Data("{}".utf8)) }
        do { _ = try await query.request("fault/getAssetFaultDetailById", query: ["faultId": "f"]); Issue.record("Expected expired query token") }
        catch { #expect((error as? APIError)?.status == 401) }
        #expect(!query.signedIn)
        #expect(operation.signedIn)
        #expect(operation.authentication.text("token") == "TEST-TOKEN")
    }

    @Test func queryModePermissionsAndTaskComeFromOperator() async throws {
        let operation = makeSession()
        StubURLProtocol.responder = { request in
            let body: String
            switch request.url?.lastPathComponent {
            case "getAssetFaultDetailById": body = #"{"faultId":"f","faultState":"SIGNED","processInstanceId":"p","taskInfo":{"id":"operator-task","category":"SIGNED"}}"#
            case "getWorkflowTaskState": body = #"{"canSubmit":true}"#
            default: body = #"{"flowConditions":[]}"#
            }
            return (200, Data(body.utf8))
        }
        let viewed: JSONValue = .object(["faultId": .string("f"), "faultState": .string("SIGNED"), "processInstanceId": .string("p"), "taskInfo": .object(["id": .string("reader-task")])])
        let access = try await operatorOrderAccess(session: operation, kind: .fault, id: "f", viewed: viewed)
        #expect(access.actions.hasActions)
        #expect(access.actions.task["id"].text == "operator-task")
        #expect(access.notice == nil)
        #expect(StubURLProtocol.captured.allSatisfy { $0.authorization == "Bearer TEST-TOKEN" })
        #expect(StubURLProtocol.captured.allSatisfy { $0.method == "GET" })
    }

    @Test func missingOperatorPermissionNeverUsesReaderTask() async throws {
        let operation = makeSession()
        StubURLProtocol.responder = { request in
            let body = request.url?.lastPathComponent == "getAssetFaultDetailById" ? #"{"faultId":"f","faultState":"SIGNED","processInstanceId":"p","taskInfo":{"id":"operator-task"}}"# : #"{"canSubmit":false,"canDelegate":false,"canResolveDelegate":false}"#
            return (200, Data(body.utf8))
        }
        let viewed: JSONValue = .object(["faultId": .string("f"), "faultState": .string("SIGNED"), "processInstanceId": .string("p"), "taskInfo": .object(["id": .string("reader-task")])])
        let access = try await operatorOrderAccess(session: operation, kind: .fault, id: "f", viewed: viewed)
        #expect(!access.actions.hasActions)
        #expect(access.actions.task == .null)
        #expect(access.notice?.contains("没有此单据的操作权限") == true)
        #expect(StubURLProtocol.captured.allSatisfy { $0.authorization == "Bearer TEST-TOKEN" && $0.method == "GET" })
    }

    @Test func batchCompletionChecksOperatorPermissionBeforeRepairOrMaterialWrites() async {
        let operation = makeSession()
        StubURLProtocol.responder = { request in
            let body = request.url?.lastPathComponent == "getAssetFaultDetailById" ? #"{"faultId":"f","faultState":"REPAIRED","processInstanceId":"p","taskInfo":{"id":"t","category":"REPAIRED","processInstanceId":"p"}}"# : #"{"canSubmit":false}"#
            return (200, Data(body.utf8))
        }
        let item = BatchItem(id: "f", kind: .fault, name: "fixture", reference: "fixture", group: "REPAIRED")
        do {
            try await BatchProcessor.process(item, action: .complete, operation: .proceed, preset: BatchPreset(), session: operation, ask: { _ in .stop })
            Issue.record("Unauthorized batch reached the repair stage")
        } catch { #expect(error.localizedDescription.contains("没有此单据的操作权限")) }
        #expect(StubURLProtocol.captured.count == 2)
        #expect(StubURLProtocol.captured.allSatisfy { $0.method == "GET" && $0.authorization == "Bearer TEST-TOKEN" })
    }

    @Test func queryCachesAreSeparatedByOperatorAndDefaultUsesOperator() async throws {
        let operation = makeSession(), query = makeQuerySession()
        query.queryOwnerScope = "operator-a#factory"
        let first = query.draftScope
        query.queryOwnerScope = "operator-b#factory"
        #expect(first != query.draftScope)
        let suite = "query-test-" + UUID().uuidString
        let defaults = try #require(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let model = QueryAccountModel(defaults: defaults)
        let source = try await model.source(for: operation)
        #expect(source === operation)
        #expect(StubURLProtocol.captured.isEmpty)
    }

    @Test func repairSaveRequestMatchesOriginalClientContract() async throws {
        let session = makeSession()
        let payload = repairSubmissionPayload(ContractFixtures.repairRecord, repairID: .number(88))
        _ = try await session.request("repair/handleRepair", body: .object(payload))
        let sent = try #require(StubURLProtocol.captured.last)
        #expect(sent.method == "POST")
        #expect(sent.path == "/rest/repair/handleRepair")
        #expect(sent.authorization == "Bearer TEST-TOKEN")
        #expect(sent.contentType == "application/json")
        let body = try JSONDecoder().decode(JSONValue.self, from: sent.body)
        #expect(Set(body.object.keys) == ContractFixtures.repairBodyKeys)
        #expect(body["repairId"] == .number(88))
        #expect(body["content"].text == "更换轴承")
        #expect(body["startTime"].text == "2026-10-15 09:30")
        #expect(body["faultType"]["typeId"] == .number(3))
        // Read-only/related data from the loaded record is not written back.
        #expect(body["assetFault"] == .null)
        #expect(body["createTime"] == .null)
        // The ERP material lines keep every nested business object and the use quantity.
        #expect(body["erpRepairMaterials"].array.count == 1)
        #expect(body["erpRepairMaterials"].array[0] == ContractFixtures.materialLine)
    }

    @Test func nextNodeApproverMustBeChosenAndIsSentUnderItsVariable() async throws {
        let session = makeSession()
        let nodes = [ContractFixtures.confirmNode]
        let variable = "fixtureRepairConfirmUser"
        // Nothing chosen: the flow must not be submitted.
        #expect(missingAssigneeMessage(nodes: nodes, selections: [:])?.contains("【维修】确认") == true)
        #expect(StubURLProtocol.captured.isEmpty)
        // Chosen: the user name goes under the node's variable name.
        let selections = [variable: ["zhang.fixture"]]
        #expect(missingAssigneeMessage(nodes: nodes, selections: selections) == nil)
        let payload = commitTaskPayload(task: ContractFixtures.repairTask, condition: "agree", comment: "已完成",
                                        assignees: assigneesPayload(nodes: nodes, selections: selections))
        _ = try await session.request("process/commitTask", body: .object(payload))
        let sent = try #require(StubURLProtocol.captured.last)
        #expect(sent.method == "POST")
        #expect(sent.path == "/rest/process/commitTask")
        let body = try JSONDecoder().decode(JSONValue.self, from: sent.body)
        #expect(Set(body.object.keys) == ["taskId", "processInstanceId", "condition", "comment", "taskData", "assignees"])
        #expect(body["taskId"].text == "T-FIXTURE")
        #expect(body["processInstanceId"].text == "P-FIXTURE")
        #expect(body["condition"].text == "agree")
        #expect(body["comment"].text == "已完成")
        #expect(body["taskData"] == .null)
        #expect(body["assignees"][variable].text == "zhang.fixture")
    }

    @Test func returningATaskDoesNotSendApprovers() async throws {
        let session = makeSession()
        let payload = commitTaskPayload(task: ContractFixtures.repairTask, condition: "reject", comment: "退回", assignees: nil)
        _ = try await session.request("process/commitTask", body: .object(payload))
        let body = try JSONDecoder().decode(JSONValue.self, from: try #require(StubURLProtocol.captured.last).body)
        #expect(body["assignees"] == .null)
        #expect(body.object["assignees"] == nil)
        #expect(body["condition"].text == "reject")
    }

    @Test func serverFailureNamesStageRequestAndStatusWithoutSecretsOrRetry() async {
        let session = makeSession()
        StubURLProtocol.responder = { _ in (500, Data("{\"message\":\"维修记录状态不允许提交\"}".utf8)) }
        do {
            _ = try await session.request("repair/handleRepair", body: .object(["repairId": .number(1)]))
            Issue.record("a 500 response must be thrown")
        } catch {
            let text = stageFailureMessage("保存维修结果", error)
            #expect(text.contains("保存维修结果失败"))
            #expect(text.contains("POST repair/handleRepair"))
            #expect(text.contains("HTTP 500"))
            #expect(text.contains("维修记录状态不允许提交"))
            #expect(!text.contains("TEST-TOKEN"))
        }
        #expect(StubURLProtocol.captured.count == 1, "side-effecting submissions are never retried automatically")
    }

    @Test func htmlAndTokenBearingErrorBodiesAreNotShown() async {
        let session = makeSession()
        StubURLProtocol.responder = { _ in (500, Data("<html><body>Error Bearer TEST-TOKEN</body></html>".utf8)) }
        do {
            _ = try await session.request("process/commitTask", body: .object([:]))
            Issue.record("a 500 response must be thrown")
        } catch {
            let text = stageFailureMessage("提交流程节点", error)
            #expect(text.contains("提交流程节点失败"))
            #expect(text.contains("POST process/commitTask"))
            #expect(text.contains("HTTP 500"))
            #expect(text.contains("服务器未提供错误说明"))
            #expect(!text.contains("<html"))
            #expect(!text.contains("TEST-TOKEN"))
        }
        StubURLProtocol.responder = { _ in (500, Data("{\"message\":\"Authorization: Bearer abc.def\"}".utf8)) }
        do {
            _ = try await session.request("process/commitTask", body: .object([:]))
            Issue.record("a 500 response must be thrown")
        } catch {
            #expect(!stageFailureMessage("提交流程节点", error).contains("abc.def"))
        }
    }

    @Test func alternativeErrorFieldsAreRead() async {
        let session = makeSession()
        StubURLProtocol.responder = { _ in (500, Data("{\"timestamp\":\"x\",\"status\":500,\"error\":\"Internal Server Error\",\"path\":\"/rest/repair/handleRepair\"}".utf8)) }
        do {
            _ = try await session.request("repair/handleRepair", body: .object([:]))
            Issue.record("a 500 response must be thrown")
        } catch {
            #expect(stageFailureMessage("保存维修结果", error).contains("Internal Server Error"))
        }
    }

    @Test func localValidationMessagesAreNotLabelledAsServerFailures() {
        let error = APIError(message: "请选择【维修】确认的审批人")
        #expect(stageFailureMessage("提交流程节点", error) == "请选择【维修】确认的审批人")
    }
}
