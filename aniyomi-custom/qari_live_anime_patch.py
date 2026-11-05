#!/usr/bin/env python3
"""Switch rr's built-in ArabsHentai anime source to on-demand network pages.

Run LAST after qari_auto_sync_patch and qari_external_repo_compat_patch.
Does not alter Aniyomi's extension loader, download settings or APK signing.
"""
from pathlib import Path
from shutil import copy2

HERE = Path(__file__).resolve().parent
SOURCE = Path('app/src/main/java/eu/kanade/tachiyomi/source/anime/QariVerifiedAnimeSource.kt')
ENGINE = Path('app/src/main/java/eu/kanade/tachiyomi/source/anime/QariLiveAnimeEngine.kt')
SCREEN = Path('app/src/main/java/eu/kanade/tachiyomi/ui/browse/anime/source/browse/BrowseAnimeSourceScreen.kt')

CLASS = '''class QariVerifiedAnimeSource(private val appContext: Context) : AnimeHttpSource() {
    override val name = "ArabsHentai"
    override val lang = "ar"
    override val baseUrl = "https://arabshentai.com"
    override val supportsLatest = true
    override fun getHomeUrl() = "$baseUrl/anime/"

    override fun headersBuilder() = super.headersBuilder().add("Referer", "$baseUrl/")

    private val live by lazy { QariLiveAnimeEngine(appContext, client, headers) }

    override suspend fun getPopularAnime(page: Int) = live.listing(page)
    override suspend fun getLatestUpdates(page: Int) = live.listing(page)

    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ) = live.listing(page, query)

    @Suppress("DEPRECATION")
    override fun fetchPopularAnime(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getPopularAnime(page) } }

    @Suppress("DEPRECATION")
    override fun fetchLatestUpdates(page: Int): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getLatestUpdates(page) } }

    @Suppress("DEPRECATION")
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getSearchAnime(page, query, filters) } }

    @Suppress("DEPRECATION")
    override suspend fun getAnimeDetails(anime: SAnime): SAnime = live.details(anime)

    @Suppress("DEPRECATION")
    override fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getAnimeDetails(anime) } }

    @Suppress("DEPRECATION")
    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> = live.episodes(anime)

    @Suppress("DEPRECATION")
    override fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        Observable.fromCallable { kotlinx.coroutines.runBlocking { getEpisodeList(anime) } }

    override suspend fun getAnimeEpisodeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeEpisodeUpdate {
        val details = if (fetchDetails) live.details(anime) else anime
        val fresh = if (fetchEpisodes) live.episodes(anime) else episodes
        return SAnimeEpisodeUpdate(details, fresh)
    }

    override suspend fun getAnimeSeasonUpdate(
        anime: SAnime,
        seasons: List<SAnime>,
        fetchDetails: Boolean,
        fetchSeasons: Boolean,
    ): SAnimeSeasonUpdate = SAnimeSeasonUpdate(
        if (fetchDetails) live.details(anime) else anime,
        seasons,
    )

    override suspend fun getVideoList(episode: SEpisode): List<Video> = live.videos(episode)
}

'''


def replace_once(s: str, old: str, new: str, label: str) -> str:
    n = s.count(old)
    if n != 1:
        raise RuntimeError(f'Unexpected Aniyomi source layout for {label}: matches={n}')
    return s.replace(old, new, 1)


def patch(root: Path) -> None:
    root = Path(root).resolve()
    source = root / SOURCE
    screen = root / SCREEN
    engine = root / ENGINE
    if not source.is_file() or not screen.is_file():
        raise RuntimeError('Missing upstream verified Aniyomi source or browse screen')
    code = (HERE / 'live_anime/QariLiveAnimeEngine.kt').read_text(encoding='utf-8')
    if 'QariLiveAnimeEngine' not in code or 'getVideoList' not in source.read_text(encoding='utf-8'):
        raise RuntimeError('Native anime source API does not match patch requirements')
    if engine.exists() and engine.read_text(encoding='utf-8') != code:
        raise RuntimeError('Refusing to overwrite a different QariLiveAnimeEngine.kt')

    s = source.read_text(encoding='utf-8')
    marker = '/** Persist a bounded, user-visible catalog in the host application\'s own storage. */'
    header = 'class QariVerifiedAnimeSource(private val appContext: Context) : AnimeHttpSource() {'
    if CLASS in s:
        source_new = s
    else:
        if s.count(header) != 1 or s.count(marker) != 1:
            raise RuntimeError('Cannot locate the built-in anime class for safe replacement')
        a = s.index(header)
        b = s.index(marker, a)
        source_new = s[:a] + CLASS + s[b:]

    ui = screen.read_text(encoding='utf-8')
    if 'QARI_LIVE_ANIME_UI_V1' not in ui:
        ui = replace_once(ui, 'import androidx.compose.runtime.LaunchedEffect\n',
            'import androidx.compose.runtime.LaunchedEffect\n'
            'import androidx.compose.runtime.DisposableEffect\n', 'Compose lifecycle')
        ui = replace_once(ui, 'import androidx.compose.ui.platform.LocalConfiguration\n',
            'import androidx.compose.ui.platform.LocalConfiguration\n'
            'import androidx.lifecycle.Lifecycle\n'
            'import androidx.lifecycle.LifecycleEventObserver\n'
            'import androidx.lifecycle.compose.LocalLifecycleOwner\n', 'Lifecycle imports')
        ui = replace_once(ui, 'import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource\n',
            'import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource\n'
            'import eu.kanade.tachiyomi.source.anime.QariVerifiedAnimeSource\n', 'Native source import')
        ui = replace_once(ui,
            '        var topBarHeight by remember { mutableIntStateOf(0) }\n',
            '''        // QARI_LIVE_ANIME_UI_V1: refresh when the user returns from Gecko verification.
        val liveAnimeItems = screenModel.animePagerFlowFlow.collectAsLazyPagingItems()
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner, screenModel.source) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME && screenModel.source is QariVerifiedAnimeSource) {
                    liveAnimeItems.refresh()
                }
            }
            owner.lifecycle.addObserver(observer)
            onDispose { owner.lifecycle.removeObserver(observer) }
        }

        var topBarHeight by remember { mutableIntStateOf(0) }
''', 'refresh-on-resume')
        ui = replace_once(ui,
            '''                    ) {
                        FilterChip(
                            selected = state.listing == Listing.Popular,''',
            '''                    ) {
                        if (screenModel.source is QariVerifiedAnimeSource) {
                            FilterChip(
                                selected = false,
                                onClick = onWebViewClick,
                                label = { Text("فتح الموقع / التحقق") },
                            )
                        }
                        FilterChip(
                            selected = state.listing == Listing.Popular,''',
            'verification button')
        ui = replace_once(ui,
            '                animeList = screenModel.animePagerFlowFlow.collectAsLazyPagingItems(),',
            '                animeList = liveAnimeItems,', 'live paging instance')
    else:
        if ui.count('QARI_LIVE_ANIME_UI_V1') != 1:
            raise RuntimeError('Unexpected prior live UI patch')

    # Atomic preflight: no writes until every anchor and destination has passed.
    source.write_text(source_new, encoding='utf-8')
    screen.write_text(ui, encoding='utf-8')
    engine.parent.mkdir(parents=True, exist_ok=True)
    engine.write_text(code, encoding='utf-8')
    print('Aniyomi rr: direct archive pagination + on-demand episodes/video URLs + Gecko cache fallback')


if __name__ == '__main__':
    import argparse
    parser = argparse.ArgumentParser()
    parser.add_argument('aniyomi_source', type=Path)
    patch(parser.parse_args().aniyomi_source)
