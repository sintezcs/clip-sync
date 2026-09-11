# Native verification

This is a reproducible verification recipe for the current Android and Mac implementation, not a statement that every gate has passed. Record the commit, toolchain, commands, exit status, test skips and result artifacts for each run. The [implementation handoff](../handoff/android-implementation-progress.md) records session evidence; the [audit](../reviews/2026-09-11-native-app-audit.md) describes the original baseline.

## Mac toolchain and dependency graph

Use Xcode 26.3 and macOS deployment target 14.0. Set `DEVELOPER_DIR` on each local command so another task's selected toolchain is unaffected. The deployment override applies to package builds too; setting only the application target's deployment version did not satisfy package availability checks.

From the repository root:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer ./scripts/verify-macos.sh
```

The script builds Release, then builds/tests Debug, without opening or installing the production application. It creates a private unique directory under root `build/native-verification/` containing isolated DerivedData, a copy of the resolved graph, commit/toolchain identifiers, separate build/test logs and `.xcresult` bundles. It uses local ad hoc signing and does not verify distribution signing, notarization or release installation. An explicit `DEVELOPER_DIR` overrides the default shown above; supply Xcode 26.3. It never changes global `xcode-select` state.

The script requires `Package.resolved`, asks Xcode to use that graph, and checks for the application startup test guard before execution. The source check is a tripwire, not proof that arbitrary future startup changes remain safe. XCTest's actual configuration/runtime markers trigger the guard; the script does not manufacture XCTest environment paths. It explicitly excludes both `NativeBridgeE2ETests` and `PairingWindowVisualTests`, even if their opt-in markers are already present. Each requires a separate coordinated run. After an owned opt-in run, remove `/tmp/klippa-audit-ui-enabled` or `/tmp/klippa-audit-e2e/enabled` as appropriate and remove temporary pairing files. Do not remove a marker belonging to another active run; the default harness skips these tests without mutating markers.

Inspect XCTest results and skips, not just compilation or a shell exit status. CI selects `/Applications/Xcode_26.3.app`, uses `platform=macOS` and the host architecture with `ONLY_ACTIVE_ARCH=YES`; it does not force ARM64 on an Intel runner. The latest Mac suite passed 92 tests with zero failures; the earlier lifecycle checkpoint passed 86 (`/tmp/klippa-mac-lifecycle-tests.log`). The separate opt-in pairing-window visual test passed (`/tmp/klippa-mac-pairing-ui-final.log`), with QR OCR and actual image review. The universal Mac Release ad hoc build also passed (`/tmp/klippa-mac-release.log`); deep/strict codesign verification exited zero. This does not establish notarization or an entire harness rerun.

Keep `mac/ClipSync.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved` under version control with the project constraints. Commit resolution changes together and review the complete transitive graph. This intentionally supersedes the old repository guidance to ignore the lockfile. The project uses its original supported dependency ranges; the temporary Hummingbird/NIO/AsyncHTTPClient downgrades were discarded.

### Reproduced linker defect and remedy

Adding `HummingbirdTesting` to the hosted test target caused Xcode to emit dynamic package frameworks for the shared application/test graph. The generated `NIOTransportServices` link omitted `NIOFoundationEssentialsCompat`, although inspection confirmed the required `ByteBuffer.getData(at:length:)` symbol existed in that binary. An explicit Essentials dependency on the application did not repair the framework link.

The Foundation module split shipped in [SwiftNIO 2.99.0](https://github.com/apple/swift-nio/releases/tag/2.99.0), through [upstream change #3567](https://github.com/apple/swift-nio/pull/3567). Downgrading below that split merely exposed another transitive failure: `HummingbirdCore` omitted NIOHTTP1 when linking HTTP pipeline/parser symbols. The [Hummingbird 2.22.0 manifest](https://github.com/hummingbird-project/hummingbird/blob/2.22.0/Package.swift) declares the intermediate NIOHTTPTypesHTTP1 dependency, illustrating why changing NIO versions did not solve the general dynamic-graph problem.

Removing the test target's `HummingbirdTesting` product dependency restored static package linking and eliminated those linker failures in the experiment. Compilation then reached an application WebSocket API mismatch with the temporarily older graph. The implementation uses direct HTTP fixtures instead of reintroducing that shared testing product, and the original dependency ranges are restored. The refreshed graph subsequently built and passed the 86-test lifecycle suite. No dependency checkout patch is required.

Do not reinstate the downgrade pins as a fix. Review upstream releases and advisories periodically and update the lockfile deliberately; neither a resolved graph nor a successful build establishes that its dependencies are advisory-free.

## Local packaging and signing boundary

The universal Release build and `codesign --verify --deep --strict` passed, but launching that ad hoc hardened-runtime app on macOS 15.7.5 failed: dyld rejected embedded `libswiftCompatibilitySpan.dylib` under library validation because the signature did not provide the required Team ID relationship. There are no usable signing identities on this machine. The preserved Release bundle is `/tmp/klippa-local-release-signing-pending.app`. **Release runtime/distribution signing is still an open gate.** Build and signature-integrity success did not establish launchability.

Apple documents that [library validation permits Apple-signed or same-Team-ID libraries](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.security.cs.disable-library-validation). A consistent Apple Development identity is the preferred development route; Developer ID signing and notarization are separate distribution work. Do not remove the compatibility library, disable Release hardening, or add a blanket library-validation exception to hide this failure.

The earlier test-host installation contained test PlugIns/frameworks and failed nested XCUIAutomation signature verification. It has been replaced by a clean app-only Debug build from `/tmp/klippa-local-app-build`, with no XCTest payload and passing deep/strict signature verification. The clean app is installed at `/Users/aminakov/Applications/ClipSync.app`. The user approved Keychain access; application startup, LAN port 7010 and Bonjour are live. Normal LAN pairing/clipboard verification remains in progress. Never operate SecurityAgent or rewrite ACLs to bypass a user's authorization.

For a clean local-only ad hoc development app, use a **fresh** DerivedData directory and the plain `build` action. Keep it separate from all XCTest products. A clean app-only build using this approach has now been verified and installed; use a new directory when reproducing it:

```bash
# From repository root. Choose a new directory for each independent packaging run.
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer xcodebuild build \
  -project mac/ClipSync.xcodeproj -scheme ClipSync -configuration Debug \
  -destination 'platform=macOS' -derivedDataPath build/mac-local-clean \
  -onlyUsePackageVersionsFromResolvedFile \
  MACOSX_DEPLOYMENT_TARGET=14.0 ONLY_ACTIVE_ARCH=YES \
  DEVELOPMENT_TEAM='' CODE_SIGN_IDENTITY=- \
  CODE_SIGNING_REQUIRED=YES CODE_SIGNING_ALLOWED=YES ENABLE_HARDENED_RUNTIME=NO
