import Foundation
import Security

/// Where the app keeps what a push needs, for the notification service extension to read too.
///
/// A store of its own rather than the shared module's vault: the extension is a separate process that
/// cannot load the Kotlin framework in the memory iOS gives it, and could not open the vault's file
/// or its keys anyway, both being the app's alone. What is here is what Android keeps in the vault's
/// entries written without the unlocked-device requirement, and for the same reason: a push mostly
/// arrives with the phone in a pocket.
public protocol SecretStore: Sendable {
    /// What is kept under `account`, or nil when nothing is, or it cannot be read.
    func read(_ account: String) -> Data?

    /// Keeps `data` under `account`, replacing whatever was, and says whether it is kept.
    @discardableResult
    func write(_ data: Data, to account: String) -> Bool

    /// Forgets what is kept under `account`, if anything is.
    func delete(_ account: String)
}

/// The keychain, in the access group the app shares with its extension.
///
/// Readable once the device has been unlocked since it started, locked or not since, and never by
/// another device: a backup restored elsewhere brings none of it, which leaves that device with no
/// keys to decrypt a push with until it registers, and nobody on its wake list until its first sync.
public struct KeychainStore: SecretStore {
    /// The app group both the app and the extension are in, which iOS also makes a keychain access
    /// group of: one that needs no team prefix spelt out at run time.
    public static let appGroup = "group.app.lidory.duckcount"

    /// The store the app and the extension share.
    public static let shared = KeychainStore(service: "app.lidory.duckcount.push", accessGroup: appGroup)

    private let service: String
    private let accessGroup: String

    public init(service: String, accessGroup: String) {
        self.service = service
        self.accessGroup = accessGroup
    }

    public func read(_ account: String) -> Data? {
        var query = self.query(account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess else {
            if status != errSecItemNotFound {
                log("read", status)
            }

            return nil
        }

        return result as? Data
    }

    @discardableResult
    public func write(_ data: Data, to account: String) -> Bool {
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]

        let update = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)

        if update == errSecSuccess {
            return true
        }

        guard update == errSecItemNotFound else {
            log("update", update)

            return false
        }

        let add = SecItemAdd(query(account).merging(attributes) { $1 } as CFDictionary, nil)

        guard add == errSecSuccess else {
            log("add", add)

            return false
        }

        return true
    }

    public func delete(_ account: String) {
        let status = SecItemDelete(query(account) as CFDictionary)

        if status != errSecSuccess && status != errSecItemNotFound {
            log("delete", status)
        }
    }

    private func query(_ account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecAttrAccessGroup as String: accessGroup,
        ]
    }

    /// The status alone, which names nothing that is kept.
    private func log(_ operation: String, _ status: OSStatus) {
        print("KeychainStore: could not \(operation) an item: \(status)")
    }
}

/// A store in memory, for tests.
public final class MemoryStore: SecretStore, @unchecked Sendable {
    private let lock = NSLock()
    private var items: [String: Data] = [:]

    public init() {}

    public func read(_ account: String) -> Data? {
        lock.withLock { items[account] }
    }

    @discardableResult
    public func write(_ data: Data, to account: String) -> Bool {
        lock.withLock { items[account] = data }

        return true
    }

    public func delete(_ account: String) {
        _ = lock.withLock { items.removeValue(forKey: account) }
    }
}
