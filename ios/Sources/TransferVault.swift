import CryptoKit
import Foundation
import Security

/// AES-256-GCM sealing for specs and checkpoints with a device-only key kept
/// in the Keychain (`AfterFirstUnlockThisDeviceOnly`, so background relaunches
/// after the first unlock can read it; never synced or backed up).
enum TransferCrypto {
    private static let service = "dev.pam.background-transfer"
    private static let account = "seal-key-v1"
    private static let lock = NSLock()
    private static var cached: SymmetricKey?

    static func seal(_ plain: String) throws -> Data {
        guard let combined = try AES.GCM.seal(Data(plain.utf8), using: key()).combined else {
            throw TransferError("Unable to seal transfer payload")
        }
        return combined
    }

    static func open(_ sealed: Data) throws -> String {
        let box = try AES.GCM.SealedBox(combined: sealed)
        return String(decoding: try AES.GCM.open(box, using: key()), as: UTF8.self)
    }

    static func writeFile(_ target: URL, _ plain: String) throws {
        try FileManager.default.createDirectory(at: target.deletingLastPathComponent(), withIntermediateDirectories: true)
        try seal(plain).write(to: target, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    }

    static func readFile(_ source: URL) throws -> String {
        try open(Data(contentsOf: source))
    }

    private static func key() throws -> SymmetricKey {
        lock.lock()
        defer { lock.unlock() }
        if let cached { return cached }
        if let data = Keychain.read(service: service, account: account) {
            let key = SymmetricKey(data: data)
            cached = key
            return key
        }
        let key = SymmetricKey(size: .bits256)
        let data = key.withUnsafeBytes { Data($0) }
        try Keychain.write(service: service, account: account, data: data)
        cached = key
        return key
    }
}

/// `Secret::vault()` credentials stored as Keychain items, read natively by
/// the transfer engine and never exposed to PHP snapshots.
enum SecretVault {
    private static let service = "dev.pam.background-transfer.vault"
    private static let namePattern = "^[A-Za-z0-9_.:-]{1,64}$"

    static func put(_ name: String, _ value: String) throws {
        try requireName(name)
        try Keychain.write(service: service, account: name, data: Data(value.utf8))
    }

    static func get(_ name: String) -> String? {
        Keychain.read(service: service, account: name).map { String(decoding: $0, as: UTF8.self) }
    }

    static func forget(_ name: String) throws {
        try requireName(name)
        Keychain.delete(service: service, account: name)
    }

    private static func requireName(_ name: String) throws {
        guard name.range(of: namePattern, options: .regularExpression) != nil else { throw TransferError("Invalid secret name") }
    }
}

enum Keychain {
    static func read(service: String, account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }

    static func write(service: String, account: String, data: Data) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            status = SecItemAdd(query.merging(attributes) { $1 } as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw TransferError("Keychain write failed (\(status))") }
    }

    static func delete(service: String, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
