import Foundation
import Security

enum KeychainError: Error, Equatable {
    case unexpectedStatus(OSStatus)
    case notFound
    case dataConversionFailure
    case randomGenerationFailed(OSStatus)
    case invalidSecretLength
}

protocol KeychainStorage: Sendable {
    func load(account: String) throws -> Data
    func save(_ data: Data, account: String) throws
    func delete(account: String) throws
}

final class Keychain: KeychainStorage, @unchecked Sendable {
    static let pairingSecretService = "com.clipsync.pairing-secret"
    static let defaultAccount = "default"

    private let service: String

    init(service: String = Keychain.pairingSecretService) {
        self.service = service
    }

    func load(account: String = Keychain.defaultAccount) throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else {
                throw KeychainError.dataConversionFailure
            }
            return data
        case errSecItemNotFound:
            throw KeychainError.notFound
        default:
            throw KeychainError.unexpectedStatus(status)
        }
    }

    func save(_ data: Data, account: String = Keychain.defaultAccount) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [kSecValueData as String: data]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        switch updateStatus {
        case errSecSuccess:
            return
        case errSecItemNotFound:
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
            let addStatus = SecItemAdd(add as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainError.unexpectedStatus(addStatus)
            }
        default:
            throw KeychainError.unexpectedStatus(updateStatus)
        }
    }

    func delete(account: String = Keychain.defaultAccount) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let status = SecItemDelete(query as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    func loadOrCreateSecret(size: Int = 32,
                            account: String = Keychain.defaultAccount) throws -> Data {
        guard size == 32 else { throw KeychainError.invalidSecretLength }
        do {
            let value = try load(account: account)
            guard value.count == size else { throw KeychainError.invalidSecretLength }
            return value
        } catch KeychainError.notFound {
            let bytes = try Self.randomBytes(count: size)
            try save(bytes, account: account)
            return bytes
        }
    }

    static func randomBytes(count: Int) throws -> Data {
        var buffer = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &buffer)
        guard status == errSecSuccess else {
            throw KeychainError.randomGenerationFailed(status)
        }
        return Data(buffer)
    }
}

extension KeychainError: LocalizedError {
    var errorDescription: String? {
        switch self {
        case .unexpectedStatus(let s): return "Keychain error (OSStatus \(s))"
        case .notFound: return "Item not found in Keychain"
        case .dataConversionFailure: return "Failed to decode Keychain data"
        case .randomGenerationFailed(let s): return "Secure random generation failed (OSStatus \(s))"
        case .invalidSecretLength: return "Stored pairing secret has an invalid length"
        }
    }
}
