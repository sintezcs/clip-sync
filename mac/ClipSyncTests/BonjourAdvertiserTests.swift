import Foundation
import XCTest
@testable import ClipSync

/// No publish/schedule operation reaches Bonjour or a LAN interface.
final class BonjourAdvertiserTests: XCTestCase {
    private final class FakeService: NetService {
        var publishCount = 0
        var stopCount = 0
        var removalCount = 0
        var publishImmediately = false
        override func schedule(in aRunLoop: RunLoop, forMode mode: RunLoop.Mode) {}
        override func remove(from aRunLoop: RunLoop, forMode mode: RunLoop.Mode) { removalCount += 1 }
        override func publish() {
            publishCount += 1
            if publishImmediately { delegate?.netServiceDidPublish?(self) }
        }
        override func stop() { stopCount += 1 }
    }

    @MainActor
    func testStopInvalidatesPublicationAndIgnoresLateCallbacks() {
        let service = FakeService(domain: "", type: "_clipsync._tcp", name: "synthetic", port: 17010)
        service.publishImmediately = true
        let advertiser = BonjourAdvertiser(port: 17010, serviceName: "synthetic", txtRecord: [:],
                                           makeService: { _, _, _, _ in service })
        var failures = 0
        advertiser.onPublishFailed = { _ in failures += 1 }
        advertiser.start()
        advertiser.start()
        XCTAssertEqual(service.publishCount, 1)
        XCTAssertTrue(advertiser.isPublished)
        advertiser.stop()
        advertiser.stop()
        XCTAssertFalse(advertiser.isPublished)
        XCTAssertNil(service.delegate)
        XCTAssertEqual(service.stopCount, 1)
        XCTAssertEqual(service.removalCount, 1)
        advertiser.netServiceDidPublish(service)
        advertiser.netService(service, didNotPublish: [NetService.errorCode: -72008])
        XCTAssertFalse(advertiser.isPublished)
        XCTAssertEqual(failures, 0)
    }

    @MainActor
    func testRestartRejectsPreviousServiceCallbacksAndFailureAllowsRetry() {
        var services: [FakeService] = []
        let advertiser = BonjourAdvertiser(port: 17010, serviceName: "synthetic", txtRecord: [:],
                                           makeService: { domain, type, name, port in
            let service = FakeService(domain: domain, type: type, name: name, port: port)
            services.append(service)
            return service
        })
        var failures = 0
        advertiser.onPublishFailed = { _ in failures += 1 }
        advertiser.start()
        let old = services[0]
        advertiser.stop()
        advertiser.start()
        let current = services[1]
        advertiser.netServiceDidPublish(old)
        XCTAssertFalse(advertiser.isPublished)
        advertiser.netServiceDidPublish(current)
        XCTAssertTrue(advertiser.isPublished)
        advertiser.netService(old, didNotPublish: [NetService.errorCode: -72008])
        XCTAssertTrue(advertiser.isPublished)
        XCTAssertEqual(failures, 0)
        advertiser.netService(current, didNotPublish: [NetService.errorCode: -72008])
        XCTAssertFalse(advertiser.isPublished)
        XCTAssertEqual(failures, 1)
        XCTAssertNil(current.delegate)
        XCTAssertEqual(current.stopCount, 1)
        advertiser.netServiceDidPublish(current)
        XCTAssertFalse(advertiser.isPublished)
        advertiser.start()
        XCTAssertEqual(services.count, 3)
        advertiser.netServiceDidPublish(services[2])
        XCTAssertTrue(advertiser.isPublished)
        advertiser.stop()
    }
}
