#!/usr/bin/env python3
"""Apply small, explicit customizations to pinned Aniyomi source tree.

Run from the Aniyomi root directory. Fails safely if upstream source changes.
"""
from pathlib import Path
import sys

ROOT = Path.cwd()
CHANGES = []


def replace_once(path: str, before: str, after: str) -> None:
    file = ROOT / path
    if not file.is_file():
        raise FileNotFoundError(f"Missing upstream file: {path}")
    content = file.read_text(encoding="utf-8")
    occurrences = content.count(before)
    if occurrences != 1:
        raise RuntimeError(f"Unexpected upstream file format in {path}: expected one anchor, found {occurrences}")
    file.write_text(content.replace(before, after, 1), encoding="utf-8")
    CHANGES.append(path)


# 1. Separate APK install, avoiding accidental replacement of official Aniyomi.
replace_once(
    "app/build.gradle.kts",
    'applicationId = "xyz.jmir.tachiyomi.mi"',
    'applicationId = "com.ali.aniyomi.quality"',
)

# 2. User-selectable preferred quality for automatic/bulk episode downloads.
replace_once(
    "domain/src/main/java/tachiyomi/domain/download/service/DownloadPreferences.kt",
    '    fun numberOfDownloads() = preferenceStore.getInt("download_slots", 1)\n',
    '    fun preferredAnimeDownloadQuality() = preferenceStore.getInt("preferred_anime_download_quality", -1)\n\n'
    '    fun numberOfDownloads() = preferenceStore.getInt("download_slots", 1)\n',
)

replace_once(
    "app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsDownloadScreen.kt",
    '''            Preference.PreferenceItem.ListPreference(
                preference = downloadPreferences.numberOfDownloads(),
                entries = (1..5).associateWith { it.toString() }.toImmutableMap(),
                title = stringResource(AYMR.strings.pref_download_slots),
            ),''',
    '''            Preference.PreferenceItem.ListPreference(
                preference = downloadPreferences.numberOfDownloads(),
                entries = (1..5).associateWith { it.toString() }.toImmutableMap(),
                title = stringResource(AYMR.strings.pref_download_slots),
            ),
            Preference.PreferenceItem.ListPreference(
                preference = downloadPreferences.preferredAnimeDownloadQuality(),
                entries = persistentMapOf(
                    -1 to "تلقائي (أفضل جودة متاحة)",
                    360 to "360p",
                    480 to "480p",
                    720 to "720p",
                    1080 to "1080p",
                    1440 to "1440p",
                    2160 to "2160p (4K)",
                ),
                title = "جودة تنزيل حلقات الأنمي",
            ),''',
)

# 3. Hoster-aware resolution selection, with best-available fallback.
#    Note: availability comes from source extensions, not from this app.
replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/ui/player/loader/HosterLoader.kt",
    '        class EarlyReturnException(val video: Video) : Exception()\n',
    '''        /**
         * Select the requested resolution for automatic and batch downloads.
         * Prefer an exact match; otherwise prefer the nearest lower resolution,
         * then the nearest higher resolution. If no video titles expose a
         * resolution, fall back to the normal best-video selection.
         * A manually chosen single-episode video always overrides this setting.
         */
        suspend fun getVideoForQuality(source: AnimeSource, hosterList: List<Hoster>, quality: Int): Video? {
            val candidates = hosterList.flatMap { hoster ->
                when (val state = EpisodeLoader.loadHosterVideos(source, hoster)) {
                    is HosterState.Ready -> state.videoList
                    else -> emptyList()
                }
            }

            val resolutionPattern = Regex("""(?<!\\d)(2160|1440|1080|720|480|360|240)\\s*[pP]?(?!\\d)""")
            val matches = candidates.mapNotNull { video ->
                val detected = resolutionPattern.find(video.videoTitle)?.groupValues?.get(1)?.toIntOrNull()
                detected?.let { video to it }
            }.sortedWith(
                compareBy<Pair<Video, Int>>(
                    { if (it.second <= quality) 0 else 1 },
                    { kotlin.math.abs(quality - it.second) },
                ),
            )

            for ((video, _) in matches) {
                val resolved = getResolvedVideo(source, video)
                if (resolved?.videoUrl?.isNotBlank() == true) return resolved
            }
            return getBestVideo(source, hosterList)
        }

        class EarlyReturnException(val video: Video) : Exception()
''',
)

