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
    var errorDescription: String? { message }
}

@MainActor
final class LiveSession: ObservableObject {
    @Published private(set) var authentication: Fields = [:]
    @Published var signingIn = false
    private let root = URL(string: "http://tpm.forbetter.cn:8855/")!
    private let account = "tpm-session"
    var signedIn: Bool { !authentication.text("token").isEmpty }
    init() { restore() }
    func restore() {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.personal.tpm", kSecAttrAccount as String: account, kSecReturnData as String: true]
        var result: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let data = result as? Data, let value = try? JSONDecoder().decode(JSONValue.self, from: data) { authentication = value.object }
    }
    private func persist() throws {
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
        authentication = [:]
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.personal.tpm", kSecAttrAccount as String: account] as CFDictionary)
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
        let result = try await perform(request)
        guard !result["token"].text.isEmpty else { throw APIError(message: "服务器没有返回有效登录会话") }
        authentication = result.object
        do { try persist() } catch { authentication = [:]; throw error }
    }
    func request(_ path: String, query: [String: String] = [:], body: JSONValue? = nil) async throws -> JSONValue {
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
    private func perform(_ input: URLRequest) async throws -> JSONValue {
        var request = input
        request.timeoutInterval = 60
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw APIError(message: "服务器响应无效") }
        guard (200..<300).contains(http.statusCode) else {
            if http.statusCode == 401 { signOut() }
            let value = try? JSONDecoder().decode(JSONValue.self, from: data)
            let message = value?["message"].text ?? ""
            throw APIError(message: message.isEmpty ? "请求失败（\(http.statusCode)）" : message)
        }
        if let token = http.value(forHTTPHeaderField: "Authorization"), signedIn {
            authentication["token"] = .string(token.hasPrefix("Bearer ") ? String(token.dropFirst(7)) : token)
            try persist()
        }
        if data.isEmpty { return .null }
        return try JSONDecoder().decode(JSONValue.self, from: data)
    }
    func page(_ path: String, offset: Int = 0, filters: [JSONValue] = []) async throws -> JSONValue {
        try await request(path, body: .object(["offset": .number(Double(offset)), "limit": .number(20), "sort": .string("createTime"), "order": .string("desc"), "filters": .array(filters)]))
    }
    func uploadPhoto(_ data: Data, directory: String) async throws -> JSONValue {
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

func filter(_ field: String, _ value: String, _ operation: String = "eq") -> JSONValue {
    .object(["field": .string(field), "value": .string(value), "operator": .string(operation), "logic": .string("and")])
}

struct NativeLoginView: View {
    @EnvironmentObject private var session: LiveSession
    @State private var username = ""
    @State private var password = ""
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                Section("原系统账号") {
                    TextField("账号", text: $username).textContentType(.username).textInputAutocapitalization(.never).autocorrectionDisabled()
                    SecureField("密码", text: $password).textContentType(.password)
                }
                Section {
                    Button { Task { do { try await session.login(username: username, password: password); password = "" } catch { self.error = error.localizedDescription } } } label: {
                        HStack { Text("登录"); Spacer(); if session.signingIn { ProgressView() } }
                    }.disabled(session.signingIn || username.isEmpty || password.isEmpty)
                }
                if let error { Section { Text(error).foregroundStyle(.red) } }
            }.navigationTitle("登录")
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
