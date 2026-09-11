import AppKit
import Foundation
import Logging
import NIOSSL
import SwiftUI
import UserNotifications

@main
struct ClipSyncApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate

    var body: some Scene {
        Settings {
            EmptyView()
        }
    }
}

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private lazy var hub = WebSocketHub(errorStore: errorStore)
    private lazy var watcher = PasteboardWatcher()
    private lazy var injector = PasteboardInjector(watcher: watcher)
    private let keychain = Keychain()
    private var pairingSecret: Data = Data()
    private lazy var pairing = PairingManager(secret: pairingSecret)
    private let tokenStore = TokenStore()
    private let tlsManager = TLSManager()
    private lazy var hmacValidator = HMACValidator(secret: pairingSecret)
    private var server: ClipServer?
    private var listenerReady = false
    private var initialPairingPolicy = InitialPairingPolicy()
    private var pairingPresentationPolicy = PairingPresentationPolicy()
    private var pairingPresentationTask: Task<Void, Never>?
    private var pairingCancellationTask: Task<Void, Never>?
    private var revokingPairing = false
    private var presentedPairingSessionID: String?
    private var syncTransition = false
    let errorStore = ErrorStore()
    private lazy var menuBar = MenuBarController(
        hub: hub,
        errorStore: errorStore,
        onStartPairing: { [weak self] in self?.startPairing() },
        onTailscale: { [weak self] in self?.showTailscale() },
        onToggleSync: { [weak self] in self?.toggleSync() },
        onRevokeDevices: { [weak self] in self?.revokePairedDevices() },
        onQuit: { NSApp.terminate(nil) }
    )
    private var advertiser: BonjourAdvertiser?
    private var reachabilityMonitor: ReachabilityMonitor?
    private let pairingWindow = PairingWindowController()
    private let tailscaleWindow = TailscaleWindowController()
    private var startupTask: Task<Void, Never>?
    private var broadcastTask: Task<Void, Never>?
    private var serverTask: Task<Void, Never>?
    private var logger = Logger(label: "clipsync.app")

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Hosted XCTest launches this application. Check before clipboard, UI, Keychain or network access.
        guard !TestHost.isRunning else { return }
        NSApp.setActivationPolicy(.accessory)
        menuBar.install()
        startupTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                try await SecureStartup.run(loadSecret: {
                    try self.keychain.loadOrCreateSecret()
                }, prepareIdentity: {
                    try self.tlsManager.loadOrCreate()
                    return try self.tlsManager.makeServerTLSConfiguration()
                }, prepareTokens: {
                    try await self.tokenStore.prepare()
                }, start: { secret, tlsConfiguration in
                    self.pairingSecret = secret
                    self.hmacValidator = HMACValidator(secret: secret)
                    self.server = ClipServer(
                        hub: self.hub, injector: self.injector, pairing: self.pairing,
                        tokenStore: self.tokenStore, hmacValidator: self.hmacValidator,
                        tlsConfiguration: tlsConfiguration, errorStore: self.errorStore,
                        onReady: { [weak self] in await self?.serverBecameReady() })
                    self.startPipeline()
                })
            } catch is CancellationError {
                return
            } catch {
                self.logger.error("Secure startup blocked: \(error)")
                self.errorStore.append(AppError(
                    severity: .error, summary: "Secure startup blocked",
                    detail: "ClipSync could not load a valid identity and credentials. Sync has not started.",
                    suggestion: "Unlock Keychain and restart ClipSync. Restore incomplete identity data before resetting trust."))
            }
        }
    }

    func applicationWillTerminate(_ notification: Notification) {
        guard !TestHost.isRunning else { return }
        startupTask?.cancel()
        stopPipeline()
        menuBar.tearDown()
    }

    private func stopPublication() {
        listenerReady = false
        invalidatePairingPresentation()
        pairingWindow.close()
        broadcastTask?.cancel()
        broadcastTask = nil
        if server != nil { watcher.stop() }
        reachabilityMonitor?.stop()
        reachabilityMonitor = nil
        advertiser?.stop()
        advertiser = nil
    }

    private func stopPipeline() {
        stopPublication()
        serverTask?.cancel()
        server?.stop()
        server = nil
    }

    /// The menu action is explicit authorization to revoke every paired phone.
    func revokePairedDevices() {
        guard !TestHost.isRunning, server != nil, !revokingPairing else { return }
        revokingPairing = true
        invalidatePairingPresentation()
        pairingWindow.close()
        Task { @MainActor [weak self] in
            guard let self else { return }
            defer { self.revokingPairing = false }
            do {
                await self.pairing.cancel()
                let ids = try await self.tokenStore.revokeAll()
                for id in ids { await self.server?.revokeSessions(tokenID: id) }
            } catch {
                self.stopPipeline()
                self.errorStore.append(AppError(severity: .error,
                    summary: "Revocation could not be saved; sync stopped",
                    detail: "Stored credentials have not been confirmed revoked.",
                    suggestion: "Unlock Keychain, restart ClipSync and remove paired devices again."))
            }
        }
    }

    /// Hummingbird calls this only after binding its TLS listener successfully.
    private func serverBecameReady() async {
        guard !TestHost.isRunning, server != nil, !Task.isCancelled else { return }
        listenerReady = true
        if broadcastTask == nil {
            let hub = self.hub
            let stream = watcher.events()
            broadcastTask = Task.detached(priority: .utility) {
                for await payload in stream {
                    guard !Task.isCancelled else { break }
                    await hub.broadcast(payload)
                }
            }
        }
        if !menuBar.isSyncPaused { watcher.start() }
        startAdvertising()
        // This runs only after the actual TLS listener is bound. Consume the first-ready
        // decision even if reading storage fails; reconnects must never reopen onboarding.
        guard !initialPairingPolicy.considered else { return }
        let generation = pairingPresentationPolicy.begin()
        let pairedCount = try? await tokenStore.list().count
        if initialPairingPolicy.listenerReady(pairedDeviceCount: pairedCount),
           pairingPresentationPolicy.permits(generation, listenerReady: listenerReady) {
            startPairing()
        }
    }

    private func startPipeline() {
        serverTask = Task.detached { [weak self] in
            guard let server = await self?.server else { return }
            var retries = 0
            let maxRetries = 3

            while retries < maxRetries {
                do {
                    try await server.run()
                    await self?.stopPublication()
                    // run() returned normally — clean shutdown, stop retrying.
                    break
                } catch {
                    await self?.stopPublication()
                    // Ignore cancellation — this is an intentional shutdown.
                    if Task.isCancelled { break }
                    let description = String(describing: error)
                    let isPortInUse = description.contains("addressInUse")
                        || description.contains("EADDRINUSE")
                        || description.localizedCaseInsensitiveContains("address already in use")
                    let logger = await self?.logger
                    let errorStore = self?.errorStore
                    let port = ServerConfig.defaultPort

                    if isPortInUse {
                        logger?.error("Port \(port) already in use — another ClipSync instance may be running")
                        await MainActor.run {
                            errorStore?.appendAndNotify(AppError(
                                severity: .error,
                                summary: "Port \(port) already in use",
                                detail: "Another ClipSync instance is already running.",
                                suggestion: "Quit the other instance from the menu bar."
                            ))
                        }
                        break  // No point retrying — port won't free itself
                    }

                    retries += 1
                    logger?.error("Server crashed (attempt \(retries)/\(maxRetries)): \(error)")

                    if retries < maxRetries {
                        logger?.info("Restarting server in 5s...")
                        try? await Task.sleep(for: .seconds(5))
                        if Task.isCancelled { break }
                    } else {
                        logger?.error("Server failed after \(maxRetries) attempts, giving up")
                        let detail = error.localizedDescription
                        await MainActor.run {
                            errorStore?.appendAndNotify(AppError(
                                severity: .error,
                                summary: "Server stopped unexpectedly",
                                detail: detail,
                                suggestion: "Restart ClipSync manually."
                            ))
                        }
                    }
                }
            }
        }
    }

    private func startAdvertising() {
        guard advertiser == nil else { return }
        let name = Self.deviceName()
        var txt: [String: String] = [
            "version": "0.1.1",
            "name": name,
        ]
        if !tlsManager.spkiFingerprint.isEmpty {
            txt["fp"] = tlsManager.spkiFingerprint
        }
        let advertiser = BonjourAdvertiser(
            port: Int32(ServerConfig.default.port),
            serviceName: name,
            txtRecord: txt
        )
        advertiser.onPublishFailed = { [weak self] error in
            Task { @MainActor in
                self?.errorStore.append(AppError(
                    severity: .warning,
                    summary: "mDNS advertising failed",
                    detail: error.localizedDescription,
                    suggestion: "Devices on your network may not find this Mac automatically."
                ))
            }
        }
        advertiser.start()
        self.advertiser = advertiser

        let reachability = ReachabilityMonitor(advertiser: advertiser)
        reachability.onNetworkChange = { [weak self] in
            Task { @MainActor in
                self?.logger.info("Network changed — verifying server health")
            }
        }
        reachability.start()
        self.reachabilityMonitor = reachability
    }

    private static func deviceName() -> String {
        let raw = ProcessInfo.processInfo.hostName
        if let base = raw.components(separatedBy: ".").first, !base.isEmpty {
            return base
        }
        return "ClipSync"
    }

    private func showTailscale() {
        tailscaleWindow.show(onStartPairing: { [weak self] in
            self?.startPairing()
        })
    }

    private func toggleSync() {
        guard !TestHost.isRunning, listenerReady, !syncTransition else { return }
        syncTransition = true
        let nowPaused = !menuBar.isSyncPaused
        if nowPaused {
            injector.setSyncEnabled(false)
            watcher.setSyncEnabled(false)
            watcher.stop()
        }
        Task { @MainActor in
            defer { syncTransition = false }
            await hub.setSyncEnabled(!nowPaused)
            guard listenerReady else { return }
            if !nowPaused {
                injector.setSyncEnabled(true)
                watcher.setSyncEnabled(true)
                watcher.start()
            }
            menuBar.setSyncPaused(nowPaused)
            logger.info("Sync state changed by user")
        }
    }

    private func invalidatePairingPresentation() {
        pairingPresentationPolicy.invalidate()
        pairingPresentationTask?.cancel()
        pairingPresentationTask = nil
        if let sessionID = presentedPairingSessionID {
            pairingCancellationTask = Task { await pairing.cancel(sessionID: sessionID) }
        }
        presentedPairingSessionID = nil
    }

    private func startPairing() {
        guard !TestHost.isRunning, listenerReady, !revokingPairing else { return }
        pairingPresentationTask?.cancel()
        let requestGeneration = pairingPresentationPolicy.begin()
        pairingPresentationTask = Task { @MainActor [weak self] in
            guard let self else { return }
            do {
                // Finish the previous window's session-specific cancellation before reusing
                // a session, so an old close callback cannot cancel the newly focused window.
                await pairingCancellationTask?.value
                guard !Task.isCancelled,
                      pairingPresentationPolicy.permits(requestGeneration, listenerReady: listenerReady) else { return }
                let existing = await pairing.currentSession()
                guard !Task.isCancelled,
                      pairingPresentationPolicy.permits(requestGeneration, listenerReady: listenerReady) else { return }
                let session: PairingSession
                if let existing { session = existing }
                else { session = try await pairing.startPairing() }
                guard !Task.isCancelled,
                      pairingPresentationPolicy.permits(requestGeneration, listenerReady: listenerReady) else { return }
                // Focusing the same window retains its callback generation. Replacing a consumed
                // session advances it before show() closes the old window, so that old callback
                // cannot invalidate the new presentation.
                let generation = presentedPairingSessionID != nil && presentedPairingSessionID != session.id
                    ? pairingPresentationPolicy.begin(replacing: true) : requestGeneration
                presentedPairingSessionID = session.id
                let hostname = TLSManager.primaryIPv4Address() ?? ProcessInfo.processInfo.hostName
                pairingWindow.show(session: session, hostname: hostname, port: ServerConfig.default.port,
                    fingerprint: tlsManager.spkiFingerprint,
                    onRefresh: { [weak self] in
                        guard let self, self.pairingPresentationPolicy.permits(generation, listenerReady: self.listenerReady) else { return nil }
                        let refreshed = try? await self.pairing.refreshPairing(sessionID: session.id)
                        guard !Task.isCancelled,
                              self.pairingPresentationPolicy.permits(generation, listenerReady: self.listenerReady) else { return nil }
                        return refreshed
                    },
                    onClose: { [weak self] in
                        guard let self, self.pairingPresentationPolicy.permits(generation, listenerReady: true) else { return }
                        self.invalidatePairingPresentation()
                    })
            } catch is CancellationError { }
            catch { logger.error("Failed to start pairing: \(error)") }
        }
    }

}


