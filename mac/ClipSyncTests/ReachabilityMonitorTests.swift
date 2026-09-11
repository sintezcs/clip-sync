import Foundation
import XCTest
@testable import ClipSync

final class ReachabilityMonitorTests: XCTestCase {
    private final class FakeMonitor: ReachabilityPathMonitoring {
        var onUpdate: ((Set<String>, Bool) -> Void)?
        var starts = 0
        var cancellations = 0
        func start(queue: DispatchQueue) { starts += 1 }
        func cancel() { cancellations += 1 }
    }
    private final class FakeService: NetService {
        var publishes = 0
        override func schedule(in aRunLoop: RunLoop, forMode mode: RunLoop.Mode) {}
        override func remove(from aRunLoop: RunLoop, forMode mode: RunLoop.Mode) {}
        override func publish() { publishes += 1 }
        override func stop() {}
    }

    @MainActor
    func testStoppedSessionCannotPublishFromPendingWorkOrLatePathCallback() {
        let service = FakeService(domain: "", type: "_clipsync._tcp", name: "synthetic", port: 17010)
        let advertiser = BonjourAdvertiser(port: 17010, serviceName: "synthetic", txtRecord: [:],
                                           makeService: { _, _, _, _ in service })
        let monitor = FakeMonitor()
        var scheduled: [DispatchWorkItem] = []
        let sut = ReachabilityMonitor(advertiser: advertiser, makeMonitor: { monitor },
                                      scheduleRestart: { scheduled.append($0) })
        var changes = 0
        sut.onNetworkChange = { changes += 1 }
        sut.start()
        sut.start()
        XCTAssertEqual(monitor.starts, 1)
        let lateCallback = monitor.onUpdate!
        lateCallback(["en0"], true)
        XCTAssertEqual(scheduled.count, 1)
        sut.stop()
        sut.stop()
        XCTAssertEqual(monitor.cancellations, 1)
        XCTAssertNil(monitor.onUpdate)
        XCTAssertTrue(scheduled[0].isCancelled)
        scheduled[0].perform()
        lateCallback(["en1"], true)
        XCTAssertEqual(scheduled.count, 1)
        XCTAssertEqual(changes, 1)
        XCTAssertEqual(service.publishes, 0)
    }

    @MainActor
    func testNewSessionAndLatestPathAreTheOnlyPermittedRestart() {
        var services: [FakeService] = []
        let advertiser = BonjourAdvertiser(port: 17010, serviceName: "synthetic", txtRecord: [:],
                                           makeService: { domain, type, name, port in
            let service = FakeService(domain: domain, type: type, name: name, port: port)
            services.append(service)
            return service
        })
        var monitors: [FakeMonitor] = []
        var scheduled: [DispatchWorkItem] = []
        let sut = ReachabilityMonitor(advertiser: advertiser, makeMonitor: {
            let monitor = FakeMonitor()
            monitors.append(monitor)
            return monitor
        }, scheduleRestart: { scheduled.append($0) })
        sut.start()
        let oldCallback = monitors[0].onUpdate!
        oldCallback(["en0"], true)
        sut.stop()
        sut.start()
        XCTAssertEqual(monitors.count, 2)
        oldCallback(["en1"], true)
        XCTAssertEqual(scheduled.count, 1)
        let currentCallback = monitors[1].onUpdate!
        currentCallback(["en0"], true)
        currentCallback(["en0"], false)
        XCTAssertTrue(scheduled[1].isCancelled)
        scheduled[1].perform()
        XCTAssertTrue(services.isEmpty)
        // Recovery with the same interface names must still re-announce.
        currentCallback(["en0"], true)
        currentCallback(["en0"], true)
        XCTAssertEqual(scheduled.count, 3)
        scheduled[2].perform()
        XCTAssertEqual(services.count, 1)
        XCTAssertEqual(services[0].publishes, 1)
        sut.stop()
        XCTAssertFalse(advertiser.isPublished)
    }
}
