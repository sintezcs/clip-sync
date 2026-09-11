#!/usr/bin/env swift
import AppKit
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// This deliberately operates on the system clipboard only after explicit authorization.
// It never logs, saves, or restores unrelated clipboard content.
enum SmokeError: Error {
    case timedOut(String)
    case imageCreation
    case clipboardWrite
}

func stage(_ name: String) {
    print(name)
    fflush(stdout)
}

func waitFor(_ name: String, seconds: TimeInterval, predicate: () -> Bool) throws {
    stage("Waiting: \(name)")
    let deadline = ProcessInfo.processInfo.systemUptime + seconds
    while ProcessInfo.processInfo.systemUptime < deadline {
        if autoreleasepool(invoking: predicate) { return }
        Thread.sleep(forTimeInterval: 0.1)
    }
    throw SmokeError.timedOut(name)
}

let rgbaInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue

func hasPixels(_ data: Data, rgba: [UInt8], tolerance: Int = 0) -> Bool {
    guard rgba.count == 4, data.count <= 1024 * 1024,
          let source = CGImageSourceCreateWithData(data as CFData, nil),
          CGImageSourceGetCount(source) == 1,
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          properties[kCGImagePropertyPixelWidth] as? Int == 8,
          properties[kCGImagePropertyPixelHeight] as? Int == 8,
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
          image.width == 8, image.height == 8,
          let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else { return false }
    var bytes = [UInt8](repeating: 0, count: 8 * 8 * 4)
    let rendered = bytes.withUnsafeMutableBytes { buffer -> Bool in
        guard let context = CGContext(data: buffer.baseAddress, width: 8, height: 8,
            bitsPerComponent: 8, bytesPerRow: 32, space: colorSpace, bitmapInfo: rgbaInfo) else { return false }
        context.setBlendMode(.copy)
        context.draw(image, in: CGRect(x: 0, y: 0, width: 8, height: 8))
        return true
    }
    return rendered && (0..<64).allSatisfy { pixel in
        (0..<4).allSatisfy { abs(Int(bytes[pixel * 4 + $0]) - Int(rgba[$0])) <= tolerance }
    }
}

func makeRedPNG() throws -> Data {
    guard let colorSpace = CGColorSpace(name: CGColorSpace.sRGB) else { throw SmokeError.imageCreation }
    var pixels: [UInt8] = (0..<64).flatMap { _ -> [UInt8] in [255, 0, 0, 255] }
    let image = pixels.withUnsafeMutableBytes { buffer -> CGImage? in
        let context = CGContext(data: buffer.baseAddress, width: 8, height: 8,
            bitsPerComponent: 8, bytesPerRow: 32, space: colorSpace, bitmapInfo: rgbaInfo)
        return context?.makeImage()
    }
    guard let image else { throw SmokeError.imageCreation }
    let output = NSMutableData()
    guard let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil) else {
        throw SmokeError.imageCreation
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw SmokeError.imageCreation }
    let png = output as Data
    guard hasPixels(png, rgba: [255, 0, 0, 255]) else { throw SmokeError.imageCreation }
    return png
}

func runSmoke(heicImage: Bool) throws {
    let pasteboard = NSPasteboard.general
    let initialChangeCount = pasteboard.changeCount
    let red = try makeRedPNG()
    try waitFor("Android ready", seconds: 60) {
        pasteboard.changeCount != initialChangeCount &&
            pasteboard.string(forType: .string) == "LAN_ANDROID_READY"
    }
    Thread.sleep(forTimeInterval: 2)
    stage("Sending: Mac text")
    pasteboard.clearContents()
    guard pasteboard.setString("LAN_MAC_TEXT_Ω", forType: .string) else { throw SmokeError.clipboardWrite }
    try waitFor("Android text", seconds: 40) { pasteboard.string(forType: .string) == "LAN_ANDROID_TEXT_Ω" }
    // Allow Android's trigger-writing Activity to transition to CREATED before this response.
    Thread.sleep(forTimeInterval: 2)
    stage("Sending: Mac red PNG")
    pasteboard.clearContents()
    guard pasteboard.setData(red, forType: .png) else { throw SmokeError.clipboardWrite }
    try waitFor(heicImage ? "Android HEIC converted to PNG" : "Android blue PNG", seconds: 40) {
        let imageType = NSPasteboard.PasteboardType.png
        guard let bytes = pasteboard.data(forType: imageType) else { return false }
        return hasPixels(bytes, rgba: heicImage ? [255, 255, 255, 255] : [0, 0, 255, 255], tolerance: 0)
    }
    Thread.sleep(forTimeInterval: 2)
    stage("Sending: completion marker")
    pasteboard.clearContents()
    guard pasteboard.setString("LAN_MAC_COMPLETE", forType: .string) else { throw SmokeError.clipboardWrite }
    stage(heicImage ? "Success: LAN text, Mac PNG and Android HEIC converted to PNG; completion marker left on Mac clipboard" : "Success: LAN bidirectional text and PNG; completion marker left on Mac clipboard")
}

let arguments = Array(CommandLine.arguments.dropFirst())
if arguments != ["--confirm-system-clipboard"] && arguments != ["--confirm-system-clipboard", "--heic-image"] {
    fputs("Usage: native-lan-smoke --confirm-system-clipboard [--heic-image]\n", stderr)
    exit(2)
}

do {
    try runSmoke(heicImage: arguments.contains("--heic-image"))
} catch SmokeError.timedOut(let name) {
    fputs("Failed: timed out waiting for \(name)\n", stderr)
    exit(1)
} catch {
    fputs("Failed: synthetic image creation or clipboard write\n", stderr)
    exit(1)
}
