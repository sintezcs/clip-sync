import Foundation
import HTTPTypes
import Hummingbird
import HummingbirdCore
import HummingbirdWebSocket
import HummingbirdTLS
import NIOSSL
import Logging

extension PairingResponse: ResponseEncodable {}

final class ClipServer {
    private let config: ServerConfig
    private var logger: Logger
    private let hub: WebSocketHub
    private let injector: PasteboardInjector
    private let pairing: PairingManager
    private let tokenStore: TokenStore
    private let hmacValidator: HMACValidator
    private let tlsConfiguration: TLSConfiguration
    private let journal: ReplayJournal
    private let onReady: @Sendable () async -> Void
    private var runTask: Task<Void, Never>?
    private let errorStore: ErrorStore
    let rateLimiter = RateLimiter()

    init(config: ServerConfig = .default,
         hub: WebSocketHub,
         injector: PasteboardInjector,
         pairing: PairingManager,
         tokenStore: TokenStore,
         hmacValidator: HMACValidator,
         tlsConfiguration: TLSConfiguration,
         errorStore: ErrorStore,
         journal: ReplayJournal? = nil,
         onReady: @escaping @Sendable () async -> Void = {}) {
        self.config = config
        var logger = Logger(label: "clipsync.server")
        logger.logLevel = config.logLevel
        self.logger = logger
        self.hub = hub
        self.injector = injector
        self.pairing = pairing
        self.tokenStore = tokenStore
        self.hmacValidator = hmacValidator
        self.tlsConfiguration = tlsConfiguration
        self.errorStore = errorStore
        self.journal = journal ?? ReplayJournal(directory: FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("ClipSync/Replay", isDirectory: true))
        self.onReady = onReady
    }

    /// Runs the server until it exits or throws. Propagates errors to the caller.
    func run() async throws {
        let router = Self.makeRouter(
            hub: hub,
            injector: injector,
            pairing: pairing,
            tokenStore: tokenStore,
            hmacValidator: hmacValidator,
            rateLimiter: rateLimiter,
            journal: journal,
            logger: logger
        )

        let wsBuilder = HTTPServerBuilder.http1WebSocketUpgrade(configuration: .init(ws: .init(
            maxFrameSize: 16 * 1024, autoPing: .enabled(timePeriod: .seconds(5)), closeTimeout: .seconds(3)))) { [tokenStore, hub] request, channel, logger in
            guard request.path == "/ws" else { return .dontUpgrade }
            let epoch = await hub.registrationEpoch()
            let authHeader = request.headerFields[HTTPField.Name("Authorization")!]
            guard let token = AuthMiddleware<ClipRequestContext>.extractBearer(authHeader),
                  let record = try await tokenStore.validate(tokenPlain: token) else { return .dontUpgrade }
            let remoteAddress = channel.remoteAddress?.ipAddress
            return .upgrade([:]) { inbound, outbound, _ in
                let client = WebSocketHub.Client(tokenID: record.id, outbound: outbound, remoteAddress: remoteAddress)
                guard await hub.register(client, expectedEpoch: epoch) else { return }
                do {
                    // This socket is receive-only for clients; ping/pong are handled by the library.
                    for try await _ in inbound {
                        try await outbound.close(.policyViolation, reason: "Use authenticated POST /inject")
                        break
                    }
                } catch { logger.debug("WebSocket ended") }
                await hub.unregister(client)
            }
        }
        let serverBuilder = try HTTPServerBuilder.tls(wsBuilder, tlsConfiguration: tlsConfiguration)
        let onReady = self.onReady
        let app = Application(
            router: router,
            server: serverBuilder,
            configuration: .init(
                address: .hostname(config.host, port: config.port),
                serverName: "ClipSync"
            ),
            onServerRunning: { _ in await onReady() },
            logger: logger
        )
        logger.info("ClipSync server starting on \(config.host):\(config.port)", metadata: [
            "tls": .stringConvertible(true),
        ])
        await hub.startPingLoop()
        try await app.runService()
    }