/// Test hosts must not run production startup even when Xcode omits its usual environment variable.
enum TestHost {
    static var isRunning: Bool {
        let environment = ProcessInfo.processInfo.environment
        return environment["XCTestConfigurationFilePath"] != nil || environment["XCTestBundlePath"] != nil ||
            NSClassFromString("XCTestCase") != nil || NSClassFromString("XCTest.XCTestCase") != nil ||
            Bundle.allBundles.contains { $0.bundleURL.pathExtension == "xctest" }
    }
}

/// The only gate that can create listener/watcher/advertiser effects; injectable for startup-failure tests.
@MainActor
enum SecureStartup {
    static func run<Identity>(
        loadSecret: () throws -> Data,
        prepareIdentity: () throws -> Identity,
        prepareTokens: () async throws -> Void,
        start: (Data, Identity) -> Void
    ) async throws {
        let secret = try loadSecret()
        guard secret.count == 32 else { throw KeychainError.invalidSecretLength }
        let identity = try prepareIdentity()
        try await prepareTokens()
        try Task.checkCancellation()
        start(secret, identity)
    }
}

/// One offer per process, only on the first successful listener readiness.
struct InitialPairingPolicy {
    private(set) var considered = false

    mutating func listenerReady(pairedDeviceCount: Int?) -> Bool {
        guard !considered else { return false }
        considered = true
        return pairedDeviceCount == 0
    }
}

/// Presentation generations survive focus requests but invalidate all suspended work on close/stop.
struct PairingPresentationPolicy {
    private var generation: UUID?

    mutating func begin(replacing: Bool = false) -> UUID {
        if replacing || generation == nil { generation = UUID() }
        return generation!
    }

    mutating func invalidate() { generation = nil }

    func permits(_ candidate: UUID, listenerReady: Bool) -> Bool {
        listenerReady && generation == candidate
    }
}
