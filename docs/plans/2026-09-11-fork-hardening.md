# ClipSync adaptation plan

Date: 11 September 2026. Baseline: `0357798cc0f4c8ad0102570c92ab348ae497f40a`.

The [source audit](../reviews/2026-09-11-native-app-audit.md) describes baseline commit `0357798`; preserve it as historical evidence. Native Android and Mac repairs have since been implemented on the isolated branch. This plan replaces the earlier greenfield direction.

## Implementation and verification status — 11 September 2026

Worktree: `/Users/aminakov/code/klippa/android-work`; branch: `feature/android-hardening-oneui`; Android checkpoint: `9c7489d`. The [continuation handoff](../handoff/android-implementation-progress.md) is the current execution record. See [native verification](../development/native-verification.md) for reproducible commands and [protocol v2](../protocol-v2.md) for the implemented contract. Checkboxes track complete implementation tasks; broader acceptance gates remain open even when their code exists.

Both native apps now implement independently verified SPKI pairing, single-use v2 QR enrollment, strict payload validation, bounded image handling, durable replay acceptance before effects, pause/revocation and content-free notifications. Mac TLS/Keychain/token initialization fails closed; listener readiness gates advertisement. Android owns discovery/recovery in its service and uses atomic Shizuku snapshots, serialized sends and an ephemeral latest-event outbox. Mac Bonjour/reachability lifecycle fixes reject stale callbacks and cancelled restarts. Unpaired first launch offers pairing once after listener readiness; paired startup and reconnect stay quiet. The reviewed pairing presentation generation fix cancels obsolete work after stop/revoke/dismiss; the final Mac suite now passes 92 tests and the clean installed application starts successfully. Generic files and automatic screenshot capture are disabled.

Verified evidence: Android checkpoint debug/release/unit/lint, 72 JVM tests, seven standard instrumentation tests and two separate real-Shizuku tests; later v2 and diagnostics tests bring the verified JVM total to 77. The latest default instrumentation runner reported `OK (12 tests)`; opt-in skips in that default run are not additional executed passes. Mac's final suite passed 92 tests; the opt-in pairing-window visual test passed separately, with actual QR OCR/image review. Actual Mac↔Android text and PNG pixel checks passed through both the dedicated emulator and the physical Fold over USB, with Android backgrounded and using Shizuku. These are synthetic named-Mac-pasteboard fixtures, not normal LAN setup or a soak.

The user subsequently released the Fold for this task: serial `R5GL72NJTFM`, model `SM-F971B`, Android 17. Cover-screen geometry is 1248×1972 physical pixels, density 420, font scale 1.0; Gboard is unchanged. Shizuku 13.6 was started through ADB and the user personally granted “Allow all the time.” Cover One UI screenshots were reviewed. The actual unfolded inner display is 2448×1848 pixels and was visually reviewed; four physical UI tests passed. Exact One UI version and full usable window/dp/folding matrix remain to record. `emulator-5554` remains prohibited; the guarded default harness still targets only `emulator-5580` / `Klippa_Audit`.

Open gates: origin/session/sequence protocol and full ordering/acknowledgement semantics, WebSocket allocation before application validation, normal LAN setup, the wider physical image-provider/destination matrix, inner-display/fold transitions/accessibility, latency/battery measurements and 48-hour soak. The universal Mac Release build passed, but ad hoc hardened-runtime launch failed library validation without a suitable signing identity; the Release runtime gate remains open. Clean app-only Debug packaging has passed deep/strict signature verification and is installed; after user-approved Keychain access, startup, LAN port 7010 and Bonjour are live. Normal LAN pairing remains in progress. No publication or hardened binary release is claimed.

## Product contract

One personal Android phone and one Mac; existing keyboard; no root or bootloader changes. Copy text or an image in an ordinary application and paste in a compatible application on the other device. Start with an awake Mac and a reachable local network. An ongoing Android connection notification and reactivating Shizuku after reboot are acceptable setup/lifecycle costs; stealing focus for each copy is not.

Do not promise delivery while a device is offline, force-stopped or asleep. Show a clear paused/unavailable state and retain only the latest eligible event briefly. Image paste works only in apps accepting image content URIs; copying a web link to an image is not necessarily copying image bytes.

No automatic screenshots, copied-file uploads, clipboard history, cloud relay or multi-peer functionality in the initial hardened release.

**Android UI requirement:** revamp the app to match the One UI visual language in the user's five Samsung screenshots. Galaxy Z Fold cover and inner displays, rotation, folding transitions and split-screen are first-class targets. The exact model, One UI version and display scaling will be recorded during device validation; do not infer them from screenshot pixels. The Mac application retains native macOS conventions.

