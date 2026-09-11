# Android implementation progress

Plan: docs/plans/2026-09-11-fork-hardening.md
Worktree: /Users/aminakov/code/klippa/android-work
Branch: feature/android-hardening-oneui
Baseline: 0357798cc0f4c8ad0102570c92ab348ae497f40a

## Scope and decisions

Implement and verify Android-only work; defer real Mac interoperability and Mac fixes. User authorized subagents and Android iteration/device verification. Existing claude.md development-install prohibition is superseded by the explicit request to iterate and verify the Android app. Use emulator with synthetic clipboard data first; do not enable real phone clipboard synchronization against the insecure baseline Mac. No push or publication.

## Ownership / interface review

| Task | Files / interfaces | Dependency and check |
|---|---|---|
| One UI / foldable | UI, ViewModel, MainActivity | Consumes secure pairing API; coordinate with pairing task |
| Pairing / payload | net/ClipClient, PairingApi, model payload and sender | Preserve legacy wire fields; secure manual fingerprint verification |
| Sync / recovery | service, NetworkChangeObserver, new sync classes | Consumes payload validation and helper snapshot from integration |
| Integration / images | Shizuku helper, cache, clipboard writer, notifications, build and manifest | Service consumes explicit snapshot/stream API; controller coordinates |

UI owns pairing confirmation and ViewModel; security task owns trust API. Service task owns all service edits; image helper contract coordinated before integration. No parallel Gradle builds; controller runs shared verification.

## Android checkpoint status

Independent Android implementation is verified and ready for the Mac integration stage. Xcode 26.3 (17C529) is installed at `/Applications/Xcode.app`; use command-scoped `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` rather than changing the other session’s global toolchain. Native macOS fixes and full-flow verification are now explicitly authorized.

- Native One UI inspired light/dark/system settings, adaptive narrow/medium/expanded layouts and physical hinge avoidance, saved navigation/forms, explicit trust review.
- Strict SPKI pinning before credential exchange; existing TOFU trust requires re-pairing. Bounded text/PNG/JPEG payloads and cancellable provider/image reads; no generic file capture or automatic focus-taking send activity.
- Service-owned discovery/recovery, peer-scoped operations, serialized clipboard handling, bounded latest-event outbox, explicit own-event echo suppression, atomic helper snapshots.
- Durable replay journal records opaque peer/event hashes before clipboard/network effects, fsyncs file and directory metadata, and fails closed on corrupt/unwritable/full storage or backward clock changes. No clipboard content stored in this journal. A clock/storage error requires correction and app restart; do not silently delete replay state to recover.
- Minimal permissions; no broad media, Accessibility, package installation or legacy storage permission. Generic private notifications and bounded image cache.

## Verification and isolation

- User forbids Fold8 and emulator-5554 use because another session owns them. Use **ONLY emulator-5580**, AVD `/tmp/klippa-emulator/Klippa_Audit.avd`. No device mutations target any other serial.
- `scripts/verify-android.sh` validates literal serial, AVD name, emulator property and boot before installs/tests; do not use `connectedDebugAndroidTest` locally.
- Final checkpoint: debug/release assembly, all 72 JVM tests and lint passed (zero lint errors; existing/tooling/icon/custom-API warnings remain). Seven standard instrumentation tests passed, with two opt-in Shizuku cases skipped by default; the two Shizuku cases passed in their separate explicit run. Latest exact checkpoint standard run: 13.108 seconds.
- Seven standard instrumentation tests passed: real pinned TLS inbound text/image (image applied while Activity backgrounded), replay/pause, stalled provider cancellation/deadline recovery, and UI semantics/state/trust confirmation.
- Two opt-in real Shizuku tests passed on Android 16 with Shizuku 13.6.0 launched as ADB shell (no root): helper bind/atomic snapshot/background image FD read/stale identity rejection; automatic text/image sends to pinned TLS peer with no SendClipActivity. Default CI skips these two unless explicitly enabled.
- ShellClipboardProbe separately validated an actual shell-UID synthetic PNG read (108 bytes). No personal clipboard contents logged.
- Compact light, expanded light (including final geometry), narrow dark at 200% font screenshots were visually inspected in `docs/verification/android/`. These are emulator profiles, not claims about actual Galaxy dimensions or frame rates.
- Three subagents independently reviewed security, sync and UI integration. Fixed findings include own-write race, unbounded inbound work queue, upload cancellation, image pipe timeout, cache pin timing, stale peer operations, Activity recreation duplicate sends, provider read deadlines, durable-directory metadata, RTL hinge placement and stopped-service restoration.

## Remaining gates

- Mac v1 has no idempotent application acknowledgement/origin sequence; current Android deliberately avoids retrying ambiguous uploads. Cross-device clock ordering is conservative.
- OkHttp allocates an entire incoming WebSocket message before application validation; queue/payload checks do not eliminate this transport-level allocation risk.
- Actual Samsung clipboard providers, fold transitions/taskbar/TalkBack and performance/battery/48-hour soak remain unverified. Do not touch Fold8 without a new authorization changing the current restriction.
- Mac startup/security/pairing/injection fixes and real Mac↔Android flow are now the next authorized work. No publication or signed release yet.

## Reproduce

```sh
cd /Users/aminakov/code/klippa/android-work
ANDROID_HOME=/Users/aminakov/Library/Android/sdk scripts/verify-android.sh
# Separate opt-in tests; Shizuku must already be running and authorized on the audit AVD:
/Users/aminakov/Library/Android/sdk/platform-tools/adb -s emulator-5580 shell am instrument -w -r -e shizuku true -e class com.clipsync.shizuku.ShizukuIntegrationTest com.clipsync.app.test/androidx.test.runner.AndroidJUnitRunner
```

No actual personal content or user reference screenshot data is included in fixtures. APKs are under `android/app/build/outputs/apk/`; local verification logs are ignored build outputs.
