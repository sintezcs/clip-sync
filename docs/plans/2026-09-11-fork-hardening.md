# ClipSync adaptation plan

Date: 11 September 2026. Baseline: `0357798cc0f4c8ad0102570c92ab348ae497f40a`.

The [source audit](../reviews/2026-09-11-native-app-audit.md) establishes why this fork is worth retaining and what must change. This plan replaces the earlier greenfield direction. The audit describes the unchanged baseline commit; Android implementation has since progressed on the isolated branch described below. Mac findings in that audit remain open.

## Implementation status — Android only, 11 September 2026

The implementation is in `/Users/aminakov/code/klippa/android-work`, branch `feature/android-hardening-oneui`. Use the [continuation handoff](../handoff/android-implementation-progress.md) for the latest execution record, the [Android implementation review](../reviews/android-implementation-review.md) for safety limits, and the [verification harness](../development/android-verification.md) for repeatable checks. A checked item below means its Android implementation is present; it does not close a Mac, physical-device or release gate. Mixed-platform tasks remain unchecked where their Mac or protocol half is pending.

Android now has reviewed manual fingerprint pairing without TOFU, strict payload validation, bounded/cancellable image reads, service-owned endpoint recovery, serialized sends, a latest-event outbox, durable peer-scoped replay recording, atomic helper snapshots, content-free notifications and a One UI-style adaptive settings interface. Existing pairings require renewed verification. The current Mac `/pair` wire format is retained; its future QR single-use-secret protocol has not been invented or implemented on Android alone.

The controller reports passing debug/release builds, unit tests, lint and all seven instrumentation tests on the dedicated audit emulator. These instrumentation tests cover the service/transport path, settings behavior and stalled shared-image-provider cancellation. The shell-UID image probe also succeeded with synthetic content; full Shizuku lifecycle verification is still underway. No final unit-test count or full-device result is asserted here. Compact/expanded/light/dark/enlarged-text emulator screenshots are saved in `docs/verification/android/`; they are not physical Galaxy acceptance evidence.

**Device restriction:** do not operate the user's Fold8 or `emulator-5554`; another task owns them. Only the dedicated `emulator-5580` / `Klippa_Audit` AVD is authorized for this task. Use synthetic content and the serial-checking harness. Physical-phone validation remains deferred until the user explicitly changes that restriction.

**Open gates:** Mac TLS/Keychain startup, authenticated QR protocol, server replay/revocation/rate limits and Mac data-retention fixes; actual Mac interoperability; provider grants and foldable behavior on the physical phone; cross-device ordering under clock skew; WebSocket frame allocation before the application size check; latency/battery measurements and the 48-hour soak. No publication, signed-release readiness or safe personal-clipboard claim follows from the Android checks.

## Product contract

One personal Android phone and one Mac; existing keyboard; no root or bootloader changes. Copy text or an image in an ordinary application and paste in a compatible application on the other device. Start with an awake Mac and a reachable local network. An ongoing Android connection notification and reactivating Shizuku after reboot are acceptable setup/lifecycle costs; stealing focus for each copy is not.

Do not promise delivery while a device is offline, force-stopped or asleep. Show a clear paused/unavailable state and retain only the latest eligible event briefly. Image paste works only in apps accepting image content URIs; copying a web link to an image is not necessarily copying image bytes.

No automatic screenshots, copied-file uploads, clipboard history, cloud relay or multi-peer functionality in the initial hardened release.

**Android UI requirement:** revamp the app to match the One UI visual language in the user's five Samsung screenshots. Galaxy Z Fold cover and inner displays, rotation, folding transitions and split-screen are first-class targets. The exact model, One UI version and display scaling will be recorded during device validation; do not infer them from screenshot pixels. The Mac application retains native macOS conventions.

The reference images are visual input only. Do not copy their contacts, phone numbers, email addresses or calendar entries into fixtures, documentation assets or published screenshots. Use synthetic content for all visual tests.

## Batch 0 — Establish a reproducible development baseline

- [x] Keep an isolated working branch from the reviewed commit and preserve the audit as the baseline.
- [ ] Configure full Xcode and run the Mac build/unit suite; Android compile/unit/lint already pass in this audit environment.
- [ ] Record the actual Galaxy Z Fold model, Android/One UI version, cover/inner window dimensions in dp, font/display scaling, current keyboard and image source/destination apps during device testing. Do not change payment-security settings.
- [ ] Record signing identities and decide whether the fork keeps the application ID. Keeping an ID still requires the same signing key to update an existing installation; otherwise data migration/reinstallation is needed.
- [ ] Correct installer/release references from upstream to this fork before using automated installation.
- [ ] Remove generated `rust/target` content from future commits in a separate repository cleanup. Do not rewrite published history as part of this step.