The reference images are visual input only. Do not copy their contacts, phone numbers, email addresses or calendar entries into fixtures, documentation assets or published screenshots. Use synthetic content for all visual tests.

## Batch 0 — Establish a reproducible development baseline

- [x] Keep an isolated working branch from the reviewed commit and preserve the audit as the baseline.
- [x] Configure full Xcode and run the Mac build/unit suite; Android compile/unit/lint already pass in this audit environment.
- [ ] Record the actual Galaxy Z Fold model, Android/One UI version, cover/inner window dimensions in dp, font/display scaling, current keyboard and image source/destination apps during device testing. Do not change payment-security settings.
- [ ] Record signing identities and decide whether the fork keeps the application ID. Keeping an ID still requires the same signing key to update an existing installation; otherwise data migration/reinstallation is needed.
- [x] Correct installer/release references from upstream to this fork before using automated installation.
- [ ] Remove generated `rust/target` content from future commits in a separate repository cleanup. Do not rewrite published history as part of this step.

Deliverable: both native builds reproducible from the pinned source and a non-sensitive test installation. No published release yet.

## Batch 1 — Close trust and data-handling defects

Relevant audit findings: S1–S7. Most work is in `PairingApi`, `ClipClient`, `SettingsScreen/ViewModel`, Mac startup/pairing/server, payload validation, `PasteboardInjector`, and notifications.

- [x] Fail closed on TLS and Keychain initialization errors. No listener or mDNS advertisement starts in a degraded cryptographic state.
- [x] Put pinned identity and a cryptographically random single-use secret in the pairing QR. Authenticate before credential exchange; keep mDNS as discovery only.
- [x] Make externally delivered pairing links open a review screen. Existing trust cannot be replaced without an explicit user action.
- [x] Restrict pairing availability to an explicit short-lived session; rate-limit by connection address plus global/session budget, with bounded memory.
- [x] Record durable peer/nonce acceptance before effects and reject duplicate application. A retry has at most one clipboard effect; acceptance does not guarantee a successful write after a crash.
- [x] Generate cache names locally; prevent traversal and symlink escape. Disable generic file injection/capture for initial scope.
- [ ] Reject malformed timestamps without arithmetic traps; bound payload, stream and decoded-image dimensions before allocation.
- [x] Add device revocation and close sessions belonging to revoked credentials.
- [x] Disable permanent image saving, screenshot observation and clipboard content in notifications by default. Implement sensitive-item policy and bounded cache expiration.

Implementation notes: both sides require independently verified trust and validate timestamp/MIME/base64/size limits. Cache paths are generated locally; generic files are rejected. Hashed peer/event replay keys persist before effects, and current authorization is checked at the Mac write. The v2 QR secret is implemented on both sides. The combined pre-allocation task remains unchecked because OkHttp allocates complete WebSocket messages before application validation; bounded decoding does not close that transport-level issue.

Required tests: malicious first-pair endpoint, spoofed mDNS fingerprint, deep-link replacement attempt, certificate mismatch after pairing, used/expired pairing secret, failure-injected Keychain/TLS startup, traversal including absent name/malicious nonce, replayed authenticated event, rate-header rotation, Int64 bounds, oversized/compressed image, and revocation of an open socket.

Gate: all security regressions pass. A receiving phone or network peer cannot silently change trust or choose a local output path. Personal clipboard use starts only after this gate and a smoke test.

## Batch 2 — Make text synchronization correct and recoverable

Relevant findings: R1, R2, R4, R5. Preserve transport libraries; replace synchronization orchestration.

- [x] Separate stable peer identity/credentials from discovered endpoints (`PeerOperation` on Android and token identity on Mac).
- [x] Move discovery and endpoint refresh into the connection service. Network changes preserve pairing; authenticate every candidate against the stored identity.
- [x] Use an appropriate connected-device foreground-service configuration with documented prerequisites, lifecycle handling and recoverable errors.
- [x] Separate `networkConnected`, `helperAuthorized`, `helperRunning` and `clipboardReadable` readiness. A WebSocket alone must not mean “sync working.”
- [ ] Create one serialized event reducer on each device with event ID, origin, session, monotonic sequence, full-content digest and expiry.
- [x] Deduplicate and resolve local-vs-remote conflicts before changing the clipboard. Remove timing-only echo suppression and one-thread-per-send ordering.
- [ ] Acknowledge application of events; maintain a bounded latest-event outbox. Expired/older events cannot overwrite a newer local copy after reconnect.
- [x] Return atomic clipboard snapshots from the Shizuku helper; use explicit supported API-signature adapters and meaningful failures.
- [ ] Validate initial network callbacks, Wi-Fi/cellular coexistence, DHCP change, Mac restart, airplane mode, screen off, Shizuku death, permission loss and force-stop recovery.

