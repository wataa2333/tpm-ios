import SwiftUI
import Security

/// A separate login is a read capability, never an alternate credential for workflow operations.
enum QueryReadPolicy {
    static let getPaths: Set<String> = [
        "security/getMobileLoginUserInfo", "fault/getAssetFaultDetailById", "repair/getRepairsByFaultId",
        "workflow/getWorkflowHistoryNodesByProcessInstanceId", "workflow/getAssigneeElementsByProcessInstanceId",
        "security/getUserByUserName"
    ]
    static func validate(path: String, body: JSONValue?) throws {
        if ["fault/pageAssetFault", "precise/pagePrecise", "asset/pageAsset"].contains(path), let body,
           Set(body.object.keys).isSubset(of: ["offset", "limit", "sort", "order", "filters"]),
           case .array = body["filters"], body["limit"].number > 0, body["offset"].number >= 0 { return }
        if path == "precise/getPreciseAnalysis", let body, case .object = body,
           Set(body.object.keys).isSubset(of: ["inspectStates", "faultStates", "maintainStates", "analysisResults", "type", "layerCode", "categoryIds", "useStates", "assetStates"]) { return }
        if ["precise/getPreciseOrdersByPreciseId", "precise/getPreciseRecordsByPreciseId"].contains(path), body == nil { return }
        guard body == nil && getPaths.contains(path) else {
            throw APIError(message: "查询账号仅能读取维修及精密度看板数据；单据操作必须使用当前登录账号")
        }
    }
}

private enum QueryCredentialVault {
    private static let service = "com.personal.tpm.query-account"
    private static func query(_ username: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: username]
    }
    static func save(_ password: String, username: String) throws {
        let q = query(username), data = Data(password.utf8)
        let status = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = q; add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw APIError(message: "无法保存查询账号，请解锁设备后重试") }
        } else if status != errSecSuccess { throw APIError(message: "无法更新查询账号") }
    }
    static func password(_ username: String) -> String? {
        var q = query(username); q[kSecReturnData as String] = true; q[kSecMatchLimit as String] = kSecMatchLimitOne
        var value: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &value) == errSecSuccess, let data = value as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }
    static func usernames() -> [String] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
                               kSecReturnAttributes as String: true, kSecMatchLimit as String: kSecMatchLimitAll]
        q[kSecReturnData as String] = false
        var value: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &value) == errSecSuccess, let items = value as? [[String: Any]] else { return [] }
        return items.compactMap { $0[kSecAttrAccount as String] as? String }.sorted()
    }
    static func remove(_ username: String) { SecItemDelete(query(username) as CFDictionary) }
}

