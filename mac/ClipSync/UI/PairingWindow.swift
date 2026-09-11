import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import SwiftUI

struct PairingView: View {
    @State private var session: PairingSession
    let hostname: String
    let port: Int
    let fingerprint: String
    let onRefresh: () async -> PairingSession?
    @State private var remaining: TimeInterval = 0
    @State private var cachedQRImage: NSImage?
    @State private var isRefreshing = false

    init(session: PairingSession, hostname: String, port: Int, fingerprint: String,
         onRefresh: @escaping () async -> PairingSession?) {
        self._session = State(initialValue: session)
        self.hostname = hostname; self.port = port; self.fingerprint = fingerprint
        self.onRefresh = onRefresh
    }

    var body: some View {
        VStack(spacing: 16) {
            Text("Pair your phone").font(.title2.weight(.semibold))
            Text("Scan this code on your phone, then review and confirm the connection.")
                .font(.callout).multilineTextAlignment(.center)
            if let qr = cachedQRImage, remaining > 0 {
                Image(nsImage: qr).interpolation(.none).resizable().frame(width: 220, height: 220)
            } else { Text("Pairing expired").frame(width: 220, height: 220) }
            Text("\(hostname):\(port)").font(.caption).textSelection(.enabled)
            Text("Server fingerprint").font(.headline)
            Text(fingerprint).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
            Text("Manual pairing code: \(session.code)").font(.system(.title3, design: .monospaced)).textSelection(.enabled)
            Text("For manual setup, compare the complete fingerprint above before entering this code.")
                .font(.caption).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Text(remaining > 0 ? String(format: "Expires in %d:%02d", Int(remaining) / 60, Int(remaining) % 60) : "Expired")
                .font(.system(.callout, design: .monospaced)).foregroundStyle(remaining <= 30 ? .red : .secondary)
            Button("New pairing code") { Task { await refresh() } }.disabled(isRefreshing)
        }
        .padding(24).frame(width: 420, height: 590)
        .onAppear { regenerateQR(); updateRemaining() }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { _ in updateRemaining() }
    }
    private func refresh() async {
        isRefreshing = true
        defer { isRefreshing = false }
        guard let next = await onRefresh() else { return }
        session = next; regenerateQR(); updateRemaining()
    }
    private func updateRemaining() { remaining = max(0, session.expiresAt.timeIntervalSinceNow) }
    private func regenerateQR() {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(session.url(hostname: hostname, port: port, fingerprint: fingerprint).utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { cachedQRImage = nil; return }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 8, y: 8))
        guard let cg = CIContext().createCGImage(scaled, from: scaled.extent) else { cachedQRImage = nil; return }
        cachedQRImage = NSImage(cgImage: cg, size: NSSize(width: scaled.extent.width, height: scaled.extent.height))
    }
}

@MainActor
final class PairingWindowController: NSObject, NSWindowDelegate {
    private var window: NSWindow?
    private var shownSessionID: String?
    private var onClose: (() -> Void)?
    func show(session: PairingSession, hostname: String, port: Int, fingerprint: String,
              onRefresh: @escaping () async -> PairingSession?, onClose: @escaping () -> Void) {
        if let window, shownSessionID == session.id { window.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true); return }
        close()
        shownSessionID = session.id
        self.onClose = onClose
        let hosting = NSHostingController(rootView: PairingView(session: session, hostname: hostname, port: port,
            fingerprint: fingerprint, onRefresh: onRefresh))
        hosting.sizingOptions = []
        let win = NSWindow(contentViewController: hosting)
        win.title = "Pair your phone"; win.styleMask = [.titled, .closable]; win.isReleasedWhenClosed = false
        let size = NSSize(width: 420, height: 590)
        win.setContentSize(size); win.contentMinSize = size; win.contentMaxSize = size
        win.delegate = self; win.center(); window = win
        win.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true)
    }
    func windowWillClose(_ notification: Notification) {
        let action = onClose; onClose = nil; window = nil; shownSessionID = nil; action?()
    }
    func close() { window?.close(); window = nil; shownSessionID = nil }
}
