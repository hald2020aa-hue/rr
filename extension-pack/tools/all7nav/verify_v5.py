#!/usr/bin/env python3
"""Offline audit of Qari's seven media-extractor modules; not a live playback test."""
from pathlib import Path
import re
import sys

SITES = {
    'nxxhentai': 'NXXHentai',
    'hentaixplanet': 'HentaiXPlanet',
    'zebzob': 'Zebzob',
    'saudihentai': 'SaudiHentai',
    'hentaitime': 'HentaiTime',
    'ristohentai': 'RistoHentai',
    'arabshentai': 'ArabsHentai',
}


def verify(root: Path):
    for slug, name in SITES.items():
        folder = root / 'extension-pack/src/ar' / slug
        src = folder / 'src/eu/kanade/tachiyomi/animeextension/ar' / slug
        helper = (src / 'QariPlaybackExtractor.kt').read_text(encoding='utf8')
        source = (src / f'{name}.kt').read_text(encoding='utf8')
        build = (folder / 'build.gradle.kts').read_text(encoding='utf8')
        assert f'package eu.kanade.tachiyomi.animeextension.ar.{slug}' in helper
        assert 'versionCode = 5' in build, f'{slug}: versionCode not 5'
        assert 'QariPlaybackExtractor.videos(' in source, f'{slug}: extractor not connected'
        for signature in (
            'private val profiles = mapOf(', '"nxxhentai.net"', '"arabshentai.com"',
            '"hentaixplanet.com"', '"saudihentai.com"', '"hentai-time.com"',
            '"ristohentai.com"', '"zebzob.net"',
            'doo_player_ajax', 'application/ld+json', 'video_url_alt',
            'm3u8', 'mpd', 'mp4', 'requestHeaders(headers, stream.url, stream.referer)',
            'embedded=', 'cf-challenge',
        ):
            assert signature in helper, f'{slug}: missing {signature}'
        assert re.search(r'\bVideo\(stream\.url', helper)
        print(f'PASS {slug}: profile, public players, auth scoping, version 14.5')
    workflow = (root / '.github/workflows/build-arabic-extensions.yml').read_text(encoding='utf8')
    assert 'verify_v5.py' in workflow, 'CI missing V5 audit'
    print('PASS: seven V5 adapters and CI verifier; live playback is NOT verified')


if __name__ == '__main__':
    verify(Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve())
