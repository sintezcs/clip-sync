import Foundation

actor RateLimiter {
    private struct Bucket { var dates: [Date]; var expiresAt: Date }
    private var requests: [String: Bucket] = [:]
    private let maxKeys: Int
    private let clock: @Sendable () -> Date
    init(maxKeys: Int = 1024, clock: @escaping @Sendable () -> Date = { Date() }) {
        self.maxKeys = maxKeys; self.clock = clock
    }
    func allow(key: String, maxRequests: Int, windowSeconds: TimeInterval) -> Bool {
        guard key.utf8.count <= 256, maxRequests > 0, maxRequests <= 1000,
              windowSeconds > 0, windowSeconds <= 300 else { return false }
        let now = clock()
        requests = requests.filter { $0.value.expiresAt > now }
        guard requests[key] != nil || requests.count < maxKeys else { return false }
        let cutoff = now.addingTimeInterval(-windowSeconds)
        var dates = requests[key]?.dates.filter { $0 > cutoff } ?? []
        guard dates.count < maxRequests else { return false }
        dates.append(now)
        requests[key] = Bucket(dates: dates, expiresAt: now.addingTimeInterval(windowSeconds))
        return true
    }
    func reset(key: String) { requests.removeValue(forKey: key) }
}
