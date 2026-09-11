#!/usr/bin/env swift
import AppKit
import CoreGraphics
import CryptoKit
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Start this before LargeLanSmokeTest, using --confirm-system-clipboard explicitly.
// The Android test requires the existing user pairing and exact Fold serial/model/pin args.
// No listener, credential mutation, personal clipboard logging, or unrelated content restoration.
// Android instrumentation completion may kill its service: relaunch normal ClipSync afterwards.
enum LargeSmokeError: Error { case fixture, clipboardWrite, timedOut(String) }
let width = 2048, height = 1536
let oldLimit = 8 * 1024 * 1024, maxBytes = 50 * 1024 * 1024
let macSeed: UInt32 = 0x13579BDF, androidSeed: UInt32 = 0x2468ACE1
let readyMarker = "LARGE_LAN_ANDROID_READY", completeMarker = "LARGE_LAN_MAC_COMPLETE"
let rgbaInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue

func stage(_ name: String) { print(name); fflush(stdout) }
func waitFor(_ name: String, predicate: () -> Bool) throws {
    stage("Waiting: \(name)")
    let until = ProcessInfo.processInfo.systemUptime + 90
    while ProcessInfo.processInfo.systemUptime < until {
        if autoreleasepool(invoking: predicate) { return }
        Thread.sleep(forTimeInterval: 0.1)
    }
    throw LargeSmokeError.timedOut(name)
}
func pixels(_ seed: UInt32) -> [UInt8] {
    var result = [UInt8](repeating: 255, count: width * height * 4)
    // Mirrored rows make canonical pixels independent of API row-coordinate conventions.
    for y in 0..<height {
        var state = seed ^ (UInt32(min(y, height - 1 - y) + 1) &* 0x9E3779B9)
        for x in 0..<width {
            state ^= state << 13; state ^= state >> 17; state ^= state << 5
            let offset = (y * width + x) * 4
            result[offset] = UInt8(truncatingIfNeeded: state >> 16)
            result[offset + 1] = UInt8(truncatingIfNeeded: state >> 8)
            result[offset + 2] = UInt8(truncatingIfNeeded: state)
        }
    }
    return result
}
func pixelHash(_ rgba: [UInt8]) -> Data? {
    guard rgba.count == width * height * 4 else { return nil }
    var hasher = SHA256()
    var row = [UInt8](repeating: 0, count: width * 3)
    for y in 0..<height {
        for x in 0..<width {
            let offset = (y * width + x) * 4
            guard rgba[offset + 3] == 255 else { return nil }
            row[x * 3] = rgba[offset]; row[x * 3 + 1] = rgba[offset + 1]; row[x * 3 + 2] = rgba[offset + 2]
        }
        hasher.update(data: Data(row))
    }
    return Data(hasher.finalize())
}
func decodedHash(_ data: Data) -> Data? {
    guard data.count > oldLimit, data.count <= maxBytes,
          let source = CGImageSourceCreateWithData(data as CFData, [kCGImageSourceShouldCache: false] as CFDictionary),
          CGImageSourceGetCount(source) == 1, CGImageSourceGetType(source) as String? == UTType.png.identifier,
          let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
          properties[kCGImagePropertyPixelWidth] as? Int == width,
          properties[kCGImagePropertyPixelHeight] as? Int == height,
          let image = CGImageSourceCreateImageAtIndex(source, 0, nil),
          image.width == width, image.height == height,
          let space = CGColorSpace(name: CGColorSpace.sRGB) else { return nil }
    var rgba = [UInt8](repeating: 0, count: width * height * 4)
    let drawn = rgba.withUnsafeMutableBytes { buffer -> Bool in
        guard let context = CGContext(data: buffer.baseAddress, width: width, height: height,
            bitsPerComponent: 8, bytesPerRow: width * 4, space: space, bitmapInfo: rgbaInfo) else { return false }
        context.setBlendMode(.copy)
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        return true
    }
    return drawn ? pixelHash(rgba) : nil
}
func png(_ seed: UInt32) throws -> Data {
    guard let space = CGColorSpace(name: CGColorSpace.sRGB) else { throw LargeSmokeError.fixture }
    var rgba = pixels(seed)
    let expected = pixelHash(rgba)
    let image = rgba.withUnsafeMutableBytes { buffer -> CGImage? in
        CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: width * 4, space: space, bitmapInfo: rgbaInfo)?.makeImage()
    }
    let output = NSMutableData()
    guard let image, let destination = CGImageDestinationCreateWithData(output, UTType.png.identifier as CFString, 1, nil) else {
        throw LargeSmokeError.fixture
    }
    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { throw LargeSmokeError.fixture }
    let bytes = output as Data
    guard bytes.count > oldLimit, bytes.count <= maxBytes, let actual = decodedHash(bytes), actual == expected else {
        throw LargeSmokeError.fixture
    }
    return bytes
}
func run() throws {
    let pasteboard = NSPasteboard.general
    let initial = pasteboard.changeCount
    stage("Preparing: deterministic PNG above 8 MiB")
    let outgoing = try png(macSeed)
    guard let expectedIncoming = pixelHash(pixels(androidSeed)) else { throw LargeSmokeError.fixture }
    try waitFor("Android ready") { pasteboard.changeCount != initial && pasteboard.string(forType: .string) == readyMarker }
    Thread.sleep(forTimeInterval: 2)
    stage("Sending: Mac large PNG")
    let item = NSPasteboardItem()
    guard item.setData(outgoing, forType: .png) else { throw LargeSmokeError.clipboardWrite }
    pasteboard.clearContents()
    guard pasteboard.writeObjects([item]) else { throw LargeSmokeError.clipboardWrite }
    var checkedCount = pasteboard.changeCount
    try waitFor("Android large PNG with matching pixel hash") {
        let count = pasteboard.changeCount
        guard count != checkedCount else { return false }
        checkedCount = count
        guard let incoming = pasteboard.data(forType: .png), count == pasteboard.changeCount else { return false }
        return decodedHash(incoming) == expectedIncoming
    }
    Thread.sleep(forTimeInterval: 2)
    stage("Sending: completion marker")
    pasteboard.clearContents()
    guard pasteboard.setString(completeMarker, forType: .string) else { throw LargeSmokeError.clipboardWrite }
    stage("Success: valid PNG above 8 MiB, 2048x1536 pixels verified both ways; synthetic completion marker left on Mac")
}

guard Array(CommandLine.arguments.dropFirst()) == ["--confirm-system-clipboard"] else {
    fputs("Usage: native-large-lan-smoke.swift --confirm-system-clipboard\n", stderr)
    exit(2)
}
do { try run() }
catch LargeSmokeError.timedOut(let stage) { fputs("Failed: timeout waiting for \(stage)\n", stderr); exit(1) }
catch { fputs("Failed: synthetic fixture validation or clipboard write\n", stderr); exit(1) }