Implementation notes: `PeerOperation` captures fingerprint/token independently of endpoint updates; serialized handling uses nonce checks, snapshot digests and own-write markers, with a short-lived latest-event outbox. Both native replay records survive restart. Mac responds with `applied:true/false`, but Android currently treats HTTP success as success without surfacing that distinction. Origin/session/monotonic sequence fields and a complete cross-device ordering protocol remain unimplemented. Ambiguous requests are not retried. The new diagnostics mapper distinguishes granted Shizuku permission from a helper that has not started because the app is unpaired; its four new JVM tests passed as part of the 77-test suite.

Gate: at least 100 deliberate text changes in each direction apply correctly in a controlled test; repeated content, rapid A→B changes and remote A→local B races produce no stale overwrite. Include Unicode, multiline text, large text at the limit and same-content intentional copies. Measure latency distribution; target p95 under two seconds while both devices are active and reachable, then adjust based on evidence. This is a target, not a current claim.

## Batch 3 — Remove focus changes from image synchronization

Relevant findings: R3 and S7. This is the highest platform-dependent work and should have a prototype gate before polishing UI.

- [x] Prototype a Shizuku operation that snapshots and opens the current clipboard image within the process holding its URI grant.
- [x] Transfer bytes through a bounded stream/file descriptor. Keep the privileged interface limited to current clipboard content; no arbitrary path/URI reader.
- [ ] Test whether clipboard providers grant usable access to the shell identity on the actual phone. If not, document the limitation and retain explicit Share as the fallback instead of silently starting a focus-taking Activity.
- [x] Remove automatic `SendClipActivity` launches. The bounded shell-image prototype is implemented; ordinary provider failures use explicit Share rather than launching an Activity. The physical synthetic provider bridge passed; acceptance across actual third-party source/destination apps remains open.
- [x] On receipt, expose a narrowly scoped app-owned image URI with correct MIME and clipboard grants. Keep backing data alive long enough for deferred paste; delete expired data without breaking the current clipboard item.
- [x] Keep transfer work cancellable; an old image finishing late cannot replace newer clipboard content.
- [x] Verify image notification construction without reusing recycled bitmaps; prefer a generic notification for normal sync.

Physical USB evidence covers synthetic text/PNG and pixel equality with Android backgrounded and the real Shizuku helper. It does not complete the following matrix.

Device matrix: Gallery/Photos, Chrome image copy, screenshot copied through the system UI, messaging app image copy where supported; paste into at least two actual destination apps. Test PNG/JPEG, transparency, large dimensions, inaccessible/expired URI, unsupported MIME, app process death, and paste several minutes after receiving.

Gate: 20 representative images in each direction, no foreground Activity changes, no silent failures, correct paste data and bounded memory/cache. If shell-side URI access cannot meet the contract on the target phone, stop and decide whether explicit image sharing is acceptable; do not invest in a new backend to solve a local permission problem.

## Batch 4 — One UI redesign and Galaxy Z Fold adaptation

This is required release scope, not optional polish. Design tokens and static previews can be developed after Batch 0; integrate real status/actions after Batches 1–2, and complete visual/performance validation after Batch 3. Preserve all security gates.

### Visual specification from the supplied references

The two Mobile networks screenshots establish settings rows, switches and anchored selection menus. Calendar settings establishes grouped rows, inset dividers, helper text and pill buttons. Phone establishes expandable detail within a group and rounded navigation surfaces; Calendar's drawer establishes selected-row treatment. Borrow these patterns only where the app needs them: do not add a drawer or three tabs just to imitate the screenshots.

