import Foundation
import CryptoKit
import Logging

enum PairingError: Error, Equatable {
    case notStarted
    case invalid
    case expired
    case consumed
    case randomFailure
    case rateLimited
}

struct PairingResponse: Codable, Sendable {
    let token: String
    let sig: String
    /// Base64-encoded pairing-secret shared with the client so it can sign
    /// `POST /inject` requests with the same HMAC key the server validates.
    /// Confidentiality is preserved by TLS (self-signed cert pinned via SPKI).
    let secret: String
}

struct PairingSession: Sendable {
    let id: String
    let code: String
    let qrSecret: String
    let expiresAt: Date

    func url(hostname: String, port: Int, fingerprint: String) -> String {
        var components = URLComponents()
        components.scheme = "clipsync"
        components.host = "pair"
        components.queryItems = [URLQueryItem(name: "v", value: "2"),
            URLQueryItem(name: "host", value: hostname), URLQueryItem(name: "port", value: String(port)),
            URLQueryItem(name: "fp", value: fingerprint), URLQueryItem(name: "secret", value: qrSecret)]
        return components.string ?? ""
    }
}

protocol PairingClock: Sendable {
    func now() -> Date
}

struct SystemPairingClock: PairingClock {
    func now() -> Date { Date() }
}

actor PairingManager {
    private struct ActiveCode {
        let id: String
        let code: String
        let qrSecret: String
        let createdAt: Date
        var attempts = 0
        var consumed: Bool = false
    }

    private let secret: Data
    private let ttl: TimeInterval
    private let clock: PairingClock
    private var active: ActiveCode?
    private var logger: Logger

    init(secret: Data,
         ttl: TimeInterval = 300,
         clock: PairingClock = SystemPairingClock(),
         logger: Logger = Logger(label: "clipsync.pairing")) {
        self.secret = secret
        self.ttl = ttl
        self.clock = clock
        self.logger = logger
    }

    func startPairing() throws -> PairingSession {
        try start(id: UUID().uuidString)
    }

    private func start(id: String) throws -> PairingSession {
        guard secret.count == 32, ttl > 0, ttl <= 300 else { throw PairingError.invalid }
        let code = try Self.generate6DigitCode()
        let qr = try Self.randomBytes(count: 32).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        active = ActiveCode(id: id, code: code, qrSecret: qr, createdAt: clock.now())
        return session(active!)
    }

    func refreshPairing(sessionID: String) throws -> PairingSession {
        guard active?.id == sessionID else { throw PairingError.notStarted }
        return try start(id: sessionID)
    }

    func cancel(sessionID: String? = nil) {
        if sessionID == nil || active?.id == sessionID { active = nil }
    }

    private func session(_ active: ActiveCode) -> PairingSession {
        PairingSession(id: active.id, code: active.code, qrSecret: active.qrSecret,
            expiresAt: active.createdAt.addingTimeInterval(ttl))
    }

    func currentSession() -> PairingSession? {
        guard let active, !active.consumed else { return nil }
        let age = clock.now().timeIntervalSince(active.createdAt)
        guard age >= 0 && age < ttl else { return nil }
        return session(active)
    }

    func consume(code: String) throws -> PairingResponse { try consume(candidate: code, qr: false) }
    func consume(secret: String) throws -> PairingResponse { try consume(candidate: secret, qr: true) }

    private func consume(candidate: String, qr: Bool) throws -> PairingResponse {
        guard let current = active else { throw PairingError.notStarted }
        let age = clock.now().timeIntervalSince(current.createdAt)
        guard age >= 0 && age < ttl else { active = nil; throw PairingError.expired }
        guard !current.consumed else { throw PairingError.consumed }
        guard current.attempts < 10 else { throw PairingError.rateLimited }
        active?.attempts += 1
        let expected = qr ? current.qrSecret : current.code
        guard HMACValidator.constantTimeEquals(candidate, expected) else { throw PairingError.invalid }
        active?.consumed = true
        let tokenBytes = try Self.randomBytes(count: 32)
        let signature = HMAC<SHA256>.authenticationCode(for: tokenBytes, using: SymmetricKey(data: secret))
        return PairingResponse(token: tokenBytes.base64EncodedString(), sig: Data(signature).base64EncodedString(),
            secret: secret.base64EncodedString())
    }

    static func generate6DigitCode() throws -> String {
        var digits = ""
        while digits.count < 6 {
            var byte: UInt8 = 0
            let status = withUnsafeMutableBytes(of: &byte) { buffer -> Int32 in
                guard let base = buffer.baseAddress else { return errSecParam }
                return SecRandomCopyBytes(kSecRandomDefault, 1, base)
            }
            guard status == errSecSuccess else { throw PairingError.randomFailure }
            if byte < 250 {
                digits += String(byte % 10)
            }
        }
        return digits
    }

    static func randomBytes(count: Int) throws -> Data {
        var buffer = [UInt8](repeating: 0, count: count)
        let status = SecRandomCopyBytes(kSecRandomDefault, count, &buffer)
        guard status == errSecSuccess else { throw PairingError.randomFailure }
        return Data(buffer)
    }

    static func fingerprint(of secret: Data, hexLength: Int = 16) -> String {
        let digest = SHA256.hash(data: secret)
        let hex = digest.map { String(format: "%02x", $0) }.joined()
        return String(hex.prefix(hexLength))
    }
}
