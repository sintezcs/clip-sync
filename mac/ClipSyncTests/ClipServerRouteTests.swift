import AppKit
import Foundation
import HTTPTypes
import Hummingbird
import Logging
import NIOCore
import XCTest
@testable import ClipSync

final class ClipServerRouteTests: XCTestCase {
    private final class MemoryKeys: KeychainStorage, @unchecked Sendable {
        private let lock = NSLock()
        private var values: [String: Data] = [:]
        func load(account: String) throws -> Data {
            lock.lock(); defer { lock.unlock() }
            guard let value = values[account] else { throw KeychainError.notFound }
            return value
        }
        func save(_ data: Data, account: String) throws {
            lock.lock(); defer { lock.unlock() }; values[account] = data
        }
        func delete(account: String) throws {
            lock.lock(); defer { lock.unlock() }; values.removeValue(forKey: account)
        }
    }
    private final class FailingPasteboard: PasteboardWriting, @unchecked Sendable {
        var writes = 0
        var changeCount: Int { 0 }
        func string(forType type: NSPasteboard.PasteboardType) -> String? { nil }
        func data(forType type: NSPasteboard.PasteboardType) -> Data? { nil }
        func types() -> [NSPasteboard.PasteboardType]? { nil }
        func replace(data: Data, type: NSPasteboard.PasteboardType, marker: String) -> Bool {
            writes += 1
            return false
        }
    }
    private struct LoopbackClient {
        let port: Int
        let session: URLSession
        func execute(uri: String, method: HTTPRequest.Method, headers: HTTPFields = [:],
                     body: ByteBuffer, check: (LoopbackResponse) throws -> Void) async throws {
            var request = URLRequest(url: URL(string: "http://127.0.0.1:\(port)\(uri)")!)
            request.httpMethod = method.rawValue
            request.httpBody = Data(buffer: body)
            for header in headers { request.setValue(header.value, forHTTPHeaderField: header.name.rawName) }
            let (data, response) = try await session.data(for: request)
            let http = try XCTUnwrap(response as? HTTPURLResponse)
            try check(LoopbackResponse(status: .init(code: http.statusCode), body: ByteBuffer(bytes: data)))
        }
    }
    private struct LoopbackResponse {
        let status: HTTPResponse.Status
        let body: ByteBuffer
    }
    private enum HarnessError: Error { case listenerUnavailable }

    private func withLoopback(_ router: Router<ClipRequestContext>,
                              test: (LoopbackClient) async throws -> Void) async throws {
        let (ports, continuation) = AsyncThrowingStream<Int, Error>.makeStream()
        // Plain HTTP is confined to this test listener on loopback. ClipServer production requires TLS.
        let app = Application(router: router,
            configuration: .init(address: .hostname("127.0.0.1", port: 0)),
            onServerRunning: { channel in
                if let port = channel.localAddress?.port { continuation.yield(port) }
                else { continuation.finish(throwing: HarnessError.listenerUnavailable) }
            })
        let server = Task {
            do { try await app.runService(); continuation.finish() }
            catch { continuation.finish(throwing: error) }
        }
        let deadline = Task {
            do { try await Task.sleep(for: .seconds(10)); continuation.finish(throwing: HarnessError.listenerUnavailable) }
            catch { }
        }
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 5
        configuration.timeoutIntervalForResource = 10
        configuration.connectionProxyDictionary = [:]
        let session = URLSession(configuration: configuration)
        do {
            var iterator = ports.makeAsyncIterator()
            guard let port = try await iterator.next() else { throw HarnessError.listenerUnavailable }
            deadline.cancel()
            try await test(LoopbackClient(port: port, session: session))
            session.invalidateAndCancel()
            continuation.finish()
            server.cancel()
            await server.value
        } catch {
            deadline.cancel()
            session.invalidateAndCancel()
            continuation.finish()
            server.cancel()
            await server.value
            throw error
        }
    }

