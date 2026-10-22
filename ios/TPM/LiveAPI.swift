import SwiftUI
import Security

indirect enum JSONValue: Codable, Hashable, Sendable {
    case object([String: JSONValue]), array([JSONValue]), string(String), number(Double), bool(Bool), null
    init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null }
        else if let v = try? c.decode(Bool.self) { self = .bool(v) }
        else if let v = try? c.decode(Double.self) { self = .number(v) }
        else if let v = try? c.decode(String.self) { self = .string(v) }
        else if let v = try? c.decode([String: JSONValue].self) { self = .object(v) }
        else { self = .array(try c.decode([JSONValue].self)) }
    }
    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .string(let v): try c.encode(v)
        case .number(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }
    var object: [String: JSONValue] { if case .object(let v) = self { v } else { [:] } }
    var array: [JSONValue] { if case .array(let v) = self { v } else { [] } }
    var text: String {
        switch self {
        case .string(let v): v
        case .number(let v): v == v.rounded() ? String(format: "%.0f", v) : String(v)
        case .bool(let v): v ? "是" : "否"
        case .array(let v): v.map(\.text).joined(separator: "、")
        default: ""
        }
    }
    var bool: Bool { if case .bool(let v) = self { v } else { false } }
    var number: Double { if case .number(let v) = self { v } else { Double(text) ?? 0 } }
    subscript(_ key: String) -> JSONValue { object[key] ?? .null }
    func at(_ path: String) -> JSONValue { path.split(separator: ".").reduce(self) { $0[String($1)] } }
    var encodedString: String { String(data: (try? JSONEncoder().encode(self)) ?? Data(), encoding: .utf8) ?? "" }
}

typealias Fields = [String: JSONValue]
extension Dictionary where Key == String, Value == JSONValue {
    func text(_ path: String) -> String { JSONValue.object(self).at(path).text }
    func value(_ path: String) -> JSONValue { JSONValue.object(self).at(path) }
    var label: String {
        for key in ["realName", "assetName", "materialDesc", "deptName", "groupName", "divisionDesc", "divisionName", "projectName", "typeName", "title", "label", "taskName", "name", "userName", "sn"] {
            if let v = self[key], !v.text.isEmpty { return v.text }
        }
        return "未命名"
    }
}

struct APIError: LocalizedError {
    let message: String
    /// HTTP status of a failed server response; nil for local validation errors.
    var status: Int? = nil
    /// "POST repair/handleRepair" style label. Never contains query values, headers or bodies.
    var request: String? = nil
    /// Sanitized one-line server explanation, empty when the server gave none.
    var detail: String = ""
    var errorDescription: String? { message }
}

/// Short, safe description of a failing response body. HTML pages, multi-line
/// traces, bearer tokens and credentials are never surfaced to the user.
func serverErrorSummary(_ data: Data) -> String {
    guard !data.isEmpty else { return "" }
    var raw = ""
    if let value = try? JSONDecoder().decode(JSONValue.self, from: data) {
        for key in ["message", "msg", "errorMessage", "detail", "error"] {
            if case .string(let text) = value[key], !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { raw = text; break }
        }
    } else if let text = String(data: data, encoding: .utf8) {
        raw = text
    }
    let firstLine = raw.split(whereSeparator: { $0.isNewline }).first.map(String.init) ?? ""
    var text = firstLine.trimmingCharacters(in: .whitespacesAndNewlines)
    if text.hasPrefix("<") { return "" }
    text = text.replacingOccurrences(of: "(?i)bearer\\s+\\S+", with: "Bearer ***", options: .regularExpression)
    text = text.replacingOccurrences(of: "(?i)(token|password|authorization|secret)\\s*[=:：]\\s*\\S+", with: "$1=***", options: .regularExpression)
    return String(text.prefix(160))
}

/// "POST repair/handleRepair" — method and path only, so it is safe to display and log.
func requestLabel(_ request: URLRequest) -> String {
    var path = request.url?.path ?? ""
    if path.hasPrefix("/rest/") { path.removeFirst(6) } else if path.hasPrefix("/") { path.removeFirst() }
    return (request.httpMethod ?? "GET") + " " + path
}

