"""Verify 3.7 -> 3.8 and real JNI on the disposable yilan-api30 emulator only.

Requires emulator-env.ps1, the dedicated ADB server on 5039 and build-smoke.py.
Refuses an existing app installation; never targets physical phones or other VMs.
"""
import json
import pathlib
import subprocess
import time

ROOT = pathlib.Path(__file__).resolve().parents[2]
ADB = ROOT / '.cache/android/platform-tools/platform-tools/adb.exe'
REPORTS = ROOT / '.local/reports/android-smoke'
REPORTS.mkdir(parents=True, exist_ok=True)
command = [str(ADB), '-P', '5039', '-s', 'emulator-5580']
results = []


def adb(*args, timeout=90):
    result = subprocess.run(command + list(args), cwd=ROOT, capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=timeout)
    results.append(dict(args=list(args), exitCode=result.returncode, output=result.stdout + result.stderr))
    (REPORTS / 'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


start = time.monotonic()
ready = False
while time.monotonic() - start < 240:
    try:
        probe = subprocess.run(command + ['shell', 'getprop', 'sys.boot_completed'],
                               capture_output=True, text=True, timeout=8)
        if probe.returncode == 0 and probe.stdout.strip() == '1':
            ready = True
            break
    except subprocess.TimeoutExpired:
        pass
    print('Waiting for workspace emulator boot: ' + str(int(time.monotonic() - start)) + 's', flush=True)
    time.sleep(8)
if not ready:
    (REPORTS / 'boot-status.txt').write_text('Boot did not complete within 240 seconds; no APK installation/test performed.\n', encoding='utf-8')
    raise SystemExit('Emulator boot incomplete: no runtime acceptance claimed.')
if adb('emu', 'avd', 'name', timeout=10).splitlines()[0].strip() != 'yilan-api30':
    raise SystemExit('Refusing unexpected emulator')
if adb('shell', 'pm', 'path', 'cn.yilan.news').strip():
    raise SystemExit('Refusing to overwrite pre-existing app data; use a new disposable workspace AVD.')
for file in ['dist/android/yilan-standalone-3.7.0-dev.apk', '.cache/android-smoke/yilan-smoke.apk']:
    print(adb('install', '-r', '-t', str(ROOT / file)), flush=True)


def instrument(mode):
    output = adb('shell', 'am', 'instrument', '-w', '-e', 'mode', mode,
                 'cn.yilan.news.test/cn.yilan.news.test.DeviceSmoke', timeout=90)
    print(output, flush=True)
    if 'result=PASS' not in output or 'INSTRUMENTATION_CODE: -1' not in output:
        raise RuntimeError('Instrumentation did not pass: ' + mode)


instrument('seed')
print(adb('install', '-r', str(ROOT / 'dist/android/yilan-standalone-3.8.0-dev.apk')), flush=True)
instrument('verify')
instrument('native')
adb('shell', 'am', 'start', '-W', '-n', 'cn.yilan.news/.MainActivity')
time.sleep(4)
screen = subprocess.run(command + ['exec-out', 'screencap', '-p'], cwd=ROOT, capture_output=True, timeout=30, check=True)
(REPORTS / 'startup.png').write_bytes(screen.stdout)
print('PASS: emulator upgrade state, Android JNI invocation and native Activity startup. See private reports for actual fetch outcome.')
