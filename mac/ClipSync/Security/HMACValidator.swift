import Foundation
import CryptoKit

enum HMACValidationError: Error, Equatable {
    case missingHeader
    case malformedHeader
    case unknownVersion
    case invalidTimestamp
    case replayOrSkew
    case invalidSignature
}

/// Clock abstraction for tests.
protocol HMACClock: Sendable {
    func now() -> Date
}

struct SystemHMACClock: HMACClock {
    func now() -> Date { Date() }
}

/// Validates request signatures of the form:
///
///     X-ClipSync-Signature: t=<unix_ts>, v1=<hex>
///
/// where `v1 == HMAC-SHA256(pairingSecret, "<ts>.<body>")` (hex-encoded).
///
/// Rejects timestamps skewed more than `skewSeconds` (default 60) from `now()`.
struct HMACValidator: Sendable {
    let secret: Data
    let clock: HMACClock
    let skewSeconds: TimeInterval

    init(secret: Data,
         clock: HMACClock = SystemHMACClock(),
         skewSeconds: TimeInterval = 60) {
        self.secret = secret
        self.clock = clock
        self.skewSeconds = skewSeconds
    }

    func validate(headerValue: String?, body: Data) throws {
        guard let headerValue, !headerValue.isEmpty else {
            throw HMACValidationError.missingHeader
        }
        let parsed = try Self.parseHeader(headerValue)
        let now = clock.now().timeIntervalSince1970
        guard now.isFinite, now >= 0, parsed.timestamp >= 0, skewSeconds > 0,
              abs(now - Double(parsed.timestamp)) < skewSeconds else {
            throw HMACValidationError.replayOrSkew
        }

        let signingString = "\(parsed.timestamp).".data(using: .utf8)! + body
        let mac = HMAC<SHA256>.authenticationCode(
            for: signingString,
            using: SymmetricKey(data: secret)
        )
        let expectedHex = Data(mac).map { String(format: "%02x", $0) }.joined()
        guard Self.constantTimeEquals(expectedHex.lowercased(), parsed.signatureHex.lowercased()) else {
            throw HMACValidationError.invalidSignature
        }
    }

    /// Produces a `X-ClipSync-Signature` header value for the given body at the given time.
    /// Exposed for tests and potential client libs.
    static func sign(body: Data, secret: Data, at timestamp: Int) -> String {
        let signingString = "\(timestamp).".data(using: .utf8)! + body
        let mac = HMAC<SHA256>.authenticationCode(
            for: signingString,
            using: SymmetricKey(data: secret)
        )
        let hex = Data(mac).map { String(format: "%02x", $0) }.joined()
        return "t=\(timestamp), v1=\(hex)"
    }

    struct ParsedHeader: Equatable {
        let timestamp: Int64
        let signatureHex: String
    }

    static func parseHeader(_ raw: String) throws -> ParsedHeader {
        guard raw.utf8.count <= 256 else { throw HMACValidationError.malformedHeader }
        var ts: Int64?
        var sig: String?
        let parts = raw.split(separator: ",")
        for part in parts {
            let trimmed = part.trimmingCharacters(in: .whitespaces)
            guard let equalsIdx = trimmed.firstIndex(of: "=") else {
                throw HMACValidationError.malformedHeader
            }
            let key = String(trimmed[..<equalsIdx])
            let value = String(trimmed[trimmed.index(after: equalsIdx)...])
            switch key {
            case "t":
                guard ts == nil, let parsed = Int64(value), parsed >= 0 else {
                    throw HMACValidationError.invalidTimestamp
                }
                ts = parsed
            case "v1":
                guard sig == nil, value.utf8.count == 64, value.utf8.allSatisfy({
                    (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0)
                }) else { throw HMACValidationError.malformedHeader }
                sig = value
            default:
                // Ignore unknown parameters to allow forward compatibility.
                continue
            }
        }
        guard let ts else { throw HMACValidationError.invalidTimestamp }
        guard let sig else { throw HMACValidationError.unknownVersion }
        return ParsedHeader(timestamp: ts, signatureHex: sig)
    }

    static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let aBytes = Array(a.utf8)
        let bBytes = Array(b.utf8)
        guard aBytes.count == bBytes.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<aBytes.count {
            diff |= aBytes[i] ^ bBytes[i]
        }
        return diff == 0
    }
}
