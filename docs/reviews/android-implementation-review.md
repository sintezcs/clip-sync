# Android implementation review and release limits

Updated 11 September 2026. Scope: `feature/android-hardening-oneui` in the isolated `android-work` checkout, following the [baseline native audit](2026-09-11-native-app-audit.md). This is an implementation/review record, not a certification. See the [plan](../plans/2026-09-11-fork-hardening.md) and [continuation handoff](../handoff/android-implementation-progress.md).

## Implemented controls

Pairing no longer accepts an arbitrary first certificate. The user reviews the endpoint, independently compares the Mac SPKI fingerprint, and explicitly confirms pairing or replacement. mDNS is an endpoint hint only. The existing Mac response is checked for bounded, correctly encoded credentials and a consistent HMAC; that HMAC is not the source of peer identity. TLS redirects and automatic request retries are disabled.

Android restricts clipboard transfer to text and validated PNG/JPEG images. Payload validation covers canonical base64, byte budgets, UTF-8, metadata and overflow-safe timestamps. Shared-image reads have a deadline, cancellation signal, descriptor cleanup and bounded worker capacity; decoded image dimensions are checked before a full bitmap allocation. The privileged image operation accepts only the current clipboard identity, not an arbitrary URI or path. Notifications carry no clipboard content. Generic-file and automatic screenshot paths are excluded.

The service owns discovery and reconnect, preserves verified credentials across route changes, distinguishes network/helper/readiness states, and serializes clipboard decisions. Peer identity is captured for asynchronous operations. Nonces are checked before clipboard effects and stored as opaque peer/event hashes in app-private durable storage. Corrupt/full/unavailable replay storage fails closed. A latest-event outbox expires unattempted content; ambiguous network delivery is not retried against the legacy Mac. Own-write markers prevent a racing local copy from being mistaken for a completed remote write.

Review corrections included the post-write baseline race, unbounded inbound queue, upload cancellation, provider-read deadlines, image-pipe timeout, cache protection before actual application, stale peer capture and recreation-triggered duplicate explicit sends. The adaptive settings implementation uses window/fold information and saved UI state; it does not select layouts from a Samsung model identifier.

## Verification and boundaries

The controller reports passing Android debug/release builds, unit tests, lint and seven instrumentation tests on `emulator-5580` (`Klippa_Audit`). The tests exercise service transport/clipboard behavior, settings semantics/restoration/pairing confirmation and stalled image-provider cancellation. A synthetic shell-UID clipboard-image probe succeeded; full Shizuku startup/binding/death/restart validation remains in progress. The handoff is the live record; this document does not infer additional test counts or completed runs.

The user's Fold8 and `emulator-5554` are explicitly unavailable to this task. Do not operate them. Only the dedicated audit emulator is authorized. Emulator screenshots and synthetic providers do not establish physical Gallery/Chrome/messaging grants, cover/inner-display behavior, TalkBack usability or payment-app compatibility.

## Release blockers and operational limits

- Mac security work remains open: fail-closed TLS/Keychain startup, independently authenticated QR with a single-use secret, replay/idempotency, pairing throttling, revocation and data-retention/path controls. Current compatibility is the legacy `/pair` exchange with a manually verified fingerprint.
- Mac v1 has no complete origin/session/sequence or idempotent acknowledgement contract. Cancellation cannot undo content already accepted by a peer; independent clocks limit conflict ordering. A successful Android-only suite is not cross-device delivery proof.
- OkHttp allocates a WebSocket frame before Android's application payload-size check. The application rejects oversized frames afterward; this is not a transport allocation cap.
- Provider permissions, deferred URI paste and complete Shizuku lifecycle must be demonstrated on authorized target hardware. An inaccessible image remains an explicit-send/failure case, not a reason to launch a background Activity.
- A 48-hour soak, reboot/screen-off recovery, latency distribution, battery measurement, physical foldable visual/accessibility checks and release signing/provenance remain pending.

Do not publish or enable sensitive daily clipboard use on the strength of this Android implementation alone. Complete the remaining Mac and device gates before making that claim.
