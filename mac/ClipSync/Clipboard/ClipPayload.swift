import Foundation
import CoreFoundation

enum ClipKind: String, Codable, Sendable {
    case text
    case image
    case file
}

struct ClipPayload: Codable, Sendable, Equatable {
    let type: ClipKind
    let mime: String
    let dataBase64: String
    let ts: Int64
    let nonce: String
    let name: String?

    // Wire protocol uses "data" as the key; the Swift property is named
    // dataBase64 for clarity. CodingKeys bridges the two.
    enum CodingKeys: String, CodingKey {
        case type
        case mime
        case dataBase64 = "data"
        case ts
        case nonce
        case name
    }

    init(type: ClipKind, mime: String, dataBase64: String, ts: Int64, nonce: String, name: String? = nil) {
        self.type = type
        self.mime = mime
        self.dataBase64 = dataBase64
        self.ts = ts
        self.nonce = nonce
        self.name = name
    }

    static func currentTimestampMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }

    static func newNonce() -> String {
        UUID().uuidString
    }

    static func text(_ value: String, ts: Int64 = ClipPayload.currentTimestampMillis()) -> ClipPayload {
        let data = Data(value.utf8)
        return ClipPayload(
            type: .text,
            mime: "text/plain",
            dataBase64: data.base64EncodedString(),
            ts: ts,
            nonce: newNonce()
        )
    }

    static func image(_ data: Data, mime: String = "image/png", ts: Int64 = ClipPayload.currentTimestampMillis()) -> ClipPayload {
        ClipPayload(
            type: .image,
            mime: mime,
            dataBase64: data.base64EncodedString(),
            ts: ts,
            nonce: newNonce()
        )
    }

    static func file(_ data: Data, name: String, mime: String = "application/octet-stream", ts: Int64 = ClipPayload.currentTimestampMillis()) -> ClipPayload {
        ClipPayload(
            type: .file,
            mime: mime,
            dataBase64: data.base64EncodedString(),
            ts: ts,
            nonce: newNonce(),
            name: name
        )
    }

    static let maxTextBytes = 1024 * 1024
    static let maxImageBytes = 50 * 1024 * 1024
    static let maxJSONBytes = ((maxImageBytes + 2) / 3) * 4 + 4096

    static func decodeValidated(_ data: Data, nowMs: Int64 = currentTimestampMillis()) throws -> ClipPayload {
        try decodePrepared(data, nowMs: nowMs).payload
    }

    static func decodePrepared(_ data: Data, nowMs: Int64 = currentTimestampMillis()) throws -> PreparedClip {
        guard data.count <= maxJSONBytes,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let timestamp = object["ts"] as? NSNumber,
              CFGetTypeID(timestamp) != CFBooleanGetTypeID(),
              !["f", "d"].contains(String(cString: timestamp.objCType)) else {
            throw ValidationError.timestampOutOfRange
        }
        for key in ["type", "mime", "data", "nonce"] {
            guard object[key] is String else { throw ValidationError.invalidBase64 }
        }
        if let name = object["name"], !(name is String) { throw ValidationError.invalidName }
        let payload = try JSONDecoder().decode(Self.self, from: data)
        return try PreparedClip(payload, nowMs: nowMs)
    }

    var rawData: Data? { Data(base64Encoded: dataBase64) }

    func validate(nowMs: Int64 = ClipPayload.currentTimestampMillis()) throws {
        _ = try validatedData(nowMs: nowMs)
    }

    func validatedData(nowMs: Int64 = ClipPayload.currentTimestampMillis()) throws -> Data {
        guard type == .text || type == .image else { throw ValidationError.invalidType(type.rawValue) }
        guard (type == .text && mime == "text/plain") ||
              (type == .image && ["image/png", "image/jpeg"].contains(mime)) else { throw ValidationError.invalidMime }
        let validNonce = nonce.utf8.allSatisfy { (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 45 || $0 == 95 }
        guard !nonce.isEmpty, nonce.utf8.count <= 128, validNonce else { throw ValidationError.missingNonce }
        if let name {
            guard !name.isEmpty, name.utf16.count <= 255, name != ".", name != "..",
                  !name.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 || $0 == "/" || $0 == "\\" }) else {
                throw ValidationError.invalidName
            }
        }
        // Non-negative operands make either ordered subtraction safe even at Int64.max.
        guard ts >= 0, nowMs >= 0, (ts > nowMs ? ts - nowMs : nowMs - ts) < 300_000 else {
            throw ValidationError.timestampOutOfRange
        }
        let limit = type == .text ? Self.maxTextBytes : Self.maxImageBytes
        guard dataBase64.utf8.count <= ((limit + 2) / 3) * 4,
              let bytes = Data(base64Encoded: dataBase64), bytes.count <= limit,
              bytes.base64EncodedString() == dataBase64 else { throw ValidationError.invalidBase64 }
        if type == .text {
            guard String(data: bytes, encoding: .utf8) != nil else { throw ValidationError.invalidUTF8 }
        } else {
            try ClipImageSafety.validate(bytes, mime: mime)
        }
        return bytes
    }

    enum ValidationError: Error {
        case invalidType(String), invalidMime, missingNonce, invalidName, invalidBase64, invalidUTF8
        case timestampOutOfRange
    }
}

/// Immutable proof that wire metadata and content passed all bounds and format checks.
/// Construct off the UI actor; application uses these exact bytes without decoding again.
struct PreparedClip: Sendable {
    let payload: ClipPayload
    let bytes: Data

    init(_ payload: ClipPayload, nowMs: Int64 = ClipPayload.currentTimestampMillis()) throws {
        let bytes = try payload.validatedData(nowMs: nowMs)
        self.payload = payload
        self.bytes = bytes
    }
}
