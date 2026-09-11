#!/usr/bin/env bash
# Local, isolated builds only; never launches the production app or selects a device.
set -euo pipefail

readonly VERIFY_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ $# -ne 0 ]]; then
  echo 'Usage: DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer scripts/verify-macos.sh' >&2
  exit 2
fi
[[ "$(uname -s)" == Darwin ]] || { echo 'error: macOS is required' >&2; exit 1; }
readonly verify_developer_dir="${DEVELOPER_DIR:-/Applications/Xcode.app/Contents/Developer}"
[[ -x "$verify_developer_dir/usr/bin/xcodebuild" ]] || {
  echo 'error: set DEVELOPER_DIR to the Xcode 26.3 Developer directory' >&2
  exit 1
}
readonly resolved="$VERIFY_ROOT/mac/ClipSync.xcodeproj/project.xcworkspace/xcshareddata/swiftpm/Package.resolved"
[[ -f "$resolved" ]] || { echo 'error: checked-in Package.resolved is missing' >&2; exit 1; }

# The AppDelegate guard must remain before production startup; XCTest supplies its
# own configuration and runtime markers. Do not fabricate XCTest environment paths.
if ! grep -q 'guard !TestHost.isRunning else { return }' "$VERIFY_ROOT/mac/ClipSync/App.swift"; then
  echo 'error: expected production-startup XCTest guard is missing; review App.swift before testing' >&2
  exit 1
fi

umask 077
mkdir -p "$VERIFY_ROOT/build/native-verification"
readonly verify_output="$(mktemp -d "$VERIFY_ROOT/build/native-verification/run-$(date -u +%Y%m%dT%H%M%SZ).XXXXXX")"
printf 'Mac verification artifacts: %s\n' "$verify_output"
DEVELOPER_DIR="$verify_developer_dir" "$verify_developer_dir/usr/bin/xcodebuild" -version | tee "$verify_output/toolchain.txt"
git -C "$VERIFY_ROOT" rev-parse HEAD > "$verify_output/commit.txt"
cp "$resolved" "$verify_output/Package.resolved"

common=(
  -project "$VERIFY_ROOT/mac/ClipSync.xcodeproj"
  -scheme ClipSync
  -destination 'platform=macOS'
  -derivedDataPath "$verify_output/DerivedData"
  -onlyUsePackageVersionsFromResolvedFile
  MACOSX_DEPLOYMENT_TARGET=14.0
  ONLY_ACTIVE_ARCH=YES
  CODE_SIGN_IDENTITY=-
  CODE_SIGNING_REQUIRED=YES
  CODE_SIGNING_ALLOWED=YES
)

# Release build is not installed or opened; signing is local ad hoc, not distribution.
DEVELOPER_DIR="$verify_developer_dir" "$verify_developer_dir/usr/bin/xcodebuild" build \
  "${common[@]}" -configuration Release \
  -resultBundlePath "$verify_output/release-build.xcresult" \
  2>&1 | tee "$verify_output/release-build.log"

# Explicit exclusion remains effective even if another coordinated run left its
# bridge or UI-audit opt-in marker on disk. Opt-in checks need separate invocations.
DEVELOPER_DIR="$verify_developer_dir" "$verify_developer_dir/usr/bin/xcodebuild" test \
  "${common[@]}" -configuration Debug \
  -skip-testing:ClipSyncTests/NativeBridgeE2ETests/testNamedPasteboardAndroidBridgeOptIn \
  -skip-testing:ClipSyncTests/PairingWindowVisualTests/testPairingWindowVisualOptIn \
  -resultBundlePath "$verify_output/tests.xcresult" \
  2>&1 | tee "$verify_output/tests.log"

printf 'Build/test commands completed. Inspect test counts, skips and failures in %s/tests.xcresult.\n' "$verify_output"
