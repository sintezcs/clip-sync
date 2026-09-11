import Foundation
import XCTest
@testable import ClipSync

final class ReplayJournalTests: XCTestCase {
    private var directory: URL!
    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("clipsync-replay-test-\(UUID())")
    }
    override func tearDownWithError() throws { try? FileManager.default.removeItem(at: directory) }

    func testRestartRejectsDuplicateAndConflictingNonceWithoutStoringContent() async throws {
        let first = ReplayJournal(directory: directory)
        let time = Date(timeIntervalSince1970: 1000)
        let accepted = try await first.accept(peer: "private-token", nonce: "private-nonce", payload: Data("private-clipboard".utf8), now: time)
        XCTAssertEqual(accepted, .accepted)
        let restored = ReplayJournal(directory: directory)
        let duplicate = try await restored.accept(peer: "private-token", nonce: "private-nonce", payload: Data("private-clipboard".utf8), now: time)
        let conflict = try await restored.accept(peer: "private-token", nonce: "private-nonce", payload: Data("different".utf8), now: time)
        XCTAssertEqual(duplicate, .duplicate)
        XCTAssertEqual(conflict, .conflict)
        let file = try Data(contentsOf: directory.appendingPathComponent("events-v1.json"))
        let envelope = try JSONDecoder().decode(ReplayJournal.Envelope.self, from: file)
        XCTAssertFalse(String(decoding: envelope.body, as: UTF8.self).contains("private-"))
    }

    func testCapacityCannotEvictAndExpiryAllowsNewEvents() async throws {
        let journal = ReplayJournal(directory: directory, capacity: 1, retention: 10)
        _ = try await journal.accept(peer: "peer", nonce: "a", payload: Data(), now: Date(timeIntervalSince1970: 100))
        do {
            _ = try await journal.accept(peer: "peer", nonce: "b", payload: Data(), now: Date(timeIntervalSince1970: 101))
            XCTFail("Full journal must reject before effects")
        } catch ReplayJournal.Failure.full { }
        let accepted = try await journal.accept(peer: "peer", nonce: "b", payload: Data(), now: Date(timeIntervalSince1970: 110))
        XCTAssertEqual(accepted, .accepted)
    }

    func testCorruptionFailsClosedAcrossRecreation() async throws {
        let journal = ReplayJournal(directory: directory)
        _ = try await journal.accept(peer: "p", nonce: "n", payload: Data())
        try Data("broken".utf8).write(to: directory.appendingPathComponent("events-v1.json"))
        let restored = ReplayJournal(directory: directory)
        do { _ = try await restored.accept(peer: "p", nonce: "n", payload: Data()); XCTFail("Corruption accepted") }
        catch { }
        try FileManager.default.removeItem(at: directory.appendingPathComponent("events-v1.json"))
        do { _ = try await restored.accept(peer: "p", nonce: "n", payload: Data()); XCTFail("Failure did not latch") }
        catch { }
    }

    func testUnwritableDestinationPreventsAcceptance() async throws {
        try Data("not-a-directory".utf8).write(to: directory)
        let journal = ReplayJournal(directory: directory)
        do { _ = try await journal.accept(peer: "p", nonce: "n", payload: Data()); XCTFail("Invalid storage accepted") }
        catch { }
    }

    func testPeerScopeAndBackwardClock() async throws {
        let journal = ReplayJournal(directory: directory)
        let time = Date(timeIntervalSince1970: 1000)
        _ = try await journal.accept(peer: "a", nonce: "n", payload: Data(), now: time)
        let other = try await journal.accept(peer: "b", nonce: "n", payload: Data(), now: time)
        XCTAssertEqual(other, .accepted)
        let restored = ReplayJournal(directory: directory)
        do { _ = try await restored.accept(peer: "a", nonce: "new", payload: Data(), now: time.addingTimeInterval(-1)); XCTFail("Clock rollback accepted") }
        catch { }
    }
}