Deliverable: both native builds reproducible from the pinned source and a non-sensitive test installation. No published release yet.

## Batch 1 — Close trust and data-handling defects

Relevant audit findings: S1–S7. Most work is in `PairingApi`, `ClipClient`, `SettingsScreen/ViewModel`, Mac startup/pairing/server, payload validation, `PasteboardInjector`, and notifications.

- [ ] Fail closed on TLS and Keychain initialization errors. No listener or mDNS advertisement starts in a degraded cryptographic state.
- [ ] Put pinned identity and a cryptographically random single-use secret in the pairing QR. Authenticate before credential exchange; keep mDNS as discovery only.
- [x] Make externally delivered pairing links open a review screen. Existing trust cannot be replaced without an explicit user action.
- [ ] Restrict pairing availability to an explicit short-lived session; rate-limit by connection address plus global/session budget, with bounded memory.
- [ ] Add nonce/event idempotency so a valid retry has one clipboard effect.
- [ ] Generate cache names locally; prevent traversal and symlink escape. Disable generic file injection/capture for initial scope.
- [ ] Reject malformed timestamps without arithmetic traps; bound payload, stream and decoded-image dimensions before allocation.
- [ ] Add device revocation and close sessions belonging to revoked credentials.
- [ ] Disable permanent image saving, screenshot observation and clipboard content in notifications by default. Implement sensitive-item policy and bounded cache expiration.

Android implementation notes: manual pairing requires an independently compared SPKI pin; discovery cannot supply trust. Android uses locally generated image-cache names, rejects generic files, validates timestamp/MIME/base64/size limits, suppresses sensitive captures, removes screenshot observation, and records hashed peer/event replay keys before effects. These changes cover Android portions of the unchecked cross-platform tasks above. True pre-allocation WebSocket bounds and the Mac security repairs remain pending.

Required tests: malicious first-pair endpoint, spoofed mDNS fingerprint, deep-link replacement attempt, certificate mismatch after pairing, used/expired pairing secret, failure-injected Keychain/TLS startup, traversal including absent name/malicious nonce, replayed authenticated event, rate-header rotation, Int64 bounds, oversized/compressed image, and revocation of an open socket.

Gate: all security regressions pass. A receiving phone or network peer cannot silently change trust or choose a local output path. Personal clipboard use starts only after this gate and a smoke test.

## Batch 2 — Make text synchronization correct and recoverable

Relevant findings: R1, R2, R4, R5. Preserve transport libraries; replace synchronization orchestration.

- [ ] Introduce stable `PairedPeer` identity/credentials separate from discovered endpoints.
- [x] Move discovery and endpoint refresh into the connection service. Network changes preserve pairing; authenticate every candidate against the stored identity.
- [x] Use an appropriate connected-device foreground-service configuration with documented prerequisites, lifecycle handling and recoverable errors.
- [x] Separate `networkConnected`, `helperAuthorized`, `helperRunning` and `clipboardReadable` readiness. A WebSocket alone must not mean “sync working.”
- [ ] Create one serialized event reducer on each device with event ID, origin, session, monotonic sequence, full-content digest and expiry.
- [ ] Deduplicate and resolve local-vs-remote conflicts before changing the clipboard. Remove timing-only echo suppression and one-thread-per-send ordering.
- [ ] Acknowledge application of events; maintain a bounded latest-event outbox. Expired/older events cannot overwrite a newer local copy after reconnect.
- [ ] Return atomic clipboard snapshots from the Shizuku helper; use explicit supported API-signature adapters and meaningful failures.
- [ ] Validate initial network callbacks, Wi-Fi/cellular coexistence, DHCP change, Mac restart, airplane mode, screen off, Shizuku death, permission loss and force-stop recovery.

Android implementation notes: `PeerOperation` captures fingerprint/token separately from endpoint updates; a serialized reducer uses nonce checks, snapshot digests and own-write markers, with a short-lived latest-event outbox. Replay records survive process restart and reject capacity/corruption failures. The legacy Mac protocol still lacks origin/session sequences and idempotent acknowledgements, so ambiguous requests are not retried. The full cross-device reducer/acknowledgement tasks above remain open.

