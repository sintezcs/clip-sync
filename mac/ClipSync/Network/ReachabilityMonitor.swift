import Foundation
import Logging
import Network

protocol ReachabilityPathMonitoring: AnyObject {
    var onUpdate: ((Set<String>, Bool) -> Void)? { get set }
    func start(queue: DispatchQueue)
    func cancel()
}

final class SystemReachabilityPathMonitor: ReachabilityPathMonitoring {
    var onUpdate: ((Set<String>, Bool) -> Void)?
    private let monitor = NWPathMonitor()
    func start(queue: DispatchQueue) {
        monitor.pathUpdateHandler = { [weak self] path in
            self?.onUpdate?(ReachabilityMonitor.interfaceNames(from: path), path.status == .satisfied)
        }
        monitor.start(queue: queue)
    }
    func cancel() {
        monitor.pathUpdateHandler = nil
        onUpdate = nil
        monitor.cancel()
    }
}

/// Re-announces only while the current monitoring session remains active.
/// Lifecycle and advertisement operations are serialized on the main queue.
final class ReachabilityMonitor {
    private let queue: DispatchQueue
    private let advertiser: BonjourAdvertiser
    private let makeMonitor: () -> ReachabilityPathMonitoring
    private let scheduleRestart: (DispatchWorkItem) -> Void
    private var monitor: ReachabilityPathMonitoring?
    private var pendingRestart: DispatchWorkItem?
    private var sessionID: UUID?
    private var restartID: UUID?
    private var lastInterfaceNames: Set<String> = []
    private var lastSatisfied = false
    private var logger: Logger

    /// Delivered on the main queue for an active session only.
    var onNetworkChange: (() -> Void)?

    init(
        advertiser: BonjourAdvertiser,
        queue: DispatchQueue = DispatchQueue(label: "clipsync.reachability", qos: .utility),
        logger: Logger = Logger(label: "clipsync.reachability"),
        makeMonitor: @escaping () -> ReachabilityPathMonitoring = { SystemReachabilityPathMonitor() },
        scheduleRestart: @escaping (DispatchWorkItem) -> Void = {
            DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(500), execute: $0)
        }
    ) {
        self.advertiser = advertiser
        self.queue = queue
        self.logger = logger
        self.makeMonitor = makeMonitor
        self.scheduleRestart = scheduleRestart
    }

    func start() {
        onMain {
            guard self.sessionID == nil else { return }
            let identity = UUID()
            self.sessionID = identity
            self.lastInterfaceNames = []
            self.lastSatisfied = false
            let monitor = self.makeMonitor()
            self.monitor = monitor
            monitor.onUpdate = { [weak self] names, satisfied in
                // Never wait on main from NWPathMonitor's callback queue.
                let deliver: () -> Void = { [weak self] in
                    self?.handleUpdate(names: names, satisfied: satisfied, session: identity)
                }
                if Thread.isMainThread { deliver() }
                else { DispatchQueue.main.async(execute: deliver) }
            }
            monitor.start(queue: self.queue)
            self.logger.info("ReachabilityMonitor started")
        }
    }

    func stop() {
        onMain {
            // Invalidate first: queued path callbacks and delayed restarts become inert.
            self.sessionID = nil
            self.cancelRestart()
            self.monitor?.onUpdate = nil
            self.monitor?.cancel()
            self.monitor = nil
            self.advertiser.stop()
            self.logger.info("ReachabilityMonitor stopped")
        }
    }

    static func interfaceNames(from path: NWPath) -> Set<String> {
        Set(path.availableInterfaces.map(\.name))
    }

    private func handleUpdate(names: Set<String>, satisfied: Bool, session: UUID) {
        guard sessionID == session else { return }
        guard names != lastInterfaceNames || satisfied != lastSatisfied else { return }
        lastInterfaceNames = names
        lastSatisfied = satisfied
        cancelRestart()
        advertiser.stop()
        guard satisfied else { return }
        let restart = UUID()
        restartID = restart
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.sessionID == session, self.restartID == restart else { return }
            self.pendingRestart = nil
            self.restartID = nil
            self.advertiser.start()
        }
        pendingRestart = work
        scheduleRestart(work)
        onNetworkChange?()
    }

    private func cancelRestart() {
        restartID = nil
        pendingRestart?.cancel()
        pendingRestart = nil
    }

    private func onMain(_ operation: () -> Void) {
        if Thread.isMainThread { operation() }
        else { DispatchQueue.main.sync(execute: operation) }
    }
}
