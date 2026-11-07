import SwiftUI
import Security

// Remembered accounts: the login page can keep the password of an account, and the "我的" page switches
// between the remembered accounts without typing anything. Passwords are kept only in the iOS Keychain,
// readable while the phone is unlocked and never leaving this device (not in backups, not in iCloud),
// and they are never shown on screen: the lists show the account and the name, not the password.

struct SavedAccount: Identifiable, Equatable {
    let username: String
    var displayName: String
    var lastUsed: Date
    var id: String { username }
}

enum AccountVault {
    private static let service = "com.personal.tpm.accounts"
    private struct Payload: Codable {
        var password: String
        var displayName: String
        var lastUsed: Date
    }
    private static func query(_ username: String? = nil) -> [String: Any] {
        var q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service]
        if let username { q[kSecAttrAccount as String] = username }
        return q
    }
    static func save(username: String, password: String, displayName: String) throws {
        let data = try JSONEncoder().encode(Payload(password: password, displayName: displayName, lastUsed: Date()))
        let update = SecItemUpdate(query(username) as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecItemNotFound {
            var add = query(username)
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            guard SecItemAdd(add as CFDictionary, nil) == errSecSuccess else { throw APIError(message: "无法安全保存账号") }
        } else if update != errSecSuccess {
            throw APIError(message: "无法更新已保存的账号")
        }
    }
    /// The remembered accounts, most recently used first. Passwords stay in the Keychain.
    static func list() -> [SavedAccount] {
        var q = query()
        q[kSecReturnAttributes as String] = true
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitAll
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let items = result as? [[String: Any]] else { return [] }
        return items.compactMap { item in
            guard let name = item[kSecAttrAccount as String] as? String, let data = item[kSecValueData as String] as? Data,
                  let payload = try? JSONDecoder().decode(Payload.self, from: data) else { return nil }
            return SavedAccount(username: name, displayName: payload.displayName, lastUsed: payload.lastUsed)
        }.sorted { $0.lastUsed > $1.lastUsed }
    }
    static func password(for username: String) -> String? {
        var q = query(username)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess, let data = result as? Data,
              let payload = try? JSONDecoder().decode(Payload.self, from: data) else { return nil }
        return payload.password
    }
    static func remove(username: String) {
        SecItemDelete(query(username) as CFDictionary)
    }
}

@MainActor
final class SavedAccountsModel: ObservableObject {
    @Published private(set) var accounts: [SavedAccount] = AccountVault.list()
    func reload() { accounts = AccountVault.list() }
    func remove(_ account: SavedAccount) {
        AccountVault.remove(username: account.username)
        reload()
    }
}

/// The "我的" tab: the account, the factory, switching between remembered accounts and signing out.
struct ProfileTab: View {
    @EnvironmentObject private var session: LiveSession
    @StateObject private var saved = SavedAccountsModel()
    @State private var showAccounts = false
    @State private var refreshing = false
    @State private var switchError: String?
    @State private var draftCount = 0
    @State private var resuming: FaultDraft?
    @State private var draftNotice: String?
    @State private var developer = false
    private var current: String { session.authentication.text("name") }
    var body: some View {
        List {
            Section {
                LabeledContent("账号") { Text(current).animatedTextValue(current) }
                LabeledContent("姓名") { Text(session.authentication.text("fullName")).animatedTextValue(session.authentication.text("fullName")) }
            }
            Section { LabeledContent("当前厂区") { Text(session.currentFactoryName).animatedTextValue(session.currentFactoryName) } }
            Section {
                NavigationLink {
                    DraftBoxView { draft in
                        // A draft of an existing order goes back into that order's own form, never into another.
                        if let sn = draft.orderSN, draft.orderID != nil { draftNotice = "这份草稿属于单号 " + sn + "。请打开该工单，点“编辑”继续填写。" }
                        else if draft.orderID != nil { draftNotice = "这份草稿属于一张已有的工单。请打开该工单，点“编辑”继续填写。" }
                        else { resuming = draft }
                    }
                } label: {
                    HStack { Label("草稿箱", systemImage: "tray.full"); Spacer(); if draftCount > 0 { Text("\(draftCount)").animatedTextValue("\(draftCount)").foregroundStyle(.secondary) } }
                }.accessibilityIdentifier("draft-box")
            }
            Section {
                DisclosureGroup(isExpanded: $showAccounts) {
                    ForEach(saved.accounts) { account in
                        Button {
                            Task {
                                do { try await session.switchAccount(to: account); saved.reload() }
                                catch { switchError = error.localizedDescription }
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(account.displayName.isEmpty ? account.username : account.displayName).animatedTextValue(account.displayName.isEmpty ? account.username : account.displayName).foregroundStyle(Color.primary)
                                    Text(account.username).animatedTextValue(account.username).font(AppFont.footnote).foregroundStyle(Color.secondary)
                                }
                                Spacer()
                                if account.username == current { Image(systemName: "checkmark").foregroundStyle(Color.accentColor) }
                            }.contentShape(.rect)
                        }
                        .buttonStyle(.plain)
                        .disabled(account.username == current || session.signingIn)
                        .swipeActions { Button("移除", role: .destructive) { saved.remove(account) } }
                        .accessibilityIdentifier("saved-account-" + account.username)
                    }
                    if saved.accounts.isEmpty {
                        Text("还没有记住的账号。登录时打开“记住密码”，之后就可以在这里一键切换。").font(AppFont.footnote).foregroundStyle(.secondary)
                    }
                    Button("添加其他账号", systemImage: "plus") { session.signOut() }
                } label: {
                    HStack { Label("切换账号", systemImage: "person.2.fill"); Spacer(); if session.signingIn { ProgressView() } }
                }
                .accessibilityIdentifier("switch-account")
            }
            Section { NavigationLink { AppearanceSettings() } label: { Label("外观", systemImage: "paintpalette") } }
            Section("其他") {
                Button { developer = true } label: { Label("开发者选项", systemImage: "hammer") }
                    .accessibilityIdentifier("developer-options")
                NavigationLink { QueryAccountSettingsView() } label: { Label("独立查询账号", systemImage: "person.badge.key") }
            }
            Section { Button("退出登录", role: .destructive) { session.signOut() } }
        }
        .navigationTitle("我的").navigationBarTitleDisplayMode(.large).nativeNavigationChrome()
        .modifier(ListTabVisibility())
        .sheet(isPresented: $developer) { DeveloperAccessSheet() }
        .rootTopBar(busy: refreshing) {
            Task { refreshing = true; try? await session.refreshFactories(); refreshing = false }
        }
        .errorDialog($switchError, title: "切换账号失败", clearsOnClose: true)
        .errorDialog($draftNotice, title: "请从工单里继续", clearsOnClose: true)
        .onAppear { saved.reload(); draftCount = DraftStore.shared.drafts(scope: session.draftScope).count }
        .sheet(item: $resuming, onDismiss: { draftCount = DraftStore.shared.drafts(scope: session.draftScope).count }) { draft in
            NavigationStack { OriginalFaultForm(draft: draft) }.presentationDragIndicator(.visible)
        }
    }
}
