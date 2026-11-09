"""Read-only project memory, link and local release checks; no network/private state."""
import hashlib
import json
import pathlib
import re
import sys
import urllib.parse
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
errors, warnings = [], []

def read(path):
    return path.read_text(encoding='utf-8-sig')

def targets(text):
    return re.findall(r'!?\[[^\]]*\]\(([^)]+)\)', text)

def resolve(origin, target):
    parsed = urllib.parse.urlsplit(target.strip('<>'))
    if parsed.scheme or parsed.netloc:
        return None, ''
    path = (origin.parent / urllib.parse.unquote(parsed.path)).resolve() if parsed.path else origin
    return path, urllib.parse.unquote(parsed.fragment)

def slug(title):
    title = re.sub(r'[`*_~]', '', title)
    return re.sub(r'[^\w -]', '', title.lower()).replace(' ', '-')

def anchors(path):
    result, counts = set(), {}
    for title in re.findall(r'^#{1,6} +(.+?) *#*$', read(path), re.M):
        base = slug(title)
        count = counts.get(base, 0)
        result.add(base if count == 0 else f'{base}-{count}')
        counts[base] = count + 1
    return result

handoff_path = ROOT / 'HANDOFF.md'
handoff = read(handoff_path)
protocol = read(ROOT / 'MEMORY_PROTOCOL.md')
required = set(re.findall(r'[├└]── +([a-z][a-z-]+\.md)', protocol))
actual = {p.resolve() for p in (ROOT / 'memory').rglob('*.md')}
index_path = ROOT / 'memory/INDEX.md'
index_documents = [handoff_path] + ([index_path] if index_path.exists() else [])
indexed = []
for index in index_documents:
    for target in targets(read(index)):
        path, _ = resolve(index, target)
        if path and path.is_relative_to(ROOT / 'memory') and path.suffix == '.md':
            if path != index_path:
                indexed.append(path)
if len(indexed) != len(set(indexed)):
    errors.append('Duplicate topic entries in HANDOFF/INDEX')
expected = set(indexed) | ({index_path} if index_path.exists() else set())
for path in sorted(actual - expected):
    errors.append('Unindexed memory: ' + path.relative_to(ROOT).as_posix())
for path in sorted(expected - actual):
    errors.append('Missing memory: ' + path.relative_to(ROOT).as_posix())
if not required.issubset({p.name for p in actual}):
    errors.append('Protocol core topic files missing')

for path in sorted(set(indexed) & actual):
    body = read(path)
    if not body.startswith('# ') or not re.search(r'20\d{2}-\d{2}-\d{2}', body):
        errors.append(path.name + ': missing title or evidence date')
    labels = re.findall(r'P(\d)\s*/\s*([A-Za-z]+)', body)
    if not labels or any(p not in '0123' or s not in {'Active', 'Pending', 'Superseded', 'Obsolete'} for p, s in labels):
        errors.append(path.name + ': missing/invalid priority-status labels')
    if '<!-- GENERATED_' in body:
        errors.append(path.name + ': unfilled template')
    if len(body.splitlines()) > 250:
        warnings.append(path.name + ': exceeds topic soft line budget')
for name, low, high in [('AGENTS.md', 30, 60), ('HANDOFF.md', 60, 100)]:
    count = len(read(ROOT / name).splitlines())
    if not low <= count <= high:
        warnings.append(f'{name}: {count} lines, outside soft budget {low}-{high}')
if '(HANDOFF.md)' not in read(ROOT / 'AGENTS.md') or '(HANDOFF.md)' not in read(ROOT / 'README.md'):
    errors.append('AGENTS/README must link to HANDOFF')
if '(MEMORY_PROTOCOL.md)' not in read(ROOT / 'AGENTS.md'):
    errors.append('AGENTS must identify the root memory protocol')

# Scope is intentional: do not traverse caches, private logs, databases or tool configs.
docs = set(ROOT.glob('*.md')) | actual | set((ROOT / 'docs').rglob('*.md'))
docs |= {ROOT / 'android/README.md', ROOT / 'desktop/README.md'}
patterns = {
    'private-key': r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    'github-token': r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})',
    'api-secret': r'(?<![A-Za-z0-9])sk-(?:proj-)?[A-Za-z0-9_-]{24,}',
    'bearer-token': r'Bearer\s+[A-Za-z0-9._-]{28,}',
    'url-token': r'(?:\#|[?&])(?:token|access_token|api_key)=[A-Za-z0-9_-]{20,}',
}
for path in sorted(docs):
    if not path.exists():
        errors.append('Missing document: ' + str(path.relative_to(ROOT)))
        continue
    body, rel = read(path), path.relative_to(ROOT).as_posix()
    for target in targets(body):
        resolved, fragment = resolve(path, target)
        if resolved is None:
            continue
        if not resolved.is_relative_to(ROOT):
            errors.append(f'{rel}: link leaves workspace: {target}')
        elif not resolved.exists():
            errors.append(f'{rel}: broken local link: {target}')
        elif fragment and resolved.suffix == '.md' and fragment not in anchors(resolved):
            errors.append(f'{rel}: broken Markdown anchor: {target}')
    for label, pattern in patterns.items():
        if re.search(pattern, body, re.I):
            errors.append(f'{rel}: potential secret ({label}); inspect without printing value')
    if path in set(indexed) | {handoff_path, ROOT / 'AGENTS.md'}:
        if re.search(r'(?<![A-Za-z])M(?:0[1-9]|10)(?![0-9])', body) and path != handoff_path:
            errors.append(f'{rel}: legacy memory ID outside migration mapping')

