"""Pinned official portable build tools. Writes only .cache/android in this checkout."""
import concurrent.futures,hashlib,json,pathlib,urllib.request,zipfile
root=pathlib.Path(__file__).resolve().parents[2]/'.cache'/'android'
root.mkdir(parents=True,exist_ok=True)
packages=[
 ('jdk','https://aka.ms/download-jdk/microsoft-jdk-21.0.12.1-windows-x64.zip','sha256','192441a9d27da813bada974bb88b4cf64d37a9589ed37f204374d411ca5ce07f'),
 ('platform','https://dl.google.com/android/repository/platform-36_r02.zip','sha1','2c1a80dd4d9f7d0e6dd336ec603d9b5c55a6f576'),
 ('build-tools','https://dl.google.com/android/repository/build-tools_r36_windows.zip','sha1','f16ccffd34de8790dede813a6c7d8e2c11a27b50')]
def install(p):
 name,url,algorithm,expected=p
 archive=root/(name+'.zip');destination=root/name
 if (destination/'.ready').exists() and (destination/'.ready').read_text()==expected:
  print(name+' already prepared',flush=True);return
 def digest():
  h=hashlib.new(algorithm)
  with archive.open('rb') as f:
   for block in iter(lambda:f.read(1024*1024),b''):h.update(block)
  return h.hexdigest()
 if not archive.exists() or digest()!=expected:
  print('Downloading official '+name,flush=True)
  with urllib.request.urlopen(url,timeout=90) as response,archive.open('wb') as output:
   while block:=response.read(1024*1024):output.write(block)
 if digest()!=expected:raise RuntimeError('Checksum mismatch: '+name)
 if not (destination/'.ready').exists():
  destination.mkdir(exist_ok=True)
  with zipfile.ZipFile(archive) as z:
   for member in z.infolist():
    target=(destination/member.filename).resolve()
    if not target.is_relative_to(destination.resolve()):raise RuntimeError('Unsafe archive path')
   z.extractall(destination)
  (destination/'.ready').write_text(expected)
 print(name+' verified and ready',flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(install,packages))
(root/'provenance.json').write_text(json.dumps([dict(zip(['name','url','checksum_algorithm','checksum'],p)) for p in packages],indent=2))
