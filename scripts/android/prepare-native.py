"""Optional portable Rust/NDK tools; no registry, PATH, certificates or system installers.

Rustup is used as a standalone manager, NOT run in installer mode. All persistent
state, archives and extracted packages stay under this checkout's .cache.
"""
import argparse
import hashlib
import json
import os
import pathlib
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = ROOT / '.cache' / 'native'
HOST = 'x86_64-pc-windows-gnu'
RUSTUP_URL = 'https://static.rust-lang.org/rustup/dist/x86_64-pc-windows-msvc/rustup-init.exe'
RUSTUP_SHA256 = '38027d51d69a5d05493627a0634dbea52d6cd793c8f072d2373d3f4163eec7db'
NDK_URL = 'https://dl.google.com/android/repository/android-ndk-r30-windows.zip'
NDK_SHA1 = '9bf167a1985fa7d4a036186b78f702eab9179408'
ANDROID_TARGETS = ['aarch64-linux-android', 'armv7-linux-androideabi', 'x86_64-linux-android']


def digest(path, algorithm):
    h = hashlib.new(algorithm)
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def download(url, path, algorithm, expected):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.is_file() and digest(path, algorithm) == expected:
        return
    # Ignore proxy environment/settings: these tools must be obtainable directly.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    print('Downloading official package: ' + url, flush=True)
    with opener.open(url, timeout=30) as response, path.open('wb') as stream:
        shutil.copyfileobj(response, stream, 1024 * 1024)
    if digest(path, algorithm) != expected:
        raise RuntimeError('Official checksum mismatch: ' + path.name)


def environment():
    env = os.environ.copy()
    env.update(RUSTUP_HOME=str(CACHE / 'rustup'), CARGO_HOME=str(CACHE / 'cargo'),
               CARGO_TARGET_DIR=str(ROOT / '.cache' / 'native-build'),
               TEMP=str(ROOT / '.cache' / 'tmp'), TMP=str(ROOT / '.cache' / 'tmp'),
               RUSTUP_NO_UPDATE_CHECK='1', CARGO_INCREMENTAL='0')
    pathlib.Path(env['TEMP']).mkdir(parents=True, exist_ok=True)
    return env


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--rust', action='store_true')
    parser.add_argument('--ndk', action='store_true')
    parser.add_argument('--ndk-cn', action='store_true', help='Use Google official dl.google.cn download host')
    parser.add_argument('--ndk-cdn', action='store_true', help='Use Google official gvt1 CDN redirector')
    args = parser.parse_args()
    if not args.rust and not args.ndk and not args.ndk_cn and not args.ndk_cdn:
        parser.error('Select --rust and/or --ndk')
    CACHE.mkdir(parents=True, exist_ok=True)
    records = []
    if args.rust:
        manager = CACHE / 'rustup.exe'
        download(RUSTUP_URL, manager, 'sha256', RUSTUP_SHA256)
        env = environment()
        # Renaming rustup-init.exe to rustup.exe selects manager mode; no
        # rustup-init/self install, shell profiles or uninstall registry entry.
        subprocess.run([str(manager), 'toolchain', 'install', 'stable-' + HOST,
                        '--profile', 'minimal', '--no-self-update', '--target',
                        ','.join(ANDROID_TARGETS)], env=env, cwd=ROOT, check=True)
        subprocess.run([str(manager), 'run', 'stable-' + HOST, 'rustc', '--version'],
                       env=env, cwd=ROOT, check=True)
        records.append(dict(tool='rustup-manager', url=RUSTUP_URL, sha256=RUSTUP_SHA256,
                            host=HOST, targets=ANDROID_TARGETS))
    if args.ndk or args.ndk_cn or args.ndk_cdn:
        archive = CACHE / 'android-ndk-r30-windows.zip'
        destination = CACHE / 'ndk'
        ndk_url = NDK_URL.replace('dl.google.com', 'dl.google.cn') if args.ndk_cn else NDK_URL
        if args.ndk_cdn:
            ndk_url = NDK_URL.replace('https://dl.google.com/android/', 'https://redirector.gvt1.com/edgedl/android/')
        download(ndk_url, archive, 'sha1', NDK_SHA1)
        if not (destination / '.ready').exists():
            destination.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(archive) as package:
                for member in package.infolist():
                    if not (destination / member.filename).resolve().is_relative_to(destination.resolve()):
                        raise RuntimeError('Unsafe NDK archive member')
                package.extractall(destination)
            (destination / '.ready').write_text(NDK_SHA1, encoding='ascii')
        records.append(dict(tool='ndk', url=ndk_url, sha1=NDK_SHA1))
    record = CACHE / 'provenance.json'
    previous = json.loads(record.read_text(encoding='utf-8')) if record.exists() else []
    by_tool = {item['tool']: item for item in previous + records}
    record.write_text(json.dumps(list(by_tool.values()), indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