| Element | Planned treatment |
|---|---|
| Surfaces | Pale neutral gray page, white rounded groups, restrained separators; soft elevation for floating menus only. Replace the existing green/neumorphic theme. |
| Color | Deep teal titles and selected values, muted blue enabled switches, neutral gray secondary text. Approximate starting palette: background `#F0F1F3`, surface `#FFFFFF`, title `#07506B`, accent `#79B1D2`; validate on device and adjust for contrast. These are design estimates, not sampled Samsung tokens. |
| Typography | System sans-serif, strong page titles, readable regular row labels, smaller secondary values. Respect font scaling; do not bundle a proprietary Samsung font. |
| Shape and spacing | Start with 24–28 dp group corners, 16–24 dp outer gutters, 16–20 dp row padding, and at least 56 dp row height. Rows grow for wrapped text. Values are starting tokens to refine against the references, not pixel conversions. |
| Controls | Rounded switches, restrained outline icons, generous pill actions, anchored rounded selection menus with a checkmark. Keep platform semantics and at least 48 dp touch targets. |
| Header and motion | Generous title spacing on tall windows; compact header when height is scarce or content scrolls. Subtle press, expand and selection transitions; no continuous decorative effects. |
| Themes | Follow system light/dark mode by default, with an optional override. Dark mode uses corresponding dark surfaces and accessible contrast; references only establish the light appearance. |

### Screen structure

Keep one simple landing screen titled **ClipSync**. Put the paired Mac name, an honest status (Ready, Paused, Reconnecting, or Setup needed), and the sync switch first. Follow with grouped settings: **Connection**, **Clipboard**, and **Privacy**. Open **Setup & diagnostics** from a settings row; show it prominently only when action is needed. Normal status can show a last-success timestamp without revealing clipboard content.

Connection details contain peer identity, route and disconnect/remove actions. Clipboard settings contain text/image preferences and supported explicit-send fallback. Privacy contains sensitive-content handling and notification preferences. Pairing is a dedicated review screen showing the selected Mac and a clear confirmation action; theme changes must not obscure trust replacement or permission prompts. Explain Shizuku in setup when the user must act, keeping protocol fields and raw errors in diagnostics.

### Adaptive layout contract

| Available window/posture | Layout |
|---|---|
| Narrow cover screen or narrow split-screen | Single scrollable column; details open as a separate destination; labels wrap and actions remain reachable above system/keyboard insets. |
| Medium width | Centered single column with a readable maximum width (initial target 600 dp); do not assume an unfolded phone has enough usable width for two panes. |
| Expanded width with sufficient pane space | Settings list/status on the left and selected detail on the right, initially about 1:1 with minimum pane widths around 320 dp. Collapse to one pane if constraints or font scale make either pane unusable. |
| Short landscape window | Compact toolbar; independently scrollable content; no large fixed header or screen-height-dependent spacing. |
| Separating fold/occluding hinge | Respect reported fold bounds. In book posture place panes on either side when viable; in tabletop posture use a compact status area above and scrollable controls below, falling back to a usable single pane if space is insufficient. |