@MainActor final class QueryAccountModel: ObservableObject {
    static let selectionKey = "developer.queryAccount.username"
    @Published private(set) var username: String
    @Published private(set) var revision = UUID()
    private var reader: LiveSession?
    private var binding: String?
    private var connecting: Task<LiveSession, Error>?
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults; username = defaults.string(forKey: Self.selectionKey) ?? ""
    }
    func invalidate() {
        connecting?.cancel(); connecting = nil; reader?.signOut(); reader = nil; binding = nil; revision = UUID()
    }
    func useOperator() {
        invalidate(); username = ""; defaults.removeObject(forKey: Self.selectionKey)
    }
    func forget() {
        if !username.isEmpty { QueryCredentialVault.remove(username) }
        useOperator()
    }
    var savedAccounts: [SavedAccount] {
        let accounts = AccountVault.list()
        let existing = Set(accounts.map { $0.username.lowercased() })
        return accounts + QueryCredentialVault.usernames().filter { !existing.contains($0.lowercased()) }.map {
            SavedAccount(username: $0, displayName: $0, lastUsed: .distantPast)
        }
    }
    func label(operator session: LiveSession) -> String {
        username.isEmpty ? session.authentication.text("name") : username
    }
    private func authorizeSavedPassword() async throws {
        switch await BiometricGate.authenticate(reason: "使用独立查询账号读取维修数据") {
        case .passed: break
        case .cancelled: throw CancellationError()
        case .failed(let message): throw APIError(message: message)
        }
    }
    private func align(_ source: LiveSession, to session: LiveSession) async throws {
        guard session.signedIn, !session.currentFactoryID.isEmpty else { throw APIError(message: "请先登录并选择当前厂区") }
        let factory = session.currentFactoryID
        if source.factories.isEmpty { try await source.refreshFactories() }
        guard source.factories.contains(where: { $0["factoryId"].text == factory }) else {
            throw APIError(message: "查询账号没有当前厂区的访问权限，请更换查询账号或厂区")
        }
        try await source.changeFactory(to: factory)
        source.queryOwnerScope = session.draftScope
    }
    /// Validation happens in a separate read-only session; the operator login is never replaced.
    func select(username input: String, password inputPassword: String, operator session: LiveSession) async throws {
        let name = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { throw APIError(message: "请填写查询账号") }
        if name.caseInsensitiveCompare(session.authentication.text("name")) == .orderedSame {
            useOperator(); return
        }
        let password: String
        if inputPassword.isEmpty {
            try await authorizeSavedPassword()
            guard let saved = QueryCredentialVault.password(name) ?? AccountVault.password(for: name) else { throw APIError(message: "请填写密码，或先保存该账号") }
            password = saved
        } else { password = inputPassword }
        let owner = session.scopeRevision, token = revision
        let source = LiveSession(queryOnly: true)
        try await source.login(username: name, password: password)
        guard owner == session.scopeRevision, token == revision else { throw CancellationError() }
        try await align(source, to: session)
        try Task.checkCancellation()
        guard owner == session.scopeRevision, token == revision else { throw CancellationError() }
        try QueryCredentialVault.save(password, username: name)
        invalidate(); username = name; defaults.set(name, forKey: Self.selectionKey)
        reader = source; binding = session.scopeRevision.uuidString + ":" + revision.uuidString
    }
    func source(for session: LiveSession) async throws -> LiveSession {
        guard !username.isEmpty else { return session }
        if username.caseInsensitiveCompare(session.authentication.text("name")) == .orderedSame { return session }
        let key = session.scopeRevision.uuidString + ":" + revision.uuidString
        if binding == key, let reader, reader.signedIn { return reader }
        if binding != key { connecting?.cancel(); connecting = nil; reader?.signOut(); reader = nil; binding = key }
        if connecting == nil {
            let name = username, token = revision, owner = session.scopeRevision
            connecting = Task {
                try await self.authorizeSavedPassword()
                guard let password = QueryCredentialVault.password(name) else { throw APIError(message: "查询账号凭据不可用，请到开发者选项重新验证") }
                let source = LiveSession(queryOnly: true)
                try await source.login(username: name, password: password)
                try Task.checkCancellation()
                guard owner == session.scopeRevision, token == self.revision else { throw CancellationError() }
                try await self.align(source, to: session)
                try Task.checkCancellation()
                guard token == self.revision, owner == session.scopeRevision else { throw CancellationError() }
                return source
            }
        }
        let pending = connecting!, token = revision, owner = session.scopeRevision
        do {
            let source = try await pending.value
            try Task.checkCancellation()
            guard token == revision, owner == session.scopeRevision else { throw CancellationError() }
            reader = source; connecting = nil
            return source
        } catch {
            if token == revision, owner == session.scopeRevision { connecting = nil }
            throw error
        }
    }
}

