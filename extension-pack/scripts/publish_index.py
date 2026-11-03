#!/usr/bin/env python3
"""Generate legacy Aniyomi index + required repo.json from real debug APKs.

The pinned Aniyomi 2026-09 client treats index.min.json as a legacy store,
then fetches repo.json to read the store metadata and signing fingerprint.
Missing repo.json prevents the store from being added, despite a good index.
"""
from pathlib import Path
import json
import os
import re
import shutil
import subprocess
import sys


def find_apksigner():
    exe = shutil.which('apksigner')
    if exe:
        return exe
    for root in [os.getenv('ANDROID_HOME'), os.getenv('ANDROID_SDK_ROOT')]:
        if root:
            tools = list((Path(root) / 'build-tools').glob('*/apksigner'))
            if tools:
                # Gradle builds use installed SDK Build Tools; the highest is fine.
                def version(path):
                    return tuple(int(x) for x in re.findall(r'\d+', path.parent.name))
                return str(max(tools, key=version))
    raise RuntimeError('Android apksigner not found; cannot publish unverified signingKeyFingerprint')


def get_signer_fingerprint(apk):
    result = subprocess.run(
        [find_apksigner(), 'verify', '--print-certs', str(apk)],
        check=True,
        capture_output=True,
        text=True,
    )
    match = re.search(
        r'Signer\s+#1\s+certificate\s+SHA-256\s+digest:\s*([0-9a-fA-F:]+)',
        result.stdout + result.stderr,
    )
    if not match:
        raise RuntimeError('Could not read APK signer fingerprint from apksigner output')
    digest = match.group(1).replace(':', '').lower()
    if not re.fullmatch(r'[0-9a-f]{64}', digest):
        raise RuntimeError('Invalid APK signing key SHA-256 fingerprint')
    return digest


def main():
    if len(sys.argv) != 3:
        raise SystemExit('Usage: publish_index.py EXTENSION_UPSTREAM_DIR OUTPUT_DIR')
    upstream = Path(sys.argv[1]).resolve()
    out = Path(sys.argv[2]).resolve()
    (out / 'apk').mkdir(parents=True, exist_ok=True)
    entries = []
    copied = []
    for mod in sorted((upstream / 'src/ar').iterdir()):
        if not mod.is_dir() or not (mod / 'build/keiyoushi-source-info.json').exists():
            continue
        meta = json.loads((mod / 'build/keiyoushi-source-info.json').read_text(encoding='utf8'))
        outputs = sorted((mod / 'build/outputs/apk').rglob('*.apk'))
        outputs = [p for p in outputs if 'debug' in str(p)]
        if len(outputs) != 1:
            raise RuntimeError(f'Expected one debug APK for {mod.name}; found {len(outputs)}')
        src = outputs[0]
        filename = f"aniyomi-ar.{mod.name}-v{meta['versionName']}-debug.apk"
        dest = out / 'apk' / filename
        shutil.copyfile(src, dest)
        copied.append(dest)
        entries.append({
            'name': f"Aniyomi: {meta['name']}",
            'pkg': meta['packageName'],
            'apk': filename,
            'lang': 'ar',
            'code': int(meta['versionCode']),
            'version': str(meta['versionName']),
            'nsfw': 1,
            'torrent': 0,
            'sources': [
                {
                    'name': s['name'],
                    'lang': s['lang'],
                    'id': str(s['id']),
                    'baseUrl': s['baseUrl'],
                }
                for s in meta['sources']
            ],
        })
    if len(entries) != 7:
        raise RuntimeError(f'Expected seven compiled extensions; found {len(entries)}. No partial index.')
    fingerprints = {get_signer_fingerprint(apk) for apk in copied}
    if len(fingerprints) != 1:
        raise RuntimeError(f'Extension APKs have multiple signer fingerprints; refusing to publish: {len(fingerprints)}')
    fingerprint = fingerprints.pop()
    (out / 'index.min.json').write_text(
        json.dumps(entries, ensure_ascii=False, separators=(',', ':')) + '\n', encoding='utf8',
    )
    (out / 'index.json').write_text(
        json.dumps(entries, ensure_ascii=False, indent=2) + '\n', encoding='utf8',
    )
    repo = {
        'index_v2': None,
        'meta': {
            'name': 'Qari Arabic Anime Extensions',
            'shortName': 'Qari',
            'website': 'https://github.com/hald2020aa-hue/rr',
            'signingKeyFingerprint': fingerprint,
        },
    }
    (out / 'repo.json').write_text(
        json.dumps(repo, ensure_ascii=False, indent=2) + '\n', encoding='utf8',
    )
    (out / 'README.md').write_text(
        'Aniyomi Arabic experimental extensions. Not live-tested. '
        'Debug APKs are signed with ephemeral CI keys and may need '
        'uninstall/reinstall after rebuilding. Do not confuse build success '
        'with working source playback.\n',
        encoding='utf8',
    )
    print(f'Generated index and repo.json for {len(entries)} actual APKs.')
    print(f'Signer SHA-256: {fingerprint}')
    for e in entries:
        print(' -', e['name'], e['pkg'], e['apk'])


if __name__ == '__main__':
    main()
