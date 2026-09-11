import Foundation
import HummingbirdWebSocket
import Logging
import NIOCore
import NIOWebSocket

struct ClipClientInfo: Sendable, Hashable {
    let id: UUID
    let remoteAddress: String?
    let connectedAt: Date
    let lastSeen: Date
}

actor WebSocketHub {
    final class Client: Hashable, @unchecked Sendable {
        let id: UUID
        let tokenID: String
        let outbound: WebSocketOutboundWriter
        let sourceTag: String?
        let remoteAddress: String?
        let connectedAt: Date
        var lastSeen: Date
        init(id: UUID = UUID(), tokenID: String, outbound: WebSocketOutboundWriter,
             sourceTag: String? = nil, remoteAddress: String? = nil, connectedAt: Date = Date()) {
            self.id = id; self.tokenID = tokenID; self.outbound = outbound
            self.sourceTag = sourceTag; self.remoteAddress = remoteAddress
            self.connectedAt = connectedAt; self.lastSeen = connectedAt
        }
        static func == (lhs: Client, rhs: Client) -> Bool { lhs.id == rhs.id }
        func hash(into hasher: inout Hasher) { hasher.combine(id) }
    }
    private var clients: Set<Client> = []
    private var continuations: [UUID: AsyncStream<[ClipClientInfo]>.Continuation] = [:]
    private var epoch: UInt64 = 0
    private var syncEnabled = true
    private var pending: ClipPayload?
    private var sending = false
    private let logger: Logger
    let errorStore: ErrorStore
    init(logger: Logger = Logger(label: "clipsync.ws.hub"), errorStore: ErrorStore) {
        self.logger = logger; self.errorStore = errorStore
    }
    var clientCount: Int { clients.count }
    func registrationEpoch() -> UInt64 { epoch }
    func snapshot() -> [ClipClientInfo] {
        clients.map { ClipClientInfo(id: $0.id, remoteAddress: $0.remoteAddress, connectedAt: $0.connectedAt, lastSeen: $0.lastSeen) }
            .sorted { $0.connectedAt < $1.connectedAt }
    }
    func events() -> AsyncStream<[ClipClientInfo]> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let id = UUID(); continuations[id] = continuation; continuation.yield(snapshot())
            continuation.onTermination = { [weak self] _ in Task { await self?.removeContinuation(id) } }
        }
    }
    private func removeContinuation(_ id: UUID) { continuations.removeValue(forKey: id) }
    func register(_ client: Client, expectedEpoch: UInt64) async -> Bool {
        guard epoch == expectedEpoch, clients.count < 4 else {
            try? await client.outbound.close(.policyViolation, reason: "Session unavailable")
            return false
        }
        clients.insert(client); notifyChange(); return true
    }
    func unregister(_ client: Client) { clients.remove(client); notifyChange() }
    func touch(_ client: Client) {
        guard clients.contains(client) else { return }
        client.lastSeen = Date(); notifyChange()
    }
    private func notifyChange() { for continuation in continuations.values { continuation.yield(snapshot()) } }
    func setSyncEnabled(_ enabled: Bool) { syncEnabled = enabled; if !enabled { pending = nil } }
    func revokeSessions(tokenID: String) async {
        epoch &+= 1
        let removed = clients.filter { $0.tokenID == tokenID }
        clients.subtract(removed); notifyChange()
        for client in removed { try? await client.outbound.close(.policyViolation, reason: "Pairing revoked") }
    }
    func closeAll() async {
        epoch &+= 1; pending = nil
        let removed = clients; clients.removeAll(); notifyChange()
        for client in removed { try? await client.outbound.close(.goingAway, reason: "Server stopping") }
    }
    // WebSocket library manages ping/pong and timeouts; successful writes are not fabricated pong receipts.
    func startPingLoop() {}
    func stop() async { await closeAll() }
    func broadcast(_ payload: ClipPayload) async {
        guard syncEnabled, !clients.isEmpty else { return }
        pending = payload
        guard !sending else { return }
        sending = true
        defer { sending = false }
        while let next = pending {
            pending = nil
            guard syncEnabled else { break }
            guard let data = try? JSONEncoder().encode(next), data.count <= ClipPayload.maxJSONBytes,
                  let text = String(data: data, encoding: .utf8) else { continue }
            for client in clients {
                guard syncEnabled else { break }
                guard clients.contains(client) else { continue }
                do { try await client.outbound.writeTextMessage(text) }
                catch { clients.remove(client); notifyChange() }
            }
        }
    }
}