/// Names the operation that failed so "请求失败（500）" can be told apart between
/// saving the repair result, loading approvers and committing the workflow node.
func stageFailureMessage(_ stage: String, _ error: Error) -> String {
    guard let api = error as? APIError, api.status != nil || api.request != nil else {
        return error is APIError ? error.localizedDescription : "\(stage)失败：\(error.localizedDescription)"
    }
    guard let status = api.status else { return "\(stage)失败：\(api.request ?? "")，\(api.message)" }
    let explanation = status == 403 ? "当前登录账号没有此请求的权限" + (api.detail.isEmpty ? "" : "；" + api.detail) : api.detail.isEmpty ? "服务器未提供错误说明" : "服务器信息：" + api.detail
    return "\(stage)失败：\(api.request ?? "") 返回 HTTP \(status)，\(explanation)"
}

@MainActor
final class LiveSession: ObservableObject {
    @Published private(set) var authentication: Fields = [:]
    @Published var signingIn = false
    @Published private(set) var scopeRevision = UUID()
    /// True while a factory change is in flight; the app dims its content and then fades the new factory in.
    @Published private(set) var switchingFactory = false
    private let root = URL(string: "http://tpm.forbetter.cn:8855/")!
    private let account = "tpm-session"
    /// Query sessions never restore/persist the operator token and cannot call business write endpoints.
    let queryOnly: Bool
    var queryOwnerScope = ""
    var signedIn: Bool { !authentication.text("token").isEmpty }
    var factories: [JSONValue] { authentication["factories"]?.array ?? [] }
    var currentFactoryID: String { authentication.text("factoryId") }
    var currentFactoryName: String {
        factories.first { $0["factoryId"].text == currentFactoryID }?["factoryName"].text ?? "选择厂区"
    }
    private let urlSession: URLSession
    /// `authentication` is only for tests that need a signed-in session without the Keychain.
    init(urlSession: URLSession = .shared, authentication initial: Fields? = nil, queryOnly: Bool = false) {
        self.urlSession = urlSession; self.queryOnly = queryOnly
        if let initial { authentication = initial } else if !queryOnly { restore() }
    }
    func restore() {
        guard !queryOnly else { return }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.personal.tpm", kSecAttrAccount as String: account, kSecReturnData as String: true]
        var result: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let data = result as? Data, let value = try? JSONDecoder().decode(JSONValue.self, from: data) { authentication = value.object }
    }
    private func persist() throws {
        guard !queryOnly else { return }
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.personal.tpm", kSecAttrAccount as String: account]
        let data = try JSONEncoder().encode(JSONValue.object(authentication))
        let attributes: [String: Any] = [kSecValueData as String: data]
        let update = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if update == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw APIError(message: "无法安全保存登录会话") }
        } else if update != errSecSuccess { throw APIError(message: "无法更新登录会话") }
    }
    func signOut() {
        if !queryOnly { UserDirectory.shared.clear() }
        scopeRevision = UUID()
        authentication = [:]
        if !queryOnly { SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.personal.tpm", kSecAttrAccount as String: account] as CFDictionary) }
    }
    func login(username: String, password: String) async throws {
        signingIn = true
        defer { signingIn = false }
        var request = URLRequest(url: root.appendingPathComponent("userLogin"))
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        request.setValue("XMLHttpRequest", forHTTPHeaderField: "x-requested-with")
        func escape(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~")) ?? "" }
        request.httpBody = "username=\(escape(username))&password=\(escape(password))".data(using: .utf8)
        // A rejected password answers 401 too; that must not sign out the account already in use.
        let result = try await perform(request, signOutOnUnauthorized: false)
        guard !result["token"].text.isEmpty else { throw APIError(message: "服务器没有返回有效登录会话") }
        authentication = result.object
        do { try persist() } catch { authentication = [:]; throw error }
        scopeRevision = UUID()
    }
    func refreshFactories() async throws {
        let info = try await request("security/getMobileLoginUserInfo")
        guard case .array = info["factories"] else { throw APIError(message: "服务器未返回厂区列表，请重试") }
        authentication["factories"] = info["factories"]
        try persist()
    }
    func changeFactory(to factoryID: String) async throws {
        guard factories.contains(where: { $0["factoryId"].text == factoryID }) else { throw APIError(message: "请选择账号可访问的厂区") }
        guard factoryID != currentFactoryID else { return }
        switchingFactory = true
        defer { switchingFactory = false }
        var components = URLComponents(url: root.appendingPathComponent("rest/security/changeFactory"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "factoryId", value: factoryID)]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("Bearer " + authentication.text("token"), forHTTPHeaderField: "Authorization")
        let result = try await perform(request, allowPlainText: true)
        guard case .string(let token) = result, !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw APIError(message: "服务器未返回新厂区的登录令牌，请重新登录核对当前厂区")
        }
        let previous = authentication
        authentication["token"] = .string(token)
        authentication["factoryId"] = factories.first { $0["factoryId"].text == factoryID }?["factoryId"] ?? .string(factoryID)
        do { try persist() } catch { authentication = previous; throw error }
        scopeRevision = UUID()
        if !queryOnly { rememberFactory() }
    }
    func request(_ path: String, query: [String: String] = [:], body: JSONValue? = nil) async throws -> JSONValue {
        if queryOnly { try QueryReadPolicy.validate(path: path, body: body) }
        var components = URLComponents(url: root.appendingPathComponent("rest").appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query.sorted { $0.key < $1.key }.map { URLQueryItem(name: $0.key, value: $0.value) } }
        var request = URLRequest(url: components.url!)
        request.setValue("Bearer " + authentication.text("token"), forHTTPHeaderField: "Authorization")
        if let body {
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try JSONEncoder().encode(body)
        }
        return try await perform(request)
    }
    private func perform(_ input: URLRequest, allowPlainText: Bool = false, signOutOnUnauthorized: Bool = true) async throws -> JSONValue {
        let scope = scopeRevision
        var request = input
        request.timeoutInterval = 60
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let label = requestLabel(request)
        let data: Data, response: URLResponse
        do { (data, response) = try await urlSession.data(for: request) }
        catch let error as URLError where error.code != .cancelled {
            throw APIError(message: "网络错误：" + error.localizedDescription, request: label)
        }
        // The page that asked may be gone by now (left, or the factory or account changed). Its answer is thrown away
        // here, before anything is decoded, stored or applied.
        try Task.checkCancellation()
        guard scope == scopeRevision else { throw CancellationError() }
        guard let http = response as? HTTPURLResponse else { throw APIError(message: "服务器响应无效", request: label) }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 && signOutOnUnauthorized { signOut() }
            let detail = serverErrorSummary(data)
            let message = http.statusCode == 403 ? "账号 \(authentication.text("name")) 没有此请求的权限" : "请求失败（\(http.statusCode)）"
            throw APIError(message: message + (detail.isEmpty ? "" : "：" + detail), status: http.statusCode, request: label, detail: detail)
        }
        if let token = http.value(forHTTPHeaderField: "Authorization"), signedIn {
            authentication["token"] = .string(token.hasPrefix("Bearer ") ? String(token.dropFirst(7)) : token)
            try persist()
        }
        if data.isEmpty { return .null }
        if allowPlainText, let text = String(data: data, encoding: .utf8), (try? JSONDecoder().decode(JSONValue.self, from: data)) == nil {
            return .string(text.trimmingCharacters(in: .whitespacesAndNewlines))
        }
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
    func page(_ path: String, offset: Int = 0, filters: [JSONValue] = [], limit: Int = 20) async throws -> JSONValue {
        try await request(path, body: .object(["offset": .number(Double(offset)), "limit": .number(Double(limit)), "sort": .string("createTime"), "order": .string("desc"), "filters": .array(filters)]))
    }
    func uploadPhoto(_ data: Data, directory: String) async throws -> JSONValue {
        guard !queryOnly else { throw APIError(message: "查询账号仅用于读取；上传和单据操作必须使用当前登录账号") }
        var components = URLComponents(url: root.appendingPathComponent("rest/upload/uploadImage"), resolvingAgainstBaseURL: false)!
        components.queryItems = [URLQueryItem(name: "directory", value: directory)]
        var request = URLRequest(url: components.url!)
        request.httpMethod = "POST"
        request.setValue("Bearer " + authentication.text("token"), forHTTPHeaderField: "Authorization")
        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        var body = Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".utf8)
        body.append(data); body.append(Data("\r\n--\(boundary)--\r\n".utf8)); request.httpBody = body
        return try await perform(request)
    }
}