```

The command-local Debug runtime override is for local development without an identity; the project's Release setting remains enabled. Before installation, inspect the resulting bundle for unexpected `.xctest`, XCTest/XCUIAutomation frameworks or test PlugIns, and verify its signatures. If contaminated, rebuild in a new directory rather than deleting nested code from an installed bundle. Retain a stable final application path and let the user resolve any new authorization request. Keychain access remains subject to the user's approval; do not recreate secrets to bypass it. Final launch has passed for the installed Debug app; normal LAN pairing remains a separate pending check.

## First-launch onboarding verification

An unpaired real application startup offers pairing once after its first successful listener bind. Paired startup and reconnect remain quiet. Generation checks and task cancellation prevent a suspended presentation from reopening after stop, revocation or dismissal; replacing a session invalidates the old window callback before it closes. Independent source review accepted the lifecycle fix. The final Mac suite passes 92 tests including those policies, and the clean installed application starts successfully. Hosted XCTest still bypasses production onboarding entirely.

## Isolation and coverage

Hosted XCTest must return from application launch before production Keychain, menu, clipboard watcher, listener or Bonjour side effects. Storage tests use injected failures or unique `com.clipsync.tests.*` Keychain services. Clipboard unit tests use an in-memory pasteboard. Never substitute the general Mac pasteboard or production credentials in these tests.

`NativeBridgeE2ETests` is separately opt-in: without `/tmp/klippa-audit-e2e/enabled` it skips. Its fixture uses a uniquely named `NSPasteboard`, a unique test Keychain namespace, a temporary replay journal and a loopback listener on port 17010. The pairing fixture file contains temporary credentials: keep the directory private, do not publish it in logs or artifacts, and remove the opt-in marker after the coordinated run. A skipped bridge fixture is not evidence of Mac–Android interoperability. Coordinate both endpoints before enabling it.

Mac checks should cover startup/storage failures, partial TLS identity rejection, pin and pairing validation, expiry/attempt limits, malformed payloads, durable replay across recreation, authorization revocation, paused/stale clipboard writes and route rejection before effects. Native test coverage and successful compilation do not establish a 48-hour soak or physical-device compatibility.

## Android and device boundaries

Use JDK 17 and the Android SDK. The [Android harness guide](android-verification.md) describes `./scripts/verify-android.sh`, reports, CI instrumentation and opt-in Shizuku checks. Current CI also builds the release APK. The workflow file is authoritative for the Mac job; the older Android guide's statement that the Mac toolchain is unchanged predates this update.

The default Android harness remains restricted to the dedicated `Klippa_Audit` AVD on **emulator-5580**. The user subsequently authorized the physical **R5GL72NJTFM / SM-F971B** phone; only the bridge harness's explicit `--fold` mode selects it, verifying model/serial and owning its USB reverse mapping. **emulator-5554 remains prohibited.** Do not select arbitrary attached devices or issue untargeted mutations. Use synthetic data; physical-phone authorization does not authorize inspecting personal clipboard content.

The normal suite does not prove real Shizuku behavior: separately opt in, inspect individual statuses and confirm permission/helper readiness. A full-flow check must distinguish Android-only TLS fixtures from the actual Mac listener. Preserve evidence for both directions, text and supported images, pause/revoke, reconnect and duplicate suppression.

## Remaining release gates

Physical One UI cover-screen review passed. The actual unfolded 2448×1848 inner display was also visually reviewed, and four physical UI instrumentation tests passed (`/tmp/klippa-fold-ui-tests.log`). The full folding/rotation/accessibility matrix remains open; provider cancellation across real third-party providers, accessibility/performance and a 48-hour soak remain distinct gates. No battery or latency measurements have been made, and mDNS discovered the real Mac at `192.168.2.115:7010`, but the installed app now starts and serves LAN port 7010 with Bonjour live; normal LAN pairing and clipboard validation remain in progress. Android's WebSocket library can allocate an incoming message before application payload validation; application size checks do not eliminate that allocation exposure. There is no offline delivery or cloud relay guarantee. See the [current protocol contract](../protocol-v2.md) for accepted-versus-applied semantics and clock requirements. Record new pass results only after inspecting their actual artifacts.

## Run the existing native bridge fixtures

After compiling the Mac XCTest products and Android debug/test APKs, start the dedicated `Klippa_Audit` AVD on `emulator-5580`, install the disposable debug app, and enable its Shizuku permission. Then run:

```bash
DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer python3 scripts/verify-native-bridge.py --derived-data /tmp/klippa-xcode-build
```

Add `--fold` only for the explicitly authorized physical phone over USB. The physical device currently runs Android 17, with Shizuku 13.6 started through ADB and permission granted personally by the user. Gboard remains unchanged.

This command performs no build. It installs the existing test APK, verifies the selected target identity, starts only the opt-in Mac bridge XCTest, and invokes only `MacEndToEndTest`. It requires both explicit Android test success and Mac exit success plus the fixture completion record. The Mac fixture uses an isolated named pasteboard and test Keychain namespace; no production Mac clipboard or credentials are used.

The harness refuses concurrent runs using `flock` and an existing opt-in marker. Existing fixture directories must be owned by the current user, mode `0700`, and not symlinks. Temporary pairing data is passed as base64 directly in subprocess arguments without printing it. Pairing readiness is bounded to 60 seconds, Android instrumentation to 170 seconds, and the Mac runner to 240 seconds. Cleanup removes this run's marker and pairing file, stops only its child process group, and force-stops the audit app only after an incomplete Android run. Result bundles, sanitized logs, and completion evidence are stored in a unique private `build/native-verification/bridge-*` directory. An unsuccessful run is not interoperability evidence.

## Recorded native bridge evidence

Both actual Mac↔Android fixtures passed text and PNG pixel checks in both directions while Android was backgrounded and using its real Shizuku helper:

- Emulator: `build/native-verification/bridge-55jv2r2i/`.
- Physical Fold over USB: `build/native-verification/bridge-2p9tdtu2/`.

Each directory contains successful completion JSON, Android/Mac logs and an XCTest result bundle. These named-Mac-pasteboard synthetic fixtures prove the checked native flow, not general Mac clipboard/LAN setup, arbitrary provider compatibility or a long-running soak.

Android checkpoint `9c7489d` passed 72 JVM tests, seven standard instrumentation tests and two separately opted-in real-Shizuku cases, plus debug/release build and lint. One later pairing-v2 unit test brings verified JVM coverage to 73. The four new authorization-diagnostics tests have now passed, bringing verified JVM coverage to 77; the final diagnostics release build also passed. See the [handoff](../handoff/android-implementation-progress.md) for the current device profile and next gates.

## Final Android pairing review

The final cancellation/commit-gate and typed-error guidance revision adds seven passing JVM regressions (84 total; `/tmp/klippa-android-final-pairing.log`). Normal LAN testing is waiting on the phone VPN route: `192.168.2.115` currently uses `tun0`, and TCP7010 times out despite the Mac listener and Bonjour being live. User action to allow LAN traffic or pause the VPN is pending. Do not treat the earlier USB bridge as normal LAN evidence.

The final Android pairing revision also passed a fresh native Mac ↔ isolated-emulator bridge run: `build/native-verification/bridge-62g1t67u/success.json` confirms text and PNG in both directions; driver log `/tmp/klippa-final-bridge-driver.log`. This remains a named-pasteboard fixture, not the pending general-clipboard LAN test. Final debug app and test APK were successfully installed on the physical Fold.
