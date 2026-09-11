# ClipSync native application audit

Reviewed 11 September 2026. Repository: [sintezcs/clip-sync](https://github.com/sintezcs/clip-sync), commit `0357798cc0f4c8ad0102570c92ab348ae497f40a`.

## Decision

**Adapt this implementation, but do not use the current version for sensitive everyday clipboard data.** The native applications, clipboard adapters, local discovery, TLS transport, and Shizuku integration provide a useful foundation. Several defects affect the core trust boundary and the promised automatic behavior; this is a focused hardening project rather than cosmetic fine-tuning.

The preferred initial scope is one Android phone and one Mac, text and images, direct local networking, with the existing keyboard and no root. Preserve the native Kotlin/Compose and Swift applications. Defer a custom cloud relay and push notifications until local correctness and lifecycle recovery are demonstrated.

This is a source audit with focused executable probes, not a certification or a completed device penetration test. I found no intentional third-party clipboard-upload destination in the examined native application paths. That does not establish that published binaries match the source or that all dependencies are safe.

## How the implementation works

| Component | Actual approach | Assessment |
|---|---|---|
| Mac application | Swift menu-bar app; polls NSPasteboard approximately every 750 ms; Hummingbird HTTP/WebSocket server | Appropriate foundation. Polling is acceptable for a personal desktop utility, but can miss copies replaced between polls. |
| Android application | Kotlin/Compose; persistent foreground service; OkHttp HTTPS and WebSocket | Suitable transport components. Current lifecycle and synchronization logic need substantial repair. |
| Android capture | Shizuku UserService running with shell identity calls hidden IClipboard APIs through reflection; polls about every 1–2 seconds | Fits no-root and current-keyboard requirements, but depends on OS/OEM behavior and Shizuku availability. |
| Android image capture | Detects image through Shizuku, then launches a transparent Activity to obtain clipboard URI access | Not seamless; a major gap relative to the stated goal. |
| Discovery and pairing | Bonjour/mDNS, six-digit pairing code, self-signed TLS identity, stored certificate fingerprint, bearer token and HMAC secret | Established-session pinning is useful; initial identity authentication is insufficient. |
| Mac → Android | WebSocket broadcast, then Android clipboard write | No FCM or intermediate server is involved. |
| Android → Mac | HTTPS POST `/inject`, bearer authentication plus HMAC | Reuses reasonable primitives, but lacks reliable event acknowledgement/deduplication semantics. |
| Away from home | Manual host/Tailscale-oriented configuration | Requires network reachability to an awake Mac. It is not a store-and-forward cloud clipboard. |

Sources: [Mac pipeline][app], [watcher][watcher], [Android service][service], [Shizuku helper][helper], [client][client], [server][server].

Android's normal background clipboard restriction is real; an ordinary foreground service does not grant clipboard-read access. Shizuku is a privileged bridge, not a replacement keyboard and not root. Its non-root setup must be restarted after reboot, and vendor restrictions can interrupt it. The helper chooses hidden methods by name and supplies zero for unknown integer arguments, including user/device identifiers; this deserves explicit version adapters rather than assuming future signatures remain compatible. Exceptions currently often become null or a silent failed write, so a connected UI can conceal a broken clipboard bridge. The claimed Accessibility fallback also disables itself above Android 11 in this source. [Android clipboard restriction][android-clipboard], [Shizuku setup][shizuku], [helper source][helper], [accessibility source][accessibility].

No bootloader unlock is required. However, payment-app compatibility has not been tested on this phone; individual apps may object to debugging or other device settings. The plan must not require disabling payment protection or broad device security controls. Shizuku permission should go only to the reviewed application.

## Security findings

Priorities below are implementation order, not CVSS scores. “Confirmed” means the described control flow is present in the reviewed commit. Probe results are identified separately; they do not imply a complete network exploit was executed.

### S1 — P1: Initial pairing does not authenticate the intended Mac

The manual/QR path uses an OkHttp trust manager accepting any certificate and a permissive hostname verifier, then saves the certificate it encountered. The QR contains a host, port and code, but no independently authenticated fingerprint. The discovery path can pin a fingerprint, but learns it from unauthenticated mDNS. An attacker controlling discovery or the initial network connection can substitute its own identity. Pinning later connections preserves the identity learned initially; it cannot fix a compromised first connection.

There is an additional takeover path: the exported `clipsync://pair` deep link immediately invokes manual pairing. A successful response overwrites saved host, token, fingerprint and secret and enables sync. The response's `sig` is checked only for being nonempty, not cryptographically verified. A malicious server does not need to validate the supplied six-digit code; that code protects the legitimate Mac's endpoint, not the Android app from an impostor. An externally launched link can therefore initiate pairing with an attacker-controlled endpoint without an in-app trust-replacement confirmation. Browser/OS dispatch and complete on-device behavior were not exercised.

**Repair:** QR includes the Mac's SPKI fingerprint and a high-entropy, short-lived, single-use pairing secret. Pin the TLS connection before exchanging credentials. Treat mDNS only as an endpoint hint. Show the intended peer and require explicit confirmation before replacing an existing pairing. If retaining a manual short-code flow, use a reviewed authenticated pairing protocol or an independently compared fingerprint; merely checking a signature with a key returned in the same untrusted response is insufficient.

Evidence: [permissive client][client], [pairing response handling][pairing-api], [deep-link handler][settings], [credential persistence][viewmodel], [QR generation][pairing-window].

### S2 — P1: Received filenames can escape the destination folder

`PasteboardInjector.saveFile` appends `payload.name` to `~/Documents/ClipSync` without validating path containment. Validation only caps the name's length. A name containing `../` can place a new file outside that directory, within locations writable by the Mac process. The fallback name also contains a remote nonce. Image saving uses the same file-saving path.

An authenticated `/inject` request is required: this is **not an unauthenticated arbitrary-code-execution finding**. Existing-file handling generally chooses another name instead of overwriting; arbitrary new-file placement is still unacceptable. A temporary-directory probe reproduced the path escape without touching real user documents.

**Repair:** generate local storage names independently of remote metadata, keep incoming images in an app-private bounded cache, and validate containment including symlink behavior. Disable generic file transfer for the first text/image release. Never use a remote nonce as a filesystem path.

Evidence: [saveFile, line 83][injector], [payload validation, line 83][payload].

### S3 — P1: Cryptographic initialization fails open

If Keychain secret loading fails, the Mac substitutes 32 zero bytes. If TLS initialization fails, it continues with a nil TLS configuration; the server has a plaintext branch and binds to all interfaces by default.

The official Android client still requests HTTPS, so it should fail to connect in this situation rather than silently send its traffic over HTTP. The defect is that the Mac continues exposing a plaintext service and may use a predictable HMAC key. Bearer authentication remains a separate check; the zero key alone is not a complete authentication bypass.

**Repair:** start neither server nor discovery until secure storage and TLS identity initialization succeed. Present a recoverable error. Add injected-failure tests proving no listener is created.

Evidence: [startup, lines 52–70][app], [server TLS branch][server], [bind configuration][server-config].

### S4 — P2: Replay protection and pairing throttling are incomplete

The HMAC validator accepts the same signed body repeatedly within its default 30-second timestamp window. Payload nonces are not recorded to make application idempotent. Repeated delivery can rewrite the clipboard or save extra files. A standalone probe using the actual validator accepted an identical request twice.

Rate limiting uses the request's `X-Forwarded-For` as its key without a trusted-proxy boundary. A caller can rotate this header. The actual limiter admitted 5/10 attempts with one key and 10/10 with rotating keys. Its dictionary also retains distinct keys indefinitely. Combined with a six-digit pairing code, this undermines the intended brute-force protection. This audit did not brute-force a running server.

**Repair:** derive peer addresses from the connection, add global and pairing-session attempt caps, bound/expire limiter entries, and persist recent accepted event IDs for idempotent retry. Timestamp checks are freshness checks, not deduplication.

Evidence: [HMAC validator][hmac], [pair route][server], [middleware][rate-middleware], [limiter][rate-limiter].

### S5 — P2: Unpairing is not effective server-side revocation

Android clears its local pairing. Mac token storage has a revoke operation, but it is not wired into a usable device-management flow. WebSocket clients are authenticated at handshake and are not associated with a revocable token for subsequent disconnection. An old credential/session can remain trusted after local unpairing.

**Repair:** identify paired devices, revoke credentials on the Mac, terminate that device's existing sockets, and expose a clear “remove phone” action. Use per-peer secrets if supporting multiple devices; rotate credentials when trust is reset.

Evidence: [token store][tokens], [WebSocket hub][hub], [server][server], [Android settings logic][viewmodel].

### S6 — P1 before personal use: Collection and retention exceed a clipboard utility's needs

The Mac watcher can read and transfer a copied file's contents, not just text/images. Incoming images are also saved permanently under Documents and trigger an alert. Documents may be synchronized by another service depending on the user's Mac configuration. Android's incoming notifications include clipboard text or image previews; exposure on the lock screen depends on notification settings. Screenshot observation can run when media access is granted and shares the auto-send control, rather than being a distinct screenshot-sync opt-in.

Sensitive clipboard metadata is not consistently preserved or enforced. Android contains a sensitivity helper, but the synchronization paths do not provide an end-to-end policy for excluding sensitive items. Passwords, one-time codes and private images therefore need deliberate handling, regardless of whether transport is encrypted.

**Repair:** default to explicit clipboard text/images only; disable screenshot monitoring and generic file capture unless separately enabled. Skip platform-marked sensitive/transient items where available, offer pause/exclusions, use generic notifications, and retain only a bounded cache needed for image URI lifetime. Avoid permanent saves and modal alerts for ordinary clipboard updates. Sensitivity detection cannot perfectly identify every secret.

Evidence: [watcher][watcher], [injector][injector], [notifications][notifications], [service][service], [clipboard writer][writer].

### S7 — P2: Malformed timestamps and oversized content can terminate processing

Swift validation computes `abs(now - ts)` with a remote signed 64-bit integer. `Int64.min` causes arithmetic overflow, rather than a handled validation error. Running the actual validator in a standalone subprocess terminated it with signal 5. This is a confirmed process-level arithmetic fault, not a demonstrated unauthenticated server crash: `/inject` is authenticated.

Some content paths read an entire file/stream before enforcing a size limit. Image decoders also need pixel/dimension limits, because compressed-byte limits alone do not bound memory. Different JSON-body and decoded-file caps are inconsistent: base64 expansion means a 20 MiB HTTP-body cap cannot carry a 20 MiB file.

**Repair:** overflow-safe timestamp comparisons, strict schema/MIME bounds, bounded stream reads, image dimension checks before full decode, and a consistent encoded/decoded size budget. Test malformed frames and cancellation as well as normal files.

Evidence: [payload, line 97][payload], [watcher][watcher], [Activity image reader][send-activity], [rate middleware][rate-middleware], [notification image decoding][notifications].

## Reliability findings

### R1 — P1: Network callbacks can destroy the usable endpoint

`NetworkChangeObserver` registers for INTERNET-capable networks, not specifically default-network changes. Its initial `onAvailable` calls reconnect. In auto mode, that callback clears `prefs.host`; reconnect then has no host. Discovery in the settings UI populates a list, not an autonomous service-side endpoint repair. The UI later suggests pairing again. This can occur on initial registration as well as roaming; with Wi-Fi and cellular active, callbacks are not a reliable default-route model.

**Repair:** separate persistent peer identity from temporary addresses. Keep credentials across network changes. Resolve and authenticate endpoints inside the connection service, with a state machine for discovery, connection, backoff and recovery. Do not confuse “peer temporarily unreachable” with “unpaired.”

Evidence: [service, line 205][service], [network observer][network], [settings recovery][viewmodel].

### R2 — P1: The foreground-service type conflicts with continuous operation

The app targets API 35 and declares a `dataSync` foreground service without an `onTimeout` implementation. On Android 15+ this service type has a six-hour background allowance per 24-hour period, with foreground interaction resetting the allowance. Exceeding the timeout without stopping can cause a failure. A permanently ongoing notification does not remove this limit. [Manifest][manifest], [build configuration][android-build], [official timeout behavior][fgs-timeout].

**Repair:** use the service type appropriate to actual ongoing communication with another device—evaluate `connectedDevice` and satisfy its documented prerequisites. Add lifecycle error handling and visible recovery. This is not a blanket exemption from Doze, force-stop or OEM background management; companion-device APIs also merit a bounded prototype if needed. [Official connected-device service requirements][fgs-types].

### R3 — P1 for images: Shizuku does not currently deliver image bytes to the app

The helper returns URI metadata, but the regular application lacks the shell process's URI grant. The implementation compensates by launching `SendClipActivity`, waiting for window focus, reading the clipboard, and sending while the Activity remains active. Automatic image capture can interrupt the foreground app or be blocked by background-launch rules. It does not meet “copy normally, paste normally.”

**Repair:** prototype a narrowly scoped helper operation that snapshots the current clipboard and opens/streams its image in the process holding the grant, returning a bounded file descriptor or stream. Do not add an arbitrary privileged file-reader API. Verify provider behavior on the target phone. Keep explicit Share/Send as a fallback when automatic capture is unavailable. On receive, use normal app-owned content URIs and platform clipboard APIs, validating URI grants in destination apps.

Evidence: [service, lines 546–555][service], [helper][helper], [transparent Activity][send-activity]. This helper redesign is a proposed approach, not yet proven on the user's device.

### R4 — P1: Lost updates and stale clipboard overwrites

The current algorithm tracks 32-bit content hashes and timing windows, rather than event identity and acknowledgement. A local copy within two seconds of a Mac write is marked as already seen and dropped. Failed outbound sends have already advanced the observed hash. Each send starts an independent thread, allowing newer content and retries of older content to arrive out of order. On receive, the service applies content before its echo check; the check mostly suppresses notification, not the clipboard write.

A concrete failure sequence is: Mac sends A → user copies B on Android immediately → the two-second filter records B without sending it. B may never reach the Mac. Another is: Android sends A, then B → an old response/retry of A arrives later → A replaces B.

**Repair:** serialize clipboard events through one reducer per device. Include origin device, session and monotonic sequence/event ID; deduplicate before writing, acknowledge accepted events, and retry only the latest pending intent within a short TTL. Preserve a deliberate local copy made after an inbound transfer began. Use full-content digests as supporting data, not as the event identity. A global latest-wins policy needs explicit handling of simultaneous edits and clock skew.

Evidence: [service polling/send/receive][service], [helper hash][helper], [Mac watcher suppression][watcher].

### R5 — P2: Performance and recovery are unproven

The polling intervals imply approximately a 0–2 second Android capture delay plus transfer; “immediate” is not established. Independent hash/MIME/text calls can observe different clipboard versions. Rapid intermediate copies may be missed. There is no demonstrated multi-day battery profile, reboot recovery, screen-off delivery guarantee, or sustained image URI compatibility matrix.

**Repair:** return one atomic clipboard snapshot from the helper, then evaluate a shell-side listener if the target OS supports it reliably. Keep adaptive polling as a tested fallback. Make readiness explicit: connected network, authorized/running helper, and verified clipboard access are separate states. Measure latency and energy on the actual phone rather than inferring from desktop unit tests.

## What to retain and what to change

**Retain:** native interfaces; NSPasteboard and Android ClipboardManager integration; OkHttp; Hummingbird/SwiftNIO; Android NSD/Bonjour; Keychain/encrypted preference storage; Shizuku API/UserService boundary; basic payload model and existing unit-test structure. These are useful starting points, although their integration needs repair.

**Refactor:** pairing trust establishment; lifecycle/endpoint management; the Android foreground service's synchronization core; image grant handling; validation and retention policy. Avoid a wholesale language or Rust rewrite while these behaviors are unresolved.

**Defer:** custom cloud infrastructure, FCM, multi-device history, general file transfer and automatic screenshots. A VPN path can extend the existing protocol to an awake remote Mac, after hardening and route testing. FCM cannot grant clipboard access or replace Shizuku, and high-priority messages are intended for time-sensitive user-visible content and can be deprioritized if used for silent updates. It should not be the foundation for guaranteed instant background clipboard delivery. [Firebase priority guidance][fcm].

## Build, release and repository health

The installer still points to `2cristo7/clip-sync`, so running it from this fork would download upstream releases, not our patched binaries. It uses mutable release downloads without a separate publisher/checksum-verification policy. Change this before distributing the fork; use controlled signing identities and an atomic install/rollback process. [Installer][installer].

The repository tracks roughly **43,418 files under `rust/target`**, with about **6.2 GB of total tracked blob sizes** in the inspected tree. These are source-tree size measurements, not compressed clone size. A sparse, shallow, blob-filtered clone was used for the native audit. Remove generated artifacts from the index in a dedicated cleanup change; history rewriting is a separate decision.

The native dependency stack includes older Android tooling and an alpha AndroidX security-crypto dependency. Age alone is not evidence of a vulnerability. Pin/lock reproducible dependencies, inspect transitive advisories and licenses, and validate current release tooling before shipping. This review did not complete a dependency CVE audit or verify release-binary provenance.

Existing tests cover some protocol utilities and parsing, but do not establish the difficult behaviors above. Some Android tests assert constants or method presence rather than exercising a clipboard write. HMAC timestamp tests do not substitute for fresh-message replay tests. CI must cover the event reducer, trust replacement, networking lifecycle and real-device clipboard paths. [CI][ci], [Android clipboard tests][writer-tests], [Mac HMAC tests][hmac-tests].

## Verification performed and limits

* Inspected the native Swift/Kotlin source, manifests, build files, installer and CI at the pinned commit. The app source was not modified, installed or launched on the phone.
* Compiled actual `ClipPayload`, `HMACValidator` and `RateLimiter` Swift source with a small standalone runner using the local Swift compiler.
* Confirmed identical fresh HMAC acceptance twice; limiter acceptance 5/10 for a fixed key versus 10/10 for rotated keys; filename escape using the same URL construction in an isolated temporary directory; and a signal-5 termination for `Int64.min` timestamp validation.
* These probes isolate implementation properties. Pairing takeover, full HTTP file injection, notification rendering, lifecycle behavior and battery use require integration/device tests.
* Full macOS app build/tests were unavailable because this machine has Command Line Tools selected and no full Xcode installation. Standalone Swift probe compilation is not a macOS app build.
* Android compilation, 29 unit tests and lint completed successfully; lint reported 76 warnings. See the [verification record](2026-09-11-verification.md) for exact commands, results and limitations.

See [the adaptation plan](../plans/2026-09-11-fork-hardening.md) for implementation batches and acceptance criteria. The earlier greenfield research remains background material; this plan supersedes its initial implementation direction.

[app]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/App.swift
[watcher]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Clipboard/PasteboardWatcher.swift
[service]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/service/ClipForegroundService.kt
[helper]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/shizuku/ClipboardUserService.kt
[client]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/net/ClipClient.kt
[server]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Server/ClipServer.swift
[pairing-api]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/net/PairingApi.kt
[settings]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/ui/SettingsScreen.kt#L94
[viewmodel]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/ui/SettingsViewModel.kt
[pairing-window]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/UI/PairingWindow.swift
[injector]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Clipboard/PasteboardInjector.swift#L83
[payload]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Clipboard/ClipPayload.swift#L83
[server-config]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Server/ServerConfig.swift
[hmac]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Security/HMACValidator.swift
[rate-middleware]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Server/RateLimitMiddleware.swift
[rate-limiter]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Server/RateLimiter.swift
[tokens]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Pairing/TokenStore.swift
[hub]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSync/Server/WebSocketHub.swift
[notifications]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/notifications/IncomingClipNotifier.kt
[writer]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/clipboard/ClipboardWriter.kt
[network]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/net/NetworkChangeObserver.kt
[manifest]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/AndroidManifest.xml
[android-build]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/build.gradle.kts
[send-activity]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/overlay/SendClipActivity.kt
[accessibility]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/main/java/com/clipsync/accessibility/ClipAccessibilityService.kt
[installer]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/install.sh
[ci]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/.github/workflows/ci.yml
[writer-tests]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/android/app/src/test/java/com/clipsync/clipboard/ClipboardWriterTest.kt
[hmac-tests]: https://github.com/sintezcs/clip-sync/blob/0357798cc0f4c8ad0102570c92ab348ae497f40a/mac/ClipSyncTests/HMACValidatorTests.swift
[android-clipboard]: https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
[shizuku]: https://shizuku.rikka.app/guide/setup/
[fgs-timeout]: https://developer.android.com/develop/background-work/services/fgs/timeout
[fgs-types]: https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device
[fcm]: https://firebase.google.com/docs/cloud-messaging/android-message-priority