/// A request that was cancelled because its page was left is not a failure: nothing is shown for it.
func isCancellation(_ error: Error) -> Bool {
    error is CancellationError || (error as? URLError)?.code == .cancelled
}

func filter(_ field: String, _ value: String, _ operation: String = "eq") -> JSONValue {
    .object(["field": .string(field), "value": .string(value), "operator": .string(operation), "logic": .string("and")])
}

extension LiveSession {
    /// Signs in and, when asked, remembers the password in the Keychain for quick switching.
    func signIn(username: String, password: String, remember: Bool) async throws {
        try await login(username: username, password: password)
        await restoreLastFactory()
        if remember {
            let name = authentication.text("fullName")
            try? AccountVault.save(username: username, password: password, displayName: name.isEmpty ? username : name)
        }
    }

    /// Switches to a remembered account: the pages dim, then the new account's pages fade in.
    func switchAccount(to account: SavedAccount) async throws {
        switch await BiometricGate.authenticate(reason: "使用已记住的账号 " + (account.displayName.isEmpty ? account.username : account.displayName)) {
        case .passed: break
        case .cancelled: return
        case .failed(let message): throw APIError(message: message)
        }
        guard let password = AccountVault.password(for: account.username) else {
            throw APIError(message: "没有找到这个账号保存的密码，请重新登录并打开“记住密码”")
        }
        switchingFactory = true
        defer { switchingFactory = false }
        try await signIn(username: account.username, password: password, remember: true)
    }
}