    func start() {
        guard runTask == nil else { return }
        let config = self.config
        let logger = self.logger
        let errorStore = self.errorStore

        runTask = Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return }
            do {
                try await self.run()
            } catch {
                Self.logStartupError(error, config: config, logger: logger)
                let description = String(describing: error)
                let isPortInUse = description.contains("addressInUse")
                    || description.contains("EADDRINUSE")
                    || description.localizedCaseInsensitiveContains("address already in use")
                await MainActor.run {
                    if isPortInUse {
                        errorStore.appendAndNotify(AppError(
                            severity: .error,
                            summary: "Port \(config.port) already in use",
                            detail: error.localizedDescription,
                            suggestion: "Close other ClipSync instances or change the port."
                        ))
                    } else {
                        errorStore.appendAndNotify(AppError(
                            severity: .error,
                            summary: "Server failed to start",
                            detail: error.localizedDescription,
                            suggestion: "Check the logs and restart ClipSync."
                        ))
                    }
                }
            }
        }
    }

    func stop() {
        runTask?.cancel()
        runTask = nil
        Task { await hub.stop() }
    }

    func revokeSessions(tokenID: String) async { await hub.revokeSessions(tokenID: tokenID) }

    private static let version = "0.1.1"
    private static let platform = "macos"

    static func makeRouter(hub: WebSocketHub,
                           injector: PasteboardInjector,
                           pairing: PairingManager,
                           tokenStore: TokenStore,
                           hmacValidator: HMACValidator,
                           rateLimiter: RateLimiter,
                           journal: ReplayJournal,
                           logger: Logger) -> Router<ClipRequestContext> {
        let router = Router(context: ClipRequestContext.self)
        router.add(middleware: RateLimitMiddleware<ClipRequestContext>(rateLimiter: rateLimiter))
        router.add(middleware: AuthMiddleware<ClipRequestContext>(tokenStore: tokenStore, hmacValidator: hmacValidator))
        router.get("/health") { _, _ -> HealthResponse in
            HealthResponse(ok: true, version: version, platform: platform)
        }
        router.post("/inject") { request, _ -> InjectResponse in
            guard injector.isSyncEnabled else { throw HTTPError(.serviceUnavailable, message: "Sync paused") }
            guard let token = AuthMiddleware<ClipRequestContext>.extractBearer(request.headers[.authorization]),
                  try await tokenStore.validate(tokenPlain: token) != nil else { throw HTTPError(.unauthorized) }
            var request = request
            let buffer = try await request.collectBody(upTo: ClipPayload.maxJSONBytes)
            let raw = Data(buffer: buffer)
            let prepared: PreparedClip
            do {
                prepared = try await Task.detached(priority: .userInitiated) {
                    try ClipPayload.decodePrepared(raw)
                }.value
            } catch { throw HTTPError(.badRequest, message: "Invalid clipboard payload") }
            let payload = prepared.payload
            guard injector.isSyncEnabled else { throw HTTPError(.serviceUnavailable, message: "Sync paused") }
            let decision: ReplayDecision
            do { decision = try await journal.accept(peer: token, nonce: payload.nonce, payload: raw) }
            catch { throw HTTPError(.serviceUnavailable, message: "Replay protection unavailable") }
            switch decision {
            case .duplicate: return InjectResponse(ok: true, nonce: payload.nonce, applied: false)
            case .conflict: throw HTTPError(.conflict, message: "Event identity conflict")
            case .accepted: break
            }
            guard let record = try await tokenStore.validate(tokenPlain: token) else { throw HTTPError(.unauthorized) }
            do {
                try await MainActor.run {
                    try tokenStore.withAuthorization(id: record.id) { try injector.inject(prepared) }
                }
            }
            catch TokenStoreError.unauthorized { throw HTTPError(.unauthorized) }
            catch PasteboardInjectionError.paused { throw HTTPError(.serviceUnavailable, message: "Sync paused") }
            catch PasteboardInjectionError.superseded { throw HTTPError(.conflict, message: "Newer local clipboard item") }
            catch { throw HTTPError(.badRequest, message: "Clipboard write failed") }
            await hub.broadcast(payload)
            return InjectResponse(ok: true, nonce: payload.nonce, applied: true)
        }
        router.get("/pair") { request, _ -> PairingResponse in
            guard let code = request.uri.queryParameters["code"], code.count == 6 else { throw HTTPError(.badRequest) }
            do {
                let generation = try await tokenStore.issuanceGeneration()
                let response = try await pairing.consume(code: String(code))
                _ = try await tokenStore.register(tokenPlain: response.token, deviceLabel: "Personal phone", issuanceGeneration: generation)
                return response
            } catch TokenStoreError.unauthorized { throw HTTPError(.unauthorized, message: "Pairing canceled") }
            catch PairingError.rateLimited { throw HTTPError(.tooManyRequests) }
            catch is PairingError { throw HTTPError(.unauthorized, message: "Pairing unavailable or invalid") }
            catch { throw HTTPError(.serviceUnavailable, message: "Secure pairing storage unavailable") }
        }
        router.post("/pair") { request, _ -> PairingResponse in
            var request = request
            let body = try await request.collectBody(upTo: 1024)
            struct SecretRequest: Decodable { let secret: String }
            guard let candidate = try? JSONDecoder().decode(SecretRequest.self, from: body),
                  candidate.secret.utf8.count == 43 else { throw HTTPError(.badRequest) }
            do {
                let generation = try await tokenStore.issuanceGeneration()
                let response = try await pairing.consume(secret: candidate.secret)
                _ = try await tokenStore.register(tokenPlain: response.token, deviceLabel: "Personal phone", issuanceGeneration: generation)
                return response
            } catch TokenStoreError.unauthorized { throw HTTPError(.unauthorized, message: "Pairing canceled") }
            catch PairingError.rateLimited { throw HTTPError(.tooManyRequests) }
            catch is PairingError { throw HTTPError(.unauthorized, message: "Pairing unavailable or invalid") }
            catch { throw HTTPError(.serviceUnavailable, message: "Secure pairing storage unavailable") }
        }
        return router
    }

    struct HealthResponse: ResponseEncodable, Sendable {
        let ok: Bool
        let version: String
        let platform: String
    }

    struct InjectResponse: ResponseEncodable, Sendable {
        let ok: Bool
        let nonce: String
        /// Duplicate acknowledgement means previously accepted, not proof of application after a crash.
        let applied: Bool
    }

    private static func logStartupError(
        _ error: Error,
        config: ServerConfig,
        logger: Logger
    ) {
        let description = String(describing: error)
        if description.contains("addressInUse")
            || description.contains("EADDRINUSE")
            || description.localizedCaseInsensitiveContains("address already in use")
        {
            logger.error(
                """
                Port \(config.port) is already in use on \(config.host). \
                Another ClipSync instance or a different process is holding it. \
                Stop the other process and relaunch.
                """
            )
        } else {
            logger.error("ClipSync server failed to start on \(config.host):\(config.port): \(error)")
        }
    }
}
