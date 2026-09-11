import AppKit
import XCTest
@testable import ClipSync

/// An entirely in-memory pasteboard; no test references NSPasteboard.general or launches UI.
final class FakePasteboard: PasteboardWriting {
    private(set) var changeCount = 0
    private var storage: [NSPasteboard.PasteboardType: Data] = [:]
    var afterReplace: (() -> Void)?
    func types() -> [NSPasteboard.PasteboardType]? { Array(storage.keys) }
    func string(forType type: NSPasteboard.PasteboardType) -> String? { storage[type].flatMap { String(data: $0, encoding: .utf8) } }
    func data(forType type: NSPasteboard.PasteboardType) -> Data? { storage[type] }
    func replace(data: Data, type: NSPasteboard.PasteboardType, marker: String) -> Bool {
        storage = [type: data, PasteboardWatcher.ownershipType: Data(marker.utf8)]
        changeCount += 1
        afterReplace?()
        return true
    }
    func externalWriteText(_ value: String) { storage = [.string: Data(value.utf8)]; changeCount += 1 }
    func externalWriteImage(_ data: Data, type: NSPasteboard.PasteboardType = .png) { storage = [type: data]; changeCount += 1 }
    func externalWriteFile() { storage = [.fileURL: Data("file:///private/synthetic".utf8), .string: Data("file".utf8)]; changeCount += 1 }
    func externalWriteSensitive(_ value: String) {
        storage = [.string: Data(value.utf8), .init("org.nspasteboard.ConcealedType"): Data()]; changeCount += 1
    }
}