Use current **window** constraints and width/height size classes, with Jetpack WindowManager/Compose adaptive posture information. Do not branch on a device model, fixed aspect ratio, screenshot resolution, or orientation alone. A flat crease is not automatically an occluded region. Reposition popups/dialogs on resize and keep critical controls out of separating/occluded fold bounds. These mechanisms follow [Android's fold-aware Compose guidance](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware); choose compatible stable dependency versions during implementation.

Preserve destination, selected setting, scroll positions and unsent form input across resize, rotation and Activity recreation. Do not persist a one-time pairing secret beyond its intended session lifetime. Folding must not start duplicate services, repeat pairing, reset sync preferences or resend a clipboard event. Handle edge-to-edge system bars, cutouts, navigation gestures, keyboard and Samsung taskbar insets. Keep the app resizable with no forced orientation or fixed aspect-ratio restrictions.

### Implementation tasks and file map

All Android paths below are relative to `android/app/src/main/java/com/clipsync/` unless stated otherwise.

- [x] Replace palette/typography in `ui/theme/ClipSyncTheme.kt`; migrate `ui/theme/NeuComponents.kt` into reusable settings groups, rows, switches and pill actions in `ui/components/OneUiSettingsComponents.kt`. Remove obsolete shadow/glow treatments and update call sites.
- [x] Refactor `ui/SettingsScreen.kt` and existing `ui/sections/` into the screen structure above. Keep service state and business actions in `ui/SettingsViewModel.kt`; expose accessible status text and recovery actions.
- [x] Add `ui/layout/AdaptiveSettingsLayout.kt` to select panes from current constraints/posture, with shared destinations and stable selection state. Use saved UI state without duplicating connection ownership.
- [x] Update `app/MainActivity.kt` and `android/app/src/main/AndroidManifest.xml` for resize/inset behavior as needed. Add compatible WindowManager/Compose adaptive dependencies through `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`.
- [x] Adapt pairing review, error banners, menus and setup dialogs to both layouts. Maintain one TalkBack toggle action per settings switch row, clear focus order, labels for icon-only actions, and non-color status cues.
- [x] Add Compose previews/visual baselines using synthetic states: unpaired, ready, paused, reconnecting, Shizuku unavailable, expired pairing and long peer names. Cover light/dark and enlarged fonts.
- [ ] Add meaningful instrumentation tests in `android/app/src/androidTest/java/com/clipsync/ui/` for resize state preservation, pane navigation/back behavior, accessible switches and pairing-confirmation visibility. Add Macrobenchmark or equivalent frame-timing evidence for scrolling and pane changes on a release-like build.

### Visual and foldable acceptance gate

- [ ] Review screenshots against the supplied references for hierarchy, colors, spacing, grouped corners, switches and menus. Capture actual app screens on both the Galaxy cover and inner display, portrait and landscape, including one narrow split-screen case.
- [ ] Exercise widths around layout breakpoints and a short window in previews/emulation; measure actual device dp dimensions rather than treating arbitrary preview presets as Galaxy specifications.
- [ ] Test default and 200% font scaling, increased display size, long English/Russian labels, TalkBack, keyboard-open pairing input, gesture navigation and visible taskbar. No clipped controls, overlapping labels, accidental horizontal scrolling or inaccessible confirmation actions.
- [ ] Fold/unfold and rotate during settings navigation, pairing entry and an image transfer. Selection/input survive, pairing is not resubmitted, and sync continues without duplicated side effects. Explicitly test Activity recreation.
- [ ] Record scrolling, group expansion, popup opening and pane transitions on the phone. Inspect frame timings against the current display refresh-rate budget; resolve recurring app-caused jank, main-thread I/O and sustained dropped-frame sequences. Avoid an unmeasured promise of 120 fps.

Gate: all essential flows work on both displays and resized windows; reference-based visual review passes; accessibility and continuity checks pass; performance evidence comes from the actual Galaxy, not only an emulator. UI implementation, emulator checks, physical cover/inner-display visual review and four physical UI tests are complete; the broader physical-device acceptance matrix remains open.

## Batch 5 — Prove daily-use reliability and publish the fork

- [ ] Run at least a 48-hour device soak, including screen-off periods and an Android foreground-service timeout test. Restore any temporary device test configuration afterward.
- [ ] Measure battery impact against a comparable baseline, capture p50/p95 latency and count dropped/stale events using synthetic event IDs, without logging clipboard contents.
- [ ] Test no-root Shizuku restart after reboot and clear recovery guidance. Confirm the existing keyboard and payment apps still work on the user's device.
- [ ] Test remote operation through a private VPN as a separate supported mode: trusted identity stays fixed while endpoint selection changes. Mac sleep remains an explicit limitation.
- [ ] Pin dependencies, review advisories/licenses, run release builds, and ensure CI builds/tests both applications with correct toolchain and artifact paths.
- [ ] Produce signed Android and macOS artifacts from the reviewed commit; publish checksums and release notes listing limitations. Use safe replacement/rollback in the installer.
- [ ] Review the concrete diff and test evidence before publishing changes or binaries.

Gate: Batch 4 visual/foldable acceptance is complete, no unresolved S1–S3 defects, no stale clipboard writes in the test matrix, no secrets in logs/notifications, and documented recovery for unavailable components. Release notes must not claim Apple-equivalent always-on behavior.

## Effort and decision points

Budget roughly **three to five engineering weeks** for hardening, the One UI redesign, foldable adaptation and device validation (including roughly one additional week for the UI scope), conditional on full Xcode availability and shell-side image access working. This is a planning estimate, not a delivery commitment. Security fixes and text correctness should land in small reviewable changes before image work. Reserve separate time for a failing OEM/Android compatibility case.

Reconsider a larger rewrite only if the image prototype cannot meet the device contract or the serialized core proves impossible to isolate cleanly. Neither outcome is established by this audit. Continue from the handoff with normal LAN pairing/clipboard verification, Release signing and the wider physical folding/accessibility/provider matrix. Diagnostics tests, clean Debug packaging, user-approved startup and inner-display visual review are complete. Mac startup/trust/replay implementation and the emulator/physical USB native bridges are complete; do not repeat them as unfinished baseline work. Keep emulator-5554 outside this task and retain the specific physical-phone authorization boundary.

Final review addendum: Android pairing responses are now invalidated by later Pause/Remove actions with guarded credential persistence and service startup; seven additional JVM regressions pass (84 total). Normal LAN setup is waiting on the phone VPN permitting the Mac local address; do not bypass the VPN or claim a LAN pass from the earlier USB fixture.
