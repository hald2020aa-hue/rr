#!/usr/bin/env python3
"""Generate an Aniyomi-compatible index from actually built APKs and their source-info.
No dummy APKs, fake extension metadata, or unbuilt sources are published.
"""
from pathlib import Path
import json
import shutil
import sys

upstream=Path(sys.argv[1]).resolve()
out=Path(sys.argv[2]).resolve()
(out/'apk').mkdir(parents=True,exist_ok=True)
entries=[]
for mod in sorted((upstream/'src/ar').iterdir()):
    if not mod.is_dir() or not (mod/'build/keiyoushi-source-info.json').exists():
        continue
    meta=json.loads((mod/'build/keiyoushi-source-info.json').read_text())
    output=sorted((mod/'build/outputs/apk').rglob('*.apk'))
    output=[p for p in output if 'debug' in str(p)]
    if len(output)!=1:
        raise RuntimeError(f'Expected one debug APK for {mod.name}, got {len(output)}')
    src=output[0]
    filename=f"aniyomi-ar.{mod.name}-v{meta['versionName']}-debug.apk"
    shutil.copyfile(src,out/'apk'/filename)
    entry={
        'name':f"Aniyomi: {meta['name']}",
        'pkg':meta['packageName'],
        'apk':filename,
        'lang':'ar',
        'code':int(meta['versionCode']),
        'version':str(meta['versionName']),
        'nsfw':1,
        'torrent':0,
        'sources':[
            {'name':s['name'],'lang':s['lang'],'id':str(s['id']),'baseUrl':s['baseUrl']}
            for s in meta['sources']
        ],
    }
    entries.append(entry)
if len(entries)!=7:
    raise RuntimeError(f'Expected seven compiled extensions, found {len(entries)}. Will not publish a partial index.')
(out/'index.min.json').write_text(json.dumps(entries,ensure_ascii=False,separators=(',',':'))+'\n',encoding='utf8')
(out/'index.json').write_text(json.dumps(entries,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
(out/'README.md').write_text('Aniyomi Arabic experimental extensions. **Not live-tested.** Install third-party APKs at your own risk. Debug APKs are signed with ephemeral CI keys and may need uninstall/reinstall on each build.\n')
print(f'Generated index for {len(entries)} actual APKs.')
for e in entries:print(' -',e['name'],e['pkg'],e['apk'])
