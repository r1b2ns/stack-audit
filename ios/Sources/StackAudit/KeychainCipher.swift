import CryptoKit
import Foundation
import Security
import StackAuditKit

final class KeychainCipher: NSObject, DataCipher {
    private let key: SymmetricKey

    init(service: String = "io.github.r1b2ns.stackaudit", account: String = "blob-key") {
        key = Self.loadKey(service: service, account: account) ?? Self.createKey(service: service, account: account)
    }

    init(key: SymmetricKey) {
        self.key = key
    }

    func seal(data: Data) -> Data {
        (try? AES.GCM.seal(data, using: key).combined) ?? Data()
    }

    func open(data: Data) -> Data {
        guard let box = try? AES.GCM.SealedBox(combined: data), let plain = try? AES.GCM.open(box, using: key) else {
            return Data()
        }
        return plain
    }

    static var isKeychainAvailable: Bool {
        var probe = query(service: "io.github.r1b2ns.stackaudit.probe", account: "probe")
        probe[kSecValueData as String] = Data([1])
        SecItemDelete(query(service: "io.github.r1b2ns.stackaudit.probe", account: "probe") as CFDictionary)
        let status = SecItemAdd(probe as CFDictionary, nil)
        SecItemDelete(query(service: "io.github.r1b2ns.stackaudit.probe", account: "probe") as CFDictionary)
        return status == errSecSuccess
    }

    private static func query(service: String, account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func loadKey(service: String, account: String) -> SymmetricKey? {
        var search = query(service: service, account: account)
        search[kSecReturnData as String] = true
        search[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(search as CFDictionary, &result) == errSecSuccess, let data = result as? Data else {
            return nil
        }
        return SymmetricKey(data: data)
    }

    private static func createKey(service: String, account: String) -> SymmetricKey {
        let key = SymmetricKey(size: .bits256)
        var item = query(service: service, account: account)
        item[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        item[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemDelete(query(service: service, account: account) as CFDictionary)
        SecItemAdd(item as CFDictionary, nil)
        return key
    }
}
