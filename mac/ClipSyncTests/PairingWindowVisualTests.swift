import AppKit
import Foundation
import Vision
import XCTest
@testable import ClipSync

/// Explicit UI audit only. Synthetic credentials have no listening server or clipboard access.
final class PairingWindowVisualTests: XCTestCase {
    @MainActor
    func testPairingWindowVisualOptIn() async throws {
        let marker = "/tmp/klippa-audit-ui-enabled"
        guard FileManager.default.fileExists(atPath: marker) else {
            throw XCTSkip("Requires explicit /tmp/klippa-audit-ui-enabled marker")
        }
        let attributes = try FileManager.default.attributesOfItem(atPath: marker)
        guard attributes[.type] as? FileAttributeType == .typeRegular else {
            throw XCTSkip("UI audit marker must be a regular file")
        }
        let pairing = PairingManager(secret: Data(repeating: 0xA5, count: 32))
        let session = try await pairing.startPairing()
        let fingerprint = Data(repeating: 0x5A, count: 32).base64EncodedString()
            .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let controller = PairingWindowController()
        let previousWindows = Set(NSApp.windows.map(\.windowNumber))
        var closed = false
        controller.show(session: session, hostname: "synthetic-audit.invalid", port: 17011,
            fingerprint: fingerprint, onRefresh: { try? await pairing.refreshPairing(sessionID: session.id) },
            onClose: { closed = true })
        defer { controller.close() }
        // Allow SwiftUI onAppear and the QR state update to render on the real run loop.
        try await Task.sleep(for: .seconds(1))
        let window = try XCTUnwrap(NSApp.windows.first {
            !previousWindows.contains($0.windowNumber) && $0.title == "Pair your phone"
        })
        XCTAssertTrue(window.isVisible)
        let view = try XCTUnwrap(window.contentView)
        view.layoutSubtreeIfNeeded()
        view.displayIfNeeded()
        let bitmap = try XCTUnwrap(view.bitmapImageRepForCachingDisplay(in: view.bounds))
        view.cacheDisplay(in: view.bounds, to: bitmap)
        // cacheDisplay captures the hosting view's transparent backing, excluding the
        // NSWindow background. Resolve its dynamic color in the actual window appearance
        // and composite before OCR/export, just as the window server does on screen.
        let backing = try XCTUnwrap(bitmap.cgImage)
        var background: CGColor?
        window.effectiveAppearance.performAsCurrentDrawingAppearance {
            background = window.backgroundColor.usingColorSpace(.sRGB)?.cgColor
        }
        let color = try XCTUnwrap(background)
        let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
        let composite = try XCTUnwrap(CGContext(data: nil, width: backing.width, height: backing.height,
            bitsPerComponent: 8, bytesPerRow: backing.width * 4, space: space,
            bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue))
        let bounds = CGRect(x: 0, y: 0, width: backing.width, height: backing.height)
        composite.setFillColor(color)
        composite.fill(bounds)
        composite.draw(backing, in: bounds)
        let image = try XCTUnwrap(composite.makeImage())
        let png = try XCTUnwrap(NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]))
        let artifact = URL(fileURLWithPath: "/tmp/klippa-pairing-ui.png")
        // A fresh atomic replacement avoids following an existing destination symlink.
        try png.write(to: artifact, options: [.atomic])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: artifact.path)

        let text = VNRecognizeTextRequest()
        text.recognitionLevel = .accurate
        let qr = VNDetectBarcodesRequest()
        qr.symbologies = [.qr]
        try VNImageRequestHandler(cgImage: image).perform([text, qr])
        let renderedText = (text.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: " ")
        XCTAssertTrue(renderedText.contains("Pair your phone"))
        XCTAssertTrue(renderedText.contains("Server fingerprint"))
        XCTAssertTrue(renderedText.contains("Manual pairing code"))
        XCTAssertTrue(renderedText.contains("New pairing code"))
        XCTAssertTrue((qr.results ?? []).contains {
            $0.payloadStringValue == session.url(hostname: "synthetic-audit.invalid", port: 17011, fingerprint: fingerprint)
        }, "Rendered QR must encode the displayed synthetic session")
        controller.close()
        XCTAssertTrue(closed)
        XCTAssertFalse(window.isVisible)
        await pairing.cancel(sessionID: session.id)
    }
}
