#!/usr/bin/env python3
"""Add a narrow NXXHentai Chrome Custom Tab route in the pinned main Aniyomi app.

Why: Chrome plays the user's NXX VIPServer episode, while the normal extension
extractor and independent Android WebView are rejected / return no HLS.

Only browser-route normal online NXX playback requests. Preserve downloaded
files, explicit external players, and explicit preselected video/hoster lists.
Do not copy or expose browser cookies. The Chrome Custom Tabs utility must
already have been installed by qari_chrome_tabs_patch.py.
"""
from pathlib import Path


def apply_nxx_chrome_playback_patch(root: Path, replace_once):
    path = "app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt"
    replace_once(path,
        "import eu.kanade.tachiyomi.animesource.model.Video\n",
        "import eu.kanade.tachiyomi.animesource.model.Video\n"
        "import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource\n",
    )
    replace_once(path,
        "import eu.kanade.tachiyomi.util.system.openInBrowser\n",
        "import eu.kanade.tachiyomi.util.system.openInBrowser\n"
        "import eu.kanade.tachiyomi.util.system.openInChromeCustomTab\n",
    )
    replace_once(path,
        "import tachiyomi.domain.entries.anime.interactor.GetAnime\n",
        "import tachiyomi.domain.entries.anime.interactor.GetAnime\n"
        "import tachiyomi.domain.items.episode.interactor.GetEpisode\n"
        "import tachiyomi.domain.source.anime.service.AnimeSourceManager\n",
    )

    helper = '''        /**
         * Qari NXX playback route: Chrome's real browser session can play
         * VIPServer episodes even when Aniyomi's extractor/WebView cannot.
         *
         * This is browser playback, NOT a native HLS extractor. The fallback
         * never accesses Chrome cookies or fabricates a playable video URL.
         */
        private suspend fun openQariNxxEpisodeInChrome(
            context: Context,
            animeId: Long,
            episodeId: Long,
        ): Boolean {
            val anime = Injekt.get<GetAnime>().await(animeId) ?: return false
            val source = Injekt.get<AnimeSourceManager>().getOrStub(anime.source) as? AnimeHttpSource
                ?: return false
            val siteHost = runCatching { java.net.URI(source.baseUrl).host?.lowercase()?.removePrefix("www.") }
                .getOrNull()
            if (siteHost != "nxxhentai.net") return false

            val episode = Injekt.get<GetEpisode>().await(episodeId) ?: return false
            // Previously downloaded episodes must remain playable offline.
            val downloaded = runCatching {
                Injekt.get<AnimeDownloadCache>().isEpisodeDownloaded(
                    episode.name,
                    episode.scanlator,
                    anime.title,
                    anime.source,
                    false,
                )
            }.getOrDefault(false)
            if (downloaded) return false

            val target = runCatching {
                java.net.URI(source.baseUrl.trimEnd('/') + "/")
                    .resolve(episode.url)
                    .normalize()
            }.getOrNull() ?: return false
            // Do not open cross-site player URLs or non-episode pages implicitly.
            if (target.scheme != "https" ||
                target.host?.lowercase()?.removePrefix("www.") != "nxxhentai.net" ||
                !target.path.orEmpty().startsWith("/episodes/") ||
                target.rawUserInfo != null
            ) return false

            withUIContext { context.openInChromeCustomTab(target.toASCIIString()) }
            return true
        }

'''
    replace_once(path,
        "        suspend fun startPlayerActivity(\n",
        helper + "        suspend fun startPlayerActivity(\n",
    )
    replace_once(path,
        """            hosterList: List<Hoster>? = null,
        ) {
            if (extPlayer) {""",
        """            hosterList: List<Hoster>? = null,
        ) {
            // Known failing NXX VIPServer: open this episode in Chrome's own
            // session instead of repeatedly failing inside the native player.
            // Keep all other playback paths completely unchanged.
            if (!extPlayer && video == null && hosterList == null &&
                openQariNxxEpisodeInChrome(context, animeId, episodeId)
            ) return

            if (extPlayer) {""",
    )
    print(" + Qari NXX online episode playback via Chrome Custom Tabs (app main route)")
