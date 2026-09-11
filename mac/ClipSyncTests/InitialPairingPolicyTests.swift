import XCTest
@testable import ClipSync

final class InitialPairingPolicyTests: XCTestCase {
    func testFirstUnpairedReadinessOffersSetupOnlyOnce() {
        var policy = InitialPairingPolicy()
        XCTAssertTrue(policy.listenerReady(pairedDeviceCount: 0))
        // Dismissal and repeated listener reconnects do not reset the process policy.
        for _ in 0..<10 { XCTAssertFalse(policy.listenerReady(pairedDeviceCount: 0)) }
    }

    func testPairedStartupRemainsQuietEvenIfDevicesAreLaterRemoved() {
        var policy = InitialPairingPolicy()
        XCTAssertFalse(policy.listenerReady(pairedDeviceCount: 1))
        XCTAssertFalse(policy.listenerReady(pairedDeviceCount: 0))
    }

    func testUnknownStorageDoesNotOpenOrRetrySetup() {
        var policy = InitialPairingPolicy()
        XCTAssertFalse(policy.listenerReady(pairedDeviceCount: nil))
        XCTAssertFalse(policy.listenerReady(pairedDeviceCount: 0))
    }
}

final class PairingPresentationPolicyTests: XCTestCase {
    func testSuspendedPresentationCannotResumeAfterStopRevokeOrDismiss() {
        for _ in 0..<3 {
            var policy = PairingPresentationPolicy()
            let suspended = policy.begin()
            XCTAssertTrue(policy.permits(suspended, listenerReady: true))
            policy.invalidate()
            XCTAssertFalse(policy.permits(suspended, listenerReady: true))
            let new = policy.begin()
            XCTAssertNotEqual(new, suspended)
            XCTAssertFalse(policy.permits(suspended, listenerReady: true))
            XCTAssertTrue(policy.permits(new, listenerReady: true))
        }
    }

    func testLostReadinessBlocksPresentationAndRefresh() {
        var policy = PairingPresentationPolicy()
        let generation = policy.begin()
        XCTAssertFalse(policy.permits(generation, listenerReady: false))
    }

    func testFocusKeepsCallbacksButReplacementRejectsOldCloseCallback() {
        var policy = PairingPresentationPolicy()
        let old = policy.begin()
        XCTAssertEqual(policy.begin(), old)
        let replacement = policy.begin(replacing: true)
        XCTAssertFalse(policy.permits(old, listenerReady: true))
        XCTAssertTrue(policy.permits(replacement, listenerReady: true))
    }
}
