import Foundation
import Security

/// Preserve user choices independently of app-container replacement by a signing/install tool.
/// Passwords remain in their existing credential vaults. Never restore over an existing preference.
@MainActor enum DeveloperPreferences {
    static let keys = [ManagementSettings.enabledKey, ManagedReadSettings.experimentalKey,
                       ManagedReadSettings.pageKey, ManagedReadSettings.concurrencyKey,
                       QueryAccountModel.selectionKey]
    private static var observer: NSObjectProtocol?
    private static var lastData: Data?
    private static var query: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: "com.personal.tpm.preferences",
         kSecAttrAccount as String: "developer.v1"]
    }
    static func restoreMissing(defaults: UserDefaults = .standard) {
        var request = query
        request[kSecReturnData as String] = true; request[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let saved = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return }
        restore(saved, defaults: defaults)
    }
    static func restore(_ saved: [String: Any], defaults: UserDefaults) {
        for key in keys where defaults.object(forKey: key) == nil {
            guard let value = saved[key] else { continue }
            if key == QueryAccountModel.selectionKey {
                if let value = value as? String { defaults.set(value, forKey: key) }
            } else if let value = value as? NSNumber {
                if key == ManagedReadSettings.pageKey, !ManagedReadSettings.pageSizes.contains(value.intValue) { continue }
                if key == ManagedReadSettings.concurrencyKey, !ManagedReadSettings.concurrencyLevels.contains(value.intValue) { continue }
                defaults.set(value, forKey: key)
            }
        }
    }
    static func start() {
        guard observer == nil else { return }
        restoreMissing()
        save()
        observer = NotificationCenter.default.addObserver(forName: UserDefaults.didChangeNotification, object: nil, queue: .main) { _ in
            Task { @MainActor in save() }
        }
    }
    private static func save() {
        let saved = keys.reduce(into: [String: Any]()) { result, key in
            if let value = UserDefaults.standard.object(forKey: key) { result[key] = value }
        }
        guard let data = try? JSONSerialization.data(withJSONObject: saved, options: [.sortedKeys]), data != lastData else { return }
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var request = query
            request[kSecValueData as String] = data
            request[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            guard SecItemAdd(request as CFDictionary, nil) == errSecSuccess else { return }
        } else if status != errSecSuccess { return }
        lastData = data
    }
}
