# Android verification harness

Run from the repository root with JDK 17 and the Android SDK configured:

```bash
./scripts/verify-android.sh
```

First boot the existing synthetic-test AVD `Klippa_Audit` on emulator console port `5580` using Android Studio or the Android SDK emulator. The harness neither creates nor launches an AVD. It refuses to proceed unless `adb -s emulator-5580 emu avd name` identifies exactly `Klippa_Audit`, the target reports emulator hardware, and boot has completed. There is no serial or AVD override. Attached phones and other emulators are never selected.

The default command runs unit tests, Android lint, and debug app/test APK builds. It then rechecks the emulator before installing each APK and before running the complete `AndroidJUnitRunner` suite. Installation updates only the audit emulator's ClipSync test application. Instrumentation can change its synthetic app state and UI settings; use a dedicated AVD without personal accounts or clipboard content.

Build and instrumentation output is saved in a unique directory under `android/app/build/reports/local-verification/`, which is ignored by Git. The harness does not dump logcat, preferences, pairing links or clipboard data. Test fixtures must remain synthetic. Reports from Gradle continue to use their normal directories under `android/app/build/reports/`.

A shell exit code alone is not sufficient evidence of successful instrumentation: `am instrument` can exit zero after a test failure. The harness additionally requires an explicit `OK (N tests)` result with at least one test and rejects reported instrumentation failures/errors.

For CI or a just-completed local build:

```bash
./scripts/verify-android.sh --instrumentation-only
```

This option skips Gradle and requires existing debug app/test APKs. It retains all emulator identity checks and result validation. Use the default command if the outputs might be stale. Neither mode publishes a release or installs a release APK.

## Continuous integration

The Android CI job keeps the existing unit, lint, debug, Android-test and release-build checks. It then creates an API 35 x86_64 `Klippa_Audit` emulator on port 5580 and invokes the same guarded instrumentation path. The Android checkout includes only the Android project, scripts and workflow files; generated Rust content is excluded. Failed-run reports are uploaded by the existing artifact step. The Mac job and its toolchain are unchanged.

Emulator setup uses the maintained third-party [ReactiveCircus Android Emulator Runner](https://github.com/ReactiveCircus/android-emulator-runner), pinned to [release v2.38.0's commit](https://github.com/ReactiveCircus/android-emulator-runner/commit/a421e43855164a8197daf9d8d40fe71c6996bb0d). Its documented `avd-name`, `emulator-port`, KVM setup and script inputs are used directly; it is not a Google-owned action. Instrumentation invocation follows the [Android command-line testing documentation](https://developer.android.com/studio/test/command-line).

The emulator suite validates Android UI and local behavior. It does not replace Galaxy cover/inner-display, Shizuku/provider compatibility, TalkBack, frame-timing, Mac interoperability or long-running device-soak gates.

## Opt-in real Shizuku checks

`ShizukuIntegrationTest` is skipped by default, including CI. On the dedicated audit emulator, start Shizuku and grant ClipSync permission through the manager UI first. After building/installing the current debug app and test APKs, the separately authorized operator can run:

```bash
adb -s emulator-5580 shell am instrument -w -r \
  -e shizuku true \
  -e class com.clipsync.shizuku.ShizukuIntegrationTest \
  com.clipsync.app.test/androidx.test.runner.AndroidJUnitRunner
```

Confirm the target is still `Klippa_Audit` before running this command. The opt-in tests also skip when Shizuku is not running or permission is missing; a skip is not a successful helper validation. Inspect the test statuses as well as the final result.

The tests bind the real privileged helper, check atomic text snapshots, read a synthetic image through its actual clipboard URI grant while the Activity is stopped, reject a stale image identity, and destroy the helper. A second test runs the real connection service against a local pinned TLS fixture, verifies automatic text/image POSTs, nonce-echo suppression and no `SendClipActivity` launch. Clipboard copies are seeded in the target Activity; network completion is awaited with that Activity stopped. The fixture never exchanges data with a Mac or logs personal clipboard contents. Teardown stops sync, removes fixture pairing and clears the synthetic clipboard. These tests require a disposable test installation and are not for personal-device data.
