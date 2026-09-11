#!/usr/bin/env bash
# Deliberately no target override: this harness must never select a personal device.
set -euo pipefail

readonly VERIFY_SERIAL='emulator-5580'
readonly VERIFY_AVD='Klippa_Audit'
readonly VERIFY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

usage() {
  cat <<'USAGE'
Usage: scripts/verify-android.sh [--instrumentation-only]

Default: run unit tests, lint, debug and test-APK builds; install both APKs and
run instrumentation only on emulator-5580, whose AVD name must be Klippa_Audit.
--instrumentation-only reuses existing build outputs (for CI after build checks).
No physical device or alternate emulator can be selected.
USAGE
}

instrumentation_only=false
case "${1:-}" in
  '') ;;
  --instrumentation-only) instrumentation_only=true ;;
  --help|-h) usage; exit 0 ;;
  *) usage >&2; exit 2 ;;
esac
[[ $# -le 1 ]] || { usage >&2; exit 2; }

adb_bin="$(command -v adb || true)"
if [[ -z "$adb_bin" ]]; then
  sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [[ -n "$sdk_root" && -x "$sdk_root/platform-tools/adb" ]] || {
    echo 'error: add Android SDK platform-tools to PATH or set ANDROID_HOME' >&2
    exit 1
  }
  adb_bin="$sdk_root/platform-tools/adb"
fi
readonly adb_bin

verify_target() {
  local avd_output qemu booted
  avd_output="$("$adb_bin" -s "$VERIFY_SERIAL" emu avd name | tr -d '\r')" || {
    echo 'error: audit emulator is unavailable; no device action was attempted' >&2
    return 1
  }
  if [[ "$avd_output" != "$VERIFY_AVD" && "$avd_output" != "$VERIFY_AVD"$'\nOK' ]]; then
    echo 'error: emulator-5580 is not the Klippa_Audit AVD; refusing installation/tests' >&2
    return 1
  fi
  qemu="$("$adb_bin" -s "$VERIFY_SERIAL" shell getprop ro.kernel.qemu | tr -d '\r')"
  booted="$("$adb_bin" -s "$VERIFY_SERIAL" shell getprop sys.boot_completed | tr -d '\r')"
  [[ "$qemu" == 1 && "$booted" == 1 ]] || {
    echo 'error: target is not a fully booted Android emulator' >&2
    return 1
  }
}

verify_target
reports="$VERIFY_ROOT/android/app/build/reports/local-verification"
mkdir -p "$reports"
run_reports="$(mktemp -d "$reports/run-$(date -u +%Y%m%dT%H%M%SZ).XXXXXX")"
printf 'Verification logs: %s\n' "$run_reports"

if [[ "$instrumentation_only" == false ]]; then
  (
    cd "$VERIFY_ROOT/android"
    ./gradlew --console=plain :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
  ) 2>&1 | tee "$run_reports/gradle.log"
fi

app_apk="$VERIFY_ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
test_apk="$VERIFY_ROOT/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$app_apk" && -f "$test_apk" ]] || {
  echo 'error: debug app/test APK missing; run the default verification command first' >&2
  exit 1
}

# Recheck after the build and before each mutation in case an AVD was replaced.
verify_target
"$adb_bin" -s "$VERIFY_SERIAL" install -r -t "$app_apk" 2>&1 | tee "$run_reports/install-app.log"
verify_target
"$adb_bin" -s "$VERIFY_SERIAL" install -r -t "$test_apk" 2>&1 | tee "$run_reports/install-tests.log"
verify_target
"$adb_bin" -s "$VERIFY_SERIAL" shell am instrument -w -r \
  com.clipsync.app.test/androidx.test.runner.AndroidJUnitRunner \
  2>&1 | tr -d '\r' | tee "$run_reports/instrumentation.log"

# adb/am can return exit 0 even when JUnit failed. Require explicit, non-empty success.
if ! grep -Eq '^OK \([1-9][0-9]* tests?\)$' "$run_reports/instrumentation.log" ||
   grep -Eq '^(FAILURES!!!|INSTRUMENTATION_FAILED:|INSTRUMENTATION_ABORTED:|INSTRUMENTATION_STATUS_CODE: -[12]$|INSTRUMENTATION_RESULT: shortMsg=)' "$run_reports/instrumentation.log"; then
  echo 'error: instrumentation failed or did not report a non-empty successful test run' >&2
  exit 1
fi
printf 'Android verification passed on %s (%s).\n' "$VERIFY_SERIAL" "$VERIFY_AVD"