Gate: at least 100 deliberate text changes in each direction apply correctly in a controlled test; repeated content, rapid A→B changes and remote A→local B races produce no stale overwrite. Include Unicode, multiline text, large text at the limit and same-content intentional copies. Measure latency distribution; target p95 under two seconds while both devices are active and reachable, then adjust based on evidence. This is a target, not a current claim.

## Batch 3 — Remove focus changes from image synchronization

Relevant findings: R3 and S7. This is the highest platform-dependent work and should have a prototype gate before polishing UI.

- [x] Prototype a Shizuku operation that snapshots and opens the current clipboard image within the process holding its URI grant.
- [x] Transfer bytes through a bounded stream/file descriptor. Keep the privileged interface limited to current clipboard content; no arbitrary path/URI reader.
- [ ] Test whether clipboard providers grant usable access to the shell identity on the actual phone. If not, document the limitation and retain explicit Share as the fallback instead of silently starting a focus-taking Activity.
- [x] Remove automatic `SendClipActivity` launches. The bounded shell-image prototype is implemented; ordinary provider failures use explicit Share rather than launching an Activity. Physical-provider acceptance remains open.
- [ ] On receipt, expose a narrowly scoped app-owned image URI with correct MIME and clipboard grants. Keep backing data alive long enough for deferred paste; delete expired data without breaking the current clipboard item.
- [ ] Keep transfer work cancellable; an old image finishing late cannot replace newer clipboard content.
- [x] Verify image notification construction without reusing recycled bitmaps; prefer a generic notification for normal sync.

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
- [ ] Refactor `ui/SettingsScreen.kt` and existing `ui/sections/` into the screen structure above. Keep service state and business actions in `ui/SettingsViewModel.kt`; expose accessible status text and recovery actions.
- [x] Add `ui/layout/AdaptiveSettingsLayout.kt` to select panes from current constraints/posture, with shared destinations and stable selection state. Use saved UI state without duplicating connection ownership.
- [x] Update `app/MainActivity.kt` and `android/app/src/main/AndroidManifest.xml` for resize/inset behavior as needed. Add compatible WindowManager/Compose adaptive dependencies through `android/gradle/libs.versions.toml` and `android/app/build.gradle.kts`.
- [ ] Adapt pairing review, error banners, menus and setup dialogs to both layouts. Maintain one TalkBack toggle action per settings switch row, clear focus order, labels for icon-only actions, and non-color status cues.
- [x] Add Compose previews/visual baselines using synthetic states: unpaired, ready, paused, reconnecting, Shizuku unavailable, expired pairing and long peer names. Cover light/dark and enlarged fonts.
- [ ] Add meaningful instrumentation tests in `android/app/src/androidTest/java/com/clipsync/ui/` for resize state preservation, pane navigation/back behavior, accessible switches and pairing-confirmation visibility. Add Macrobenchmark or equivalent frame-timing evidence for scrolling and pane changes on a release-like build.

### Visual and foldable acceptance gate

- [ ] Review screenshots against the supplied references for hierarchy, colors, spacing, grouped corners, switches and menus. Capture actual app screens on both the Galaxy cover and inner display, portrait and landscape, including one narrow split-screen case.
- [ ] Exercise widths around layout breakpoints and a short window in previews/emulation; measure actual device dp dimensions rather than treating arbitrary preview presets as Galaxy specifications.
- [ ] Test default and 200% font scaling, increased display size, long English/Russian labels, TalkBack, keyboard-open pairing input, gesture navigation and visible taskbar. No clipped controls, overlapping labels, accidental horizontal scrolling or inaccessible confirmation actions.
- [ ] Fold/unfold and rotate during settings navigation, pairing entry and an image transfer. Selection/input survive, pairing is not resubmitted, and sync continues without duplicated side effects. Explicitly test Activity recreation.
- [ ] Record scrolling, group expansion, popup opening and pane transitions on the phone. Inspect frame timings against the current display refresh-rate budget; resolve recurring app-caused jank, main-thread I/O and sustained dropped-frame sequences. Avoid an unmeasured promise of 120 fps.

Gate: all essential flows work on both displays and resized windows; reference-based visual review passes; accessibility and continuity checks pass; performance evidence comes from the actual Galaxy, not only an emulator. UI implementation and limited emulator verification are present; this physical-device acceptance gate remains open.

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

Reconsider a larger rewrite only if the image prototype cannot meet the device contract or the serialized core proves impossible to isolate cleanly. Neither outcome is established by this audit. The next cross-platform work is Batch 1's Mac fail-closed startup, pairing trust and replay repair after completing its build baseline. Continue remaining Android/Shizuku verification from the handoff; do not repeat already completed implementation work or use the prohibited devices.
