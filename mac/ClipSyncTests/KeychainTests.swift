import XCTest
@testable import ClipSync

final class KeychainTests: XCTestCase {
    private var service: String = ""
    private var keychain: Keychain!

    override func setUp() {
        super.setUp()
        service = "com.clipsync.tests.\(UUID().uuidString)"
        keychain = Keychain(service: service)
    }

    override func tearDown() {
        try? keychain.delete()
        super.tearDown()
    }

    func testSaveLoadDeleteRoundtrip() throws {
        let payload = Data([0xDE, 0xAD, 0xBE, 0xEF])
        do {
            try keychain.save(payload)
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("Keychain access unavailable in this test environment (status=\(status))")
        }

        let loaded = try keychain.load()
        XCTAssertEqual(loaded, payload)

        let updated = Data([0xCA, 0xFE])
        try keychain.save(updated)
        XCTAssertEqual(try keychain.load(), updated)

        try keychain.delete()
        XCTAssertThrowsError(try keychain.load()) { error in
            XCTAssertEqual(error as? KeychainError, .notFound)
        }
    }

    func testInvalidStoredSecretIsNotReplaced() throws {
        do { try keychain.save(Data([1])) }
        catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("Keychain access unavailable (status=\(status))")
        }
        XCTAssertThrowsError(try keychain.loadOrCreateSecret()) { error in
            XCTAssertEqual(error as? KeychainError, .invalidSecretLength)
        }
        XCTAssertEqual(try keychain.load(), Data([1]))
    }

    func testLoadOrCreateSecretIsStable() throws {
        let first: Data
        do {
            first = try keychain.loadOrCreateSecret()
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("Keychain access unavailable in this test environment (status=\(status))")
        }
        XCTAssertEqual(first.count, 32)
        let second = try keychain.loadOrCreateSecret()
        XCTAssertEqual(first, second)
    }
}

/// In-memory injection never accesses the user's Keychain.
final class TestKeychainStorage: KeychainStorage, @unchecked Sendable {
    var values: [String: Data] = [:]
    var loadFailure: Error?
    var saveFailure: Error?
    var deleteFailure: Error?
    var saves = 0
    func load(account: String) throws -> Data {
        if let loadFailure { throw loadFailure }
        guard let value = values[account] else { throw KeychainError.notFound }
        return value
    }
    func save(_ data: Data, account: String) throws {
        saves += 1
        if let saveFailure { throw saveFailure }
        values[account] = data
    }
    func delete(account: String) throws {
        if let deleteFailure { throw deleteFailure }
        values.removeValue(forKey: account)
    }
}

final class SecureStartupTests: XCTestCase {
    enum Failure: Error { case injected }

    @MainActor func testEverySecurityFailurePreventsAllPipelineEffects() async {
        for failureStage in 0...2 {
            var listenerStarts = 0
            var watcherStarts = 0
            var advertisements = 0
            do {
                try await SecureStartup.run(loadSecret: {
                    if failureStage == 0 { throw Failure.injected }
                    return Data(repeating: 7, count: 32)
                }, prepareIdentity: {
                    if failureStage == 1 { throw Failure.injected }
                    return "synthetic-identity"
                }, prepareTokens: {
                    if failureStage == 2 { throw Failure.injected }
                }, start: { _, _ in
                    listenerStarts += 1; watcherStarts += 1; advertisements += 1
                })
                XCTFail("Startup should fail")
            } catch { }
            XCTAssertEqual(listenerStarts, 0)
            XCTAssertEqual(watcherStarts, 0)
            XCTAssertEqual(advertisements, 0)
        }
    }

    @MainActor func testInvalidSecretCannotStartPipeline() async {
        for size in [0, 1, 31, 33, 100] {
            var started = false
            do {
                try await SecureStartup.run(loadSecret: { Data(count: size) }, prepareIdentity: { "test" },
                    prepareTokens: {}, start: { _, _ in started = true })
                XCTFail("Invalid secret accepted")
            } catch { }
            XCTAssertFalse(started)
        }
    }

    @MainActor func testValidSecurityStateStartsExactlyOnce() async throws {
        var starts = 0
        try await SecureStartup.run(loadSecret: { Data(repeating: 7, count: 32) }, prepareIdentity: { "test" },
            prepareTokens: {}, start: { secret, identity in
                XCTAssertEqual(secret.count, 32)
                XCTAssertEqual(identity, "test")
                starts += 1
            })
        XCTAssertEqual(starts, 1)
    }

    @MainActor func testTestHostIsRecognizedBeforeAppStartup() {
        XCTAssertTrue(TestHost.isRunning)
    }
}
