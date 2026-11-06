#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path.cwd()
DATA={
'nxxhentai':'NXXHentai', 'hentaixplanet':'HentaiXPlanet',
'zebzob':'Zebzob', 'saudihentai':'SaudiHentai',
'hentaitime':'HentaiTime', 'ristohentai':'RistoHentai',
'arabshentai':'ArabsHentai',
}
for slug,klass in DATA.items():
    folder=ROOT/'extension-pack/src/ar'/slug/'src/eu/kanade/tachiyomi/animeextension/ar'/slug
    source=(folder/(klass+'.kt')).read_text(encoding='utf-8')
    nav=(folder/'QariSiteNavigation.kt').read_text(encoding='utf-8')
    build=(ROOT/'extension-pack/src/ar'/slug/'build.gradle.kts').read_text(encoding='utf-8')
    for mark in ('QARI_LIVE_MENU_FILTERS_V2','SiteSectionFilter','SiteCategoryFilter',
                 'override fun getFilterList()','qariNav.discover(document)',
                 'qariNav.recordNext(document, response.request.url.toString())',
                 'qariNav.pageUrl(route, page)'):
        assert mark in source, f'{slug}: missing {mark}'
    for mark in ('private fun safe(url: String)','fun discover(document: Document)',
                 'fun recordNext(document: Document, currentUrl: String)',
                 'fun categories(): List<QariNavEntry>','fun sections(): List<QariNavEntry>'):
        assert mark in nav, f'{slug}: missing {mark}'
    assert 'versionCode = 3' in build, f'{slug}: extension version unchanged'
    assert 'QariPlaybackExtractor.videos(' in source, f'{slug}: playback resolver missing'
    helper=(folder/'QariPlaybackExtractor.kt').read_text(encoding='utf-8')
    for feature in ('iframe', 'data-setup', 'Referer', 'm3u8', 'mp4'):
        assert feature in helper, f'{slug}: missing playback {feature}'
    assert source.count('class '+klass+' ') == 1, f'{slug}: source class changed'
    print('PASS',slug,'menus/categories/pagination/version')
print('OK: all 7 source modules verified')
