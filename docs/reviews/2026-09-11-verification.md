# Audit verification record

Source commit: `0357798cc0f4c8ad0102570c92ab348ae497f40a`. Date: 11 September 2026.

## Android baseline

Executed from `android/`:

```sh
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug --console=plain
```

Result: **BUILD SUCCESSFUL**, 6m 44s; 34 tasks executed. JUnit XML reports: **29 tests, 0 failures, 0 errors, 0 skipped**. Lint completed with **76 warnings and no errors**. Warnings include trust-all certificate managers, hidden API use, dependency versions and selected-photo access; successful lint is not a clean security assessment.

Gradle downloaded its required tooling and installed already-license-accepted SDK Platform 35 and Build Tools 34. No APK was installed, and no Android device settings were changed.

Generated local reports (ignored build output): `android/app/build/reports/tests/testDebugUnitTest/index.html` and `android/app/build/reports/lint-results-debug.html`. These reports are reproducible build artifacts, not committed audit evidence.

## Swift focused probes

Compiled the reviewed `ClipPayload.swift`, `HMACValidator.swift`, and `RateLimiter.swift` unchanged with a standalone runner using the local Swift compiler. The path probe copies the destination URL construction into a temporary directory; it does not call AppKit or the HTTP server.

Observed output:

```text
REPLAY: identical signed body accepted twice
RATE LIMIT: fixed key 5/10; changing caller-supplied key 10/10
PATH: outside intended directory = true (temporary sandbox only)
EXTREME TIMESTAMP: subprocess return code -5
```

The final probe intentionally passes `Int64.min` to payload validation in a subprocess, which terminates with a trap on the baseline. No network requests or user-file writes are performed. The rate test demonstrates the limiter's key behavior; source inspection establishes that the HTTP routes use the untrusted header as that key. It is not an HTTP load test.

Reproduce on macOS with Swift/Command Line Tools:

```sh
python3 docs/reviews/probes/run.py
```

The runner reads the checkout's current source, so results should change after fixes. It is a diagnostic harness, not an asserting regression suite. Convert the relevant cases into production tests during hardening.

## Not verified

The full macOS application and XCTest suite could not run: `xcodebuild -version` reports that the selected developer directory is Command Line Tools, and no full Xcode installation was found. Standalone Swift success does not establish that the Hummingbird/AppKit application builds.

No phone clipboard operations, background lifecycle/Doze tests, pairing attack integration, VPN roaming, image-provider grants, payment-app compatibility, battery soak, release signing, dependency vulnerability audit, or published-binary provenance verification was performed. Findings distinguish source-confirmed behavior from these outstanding tests.
