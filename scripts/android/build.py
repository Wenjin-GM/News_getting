"""Build and sign a small Java-only APK using the pinned official portable toolchain."""
import hashlib,json,os,pathlib,secrets,subprocess,zipfile,shutil,xml.etree.ElementTree as ET
root=pathlib.Path(__file__).resolve().parents[2]; android=root/'android'; tools=root/'.cache'/'android'
jdk=next((tools/'jdk').glob('*/bin')); platform=tools/'platform'/'android-36'/'android.jar'; buildtools=tools/'build-tools'/'android-16'
build=root/'.cache'/'android-build'; signing=root/'.local'/'signing'/'android'; output=root/'dist'/'android'
manifest_root=ET.parse(android/'AndroidManifest.xml').getroot()
version=manifest_root.get('{http://schemas.android.com/apk/res/android}versionName')
version_code=int(manifest_root.get('{http://schemas.android.com/apk/res/android}versionCode'))
development=version.endswith('-dev')
password=signing/'keystore-password.txt';key=signing/'yilan-release.p12'
if development and (not password.is_file() or not key.is_file()):raise RuntimeError('Development upgrade build requires the existing signing identity; no key will be generated.')
for name in ['classes','dex','gen']:
 target_dir=(build/name).resolve()
 if not target_dir.is_relative_to(build.resolve()):raise RuntimeError('Unsafe generated output directory')
 if target_dir.exists():shutil.rmtree(target_dir)
assets=android/'assets';assets.mkdir(exist_ok=True)
shutil.copy2(root/'config'/'sources.json',assets/'sources.json');shutil.copy2(root/'internal'/'catalog'/'ai_catalog.json',assets/'ai-catalog.json')
for p in [build,output,build/'classes',build/'dex',build/'gen',android/'res'/'mipmap-xxxhdpi',root/'.cache'/'tmp']:p.mkdir(parents=True,exist_ok=True)
if not development:signing.mkdir(parents=True,exist_ok=True)
env=os.environ.copy();env['JAVA_HOME']=str(jdk.parent);env['JAVA_TOOL_OPTIONS']='-Duser.home='+str(tools/'java-home')+' -Djava.io.tmpdir='+str(root/'.cache'/'tmp')
def run(*args,capture=False):
 result=subprocess.run([str(x) for x in args],cwd=android,env=env,check=True,capture_output=capture,text=True,encoding='utf-8',errors='replace')
 return result.stdout if capture else ''
if not development and not password.exists():password.write_text(secrets.token_urlsafe(40),encoding='utf-8')
env['YILAN_SIGN_PASS']=password.read_text(encoding='utf-8').strip()
if not development and not key.exists():run(jdk/'keytool.exe','-genkeypair','-keystore',key,'-storetype','PKCS12','-alias','yilan-release','-keyalg','RSA','-keysize','3072','-sigalg','SHA256withRSA','-validity','10000','-storepass:env','YILAN_SIGN_PASS','-dname','CN=Yilan News Release, OU=Personal Project, O=Yilan, C=CN')
shutil.copy2(root/'assets'/'icon-192.png',android/'res'/'mipmap-xxxhdpi'/'ic_launcher.png')
run(buildtools/'aapt2.exe','compile','--dir',android/'res','-o',build/'resources.zip')
run(buildtools/'aapt2.exe','link','-I',platform,'--manifest',android/'AndroidManifest.xml','--java',build/'gen','--min-sdk-version','26','--target-sdk-version','36','-A',assets,'-o',build/'unsigned.apk',build/'resources.zip')
java=list((android/'src').rglob('*.java'))+list((build/'gen').rglob('*.java'))
run(jdk/'javac.exe','-encoding','UTF-8','--release','8','-classpath',platform,'-d',build/'classes',*java)
run(jdk/'java.exe','-cp',buildtools/'lib'/'d8.jar','com.android.tools.r8.D8','--release','--min-api','26','--lib',platform,'--output',build/'dex',*list((build/'classes').rglob('*.class')))
with zipfile.ZipFile(build/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as apk:
 for dex in (build/'dex').glob('*.dex'):apk.write(dex,dex.name)
run(buildtools/'zipalign.exe','-f','-p','4',build/'unsigned.apk',build/'aligned.apk')
target=output/('yilan-standalone-'+version+'.apk')
run(jdk/'java.exe','-jar',buildtools/'lib'/'apksigner.jar','sign','--ks',key,'--ks-key-alias','yilan-release','--ks-pass','env:YILAN_SIGN_PASS','--v4-signing-enabled','false','--out',target,build/'aligned.apk')
verification=run(jdk/'java.exe','-jar',buildtools/'lib'/'apksigner.jar','verify','--verbose','--print-certs',target,capture=True)
manifest=run(buildtools/'aapt.exe','dump','badging',target,capture=True)
permissions=[line for line in manifest.splitlines() if line.startswith('uses-permission:')]
if permissions!=["uses-permission: name='android.permission.INTERNET'"]:raise RuntimeError('Unexpected APK permissions: '+repr(permissions))
if "sdkVersion:'26'" not in manifest or "targetSdkVersion:'36'" not in manifest:raise RuntimeError('Unexpected SDK versions')
digest=hashlib.sha256(target.read_bytes()).hexdigest()
(output/(target.name+'.sha256')).write_text(digest+'  '+target.name+'\n',encoding='ascii')
cert=next(line.split(': ',1)[1] for line in verification.splitlines() if line.startswith('Signer #1 certificate SHA-256 digest:'))
if development:
 original=json.loads((root/'releases'/'android'/'release.json').read_text(encoding='utf-8'))
 if cert.lower()!=original['certificateSha256'].lower():raise RuntimeError('Development upgrade APK does not match the original release certificate.')
if "versionCode='"+str(version_code)+"'" not in manifest or "versionName='"+version+"'" not in manifest:raise RuntimeError('Unexpected APK version')
with zipfile.ZipFile(target) as apk:
 dex=apk.read('classes.dex')
 for banned in [b'chatgpt.site',b'android/webkit/WebView',b'addJavascriptInterface',b'firebase',b'Sentry']:
  if banned in dex:raise RuntimeError('Forbidden runtime dependency found: '+repr(banned))
 for required in ['assets/sources.json','assets/ai-catalog.json']:
  if required not in apk.namelist():raise RuntimeError('Missing embedded data: '+required)
metadata=dict(version=version,versionCode=version_code,package='cn.yilan.news',file=target.name,bytes=target.stat().st_size,sha256=digest,certificateSha256=cert,minAndroid='8.0',minSdk=26,targetSdk=36,permissions=['android.permission.INTERNET'],runtime='native-android',collection='direct-on-device',backendRequired=False,accountRequired=False,webView=False,privateStorageOnly=True,apkShare='read-only URI grant to user-selected receiver')
(output/'release.json').write_text(json.dumps(metadata,ensure_ascii=False,indent=2),encoding='utf-8')
(output/'android-verification.txt').write_text(verification+'\n'+manifest,encoding='utf-8')
print(json.dumps(metadata,ensure_ascii=False,indent=2));print('PASS: release signature and INTERNET-only permissions')
