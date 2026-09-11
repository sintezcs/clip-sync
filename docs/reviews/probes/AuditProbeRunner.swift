@main struct AuditProbe {
 static func main() async throws {
  if CommandLine.arguments.contains("--extreme-timestamp") {
   let p = ClipPayload(type: .text, mime: "text/plain", dataBase64: "QQ==", ts: Int64.min, nonce: "audit")
   try p.validate()
   return
  }
  let body = Data("audit-only".utf8)
  let secret = Data(repeating: 7, count: 32)
  let validator = HMACValidator(secret: secret)
  let header = HMACValidator.sign(body: body, secret: secret, at: Int(Date().timeIntervalSince1970))
  try validator.validate(headerValue: header, body: body)
  try validator.validate(headerValue: header, body: body)
  print("REPLAY: identical signed body accepted twice")
  let limiter = RateLimiter()
  var fixed = 0
  for _ in 0..<10 { if await limiter.allow(key: "pair:fixed", maxRequests: 5, windowSeconds: 60) { fixed += 1 } }
  var rotated = 0
  for i in 0..<10 { if await limiter.allow(key: "pair:spoofed-\(i)", maxRequests: 5, windowSeconds: 60) { rotated += 1 } }
  print("RATE LIMIT: fixed key \(fixed)/10; changing caller-supplied key \(rotated)/10")
  let root = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("klippa-path-probe-" + UUID().uuidString)
  defer { try? FileManager.default.removeItem(at: root) }
  let clipSyncDir = root.appendingPathComponent("Documents/ClipSync")
  try FileManager.default.createDirectory(at: clipSyncDir, withIntermediateDirectories: true)
  let fileName = "../../outside-clipboard-directory.txt"
  let destURL = clipSyncDir.appendingPathComponent(fileName)
  try Data("audit marker".utf8).write(to: destURL)
  let escaped = root.appendingPathComponent("outside-clipboard-directory.txt")
  print("PATH: outside intended directory = \(FileManager.default.fileExists(atPath: escaped.path)) (temporary sandbox only)")
 }
}