final class PasteboardRoundtripTests: XCTestCase {
    static func png() throws -> Data {
        let image = try XCTUnwrap(NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 2, pixelsHigh: 2,
            bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
            colorSpaceName: .deviceRGB, bytesPerRow: 8, bitsPerPixel: 32))
        for x in 0..<2 { for y in 0..<2 { image.setColor(.blue, atX: x, y: y) } }
        return try XCTUnwrap(image.representation(using: .png, properties: [:]))
    }

    func testTextAndActualSyntheticPNGInjection() throws {
        let pb = FakePasteboard()
        let injector = PasteboardInjector(pasteboard: pb)
        try injector.inject(.text("Synthetic Ω"))
        XCTAssertEqual(pb.string(forType: .string), "Synthetic Ω")
        let bytes = try Self.png()
        try injector.inject(.image(bytes))
        XCTAssertEqual(pb.data(forType: .png), bytes)
    }

    func testPreparedPayloadAppliesExactValidatedContent() throws {
        let pb = FakePasteboard()
        let injector = PasteboardInjector(pasteboard: pb)
        for payload in [ClipPayload.text("Prepared synthetic Ω"), .image(try Self.png())] {
            let prepared = try ClipPayload.decodePrepared(JSONEncoder().encode(payload))
            XCTAssertEqual(prepared.payload, payload)
            XCTAssertEqual(prepared.bytes, payload.rawData)
            try injector.inject(prepared)
            let type: NSPasteboard.PasteboardType = payload.type == .text ? .string : .png
            XCTAssertEqual(pb.data(forType: type), prepared.bytes)
            XCTAssertEqual(pb.string(forType: PasteboardWatcher.ownershipType), payload.nonce)
        }
        injector.setSyncEnabled(false)
        XCTAssertThrowsError(try injector.inject(PreparedClip(.text("paused"))))
    }

    func testInvalidPayloadCannotBecomePrepared() throws {
        let invalid = [ClipPayload.image(Data([0x89, 0x50, 0x4e, 0x47])),
                       ClipPayload.file(Data(), name: "synthetic")]
        for payload in invalid {
            XCTAssertThrowsError(try PreparedClip(payload))
            XCTAssertThrowsError(try ClipPayload.decodePrepared(JSONEncoder().encode(payload)))
        }
    }

    func testInvalidPayloadDoesNotClearExistingClipboard() throws {
        let pb = FakePasteboard()
        pb.externalWriteText("Preserve synthetic local text")
        let count = pb.changeCount
        let injector = PasteboardInjector(pasteboard: pb)
        let now = ClipPayload.currentTimestampMillis()
        let invalid = [
            ClipPayload(type: .text, mime: "text/plain", dataBase64: "/w==", ts: now, nonce: "bad-utf8"),
            ClipPayload.image(Data([0x89, 0x50, 0x4e, 0x47])),
            ClipPayload.file(Data("file".utf8), name: "../private"),
            ClipPayload(type: .image, mime: "image/gif", dataBase64: "AA==", ts: now, nonce: "bad-mime")
        ]
        for payload in invalid { XCTAssertThrowsError(try injector.inject(payload)) }
        XCTAssertEqual(pb.changeCount, count)
        XCTAssertEqual(pb.string(forType: .string), "Preserve synthetic local text")
    }

    func testPreparedRemoteCannotOverwriteUnobservedLocalImageAndPublishesItLater() async throws {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        let injector = PasteboardInjector(pasteboard: pb, watcher: watcher)
        let stream = watcher.events()
        let bytes = try Self.png()
        let published = expectation(description: "Pending local image published asynchronously")
        let observer = Task {
            for await payload in stream {
                XCTAssertEqual(payload.type, .image)
                XCTAssertEqual(payload.rawData, bytes)
                published.fulfill()
                break
            }
        }
        defer { observer.cancel(); watcher.stop() }
        let prepared = try PreparedClip(.text("remote"))
        pb.externalWriteImage(bytes)
        XCTAssertThrowsError(try injector.inject(prepared)) { error in
            XCTAssertEqual(error as? PasteboardInjectionError, .superseded)
        }
        XCTAssertEqual(pb.data(forType: .png), bytes)
        await fulfillment(of: [published], timeout: 2)
    }

    func testRemoteADoesNotHideLocalBBeforeAcknowledgment() throws {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        let injector = PasteboardInjector(pasteboard: pb, watcher: watcher)
        pb.afterReplace = { pb.externalWriteText("local B") }
        try injector.inject(.text("remote A"))
        XCTAssertEqual(watcher.pollNow()?.rawData, Data("local B".utf8))
        XCTAssertNil(watcher.pollNow())
    }

    func testOwnedEchoIgnoredButIntentionalSameContentCopyEmits() throws {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        let injector = PasteboardInjector(pasteboard: pb, watcher: watcher)
        try injector.inject(.text("same synthetic text"))
        XCTAssertNil(watcher.pollNow())
        pb.externalWriteText("same synthetic text")
        let first = try XCTUnwrap(watcher.pollNow())
        pb.externalWriteText("same synthetic text")
        let second = try XCTUnwrap(watcher.pollNow())
        XCTAssertNotEqual(first.nonce, second.nonce)
    }

    func testPauseBothDirectionsAndNoStaleResume() throws {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        let injector = PasteboardInjector(pasteboard: pb, watcher: watcher)
        injector.setSyncEnabled(false)
        watcher.setSyncEnabled(false)
        pb.externalWriteText("copied while paused")
        XCTAssertThrowsError(try injector.inject(.text("remote while paused")))
        XCTAssertNil(watcher.pollNow())
        watcher.setSyncEnabled(true)
        injector.setSyncEnabled(true)
        XCTAssertNil(watcher.pollNow())
        XCTAssertEqual(pb.string(forType: .string), "copied while paused")
    }

    func testFilesAndSensitiveTypesNeverCapture() {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        pb.externalWriteFile()
        XCTAssertNil(watcher.pollNow())
        pb.externalWriteSensitive("synthetic concealed item")
        XCTAssertNil(watcher.pollNow())
    }

    func testOneHundredControlledChangesAndNoEcho() throws {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        let injector = PasteboardInjector(pasteboard: pb, watcher: watcher)
        // Remote events first: no local-write conflict and every owned write is suppressed.
        for i in 0..<100 {
            try injector.inject(.text("remote \(i)"))
            XCTAssertNil(watcher.pollNow())
        }
        var nonces = Set<String>()
        for i in 0..<100 {
            pb.externalWriteText(i.isMultiple(of: 2) ? "repeated" : "local \(i)")
            let event = try XCTUnwrap(watcher.pollNow())
            nonces.insert(event.nonce)
            XCTAssertNil(watcher.pollNow())
        }
        XCTAssertEqual(nonces.count, 100)
    }

    func testKnownNewerLocalCopyWinsOverOldRemote() {
        var order = ClipboardChangeOrder(baseline: 0)
        XCTAssertTrue(order.observeLocal(count: 1, nowMs: 200))
        XCTAssertFalse(order.permitsRemote(timestamp: 100))
        XCTAssertFalse(order.permitsRemote(timestamp: 200))
        XCTAssertTrue(order.permitsRemote(timestamp: 201))
    }
}


extension PasteboardRoundtripTests {
    func testTIFFOnlyClipboardBecomesValidPNG() throws {
        let png = try Self.png()
        let representation = try XCTUnwrap(NSBitmapImageRep(data: png))
        let tiff = try XCTUnwrap(representation.representation(using: .tiff, properties: [:]))
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        pb.externalWriteImage(tiff, type: .tiff)
        let payload = try XCTUnwrap(watcher.pollNow())
        XCTAssertEqual(payload.type, .image)
        XCTAssertEqual(payload.mime, "image/png")
        try payload.validate()
        XCTAssertLessThanOrEqual(try XCTUnwrap(payload.rawData).count, ClipPayload.maxImageBytes)
        XCTAssertThrowsError(try ClipPayload.image(tiff, mime: "image/tiff").validate())
    }

    func testInvalidOrOversizedTIFFClipboardProducesNoEvent() {
        let pb = FakePasteboard()
        let watcher = PasteboardWatcher(pasteboard: pb)
        for bytes in [Data([0x49, 0x49, 42, 0]), Data(repeating: 0, count: ClipImageSafety.maxClipboardSourceBytes + 1)] {
            pb.externalWriteImage(bytes, type: .tiff)
            XCTAssertNil(watcher.pollNow())
        }
    }
}
