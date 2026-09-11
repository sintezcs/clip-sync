import CryptoKit
import Darwin
import Foundation

enum ReplayDecision: Equatable { case accepted, duplicate, conflict }

/// A durable acceptance record, not a claim that a clipboard write survived a crash.
/// No plaintext peer credentials, clipboard bytes, or nonces are written to disk.
actor ReplayJournal {
    struct Record: Codable { let digest: String; let expires: Double }
    struct Snapshot: Codable { let version: Int; let written: Double; let records: [String: Record] }
    struct Envelope: Codable { let body: Data; let checksum: String }
    enum Failure: Error { case unavailable, full, invalidClock, corrupt }
    private let directory: URL
    private let capacity: Int
    private let retention: TimeInterval
    private var snapshot: Snapshot?
    private var failed = false
    private var file: URL { directory.appendingPathComponent("events-v1.json") }

    init(directory: URL, capacity: Int = 4096, retention: TimeInterval = 600) {
        precondition((1...4096).contains(capacity) && retention > 0 && retention <= 600)
        self.directory = directory; self.capacity = capacity; self.retention = retention
    }

    static var applicationDirectory: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("ClipSync/Replay", isDirectory: true)
    }

    func accept(peer: String, nonce: String, payload: Data, now: Date = Date()) throws -> ReplayDecision {
        guard !failed else { throw Failure.unavailable }
        do {
            let time = now.timeIntervalSince1970
            guard time.isFinite && time >= 0 else { throw Failure.invalidClock }
            let current = try snapshot ?? load()
            guard time >= current.written else { throw Failure.invalidClock }
            let key = Self.hash(Self.framed(["clipsync-replay-v1", peer, nonce]))
            let digest = Self.hash(payload)
            var records = current.records.filter { $0.value.expires > time }
            if let previous = records[key] {
                return previous.digest == digest ? .duplicate : .conflict
            }
            guard records.count < capacity else { throw Failure.full }
            records[key] = Record(digest: digest, expires: time + retention)
            let next = Snapshot(version: 1, written: time, records: records)
            try persist(next)
            snapshot = next
            return .accepted
        } catch Failure.full { throw Failure.full }
        catch { failed = true; throw error }
    }

    private func load() throws -> Snapshot {
        guard FileManager.default.fileExists(atPath: file.path) else {
            return Snapshot(version: 1, written: 0, records: [:])
        }
        let input = try FileHandle(forReadingFrom: file)
        defer { try? input.close() }
        let data = try input.read(upToCount: 1_500_001) ?? Data()
        guard data.count <= 1_500_000 else { throw Failure.corrupt }
        let envelope = try JSONDecoder().decode(Envelope.self, from: data)
        guard Self.hash(envelope.body) == envelope.checksum else { throw Failure.corrupt }
        let decoded = try JSONDecoder().decode(Snapshot.self, from: envelope.body)
        guard decoded.version == 1, decoded.written.isFinite, decoded.written >= 0,
              decoded.records.count <= capacity,
              decoded.records.allSatisfy({ Self.isHash($0.key) && Self.isHash($0.value.digest)
                  && $0.value.expires.isFinite && $0.value.expires > 0 }) else { throw Failure.corrupt }
        return decoded
    }

    private func persist(_ value: Snapshot) throws {
        try ensureDirectory(directory)
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        let body = try encoder.encode(value)
        let data = try encoder.encode(Envelope(body: body, checksum: Self.hash(body)))
        let temporary = directory.appendingPathComponent("events-v1.tmp")
        defer { try? FileManager.default.removeItem(at: temporary) }
        let descriptor = Darwin.open(temporary.path, O_WRONLY | O_CREAT | O_TRUNC | O_NOFOLLOW, S_IRUSR | S_IWUSR)
        guard descriptor >= 0 else { throw Failure.unavailable }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        do {
            try handle.write(contentsOf: data)
            try handle.synchronize()
            try handle.close()
        } catch { try? handle.close(); throw error }
        guard Darwin.rename(temporary.path, file.path) == 0 else { throw Failure.unavailable }
        try syncDirectory(directory)
    }

    private func ensureDirectory(_ url: URL) throws {
        var isDirectory: ObjCBool = false
        if FileManager.default.fileExists(atPath: url.path, isDirectory: &isDirectory) {
            guard isDirectory.boolValue else { throw Failure.unavailable }
            return
        }
        let parent = url.deletingLastPathComponent()
        guard parent.path != url.path else { throw Failure.unavailable }
        try ensureDirectory(parent)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: false,
                                               attributes: [.posixPermissions: 0o700])
        try syncDirectory(url)
        try syncDirectory(parent)
    }

    private func syncDirectory(_ url: URL) throws {
        let descriptor = Darwin.open(url.path, O_RDONLY | O_DIRECTORY)
        guard descriptor >= 0 else { throw Failure.unavailable }
        defer { Darwin.close(descriptor) }
        guard Darwin.fsync(descriptor) == 0 else { throw Failure.unavailable }
    }

    private static func hash(_ data: Data) -> String { SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined() }
    private static func isHash(_ value: String) -> Bool { value.count == 64 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) } }
    private static func framed(_ values: [String]) -> Data {
        var data = Data()
        for value in values {
            let bytes = Data(value.utf8)
            var length = UInt64(bytes.count).bigEndian
            withUnsafeBytes(of: &length) { data.append(contentsOf: $0) }
            data.append(bytes)
        }
        return data
    }
}