replace_once(
    "app/src/main/java/eu/kanade/tachiyomi/data/download/anime/AnimeDownloader.kt",
    '''                val bestVideo = HosterLoader.getBestVideo(download.source, hosters)
                    ?: throw Exception(context.stringResource(AYMR.strings.video_list_empty_error))''',
    '''                val preferredQuality = preferences.preferredAnimeDownloadQuality().get()
                val bestVideo = if (preferredQuality == -1) {
                    HosterLoader.getBestVideo(download.source, hosters)
                } else {
                    HosterLoader.getVideoForQuality(download.source, hosters, preferredQuality)
                } ?: throw Exception(context.stringResource(AYMR.strings.video_list_empty_error))''',
)

# 4. Use the official FlexibleAdapter artifact published on Maven Central.
#    The older fork revision on JitPack cannot currently be resolved by Gradle.
replace_once(
    "gradle/libs.versions.toml",
    'flexible-adapter-core = "com.github.arkon.FlexibleAdapter:flexible-adapter:c8013533"',
    'flexible-adapter-core = "eu.davidea:flexible-adapter:5.1.0"',
)

# QARI_WEBVIEW_NAVIGATION_FIX_V1
# Leave normal HTTP(S) navigation to the embedded Chromium browser. The original
# handler manually called loadUrl() and then returned false, possibly starting a
# duplicate navigation during Cloudflare challenge redirects. The site can still
# refuse embedded browsers; this fix is not a Cloudflare bypass.
replace_once(
    "app/src/main/java/eu/kanade/presentation/webview/WebViewScreenContent.kt",
    '''            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                request?.let {
                    // Don't attempt to open blobs as webpages
                    if (it.url.toString().startsWith("blob:http")) {
                        return false
                    }

                    // Ignore intents urls
                    if (it.url.toString().startsWith("intent://")) {
                        return true
                    }

                    // Continue with request, but with custom headers
                    view?.loadUrl(it.url.toString(), headers)
                }
                return super.shouldOverrideUrlLoading(view, request)
            }''',
    '''            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                // Preserve browser-managed redirects, cookies, and challenge state.
                // Avoid initiating an extra loadUrl for every navigation.
                if (request?.url?.scheme == "intent") return true
                return false
            }''',
)

# QARI_ONLY_ARM64_AND_UNIVERSAL_V1
# Generate only the arm64-v8a APK and the universal APK. All source-code
# changes, including the existing WebView fix, are compiled into both.
replace_once(
    "app/build.gradle.kts",
    '            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")',
    '            include("arm64-v8a")',
)

from qari_chrome_tabs_patch import apply_chrome_tabs_patch
apply_chrome_tabs_patch(ROOT, replace_once)

from qari_gecko_browser_patch import apply_gecko_browser_patch
apply_gecko_browser_patch(ROOT, replace_once)

from qari_embedded_gecko_patch import apply_embedded_gecko_patch
apply_embedded_gecko_patch(ROOT, replace_once)

from qari_catalog_routes_patch import apply_catalog_routes_patch
apply_catalog_routes_patch(ROOT)

from qari_native_anime_patch import apply_native_anime_patch
apply_native_anime_patch(ROOT)

from qari_auto_sync_patch import apply_auto_sync_patch
apply_auto_sync_patch(ROOT)

# QARI_SINGLE_ARM64_APK_V2
# Disable the universal APK output so Gradle creates only app-arm64-v8a-debug.apk.
replace_once(
    "app/build.gradle.kts",
    '            isUniversalApk = true',
    '            isUniversalApk = false',
)

from qari_external_repo_compat_patch import apply_external_repo_compat_patch
apply_external_repo_compat_patch(ROOT, replace_once)

from qari_live_anime_patch import patch as apply_live_anime_patch
apply_live_anime_patch(ROOT)


# QARI_NXX_CHROME_PLAYBACK_V7: browser playback for NXX's failing VIPServer.
from qari_nxx_chrome_playback_patch import apply_nxx_chrome_playback_patch
apply_nxx_chrome_playback_patch(ROOT, replace_once)

print("Applied Aniyomi Quality customizations:")
for path in CHANGES:
    print(" -", path)
