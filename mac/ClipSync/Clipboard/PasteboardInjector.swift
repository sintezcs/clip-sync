import AppKit
import Foundation
import Logging

protocol PasteboardWriting: PasteboardReading {
    /// One NSPasteboardItem contains both content and its ownership marker.
    func replace(data: Data, type: NSPasteboard.PasteboardType, marker: String) -> Bool
}

extension NSPasteboard: PasteboardWriting {
    func replace(data: Data, type: NSPasteboard.PasteboardType, marker: String) -> Bool {
        let item = NSPasteboardItem()
        guard item.setData(data, forType: type), item.setString(marker, forType: PasteboardWatcher.ownershipType) else { return false }
        clearContents()
        return writeObjects([item])
    }
}

enum PasteboardInjectionError: Error, Equatable {
    case invalidBase64, unsupportedMime(String), writeFailed, paused, superseded
}

final class PasteboardInjector: @unchecked Sendable {
    private let pasteboard: PasteboardWriting
    private weak var watcher: PasteboardWatcher?
    private let lock = NSLock()
    private var enabled = true

    init(pasteboard: PasteboardWriting = NSPasteboard.general,
         watcher: PasteboardWatcher? = nil,
         logger: Logger = Logger(label: "clipsync.pasteboard.injector")) {
        self.pasteboard = pasteboard
        self.watcher = watcher
    }

    func bind(watcher: PasteboardWatcher) { lock.lock(); defer { lock.unlock() }; self.watcher = watcher }
    var isSyncEnabled: Bool { lock.lock(); defer { lock.unlock() }; return enabled }
    func setSyncEnabled(_ enabled: Bool) { lock.lock(); defer { lock.unlock() }; self.enabled = enabled }

    func inject(_ payload: ClipPayload) throws {
        try inject(PreparedClip(payload))
    }

    func inject(_ prepared: PreparedClip) throws {
        let payload = prepared.payload
        let bytes = prepared.bytes
        lock.lock()
        defer { lock.unlock() }
        guard enabled else { throw PasteboardInjectionError.paused }
        let type: NSPasteboard.PasteboardType = payload.type == .text ? .string :
            (payload.mime == "image/png" ? .png : NSPasteboard.PasteboardType("public.jpeg"))
        let write = {
            guard self.pasteboard.replace(data: bytes, type: type, marker: payload.nonce) else {
                throw PasteboardInjectionError.writeFailed
            }
        }
        if let watcher { try watcher.performRemoteWrite(payload, write: write) }
        else { try write() }
    }
}
