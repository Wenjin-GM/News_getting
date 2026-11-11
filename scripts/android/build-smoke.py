"""Build a separate signed Android instrumentation APK; no install or runtime side effects."""
import os
import pathlib
import subprocess
import zipfile
import argparse

parser = argparse.ArgumentParser()
parser.add_argument('--ui', action='store_true', help='Build isolated native UI regression instrumentation')
parser.add_argument('--ai', action='store_true', help='Build isolated AI configuration and workflow instrumentation')
options = parser.parse_args()
runner = 'AiSmoke' if options.ai else 'UiSmoke' if options.ui else 'DeviceSmoke'

root = pathlib.Path(__file__).resolve().parents[2]
tools = root / '.cache/android'
jdk = next((tools / 'jdk').glob('*/bin'))
sdk = tools / 'build-tools/android-16'
platform = tools / 'platform/android-36/android.jar'
work = root / ('.cache/android-ai-smoke' if options.ai else '.cache/android-ui-smoke' if options.ui else '.cache/android-smoke')
for folder in (work, work / 'classes', work / 'dex'):
    folder.mkdir(parents=True, exist_ok=True)
manifest = work / 'AndroidManifest.xml'
manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="cn.yilan.news.test" android:versionCode="1" android:versionName="1">
<uses-sdk android:minSdkVersion="26" android:targetSdkVersion="36"/>
<application android:label="Yilan local test" android:allowBackup="false"/>
<instrumentation android:name="cn.yilan.news.test.DeviceSmoke" android:targetPackage="cn.yilan.news"/>
</manifest>'''.replace('DeviceSmoke', runner), encoding='utf-8')
env = os.environ.copy()
env['JAVA_TOOL_OPTIONS'] = '-Duser.home=' + str(tools / 'java-home') + ' -Djava.io.tmpdir=' + str(root / '.cache/tmp')
env['YILAN_SIGN_PASS'] = (root / '.local/signing/android/keystore-password.txt').read_text(encoding='utf-8').strip()


def run(*args):
    subprocess.run(list(map(str, args)), cwd=root, env=env, check=True)


classpath = str(platform) + os.pathsep + str(root / '.cache/android-build/classes')
run(jdk / 'javac.exe', '-encoding', 'UTF-8', '--release', '8', '-classpath', classpath,
    '-d', work / 'classes', root / ('android/tests/' + runner + '.java'))
with zipfile.ZipFile(work / 'target-classes.jar', 'w') as archive:
    for file in (root / '.cache/android-build/classes').rglob('*.class'):
        archive.write(file, file.relative_to(root / '.cache/android-build/classes').as_posix())
run(jdk / 'java.exe', '-cp', sdk / 'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26',
    '--lib', platform, '--classpath', work / 'target-classes.jar', '--output', work / 'dex',
    *list((work / 'classes').rglob('*.class')))
run(sdk / 'aapt2.exe', 'link', '-I', platform, '--manifest', manifest, '-o', work / 'unsigned.apk')
with zipfile.ZipFile(work / 'unsigned.apk', 'a') as archive:
    archive.write(work / 'dex/classes.dex', 'classes.dex')
run(sdk / 'zipalign.exe', '-f', '4', work / 'unsigned.apk', work / 'aligned.apk')
run(jdk / 'java.exe', '-jar', sdk / 'lib/apksigner.jar', 'sign', '--ks',
    root / '.local/signing/android/yilan-release.p12', '--ks-key-alias', 'yilan-release',
    '--ks-pass', 'env:YILAN_SIGN_PASS', '--v4-signing-enabled', 'false', '--out',
    work / 'yilan-smoke.apk', work / 'aligned.apk')
print('Separate local instrumentation APK: ' + str((work / 'yilan-smoke.apk').relative_to(root)))