struct QueryAccountSettingsView: View {
    @EnvironmentObject private var session: LiveSession
    @EnvironmentObject private var queries: QueryAccountModel
    @State private var username = "C0382522"
    @State private var password = ""
    @State private var busy = false
    @State private var error: String?
    @State private var saved: [SavedAccount] = []
    @State private var verification: Task<Void, Never>?
    var body: some View {
        Form {
            Section {
                LabeledContent("查询使用", value: queries.label(operator: session))
                LabeledContent("操作使用", value: session.authentication.text("name"))
                Text("仅维修看板、维修搜索和详情读取使用此账号；所有单据操作始终使用当前登录账号并核对服务器权限。").font(.footnote).foregroundStyle(.secondary)
                Button("使用当前登录账号查询") { queries.useOperator(); password = "" }
            }
            Section("独立查询账号") {
                TextField("账号", text: $username).textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("密码（已保存可留空）", text: $password).textContentType(.password)
                Button("验证并使用") { verify() }.disabled(username.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if busy { ProgressView("正在验证账号与当前厂区") }
                Text("验证成功后密码仅保存在本机钥匙串中，不修改当前登录账号。查询账号无当前厂区权限时会提示。").font(.caption).foregroundStyle(.secondary)
            }
            if !saved.isEmpty {
                Section("选择已保存账号") {
                    ForEach(saved) { account in
                        Button { username = account.username; password = "" } label: {
                            VStack(alignment: .leading) {
                                Text(account.displayName.isEmpty ? account.username : account.displayName)
                                Text(account.username).font(.caption).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            if !queries.username.isEmpty {
                Section { Button("停用并移除查询凭据", role: .destructive) { queries.forget(); password = ""; saved = queries.savedAccounts } }
            }
        }.disabled(busy)
        .navigationTitle("独立查询账号").navigationBarTitleDisplayMode(.inline).nativeNavigationChrome()
        .errorDialog($error, title: "查询账号", clearsOnClose: true)
        .onAppear { saved = queries.savedAccounts; if !queries.username.isEmpty { username = queries.username } }
        .onDisappear { verification?.cancel(); password = "" }
        .interfaceMotion(busy).interfaceMotion(queries.username)
    }
    private func verify() {
        busy = true
        verification = Task {
            defer { busy = false }
            do { try await queries.select(username: username, password: password, operator: session); password = ""; saved = queries.savedAccounts }
            catch { if !isCancellation(error) { self.error = error.localizedDescription } }
        }
    }
}

/// Permission data and the task used for writes are both obtained with the operator's token.
struct OperatorOrderAccess {
    let detail: JSONValue
    let actions: DetailActionSnapshot
    let notice: String?
}

@MainActor func operatorOrderAccess(session: LiveSession, kind: WorkflowKind, id: String, viewed: JSONValue) async throws -> OperatorOrderAccess {
    guard !session.queryOnly else { throw APIError(message: "操作权限必须用当前登录账号核对") }
    let detail = try await session.request(kind.detailPath, query: [kind.idKey: id])
    guard detail[kind.idKey].text == id else { throw APIError(message: "操作账号返回的单据编号不符") }
    guard detail["faultState"] == viewed["faultState"], orderProcessID(detail) == orderProcessID(viewed) else {
        return OperatorOrderAccess(detail: detail, actions: DetailActionSnapshot(), notice: "单据状态已变化，请刷新后再操作")
    }
    let task = detail["taskInfo"], instance = orderProcessID(detail)
    guard !task["id"].text.isEmpty, !instance.isEmpty else {
        return OperatorOrderAccess(detail: detail, actions: DetailActionSnapshot(), notice: "当前登录账号没有此单据的可执行任务")
    }
    let state = try await session.request("workflow/getWorkflowTaskState", query: ["processInstanceId": instance])
    var actions = DetailActionSnapshot(task: task, permissions: state)
    guard actions.hasActions else {
        return OperatorOrderAccess(detail: detail, actions: DetailActionSnapshot(), notice: "当前登录账号 " + session.authentication.text("name") + " 没有此单据的操作权限")
    }
    if state["canSubmit"].bool {
        let form = try await session.request("workflow/getFormPropertiesConditionsByTaskId", query: ["taskId": task["id"].text])
        actions.canReturn = form["flowConditions"].array.contains { isReturnCondition($0) }
    }
    return OperatorOrderAccess(detail: detail, actions: actions, notice: nil)
}