struct NativeLoginView: View {
    @EnvironmentObject private var session: LiveSession
    @StateObject private var saved = SavedAccountsModel()
    @State private var username = ""
    @State private var password = ""
    @State private var remember = true
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                if !saved.accounts.isEmpty {
                    Section("已记住的账号") {
                        ForEach(saved.accounts) { account in
                            Button {
                                Task {
                                    do { try await session.switchAccount(to: account) }
                                    catch { self.error = error.localizedDescription }
                                }
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(account.displayName.isEmpty ? account.username : account.displayName).foregroundStyle(Color.primary)
                                    Text(account.username).font(.footnote).foregroundStyle(Color.secondary)
                                }.frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect)
                            }
                            .buttonStyle(.plain).disabled(session.signingIn)
                            .swipeActions { Button("移除", role: .destructive) { saved.remove(account) } }
                        }
                    }
                }
                Section("原系统账号") {
                    TextField("账号", text: $username).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("密码", text: $password).textContentType(.password)
                    // The app-wide label style is made for "name ... value" rows and misplaces a switch.
                    Toggle("记住密码", isOn: $remember).labeledContentStyle(.automatic)
                }
                Section {
                    Button {
                        Task {
                            do { try await session.signIn(username: username, password: password, remember: remember); password = "" }
                            catch { self.error = error.localizedDescription }
                        }
                    } label: {
                        HStack { Text("登录"); Spacer(); if session.signingIn { ProgressView() } }
                    }.disabled(session.signingIn || username.isEmpty || password.isEmpty)
                } footer: {
                    Text("密码只保存在这台手机的钥匙串里，不会显示，也不会上传。")
                }
            }.navigationTitle("登录").errorDialog($error, title: "登录失败", clearsOnClose: true)
        }
    }
}

struct LoadingFailure: View {
    let error: String?
    let retry: () -> Void
    var body: some View {
        if let error {
            ContentUnavailableView { Label("加载失败", systemImage: "wifi.exclamationmark") } description: { Text(error) } actions: { Button("重试", action: retry) }
        } else { ProgressView("正在加载…").frame(maxWidth: .infinity, maxHeight: .infinity) }
    }
}
