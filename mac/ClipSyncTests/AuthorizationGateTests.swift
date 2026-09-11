import Foundation
import XCTest
@testable import ClipSync

final class AuthorizationGateTests: XCTestCase {
    private final class Keys: KeychainStorage, @unchecked Sendable {
        var data: Data?
        var failSaves = false
        func load(account: String) throws -> Data {
            guard let data else { throw KeychainError.notFound }; return data
        }
        func save(_ data: Data, account: String) throws {
            guard !failSaves else { throw TokenStoreError.storageUnavailable }; self.data = data
        }
        func delete(account: String) throws { data = nil }
    }

    func testValidatedQueuedWriteCannotRunAfterCompletedRevocation() async throws {
        let store = TokenStore(keychain: Keys())
        let token = Data(repeating: 3, count: 32).base64EncodedString()
        let record = try await store.register(tokenPlain: token, deviceLabel: "test")
        let validated = try await store.validate(tokenPlain: token)
        XCTAssertEqual(validated?.id, record.id)
        let queue = DispatchQueue(label: "authorization.queued-effect")
        queue.suspend()
        let rejected = expectation(description: "Queued effect rejected")
        queue.async {
            do {
                try store.withAuthorization(id: record.id) { XCTFail("Revoked queued write executed") }
                XCTFail("Revoked operation accepted")
            } catch TokenStoreError.unauthorized { }
            catch { XCTFail("Unexpected error: \(error)") }
            rejected.fulfill()
        }
        do { try await store.revoke(id: record.id) }
        catch { queue.resume(); throw error }
        queue.resume()
        await fulfillment(of: [rejected], timeout: 2)
    }

    func testPersistenceFailureClearsAuthorizationAndReloadPublishesPersistedIDs() async throws {
        let keys = Keys()
        let store = TokenStore(keychain: keys)
        let record = try await store.register(tokenPlain: Data(repeating: 4, count: 32).base64EncodedString(), deviceLabel: "test")
        XCTAssertEqual(try store.withAuthorization(id: record.id) { 42 }, 42)
        let restored = TokenStore(keychain: keys)
        XCTAssertThrowsError(try restored.withAuthorization(id: record.id) { 42 })
        try await restored.prepare()
        XCTAssertEqual(try restored.withAuthorization(id: record.id) { 42 }, 42)
        keys.failSaves = true
        do { try await store.revoke(id: record.id); XCTFail("Expected persistence failure") }
        catch { }
        XCTAssertThrowsError(try store.withAuthorization(id: record.id) { XCTFail("Failed store remained authorized") })
    }

    func testConsumedPairingCannotRegisterAfterCompletedRevokeAll() async throws {
        let store = TokenStore(keychain: Keys())
        let pairing = PairingManager(secret: Data(repeating: 8, count: 32))
        let session = try await pairing.startPairing()
        let generation = try await store.issuanceGeneration()
        let response = try await pairing.consume(secret: session.qrSecret)
        // Deterministic interleaving at the route's actor suspension between consume and register.
        await pairing.cancel()
        try await store.revokeAll()
        do {
            try await store.register(tokenPlain: response.token, deviceLabel: "test", issuanceGeneration: generation)
            XCTFail("An in-flight pairing survived completed revocation")
        } catch TokenStoreError.unauthorized { }
        let records = try await store.list()
        XCTAssertTrue(records.isEmpty)

        // A separately authorized new session can pair after revocation.
        let freshSession = try await pairing.startPairing()
        let freshGeneration = try await store.issuanceGeneration()
        let fresh = try await pairing.consume(secret: freshSession.qrSecret)
        let record = try await store.register(tokenPlain: fresh.token, deviceLabel: "test", issuanceGeneration: freshGeneration)
        XCTAssertEqual(try store.withAuthorization(id: record.id) { 1 }, 1)
    }

    func testFailedRevokeCannotBeFollowedByPendingPairRegistration() async throws {
        let keys = Keys()
        let store = TokenStore(keychain: keys)
        let generation = try await store.issuanceGeneration()
        keys.failSaves = true
        do { try await store.revokeAll(); XCTFail("Expected storage failure") }
        catch { }
        keys.failSaves = false
        do {
            try await store.register(tokenPlain: Data(repeating: 6, count: 32).base64EncodedString(),
                                     deviceLabel: "test", issuanceGeneration: generation)
            XCTFail("Failed revocation must latch authorization closed")
        } catch TokenStoreError.storageUnavailable { }
    }

    func testBoundedGateRejectsOversizedPublication() throws {
        let gate = AuthorizationGate()
        gate.publish(["one"])
        XCTAssertEqual(try gate.withAuthorization(id: "one") { 1 }, 1)
        gate.publish(Set((0...16).map(String.init)))
        XCTAssertThrowsError(try gate.withAuthorization(id: "0") { 1 })
    }
}
