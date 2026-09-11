# Native Android + Mac implementation handoff

Date: 11 September 2026. Plan: [fork hardening](../plans/2026-09-11-fork-hardening.md).
Worktree: `/Users/aminakov/code/klippa/android-work`; branch: `feature/android-hardening-oneui`.
Baseline: `0357798cc0f4c8ad0102570c92ab348ae497f40a`; Android checkpoint: `9c7489d`.

## Current scope and authorization

The earlier Android-only phase is complete. The user authorized native Mac repairs, full-flow verification and subsequently released the physical Fold for this task. No push, publication or hardened binary release has been completed. Keep the original audit as baseline context, not current behavior.

The permitted phone is **R5GL72NJTFM**, model **SM-F971B**, Android **17**. Cover display: **1248×1972 physical pixels**, density **420**, font scale **1.0**. Gboard remains unchanged. Shizuku **13.6** was installed and started through ADB; the user personally granted **Allow all the time**. Root/bootloader/keyboard replacement was not required. Cover-screen One UI screenshots were visually reviewed. The unfolded inner display was measured at 2448×1848 physical pixels and visually reviewed. Four physical UI tests passed (`/tmp/klippa-fold-ui-tests.log`). Full folding/rotation/accessibility and exact One UI version remain to record.

`emulator-5554` remains prohibited. The normal Android harness targets only **emulator-5580**, AVD **Klippa_Audit**. The bridge harness has a separate explicit `--fold` mode with physical model/serial checks and an owned USB reverse mapping. Do not use untargeted device mutations. Synthetic test content only; Mac bridge tests use a named pasteboard and isolated test Keychain entries.

## Implemented

- Android adaptive One UI settings, compact/expanded layouts, hinge avoidance, themes, saved navigation and explicit pairing review. Latest permission diagnostics derive authorization from the real Shizuku check even while unpaired, separately from helper process/readability.
- Pin verification before credential exchange; v2 single-use random QR secret over POST and pinned manual code pairing. Discovery never establishes trust. Existing unsafe trust requires re-pairing.
- Mac fails closed on shared-secret, TLS identity and token-store initialization; partial identity is rejected. Tokens persist before publication; revocation excludes pending writes and closes sockets. Pairing consumption versus revocation is generation-checked.
- Listener-bound readiness precedes Mac advertisement. Bonjour stop/failure clears published state and rejects obsolete callbacks. Reachability cancels pending restarts, rejects stale sessions and uses a fresh monitor after stop.
- Strict text/PNG/JPEG validation, bounded/cancellable image/provider reads, no generic files or automatic screenshots, private notifications and bounded caches. Mac images are clipboard content, not Documents saves.
- Android service-owned endpoint recovery, peer-scoped operations, serialized clipboard events/sends and latest-event outbox; atomic Shizuku snapshots and image reads avoid focus-taking activities.
- Both sides record hashed peer/event replay identity before effects. Durable state rejects corrupt/unwritable/full storage and backward clocks. Correct the cause and restart; do not silently delete replay state. Mac's `applied` response distinguishes application from a duplicate acceptance, but Android does not yet expose that distinction.

First-launch onboarding is now implemented: an unpaired installation offers the existing pairing window once after its first successful TLS listener bind. Paired startup, dismissal and reconnect remain quiet. Presentation tasks are tracked and checked against listener readiness and a generation after suspension; stop, revocation and dismissal invalidate pending presentation/refresh work. Independent source review found the reported stale-window race closed. The final Mac suite now passes 92 tests, including onboarding policies; clean installed application startup has also been verified.

## Verified evidence

| Check | Result and evidence |
| --- | --- |
| Android checkpoint `9c7489d` | Debug/release assembly, 72 JVM tests and lint passed; zero lint errors, existing warnings remain |
| Standard Android instrumentation | Latest default runner: `OK (12 tests)`: eight executed checks and four opt-in assumption skips. Original checkpoint had seven standard checks |
| Real Shizuku on emulator | Two passed: atomic helper snapshot/background image FD/stale identity and real service text/image traffic without SendClipActivity |
| Later Android v2 unit coverage | One additional passing test, 73 verified JVM tests before the latest diagnostics change |
| Latest diagnostics tests | Four passed; verified JVM aggregate is now 77 |
| Mac unit suite | Final 92 tests passed (`/tmp/klippa-mac-presentation-tests.log`); earlier 86-test lifecycle checkpoint: `/tmp/klippa-mac-lifecycle-tests.log` |
| Mac pairing window | Opt-in visual test passed; `/tmp/klippa-mac-pairing-ui-final.log` and `.xcresult`; actual QR OCR and rendered image inspected |
| Emulator ↔ real Mac native bridge | Passed text and PNG pixel checks both directions; `build/native-verification/bridge-55jv2r2i/` |
| Physical Fold ↔ real Mac over USB | Passed text and PNG pixel checks both directions, Android backgrounded with Shizuku; `build/native-verification/bridge-2p9tdtu2/` |
| UI evidence | Emulator compact/expanded/light/dark/200%-font images in `docs/verification/android/`; physical cover One UI screenshot reviewed separately |

