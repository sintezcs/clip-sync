import XCTest
import Foundation
@testable import ClipSync

final class RateLimiterTests: XCTestCase {
    func testBoundedKeyTableDoesNotEvictLiveBudgets() async {
        let limiter = RateLimiter(maxKeys: 2)
        let a = await limiter.allow(key: "a", maxRequests: 1, windowSeconds: 60)
        let b = await limiter.allow(key: "b", maxRequests: 1, windowSeconds: 60)
        let c = await limiter.allow(key: "c", maxRequests: 1, windowSeconds: 60)
        let replayA = await limiter.allow(key: "a", maxRequests: 1, windowSeconds: 60)
        XCTAssertTrue(a); XCTAssertTrue(b); XCTAssertFalse(c); XCTAssertFalse(replayA)
    }
    func testExpiredBucketsReleaseCapacity() async {
        let clock = PairingManagerTests.MutableClock(Date(timeIntervalSince1970: 1000))
        let limiter = RateLimiter(maxKeys: 1, clock: { clock.now() })
        let first = await limiter.allow(key: "first", maxRequests: 1, windowSeconds: 60)
        XCTAssertTrue(first)
        clock.advance(by: 60)
        let second = await limiter.allow(key: "second", maxRequests: 1, windowSeconds: 60)
        XCTAssertTrue(second)
    }
    func testGlobalBudgetCannotBeBypassedWithDifferentPeerKeys() async {
        let limiter = RateLimiter()
        for _ in 0..<3 { _ = await limiter.allow(key: "/pair:global", maxRequests: 3, windowSeconds: 60) }
        let allowed = await limiter.allow(key: "/pair:global", maxRequests: 3, windowSeconds: 60)
        XCTAssertFalse(allowed)
    }
}
