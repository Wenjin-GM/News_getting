"""Optional portable emulator and JVM test dependency; no system installation or driver changes."""
import concurrent.futures,hashlib,pathlib,urllib.request,zipfile,argparse
options=argparse.ArgumentParser();options.add_argument('--emulator',action='store_true');args=options.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]/'.cache'/'android';root.mkdir(parents=True,exist_ok=True)
packages=[('platform-tools','https://dl.google.com/android/repository/platform-tools_r37.0.1-win.zip','e03e78b1d80b396f1c3358e31251cb31740e1110'),('emulator','https://dl.google.com/android/repository/emulator-windows_x64-16428233.zip','488ed747e82de7e9bb5247becd1ac043c7e5e85d'),('system-image','https://dl.google.com/android/repository/sys-img/android/x86_64-30_r11.zip','5e4de3946d46f88856c35efcc4d797b381456347')]
def download(p):
 name,url,expected=p;archive=root/(name+'.zip');destination=root/name
 def digest():
  h=hashlib.sha1()
  with archive.open('rb') as stream:
   while block:=stream.read(1024*1024):h.update(block)
  return h.hexdigest()
 if not archive.exists() or digest()!=expected:
  print('Downloading '+name,flush=True)
  with urllib.request.urlopen(url,timeout=90) as response,archive.open('wb') as out:
   while block:=response.read(1024*1024):out.write(block)
 if digest()!=expected:raise RuntimeError('Invalid official checksum: '+name)
 if not (destination/'.ready').exists():
  destination.mkdir(exist_ok=True)
  with zipfile.ZipFile(archive) as z:
   for item in z.infolist():
    if not (destination/item.filename).resolve().is_relative_to(destination.resolve()):raise RuntimeError('Invalid archive path')
   z.extractall(destination)
  (destination/'.ready').write_text(expected)
 print(name+' ready',flush=True)
if args.emulator:
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(download,packages))
jar=root/'json-test.jar';url='https://repo.maven.apache.org/maven2/org/json/json/20250517/json-20250517.jar'
expected='3ea61b2a06e31edf1c91134fe9106b0ebb16628be169f3db75bc7a2b06b45796'
if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest()!=expected:
 with urllib.request.urlopen(url,timeout=60) as r:jar.write_bytes(r.read())
if hashlib.sha256(jar.read_bytes()).hexdigest()!=expected:raise RuntimeError('JSON test library checksum mismatch')
print('JVM JSON test library ready (not bundled in APK)')
