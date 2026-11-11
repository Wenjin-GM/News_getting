"""Rebuild notices for locally downloaded Cargo.lock crates and the Rust runtime.

This intentionally includes some build/host-only packages as a conservative
superset; it never downloads or copies executable code into the APK.
"""
import pathlib
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[2]
CACHE = ROOT / '.cache/native'
source = CACHE / 'cargo/registry/src'
lock = tomllib.loads((ROOT / 'android/native/linuxdo/Cargo.lock').read_text(encoding='utf-8'))
sections = ['Yilan Android ECH dependencies\nGenerated from Cargo.lock and published crate license files.\n'
            'Build-only crates may also be listed. The compiler and NDK are not bundled.\n']
apache = next(source.glob('*/jni-0.21.1/LICENSE-APACHE')).read_text(encoding='utf-8')
for package in sorted(lock['package'], key=lambda p: (p['name'], p['version'])):
    if not package.get('source', '').startswith('registry+'):
        continue
    directories = list(source.glob('*/' + package['name'] + '-' + package['version']))
    if not directories:
        continue  # Optional/unbuilt targets from the lockfile are not distributed.
    directory = directories[0]
    metadata = tomllib.loads((directory / 'Cargo.toml').read_text(encoding='utf-8'))['package']
    files = sorted(f for f in directory.iterdir() if f.is_file()
                   and f.name.upper().startswith(('LICENSE', 'COPYING', 'NOTICE')))
    sections.append('\n' + '=' * 72 + '\n' + package['name'] + ' ' + package['version']
                    + '\nhttps://crates.io/crates/' + package['name'] + '/' + package['version']
                    + '\nDeclared license: ' + metadata.get('license', 'See license file')
                    + '\nAuthors: ' + '; '.join(metadata.get('authors', [])) + '\n')
    if not files:
        if package['name'] not in ('cesu8', 'jni-sys-macros'):
            raise RuntimeError('Review missing license text: ' + package['name'])
        # These published archives declare an Apache-2.0 alternative but omit
        # the standalone text. Select that alternative and reproduce it fully.
        sections.append('Published archive omits license files; Apache-2.0 alternative selected.\n' + apache)
    for file in files:
        sections.append('\n--- ' + file.name + ' ---\n' + file.read_text(encoding='utf-8', errors='replace'))
runtime = CACHE / 'rustup/toolchains/stable-x86_64-pc-windows-gnu/share/doc/rust'
sections.append('\n' + '=' * 72 + '\nRust standard library/runtime\nhttps://www.rust-lang.org/policies/licenses\n')
sections.append((runtime / 'COPYRIGHT-library.html').read_text(encoding='utf-8'))
for file in sorted((runtime / 'licenses').iterdir()):
    if file.is_file():
        sections.append('\n--- Rust ' + file.name + ' ---\n' + file.read_text(encoding='utf-8', errors='replace'))
target = ROOT / 'docs/android/licenses/linuxdo-dependencies.txt'
target.parent.mkdir(parents=True, exist_ok=True)
target.write_text('\n'.join(sections), encoding='utf-8')
print('Native dependency notices regenerated: ' + str(target.stat().st_size) + ' bytes')
