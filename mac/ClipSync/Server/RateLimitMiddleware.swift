import Foundation
import HTTPTypes
import Hummingbird
import NIOCore

protocol ConnectionAddressContext { var remoteAddress: String { get } }
struct ClipRequestContext: RequestContext, ConnectionAddressContext {
    var coreContext: CoreRequestContextStorage
    let remoteAddress: String
    init(source: ApplicationRequestContextSource) {
        coreContext = .init(source: source)
        remoteAddress = source.channel.remoteAddress?.ipAddress ?? "unknown"
    }
}

struct RateLimitMiddleware<Context: RequestContext>: RouterMiddleware {
    let rateLimiter: RateLimiter
    let maxInjectBodyBytes: Int
    init(rateLimiter: RateLimiter, maxInjectBodyBytes: Int = ClipPayload.maxJSONBytes) {
        self.rateLimiter = rateLimiter; self.maxInjectBodyBytes = maxInjectBodyBytes
    }
    func handle(_ request: Request, context: Context,
                next: (Request, Context) async throws -> Response) async throws -> Response {
        let path = request.uri.path
        guard path == "/inject" || path == "/pair" else { return try await next(request, context) }
        // Only the actual socket address is authoritative. Forwarding headers are intentionally ignored.
        let clientIP = (context as? ConnectionAddressContext)?.remoteAddress ?? "unknown"
        let pairing = path == "/pair"
        guard await rateLimiter.allow(key: "\(path):global", maxRequests: pairing ? 20 : 30, windowSeconds: pairing ? 60 : 1),
              await rateLimiter.allow(key: "\(path):\(clientIP)", maxRequests: pairing ? 5 : 10, windowSeconds: pairing ? 60 : 1) else {
            throw HTTPError(.tooManyRequests)
        }
        if let raw = request.headers[.contentLength] {
            guard let length = Int(raw), length >= 0 else { throw HTTPError(.badRequest) }
            guard length <= (pairing ? 1024 : maxInjectBodyBytes) else { throw HTTPError(.contentTooLarge) }
        }
        return try await next(request, context)
    }
}
