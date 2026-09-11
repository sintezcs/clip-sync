import XCTest
@testable import ClipSync

final class TokenStoreTests: XCTestCase {
    private var service: String = ""
    private var keychain: Keychain!
    private var store: TokenStore!

    override func setUp() {
        super.setUp()
        service = "com.clipsync.tests.token.\(UUID().uuidString)"
        keychain = Keychain(service: service)
        store = TokenStore(keychain: keychain, account: "tokens")
    }

    override func tearDown() {
        try? keychain.delete(account: "tokens")
        super.tearDown()
    }

    private func skipIfNoKeychain(_ block: () async throws -> Void) async throws {
        do {
            try await block()
        } catch KeychainError.unexpectedStatus(let status) where status == errSecMissingEntitlement {
            throw XCTSkip("Keychain access unavailable (status=\(status))")
        }
    }

    func testIssueValidateTouchRoundtrip() async throws {
        try await skipIfNoKeychain {
            let (id, plain) = try await store.issue(deviceLabel: "pixel-7")
            XCTAssertFalse(id.isEmpty)
            XCTAssertFalse(plain.isEmpty)

            let record = try await store.validate(tokenPlain: plain)
            XCTAssertNotNil(record)
            XCTAssertEqual(record?.id, id)
            XCTAssertEqual(record?.deviceLabel, "pixel-7")

            let before = record!.lastSeenAt
            try await Task.sleep(nanoseconds: 10_000_000)
            try await store.touch(id: id, at: Date())
            let list = try await store.list()
            XCTAssertEqual(list.count, 1)
            XCTAssertGreaterThanOrEqual(list[0].lastSeenAt, before)
        }
    }

    func testRevokeRemovesToken() async throws {
        try await skipIfNoKeychain {
            let (id, plain) = try await store.issue(deviceLabel: "pixel-7")
            try await store.revoke(id: id)
            let record = try await store.validate(tokenPlain: plain)
            XCTAssertNil(record)
        }
    }

    func testValidateUnknownTokenReturnsNil() async throws {
        try await skipIfNoKeychain {
            let record = try await store.validate(tokenPlain: "not-a-real-token")
            XCTAssertNil(record)
        }
    }

    func testRegisterStoresHashNotPlaintext() async throws {
        try await skipIfNoKeychain {
            let plain = Data(repeating: 11, count: 32).base64EncodedString()
            let rec = try await store.register(tokenPlain: plain, deviceLabel: "phone")
            XCTAssertNotEqual(rec.tokenHash, plain)
            XCTAssertEqual(rec.tokenHash, TokenStore.hashHex(plain))
            let found = try await store.validate(tokenPlain: plain)
            XCTAssertEqual(found?.id, rec.id)
        }
    }

    func testPersistenceAcrossInstances() async throws {
        try await skipIfNoKeychain {
            let (_, plain) = try await store.issue(deviceLabel: "device")
            let store2 = TokenStore(keychain: keychain, account: "tokens")
            let record = try await store2.validate(tokenPlain: plain)
            XCTAssertNotNil(record)
        }
    }
}


final class TokenStoreFailureTests: XCTestCase {
    func testCorruptStoragePreventsStartupAndIsNotOverwritten() async throws {
        let storage = TestKeychainStorage()
        storage.values["tokens"] = Data("not json".utf8)
        let store = TokenStore(keychain: storage)
        do { try await store.prepare(); XCTFail("Corrupt store accepted") } catch { }
        do { _ = try await store.issue(deviceLabel: "test"); XCTFail("Failed store issued token") } catch { }
        XCTAssertEqual(storage.saves, 0)
        XCTAssertEqual(storage.values["tokens"], Data("not json".utf8))
    }

    func testFailedRegisterNeverAuthenticatesIssuedToken() async throws {
        let storage = TestKeychainStorage()
        storage.saveFailure = KeychainError.unexpectedStatus(-1)
        let store = TokenStore(keychain: storage)
        let token = Data(repeating: 9, count: 32).base64EncodedString()
        do { _ = try await store.register(tokenPlain: token, deviceLabel: "test"); XCTFail("Write failure ignored") } catch { }
        do { _ = try await store.validate(tokenPlain: token); XCTFail("Failed store authenticated") } catch { }
        XCTAssertNil(storage.values["tokens"])
    }

    func testFailedRevokeLeavesDiskUnchangedAndFailsClosed() async throws {
        let storage = TestKeychainStorage()
        let store = TokenStore(keychain: storage)
        let (id, token) = try await store.issue(deviceLabel: "test")
        let saved = storage.values["tokens"]
        storage.saveFailure = KeychainError.unexpectedStatus(-1)
        do { try await store.revoke(id: id); XCTFail("Write failure ignored") } catch { }
        XCTAssertEqual(storage.values["tokens"], saved)
        do { _ = try await store.validate(tokenPlain: token); XCTFail("Failed store authenticated") } catch { }
    }

    func testRevokeAllPersistsBeforeNewInstanceLoads() async throws {
        let storage = TestKeychainStorage()
        let store = TokenStore(keychain: storage)
        let (id, token) = try await store.issue(deviceLabel: "test")
        let revoked = try await store.revokeAll()
        XCTAssertEqual(revoked, [id])
        let reloaded = TokenStore(keychain: storage)
        let found = try await reloaded.validate(tokenPlain: token)
        XCTAssertNil(found)
    }

    func testDuplicateRecordIDsFailWithoutDictionaryTrap() async throws {
        let storage = TestKeychainStorage()
        let record = TokenStore.Record(id: UUID().uuidString, tokenHash: String(repeating: "a", count: 64),
            createdAt: Date(), lastSeenAt: Date(), deviceLabel: "test")
        storage.values["tokens"] = try JSONEncoder().encode([record, record])
        do { try await TokenStore(keychain: storage).prepare(); XCTFail("Duplicate accepted") } catch { }
    }
}
