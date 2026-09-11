import XCTest
@testable import ClipSync

final class ClipPayloadTests: XCTestCase {
    func testTextFactoryEncodesUTF8Base64() throws {
        let payload = ClipPayload.text("hola")
        XCTAssertEqual(payload.type, .text)
        XCTAssertEqual(payload.mime, "text/plain")
        XCTAssertEqual(payload.dataBase64, Data("hola".utf8).base64EncodedString())
        XCTAssertFalse(payload.nonce.isEmpty)
    }

    func testCodableRoundtrip() throws {
        let payload = ClipPayload.text("round trip")
        let data = try JSONEncoder().encode(payload)
        let decoded = try JSONDecoder().decode(ClipPayload.self, from: data)
        XCTAssertEqual(decoded, payload)
    }

    func testDigestIgnoresTsAndNonce() {
        let a = ClipPayload(type: .text, mime: "text/plain", dataBase64: "aGk=", ts: 1, nonce: "A")
        let b = ClipPayload(type: .text, mime: "text/plain", dataBase64: "aGk=", ts: 999, nonce: "B")
        XCTAssertEqual(PasteboardWatcher.digest(for: a), PasteboardWatcher.digest(for: b))
    }
}

extension ClipPayloadTests {
    func testStrictPayloadBoundsAndTimestampArithmetic() throws {
        let now: Int64 = 1_000_000
        func payload(_ ts: Int64, data: String = "aGk=", nonce: String = "event", name: String? = nil) -> ClipPayload {
            .init(type: .text, mime: "text/plain", dataBase64: data, ts: ts, nonce: nonce, name: name)
        }
        try payload(now).validate(nowMs: now)
        for value in [Int64.min, Int64.max, -1, now - 300_000, now + 300_000] {
            XCTAssertThrowsError(try payload(value).validate(nowMs: now))
        }
        for value in ["aGk=\n", "aGk", "/w==", "aGl="] { XCTAssertThrowsError(try payload(now, data: value).validate(nowMs: now)) }
        for value in ["", "../event", String(repeating: "a", count: 129)] { XCTAssertThrowsError(try payload(now, nonce: value).validate(nowMs: now)) }
        for value in ["", "..", "../name", "a\\b", String(repeating: "a", count: 256)] { XCTAssertThrowsError(try payload(now, name: value).validate(nowMs: now)) }
        XCTAssertThrowsError(try ClipPayload.text(String(repeating: "x", count: ClipPayload.maxTextBytes + 1)).validate())
    }

    func testFractionalAndBooleanTimestampsRejected() throws {
        for value in ["1.5", "true", "\"1\""] {
            let json = "{\"type\":\"text\",\"mime\":\"text/plain\",\"data\":\"aGk=\",\"nonce\":\"event\",\"ts\":\(value)}"
            XCTAssertThrowsError(try ClipPayload.decodeValidated(Data(json.utf8), nowMs: 1))
        }
    }

    func testImageFormatMustMatchAndHeaderOnlyIsRejected() throws {
        let png = try PasteboardRoundtripTests.png()
        try ClipPayload.image(png).validate()
        XCTAssertThrowsError(try ClipPayload.image(png, mime: "image/jpeg").validate())
        XCTAssertThrowsError(try ClipPayload.image(Data(png.prefix(24))).validate())
        XCTAssertThrowsError(try ClipPayload.image(Data(repeating: 0, count: ClipPayload.maxImageBytes + 1)).validate())
    }
}


extension ClipPayloadTests {
    func testCompressedImageDimensionsRejectedBeforeDecode() throws {
        for (width, height) in [(8193, 1), (8192, 4096)] {
            var png = [UInt8](try PasteboardRoundtripTests.png())
            for (offset, value) in [(16, UInt32(width)), (20, UInt32(height))] {
                for index in 0..<4 { png[offset + index] = UInt8((value >> (24 - index * 8)) & 255) }
            }
            var crc: UInt32 = 0xffffffff
            for byte in png[12..<29] {
                crc ^= UInt32(byte)
                for _ in 0..<8 { crc = (crc & 1) == 1 ? (crc >> 1) ^ 0xedb88320 : crc >> 1 }
            }
            crc ^= 0xffffffff
            for index in 0..<4 { png[29 + index] = UInt8((crc >> (24 - index * 8)) & 255) }
            XCTAssertThrowsError(try ClipImageSafety.validate(Data(png), mime: "image/png")) { error in
                XCTAssertEqual(error as? ClipImageSafety.InvalidImage, .dimensions)
            }
        }
    }
}

extension ClipPayloadTests {
    func testImageBudgetIsFiftyMiBAndEnvelopeCoversBase64WithoutChangingText() throws {
        XCTAssertEqual(ClipPayload.maxImageBytes, 52_428_800)
        XCTAssertEqual(ClipPayload.maxJSONBytes, 69_909_164)
        XCTAssertEqual(ClipPayload.maxTextBytes, 1_048_576)
        XCTAssertEqual(ClipImageSafety.maxClipboardSourceBytes, ClipPayload.maxImageBytes)
        // Verify exact boundaries without allocating a full decoded image or JSON copies.
        try ClipImageSafety.validateEncodedSize(8 * 1024 * 1024 + 1)
        try ClipImageSafety.validateEncodedSize(ClipPayload.maxImageBytes)
        for count in [0, -1, ClipPayload.maxImageBytes + 1, Int.max] {
            XCTAssertThrowsError(try ClipImageSafety.validateEncodedSize(count)) { error in
                XCTAssertEqual(error as? ClipImageSafety.InvalidImage, .encodedSize)
            }
        }
    }

    func testPngConsumerAcceptsExactCapacityThenLatchesOverflow() {
        // Exercise the actual ImageIO consumer implementation at a small injected limit.
        let sink = ClipImageSafety.BoundedOutput(limit: 4)
        let input: [UInt8] = [1, 2, 3, 4, 5]
        input.withUnsafeBufferPointer { bytes in
            XCTAssertEqual(sink.write(bytes.baseAddress!, count: 4), 4)
            XCTAssertEqual(sink.data, Data([1, 2, 3, 4]))
            XCTAssertFalse(sink.overflow)
            XCTAssertEqual(sink.write(bytes.baseAddress!, count: 1), 0)
            XCTAssertTrue(sink.overflow)
            XCTAssertEqual(sink.write(bytes.baseAddress!, count: 0), 0)
            XCTAssertTrue(sink.overflow)
            XCTAssertEqual(sink.data.count, 4)
        }
    }
}
