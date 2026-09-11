import Foundation

/// A new change count is a deliberate copy, even if its bytes match the previous item.
struct ClipboardChangeOrder {
    private(set) var baseline: Int
    private var lastLocalMs: Int64 = -1
    private var lastRemoteMs: Int64 = -1
    init(baseline: Int) { self.baseline = baseline }
    mutating func observeLocal(count: Int, nowMs: Int64) -> Bool {
        guard count != baseline else { return false }
        baseline = count
        lastLocalMs = nowMs
        return true
    }
    func permitsRemote(timestamp: Int64) -> Bool { timestamp > lastLocalMs && timestamp >= lastRemoteMs }
    mutating func acknowledgeRemote(timestamp: Int64, exactOwnedCount: Int?) {
        lastRemoteMs = timestamp
        if let exactOwnedCount { baseline = exactOwnedCount }
    }
    mutating func baselineOnly(_ count: Int) { baseline = count }
}
