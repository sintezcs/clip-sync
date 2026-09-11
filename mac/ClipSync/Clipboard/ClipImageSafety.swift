import Foundation
import ImageIO
import UniformTypeIdentifiers

enum ClipImageSafety {
    enum InvalidImage: Error, Equatable { case unsupported, dimensions, corrupt, encodedSize }
    static let maxClipboardSourceBytes = 32 * 1024 * 1024

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
            guard count <= ClipPayload.maxImageBytes - output.data.count else {
                output.overflow = true
                return 0
            }
            output.data.append(buffer.assumingMemoryBound(to: UInt8.self), count: count)
            return count
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

    private final class BoundedOutput { var data = Data(); var overflow = false }

    private static func checkedSource(_ data: Data, type: String, maxBytes: Int) throws -> CGImageSource {
        guard !data.isEmpty, data.count <= maxBytes,
              let source = CGImageSourceCreateWithData(data as CFData,
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
