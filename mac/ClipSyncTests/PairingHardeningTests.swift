import XCTest
import Foundation
@testable import ClipSync

final class PairingHardeningTests: XCTestCase {
    private let key = Data(repeating: 7, count: 32)
    func testQRContainsPinnedIdentityAndSingleUseHighEntropySecret() async throws {
        let manager = PairingManager(secret: key)
        let session = try await manager.startPairing()
        XCTAssertEqual(session.qrSecret.utf8.count, 43)
        let url = try XCTUnwrap(URLComponents(string: session.url(hostname: "test mac.local", port: 7010, fingerprint: "A".repeatCount(43))))
        let query = Dictionary(uniqueKeysWithValues: try XCTUnwrap(url.queryItems).map { ($0.name, $0.value ?? "") })
        XCTAssertEqual(query["v"], "2")
        XCTAssertEqual(query["fp"], "A".repeatCount(43))
        XCTAssertEqual(query["secret"], session.qrSecret)
        XCTAssertNil(query["code"])
        let response = try await manager.consume(secret: session.qrSecret)
        XCTAssertEqual(Data(base64Encoded: response.token)?.count, 32)
        do { _ = try await manager.consume(code: session.code); XCTFail("QR and manual code share one-use session") }
        catch PairingError.consumed {}
    }
    func testClosingWindowCancelsSecretAndRacingRefresh() async throws {
        let manager = PairingManager(secret: key)
        let session = try await manager.startPairing()
        await manager.cancel(sessionID: session.id)
        do { _ = try await manager.refreshPairing(sessionID: session.id); XCTFail("Closed window must not reopen session") }
        catch PairingError.notStarted {}
        do { _ = try await manager.consume(secret: session.qrSecret); XCTFail("Closed window secret must fail") }
        catch PairingError.notStarted {}
    }
    func testOldWindowCloseCannotCancelReplacementAndRefreshRotatesCredentials() async throws {
        let manager = PairingManager(secret: key)
        let old = try await manager.startPairing()
        let next = try await manager.startPairing()
        await manager.cancel(sessionID: old.id)
        let refreshed = try await manager.refreshPairing(sessionID: next.id)
        XCTAssertEqual(refreshed.id, next.id)
        XCTAssertNotEqual(refreshed.qrSecret, next.qrSecret)
        do { _ = try await manager.consume(secret: next.qrSecret); XCTFail("Refresh invalidates old QR") }
        catch PairingError.invalid {}
        _ = try await manager.consume(secret: refreshed.qrSecret)
    }
    func testSessionBudgetStopsDistributedCodeGuessing() async throws {
        let manager = PairingManager(secret: key)
        let session = try await manager.startPairing()
        for _ in 0..<10 {
            do { _ = try await manager.consume(code: "not-a-valid-code"); XCTFail("Invalid attempt accepted") }
            catch PairingError.invalid {}
        }
        do { _ = try await manager.consume(secret: session.qrSecret); XCTFail("Session budget exhausted") }
        catch PairingError.rateLimited {}
    }
    func testExpiryBoundaryAndClockRollbackRejectSecret() async throws {
        let clock = PairingManagerTests.MutableClock(Date(timeIntervalSince1970: 1000))
        let manager = PairingManager(secret: key, ttl: 60, clock: clock)
        let session = try await manager.startPairing()
        clock.advance(by: 60)
        do { _ = try await manager.consume(secret: session.qrSecret); XCTFail("Expiry boundary accepted") }
        catch PairingError.expired {}
        let new = try await manager.startPairing()
        clock.advance(by: -1)
        do { _ = try await manager.consume(secret: new.qrSecret); XCTFail("Clock rollback accepted") }
        catch PairingError.expired {}
    }
}

private extension String { func repeatCount(_ count: Int) -> String { String(repeating: self, count: count) } }
