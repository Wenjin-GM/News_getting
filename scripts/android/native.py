"""Test/probe the ECH experiment or compile its Android libraries with local tools.

Nothing is installed into the application automatically. A probe makes a real
anonymous HTTPS request; desktop success is not a phone/network acceptance.
"""
import argparse
import json
import os
import pathlib
import runpy
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
PREPARE = runpy.run_path(str(pathlib.Path(__file__).with_name('prepare-native.py')))
CACHE = PREPARE['CACHE']
HOST = PREPARE['HOST']
MANIFEST = ROOT / 'android/native/linuxdo/Cargo.toml'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['test', 'probe', 'build'])
    parser.add_argument('--feed', choices=['rss', 'json', 'dns'], default='rss')
    parser.add_argument('--dns-mode', choices=['auto', 'fallback', 'cache'], default='auto',
                        help='Developer probe only; APK always uses automatic failover')
    parser.add_argument('--cache-dir', type=pathlib.Path, help='Probe cache, inside this workspace')
    parser.add_argument('--offline', action='store_true')
    args = parser.parse_args()
    env = PREPARE['environment']()
    env.update(CARGO_NET_RETRY='0', CARGO_HTTP_TIMEOUT='20', NO_PROXY='*')
    paths = [CACHE / 'python-tools/cmake/data/bin']
    # Existing host compiler is read only; no MSYS package installation.
    if pathlib.Path('C:/msys64/ucrt64/bin/gcc.exe').is_file():
        paths.append(pathlib.Path('C:/msys64/ucrt64/bin'))
    env['PATH'] = os.pathsep.join(map(str, paths)) + os.pathsep + env.get('PATH', '')
    manager = CACHE / 'rustup.exe'
    if not manager.is_file():
        raise SystemExit('Prepare workspace Rust: python scripts/android/prepare-native.py --rust')
    command = [str(manager), 'run', 'stable-' + HOST, 'cargo']
    common = ['--manifest-path', str(MANIFEST), '--locked']
    if args.offline:
        common.append('--offline')
    if args.action == 'test':
        subprocess.run(command + ['test'] + common, env=env, cwd=ROOT, check=True)
    elif args.action == 'probe':
        subprocess.run(command + ['build', '--bin', 'linuxdo-probe'] + common,
                       env=env, cwd=ROOT, check=True)
        binary = pathlib.Path(env['CARGO_TARGET_DIR']) / 'debug/linuxdo-probe.exe'
        probe = [str(binary), args.feed, args.dns_mode]
        if args.cache_dir:
            directory = args.cache_dir.resolve()
            if not directory.is_relative_to(ROOT):
                raise SystemExit('Probe cache must stay inside this workspace')
            directory.mkdir(parents=True, exist_ok=True)
            probe.append(str(directory))
        result = subprocess.run(probe, env=env, cwd=ROOT,
                                capture_output=True, text=True, encoding='utf-8', timeout=100)
        report = ROOT / '.local/reports' / ('linuxdo-ech-' + args.feed + '-' + args.dns_mode + '.json')
        report.parent.mkdir(parents=True, exist_ok=True)
        record = dict(exitCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
        report.write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
        print(result.stdout or result.stderr)
        raise SystemExit(result.returncode)
    else:
        ndk = CACHE / 'ndk/android-ndk-r30'
        clang = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
        if not (clang / 'clang.exe').is_file():
            raise SystemExit('Android NDK is not installed. No Android library was built.')
        env.update(ANDROID_NDK_HOME=str(ndk), ANDROID_NDK_ROOT=str(ndk))
        env['PATH'] = str(clang) + os.pathsep + env['PATH']
        for target, linker, cc in [
            ('aarch64-linux-android', 'aarch64-linux-android26-clang.cmd', 'aarch64-linux-android26-clang.cmd'),
            ('armv7-linux-androideabi', 'armv7a-linux-androideabi26-clang.cmd', 'armv7a-linux-androideabi26-clang.cmd'),
            ('x86_64-linux-android', 'x86_64-linux-android26-clang.cmd', 'x86_64-linux-android26-clang.cmd'),
        ]:
            # Rust invokes .cmd via cmd.exe. CC/CXX use clang.exe directly and
            # explicit target flags so C build helpers need not execute a BAT.
            key = target.replace('-', '_')
            env['CARGO_TARGET_' + key.upper() + '_LINKER'] = str(clang / linker)
            env['CC_' + key] = str(clang / 'clang.exe')
            env['CXX_' + key] = str(clang / 'clang++.exe')
            env['AR_' + key] = str(clang / 'llvm-ar.exe')
            ctarget = cc.replace('-clang.cmd', '')
            env['CFLAGS_' + key] = '--target=' + ctarget
            env['CXXFLAGS_' + key] = '--target=' + ctarget
            subprocess.run(command + ['build', '--lib', '--release', '--target', target] + common,
                           env=env, cwd=ROOT, check=True)
        print('Android libraries built under .cache/native-build; APK integration is separate.')


if __name__ == '__main__':
    main()
