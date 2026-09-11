#!/usr/bin/env python3
"""Run existing opt-in native fixtures only; never build or select another device."""
import argparse
import base64
import fcntl
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
FIXTURE = Path('/tmp/klippa-audit-e2e')
AUDIT_SERIAL = 'emulator-5580'
FOLD_SERIAL = 'R5GL72NJTFM'


def stop(process):
    if process is not None and process.poll() is None:
        # Every child is launched in its own session; never signal an unrelated process.
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=8)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


def run(argv, timeout=20):
    process = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, start_new_session=True)
    try:
        output, _ = process.communicate(timeout=timeout)
    except BaseException:
        stop(process)
        raise RuntimeError('A bounded fixture command timed out or was interrupted') from None
    if process.returncode:
        raise RuntimeError('A fixture prerequisite command failed; no credentials were printed')
    return output.decode('utf-8', errors='replace').replace('\r', '')


def verify_target(adb, fold=False):
    prefix = [adb, '-s', FOLD_SERIAL if fold else AUDIT_SERIAL]
    if fold:
        model = run(prefix + ['shell', 'getprop', 'ro.product.model']).strip()
        actual_serial = run(prefix + ['shell', 'getprop', 'ro.serialno']).strip()
        qemu = run(prefix + ['shell', 'getprop', 'ro.kernel.qemu']).strip()
        booted = run(prefix + ['shell', 'getprop', 'sys.boot_completed']).strip()
        if model != 'SM-F971B' or actual_serial != FOLD_SERIAL or qemu == '1' or booted != '1':
            raise RuntimeError('Refusing target: Fold mode requires the authorized booted SM-F971B phone')
        return
    name = run(prefix + ['emu', 'avd', 'name']).strip()
    if name not in ('Klippa_Audit', 'Klippa_Audit\nOK'):
        raise RuntimeError('Refusing target: emulator-5580 must be Klippa_Audit')
    if run(prefix + ['shell', 'getprop', 'ro.kernel.qemu']).strip() != '1' or run(prefix + ['shell', 'getprop', 'sys.boot_completed']).strip() != '1':
        raise RuntimeError('The audit AVD must be an emulator and fully booted')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--derived-data', type=Path, default=Path('/tmp/klippa-xcode-build'))
    parser.add_argument('--fold', action='store_true', help='Use only the authorized R5GL72NJTFM / SM-F971B phone via owned adb reverse')
    args = parser.parse_args()
    serial = FOLD_SERIAL if args.fold else AUDIT_SERIAL
    if platform.system() != 'Darwin':
        raise RuntimeError('macOS is required')
    os.umask(0o077)
    lock_fd = os.open('/tmp/klippa-native-bridge.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    lock_info = os.fstat(lock_fd)
    if not stat.S_ISREG(lock_info.st_mode) or lock_info.st_uid != os.getuid():
        raise RuntimeError('Unsafe fixture lock file')
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise RuntimeError('Another native bridge harness is running') from None
    # Keep the fd open through cleanup. Existing fixture state belongs to another run.
    if FIXTURE.exists() or FIXTURE.is_symlink():
        info = FIXTURE.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise RuntimeError('Existing fixture directory must be private, owned and not a symlink')
    if (FIXTURE / 'enabled').exists() or (FIXTURE / 'enabled').is_symlink():
        raise RuntimeError('Existing native fixture marker: finish its owner run before retrying')
    developer = Path(os.environ.get('DEVELOPER_DIR', '/Applications/Xcode.app/Contents/Developer'))
    xcode = developer / 'usr/bin/xcodebuild'
    adb = shutil.which('adb')
    if not adb:
        sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'Library/Android/sdk'))))
        adb = str(sdk / 'platform-tools/adb')
    test_apk = ROOT / 'android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    test_runs = list((args.derived_data / 'Build/Products').glob('ClipSync_*.xctestrun'))
    if not xcode.is_file() or not Path(adb).is_file() or not test_apk.is_file() or len(test_runs) != 1:
        raise RuntimeError('Existing Xcode test products, one ClipSync xctestrun, adb and test APK are required')
    if 'guard !TestHost.isRunning else { return }' not in (ROOT / 'mac/ClipSync/App.swift').read_text():
        raise RuntimeError('Production startup XCTest guard is missing; review before running')
    verify_target(adb, args.fold)
    if not run([adb, '-s', serial, 'shell', 'pm', 'path', 'com.clipsync.app']).strip().startswith('package:'):
        raise RuntimeError('Install the disposable debug app and configure Shizuku on the selected authorized target first')
    output_parent = ROOT / 'build/native-verification'
    output_parent.mkdir(parents=True, exist_ok=True)
    output = Path(tempfile.mkdtemp(prefix='bridge-', dir=output_parent))
    FIXTURE.mkdir(mode=0o700, exist_ok=True)
    mac = None
    mac_log = None
    secret = encoded = ''
    android_started = False
    marker_owned = False
    android_complete = False
    reverse_owned = False
    try:
        verify_target(adb, args.fold)
        if args.fold:
            existing = run([adb, '-s', serial, 'reverse', '--list'])
            if any('tcp:17010' in line.split() for line in existing.splitlines()):
                raise RuntimeError('Refusing pre-existing reverse mapping involving tcp:17010')
            run([adb, '-s', serial, 'reverse', '--no-rebind', 'tcp:17010', 'tcp:17010'])
            reverse_owned = True
        run([adb, '-s', serial, 'install', '-r', '-t', str(test_apk)], timeout=60)
        marker_fd = os.open(str(FIXTURE / 'enabled'), os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
        os.write(marker_fd, b'owned by verify-native-bridge.py\n')
        os.close(marker_fd)
        marker_owned = True
        for name in ('pair.json', 'success.json', 'failure.json'):
            stale = FIXTURE / name
            if stale.is_symlink():
                raise RuntimeError('Unsafe existing fixture file')
            if stale.exists():
                if name != 'pair.json':
                    shutil.copyfile(stale, output / ('previous-' + name))
                stale.unlink()
        mac_log = (output / 'mac.log').open('wb')
        env = dict(os.environ, DEVELOPER_DIR=str(developer))
        mac = subprocess.Popen([str(xcode), 'test-without-building', '-xctestrun', str(test_runs[0].resolve()),
            '-destination', 'platform=macOS', '-only-testing:ClipSyncTests/NativeBridgeE2ETests/testNamedPasteboardAndroidBridgeOptIn',
            '-resultBundlePath', str(output / 'bridge.xcresult')], stdout=mac_log, stderr=subprocess.STDOUT,
            env=env, start_new_session=True)
        mac_deadline = time.monotonic() + 240
        pair_deadline = time.monotonic() + 60
        while not (FIXTURE / 'pair.json').is_file():
            if mac.poll() is not None or time.monotonic() >= pair_deadline:
                raise RuntimeError('Mac fixture failed to publish pairing within 60 seconds')
            time.sleep(0.2)
        pair_file = FIXTURE / 'pair.json'
        if pair_file.is_symlink() or pair_file.stat().st_size > 4096:
            raise RuntimeError('Unsafe pairing fixture')
        config = json.loads(pair_file.read_bytes())
        if config.get('host') != '10.0.2.2' or config.get('port') != 17010 or config.get('version') != 2:
            raise RuntimeError('Pairing fixture does not target the isolated Mac listener')
        secret = config.get('secret', '')
        if not re.fullmatch(r'[A-Za-z0-9_-]{43}', secret) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', config.get('fp', '')):
            raise RuntimeError('Malformed fixture credentials')
        if args.fold:
            config['host'] = '127.0.0.1'
        encoded = base64.b64encode(json.dumps(config).encode()).decode('ascii')
        verify_target(adb, args.fold)
        android_started = True
        android = run([adb, '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.clipsync.service.MacEndToEndTest', '-e', 'macE2E', 'true', '-e', 'physicalFold', str(args.fold).lower(), '-e', 'pairConfig', encoded,
            'com.clipsync.app.test/androidx.test.runner.AndroidJUnitRunner'], timeout=min(170, max(1, mac_deadline - time.monotonic())))
        (output / 'android.log').write_text(android.replace(encoded, '[redacted]').replace(secret, '[redacted]'))
        if not re.search(r'^OK \([1-9][0-9]* tests?\)$', android, re.M) or re.search(r'^(FAILURES!!!|INSTRUMENTATION_FAILED:|INSTRUMENTATION_ABORTED:|INSTRUMENTATION_STATUS_CODE: -[12]$|INSTRUMENTATION_RESULT: shortMsg=)', android, re.M):
            raise RuntimeError('Android fixture did not report explicit successful tests')
        android_complete = True
        try:
            code = mac.wait(timeout=max(1, mac_deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            raise RuntimeError('Mac fixture exceeded 240 seconds') from None
        success_file = FIXTURE / 'success.json'
        if code != 0 or not success_file.is_file() or success_file.is_symlink():
            raise RuntimeError('Mac fixture did not complete successfully')
        success = json.loads(success_file.read_text())
        if success.get('ok') is not True or success.get('macToAndroid') != ['text', 'png'] or success.get('androidToMac') != ['text', 'png']:
            raise RuntimeError('Mac fixture completion evidence is incomplete')
        (output / 'success.json').write_text(json.dumps(success, indent=2) + '\n')
        print(f'Native bridge passed: bidirectional text and PNG. Artifacts: {output}')
    finally:
        stop(mac)
        if mac_log:
            mac_log.close()
            log_path = output / 'mac.log'
            log = log_path.read_text(errors='replace')
            for value in (secret, encoded):
                if value:
                    log = log.replace(value, '[redacted]')
            log_path.write_text(log)
        if android_started and not android_complete:
            try:
                verify_target(adb, args.fold)
                run([adb, '-s', serial, 'shell', 'am', 'force-stop', 'com.clipsync.app'], timeout=10)
            except Exception:
                print(f'Fixture app cleanup could not be confirmed; inspect {serial}.', file=sys.stderr)
        if reverse_owned:
            try:
                verify_target(adb, args.fold)
                mappings = run([adb, '-s', serial, 'reverse', '--list'])
                if any(line.split()[-2:] == ['tcp:17010', 'tcp:17010'] for line in mappings.splitlines()):
                    run([adb, '-s', serial, 'reverse', '--remove', 'tcp:17010'])
            except Exception:
                print(f'Owned reverse cleanup could not be confirmed; inspect {serial} tcp:17010.', file=sys.stderr)
        if marker_owned:
            for name in ('enabled', 'pair.json'):
                try:
                    (FIXTURE / name).unlink()
                except FileNotFoundError:
                    pass
        os.close(lock_fd)


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        # Never print subprocess argv/output: instrumentation argv contains temporary credentials.
        message = str(error) if isinstance(error, RuntimeError) else type(error).__name__
        print(f'Native bridge failed: {message}', file=sys.stderr)
        sys.exit(1)
