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
