#!/usr/bin/env python3
"""Offline audit only; does not claim that live XHamster playback was tested."""
from pathlib import Path
import sys
root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path.cwd()
module = root / "extension-pack/src/ar/xhamster"
source = (module / "src/eu/kanade/tachiyomi/animeextension/ar/xhamster/XHamsterArabic.kt").read_text(encoding="utf-8")
extractor = (module / "src/eu/kanade/tachiyomi/animeextension/ar/xhamster/XHamsterVideoExtractor.kt").read_text(encoding="utf-8")
build = (module / "build.gradle.kts").read_text(encoding="utf-8")
for route in ("/newest", "/categories", "/channels", "/pornstars", "/creators/videos/naoc", "/creators/contest/naoc"):
    assert route in source, f"Missing route: {route}"
for marker in ("getFilterList()", "popularAnimeParse", "latestUpdatesParse", "searchAnimeParse", "animeDetailsParse", "episodeListParse", "videoListParse", "page-button-link", "videoThumbProps"):
    assert marker in source, f"Missing source behavior: {marker}"
for marker in (
    "videoModel", "xplayerSettings", "collectHlsStreams", "stream.url,", "Referer",
    "m3u8", "mp4", "decodeFormatUrl", "matchingJsonObjectEnd", "algorithm !in 1..7",
):
    assert marker in extractor, f"Missing player behavior: {marker}"
assert 'baseUrl = "https://ara.xhamster.com"' in build
assert "ContentWarning.NSFW" in build
app_workflow = (root / ".github/workflows/aniyomi-quality-build.yml").read_text(encoding="utf-8")
assert "python3 ../aniyomi-custom/apply_customizations.py" in app_workflow
assert "python3 ../aniyomi-custom/fix_video_duration_badge.py" in app_workflow
extension_workflow = (root / ".github/workflows/build-arabic-extensions.yml").read_text(encoding="utf-8")
assert ":src:ar:xhamster:assembleDebug" in extension_workflow
index_script = (root / "extension-pack/scripts/publish_index.py").read_text(encoding="utf-8")
assert "len(entries) != 8" in index_script
print("PASS: six routes, section filter, video/category/channel/star parsers")
print("PASS: XHamster initials parsing, codec-nested HLS, public MP4/HLS and Referer handling")
print("PASS: app badge repair is wired into the build workflow; extension APK and 8-entry index are wired")
print("NOTE: static audit only; live website access, age-gated account state and playback are not verified")