sources = json.loads(read(ROOT / 'config/sources.json'))
catalog = json.loads(read(ROOT / 'internal/catalog/ai_catalog.json'))
for label, records in [('source', sources), ('provider', catalog['providers']), ('tool', catalog['tools'])]:
    ids = [r['id'] for r in records]
    if len(ids) != len(set(ids)):
        errors.append('Duplicate ' + label + ' IDs in maintained JSON')
provider_ids = {r['id'] for r in catalog['providers']}
if any(t['provider'] not in provider_ids for t in catalog['tools']):
    errors.append('Unknown provider reference in catalog')
if catalog['checkedAt'] not in handoff:
    errors.append('HANDOFF catalog evidence date differs from maintained JSON')

def numeric_claim(pattern, value, label):
    match = re.search(pattern, handoff)
    if not match or int(match.group(1)) != value:
        errors.append('HANDOFF current count mismatch: ' + label)

numeric_claim(r'(\d+)\s*源，默认', len(sources), 'sources')
numeric_claim(r'默认(\d+)（', sum(bool(s['enabled']) for s in sources), 'default sources')
numeric_claim(r'(\d+)综合\+', sum(s['enabled'] and s['category'] != 'ai' for s in sources), 'default general')
numeric_claim(r'\+(\d+)AI', sum(s['enabled'] and s['category'] == 'ai' for s in sources), 'default AI')
numeric_claim(r'(\d+)平台 /', len(catalog['providers']), 'providers')
numeric_claim(r'/ (\d+)工具', len(catalog['tools']), 'tools')

release = json.loads(read(ROOT / 'releases/android/release.json'))
apk = (ROOT / 'releases/android' / release['file']).resolve()
if not apk.is_relative_to(ROOT / 'releases/android'):
    raise ValueError('Release file leaves release directory')
digest = hashlib.sha256(apk.read_bytes()).hexdigest()
if digest != release['sha256'] or digest != read(apk.with_name(apk.name + '.sha256')).split()[0]:
    errors.append('Published APK checksum mismatch')
if apk.stat().st_size != release['bytes']:
    errors.append('Published APK byte count mismatch')
manifest = ET.parse(ROOT / 'android/AndroidManifest.xml').getroot()
a = '{http://schemas.android.com/apk/res/android}'
for field, value in [('package', manifest.get('package')), ('version', manifest.get(a + 'versionName')),
                     ('minSdk', int(manifest.find('uses-sdk').get(a + 'minSdkVersion'))),
                     ('targetSdk', int(manifest.find('uses-sdk').get(a + 'targetSdkVersion')))]:
    if release[field] != value:
        errors.append('Manifest/release mismatch: ' + field)
permissions = [p.get(a + 'name') for p in manifest.findall('uses-permission')]
if permissions != release['permissions'] or permissions != ['android.permission.INTERNET']:
    errors.append('Declared permission mismatch')
if release['version'] not in handoff or release['package'] not in handoff:
    errors.append('HANDOFF package/version identity mismatch')
if str(manifest.get(a + 'versionCode')) not in handoff or f"{release['bytes']:,}" not in handoff:
    errors.append('HANDOFF version code/size mismatch')
with zipfile.ZipFile(apk) as z:
    matching = (z.read('assets/sources.json') == (ROOT / 'config/sources.json').read_bytes()
                and z.read('assets/ai-catalog.json') == (ROOT / 'internal/catalog/ai_catalog.json').read_bytes())
if not matching:
    warnings.append('Source data differs from published APK; record unpublished changes, do not overwrite release')
print(json.dumps({
    'memory_files': len(actual), 'indexed_topics': len(set(indexed)),
    'markdown_files_checked': len(docs), 'sources': len(sources),
    'default_sources': sum(bool(s['enabled']) for s in sources),
    'platforms': len(catalog['providers']), 'tools': len(catalog['tools']),
    'published_apk_bytes': apk.stat().st_size, 'release_assets_match_source': matching,
    'errors': errors, 'warnings': warnings,
}, ensure_ascii=False, indent=2))
sys.exit(1 if errors else 0)