    private let secret = Data(repeating: 7, count: 32)
    private let token = Data(repeating: 9, count: 32).base64EncodedString()
    private func headers(_ body: Data) -> HTTPFields {
        [.authorization: "Bearer \(token)",
         HTTPField.Name("X-ClipSync-Signature")!: HMACValidator.sign(body: body, secret: secret, at: Int(Date().timeIntervalSince1970))]
    }

    func testFailedWriteRemainsAcceptedAfterJournalRecreationWithoutAnotherEffect() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("clipsync-route-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = TokenStore(keychain: MemoryKeys())
        try await store.register(tokenPlain: token, deviceLabel: "test")
        let pasteboard = FailingPasteboard()
        let injector = PasteboardInjector(pasteboard: pasteboard)
        let errors = await MainActor.run { ErrorStore() }
        let hub = WebSocketHub(errorStore: errors)
        let pairing = PairingManager(secret: secret)
        let body = try JSONEncoder().encode(ClipPayload.text("test event"))
        let signed = headers(body)
        for attempt in 0...1 {
            // A fresh journal object models process restart after durable acceptance and failed effect.
            let router = ClipServer.makeRouter(hub: hub, injector: injector, pairing: pairing,
                tokenStore: store, hmacValidator: HMACValidator(secret: secret), rateLimiter: RateLimiter(),
                journal: ReplayJournal(directory: directory), logger: Logger(label: "route.test"))
            try await withLoopback(router) { client in
                try await client.execute(uri: "/inject", method: .post, headers: signed, body: ByteBuffer(bytes: body)) { response in
                    if attempt == 0 { XCTAssertEqual(response.status, .badRequest) }
                    else {
                        XCTAssertEqual(response.status, .ok)
                        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(buffer: response.body)) as? [String: Any])
                        XCTAssertEqual(json["ok"] as? Bool, true)
                        XCTAssertEqual(json["applied"] as? Bool, false)
                    }
                }
            }
        }
        let writes = await MainActor.run { pasteboard.writes }
        XCTAssertEqual(writes, 1, "Duplicate acceptance must never retry an ambiguous clipboard effect")
    }

    func testAuthMalformedPayloadPauseAndRevocationPreventWrites() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("clipsync-route-\(UUID())")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = TokenStore(keychain: MemoryKeys())
        let record = try await store.register(tokenPlain: token, deviceLabel: "test")
        let pasteboard = FailingPasteboard()
        let injector = PasteboardInjector(pasteboard: pasteboard)
        let errors = await MainActor.run { ErrorStore() }
        let router = ClipServer.makeRouter(hub: WebSocketHub(errorStore: errors), injector: injector,
            pairing: PairingManager(secret: secret), tokenStore: store, hmacValidator: HMACValidator(secret: secret),
            rateLimiter: RateLimiter(), journal: ReplayJournal(directory: directory), logger: Logger(label: "route.test"))
        let body = try JSONEncoder().encode(ClipPayload.text("test"))
        let signed = headers(body)
        let malformed = Data("{broken".utf8)
        let malformedHeaders = headers(malformed)
        try await withLoopback(router) { client in
            try await client.execute(uri: "/inject", method: .post, headers: [.authorization: "Bearer \(self.token)"], body: ByteBuffer(bytes: body)) { response in
                XCTAssertEqual(response.status, .unauthorized)
            }
            try await client.execute(uri: "/inject", method: .post, headers: malformedHeaders, body: ByteBuffer(bytes: malformed)) { response in
                XCTAssertEqual(response.status, .badRequest)
            }
            injector.setSyncEnabled(false)
            try await client.execute(uri: "/inject", method: .post, headers: signed, body: ByteBuffer(bytes: body)) { response in
                XCTAssertEqual(response.status, .serviceUnavailable)
            }
            injector.setSyncEnabled(true)
            try await store.revoke(id: record.id)
            try await client.execute(uri: "/inject", method: .post, headers: signed, body: ByteBuffer(bytes: body)) { response in
                XCTAssertEqual(response.status, .unauthorized)
            }
        }
        let writes = await MainActor.run { pasteboard.writes }
        XCTAssertEqual(writes, 0)
    }
}
