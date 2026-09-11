import Foundation
import CryptoKit

enum TokenStoreError: Error {
    case invalidRecord
    case storageUnavailable
    case unauthorized
}


/// Serializes synchronous effects with publication of persisted authorization changes.
/// Operations must not call back into this gate or wait on TokenStore.
final class AuthorizationGate: @unchecked Sendable {
    private let lock = NSLock()
    private var activeIDs: Set<String> = []

    func publish(_ ids: Set<String>) {
        lock.lock(); defer { lock.unlock() }
        // TokenStore enforces sixteen records; refuse unexpected oversized state.
        activeIDs = ids.count <= 16 ? ids : []
    }

    func withAuthorization<T>(id: String, operation: () throws -> T) throws -> T {
        lock.lock(); defer { lock.unlock() }
        guard activeIDs.contains(id) else { throw TokenStoreError.unauthorized }
        return try operation()
    }
}

/// Persistent store of issued pairing tokens.
///
/// Tokens are persisted as SHA-256 hashes (never plaintext). Plaintext is only
/// ever returned from `issue(...)` and passed into `validate(...)` for comparison.
actor TokenStore {
    struct Record: Codable, Equatable, Sendable {
        let id: String
        let tokenHash: String        // hex SHA-256 of plaintext token
        var createdAt: Date
        var lastSeenAt: Date
        var deviceLabel: String
    }

    private nonisolated let authorization = AuthorizationGate()

    nonisolated func withAuthorization<T>(id: String, operation: () throws -> T) throws -> T {
        try authorization.withAuthorization(id: id, operation: operation)
    }

    private let keychain: any KeychainStorage
    private let account: String
    private var records: [String: Record] = [:] // id -> record
    private var loaded: Bool = false
    private var failed = false
    private var pairingGeneration = UUID()

    init(keychain: any KeychainStorage = Keychain(service: TokenStore.service),
         account: String = TokenStore.defaultAccount) {
        self.keychain = keychain
        self.account = account
    }

    static let service = "com.clipsync.token-store"
    static let defaultAccount = "tokens"

    // MARK: - Public API

    /// Capture before consuming a single-use pairing session. Registration is rejected if
    /// revocation intervenes, even when the response was already generated on another actor.
    func issuanceGeneration() throws -> UUID {
        try loadIfNeeded()
        return pairingGeneration
    }

    /// Registers an externally-generated plaintext token (e.g. one produced by
    /// `PairingManager.consume`) so it can be validated later.
    @discardableResult
    func register(tokenPlain: String,
                  deviceLabel: String,
                  now: Date = Date(),
                  issuanceGeneration: UUID? = nil) throws -> Record {
        try loadIfNeeded()
        guard issuanceGeneration == nil || issuanceGeneration == pairingGeneration else { throw TokenStoreError.unauthorized }
        guard Self.isValidToken(tokenPlain), Self.isValidLabel(deviceLabel), records.count < 16 else {
            throw TokenStoreError.invalidRecord
        }
        let id = UUID().uuidString
        let tokenHash = Self.hashHex(tokenPlain)
        guard !records.values.contains(where: { $0.tokenHash == tokenHash }) else { throw TokenStoreError.invalidRecord }
        let record = Record(
            id: id,
            tokenHash: tokenHash,
            createdAt: now,
            lastSeenAt: now,
            deviceLabel: deviceLabel
        )
        var next = records
        next[id] = record
        try commit(next)
        return record
    }

    func issue(deviceLabel: String, now: Date = Date()) throws -> (id: String, tokenPlain: String) {
        try loadIfNeeded()
        guard Self.isValidLabel(deviceLabel), records.count < 16 else { throw TokenStoreError.invalidRecord }
        let id = UUID().uuidString
        let tokenBytes = try Self.randomTokenBytes()
        let tokenPlain = tokenBytes.base64EncodedString()
        let tokenHash = Self.hashHex(tokenPlain)
        guard !records.values.contains(where: { $0.tokenHash == tokenHash }) else { throw TokenStoreError.invalidRecord }
        let record = Record(
            id: id,
            tokenHash: tokenHash,
            createdAt: now,
            lastSeenAt: now,
            deviceLabel: deviceLabel
        )
        var next = records
        next[id] = record
        try commit(next)
        return (id, tokenPlain)
    }

    func validate(tokenPlain: String) throws -> Record? {
        try loadIfNeeded()
        guard Self.isValidToken(tokenPlain) else { return nil }
        let hash = Self.hashHex(tokenPlain)
        return records.values.first(where: { $0.tokenHash == hash })
    }

    func touch(id: String, at date: Date = Date()) throws {
        try loadIfNeeded()
        guard var rec = records[id] else { return }
        rec.lastSeenAt = date
        var next = records
        next[id] = rec
        try commit(next)
    }

    func revoke(id: String) throws {
        try loadIfNeeded()
        var next = records
        next.removeValue(forKey: id)
        try commit(next)
        pairingGeneration = UUID()
    }

    func list() throws -> [Record] {
        try loadIfNeeded()
        return Array(records.values).sorted { $0.createdAt < $1.createdAt }
    }

    /// Clears in-memory and on-disk state. Exposed for tests.
    func reset() throws {
        try loadIfNeeded()
        try commit([:])
        pairingGeneration = UUID()
    }

    // MARK: - Persistence

    /// Eager startup validation: unreadable/corrupt token storage must prevent listener creation.
    func prepare() throws { try loadIfNeeded() }

    @discardableResult
    func revokeAll() throws -> [String] {
        try loadIfNeeded()
        let ids = Array(records.keys)
        try commit([:])
        pairingGeneration = UUID()
        return ids
    }

    private func loadIfNeeded() throws {
        guard !failed else { throw TokenStoreError.storageUnavailable }
        guard !loaded else { return }
        do {
            let data = try keychain.load(account: account)
            guard data.count <= 32_768 else { throw TokenStoreError.invalidRecord }
            let decoded = try JSONDecoder().decode([Record].self, from: data)
            guard decoded.count <= 16 else { throw TokenStoreError.invalidRecord }
            var next: [String: Record] = [:]
            var hashes = Set<String>()
            for record in decoded {
                guard UUID(uuidString: record.id) != nil,
                      record.tokenHash.count == 64,
                      record.tokenHash.utf8.allSatisfy({ (48...57).contains($0) || (97...102).contains($0) }),
                      Self.isValidLabel(record.deviceLabel),
                      record.createdAt.timeIntervalSince1970.isFinite,
                      record.lastSeenAt.timeIntervalSince1970.isFinite,
                      next[record.id] == nil, hashes.insert(record.tokenHash).inserted else {
                    throw TokenStoreError.invalidRecord
                }
                next[record.id] = record
            }
            authorization.publish(Set(next.keys))
            records = next
            loaded = true
        } catch KeychainError.notFound {
            authorization.publish([])
            records = [:]
            loaded = true
        } catch {
            authorization.publish([])
            failed = true
            throw error
        }
    }

    private func commit(_ next: [String: Record]) throws {
        do {
            let sorted = next.values.sorted { $0.createdAt < $1.createdAt }
            let data = try JSONEncoder().encode(sorted)
            try keychain.save(data, account: account)
            authorization.publish(Set(next.keys))
            records = next
        } catch {
            // Never authenticate an in-memory mutation that failed to persist.
            authorization.publish([])
            failed = true
            throw error
        }
    }

    private static func isValidLabel(_ label: String) -> Bool {
        !label.isEmpty && label.utf8.count <= 128 && !label.unicodeScalars.contains { $0.value < 32 || $0.value == 127 }
    }

    private static func isValidToken(_ token: String) -> Bool {
        guard token.utf8.count == 44, let bytes = Data(base64Encoded: token), bytes.count == 32 else { return false }
        return bytes.base64EncodedString() == token
    }

    // MARK: - Crypto helpers

    static func hashHex(_ tokenPlain: String) -> String {
        let digest = SHA256.hash(data: Data(tokenPlain.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    static func randomTokenBytes(count: Int = 32) throws -> Data {
        var buffer = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &buffer)
        guard status == errSecSuccess else {
            throw KeychainError.randomGenerationFailed(status)
        }
        return Data(buffer)
    }
}