Each bridge directory contains `success.json`, sanitized Android/Mac logs and `bridge.xcresult`. Prior failure artifacts in the same directory are historical attempts; the successful completion record and current logs establish the final result. No fixture pairing credentials or personal clipboard data belong in published evidence. The bridge validates actual native server/helper behavior through a loopback/USB transport; it does not prove ordinary LAN discovery/setup.

Universal Mac Release build and deep/strict signature verification passed, but its ad hoc hardened-runtime launch failed dyld library validation on macOS 15.7.5 (`libswiftCompatibilitySpan`, Team ID). No signing identity is available. Preserved Release: `/tmp/klippa-local-release-signing-pending.app`. The earlier temporary test-host installation was replaced with a clean app-only Debug build from `/tmp/klippa-local-app-build`, verified with deep/strict codesign and no XCTest payload. It is installed at `/Users/aminakov/Applications/ClipSync.app`. The user approved Keychain access, and startup, LAN port 7010 and Bonjour are live. Normal LAN pairing/clipboard validation is still in progress. Project Release hardening remains enabled. See the native-verification signing section.

## Toolchain and reproduction

Xcode 26.3 (17C529) is installed at `/Applications/Xcode.app`. Use command-scoped `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`, with `MACOSX_DEPLOYMENT_TARGET=14.0`; do not change another task's global toolchain. Do not run parallel Gradle/Xcode builds in this worktree. The controller owns shared builds and device execution.

See [native verification](../development/native-verification.md) and [Android harness](../development/android-verification.md). The checked-in Swift lockfile uses the original supported dependency ranges. Removing the hosted test target's HummingbirdTesting dependency fixed generated dynamic-framework linking; route fixtures use HTTP directly. Temporary dependency downgrades were discarded.

```sh
cd /Users/aminakov/code/klippa/android-work
ANDROID_HOME=/Users/aminakov/Library/Android/sdk scripts/verify-android.sh
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer scripts/verify-macos.sh
# Separately coordinated bridge; needs built products, installed app, ready Shizuku:
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer python3 scripts/verify-native-bridge.py --derived-data /tmp/klippa-xcode-build
# Explicitly authorized physical phone through owned USB reverse:
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer python3 scripts/verify-native-bridge.py --derived-data /tmp/klippa-xcode-build --fold
```

These are reproduction instructions, not commands to repeat automatically. Default Mac verification excludes the opt-in native bridge. A test skip is not a pass for that gate.

## Next work and limits

1. Preserve the verified 84 JVM / default instrumentation result; do not repeat completed diagnostics work as pending.
2. Finish ordinary LAN pairing and clipboard verification on the clean installed Debug app. Keychain authorization and app startup are complete; Release runtime signing remains open despite a successful universal build.
3. Continue the wider resize/fold/rotation/taskbar/accessibility matrix. Cover and unfolded inner geometry/visual review and four UI tests are complete.
4. Extend real source/destination image-provider coverage, including delayed paste and permission/provider failures.
5. Measure latency/battery and run the 48-hour soak. None has been measured or completed yet.

Origin/session/monotonic sequence fields and full cross-device ordering remain pending. The durable journal records acceptance, not guaranteed completion; ambiguous sends are not retried and Android currently reports HTTP success without exposing `applied:false`. OkHttp's incoming WebSocket allocation occurs before application validation. No offline/cloud queue or sleeping-Mac delivery guarantee exists. Release signing, dependency advisory/license review and publication remain separate gates.

### Current LAN setup checkpoint

The clean installed Mac app is running and the Fold discovers it over mDNS. The first normal UI pairing attempt returned `Connection failed`: a read-only route check showed `192.168.2.115` routed through Android VPN `tun0` (source `10.77.10.19`), and a TCP 7010 probe timed out. The Mac listener is live and macOS firewall is disabled. User has been asked to enable VPN local-network access or temporarily pause the VPN; no VPN configuration has been changed by the agent. Retry with a fresh pairing code after the network path is available.

### Final pairing review fix

Independent review found a delayed Android pairing response could override a later Pause or Remove Mac action. Pairing now tracks/cancels its task and uses one attempt gate around credential persistence and the later UI/service start. Seven new regression tests cover ordering and typed failure guidance; 84 JVM tests pass with zero failures/errors/skips. Typed 401/429 responses and network timeouts give fresh-code/wait/LAN-VPN guidance without exposing server response bodies. Separate review accepted this fix. The normal LAN smoke now explicitly rejects receipt before the Activity reaches CREATED and requires a fresh Android handshake marker.

Final revision build evidence: `/tmp/klippa-android-final-pairing.log` reports debug/release assembly, test APK, 84 JVM tests and lint successful (zero lint errors). `/tmp/klippa-android-pairing-instrumentation.log` reports eight executed checks plus four opt-in skips (`OK (12 tests)`) on `emulator-5580`, never the other task emulator.

The final Android pairing revision also passed a fresh native Mac ↔ isolated-emulator bridge run: `build/native-verification/bridge-62g1t67u/success.json` confirms text and PNG in both directions; driver log `/tmp/klippa-final-bridge-driver.log`. This remains a named-pasteboard fixture, not the pending general-clipboard LAN test. Final debug app and test APK were successfully installed on the physical Fold.
