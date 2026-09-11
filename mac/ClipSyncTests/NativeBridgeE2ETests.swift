import AppKit
import Darwin
import Foundation
import CoreGraphics
import ImageIO
import UniformTypeIdentifiers
import XCTest
@testable import ClipSync

/// Explicit opt-in only; never touches NSPasteboard.general, production Keychain entries or LAN interfaces.
final class NativeBridgeE2ETests: XCTestCase {
    private actor Readiness {
        var bound = false
        var failed = false
        func ready() { bound = true }
        func fail() { failed = true }
    }
    private enum FixtureError: Error { case timedOut, serverFailed, unsafeDirectory, fileWrite }

    func testNamedPasteboardAndroidBridgeOptIn() async throws {
        let root = URL(fileURLWithPath: "/tmp/klippa-audit-e2e", isDirectory: true)
        let marker = root.appendingPathComponent("enabled")
        guard FileManager.default.fileExists(atPath: marker.path) else {
            throw XCTSkip("Native bridge fixture requires explicit /tmp/klippa-audit-e2e/enabled marker")
        }
        let attrs = try FileManager.default.attributesOfItem(atPath: root.path)
        let markerAttrs = try FileManager.default.attributesOfItem(atPath: marker.path)
        guard attrs[.type] as? FileAttributeType == .typeDirectory,
              markerAttrs[.type] as? FileAttributeType == .typeRegular else { throw FixtureError.unsafeDirectory }
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: root.path)
        for name in ["pair.json", "success.json", "failure.json"] { try? FileManager.default.removeItem(at: root.appendingPathComponent(name)) }
        let keychain = Keychain(service: "com.clipsync.tests.native-bridge.\(UUID().uuidString)")
        let replayDirectory = root.appendingPathComponent("replay-\(UUID().uuidString)", isDirectory: true)
        let pb = NSPasteboard(name: .init("com.clipsync.audit.\(UUID().uuidString)"))
        let watcher = PasteboardWatcher(pasteboard: pb, intervalMillis: 100)
        let injector = PasteboardInjector(pasteboard: pb, watcher: watcher)
        let errors = await MainActor.run { ErrorStore() }
        let hub = WebSocketHub(errorStore: errors)
        let ready = Readiness()
        var serverTask: Task<Void, Error>?
        var broadcastTask: Task<Void, Never>?
        var stage = "initializing"
        defer {
            broadcastTask?.cancel()
            serverTask?.cancel()
            watcher.stop()
            pb.releaseGlobally()
            for account in ["default", "tls-cert-der", "tls-key-pem", "tokens"] { try? keychain.delete(account: account) }
            try? FileManager.default.removeItem(at: replayDirectory)
            try? FileManager.default.removeItem(at: root.appendingPathComponent("pair.json"))
        }
        do {
            let secret = try keychain.loadOrCreateSecret()
            let tls = TLSManager(keychain: keychain)
            try tls.loadOrCreate()
            let tokens = TokenStore(keychain: keychain)
            try await tokens.prepare()
            let pairing = PairingManager(secret: secret, ttl: 180)
            let session = try await pairing.startPairing()
            let server = ClipServer(config: ServerConfig(host: "127.0.0.1", port: 17010, logLevel: .warning),
                hub: hub, injector: injector, pairing: pairing, tokenStore: tokens,
                hmacValidator: HMACValidator(secret: secret), tlsConfiguration: try tls.makeServerTLSConfiguration(),
                errorStore: errors, journal: ReplayJournal(directory: replayDirectory), onReady: { await ready.ready() })
            let stream = watcher.events()
            broadcastTask = Task {
                for await payload in stream {
                    if Task.isCancelled { break }
                    await hub.broadcast(payload)
                }
            }
            watcher.start()
            serverTask = Task {
                do { try await server.run() }
                catch { await ready.fail(); throw error }
            }
            let deadline = ContinuousClock.now.advanced(by: .seconds(180))
            stage = "waiting-for-bind"
            try await waitUntil(deadline: deadline, ready: ready) { await ready.bound }
            try atomicJSON(["host": "10.0.2.2", "port": 17010, "fp": tls.spkiFingerprint,
                "secret": session.qrSecret, "version": 2], to: root.appendingPathComponent("pair.json"))
            stage = "waiting-for-android-websocket"
            try await waitUntil(deadline: deadline, ready: ready) { !(await hub.snapshot()).isEmpty }
            try await Task.sleep(for: .seconds(2))
            stage = "mac-text-to-android"
            await MainActor.run { pb.clearContents(); pb.setString("KLIPPA_MAC_TEXT_Ω", forType: .string) }
            stage = "android-text-to-mac"
            try await waitUntil(deadline: deadline, ready: ready) {
                await MainActor.run { pb.string(forType: .string) == "KLIPPA_ANDROID_TEXT_Ω" }
            }
            stage = "mac-image-to-android"
            let red = try Self.png(rgba: [255, 0, 0, 255])
            XCTAssertTrue(Self.hasSolidPixels(red, rgba: [255, 0, 0, 255]))
            await MainActor.run { pb.clearContents(); pb.setData(red, forType: .png) }
            stage = "android-image-to-mac"
            try await waitUntil(deadline: deadline, ready: ready) {
                await MainActor.run {
                    guard let data = pb.data(forType: .png) else { return false }
                    return Self.hasSolidPixels(data, rgba: [0, 0, 255, 255])
                }
            }
            stage = "completion-marker"
            await MainActor.run { pb.clearContents(); pb.setString("KLIPPA_MAC_COMPLETE", forType: .string) }
            stage = "waiting-for-android-disconnect"
            try await waitUntil(deadline: deadline, ready: ready) { await hub.snapshot().isEmpty }
            await hub.stop()
            broadcastTask?.cancel()
            serverTask?.cancel()
            if let broadcastTask { await broadcastTask.value }
            if let serverTask { _ = await serverTask.result }
            try atomicJSON(["ok": true, "macToAndroid": ["text", "png"], "androidToMac": ["text", "png"],
                "pasteboard": "isolated-named", "completedAt": ISO8601DateFormatter().string(from: Date())],
                to: root.appendingPathComponent("success.json"))
        } catch {
            await hub.stop()
            broadcastTask?.cancel()
            serverTask?.cancel()
            if let broadcastTask { await broadcastTask.value }
            if let serverTask { _ = await serverTask.result }
            try? atomicJSON(["ok": false, "stage": stage], to: root.appendingPathComponent("failure.json"))
            throw error
        }
    }

    private func waitUntil(deadline: ContinuousClock.Instant, ready: Readiness,
                           predicate: () async throws -> Bool) async throws {
        while ContinuousClock.now < deadline {
            try Task.checkCancellation()
            if await ready.failed { throw FixtureError.serverFailed }
            if try await predicate() { return }
            try await Task.sleep(for: .milliseconds(100))
        }
        throw FixtureError.timedOut
    }

    func testSyntheticFixturePNGHasOpaqueSRGBPixels() throws {
        let colors: [[UInt8]] = [[255, 0, 0, 255], [0, 0, 255, 255]]
        for rgba in colors {
            let png = try Self.png(rgba: rgba)
            XCTAssertTrue(Self.hasSolidPixels(png, rgba: rgba))
            XCTAssertFalse(Self.hasSolidPixels(png, rgba: [0, 0, 0, 0]))
        }
    }

    private static let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue

    private static func png(rgba: [UInt8]) throws -> Data {
        precondition(rgba.count == 4 && rgba[3] == 255)
        let colorSpace = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
        var pixels = (0..<64).flatMap { _ in rgba }
        let image: CGImage = try pixels.withUnsafeMutableBytes { bytes in
            let context = try XCTUnwrap(CGContext(data: bytes.baseAddress, width: 8, height: 8,
                bitsPerComponent: 8, bytesPerRow: 32, space: colorSpace, bitmapInfo: bitmapInfo))
            return try XCTUnwrap(context.makeImage())
        }
        let output = NSMutableData()
        let destination = try XCTUnwrap(CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil))
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination) else { throw FixtureError.fileWrite }
        let result = output as Data
        guard hasSolidPixels(result, rgba: rgba) else { throw FixtureError.fileWrite }
        return result
    }

    private static func hasSolidPixels(_ data: Data, rgba: [UInt8]) -> Bool {
        guard rgba.count == 4,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil), image.width == 8, image.height == 8,
              let space = CGColorSpace(name: CGColorSpace.sRGB) else { return false }
        var pixels = [UInt8](repeating: 0, count: 8 * 8 * 4)
        let drawn = pixels.withUnsafeMutableBytes { bytes -> Bool in
            guard let context = CGContext(data: bytes.baseAddress, width: 8, height: 8,
                bitsPerComponent: 8, bytesPerRow: 32, space: space, bitmapInfo: bitmapInfo) else { return false }
            context.setBlendMode(.copy)
            context.draw(image, in: CGRect(x: 0, y: 0, width: 8, height: 8))
            return true
        }
        return drawn && (0..<64).allSatisfy { pixel in
            (0..<4).allSatisfy { channel in abs(Int(pixels[pixel * 4 + channel]) - Int(rgba[channel])) <= 1 }
        }
    }

    /// A new 0600 temporary file is fully written before atomic rename exposes it to the operator.
    private func atomicJSON(_ object: [String: Any], to destination: URL) throws {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        let temporary = destination.deletingLastPathComponent().appendingPathComponent(".fixture-\(UUID().uuidString)")
        let fd = Darwin.open(temporary.path, O_CREAT | O_EXCL | O_WRONLY | O_NOFOLLOW, 0o600)
        guard fd >= 0 else { throw FixtureError.fileWrite }
        defer { Darwin.close(fd); try? FileManager.default.removeItem(at: temporary) }
        try data.withUnsafeBytes { buffer in
            var offset = 0
            while offset < data.count {
                let written = Darwin.write(fd, buffer.baseAddress!.advanced(by: offset), data.count - offset)
                guard written > 0 else { throw FixtureError.fileWrite }
                offset += written
            }
        }
        guard Darwin.fsync(fd) == 0, Darwin.rename(temporary.path, destination.path) == 0 else { throw FixtureError.fileWrite }
    }
}
