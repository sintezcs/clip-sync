import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ClipImageSafety {
    enum InvalidImage: Error, Equatable { case unsupported, dimensions, corrupt, encodedSize }
    static let maxClipboardSourceBytes = ClipPayload.maxImageBytes

    /// Inspect dimensions before any full decode; accept only the declared wire format.
    static func validate(_ data: Data, mime: String) throws {
        let type: String
        switch mime {
        case "image/png": type = UTType.png.identifier
        case "image/jpeg": type = UTType.jpeg.identifier
        default: throw InvalidImage.unsupported
        }
        let source = try checkedSource(data, type: type, maxBytes: ClipPayload.maxImageBytes)
        _ = try decodedImage(source)
    }

    /// TIFF is a local macOS pasteboard source only. The resulting wire representation is bounded PNG.
    static func clipboardTIFFToPNG(_ data: Data) throws -> Data {
        let source = try checkedSource(data, type: UTType.tiff.identifier, maxBytes: maxClipboardSourceBytes)
        let image = try decodedImage(source)
        let sink = BoundedOutput()
        let retained = Unmanaged.passRetained(sink).toOpaque()
        var callbacks = CGDataConsumerCallbacks(putBytes: { info, buffer, count in
            guard let info else { return 0 }
            let output = Unmanaged<BoundedOutput>.fromOpaque(info).takeUnretainedValue()
            return output.write(buffer.assumingMemoryBound(to: UInt8.self), count: count)
        }, releaseConsumer: { info in
            if let info { Unmanaged<BoundedOutput>.fromOpaque(info).release() }
        })
        guard let consumer = CGDataConsumer(info: retained, cbks: &callbacks) else {
            Unmanaged<BoundedOutput>.fromOpaque(retained).release()
            throw InvalidImage.corrupt
        }
        guard let destination = CGImageDestinationCreateWithDataConsumer(consumer, UTType.png.identifier as CFString, 1, nil) else {
            throw InvalidImage.corrupt
        }
        CGImageDestinationAddImage(destination, image, nil)
        guard CGImageDestinationFinalize(destination), !sink.overflow, !sink.data.isEmpty else { throw InvalidImage.encodedSize }
        return sink.data
    }

    /// The consumer never appends bytes past the encoded cap, including after a failed write.
    final class BoundedOutput {
        private let limit: Int
        private(set) var data = Data()
        private(set) var overflow = false
        init(limit: Int = ClipPayload.maxImageBytes) {
            precondition((1...ClipPayload.maxImageBytes).contains(limit))
            self.limit = limit
        }
        func write(_ bytes: UnsafePointer<UInt8>, count: Int) -> Int {
            guard !overflow, count >= 0, count <= limit - data.count else {
                overflow = true
                return 0
            }
            data.append(bytes, count: count)
            return count
        }
    }

    static func validateEncodedSize(_ count: Int, maximum: Int = ClipPayload.maxImageBytes) throws {
        guard count > 0, count <= maximum else { throw InvalidImage.encodedSize }
    }

    private static func checkedSource(_ data: Data, type: String, maxBytes: Int) throws -> CGImageSource {
        try validateEncodedSize(data.count, maximum: maxBytes)
        guard let source = CGImageSourceCreateWithData(data as CFData,
                [kCGImageSourceShouldCache: false] as CFDictionary),
              CGImageSourceGetCount(source) == 1,
              CGImageSourceGetType(source) as String? == type else { throw InvalidImage.unsupported }
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? NSNumber,
              let height = properties[kCGImagePropertyPixelHeight] as? NSNumber else { throw InvalidImage.dimensions }
        let w = width.doubleValue, h = height.doubleValue
        guard w.isFinite, h.isFinite, w >= 1, h >= 1, w <= 8192, h <= 8192,
              w.rounded() == w, h.rounded() == h, w * h <= 24_000_000 else { throw InvalidImage.dimensions }
        return source
    }

    private static func decodedImage(_ source: CGImageSource) throws -> CGImage {
        guard let image = CGImageSourceCreateImageAtIndex(source, 0,
            [kCGImageSourceShouldCache: false, kCGImageSourceShouldCacheImmediately: false] as CFDictionary),
              CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete else { throw InvalidImage.corrupt }
        return image
    }
}
